package com.ryderdavid.voicebar

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ryderdavid.voicebar.Tips.Answer
import com.ryderdavid.voicebar.Tips.Showing
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TipsTest {
    private val last = Tips.Id.entries.lastIndex

    @Test fun nextAndBackWalkTheTour() {
        assertEquals(Showing.Step(1) to 1, Tips.answer(Showing.Step(0), Answer.NEXT))
        assertEquals(Showing.Step(2) to 2, Tips.answer(Showing.Step(3), Answer.BACK))
        assertEquals(Showing.Step(0) to 0, Tips.answer(Showing.Step(0), Answer.BACK))
        // Done on the last one finishes the tour
        assertEquals(null to Tips.Id.entries.size, Tips.answer(Showing.Step(last), Answer.NEXT))
    }

    @Test fun dismissAsksAboutLater() {
        assertEquals(Showing.Ask(2) to 2, Tips.answer(Showing.Step(2), Answer.DISMISS))
        // later: put away, picks up at the same tip
        assertEquals(null to 2, Tips.answer(Showing.Ask(2), Answer.LATER))
        // no thanks: off for good
        assertEquals(null to null, Tips.answer(Showing.Ask(2), Answer.NEVER))
    }

    @Test fun savedAndReset() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear() }
        assertEquals(0, Tips.resume(ctx))
        Tips.save(ctx, 3); assertEquals(3, Tips.resume(ctx))
        Tips.save(ctx, Tips.Id.entries.size); assertNull(Tips.resume(ctx))
        Tips.save(ctx, null); assertFalse(Tips.on(ctx))
        Tips.reset(ctx)
        assertTrue(Tips.on(ctx)); assertEquals(0, Tips.resume(ctx))
    }
}
