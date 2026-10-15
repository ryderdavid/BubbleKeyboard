package com.ryderdavid.bubblekeyboard

/**
 * What the service drives on screen: the voice bar ([DockView]), which reports touches
 * through [Bar.Listener].
 */
interface KeyboardSurface {
    var hasSelection: Boolean
    var canPaste: Boolean
    /** The start of what's on the clipboard, for the paste button while dictating; null when nothing. */
    var clipPreview: String?
    var voiceInField: Boolean
    val voice: Bar.Voice

    fun showListening()
    fun showLevel(level: Float)
    fun showLiveText(text: String)
    fun showCleaning(text: String)
    /**
     * "Voice bar" was heard: the pill turns purple and shows what's said after it ([said], maybe
     * empty), bold once it's a command ([understood]). Null puts the pill back.
     */
    fun showCommand(said: String?, understood: Boolean = false)
    /** A short note in the voice pill; with [onTap], tapping it runs that (and it stays up longer). */
    fun showMessage(message: String, onTap: (() -> Unit)? = null)
    /** Good news (something learned), in the calm primary tone rather than the warning one. */
    fun showNote(message: String, onTap: (() -> Unit)? = null) = showMessage(message, onTap)
    fun hideVoice()
    fun heardSpeech()
    fun talkEnded()
    /** Start dictating as if the mic were tapped (used when coming back from Gboard). */
    fun startVoice()
    fun reset()
    fun reloadSettings()
}
