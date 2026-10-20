package com.ryderdavid.voicebar

import kotlin.test.Test
import kotlin.test.assertEquals

class StickSelectionTest {
    /** Selecting leftward: we ask for (anchor, target), the app reports it back as (target, anchor). */
    @Test fun aLeftwardSelectionKeepsGrowing() {
        val anchor = 20
        var target = anchor
        repeat(8) {
            target -= 1
            val (start, end) = minOf(anchor, target) to maxOf(anchor, target) // the app normalizes
            target = stickMovingEnd(anchor, target, start, end)
        }
        assertEquals(12, target, "eight steps left, not rattling next to the anchor")
    }

    @Test fun rightwardAndCollapsedReportsStillWork() {
        assertEquals(25, stickMovingEnd(20, 25, 20, 25))
        assertEquals(20, stickMovingEnd(20, 20, 20, 20))
        // an app that moved the anchor itself: follow whichever end is near where we aimed
        assertEquals(14, stickMovingEnd(20, 15, 14, 19))
    }
}
