package com.ryderdavid.voicebar.swipe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checked against real swipes from FUTO's swipe.futo.org test split (MIT): the resampling matches
 * the reference to float precision, and the search over the models' real output (run in ExecuTorch
 * on a Mac) gives the reference implementation's ranking, which is right for 29 of the 31.
 */
@RunWith(RobolectricTestRunner::class)
class SwipeDecoderTest {
    private val fixtures: List<JSONObject> = javaClass.classLoader!!.getResourceAsStream("swipe/fixtures.json")!!
        .bufferedReader().readText().let { JSONArray(it) }.let { a -> List(a.length()) { a.getJSONObject(it) } }

    private val lexicon by lazy { SwipeDecoder.Lexicon.parse(File("src/main/assets/words_en.txt").readLines().asSequence()) }

    private val params by lazy {
        val o = JSONObject(File("src/main/assets/swipe/scoring.json").readText()).getJSONObject(SwipeModels.SCORING_KEY)
        SwipeDecoder.Params(o.getDouble("gamma"), o.getDouble("lambda"), o.getDouble("beta"), o.getDouble("gamma_prune"), o.getDouble("beta_prune"))
    }

    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }

    @Test fun theSwipeIsResampledLikeTheReference() {
        for (f in fixtures) {
            val xs = f.getJSONArray("x"); val ys = f.getJSONArray("y"); val ts = f.getJSONArray("t")
            val pts = List(xs.length()) { SwipeDecoder.Point(xs.getDouble(it).toFloat(), ys.getDouble(it).toFloat(), ts.getLong(it)) }
            val got = SwipeDecoder.resample(pts)
            val want = f.getJSONArray("features").floats()
            for (i in want.indices) assertTrue(abs(got[i] - want[i]) < 1e-4, "${f.getString("word")} [$i] ${got[i]} vs ${want[i]}")
        }
    }

    @Test fun theSearchRanksWordsLikeTheReference() {
        var right = 0
        for (f in fixtures) {
            val flat = f.getJSONArray("logp").floats()
            val logp = Array(SwipeDecoder.T_OUT) { t -> FloatArray(27) { flat[t * 27 + it] } }
            val got = SwipeDecoder.search(logp, lexicon, params, 3).map { it.word }
            val want = f.getJSONArray("expect").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals(want, got, f.getString("word"))
            if (got.firstOrNull()?.replace("'", "").equals(f.getString("word").replace("'", ""), ignoreCase = true)) right++
        }
        assertEquals(29, right, "the right word first")
    }

    @Test fun theDictionaryTreeKeepsTheCommonerSpellingAndSkipsApostrophes() {
        val lex = SwipeDecoder.Lexicon(sequenceOf("don't" to 200, "dont" to 10, "I" to 255, "café" to 50))
        // café isn't a to z: left out; "I" is a one-letter word
        assertTrue(lex.nodes > 4)
        // d, o, n, t clearly at steps 4, 10, 16, 22; nothing (blank) everywhere else
        val logp = Array(SwipeDecoder.T_OUT) { t ->
            FloatArray(27) { c -> if (c == SwipeDecoder.BLANK) -0.01f else -20f }.also { r ->
                if (t % 6 == 4 && t < 28) { r["dont"[t / 6] - 'a'] = -0.01f; r[SwipeDecoder.BLANK] = -10f }
            }
        }
        val p = SwipeDecoder.Params(0.5, 0.01, 0.7, 0.19, 1.27)
        assertEquals("don't", SwipeDecoder.search(logp, lex, p, 1).first().word)
    }
}
