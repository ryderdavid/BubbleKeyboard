package com.ryderdavid.voicebar.swipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole swipe pipeline on real Android: the models out of the APK into ExecuTorch (with the
 * boolean key mask), then Voicebar's decoder, on 31 real swipes from FUTO's swipe.futo.org test
 * split (MIT) laid out on its QWERTY layout. The Mac reference gets 29 of them right.
 */
@RunWith(AndroidJUnit4::class)
class SwipeOnDeviceTest {
    // swipe.futo.org's QWERTY key centers (swipe-5/layouts/qwerty.json), a to z
    private val qwerty = mapOf(
        'q' to (0.05f to 1 / 6f), 'w' to (0.15f to 1 / 6f), 'e' to (0.25f to 1 / 6f), 'r' to (0.35f to 1 / 6f), 't' to (0.45f to 1 / 6f),
        'y' to (0.55f to 1 / 6f), 'u' to (0.65f to 1 / 6f), 'i' to (0.75f to 1 / 6f), 'o' to (0.85f to 1 / 6f), 'p' to (0.95f to 1 / 6f),
        'a' to (0.10046729f to 0.5f), 's' to (0.20046729f to 0.5f), 'd' to (0.30046729f to 0.5f), 'f' to (0.40046729f to 0.5f), 'g' to (0.50046729f to 0.5f),
        'h' to (0.60046729f to 0.5f), 'j' to (0.70046729f to 0.5f), 'k' to (0.80046729f to 0.5f), 'l' to (0.90046729f to 0.5f),
        'z' to (0.20046729f to 5 / 6f), 'x' to (0.30046729f to 5 / 6f), 'c' to (0.40046729f to 5 / 6f), 'v' to (0.50046729f to 5 / 6f),
        'b' to (0.60046729f to 5 / 6f), 'n' to (0.70046729f to 5 / 6f), 'm' to (0.80046729f to 5 / 6f),
    )

    @Test fun realSwipesDecodeOnAndroid() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val models = SwipeModels.load(target)
        val lexicon = SwipeDecoder.Lexicon.parse(target.assets.open("words_en.txt").bufferedReader().lineSequence())
        val keys = FloatArray(52).also { k -> for ((c, xy) in qwerty) { k[2 * (c - 'a')] = xy.first; k[2 * (c - 'a') + 1] = xy.second } }
        val fixtures = InstrumentationRegistry.getInstrumentation().context.assets.open("swipe_fixtures.json").bufferedReader().readText().let { JSONArray(it) }
        var right = 0
        var slowest = 0L
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val xs = f.getJSONArray("x"); val ys = f.getJSONArray("y"); val ts = f.getJSONArray("t")
            val pts = List(xs.length()) { SwipeDecoder.Point(xs.getDouble(it).toFloat(), ys.getDouble(it).toFloat(), ts.getLong(it)) }
            val t0 = System.nanoTime()
            val got = models.decode(pts, keys, lexicon, 3).map { it.word }
            slowest = maxOf(slowest, (System.nanoTime() - t0) / 1_000_000)
            val want = f.getJSONArray("expect").getString(0)
            if (got.firstOrNull() == want) right++
            android.util.Log.i("SwipeOnDeviceTest", "${f.getString("word")}: $got")
        }
        android.util.Log.i("SwipeOnDeviceTest", "matches the reference on $right of ${fixtures.length()}; slowest ${slowest} ms")
        assertTrue("matches the Mac reference on $right of ${fixtures.length()}", right >= fixtures.length() - 1)
    }
}
