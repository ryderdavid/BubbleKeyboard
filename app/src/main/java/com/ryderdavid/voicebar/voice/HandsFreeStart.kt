package com.ryderdavid.voicebar.voice

/**
 * Where "voice bar, hands free" goes: hands-free mode lives in the accessibility service, which
 * sets [handler] and [stopper] while it runs. Main thread.
 */
object HandsFreeStart {
    /** Starts hands-free mode; false when it can't (the accessibility service is off, or it's turned off). */
    var handler: (() -> Boolean)? = null
    var stopper: (() -> Unit)? = null
    /** Hands-free mode is on. */
    @Volatile var active = false

    fun request(): Boolean = handler?.invoke() ?: false
    fun stop() { stopper?.invoke() }
}
