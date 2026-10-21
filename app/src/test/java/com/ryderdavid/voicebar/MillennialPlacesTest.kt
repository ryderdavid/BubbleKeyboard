package com.ryderdavid.voicebar

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MillennialPlacesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun fresh(block: android.content.SharedPreferences.Editor.() -> Unit = {}) = Prefs.get(context).edit(commit = true) { clear(); block() }

    @Test fun theOldChoiceCarriesOverAsChecks() {
        fresh { putString(Prefs.MILLENNIAL_WHERE, Prefs.MILLENNIAL_CHATS) }
        assertEquals(Prefs.MillennialPlaces(chats = true), Prefs.millennial(context))
        fresh { putString(Prefs.MILLENNIAL_WHERE, Prefs.MILLENNIAL_EVERYWHERE) }
        assertEquals(Prefs.MillennialPlaces(everywhere = true), Prefs.millennial(context))
        fresh { putBoolean(Prefs.MILLENNIAL, true) } // the switch before that
        assertEquals(Prefs.MillennialPlaces(chats = true), Prefs.millennial(context))
        fresh()
        assertTrue(Prefs.millennial(context).nowhere)
    }

    @Test fun checksAreKeptOnTheirOwnUnderEverywhere() {
        fresh { putString(Prefs.MILLENNIAL_WHERE, Prefs.MILLENNIAL_EVERYWHERE) }
        Prefs.setMillennial(context, Prefs.MillennialPlaces(search = true, everywhere = true))
        assertEquals(Prefs.MillennialPlaces(search = true, everywhere = true), Prefs.millennial(context), "the old choice no longer counts once checks are saved")
        Prefs.setMillennial(context, Prefs.millennial(context).copy(everywhere = false))
        assertEquals(Prefs.MillennialPlaces(search = true), Prefs.millennial(context), "unchecking everywhere leaves search boxes as they were")
    }
}
