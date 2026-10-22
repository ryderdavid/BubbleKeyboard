package com.ryderdavid.voicebar.voice

/**
 * Formatting said aloud while dictating: "title case … end title case", "quote … end quote" and
 * "open paren … close paren" (each with a few ways to say it). Recognizing them is [mark]'s job:
 * the phrases become <title>, <quote> and <paren> tags in what was heard, which the cleanup keeps
 * where they are. [render] then turns the tags into the formatting, just before the text is typed.
 *
 * "Quote" and "parentheses" are ordinary words too, so quotes and parentheses only happen as a pair:
 * an opener with no closer after it (or a closer with no opener) goes back to being the words that
 * were said. Title case isn't said in passing, so it stays on until "end title case" or the end of the
 * dictation. "Literal quote" types the word "quote", whatever follows.
 */
object SpokenFormat {
    enum class Kind(val tag: String, val openWords: String, val closeWords: String) {
        TITLE("title", "title case", "end title case"),
        QUOTE("quote", "quote", "end quote"),
        PAREN("paren", "parentheses", "close parentheses"),
    }

    private class Phrase(val words: List<String>, val kind: Kind, val open: Boolean)

    private val PHRASES: List<Phrase> = buildList {
        fun add(kind: Kind, open: Boolean, vararg said: String) = said.forEach { add(Phrase(it.split(' '), kind, open)) }
        add(Kind.TITLE, true, "title case", "start title case", "begin title case")
        add(Kind.TITLE, false, "end title case", "stop title case", "close title case", "title case off", "end title")
        add(Kind.QUOTE, true, "quote", "open quote", "begin quote", "start quote", "open quotes", "quotes")
        add(Kind.QUOTE, false, "end quote", "close quote", "end of quote", "unquote", "end quotes", "close quotes")
        add(Kind.PAREN, true, "open paren", "open parenthesis", "open parentheses", "parentheses", "parenthesis", "paren", "open parens")
        add(Kind.PAREN, false, "close paren", "close parenthesis", "close parentheses", "end paren", "end parenthesis",
            "end parentheses", "close parens", "end parens")
    }.sortedByDescending { it.words.size } // the longest way of saying it wins ("end quote" before "quote")

    private val TAG = Regex("<(/?)(title|quote|paren)>")
    private val SPACES = Regex("[ \\t]{2,}")

    /** A token's word, for matching: lowercase, without the punctuation the recognizer put around it. */
    private fun core(token: String) = token.lowercase().trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }

    /** [heard] with the spoken formatting phrases as tags. Text that's already tagged passes through. */
    fun mark(heard: String): String {
        val tokens = heard.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return heard
        val out = StringBuilder()
        fun put(s: String) { if (out.isNotEmpty() && !out.endsWith(' ')) out.append(' '); out.append(s) }
        var i = 0
        while (i < tokens.size) {
            // "quote unquote" (so-called) is an expression, not formatting
            if (core(tokens[i]) == "quote" && i + 1 < tokens.size && core(tokens[i + 1]) == "unquote") {
                put(tokens[i]); put(tokens[i + 1]); i += 2
                continue
            }
            // "literal quote": the word itself
            if (core(tokens[i]) == "literal" && i + 1 < tokens.size) {
                val p = PHRASES.firstOrNull { matches(tokens, i + 1, it) }
                if (p != null) {
                    for (k in 0 until p.words.size) put(tokens[i + 1 + k])
                    i += 1 + p.words.size
                    continue
                }
            }
            val p = PHRASES.firstOrNull { matches(tokens, i, it) }
            if (p == null) { put(tokens[i]); i++; continue }
            val last = tokens[i + p.words.size - 1]
            if (p.open) {
                // the recognizer's comma or period after an opener isn't the speaker's; nor is its comma
                // before a parenthesis (before a quote, "she said, \"…\"" is right)
                if (p.kind == Kind.PAREN) while (out.isNotEmpty() && out.last() == ',') out.setLength(out.length - 1)
                put("<${p.kind.tag}>")
            } else {
                // a comma before a closer belongs to neither; whatever punctuation follows the closer stays
                while (out.isNotEmpty() && (out.last() == ',' || out.last() == ';')) out.setLength(out.length - 1)
                put("</${p.kind.tag}>")
                val tail = last.dropWhile { it.isLetterOrDigit() || it == '\'' || it == '’' }
                if (tail.isNotEmpty()) out.append(tail)
            }
            i += p.words.size
        }
        return out.toString()
    }

    private fun matches(tokens: List<String>, at: Int, p: Phrase): Boolean {
        if (at + p.words.size > tokens.size) return false
        for (k in p.words.indices) {
            val t = tokens[at + k]
            if (core(t) != p.words[k]) return false
            // inside a phrase the words run together: "end, quote" isn't a closer
            if (k < p.words.size - 1 && t.last() in ",.;:!?") return false
        }
        return true
    }

    /** The formatting kinds still open at the end of [tagged] (for the "Title Case" sign while dictating). */
    fun open(tagged: String): Set<Kind> {
        val depth = HashMap<Kind, Int>()
        for (m in TAG.findAll(tagged)) {
            val k = Kind.entries.first { it.tag == m.groupValues[2] }
            depth[k] = ((depth[k] ?: 0) + if (m.groupValues[1].isEmpty()) 1 else -1).coerceAtLeast(0)
        }
        return depth.filterValues { it > 0 }.keys
    }

    private sealed class Piece {
        class Text(val s: String) : Piece()
        class Open(val kind: Kind) : Piece()
        class Close(val kind: Kind) : Piece()
    }

    /**
     * [tagged] as it should read. [final]: the dictation is over, so quotes and parentheses that never
     * closed go back to words; while still dictating ([final] false) they show open, as said so far.
     */
    fun render(tagged: String, final: Boolean = true): String {
        if (!TAG.containsMatchIn(tagged)) return tagged
        // the tags in order, between the text
        val pieces = mutableListOf<Piece>()
        var at = 0
        for (m in TAG.findAll(tagged)) {
            if (m.range.first > at) pieces += Piece.Text(tagged.substring(at, m.range.first))
            val k = Kind.entries.first { it.tag == m.groupValues[2] }
            pieces += if (m.groupValues[1].isEmpty()) Piece.Open(k) else Piece.Close(k)
            at = m.range.last + 1
        }
        if (at < tagged.length) pieces += Piece.Text(tagged.substring(at))

        // pair them up: each closer with the latest opener of its kind; the rest go back to words
        val keep = BooleanArray(pieces.size)
        val stacks = HashMap<Kind, ArrayDeque<Int>>()
        for ((i, p) in pieces.withIndex()) when (p) {
            is Piece.Open -> stacks.getOrPut(p.kind) { ArrayDeque() }.addLast(i)
            is Piece.Close -> stacks[p.kind]?.removeLastOrNull()?.let { o ->
                // nothing between them ("quote unquote"): that's just the words
                val empty = pieces.subList(o + 1, i).all { it is Piece.Text && it.s.isBlank() }
                if (!empty) { keep[o] = true; keep[i] = true }
            }
            is Piece.Text -> keep[i] = true
        }
        // left open: title case runs to the end; quotes and parentheses only while still dictating
        for ((k, s) in stacks) for (o in s) if (k == Kind.TITLE || !final) keep[o] = true

        val out = StringBuilder()
        var glue = false // right after an opening mark: no space before the next words
        val titleFrom = ArrayDeque<Int>()
        fun trimEnd() { while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1) }
        // an opening mark gets a space before it, unless it follows another opening mark or starts a line
        fun spaceBeforeMark() { trimEnd(); if (out.isNotEmpty() && !glue && out.last() != '\n') out.append(' ') }
        for ((i, p) in pieces.withIndex()) {
            if (!keep[i]) {
                // back to the words that were said
                val words = when (p) { is Piece.Open -> p.kind.openWords; is Piece.Close -> p.kind.closeWords; else -> "" }
                if (out.isNotEmpty() && out.last() != ' ' && !glue) out.append(' ')
                out.append(words).append(' ')
                glue = false
                continue
            }
            when (p) {
                is Piece.Text -> {
                    var s = p.s
                    // a period said inside the quote and again after "end quote": once
                    val closed = out.length >= 2 && (out.last() == '"' || out.last() == ')')
                    if (closed && out[out.length - 2] in ".!?" && s.firstOrNull() in listOf('.', '!', '?')) s = s.drop(1)
                    if (glue) s = s.trimStart(' ')
                    else if (out.isNotEmpty() && (out.last() == '"' || out.last() == ')') && s.firstOrNull()?.isLetterOrDigit() == true) out.append(' ')
                    out.append(s)
                    if (s.isNotBlank()) glue = false
                }
                is Piece.Open -> when (p.kind) {
                    Kind.TITLE -> { titleFrom.addLast(out.length); }
                    Kind.QUOTE -> { spaceBeforeMark(); out.append('"'); glue = true }
                    Kind.PAREN -> { spaceBeforeMark(); out.append('('); glue = true }
                }
                is Piece.Close -> {
                    if (p.kind == Kind.TITLE) {
                        titleFrom.removeLastOrNull()?.let { from -> val t = titleCase(out.substring(from)); out.setLength(from); out.append(t) }
                    } else {
                        trimEnd()
                        while (out.isNotEmpty() && out.last() in ",;:") out.setLength(out.length - 1)
                        out.append(if (p.kind == Kind.QUOTE) '"' else ')')
                    }
                    glue = false
                }
            }
        }
        // title case left open runs to the end
        while (titleFrom.isNotEmpty()) { val from = titleFrom.removeLast(); val t = titleCase(out.substring(from)); out.setLength(from); out.append(t) }
        return out.toString().replace(SPACES, " ").trim()
    }

    /** Short words that stay lowercase inside a title (articles, conjunctions, short prepositions). */
    private val SMALL = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "for", "so", "yet", "as", "at", "by", "in", "of", "on", "per",
        "to", "via", "vs", "with", "from", "into", "onto", "upon", "off",
    )

    /**
     * [s] in title case: every word capitalized except [SMALL] words, which are lowercase unless
     * first, last or right after a colon. Words that already have capitals inside (iPhone, NASA) stay.
     */
    fun titleCase(s: String): String {
        val words = Regex("\\S+").findAll(s).toList()
        if (words.isEmpty()) return s
        val out = StringBuilder(s)
        for ((n, m) in words.withIndex()) {
            val w = m.value
            val after = n > 0 && words[n - 1].value.endsWith(":")
            val edge = n == 0 || n == words.lastIndex || after
            // hyphenated: each part capitalized except small words after the first (Up-to-Date)
            val fixed = w.split('-').mapIndexed { part, piece -> casePart(piece, part == 0 && edge) }.joinToString("-")
            out.replace(m.range.first, m.range.last + 1, fixed)
        }
        return out.toString()
    }

    private fun casePart(piece: String, forceCap: Boolean): String {
        val i = piece.indexOfFirst { it.isLetter() }
        if (i < 0) return piece
        val letters = piece.filter { it.isLetter() }
        // NASA, iPhone, McDonald: someone's own capitals
        if (letters.drop(1).any { it.isUpperCase() }) return piece
        val core = piece.lowercase().trim { !it.isLetter() && it != '\'' }
        return if (!forceCap && core in SMALL) piece.lowercase()
        else piece.substring(0, i) + piece[i].uppercaseChar() + piece.substring(i + 1).lowercase()
    }
}
