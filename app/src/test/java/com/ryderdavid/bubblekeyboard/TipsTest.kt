package com.ryderdavid.bubblekeyboard

import androidx.test.core.app.ApplicationProvider
import androidx.core.content.edit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TipsTest {
    private val open = Tips.Moment.OPEN
    private val rec = Tips.Moment.RECORDING

    @Test fun order() {
        assertEquals(Tips.Id.VOICE, Tips.next(emptySet(), open, 0, false))
        // one per opening
        assertNull(Tips.next(emptySet(), open, 0, true))
        // finishing comes during the first recording, once the Voice tip is done
        assertNull(Tips.next(emptySet(), rec, 0, false))
        assertEquals(Tips.Id.FINISH, Tips.next(setOf(Tips.Id.VOICE), rec, 0, true))
        assertEquals(Tips.Id.ABC, Tips.next(setOf(Tips.Id.VOICE), open, 0, false))
        assertEquals(Tips.Id.CURSOR, Tips.next(setOf(Tips.Id.VOICE, Tips.Id.ABC), open, 0, false))
        assertEquals(Tips.Id.BKSP, Tips.next(setOf(Tips.Id.VOICE, Tips.Id.ABC, Tips.Id.CURSOR), open, 0, false))
        val basics = setOf(Tips.Id.VOICE, Tips.Id.FINISH, Tips.Id.ABC, Tips.Id.CURSOR, Tips.Id.BKSP)
        // "voice bar" waits for a few dictations
        assertNull(Tips.next(basics, open, 2, false))
        assertEquals(Tips.Id.VOICEBAR, Tips.next(basics, open, 3, false))
        assertNull(Tips.next(Tips.Id.entries.toSet(), open, 9, false))
        assertNull(Tips.next(Tips.Id.entries.toSet(), rec, 9, false))
    }

    @Test fun usingAFeatureRetiresItsTip() {
        assertEquals(Tips.Id.ABC, Tips.forUse("abc"))
        assertEquals(Tips.Id.BKSP, Tips.forUse("bksp"))
        assertNull(Tips.forUse("space"))
    }

    @Test fun savedAndReset() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear() }
        assertTrue(Tips.on(ctx))
        Tips.markDone(ctx, Tips.Id.VOICE); Tips.markDone(ctx, Tips.Id.ABC)
        assertEquals(setOf(Tips.Id.VOICE, Tips.Id.ABC), Tips.done(ctx))
        repeat(3) { Tips.countDictation(ctx) }
        assertEquals(3, Tips.dictations(ctx))
        Prefs.get(ctx).edit { putBoolean(Prefs.TIPS, false) }
        Tips.reset(ctx)
        assertTrue(Tips.on(ctx)); assertEquals(emptySet(), Tips.done(ctx)); assertEquals(0, Tips.dictations(ctx))
    }
}
