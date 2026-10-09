package com.ryderdavid.bubblekeyboard.voice

/**
 * Millennial mode: a dictated message never ends with a period. Everything else (question marks,
 * exclamation marks, commas, periods between sentences, an ellipsis) stays.
 */
object Millennial {
    // one period at the very end, optionally inside closing quotes or brackets; not part of "..."
    private val FINAL_PERIOD = Regex("""(?<!\.)\.(["'”’)\]]*)\s*$""")

    /** [text] without its final period, and whether one was removed. */
    fun strip(text: String): Pair<String, Boolean> {
        val m = FINAL_PERIOD.find(text) ?: return text to false
        return text.substring(0, m.range.first) + m.groupValues[1] to true
    }

    /** What to remember about a stripped message so a follow-up dictation can put its period back. */
    fun tail(typed: String) = typed.takeLast(TAIL_LENGTH)

    /**
     * Whether a new dictation follows a message we stripped: the field still ends with exactly what
     * we typed ([tail], read back as [before]), and it doesn't already end in punctuation.
     */
    fun needsPeriodBack(tail: String?, before: CharSequence?): Boolean {
        if (tail.isNullOrEmpty() || before == null) return false
        return before.toString() == tail && tail.last().isLetterOrDigit()
    }

    const val TAIL_LENGTH = 24
}
