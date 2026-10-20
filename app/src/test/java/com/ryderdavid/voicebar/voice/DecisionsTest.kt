package com.ryderdavid.voicebar.voice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DecisionsTest {
    @Test fun asksOnePredicateAboutTheDictation() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"answers":[{"type":"predicate","name":"message","probability":0.12}],"usage":{"input_tokens":180,"output_tokens":0}}"""))
        server.start()
        var billed = 0L
        val p = Decisions.ask(OkHttpClient(), "k", Decisions.input("Messages", "Siona: are we still on?", "yeah so anyway did you see the game last night"),
            server.url("").toString().trimEnd('/')) { _, i, _ -> billed = i }
        val req = server.takeRequest()
        server.shutdown()
        assertEquals("/v1/decisions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(Decisions.MODEL, body.getString("model"))
        assertEquals("predicate", body.getJSONArray("questions").getJSONObject(0).getString("type"))
        assertTrue(body.getString("input").contains("App: Messages") && body.getString("input").contains("did you see the game"))
        assertEquals(0.12, p)
        assertEquals(180L, billed)
    }

    @Test fun aRefusalOrErrorIsNoVerdict() {
        assertNull(Decisions.probability("""{"answers":[{"type":"refusal","name":"message"}]}"""))
        assertNull(Decisions.probability("not json"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"no access"}}"""))
        server.start()
        assertNull(Decisions.ask(OkHttpClient(), "k", "x", server.url("").toString().trimEnd('/')))
        server.shutdown()
    }
}
