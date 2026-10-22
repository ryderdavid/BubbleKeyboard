package com.ryderdavid.voicebar.swipe

import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.pow

/**
 * Swipe typing's arithmetic, kept pure so it can be tested: the swipe resampled the way FUTO Swipe's
 * encoder expects it, and a beam search over the dictionary that turns the models' per-step letter
 * probabilities into words. Written from the FUTO Swipe technical report (arXiv 2606.25247: §2.1,
 * §4.1, Eq. 3, appendices B and J) and the model card; FUTO's own decoder library isn't used.
 *
 * The models' output is CTC: at each of 32 steps, a log-probability for each of the 26 letters (a to
 * z) and for "nothing here" (blank). A word's probability adds up every way its letters can be laid
 * along those steps, with blanks between and repeats collapsed. The search follows the dictionary's
 * tree, so only real words (and their beginnings) are ever considered.
 */
object SwipeDecoder {
    /** Points the encoder takes, and the steps it gives back. */
    const val T_IN = 64
    const val T_OUT = 32
    const val LETTERS = 26
    const val BLANK = 26
    const val MAX_KEYS = 64

    /** A point of the swipe, in the keyboard frame ([0, 1] across the letter rows), at [t] milliseconds. */
    class Point(val x: Float, val y: Float, val t: Long)

    /**
     * The swipe as the encoder takes it: resampled in time at 60 Hz, then to [T_IN] evenly spaced
     * points by linear interpolation. Returned as [x0..x63, y0..y63] (the [1, 2, 64] input).
     */
    fun resample(points: List<Point>): FloatArray {
        require(points.isNotEmpty())
        var xs = DoubleArray(points.size) { points[it].x.toDouble() }
        var ys = DoubleArray(points.size) { points[it].y.toDouble() }
        val ts = DoubleArray(points.size) { (points[it].t - points[0].t).toDouble() }
        val dur = ts.last()
        if (dur > 1e-3) {
            val n60 = max(2, (dur / (1000.0 / 60.0)).roundHalfEven().toInt() + 1)
            val tt = DoubleArray(n60) { dur * it / (n60 - 1) }
            xs = DoubleArray(n60) { interp(tt[it], ts, xs) }
            ys = DoubleArray(n60) { interp(tt[it], ts, ys) }
        }
        val idx = DoubleArray(xs.size) { it.toDouble() }
        val out = FloatArray(2 * T_IN)
        for (i in 0 until T_IN) {
            val p = (xs.size - 1).toDouble() * i / (T_IN - 1)
            out[i] = interp(p, idx, xs).toFloat()
            out[T_IN + i] = interp(p, idx, ys).toFloat()
        }
        return out
    }

    /** Like numpy's round: halves go to the even neighbor. */
    private fun Double.roundHalfEven(): Double = Math.rint(this)

    /** Linear interpolation of [fp] at [x] over increasing [xp], held flat beyond either end (numpy.interp). */
    private fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (x <= xp[0]) return fp[0]
        if (x >= xp.last()) return fp.last()
        var lo = 0
        var hi = xp.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (xp[mid] <= x) lo = mid else hi = mid }
        val span = xp[hi] - xp[lo]
        return if (span <= 0.0) fp[hi] else fp[lo] + (fp[hi] - fp[lo]) * (x - xp[lo]) / span
    }

    /** How words are ranked; the models' tuned values are in assets/swipe/scoring.json. */
    data class Params(
        /** Length normalization of the word's probability (γ). */
        val gamma: Double,
        /** Weight of the word's frequency, 0 to 255 (λ). */
        val lambda: Double,
        /** Bonus per letter (β). */
        val beta: Double,
        /** Pruning: how deep beginnings are favored while searching (γp, βp). */
        val gammaPrune: Double,
        val betaPrune: Double,
        val beam: Int = 100,
    )

    /**
     * The dictionary as a tree of letters (a to z): one node per word beginning, the word itself on
     * its last node. Apostrophes aren't swiped, so "dont" finds "don't". Frequencies are 0 to 255,
     * in proportion to the log of how common the word is.
     */
    class Lexicon(entries: Sequence<Pair<String, Int>>) {
        private var size = 1
        private var ch = ByteArray(1 shl 16).also { it[0] = -1 }
        private var firstChild = IntArray(1 shl 16) { -1 }
        private var nextSibling = IntArray(1 shl 16) { -1 }
        private var depth = IntArray(1 shl 16)
        private var word = arrayOfNulls<String>(1 shl 16)
        private var freq = IntArray(1 shl 16)

        init { for ((w, f) in entries) add(w, f) }

        val nodes get() = size

        private fun grow() {
            val n = ch.size * 2
            ch = ch.copyOf(n); firstChild = firstChild.copyOf(n).also { it.fill(-1, size, n) }
            nextSibling = nextSibling.copyOf(n).also { it.fill(-1, size, n) }
            depth = depth.copyOf(n); word = word.copyOf(n); freq = freq.copyOf(n)
        }

        private fun child(n: Int, c: Int): Int {
            var k = firstChild[n]
            while (k >= 0) { if (ch[k].toInt() == c) return k; k = nextSibling[k] }
            return -1
        }

        fun add(display: String, f: Int) {
            // a to z (and apostrophes) only: anything else can't be swiped
            if (display.any { it != '\'' && it != '’' && it.lowercaseChar() !in 'a'..'z' }) return
            var n = 0
            for (raw in display) {
                if (raw == '\'' || raw == '’') continue
                val c = raw.lowercaseChar() - 'a'
                var k = child(n, c)
                if (k < 0) {
                    if (size == ch.size) grow()
                    k = size++
                    ch[k] = c.toByte(); depth[k] = depth[n] + 1
                    nextSibling[k] = firstChild[n]; firstChild[n] = k
                }
                n = k
            }
            if (n != 0 && (word[n] == null || f > freq[n])) { word[n] = display; freq[n] = f }
        }

        internal fun letter(n: Int) = ch[n].toInt()
        internal fun depth(n: Int) = depth[n]
        internal fun word(n: Int) = word[n]
        internal fun freq(n: Int) = freq[n]
        internal inline fun forChildren(n: Int, f: (Int) -> Unit) { var k = firstChild[n]; while (k >= 0) { f(k); k = nextSibling[k] } }

        companion object {
            /** From "word<TAB>frequency" lines (assets/words_en.txt). */
            fun parse(lines: Sequence<String>) = Lexicon(lines.mapNotNull { l ->
                val tab = l.indexOf('\t')
                if (tab <= 0) null else l.substring(0, tab) to (l.substring(tab + 1).trim().toIntOrNull() ?: 1)
            })
        }
    }

    class Candidate(val word: String, val score: Double)

    private const val NEG = -1e30

    private fun lse(a: Double, b: Double): Double {
        val hi = max(a, b)
        val lo = if (hi == a) b else a
        return if (lo <= NEG) hi else hi + ln1p(exp(lo - hi))
    }

    /**
     * The words [logp] most likely spells, best first. [logp] is [T_OUT] rows of 27 log-probabilities
     * (a to z, then blank). Each step every kept beginning may stay (a blank, or its last letter
     * again) or grow by one letter its tree allows; the best [Params.beam] survive, deeper ones
     * favored so long words aren't crowded out. At the end, the beginnings that are whole words are
     * ranked by Eq. 3: their probability over length^γ, plus λ × frequency, plus β × length.
     */
    fun search(logp: Array<FloatArray>, lex: Lexicon, p: Params, n: Int = 5): List<Candidate> {
        var beams = HashMap<Int, DoubleArray>().apply { put(0, doubleArrayOf(0.0, NEG)) } // node -> [ends in blank, ends in a letter]
        for (row in logp) {
            val next = HashMap<Int, DoubleArray>(beams.size * 4)
            fun add(node: Int, pb: Double, pnb: Double) {
                val cur = next.getOrPut(node) { doubleArrayOf(NEG, NEG) }
                cur[0] = lse(cur[0], pb); cur[1] = lse(cur[1], pnb)
            }
            val blank = row[BLANK].toDouble()
            for ((node, s) in beams) {
                val tot = lse(s[0], s[1])
                add(node, tot + blank, NEG)
                val last = if (node == 0) -1 else lex.letter(node)
                if (node != 0) add(node, NEG, s[1] + row[last])
                lex.forChildren(node) { k ->
                    val c = lex.letter(k)
                    // the same letter twice needs a blank between, or it collapses into one
                    add(k, NEG, (if (c == last) s[0] else tot) + row[c])
                }
            }
            beams = if (next.size <= p.beam) next else HashMap<Int, DoubleArray>(p.beam * 2).also { keep ->
                next.entries.sortedByDescending { (node, s) ->
                    val d = lex.depth(node)
                    lse(s[0], s[1]) / max(d, 1).toDouble().pow(p.gammaPrune) + p.betaPrune * d
                }.take(p.beam).forEach { keep[it.key] = it.value }
            }
        }
        return beams.mapNotNull { (node, s) ->
            val w = lex.word(node) ?: return@mapNotNull null
            val len = lex.depth(node)
            Candidate(w, lse(s[0], s[1]) / len.toDouble().pow(p.gamma) + p.lambda * lex.freq(node) + p.beta * len)
        }.sortedByDescending { it.score }.take(n)
    }
}
