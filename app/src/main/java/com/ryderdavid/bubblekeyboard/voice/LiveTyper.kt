package com.ryderdavid.bubblekeyboard.voice

/** The bits of an input connection that live typing needs; small so it can be faked in tests. */
interface LiveField {
    /** Replaces the keyboard's draft (composing text), or starts one at the cursor. */
    fun setComposing(text: String)
    /** Makes the draft ordinary text. */
    fun finishComposing()
    fun commit(text: String)
    fun textBefore(n: Int): CharSequence?
    fun deleteBefore(n: Int)
    /** Groups edits so the app sees them as one change. */
    fun beginBatch() {}
    fun endBatch() {}
}

/**
 * Types dictation into the field as you speak: the words so far are the keyboard's draft (composing
 * text), replaced on every update. When the polished text arrives it replaces the draft in one step.
 *
 * If the app ends the draft itself (the user tapped elsewhere, typed, the field changed), the raw
 * words are now ordinary text. The polish then replaces them only if they're still exactly where we
 * left them, right before the cursor; otherwise the raw text is left alone.
 */
class LiveTyper(
    private val field: () -> LiveField?,
    /**
     * Our draft ended outside our control (the app finished it, or the user moved the cursor away);
     * [typedWords] of its words are now ordinary text. Returns true if the dictation is still
     * listening and moved its boundary past them (later words start a fresh draft), false if it's
     * already finishing.
     */
    private val onLost: (typedWords: Int) -> Boolean = { false },
) {
    /** Our draft is in the field. */
    var active = false
        private set
    /**
     * The app has reported the current draft back to us at least once, so a report of no draft
     * after that means it really ended (not a stale report from before).
     */
    private var confirmed = false
    /** The draft ended outside our control while the dictation was finishing; [shown] is ordinary text. */
    private var lost = false
    private var sep = ""
    /** What the draft holds: the separator plus the words. */
    private var shown = ""

    fun begin() = reset()

    /** How many words of the current draft are in the field. */
    fun typedWords() = words(shown).size

    /** The words so far, replacing the previous draft. */
    fun live(text: String) {
        val f = field() ?: return
        if (lost) {
            appendLate(f, text)
            return
        }
        if (!active && text.isBlank()) return
        if (!active) {
            sep = separator(f)
            confirmed = false
        }
        shown = if (text.isBlank()) "" else sep + text
        f.setComposing(shown)
        active = true
    }

    /**
     * Words that arrive after the draft was ended (e.g. the last ones, finalized just after the
     * finger lifted): typed after it, so nothing said is dropped.
     */
    private fun appendLate(f: LiveField, text: String) {
        val have = words(shown).size
        val now = words(text)
        if (now.size <= have) return
        val add = separator(f) + now.drop(have).joinToString(" ")
        f.commit(add)
        shown += add
    }

    /**
     * The user pressed a key mid-dictation: what's typed stays as it is. With [newSegment] (still
     * listening) later words start a new draft; otherwise the dictation is finishing, and its
     * polish may only replace the raw words if they're still right before the cursor.
     */
    fun interrupt(newSegment: Boolean) {
        if (!active && !lost) return
        if (active) field()?.finishComposing()
        active = false
        if (newSegment) {
            lost = false
            shown = ""
        } else {
            lost = shown.isNotEmpty()
        }
    }

    /** The finished (possibly polished) text replaces the raw words. */
    fun result(text: String) {
        val f = field() ?: return reset()
        when {
            active -> {
                f.setComposing(sep + text)
                f.finishComposing()
            }
            lost && shown.isNotEmpty() -> {
                if (f.textBefore(shown.length)?.toString() == shown) {
                    f.beginBatch()
                    f.deleteBefore(shown.length)
                    f.commit(sep + text)
                    f.endBatch()
                }
                // else: the user has changed things since; the raw text stays as it is
            }
            // nothing was ever shown live (no partials arrived): type it now
            else -> f.commit(separator(f) + text)
        }
        reset()
    }

    /** Thrown out: the draft goes away. */
    fun cancel() {
        if (active) field()?.let { it.setComposing(""); it.finishComposing() }
        reset()
    }

    /** Stopped without a result (silence, an error, the field closing): keep whatever is typed. */
    fun end() {
        if (active) field()?.finishComposing()
        reset()
    }

    /**
     * From onUpdateSelection. A report describes the current draft only if its composing span has
     * the draft's length; older reports (for earlier versions of the draft, or from before it) are
     * ignored, so they can't fake an ended draft.
     */
    fun onSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (!active) return
        if (candidatesStart >= 0) {
            if (candidatesEnd - candidatesStart != shown.length) return
            confirmed = true
            // our edits leave the cursor at the draft's end: anywhere else, the user moved it
            if (newSelStart != candidatesEnd || newSelEnd != candidatesEnd) {
                field()?.finishComposing()
                ended()
            }
        } else if (confirmed) {
            ended()
        }
    }

    private fun ended() {
        active = false
        if (onLost(typedWords())) {
            shown = ""
        } else {
            lost = shown.isNotEmpty()
        }
    }

    private fun separator(f: LiveField): String {
        val before = f.textBefore(1)
        return if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
    }

    private fun words(s: String) = s.split(WHITESPACE).filter { it.isNotEmpty() }

    private fun reset() {
        active = false
        confirmed = false
        lost = false
        sep = ""
        shown = ""
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
