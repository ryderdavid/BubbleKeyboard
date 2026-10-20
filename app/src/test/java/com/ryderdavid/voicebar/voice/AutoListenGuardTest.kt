package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoListenGuardTest {
    private val g = AutoListenGuard()

    @Test fun aDictationStartedByHandIsNeverHeldBack() {
        g.start(auto = false, chat = true, typicalLevel = 0.7f)
        assertTrue(g.engaged)
        assertFalse(g.expired())
        assertFalse(g.hold(endedBySilence = true))
        assertFalse(g.capped())
    }

    @Test fun farAwayTalkNeverEngagesSoTheWindowExpires() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        var t = 0L
        repeat(40) { g.level(0.35f, t); t += 100 } // a conversation across the room, about -36 dBFS
        assertFalse(g.engaged)
        assertTrue(g.expired())
    }

    @Test fun talkingAtThePhoneEngagesButAClatterDoesNot() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        assertFalse(g.level(0.9f, 0)) // one loud chunk
        g.level(0.2f, 100)
        assertFalse(g.engaged)
        g.level(0.66f, 200); g.level(0.68f, 300)
        assertTrue(g.level(0.7f, 400))
        assertFalse(g.expired())
    }

    @Test fun inAChatItsHeldOnlyWhenItEndedByItselfUntouched() {
        g.start(auto = true, chat = true, typicalLevel = 0.7f)
        assertTrue(g.hold(endedBySilence = true))
        assertFalse(g.hold(endedBySilence = false), "ended with ✓: typed")
        assertTrue(g.capped())
        g.touch()
        assertFalse(g.hold(endedBySilence = true), "touched: it's the user's")
        assertFalse(g.capped())
    }

    @Test fun outsideChatsNothingIsHeld() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        assertFalse(g.hold(endedBySilence = true))
        assertFalse(g.capped())
    }

    @Test fun theUsualLevelIsLearnedFromHandStartedDictations() {
        val quietTalker = List(50) { 0.5f } + List(30) { 0.1f }
        val learned = AutoListenGuard.learnTypical(0.7f, quietTalker)
        assertTrue(learned < 0.7f && learned > 0.5f, "$learned")
        assertEquals(0.7f, AutoListenGuard.learnTypical(0.7f, List(5) { 0.6f }), "too little to learn from")
        assertEquals(0.35f, AutoListenGuard.nearThreshold(0.3f))
    }
}
