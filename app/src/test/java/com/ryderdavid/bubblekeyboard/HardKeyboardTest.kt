package com.ryderdavid.bubblekeyboard

import android.content.res.Configuration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HardKeyboardTest {
    private fun config(keyboard: Int, hidden: Int) = Configuration().apply { this.keyboard = keyboard; hardKeyboardHidden = hidden }

    @Test fun bluetoothKeyboard() = assertTrue(HardKeyboard.attached(config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO)))
    @Test fun noKeyboard() = assertFalse(HardKeyboard.attached(config(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES)))
    @Test fun foldedAway() = assertFalse(HardKeyboard.attached(config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES)))
    @Test fun unknown() = assertFalse(HardKeyboard.attached(config(Configuration.KEYBOARD_UNDEFINED, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED)))
}
