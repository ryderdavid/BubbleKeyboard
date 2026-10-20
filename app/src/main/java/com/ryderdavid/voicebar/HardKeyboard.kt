package com.ryderdavid.voicebar

import android.content.res.Configuration

/** Whether a physical keyboard (Bluetooth, USB, a keyboard cover) is attached and usable. */
object HardKeyboard {
    /**
     * Android reports an attached full keyboard in the configuration: [Configuration.keyboard] is
     * QWERTY (or 12-key) and it isn't hidden (a slid-shut or folded-back keyboard counts as hidden).
     */
    fun attached(c: Configuration): Boolean =
        c.keyboard != Configuration.KEYBOARD_NOKEYS && c.keyboard != Configuration.KEYBOARD_UNDEFINED &&
            c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
}
