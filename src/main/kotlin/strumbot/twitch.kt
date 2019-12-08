package strumbot

import net.dv8tion.jda.api.utils.data.DataObject
import okhttp3.*
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime

class HttpException(route: String, status: Int, meaning: String)
    : Exception("$route > $status: $meaning")

class TwitchApi(
    val http: OkHttpClient,
    val scheduler: Scheduler,
    val clientId: String,
    val clientSecret: String) {

    private fun <T> makeRequest(request: Request, handler: (Response) -> T?): Mono<T> = Mono.create<T> { sink ->
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sink.error(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        sink.success(handler(response))
                    } else {
                        sink.error(HttpException(
                            response.request().url().toString(),
                            response.code(),
                            response.message()
                        ))
                    }
                }
            }
        })
    }.subscribeOn(scheduler)

    fun getStreamByLogin(login: String): Mono<Stream> = Mono.defer<Stream> {
        val request = Request.Builder()
            .addHeader("Client-ID", clientId)
            .url("https://api.twitch.tv/helix/streams?user_login=$login")
            .build()

        makeRequest(request) { response ->
            val json = DataObject.fromJson(response.body()!!.byteStream())
            val data = json.getArray("data")
            if (data.isEmpty) {
                null
            } else {
                val stream = data.getObject(0)
                Stream(
                    stream.getString("game_id"),
                    stream.getString("title"),
                    stream.getString("type"),
                    stream.getString("thumbnail_url"),
                    OffsetDateTime.parse(stream.getString("started_at"))
                )
            }
        }
    }

    fun getGame(stream: Stream): Mono<Game> = Mono.defer<Game> {
        val request = Request.Builder()
            .addHeader("Client-ID", clientId)
            .url("https://api.twitch.tv/helix/games?id=${stream.gameId}")
            .build()

        makeRequest(request) { response ->
            val json = DataObject.fromJson(response.body()!!.byteStream())
            val data = json.getArray("data")
            if (data.isEmpty) {
                null
            } else {
                val game = data.getObject(0)
                Game(
                    game.getString("id"),
                    game.getString("name")
                )
            }
        }
    }

    fun getThumbnail(stream: Stream, width: Int = 1920, height: Int = 1080): Mono<InputStream> = Mono.defer {
        val request = Request.Builder()
            .url(stream.thumbnail.replace("{width}", width.toString()).replace("{height}", height.toString()))
            .build()

        makeRequest(request) { response ->
            val buffer = ByteArrayOutputStream()
            response.body()!!.byteStream().copyTo(buffer)
            ByteArrayInputStream(buffer.toByteArray())
        }.subscribeOn(Schedulers.elastic())
    }
}

data class Stream(
    val gameId: String,
    val title: String,
    val type: String,
    val thumbnail: String,
    val startedAt: OffsetDateTime)
data class Game(val gameId: String, val name: String)
