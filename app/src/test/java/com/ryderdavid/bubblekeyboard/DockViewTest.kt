package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private var cleared = false
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
            override fun onDeleteAll() { cleared = true }
            override fun shouldCapitalize() = capitalize
            override fun onTalkStart(): Boolean { talk.add("start"); return true }
            override fun onTalkEnd(cancel: Boolean) { talk.add(if (cancel) "cancel" else "end") }
            override fun onTalkFinish(polish: Boolean) { talk.add(if (polish) "polish" else "end") }
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
    private fun relayout() {
        idle(50)
        dock.settle()
        dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
        dock.layout(0, 0, dock.width, dock.measuredHeight)
        dock.settle()
        idle(20)
    }

    @Test fun atRestThreeEqualPillsSitBetweenTheMenuAndBackspaceEnter() {
        assertEquals((52 * d).toInt(), dock.height)
        for (a in listOf(DockView.Act.MORE, DockView.Act.CURSOR, DockView.Act.MIC, DockView.Act.KEYBOARD, DockView.Act.BACKSPACE)) assertTrue(dock.testHas(a), "$a")
        assertFalse(dock.testHas(DockView.Act.ENTER), "↵ lives in the held ⌫ now")
        val more = dock.testItem(DockView.Act.MORE)!!
        val c = dock.testItem(DockView.Act.CURSOR)!!
        val k = dock.testItem(DockView.Act.KEYBOARD)!!
        val v = dock.testItem(DockView.Act.MIC)!!
        val b = dock.testItem(DockView.Act.BACKSPACE)!!
        assertTrue(more.left < c.left && c.right < k.left && k.right < v.left && v.right < b.left, "⋮ · Cursor ABC Voice · ⌫")
        assertEquals(c.width(), v.width(), 0.5f)
        assertEquals(v.width(), k.width(), 0.5f)
        assertTrue(v.width() > 90 * d, "roomy pills: ${v.width() / d} dp")
        assertTrue(abs(k.centerX() - dock.width / 2f) < 2 * d, "the pills sit dead center")
        dock.testSetBottomInset((24 * d).toInt())
        relayout()
        assertEquals((76 * d).toInt(), dock.height, "plus the navigation area under it, in the same surface")
    }

    @Test fun voiceStretchesAcrossTheBarAndCheckAlwaysPolishes() {
        tap(DockView.Act.MIC)
        assertEquals(listOf("start"), talk)
        assertTrue(dock.testTalking)
        assertTrue(dock.testHas(DockView.Act.DISCARD) && dock.testHas(DockView.Act.FINISH), "✕ and ✓ at the ends")
        assertFalse(dock.testHas(DockView.Act.CURSOR) || dock.testHas(DockView.Act.KEYBOARD), "the other pills went away")
        assertTrue(dock.testItem(DockView.Act.MIC)!!.width() > dock.width * 0.6f)
        tap(DockView.Act.FINISH)
        assertEquals(listOf("start", "polish"), talk)
        tap(DockView.Act.MIC)
        tap(DockView.Act.DISCARD)
        assertEquals(listOf("start", "polish", "start", "cancel"), talk)
    }

    @Test fun theVoicePillGrowsRatherThanJumping() {
        dock.settle()
        val before = dock.testAnimRect("voice")!!.width()
        tap(DockView.Act.MIC)
        dock.testFrame(); idle(16); dock.testFrame(); idle(16); dock.testFrame()
        val mid = dock.testAnimRect("voice")!!.width()
        dock.settle()
        val after = dock.testAnimRect("voice")!!.width()
        assertTrue(mid > before && mid < after, "mid-way: $before < $mid < $after")
    }

    @Test fun silenceFinishesWithoutForcingPolish() {
        tap(DockView.Act.MIC)
        idle(9_000)
        dock.heardSpeech()
        idle(9_000)
        assertEquals(listOf("start"), talk, "speech restarts the clock")
        idle(1_500)
        assertEquals(listOf("start", "end"), talk)
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




    @Test fun aTapOnBackspaceDeletesOnce() {
        tap(DockView.Act.BACKSPACE)
        assertEquals(listOf<Any>(BubbleView.Key.DELETE), keys)
    }

    @Test fun holdingBackspaceOpensEnterSpaceBackspace() {
        val pillBefore = dock.testItem(DockView.Act.MIC)!!.width()
        val b = at(DockView.Act.BACKSPACE)
        touch(MotionEvent.ACTION_DOWN, b)
        idle(320)
        assertEquals("TRAY", dock.testAlt)
        assertTrue(keys.isEmpty(), "nothing deleted by holding")
        val bk = dock.testItem(DockView.Act.BACKSPACE)!!
        val sp = dock.testItem(DockView.Act.SPACE_ALT)!!
        val en = dock.testItem(DockView.Act.ENTER)!!
        assertTrue(en.right < sp.left && sp.right < bk.left, "↵ · space · ⌫, right to left from the finger")
        assertTrue(bk.right - en.left > dock.width * 0.33f, "a good third of the bar")
        assertTrue(dock.testItem(DockView.Act.MIC)!!.width() < pillBefore * 0.75f, "the pills make room")
        touch(MotionEvent.ACTION_MOVE, PointF(sp.centerX(), b.y - 30 * d)) // a little high is fine
        touch(MotionEvent.ACTION_UP, PointF(sp.centerX(), b.y - 30 * d))
        assertEquals(listOf<Any>(BubbleView.Key.SPACE), keys)
        assertEquals("NONE", dock.testAlt)
        touch(MotionEvent.ACTION_DOWN, b); idle(320)
        val en2 = dock.testItem(DockView.Act.ENTER)!!
        touch(MotionEvent.ACTION_MOVE, PointF(en2.centerX(), b.y)); touch(MotionEvent.ACTION_UP, PointF(en2.centerX(), b.y))
        assertEquals(listOf<Any>(BubbleView.Key.SPACE, BubbleView.Key.ENTER), keys)
        // held and let go on ⌫ itself: one delete
        touch(MotionEvent.ACTION_DOWN, b); idle(320); touch(MotionEvent.ACTION_UP, b)
        assertEquals(BubbleView.Key.DELETE, keys.last())
        assertEquals(3, keys.size)
    }

    @Test fun restingOnBackspaceInTheTrayKeepsDeleting() {
        val b = at(DockView.Act.BACKSPACE)
        touch(MotionEvent.ACTION_DOWN, b)
        idle(320 + 450 + 300)
        touch(MotionEvent.ACTION_UP, b)
        assertTrue(keys.size > 3 && keys.all { it == BubbleView.Key.DELETE }, "$keys")
    }

    @Test fun abcBecomesTheKeyboardAndItsSpaceBar() {
        val abc = dock.testItem(DockView.Act.KEYBOARD)!!
        tap(DockView.Act.KEYBOARD)
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        assertTrue(dock.height > (200 * d).toInt())
        val hide = dock.testItem(DockView.Act.HIDE)!!
        assertTrue(hide.centerX() in (abc.left - 1)..(abc.right + 1), "⌄ is within where the ABC pill was")
        tap(DockView.Act.LETTER, 'h')
        tap(DockView.Act.SHIFT)
        tap(DockView.Act.LETTER, 'i')
        tap(DockView.Act.COMMA)
        tap(DockView.Act.SPACE)
        tap(DockView.Act.ENTER)
        assertEquals(listOf<Any>('h', 'I', ',', BubbleView.Key.SPACE, BubbleView.Key.ENTER), keys)
        tap(DockView.Act.HIDE)
        assertEquals(DockView.Drawer.NONE, dock.drawer)
        relayout()
        assertEquals((52 * d).toInt(), dock.height, "the room goes once the panel is back in its pill")
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

    @Test fun cursorBecomesTheTrackpadAndDone() {
        tap(DockView.Act.CURSOR)
        relayout()
        assertEquals(DockView.Drawer.PAD, dock.drawer)
        assertTrue(dock.testHas(DockView.Act.CHIP_PASTE))
        val p = at(DockView.Act.PAD)
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x - 30 * d, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x - 30 * d, p.y))
        assertEquals("start", stick.first())
        assertEquals(listOf("tap", "end"), stick.takeLast(2))
        dock.canPaste = true
        tap(DockView.Act.CHIP_PASTE)
        assertEquals(listOf(BubbleView.EditAction.PASTE), edits)
        tap(DockView.Act.CURSOR) // now Done
        assertEquals(DockView.Drawer.NONE, dock.drawer)
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
        idle(400)
        repeat(3) {
            val start = PointF(p.x, p.y)
            multi(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(thumb, start))
            multi(MotionEvent.ACTION_MOVE, 0, listOf(thumb, PointF(start.x + 60 * d, start.y)))
            multi(MotionEvent.ACTION_POINTER_UP, 1, listOf(thumb, PointF(start.x + 60 * d, start.y)))
        }
        touch(MotionEvent.ACTION_UP, thumb)
        val cols = stick.filter { it.startsWith("step") && it.endsWith("sel") }.sumOf { it.split(" ")[1].toInt() }
        assertTrue(cols >= 18, "three strokes: $stick")
        assertFalse("tap" in stick)
        assertEquals("end", stick.last())
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

    @Test fun everyStateDrawsMidAnimationToo() {
        val bmp = android.graphics.Bitmap.createBitmap(dock.width, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        fun frames() { repeat(4) { dock.draw(c); idle(16) } }
        frames()
        tap(DockView.Act.MIC); dock.showListening(); dock.showLevel(0.5f); dock.showLiveText("hello there"); frames()
        tap(DockView.Act.FINISH); dock.showCleaning("hello there"); frames()
        dock.hideVoice(); dock.showMessage("Add an ElevenLabs key"); frames(); dock.hideVoice(); frames()
        tap(DockView.Act.KEYBOARD); relayout(); frames()
        tap(DockView.Act.HIDE); frames(); tap(DockView.Act.CURSOR); relayout(); frames()
        tap(DockView.Act.CURSOR); frames(); tap(DockView.Act.MORE); relayout(); frames()
    }
}
