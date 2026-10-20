package com.ryderdavid.voicebar

import android.graphics.Rect
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the voice button sits on Gboard's top row when Gboard's voice key is off. */
@RunWith(RobolectricTestRunner::class)
class VoiceSlotTest {
    private val ime = Rect(0, 1000, 1248, 1900)
    private val cy = 1100
    private fun slot(vararg row: Rect) = GboardButtonService.voiceSlot(row.toList(), ime, 100, 100, 30, cy, 10)

    @Test fun emptyRowGoesToTheRightEnd() {
        assertEquals(Rect(1118, 1050, 1218, 1150), slot(Rect(30, 1050, 130, 1150)))
    }

    @Test fun suggestionsUnderneathDontMoveIt() {
        // wide suggestion chips may be covered; the button stays at the right end
        assertEquals(1218, assertNotNull(slot(Rect(700, 1050, 1200, 1150))).right)
    }

    @Test fun anAiKeyAtTheRightEndPushesItLeft() {
        val ai = Rect(1110, 1050, 1210, 1150)
        val r = assertNotNull(slot(ai))
        assertTrue(r.right <= ai.left - 10, "sits left of the AI key")
        assertTrue(!Rect.intersects(r, ai))
    }

    @Test fun anAiKeyBesideTheSpotLeavesItAlone() {
        // Gboard already put its AI key just left of the empty spot (as in the screenshot)
        assertEquals(1218, assertNotNull(slot(Rect(1005, 1050, 1105, 1150))).right)
    }

    @Test fun aRowFullOfButtonsGivesUp() {
        val row = (0 until 12).map { Rect(30 + it * 102, 1050, 130 + it * 102, 1150) }.toTypedArray()
        assertNull(slot(*row))
    }
}
