package com.ryderdavid.voicebar.voice

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class KeyChecksTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun up() { server = MockWebServer(); server.start() }
    // the probe hangs up without waiting for the server's goodbye; don't fail on a slow close
    @After fun down() { runCatching { server.shutdown() } }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun anthropicKeys() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        assertEquals(KeyChecks.Result.Ok, KeyChecks.anthropic("sk-ant-good", base(), http))
        val req = server.takeRequest()
        assertEquals("sk-ant-good", req.getHeader("x-api-key"))
        assertEquals("/v1/models?limit=1", req.path)

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertEquals(KeyChecks.Result.Rejected("Anthropic didn’t accept that key"), KeyChecks.anthropic("bad", base(), http))

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"type":"error","error":{"type":"permission_error","message":"Your key can't use this workspace"}}"""))
        assertEquals(KeyChecks.Result.Rejected("Your key can't use this workspace"), KeyChecks.anthropic("k", base(), http))

        server.enqueue(MockResponse().setResponseCode(529))
        assertIs<KeyChecks.Result.Unreachable>(KeyChecks.anthropic("k", base(), http))
    }

    @Test fun elevenLabsAccepted() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"message_type":"session_started","session_id":"x"}""") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        assertEquals(KeyChecks.Result.Ok, KeyChecks.elevenLabs("sk_good", base().replace("http", "ws"), http))
        assertEquals("sk_good", server.takeRequest().getHeader("xi-api-key"))
    }

    @Test fun elevenLabsRejected() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(KeyChecks.Result.Rejected("ElevenLabs didn’t accept that key"), KeyChecks.elevenLabs("bad", base().replace("http", "ws"), http))
    }

    @Test fun elevenLabsWithoutSpeechAccess() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"message_type":"error","error":"This API key is missing the speech_to_text permission"}""")
            }
        }))
        val r = KeyChecks.elevenLabs("sk_tts_only", base().replace("http", "ws"), http)
        assertIs<KeyChecks.Result.Rejected>(r)
    }
}
