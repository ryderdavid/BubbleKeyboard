package com.ryderdavid.bubblekeyboard

/**
 * What the service drives on screen: the voice bar ([DockView]), which reports touches
 * through [Bar.Listener].
 */
interface KeyboardSurface {
    var hasSelection: Boolean
    var canPaste: Boolean
    var voiceInField: Boolean
    val voice: Bar.Voice

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
