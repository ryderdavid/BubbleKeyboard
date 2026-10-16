package com.ryderdavid.bubblekeyboard.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks an API key with its service before it's saved, without spending anything: Anthropic by
 * listing models, ElevenLabs by opening a speech session with no audio and closing it. Blocking.
 */
object KeyChecks {
    sealed class Result {
        data object Ok : Result()
        /** The service turned the key down: [why] says how, in its words when it gave any. */
        data class Rejected(val why: String) : Result()
        /** Couldn't reach the service (offline, timeout). */
        data class Unreachable(val why: String) : Result()
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    fun anthropic(key: String, baseUrl: String = "https://api.anthropic.com", http: OkHttpClient = client): Result {
        val req = Request.Builder().url("$baseUrl/v1/models?limit=1")
            .header("x-api-key", key.trim()).header("anthropic-version", "2023-06-01").get().build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> Result.Ok
                    r.code == 401 -> Result.Rejected("Anthropic didn’t accept that key")
                    r.code == 403 -> Result.Rejected(message(r.body?.string()) ?: "That key isn’t allowed to use Claude")
                    else -> Result.Unreachable("Anthropic answered ${r.code}; try again")
                }
            }
        }.getOrElse { Result.Unreachable("Couldn’t reach Anthropic: check your connection") }
    }

    fun elevenLabs(key: String, baseUrl: String = "wss://api.elevenlabs.io", http: OkHttpClient = client): Result {
        val done = CountDownLatch(1)
        var result: Result = Result.Unreachable("Couldn’t reach ElevenLabs: check your connection")
        val req = Request.Builder().url(ElevenLabsProtocol.url(baseUrl)).header("xi-api-key", key.trim()).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // accepted at the door; give it a moment to send an error (a key without speech-to-text access)
                result = Result.Ok
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = ElevenLabsProtocol.parse(text)
                result = if (e is ElevenLabsProtocol.Event.Error) Result.Rejected(ElevenLabsProtocol.describe(e).let {
                    if (e.type.contains("auth")) "ElevenLabs didn’t accept that key" else e.message.ifBlank { it }
                }) else Result.Ok
                if (e !is ElevenLabsProtocol.Event.Partial) done.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result = when (response?.code) {
                    401 -> Result.Rejected("ElevenLabs didn’t accept that key")
                    403 -> Result.Rejected(message(runCatching { response.body?.string() }.getOrNull()) ?: "That key doesn’t have Speech to Text access")
                    null -> if (result == Result.Ok) result else Result.Unreachable("Couldn’t reach ElevenLabs: check your connection")
                    else -> Result.Unreachable("ElevenLabs answered ${response.code}; try again")
                }
                done.countDown()
            }
        })
        done.await(4, TimeUnit.SECONDS)
        ws.close(1000, null)
        return result
    }

    /** The error message in a JSON error body ({"error":{"message":…}} or {"detail":{"message":…}}), if any. */
    fun message(body: String?): String? = runCatching {
        val j = JSONObject(body ?: return null)
        (j.optJSONObject("error") ?: j.optJSONObject("detail"))?.optString("message")?.ifBlank { null } ?: j.optString("detail").ifBlank { null }
    }.getOrNull()
}
