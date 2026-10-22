package com.ryderdavid.voicebar.voice

/**
 * Fitting a dictation into text already in the field (the user went back to fix something): its
 * first letter and closing punctuation match the sentence it lands in, and a leftover word at either
 * seam goes, so the sentence reads as one. The cleanup model does most of this; this checks its work
 * and covers dictations with no cleanup at all.
 */
object Infill {
    /** Words the cleanup says are leftovers right at the cursor: the end of the text before it, the start of the text after it. */
    data class Seam(val removeBefore: String = "", val removeAfter: String = "")

    /** The dictation as it should go in, what to take out of the field on either side of it, and what changed (for the log). */
    data class Fit(val text: String, val removeBefore: String = "", val removeAfter: String = "", val notes: List<String> = emptyList())

    /** At most this many leftover words come out of the field on either side. */
    const val MAX_REMOVED_WORDS = 3

    private val REMOVE = Regex("""<remove_(before|after)>(.*?)</remove_\1>""", RegexOption.DOT_MATCHES_ALL)
    private val WORD = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’]*""")

    /** The leftovers a cleanup reply names in <remove_before> and <remove_after>, if any. */
    fun seam(reply: String?): Seam {
        if (reply == null) return Seam()
        var before = ""
        var after = ""
        for (m in REMOVE.findAll(reply)) if (m.groupValues[1] == "before") before = m.groupValues[2].trim() else after = m.groupValues[2].trim()
        return Seam(before, after)
    }

    /** [reply] without those tags, so they never get typed. */
    fun stripSeam(reply: String): String = REMOVE.replace(reply, "")

    /**
     * [text] fitted between [around]'s two sides, taking [seam]'s leftovers only when they're
     * really there: the last words before the cursor, or the first after it, and few of them.
     */
    fun fit(text: String, around: Around?, seam: Seam = Seam()): Fit {
        if (around == null || text.isBlank()) return Fit(text)
        val notes = mutableListOf<String>()
        var t = text.trim()
        var before = around.before
        var after = around.after
        var removeBefore = ""
        var removeAfter = ""

        // the leftovers the cleanup named: exactly at the seam, whole words, a few at most
        seam.removeBefore.takeIf { leftoverOk(it) }?.let { w ->
            val b = before.trimEnd(' ', '\t')
            if (b.endsWith(w) && (b.length == w.length || !b[b.length - w.length - 1].isLetterOrDigit())) {
                removeBefore = w
                before = b.dropLast(w.length)
                notes += "removed \"$w\" before"
            }
        }
        seam.removeAfter.takeIf { leftoverOk(it) }?.let { w ->
            val a = after.trimStart(' ', '\t')
            if (a.startsWith(w) && (a.length == w.length || !a[w.length].isLetterOrDigit())) {
                removeAfter = w
                after = a.drop(w.length)
                notes += "removed \"$w\" after"
            }
        }

        // a word said again across the seam: the field's copy stays, the dictation's goes
        val lastBefore = WORD.findAll(before.takeLast(60)).lastOrNull()?.takeIf { before.trimEnd(' ', '\t').endsWith(it.value) }?.value
        WORD.find(t)?.takeIf { it.range.first == 0 }?.let { first ->
            if (lastBefore != null && first.value.equals(lastBefore, ignoreCase = true) && first.value.lowercase() !in DOUBLES &&
                t.length > first.value.length && t[first.value.length] == ' ') {
                t = t.drop(first.value.length).trimStart()
                notes += "dropped \"${first.value}\" said twice"
            }
        }
        val firstAfter = WORD.find(after.trimStart(' ', '\t'))?.takeIf { it.range.first == 0 }?.value
        WORD.findAll(t).lastOrNull()?.takeIf { it.range.last == t.lastIndex }?.let { last ->
            if (firstAfter != null && last.value.equals(firstAfter, ignoreCase = true) && last.value.lowercase() !in DOUBLES && last.range.first > 0) {
                t = t.substring(0, last.range.first).trimEnd()
                notes += "dropped \"${last.value}\" said twice"
            }
        }

        // the first letter: mid-sentence, an ordinary word isn't capitalized; after a sentence ends, it is
        val b = before.trimEnd(' ', '\t')
        val end = b.trimEnd('"', '”', '’', '\'', ')', ']')
        val sentenceStart = b.isEmpty() || b.last() == '\n' || (end.isNotEmpty() && end.last() in ".!?")
        val midSentence = !sentenceStart && b.last() != ':'
        WORD.find(t)?.takeIf { it.range.first == 0 }?.value?.let { w ->
            val plain = w.replace('’', '\'').lowercase()
            if (midSentence && w[0].isUpperCase() && w.drop(1).none { it.isUpperCase() } && plain in COMMON) {
                t = t[0].lowercaseChar() + t.drop(1)
                notes += "lowercased \"$w\" mid-sentence"
            } else if (sentenceStart && b.isNotEmpty() && w[0].isLowerCase() && around.before.any { it.isUpperCase() }) {
                t = t[0].uppercaseChar() + t.drop(1)
                notes += "capitalized \"$w\" after a sentence"
            }
        }

        // the last mark: no period when the sentence carries on after the dictation
        val a = after.trimStart(' ', '\t')
        if (a.isNotEmpty() && t.endsWith('.') && !t.endsWith("..")) {
            val c = a.first()
            if (c.isLowerCase() || c.isDigit() || c in ",;:).!?") {
                t = t.dropLast(1)
                notes += "no period: the sentence goes on"
            }
        }
        return Fit(t, removeBefore, removeAfter, notes)
    }

    /**
     * Takes [fit]'s leftovers out of [field], around [typed], which was just put in right before the
     * cursor. Each is checked to be exactly where it should be first; false when nothing changed.
     */
    fun removeLeftovers(field: LiveField, typed: String, fit: Fit): Boolean {
        if (fit.removeBefore.isEmpty() && fit.removeAfter.isEmpty()) return false
        var changed = false
        field.beginBatch()
        try {
            if (fit.removeBefore.isNotEmpty()) {
                val n = typed.length + fit.removeBefore.length + 8
                val got = field.textBefore(n)?.toString()
                if (got != null && got.endsWith(typed)) {
                    val pre = got.dropLast(typed.length)
                    val gap = pre.length - pre.trimEnd(' ', '\t').length
                    val pre1 = pre.trimEnd(' ', '\t')
                    if (pre1.endsWith(fit.removeBefore)) {
                        val pre2 = pre1.dropLast(fit.removeBefore.length)
                        val keep = pre2.trimEnd(' ', '\t')
                        val ws = pre2.length - keep.length
                        // a space back between what's left and the dictation, unless that's the start of the field or a line
                        val atStart = keep.isEmpty() && got.length < n
                        val sep = if (atStart || keep.endsWith('\n')) "" else " "
                        field.deleteBefore(typed.length + gap + fit.removeBefore.length + ws)
                        field.commit(sep + typed)
                        changed = true
                    }
                }
            }
            if (fit.removeAfter.isNotEmpty()) {
                val got = field.textAfter(fit.removeAfter.length + 4)?.toString()
                if (got != null) {
                    val lead = got.length - got.trimStart(' ', '\t').length
                    if (got.drop(lead).startsWith(fit.removeAfter)) {
                        field.deleteAfter(lead + fit.removeAfter.length)
                        changed = true
                    }
                }
            }
        } finally {
            field.endBatch()
        }
        return changed
    }

    private fun leftoverOk(w: String) =
        w.isNotBlank() && w.length <= 40 && '\n' !in w && WORD.findAll(w).count() in 1..MAX_REMOVED_WORDS

    /** Words that are often said twice on purpose ("had had", "that that"). */
    private val DOUBLES = setOf("had", "that", "is", "very", "really", "so", "no", "bye", "ha", "go")

    /**
     * Ordinary words, lowercase mid-sentence. A capitalized word that isn't one of these is probably
     * a name and keeps its capital. "I" is never here; nor are words that are often names (Will, May, Mark…).
     */
    private val COMMON = """
        a an the and but or nor so yet for to of in on at by with from about as into onto over under up down out off
        after before then than that this these those there here it its it's is are was were be been being am
        do does did done have has had would can could should shall might must not no yes just also too very
        really maybe perhaps please thanks thank you your yours we our ours us they them their theirs he him his
        she her hers my me mine what when where why how which who whom whose if because while though although
        since until unless whether all any some every each both either neither more most less least much many few
        other another such same own only even still already again always never often sometimes usually soon now
        today tomorrow tonight yesterday later get got gets getting go goes going gone went come comes coming came
        make makes made take takes took let lets know knows knew think thought want wants need needs like likes
        see saw seen look looks say says said tell told ask asked give gave put keep kept find found call called
        try tried use used work feel felt seem leave left mean meant show bring brought start started stop help
        talk turn move play run pay meet send sent check sure okay ok well oh hey hi hello sorry good great nice
        fine new old first last next one two three four five once twice something anything nothing everything
        someone anyone everyone somewhere anywhere everywhere instead otherwise however also actually probably
        definitely especially basically honestly still back away around through across between without within
        don't can't won't isn't aren't wasn't weren't didn't doesn't haven't hasn't wouldn't couldn't shouldn't
        that's there's what's let's here's who's it'll that'll we're they're you're we'll they'll you'll
        we've they've you've we'd they'd you'd he's she's he'll she'll
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
}
