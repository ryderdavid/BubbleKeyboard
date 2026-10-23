package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /**
     * voicebar-8lo: a sentence is loud, then quiet for a moment at every syllable boundary and
     * plosive. A dip used to put the stretch back to nothing, so someone talking right at the
     * phone could talk all through the start window and never engage it: nothing was typed, and
     * the dictation stopped with everything it had heard thrown away.
     */
    @Test fun theDipsInSpeechDontStopItEngaging() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        var t = 1_000L
        var engagedAt = 0L
        // "hel-lo there": loud, with a dip in between, as the mic chunks see it
        for (level in listOf(0.72f, 0.3f, 0.75f, 0.2f, 0.8f)) {
            if (g.level(level, t) && engagedAt == 0L) engagedAt = t
            t += 100
        }
        assertTrue(g.engaged, "talking at the phone with normal dips must engage it")
        assertEquals(1_200L, engagedAt, "after 200 ms of talking, dip and all")
        assertFalse(g.expired())
    }

    @Test fun aRealPauseStartsTheStretchOver() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        g.level(0.9f, 1_000) // a door slamming
        var t = 1_100L
        repeat(10) { g.level(0.05f, t); t += 100 } // and then the room is quiet
        assertFalse(g.level(0.9f, t), "a second clatter a second later isn't someone talking")
        assertFalse(g.engaged)
    }

    @Test fun theLoudestLevelIsKeptForTheLog() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        assertEquals(AutoListenGuard.nearThreshold(0.7f), g.nearLevel)
        g.level(0.2f, 1_000); g.level(0.41f, 1_100); g.level(0.3f, 1_200)
        assertEquals(0.41f, g.peak, "how close a conversation across the room came")
        assertFalse(g.engaged)
    }

    /**
     * voicebar-8lo: with live typing on, words the guard holds back can't go into the field, and
     * the pill doesn't draw its text while the field is taking the words — so held-back words were
     * shown nowhere at all. Until it engages, the words belong to the pill.
     */
    @Test fun heldBackWordsGoToThePillNotTheField() {
        g.start(auto = true, chat = false, typicalLevel = 0.7f)
        assertFalse(g.typesLive(liveTyping = true), "nothing reaches the field yet: the pill shows the words")
        g.level(0.8f, 1_000); g.level(0.8f, 1_100); g.level(0.8f, 1_200)
        assertTrue(g.engaged)
        assertTrue(g.typesLive(liveTyping = true), "engaged: the words go into the field as they come")
        assertFalse(g.typesLive(liveTyping = false), "live typing off: always the pill")
    }

    @Test fun aDictationStartedByHandTypesLiveFromTheFirstWord() {
        g.start(auto = false, chat = false, typicalLevel = 0.7f)
        assertTrue(g.typesLive(liveTyping = true))
    }

    @Test fun inAChatItsHeldOnlyWhenItEndedByItselfUntouched() {
        g.start(auto = true, chat = true, typicalLevel = 0.7f, careful = true)
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

class AutoListenGuardCareTest {
    @org.junit.Test fun withoutExtraCareOnlyTheStartWindowApplies() {
        val g = AutoListenGuard()
        g.start(auto = true, chat = true, typicalLevel = 0.7f, careful = false)
        kotlin.test.assertTrue(g.chat, "the OpenAI check still knows it's a chat")
        kotlin.test.assertFalse(g.careful)
        kotlin.test.assertFalse(g.hold(endedBySilence = true))
        kotlin.test.assertFalse(g.capped())
        kotlin.test.assertTrue(g.expired(), "but nobody talking at the phone still stops it")
    }

    @Test fun whyABoxOpeningDoesntListenByItself() {
        assertNull(AutoListenGuard.skipReason(settingOn = true, restarting = false, takesText = true, keyboard = false))
        assertEquals("the setting is off", AutoListenGuard.skipReason(false, false, true, false))
        assertEquals("input restarted on the same box", AutoListenGuard.skipReason(true, true, true, false))
        assertEquals("this box doesn't take dictation (a password or raw-key field)", AutoListenGuard.skipReason(true, false, false, false))
        assertEquals("a physical keyboard is connected", AutoListenGuard.skipReason(true, false, true, true))
        // the setting being off says so, whatever else is true
        assertEquals("the setting is off", AutoListenGuard.skipReason(false, true, false, true))
    }

    @Test fun aHandWobblesAPhoneOnADeskDoesnt() {
        val rnd = java.util.Random(7)
        // a desk: the gyroscope's own noise, a few thousandths of a rad/s, around a small fixed offset
        val desk = List(25) { FloatArray(3) { 0.002f + (rnd.nextGaussian() * 0.002).toFloat() } }
        // a hand: tremor (about 8 Hz) plus slow drift, a few hundredths of a rad/s
        val hand = List(25) { i -> FloatArray(3) { a -> (0.03 * kotlin.math.sin(2 * Math.PI * 8 * i / 50.0 + a) + rnd.nextGaussian() * 0.004).toFloat() } }
        assertTrue(com.ryderdavid.voicebar.HeldProbe.motion(desk) < com.ryderdavid.voicebar.HeldProbe.GYRO_HELD)
        assertTrue(com.ryderdavid.voicebar.HeldProbe.motion(hand) > com.ryderdavid.voicebar.HeldProbe.GYRO_HELD)
        assertEquals(0f, com.ryderdavid.voicebar.HeldProbe.motion(emptyList()))
    }
}
