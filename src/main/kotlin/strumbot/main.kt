/*
 * Copyright 2019-2020 Florian Spieß and the Strumbot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Main")
package strumbot

import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.then
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.exceptions.HierarchyException
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.utils.cache.CacheFlag
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.EnumSet.noneOf
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("Main") as Logger

private val pool = Executors.newScheduledThreadPool(2) {
    thread(start=false, name="Worker-Thread", isDaemon=true, block=it::run)
}

private val poolScheduler = Schedulers.fromExecutor(pool)

fun main() {
    val configuration = loadConfiguration("config.json")
    val okhttp = OkHttpClient()
    val twitch = TwitchApi(
        okhttp, poolScheduler,
        configuration.twitchClientId, configuration.twitchClientSecret
    )

    val manager = createManager {
        this.scheduler = poolScheduler
    }

    val jda = JDABuilder(configuration.token)
        .setEventManager(manager)
        .setHttpClient(okhttp)
        .setEnabledCacheFlags(noneOf(CacheFlag::class.java))
        .setGuildSubscriptionsEnabled(false)
        .setCallbackPool(pool)
        .setGatewayPool(pool)
        .setRateLimitPool(pool)
        .build()

    setupRankListener(jda, configuration)
    // Optional message logging
    configuration.messageLogs?.let { messageWebhook ->
        MessageLogger(messageWebhook, pool, jda)
    }

    jda.awaitReady()
    StreamWatcher(twitch, jda, configuration).run(pool, poolScheduler)
}

private fun setupRankListener(jda: JDA, configuration: Configuration) {
    jda.on<GuildMessageReceivedEvent>()
        .map { it.message }
        .filter { it.member != null }
        .filter { it.contentRaw.startsWith("?rank ") }
        .flatMap { event ->
            val member = event.member!!
            val mention = member.asMention
            // The role name is after the command
            val roleName = event.contentRaw.removePrefix("?rank ").toLowerCase()
            // We shouldn't let users assign themselves any other roles like mod
            val channel = event.channel
            if (roleName !in configuration.ranks) {
                return@flatMap channel.sendMessage("$mention, That role does not exist!").asMono()
            }

            // Check if role by that name exists
            val role = event.guild.getRolesByName(roleName, false).firstOrNull()
            Mono.defer {
                if (role != null) {
                    // Add/Remove the role to the member and send a success message
                    toggleRole(member, role, event, channel, mention)
                } else {
                    // Send a failure message, unknown role
                    channel.sendMessage("$mention I don't know that role!").asMono()
                }
            }.doOnError(PermissionException::class.java) { error ->
                handlePermissionError(error, channel, mention, role)
            }
        }
        .onErrorContinue { t, _ -> log.error("Rank service encountered exception", t) }
        .subscribe()
}

private fun toggleRole(
    member: Member,
    role: Role,
    event: Message,
    channel: MessageChannel,
    mention: String
): Mono<Message> {
    return if (member.roles.any { it.idLong == role.idLong }) {
        log.debug("Adding ${role.name} to ${member.user.asTag}")
        event.guild.removeRoleFromMember(member, role).asMono().then {
            channel.sendMessage("$mention, you left **${role.name}**.").asMono()
        }
    } else {
        log.debug("Removing ${role.name} from ${member.user.asTag}")
        event.guild.addRoleToMember(member, role).asMono().then {
            channel.sendMessage("$mention, you joined **${role.name}**.").asMono()
        }
    }
}

private fun handlePermissionError(
    error: PermissionException,
    channel: MessageChannel,
    mention: String,
    role: Role?
) {
    if (error.permission == Permission.MESSAGE_WRITE || error.permission == Permission.MESSAGE_READ)
        return // Don't attempt to send another message if it already failed because of it
    when (error) {
        is InsufficientPermissionException ->
            channel.sendMessage("$mention, I'm missing the permission **${error.permission.getName()}**").queue()
        is HierarchyException ->
            channel.sendMessage("$mention, I can't assign a role to you because the role is too high! Role: ${role?.name}").queue()
        else ->
            channel.sendMessage("$mention, encountered an error: `$error`!").queue()
    }
}