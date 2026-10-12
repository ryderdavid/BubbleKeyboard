package com.ryderdavid.bubblekeyboard

/**
 * What the service drives on screen, whichever style is showing: the floating bubble
 * ([BubbleView]) or the docked bar ([DockView]). Both report touches through [BubbleView.Listener].
 */
interface KeyboardSurface {
    var hasSelection: Boolean
    var canPaste: Boolean
    var voiceInField: Boolean
    val voice: BubbleView.Voice

    fun showListening()
    fun showLevel(level: Float)
    fun showLiveText(text: String)
    fun showCleaning(text: String)
    fun showMessage(message: String)
    fun hideVoice()
    fun heardSpeech()
    fun talkEnded()
    /** Start dictating as if the mic were tapped (used when coming back from Gboard). */
    fun startVoice()
    fun reset()
    fun reloadSettings()
}
