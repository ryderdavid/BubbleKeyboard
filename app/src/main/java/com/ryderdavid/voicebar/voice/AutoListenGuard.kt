package com.ryderdavid.voicebar.voice

/**
 * Keeps "listen as soon as a text box opens" from turning a room's conversation into a message.
 *
 * - **Start window:** an auto-started dictation counts only once someone talks *at the phone*
 *   (as loud as the user's own dictation, less a margin) within [START_WINDOW_MS]; until then
 *   nothing goes into the field, and if it never happens the dictation stops quietly.
 * - **Held text:** in a chat or email app, an auto-started dictation that ends by itself (silence,
 *   or the [CAP_MS] cap) without the user touching the bar isn't typed: it waits in the pill for
 *   a tap. Ending it yourself (✓, the pill, swipe to send) types it as always.
 * - **Cap:** an untouched auto-started dictation in a chat or email app ends after [CAP_MS].
 *
 * Dictations the user starts by hand are never held back. Pure, so it can be tested.
 */
class AutoListenGuard {
    var auto = false; private set
    var chat = false; private set
    /** Someone has talked at the phone (always true for a dictation started by hand). */
    var engaged = true; private set
    var touched = false; private set
    private var loudSince = 0L
    private var threshold = DEFAULT_TYPICAL - MARGIN

    fun start(auto: Boolean, chat: Boolean, typicalLevel: Float) {
        this.auto = auto
        this.chat = auto && chat
        engaged = !auto
        touched = false
        loudSince = 0L
        threshold = nearThreshold(typicalLevel)
    }

    /** A microphone level (0..1) at [now]; true when this level is the one that engaged it. */
    fun level(level: Float, now: Long): Boolean {
        if (engaged) return false
        if (level < threshold) { loudSince = 0L; return false }
        if (loudSince == 0L) loudSince = now
        if (now - loudSince < LOUD_FOR_MS) return false
        engaged = true
        return true
    }

    /** The user did something on the bar during the dictation: it's theirs, never held. */
    fun touch() { touched = true }

    /** At the end of the start window: stop, nobody talked at the phone. */
    fun expired() = auto && !engaged

    /** Whether the finished text waits in the pill instead of going into the field. */
    fun hold(endedBySilence: Boolean) = chat && endedBySilence && !touched

    /** Whether the cap ends this dictation now. */
    fun capped() = chat && !touched

    companion object {
        const val START_WINDOW_MS = 4_000L
        const val CAP_MS = 60_000L
        /** Loud for this long (a couple of mic chunks), not one clatter. */
        const val LOUD_FOR_MS = 200L
        /** The user's dictation sits around here when nothing's known yet (about -22 dBFS). */
        const val DEFAULT_TYPICAL = 0.70f
        /** Talking at the phone: within about 6 dB of the user's usual level. */
        const val MARGIN = 0.15f

        fun nearThreshold(typical: Float) = (typical - MARGIN).coerceIn(0.35f, 0.75f)

        /** The user's usual dictation level, updated from a dictation they started by hand ([levels] of its louder chunks). */
        fun learnTypical(old: Float, levels: List<Float>): Float {
            val voiced = levels.filter { it >= 0.25f }.sorted()
            if (voiced.size < 10) return old
            val p80 = voiced[(voiced.size * 0.8f).toInt().coerceAtMost(voiced.size - 1)]
            return (old * 0.7f + p80 * 0.3f).coerceIn(0.4f, 0.95f)
        }

        /** Email apps, by package name: an auto-started dictation there is held like a chat. */
        val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.samsung.android.email.provider",
            "com.fastmail.app",
            "ch.protonmail.android", "me.proton.android.mail",
            "com.yahoo.mobile.client.android.mail",
            "com.readdle.spark",
            "com.easilydo.mail",
            "com.superhuman.mail",
            "com.fsck.k9", "net.thunderbird.android",
            "com.google.android.apps.inbox",
        )
    }
}
