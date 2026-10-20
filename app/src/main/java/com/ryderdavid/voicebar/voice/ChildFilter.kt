package com.ryderdavid.voicebar.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Ignore children's voices": a small child's voice sits more than an octave above an adult
 * man's (about 250–400 Hz against 85–155 Hz), so the pitch of what's being said tells them apart
 * with no voiceprint and no model. Audio goes through a short-time Fourier transform (64 ms
 * frames every 16 ms); each frame's pitch is the lowest one whose harmonics stand out, judged
 * over the last few frames, and a stretch that's only a child (no adult heard just before) is
 * replaced with silence, held a little past the child's last voiced sound to cover consonants.
 * About 50 ms of delay. Speech over the child's at the same moment passes as it is.
 *
 * Tested on synthetic adult and child voices through Parakeet: a child talking alone or between
 * the adult's words left no words behind (from 28–29), and the adult's own words were unchanged.
 * Single-threaded.
 */
class ChildFilter(
    /** Pitches at or above this (Hz) count as a child: the strength setting. */
    private val childMin: Float = 220f,
    private val sampleRate: Int = 16000,
) {
    private val win = FloatArray(N) { sqrt((0.5 - 0.5 * cos(2 * PI * it / N)).toFloat()) }
    private val grid = pitchGrid(ADULT_MIN, CHILD_MAX)
    private val adultGrid = pitchGrid(ADULT_MIN, ADULT_MAX)
    private val df = sampleRate.toFloat() / N
    private val loBin = (80f / df).toInt() + 1
    private val hiBin = (FMAX / df).toInt()
    /** About -50 dBFS of speech-band energy in a frame: too quiet to be someone talking at the phone. */
    private val quiet = run { val a = 0.00316f * N / 4; a * a }

    private val inBuf = FloatArray(N)
    private val outBuf = FloatArray(N)
    private val pending = FloatArrayBuilder()
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val mag = FloatArray(N / 2 + 1)
    private val c = FloatArray(N / 2 + 1)
    private val residual = FloatArray(N / 2 + 1)
    private val doms = ArrayDeque<Float>()
    private val recentAdult = ArrayDeque<Boolean>()
    private val recentChild = ArrayDeque<Boolean>()
    private var gate = 1f
    private var hang = 0

    /** What the last frame was judged to be, for tests and the settings check. */
    var lastAdult = false; private set
    var lastChild = false; private set
    /** Frames silenced so far. */
    var silencedFrames = 0; private set

    /** Feed any number of samples; get as many back (the first ~48 ms are the delay's silence). */
    fun process(samples: FloatArray): FloatArray {
        pending.add(samples)
        val out = FloatArray((pending.size / HOP) * HOP)
        var o = 0
        while (pending.size >= HOP) {
            val hop = pending.take(HOP)
            inBuf.copyInto(inBuf, 0, HOP, N)
            hop.copyInto(inBuf, N - HOP)
            frame()
            outBuf.copyInto(out, o, 0, HOP); o += HOP
            outBuf.copyInto(outBuf, 0, HOP, N)
            outBuf.fill(0f, N - HOP, N)
        }
        return out
    }

    private fun frame() {
        for (i in 0 until N) { re[i] = inBuf[i] * win[i]; im[i] = 0f }
        fft(re, im, false)
        for (k in 0..N / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        val (adult, child) = analyze()
        lastAdult = adult; lastChild = child
        push(recentAdult, adult, 8); push(recentChild, child && !adult, 5)
        val sureChild = recentChild.count { it } >= 4 && recentAdult.none { it }
        var target = 1f
        when {
            adult -> hang = 0
            sureChild -> { hang = HANG; target = 0f }
            hang > 0 -> { target = 0f; hang-- }
        }
        gate += (target - gate) * (if (target < gate) 0.6f else 0.3f)
        if (gate < 0.5f) silencedFrames++
        if (gate < 0.999f) {
            for (k in 0 until N) { re[k] *= gate; im[k] *= gate }
        }
        fft(re, im, true)
        for (i in 0 until N) outBuf[i] += re[i] * win[i] / 2f // sqrt-Hann in and out at 75% overlap sums to 2
    }

    /** (an adult is talking, a child is talking) in this frame. */
    private fun analyze(): Pair<Boolean, Boolean> {
        var energy = 0f
        var sum = 0f
        for (k in loBin until hiBin) { energy += mag[k] * mag[k]; sum += sqrt(mag[k]) }
        energy /= (hiBin - loBin)
        if (energy < quiet) return false to false
        val norm = sum / (hiBin - loBin) + 1e-9f
        for (k in mag.indices) c[k] = sqrt(mag[k]) / norm
        val s = FloatArray(grid.size) { salience(c, grid[it]) }
        val top = s.max()
        if (top < VOICED) return false to false
        // the voice's pitch: the lowest strong peak (its multiples score about as high; its halves don't)
        var dom = grid[s.indices.maxBy { s[it] }]
        for (i in s.indices) {
            if (s[i] >= 0.8f * top && (i == 0 || s[i] >= s[i - 1]) && (i == s.lastIndex || s[i] >= s[i + 1])) { dom = grid[i]; break }
        }
        // a high "pitch" that's really an adult's 2nd–6th harmonic standing out (a bright vowel):
        // the adult's own pitch still scores well, which a child's half or third never does
        if (dom >= childMin) {
            for (k in 2..6) {
                val sub = salience(c, dom / k)
                if (dom / k >= ADULT_MIN && sub >= 0.5f * top) { dom /= k; break }
            }
        }
        // a voice's pitch wanders: judge it by the last few frames, so a child's dips don't read as adult
        push(doms, dom, 5)
        val pitch = doms.sorted()[doms.size / 2]
        if (pitch < childMin) return true to false
        // a voice's pitch glides; one that jumps about from frame to frame is a hiss or a click, not a child
        // (an octave slip, the pitch doubled or halved for a frame, is still the same voice)
        val folded = doms.map { var d = it; while (d > 1.45f * pitch) d /= 2; while (d < 0.7f * pitch) d *= 2; d }
        if (doms.size < 3 || folded.max() / folded.min() > 1.3f) return false to false
        // a child: is there an adult underneath? (the child's harmonics taken out)
        strip(dom)
        var best = 0f
        var bestF = 0f
        for (f in adultGrid) { val v = salience(residual, f); if (v > best) { best = v; bestF = f } }
        val adultUnder = best > VOICED && bestF < 0.75f * childMin && !related(dom, bestF)
        return adultUnder to true
    }

    /** How strongly [f0]'s harmonics stand out: their peaks minus the troughs between them. */
    private fun salience(spec: FloatArray, f0: Float): Float {
        val h = (FMAX / f0).toInt()
        var tot = 0f
        for (n in 1..h) {
            val b = n * f0 / df
            val lo = floor(b * 0.975f).toInt()
            val hi = min(spec.lastIndex, ceil(b * 1.025f).toInt())
            var peak = 0f
            for (k in lo..hi) if (spec[k] > peak) peak = spec[k]
            val t1 = spec[min(spec.lastIndex, ((n - 0.5f) * f0 / df).roundToInt())]
            val t2 = spec[min(spec.lastIndex, ((n + 0.5f) * f0 / df).roundToInt())]
            tot += peak - 0.5f * (t1 + t2)
        }
        return tot / max(1, h)
    }

    /** [residual] = [c] with [f0]'s harmonics flattened to the median. */
    private fun strip(f0: Float) {
        c.copyInto(residual)
        val med = c.copyOfRange(loBin, hiBin).also { it.sort() }.let { it[it.size / 2] }
        var n = 1
        while (n * f0 < FMAX + f0) {
            val b = n * f0 / df
            val lo = max(0, (b * 0.965f).toInt() - 1)
            val hi = min(residual.lastIndex, ceil(b * 1.035f).toInt() + 1)
            for (k in lo..hi) residual[k] = med
            n++
        }
    }

    companion object {
        const val N = 1024
        const val HOP = 256
        const val FMAX = 3800f
        const val ADULT_MIN = 70f
        const val ADULT_MAX = 175f
        const val CHILD_MAX = 560f
        const val VOICED = 0.55f
        /** Frames (~220 ms) the silence holds after the child's voice, for the consonants. */
        const val HANG = 14

        /** Strength 1..5 → the pitch (Hz) from which a voice counts as a child's. */
        val STRENGTH_RANGE = 1..5
        const val DEFAULT_STRENGTH = 3
        fun childMin(strength: Int): Float = when (strength.coerceIn(STRENGTH_RANGE)) {
            1 -> 260f; 2 -> 240f; 3 -> 220f; 4 -> 205f; else -> 190f
        }

        fun pitchGrid(lo: Float, hi: Float): FloatArray {
            val out = ArrayList<Float>()
            var f = lo
            while (f <= hi) { out += f; f *= 1.02f }
            return out.toFloatArray()
        }

        /** f is (close to) a whole multiple of g. */
        fun related(f: Float, g: Float, tol: Float = 0.04f): Boolean {
            val k = (f / g).roundToInt()
            return k >= 1 && abs(f - k * g) <= tol * f
        }

        private fun <T> push(q: ArrayDeque<T>, v: T, n: Int) { q.addLast(v); while (q.size > n) q.removeFirst() }

        /** In-place radix-2 FFT (inverse scaled by 1/n). */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }

        @Suppress("unused") private fun log2(x: Float) = ln(x) / ln(2f)
    }
}

/** [ChildFilter] on 16-bit PCM. */
class ChildFilterPcm(childMin: Float) {
    val filter = ChildFilter(childMin)
    fun process(pcm: ByteArray): ByteArray = toPcm(filter.process(pcmToFloats(pcm)))

    /** What's still in the filter's delay (the last ~50 ms), pushed out with silence. */
    fun flush(): ByteArray = toPcm(filter.process(FloatArray(ChildFilter.N)))

    private fun toPcm(y: FloatArray): ByteArray {
        val out = ByteArray(y.size * 2)
        for (i in y.indices) {
            val v = (y[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
