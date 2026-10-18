package com.ryderdavid.bubblekeyboard.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChildFilterTest {
    private val rate = 16000

    /** A recording from test resources (macOS voices: Daniel as the adult; Samantha raised to a child's pitch and size). */
    private fun wav(name: String): FloatArray {
        val b = javaClass.getResourceAsStream("/voices/$name.wav")!!.readBytes()
        var i = 12
        while (i + 8 < b.size) {
            val id = String(b, i, 4)
            val len = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") return normalize(pcmToFloats(b.copyOfRange(i + 8, minOf(b.size, i + 8 + len))))
            i += 8 + len + (len and 1)
        }
        error("no data in $name")
    }

    private fun normalize(x: FloatArray, rms: Float = 0.1f): FloatArray {
        val r = sqrt(x.map { it * it }.average()).toFloat()
        return FloatArray(x.size) { x[it] / r * rms }
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).map { (x[it] * x[it]).toDouble() }.average()).toFloat()

    /** Through the filter in 100 ms chunks, as the microphone sends it, then flushed. */
    private fun run(x: FloatArray, f: ChildFilter = ChildFilter()): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < x.size) { val n = minOf(1600, x.size - i); f.process(x.copyOfRange(i, i + n)).forEach { out += it }; i += n }
        f.process(FloatArray(ChildFilter.N)).forEach { out += it }
        return out.toFloatArray()
    }

    private val delay = ChildFilter.N - ChildFilter.HOP

    /** For listening, or for running through a recognizer: build/childfilter/[name].wav. */
    private fun save(name: String, x: FloatArray) {
        val dir = java.io.File("build/childfilter").apply { mkdirs() }
        val data = ByteArray(x.size * 2)
        for (i in x.indices) { val v = (x[i] * 32768).toInt().coerceIn(-32768, 32767); data[2 * i] = v.toByte(); data[2 * i + 1] = (v shr 8).toByte() }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
        java.io.File(dir, "$name.wav").writeBytes(h.array() + data)
    }

    @Test fun overlapsForListening() {
        val a = wav("adult2"); val k = wav("kid2")
        for ((tag, gain) in listOf("0dB" to 1f, "minus6dB" to 0.5f)) {
            val x = a.copyOf()
            for (i in k.indices) if (i + 9600 < x.size) x[i + 9600] += k[i] * gain
            save("overlap-$tag-in", x); save("overlap-$tag-filtered", run(x))
        }
    }

    @Test fun anAdultPassesUnchanged() {
        for (n in listOf("adult1", "adult2")) {
            val x = wav(n)
            val f = ChildFilter()
            val y = run(x, f)
            assertEquals(0, f.silencedFrames, n)
            // the same audio, ~48 ms later
            var err = 0.0
            for (i in x.indices) { val d = y[i + delay] - x[i]; err += d * d }
            assertTrue(sqrt(err / x.size) < 0.01 * rms(x), "$n reconstruction")
        }
    }

    @Test fun aChildIsSilenced() {
        for (n in listOf("kid1", "kid2")) {
            val x = wav(n)
            val y = run(x)
            assertTrue(rms(y) < 0.3f * rms(x), "$n: ${rms(y)} vs ${rms(x)}") // under a tenth of the energy left
            save("$n-filtered", y)
        }
    }

    @Test fun turnsKeepTheAdult() {
        val a = wav("adult1"); val k = wav("kid1")
        val half = a.size / 2
        val x = a.copyOfRange(0, half) + FloatArray(4800) + k + FloatArray(4800) + a.copyOfRange(half, a.size)
        val y = run(x)
        save("turns-in", x); save("turns-filtered", y)
        val kidFrom = half + 4800 + delay; val kidTo = kidFrom + k.size
        assertTrue(rms(y, delay, half) > 0.9f * rms(a, 0, half), "first half kept")
        assertTrue(rms(y, kidFrom + 1600, kidTo) < 0.4f * rms(k), "child silenced: ${rms(y, kidFrom + 1600, kidTo)} vs ${rms(k)}")
        assertTrue(rms(y, kidTo + 4800 + 1600, kidTo + 4800 + a.size - half) > 0.8f * rms(a, half, a.size), "second half kept")
    }

    @Test fun pureTones() {
        // a buzz at an adult's pitch passes; the same at a child's pitch doesn't
        fun buzz(f0: Double, s: Double) = FloatArray((s * rate).toInt()) { t -> (1..12).sumOf { h -> sin(2 * PI * f0 * h * t / rate) / h }.toFloat() * 0.05f }
        val f1 = ChildFilter(); run(buzz(115.0, 1.0), f1)
        assertEquals(0, f1.silencedFrames)
        val f2 = ChildFilter(); run(buzz(300.0, 1.0), f2)
        assertTrue(f2.silencedFrames > 40, "${f2.silencedFrames}")
    }

    @Test fun strengthMovesTheLine() {
        assertTrue(ChildFilter.childMin(1) > ChildFilter.childMin(3))
        assertTrue(ChildFilter.childMin(5) < ChildFilter.childMin(3))
        assertTrue(ChildFilter.childMin(5) > ChildFilter.ADULT_MAX)
    }

    @Test fun fftRoundTrips() {
        val re = FloatArray(1024) { sin(it * 0.3f) + 0.2f * it % 7 }; val im = FloatArray(1024)
        val copy = re.copyOf()
        ChildFilter.fft(re, im, false); ChildFilter.fft(re, im, true)
        for (i in re.indices) assertEquals(copy[i], re[i], 1e-3f)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class ChildCleanupTest {
    @Test fun cleanupIsToldOnlyWhenOn() {
        val on = org.json.JSONObject(ClaudeCleanupRequest.body("", "send it daddy snack tonight", "m", children = true)).getString("system")
        val off = org.json.JSONObject(ClaudeCleanupRequest.body("", "send it tonight", "m")).getString("system")
        assertTrue(on.contains("child cutting in"))
        assertTrue(!off.contains("child cutting in"))
    }
}
