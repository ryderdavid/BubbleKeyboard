package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.edit
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class DockViewTest {
    private lateinit var activity: Activity
    private lateinit var dock: DockView
    private val keys = mutableListOf<Any>()
    private val edits = mutableListOf<BubbleView.EditAction>()
    private val talk = mutableListOf<String>()
    private val stick = mutableListOf<String>()
    private var capitalize = false
    private var settings = false
    private var picker = false
    private var style: Boolean? = null
    private var downTime = 0L

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        dock = DockView(activity, null)
        dock.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) { keys.add(key) }
            override fun onLetter(ch: Char) { keys.add(ch) }
            override fun onSwitchKeyboard() { picker = true }
            override fun onOpenSettings() { settings = true }
            override fun onSwitchStyle(docked: Boolean) { style = docked }
            override fun onEdit(action: BubbleView.EditAction) { edits.add(action) }
            override fun shouldCapitalize() = capitalize
            override fun onTalkStart(): Boolean { talk.add("start"); return true }
            override fun onTalkEnd(cancel: Boolean) { talk.add(if (cancel) "cancel" else "end") }
            override fun onStickStart() { stick.add("start") }
            override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) { stick.add("step $cols $lines ${if (selecting) "sel" else "aim"}") }
            override fun onStickTap() { stick.add("tap") }
            override fun onStickSelect() { stick.add("select") }
            override fun onStickEnd() { stick.add("end") }
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle(0)
    }

    private val d get() = activity.resources.displayMetrics.density
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, p: PointF) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
        dock.dispatchTouchEvent(e); e.recycle()
    }
    private fun at(act: DockView.Act, ch: Char = ' '): PointF {
        val r: RectF = assertNotNull(dock.testItem(act, ch), "$act is on screen")
        return PointF(r.centerX(), r.centerY())
    }
    private fun tap(act: DockView.Act, ch: Char = ' ') { val p = at(act, ch); touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(20) }
    private fun relayout() { idle(50); dock.measure(android.view.View.MeasureSpec.makeMeasureSpec(dock.width, android.view.View.MeasureSpec.EXACTLY), 0); dock.layout(0, 0, dock.width, dock.measuredHeight); idle(20) }

    @Test fun idleIsOneRowTheHeightOfANavBar() {
        assertEquals((52 * d).toInt(), dock.height)
        for (a in listOf(DockView.Act.PASTE, DockView.Act.CURSOR, DockView.Act.MIC, DockView.Act.BACKSPACE, DockView.Act.ENTER, DockView.Act.KEYBOARD, DockView.Act.MORE)) assertTrue(dock.testHas(a), "$a")
        dock.testSetBottomInset((24 * d).toInt())
        relayout()
        assertEquals((76 * d).toInt(), dock.height, "plus the navigation area under it, in the same surface")
        val mic = dock.testItem(DockView.Act.MIC)!!
        assertTrue(mic.width() > dock.testItem(DockView.Act.PASTE)!!.width() * 2.5f, "the mic pill is the wide one")
    }

    @Test fun tapTheMicToTalkThenFinishOrThrowAway() {
        tap(DockView.Act.MIC)
        assertEquals(listOf("start"), talk)
        assertTrue(dock.testTalking)
        assertTrue(dock.testHas(DockView.Act.DISCARD) && dock.testHas(DockView.Act.FINISH), "the ends become ✕ and ✓")
        tap(DockView.Act.FINISH)
        assertEquals(listOf("start", "end"), talk)
        tap(DockView.Act.MIC)
        tap(DockView.Act.DISCARD)
        assertEquals(listOf("start", "end", "start", "cancel"), talk)
    }

    @Test fun dictationFinishesItselfAfterTheSilence() {
        tap(DockView.Act.MIC)
        idle(9_000)
        dock.heardSpeech()
        idle(9_000)
        assertEquals(listOf("start"), talk, "speech restarts the clock")
        idle(1_500)
        assertEquals(listOf("start", "end"), talk)
    }

    @Test fun pasteIsInertWithNothingOnTheClipboard() {
        tap(DockView.Act.PASTE)
        assertTrue(edits.isEmpty())
        dock.canPaste = true
        tap(DockView.Act.PASTE)
        assertEquals(listOf(BubbleView.EditAction.PASTE), edits)
    }

    @Test fun theBarHasItsOwnBackspaceAndEnter() {
        tap(DockView.Act.BACKSPACE)
        tap(DockView.Act.ENTER)
        assertEquals(listOf<Any>(BubbleView.Key.DELETE, BubbleView.Key.ENTER), keys)
        val b = at(DockView.Act.BACKSPACE)
        keys.clear()
        touch(MotionEvent.ACTION_DOWN, b); idle(700); touch(MotionEvent.ACTION_UP, b)
        assertTrue(keys.size > 3, "backspace repeats while held")
    }

    @Test fun aSelectionBringsCutAndCopy() {
        dock.hasSelection = true
        tap(DockView.Act.CUT)
        tap(DockView.Act.COPY)
        assertEquals(listOf(BubbleView.EditAction.CUT, BubbleView.EditAction.COPY), edits)
        assertTrue(dock.testHas(DockView.Act.BACKSPACE) && dock.testHas(DockView.Act.ENTER))
        assertTrue(dock.testHas(DockView.Act.MIC))
    }

    @Test fun theKeyboardDrawerRisesAndTheBarBecomesItsBottomRow() {
        tap(DockView.Act.KEYBOARD)
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        assertTrue(dock.height > (200 * d).toInt())
        tap(DockView.Act.LETTER, 'h')
        tap(DockView.Act.SHIFT)
        tap(DockView.Act.LETTER, 'i')
        tap(DockView.Act.COMMA)
        tap(DockView.Act.SPACE)
        tap(DockView.Act.ENTER)
        assertEquals(listOf<Any>('h', 'I', ',', BubbleView.Key.SPACE, BubbleView.Key.ENTER), keys)
        tap(DockView.Act.HIDE)
        assertEquals(DockView.Drawer.NONE, dock.drawer)
    }

    @Test fun slidingAlongTheSpaceBarMovesTheCursor() {
        tap(DockView.Act.KEYBOARD)
        relayout()
        val s = at(DockView.Act.SPACE)
        touch(MotionEvent.ACTION_DOWN, s)
        touch(MotionEvent.ACTION_MOVE, PointF(s.x + 40 * d, s.y))
        touch(MotionEvent.ACTION_UP, PointF(s.x + 40 * d, s.y))
        assertTrue(keys.isNotEmpty() && keys.all { it == BubbleView.Key.RIGHT }, "$keys")
    }

    @Test fun theTrackpadAimsTapsAndSelects() {
        tap(DockView.Act.CURSOR)
        relayout()
        assertEquals(DockView.Drawer.PAD, dock.drawer)
        val p = at(DockView.Act.PAD)
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x - 30 * d, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x - 30 * d, p.y))
        assertEquals("start", stick.first())
        assertTrue(stick.any { it.startsWith("step -") && it.endsWith("aim") }, "$stick")
        assertEquals(listOf("tap", "end"), stick.takeLast(2))
        stick.clear()
        touch(MotionEvent.ACTION_DOWN, p)
        idle(400)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + 20 * d, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x + 20 * d, p.y))
        assertTrue("select" in stick && stick.any { it.endsWith("sel") }, "$stick")
        assertFalse("tap" in stick, "a selection stays selected")
    }

    @Test fun theTrackpadsBarIsJustItsToggleAndTheBasics() {
        tap(DockView.Act.CURSOR)
        relayout()
        assertFalse(dock.testHas(DockView.Act.PASTE) || dock.testHas(DockView.Act.CUT), "no second set of clipboard buttons")
        assertTrue(dock.testHas(DockView.Act.CHIP_PASTE))
        tap(DockView.Act.CURSOR)
        assertEquals(DockView.Drawer.NONE, dock.drawer, "the cursor button toggles it off")
    }

    private fun multi(action: Int, idx: Int, pts: List<PointF>) {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y } }
        val a = if (action == MotionEvent.ACTION_MOVE) action else action or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), a, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        dock.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun aSecondFingerKeepsASelectionGoing() {
        tap(DockView.Act.CURSOR)
        relayout()
        val p = at(DockView.Act.PAD)
        val thumb = PointF(p.x - 120 * d, p.y)
        touch(MotionEvent.ACTION_DOWN, thumb)
        idle(400) // held still: selecting
        // a second finger drags right, lifts, comes back and drags again; the thumb stays down
        repeat(3) {
            val start = PointF(p.x, p.y)
            multi(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(thumb, start))
            multi(MotionEvent.ACTION_MOVE, 0, listOf(thumb, PointF(start.x + 60 * d, start.y)))
            multi(MotionEvent.ACTION_POINTER_UP, 1, listOf(thumb, PointF(start.x + 60 * d, start.y)))
        }
        touch(MotionEvent.ACTION_UP, thumb)
        val cols = stick.filter { it.startsWith("step") && it.endsWith("sel") }.sumOf { it.split(" ")[1].toInt() }
        assertTrue(cols >= 18, "three strokes of about 7 columns each: $stick")
        assertFalse("tap" in stick)
        assertEquals("end", stick.last())
    }

    @Test fun afterFiveQuietSecondsThePillDrains() {
        tap(DockView.Act.MIC)
        idle(4_000)
        assertEquals(1f, dock.testDrain(), "not yet")
        idle(3_000)
        val mid = dock.testDrain()
        assertTrue(mid in 0.3f..0.8f, "$mid")
        dock.heardSpeech()
        assertEquals(1f, dock.testDrain(), "speech refills it")
    }

    @Test fun holdingTheCommaOpensTheTrackpad() {
        tap(DockView.Act.KEYBOARD)
        relayout()
        val c = at(DockView.Act.COMMA)
        touch(MotionEvent.ACTION_DOWN, c)
        idle(450)
        touch(MotionEvent.ACTION_UP, c)
        assertEquals(DockView.Drawer.PAD, dock.drawer)
        assertTrue(keys.isEmpty(), "no comma typed")
    }

    @Test fun theOverflowMenuOpensSettingsSwitchesAndGoesBubble() {
        tap(DockView.Act.MORE)
        relayout()
        assertEquals(DockView.Drawer.MENU, dock.drawer)
        tap(DockView.Act.TILE_SETTINGS)
        assertTrue(settings)
        tap(DockView.Act.MORE); relayout()
        tap(DockView.Act.TILE_SWITCH)
        assertTrue(picker)
        tap(DockView.Act.MORE); relayout()
        tap(DockView.Act.TILE_BUBBLE)
        assertEquals(false, style)
    }

    @Test fun everyStateDraws() {
        val bmp = android.graphics.Bitmap.createBitmap(dock.width, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        dock.draw(c)
        tap(DockView.Act.MIC); dock.showListening(); dock.showLevel(0.5f); dock.showLiveText("hello there"); dock.draw(c)
        dock.showCleaning("hello there"); dock.draw(c)
        tap(DockView.Act.DISCARD); dock.hideVoice(); dock.showMessage("Add an ElevenLabs key"); dock.draw(c); dock.hideVoice()
        for (a in listOf(DockView.Act.KEYBOARD)) { tap(a); relayout(); dock.draw(c) }
        tap(DockView.Act.HIDE); tap(DockView.Act.CURSOR); relayout(); dock.draw(c)
        tap(DockView.Act.CURSOR); tap(DockView.Act.MORE); relayout(); dock.draw(c)
    }
}
