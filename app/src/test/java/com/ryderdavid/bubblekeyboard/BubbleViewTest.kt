package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.edit
import kotlin.math.hypot
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class BubbleViewTest {
    private lateinit var activity: Activity
    private lateinit var pad: BubbleView
    private val keys = mutableListOf<Any>()
    private var capitalize = false
    private var pickerShown = false
    private var settingsShown = false
    private val edits = mutableListOf<BubbleView.EditAction>()
    private val talk = mutableListOf<String>()
    private var canTalk = true
    private var downTime = 0L

    private val stickLog = mutableListOf<String>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // most tests drive the ring by hand; tap-to-talk gets its own tests below
        Prefs.get(activity).edit { clear() }
        pad = BubbleView(activity, null)
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) { keys.add(key) }
            override fun onLetter(ch: Char) { keys.add(ch) }
            override fun onSwitchKeyboard() { pickerShown = true }
            override fun onOpenSettings() { settingsShown = true }
            override fun onEdit(action: BubbleView.EditAction) { edits.add(action) }
            override fun shouldCapitalize() = capitalize
            override fun onTalkStart(): Boolean { talk.add(if (canTalk) "start" else "refused"); return canTalk }
            override fun onStickStart() { stickLog.add("start") }
            override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) { stickLog.add("step $cols $lines ${if (selecting) "sel" else "aim"}") }
            override fun onStickTap() { stickLog.add("tap") }
            override fun onStickSelect() { stickLog.add("select") }
            override fun onStickEnd() { stickLog.add("end") }
            override fun onTalkEnd(cancel: Boolean) { talk.add(if (cancel) "cancel" else "end") }
        }
        activity.setContentView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        idle(0)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun touch(action: Int, p: PointF): Boolean {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
        return pad.dispatchTouchEvent(e).also { e.recycle() }
    }

    private fun tap(p: PointF) {
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_UP, p)
    }

    /** Opens the ring without recording (a tap on the bubble opens it listening). */
    private fun openRing() {
        pad.testOpenRing()
        idle(400)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun theRingHasTheNotchesFourKeys() {
        openRing()
        tap(pad.testSlotCenter("STICK"))
        assertEquals(BubbleView.Mode.STICK, pad.mode, "right: the joystick")
        pad.reset(); idle(100); openRing()
        tap(pad.testSlotCenter("LETTERS"))
        assertTrue(pad.gridOpen, "up: the full keyboard")
        pad.reset(); idle(100); openRing()
        tap(pad.testSlotCenter("SETTINGS"))
        assertTrue(settingsShown, "down: settings")
        pad.reset(); idle(100); openRing()
        tap(pad.testSlotCenter("PASTE"))
        assertTrue(edits.isEmpty(), "left: paste, grayed with nothing to paste")
        pad.canPaste = true
        tap(pad.testSlotCenter("PASTE"))
        assertEquals(listOf(BubbleView.EditAction.PASTE), edits)
        assertTrue(keys.isEmpty() && talk.isEmpty())
    }

    @Test fun startsAsBubbleInsideTheScreen() {
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        val c = pad.testCenter()
        assertTrue(c.x > 0 && c.x < pad.width && c.y > 0 && c.y < pad.height, "center $c in ${pad.width}x${pad.height}")
        val rects = pad.testTouchRects()
        assertTrue(rects.any { it.contains(c.x.toInt(), c.y.toInt()) })
        // the touch region is the bubble, not the whole window
        assertFalse(rects.any { it.contains(10, 10) })
        assertTrue(rects.all { it.width() < pad.width / 3 })
    }

    @Test fun tapOutsideBubbleIsNotConsumed() {
        assertFalse(touch(MotionEvent.ACTION_DOWN, PointF(10f, 10f)))
    }




    @Test fun tappingAbcOpensTheFullKeyboard() {
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        assertTrue(pad.gridOpen, "no letter bloom: abc opens the grid")
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testPill('g')!!)
        assertEquals(listOf<Any>('g'), keys)
        tap(pad.testSlotCenter("LETTERS"))
        assertFalse(pad.gridOpen, "abc (now a ✕) closes it")
    }

    @Test fun holdingAbcDoesNotOpenABloom() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc)
        idle(1200)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        touch(MotionEvent.ACTION_UP, abc)
        assertTrue(pad.gridOpen)
    }

    @Test fun slidingAlongTheSpaceBarMovesTheCursor() {
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        idle(300)
        val space = pad.testPill(' ')!!
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, space)
        touch(MotionEvent.ACTION_MOVE, PointF(space.x - 50 * d, space.y))
        touch(MotionEvent.ACTION_MOVE, PointF(space.x - 50 * d, space.y - 60 * d)) // drifting off the bar keeps sliding
        touch(MotionEvent.ACTION_MOVE, PointF(space.x - 32 * d, space.y))
        touch(MotionEvent.ACTION_UP, PointF(space.x - 32 * d, space.y))
        val lefts = keys.count { it == BubbleView.Key.LEFT }
        val rights = keys.count { it == BubbleView.Key.RIGHT }
        assertTrue(lefts in 3..5, "left steps: $keys")
        assertTrue(rights in 1..3, "and back: $keys")
        assertFalse(keys.contains(BubbleView.Key.SPACE), "a slide types no space")
        keys.clear()
        tap(space)
        assertEquals(listOf<Any>(BubbleView.Key.SPACE), keys, "a tap still types one")
    }


    @Test fun autoCapsCapitalizes() {
        capitalize = true
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        idle(500)
        tap(pad.testPill('h')!!)
        assertEquals(listOf<Any>('H'), keys)
    }

    @Test fun tapRingCenterTogglesDictation() {
        openRing()
        tap(pad.testCenter())
        assertEquals(listOf("start"), talk)
        assertTrue(pad.talkingLatched)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testCenter())
        assertEquals(listOf("start", "end"), talk)
        assertFalse(pad.talkingLatched)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    // ---------------------------------------------------------------- the folded bubble: tap or hold records, the left notch opens

    /** Press and hold the folded bubble past the click, then let go. */
    /** Start dictating from the folded bubble: a tap. */
    private fun lockIn() {
        tap(pad.testCenter())
        idle(100)
    }

    @Test fun aTapOpensTheRingListening() {
        tap(pad.testCenter())
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertEquals(listOf("start"), talk)
        assertTrue(pad.talkingLatched, "keeps listening after the finger lifts")
    }


    @Test fun releasingBeforeTheClickIsJustATap() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(150)
        touch(MotionEvent.ACTION_UP, c)
        assertFalse(pad.testPressing())
        assertFalse(pad.testLockedIn())
        assertEquals(listOf("start"), talk, "let go before the click: a tap, which records too")
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun withoutAKeyTheLockInStillOpensTheRing() {
        canTalk = false
        lockIn()
        assertEquals(listOf("refused"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertFalse(pad.talkingLatched)
    }

    @Test fun draggingDuringThePressMovesTheBubbleInstead() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        idle(100)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 200f, start.y - 200f))
        assertFalse(pad.testPressing())
        idle(600)
        touch(MotionEvent.ACTION_UP, PointF(start.x - 200f, start.y - 200f))
        assertTrue(talk.isEmpty())
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }


    @Test fun holdingTheRingCenterIsJustATap() {
        openRing()
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(1000)
        assertTrue(talk.isEmpty())
        touch(MotionEvent.ACTION_UP, c)
        assertEquals(listOf("start"), talk)
        assertTrue(pad.talkingLatched)
    }

    @Test fun backspaceDiscardsWhileListening() {
        lockIn()
        val del = pad.testSlotCenter("PASTE")
        touch(MotionEvent.ACTION_DOWN, del)
        idle(1000)
        touch(MotionEvent.ACTION_UP, del)
        assertEquals(listOf("start", "cancel"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode, "thrown away; the ring stays")
        assertTrue(edits.isEmpty(), "discarding pastes nothing")
    }

    @Test fun slidingOffTheDiscardKeyKeepsListening() {
        lockIn()
        val del = pad.testSlotCenter("PASTE")
        touch(MotionEvent.ACTION_DOWN, del)
        touch(MotionEvent.ACTION_MOVE, PointF(del.x - 300f, del.y + 300f))
        touch(MotionEvent.ACTION_UP, PointF(del.x - 300f, del.y + 300f))
        assertTrue(pad.talkingLatched)
        drawOnce()
    }

    @Test fun dictationThatEndsOnItsOwnUnlatches() {
        openRing()
        tap(pad.testCenter())
        pad.talkEnded() // e.g. the network dropped
        assertFalse(pad.talkingLatched)
        tap(pad.testCenter()) // the next tap starts again rather than stopping
        assertEquals(listOf("start", "start"), talk)
    }

    /** Hold [slot], then slide out toward its side icon and let go there. */
    private fun slideOut(slot: String, release: Boolean = true) {
        val p = pad.testSlotCenter(slot)
        val chip = pad.testSideChip(slot)
        touch(MotionEvent.ACTION_DOWN, p)
        idle(250)
        assertTrue(pad.testSideHintShowing(), "hint shows while holding $slot")
        for (step in 1..6) touch(MotionEvent.ACTION_MOVE, PointF(p.x + (chip.x - p.x) * step / 6, p.y + (chip.y - p.y) * step / 6))
        if (release) touch(MotionEvent.ACTION_UP, chip)
    }






    /** Press the folded bubble and slide [frac] of the way to notch [dir] (0 left, 1 right, 2 up, 3 down), then let go. */
    private fun slideToNotch(dir: Int, frac: Float = 1f) {
        val c = pad.testCenter()
        val n = pad.testNotch(dir)
        touch(MotionEvent.ACTION_DOWN, c)
        idle(60)
        for (k in 1..4) touch(MotionEvent.ACTION_MOVE, PointF(c.x + (n.x - c.x) * frac * k / 4, c.y + (n.y - c.y) * frac * k / 4))
        touch(MotionEvent.ACTION_UP, PointF(c.x + (n.x - c.x) * frac, c.y + (n.y - c.y) * frac))
        idle(50)
    }

    @Test fun slidingRightFromTheBubbleOpensTheJoystick() {
        slideToNotch(1)
        assertEquals(BubbleView.Mode.STICK, pad.mode)
        assertEquals(listOf("start"), stickLog)
        assertTrue(talk.isEmpty(), "a slide never starts recording")
        assertEquals(4, pad.testEditButtons().size, "cut, copy, paste and delete stand by")
    }

    @Test fun slidingLeftPastesUpTheKeyboardDownSettings() {
        slideToNotch(0)
        assertTrue(edits.isEmpty(), "nothing on the clipboard: the paste notch is grayed and won't click in")
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        pad.canPaste = true
        slideToNotch(0)
        assertEquals(listOf(BubbleView.EditAction.PASTE), edits)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode, "pasting leaves the bubble folded")
        pad.reset(); idle(100)
        slideToNotch(2)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertTrue(pad.gridOpen, "up: straight to the full keyboard")
        pad.reset(); idle(100)
        slideToNotch(3)
        assertTrue(settingsShown)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun aShortSlideOrALongHoldDoesNothingAndATapRecords() {
        val start = pad.testCenter()
        slideToNotch(1, 0.5f)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertTrue(stickLog.isEmpty() && talk.isEmpty())
        idle(600)
        assertEquals(start.x, pad.testCenter().x, 0.5f, "the bubble stays where it was")
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(3000)
        assertTrue(talk.isEmpty(), "holding never starts recording")
        assertEquals(1, pad.testNotchPhase, "the notches stay up as long as the finger does")
        touch(MotionEvent.ACTION_UP, c)
        idle(100)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode, "let go in place after a hold: nothing")
        assertTrue(talk.isEmpty())
        tap(c)
        assertEquals(listOf("start"), talk, "a quick tap records")
    }

    @Test fun aDiagonalSlideMovesTheBubble() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(60)
        touch(MotionEvent.ACTION_MOVE, PointF(c.x - 60f, c.y - 60f))
        idle(200)
        touch(MotionEvent.ACTION_MOVE, PointF(c.x - 200f, c.y - 220f))
        idle(200)
        touch(MotionEvent.ACTION_UP, PointF(c.x - 200f, c.y - 220f))
        assertEquals(c.x - 200f, pad.testCenter().x, 1f)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertTrue(stickLog.isEmpty())
    }

    @Test fun pullingAgainstAClickedInNotchStrainsThenLetsGoBack() {
        val c = pad.testCenter()
        val n = pad.testNotch(2)
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, c)
        idle(60)
        for (k in 1..4) touch(MotionEvent.ACTION_MOVE, PointF(c.x, c.y + (n.y - c.y) * k / 4))
        assertEquals(0f, pad.testNotchStrain)
        touch(MotionEvent.ACTION_MOVE, PointF(c.x, n.y - 28 * d))
        assertEquals(0.5f, pad.testNotchStrain, 0.05f)
        assertEquals(2, pad.testNotchPhase, "still held by the notch")
        touch(MotionEvent.ACTION_MOVE, PointF(c.x, n.y))
        assertEquals(0f, pad.testNotchStrain, "eased off: the pull relaxes")
        touch(MotionEvent.ACTION_UP, PointF(c.x, n.y))
        idle(100)
        assertTrue(pad.gridOpen, "let go inside the break: the notch still fires")
    }

    @Test fun draggingOnPastANotchBreaksFreeAndMovesTheBubble() {
        val c = pad.testCenter()
        val d = activity.resources.displayMetrics.density
        for ((dir, sx, sy) in listOf(Triple(2, 0f, -1f), Triple(0, -1f, 0f))) {
            val start = pad.testCenter()
            val n = pad.testNotch(dir)
            val reach = hypot(n.x - start.x, n.y - start.y)
            touch(MotionEvent.ACTION_DOWN, start)
            idle(60)
            for (k in 1..4) touch(MotionEvent.ACTION_MOVE, PointF(start.x + (n.x - start.x) * k / 4, start.y + (n.y - start.y) * k / 4))
            assertEquals(2, pad.testNotchPhase, "clicked into the notch first")
            val far = reach + 110 * d
            val end = PointF(start.x + sx * far, start.y + sy * far)
            touch(MotionEvent.ACTION_MOVE, PointF(start.x + sx * (reach + 70 * d), start.y + sy * (reach + 70 * d)))
            idle(200)
            touch(MotionEvent.ACTION_MOVE, end)
            idle(200)
            touch(MotionEvent.ACTION_UP, end)
            idle(100)
            assertEquals(BubbleView.Mode.BUBBLE, pad.mode, "broke free: nothing fired")
            assertFalse(pad.gridOpen)
            assertEquals(end.x, pad.testCenter().x, 1f)
            assertEquals(end.y, pad.testCenter().y, 1f)
        }
        assertTrue(stickLog.isEmpty() && talk.isEmpty() && !settingsShown)
        assertTrue(c.y > pad.testCenter().y)
    }

    @Test fun pushingTheKnobMovesTheTargetFasterTheFurtherItGoes() {
        pad.testEnterStick()
        idle(300)
        val c = pad.testCenter()
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, c)
        touch(MotionEvent.ACTION_MOVE, PointF(c.x + 12 * d, c.y))
        pad.testStickRun(1000)
        val slow = stickLog.filter { it.startsWith("step") }.sumOf { it.split(" ")[1].toInt() }
        stickLog.clear()
        touch(MotionEvent.ACTION_MOVE, PointF(c.x + 200 * d, c.y))
        pad.testStickRun(1000)
        val fast = stickLog.filter { it.startsWith("step") }.sumOf { it.split(" ")[1].toInt() }
        assertTrue(slow in 1..6, "a crawl near the middle: $slow")
        assertTrue(fast > 20, "quick at the rim: $fast")
        assertTrue(stickLog.all { !it.startsWith("step") || it.endsWith("aim") })
        assertTrue(kotlin.math.hypot(pad.testKnob().x - c.x, pad.testKnob().y - c.y) < 70 * d, "the knob stays inside the base")
        touch(MotionEvent.ACTION_UP, PointF(c.x + 200 * d, c.y))
        assertFalse(stickLog.contains("tap"), "a push isn't a tap")
    }

    @Test fun upAndDownMoveByLines() {
        pad.testEnterStick()
        idle(300)
        val c = pad.testCenter()
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, c)
        touch(MotionEvent.ACTION_MOVE, PointF(c.x + 5 * d, c.y - 200 * d))
        pad.testStickRun(500)
        val steps = stickLog.filter { it.startsWith("step") }
        assertTrue(steps.isNotEmpty() && steps.all { it.split(" ")[1] == "0" && it.split(" ")[2].toInt() < 0 }, "lines up only: $steps")
    }

    @Test fun tappingTheKnobMovesTheCursorAndHoldingSelects() {
        pad.testEnterStick()
        idle(300)
        val c = pad.testCenter()
        tap(c)
        assertEquals("tap", stickLog.last())
        assertEquals(BubbleView.Mode.STICK, pad.mode, "stays in the joystick")
        touch(MotionEvent.ACTION_DOWN, c)
        idle(450)
        assertEquals("select", stickLog.last())
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_MOVE, PointF(c.x - 200 * d, c.y))
        pad.testStickRun(300)
        assertTrue(stickLog.last().endsWith("sel"), "${stickLog.last()}")
        touch(MotionEvent.ACTION_UP, PointF(c.x - 200 * d, c.y))
        assertFalse(stickLog.takeLast(1).contains("tap"))
    }

    @Test fun theCloseButtonFoldsTheJoystickAway() {
        pad.testEnterStick()
        idle(300)
        val x = pad.testStickExit()
        assertTrue(pad.testTouchRects().any { it.contains(x.x.toInt(), x.y.toInt()) }, "touchable")
        tap(x)
        assertEquals("end", stickLog.last())
        idle(400)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }











    @Test fun holdingAndWigglingEveryRingKeyIsSafe() {
        // a long hold with finger jitter on each key, including sliding out and back, never throws
        listOf("LETTERS", "STICK", "SETTINGS", "PASTE").forEach { slot ->
            pad.reset(); idle(50); openRing()
            val p = pad.testSlotCenter(slot)
            touch(MotionEvent.ACTION_DOWN, p)
            for (i in 0 until 20) {
                idle(80)
                touch(MotionEvent.ACTION_MOVE, PointF(p.x + (i % 5 - 2) * 6f, p.y + (i % 3 - 1) * 6f))
            }
            touch(MotionEvent.ACTION_UP, p)
            drawOnce()
            idle(400)
        }
    }

    @Test fun editButtonsOnlyWithASelection() {
        assertTrue(pad.testEditButtons().isEmpty())
        pad.hasSelection = true
        assertEquals(4, pad.testEditButtons().size)
        pad.hasSelection = false
        assertTrue(pad.testEditButtons().isEmpty())
    }

    @Test fun editButtonsSitBesideTheBubbleOnTheRoomierSideAndAreTouchable() {
        pad.hasSelection = true
        idle(200)
        val c = pad.testCenter()
        val b = pad.testEditButtons()
        // the bubble starts at the lower right: the column goes to its left, on screen
        assertTrue(b.all { it.x < c.x && it.x > 0 }, "left of the bubble: $b")
        assertTrue(b.map { it.x }.distinct().size == 1, "a column: $b")
        val rects = pad.testTouchRects()
        b.forEach { p -> assertTrue(rects.any { it.contains(p.x.toInt(), p.y.toInt()) }, "touchable: $p") }
    }

    @Test fun editButtonsFollowTheOpenRing() {
        pad.hasSelection = true
        openRing()
        val c = pad.testCenter()
        val d = activity.resources.displayMetrics.density
        pad.testEditButtons().forEach { assertTrue(kotlin.math.hypot(it.x - c.x, it.y - c.y) > 80 * d, "outside the disc: $it") }
    }

    @Test fun tappingEditButtonsSendsCutCopyPasteDelete() {
        pad.hasSelection = true
        idle(200)
        pad.testEditButtons().forEach { tap(it) }
        assertEquals(listOf(BubbleView.EditAction.CUT, BubbleView.EditAction.COPY, BubbleView.EditAction.PASTE, BubbleView.EditAction.DELETE), edits)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode) // didn't open the ring
        assertTrue(talk.isEmpty())
    }

    @Test fun slidingOffAnEditButtonCancels() {
        pad.hasSelection = true
        idle(200)
        val p = pad.testEditButtons()[0]
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x - 200f, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x - 200f, p.y))
        assertTrue(edits.isEmpty())
    }

    @Test fun throbIsNormalizedToTheVoicesRange() {
        pad.showListening()
        repeat(20) { pad.showLevel(0.05f); idle(100) } // a quiet room
        repeat(3) { pad.showLevel(0.45f); idle(100) } // a normal voice, nowhere near the raw maximum
        assertTrue(pad.testVoiceLevelTarget() > 0.95f, "normal speech fills the throb: ${pad.testVoiceLevelTarget()}")
        pad.showLevel(0.05f)
        assertTrue(pad.testVoiceLevelTarget() < 0.05f, "silence empties it: ${pad.testVoiceLevelTarget()}")
    }

    @Test fun liveTypingPillIsJustItsLabel() {
        pad.voiceInField = true
        pad.showListening()
        pad.showLevel(0.8f)
        drawOnce()
        pad.showCleaning("raw words")
        drawOnce()
        assertEquals(BubbleView.Voice.CLEANING, pad.voice)
    }

    @Test fun voicePillStatesDontCrashDrawing() {
        pad.showListening()
        pad.showLevel(0.7f)
        pad.showLiveText("hello there this is a fairly long sentence that should wrap onto a few lines in the pill above the bubble")
        drawOnce()
        pad.showCleaning("hello there")
        drawOnce()
        pad.showMessage("Something went wrong")
        assertEquals(BubbleView.Voice.MESSAGE, pad.voice)
        idle(3100)
        assertEquals(BubbleView.Voice.OFF, pad.voice)
    }

    private fun drawOnce() {
        val bmp = android.graphics.Bitmap.createBitmap(pad.width, pad.height, android.graphics.Bitmap.Config.ARGB_8888)
        pad.draw(android.graphics.Canvas(bmp))
    }

    @Test fun ringFoldsBackAfterTheTimeout() {
        openRing() // some of the 5 s already gone
        idle(3_500)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        idle(2_000)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun ringTimeoutIsASetting() {
        Prefs.get(activity).edit { putInt(Prefs.RING_TIMEOUT, 20) }
        pad.reloadSettings()
        openRing()
        idle(19_000)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        idle(1_500)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun ringStaysOpenWhileDictating() {
        Prefs.get(activity).edit { putInt(Prefs.SILENCE_TIMEOUT, 60) }
        pad.reloadSettings()
        openRing()
        tap(pad.testCenter())
        idle(50_000)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testCenter())
        idle(31_000)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun touchingTheRingRestartsTheCountdown() {
        openRing()
        idle(3_500)
        val c = pad.testCenter()
        val d = activity.resources.displayMetrics.density
        tap(PointF(c.x + 34f * d, c.y - 34f * d)) // the disc between keys
        idle(3_500)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun draggingTheBubbleMovesAndRemembersIt() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        val target = PointF(start.x - 300f, start.y - 600f)
        idle(50)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 40f, start.y - 40f)) // a diagonal start: moving, not a notch
        idle(200)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 150f, start.y - 300f))
        idle(200)
        touch(MotionEvent.ACTION_MOVE, target)
        idle(200)
        touch(MotionEvent.ACTION_UP, target)
        val moved = pad.testCenter()
        assertEquals(target.x, moved.x, 1f)
        assertEquals(target.y, moved.y, 1f)
        val prefs = Prefs.get(activity)
        assertTrue(prefs.all.keys.any { it.startsWith(Prefs.POSITION_PREFIX) })
    }

    @Test fun aQuickFlickThrowsTheBubbleAndItGlidesToAStop() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        // 120 px every 16 ms toward the upper left: a flick
        var p = start
        repeat(4) { i ->
            idle(16)
            p = PointF(start.x - 120f * (i + 1), start.y - 120f * (i + 1))
            touch(MotionEvent.ACTION_MOVE, p)
        }
        touch(MotionEvent.ACTION_UP, p)
        assertTrue(pad.testFlinging, "it keeps going")
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode) // kicking it doesn't open it
        idle(3000)
        assertFalse(pad.testFlinging)
        val end = pad.testCenter()
        assertTrue(end.x <= p.x && end.y < p.y - 20, "glided past where the finger let go: $p -> $end")
        assertTrue(end.x > 0 && end.y > 0, "stayed on screen: $end")
        assertTrue(talk.isEmpty())
    }

    @Test fun aHardThrowAtTheEdgeEndsUpAgainstIt() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        var p = start
        idle(16); touch(MotionEvent.ACTION_MOVE, PointF(start.x - 40f, start.y - 40f)) // a diagonal start: moving, not a notch
        repeat(4) { i -> idle(16); p = PointF(start.x - 150f * (i + 1), start.y); touch(MotionEvent.ACTION_MOVE, p) }
        touch(MotionEvent.ACTION_UP, p)
        idle(3000)
        assertFalse(pad.testFlinging)
        val d = activity.resources.displayMetrics.density
        val edge = pad.testSafeArea().left + 79 * d // the ring's radius plus its margin
        assertEquals(edge, pad.testCenter().x, 2f) // comes to rest flush against the edge
        val bounce = pad.lastFlingBounce / d
        assertTrue(bounce in 2f..20f, "a little bounce, not back into the way: $bounce dp")
    }

    @Test fun aSlowDragJustStopsWhereYouLetGo() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        var p = start
        repeat(4) { i ->
            idle(200)
            p = PointF(start.x - 40f * (i + 1), start.y - 40f * (i + 1))
            touch(MotionEvent.ACTION_MOVE, p)
        }
        touch(MotionEvent.ACTION_UP, p)
        assertFalse(pad.testFlinging)
        assertEquals(p.x, pad.testCenter().x, 1f)
    }

    @Test fun aTouchCatchesTheGlidingBubble() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        var p = start
        repeat(4) { i -> idle(16); p = PointF(start.x - 120f * (i + 1), start.y - 80f * (i + 1)); touch(MotionEvent.ACTION_MOVE, p) }
        touch(MotionEvent.ACTION_UP, p)
        idle(50)
        val caught = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, caught)
        assertFalse(pad.testFlinging)
        touch(MotionEvent.ACTION_UP, caught)
    }



    // ---------------------------------------------------------------- swell, caps lock, more blooms

    /** Holds a ring key until its bloom opens, then nudges the finger off the key (as when aiming), so caps lock won't trigger. */
    private fun holdOpen(slot: String) {
        openRing()
        val key = pad.testSlotCenter(slot)
        touch(MotionEvent.ACTION_DOWN, key)
        idle(350)
        assertEquals(BubbleView.Mode.BLOOM, pad.mode)
        val nudge = 12 * pad.resources.displayMetrics.density
        touch(MotionEvent.ACTION_MOVE, PointF(key.x + nudge, key.y + nudge))
        idle(400) // fan finished opening
    }


    private fun openGrid() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc)
        idle(1000)
        touch(MotionEvent.ACTION_UP, abc) // lifting on abc after the grid opens does nothing more
        idle(300)
    }

    @Test fun longStillHoldOnAbcOpensTheGrid() {
        openGrid()
        assertTrue(pad.gridOpen)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertTrue(keys.isEmpty())
        tap(pad.testPill('h')!!)
        tap(pad.testPill('i')!!)
        assertEquals(listOf<Any>('h', 'i'), keys)
        assertTrue(pad.gridOpen, "stays open while typing")
    }

    @Test fun theGridStaysOpenUntilAbcClosesIt() {
        openGrid()
        idle(30_000)
        assertTrue(pad.gridOpen)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testSlotCenter("LETTERS"))
        assertFalse(pad.gridOpen)
        assertTrue(keys.isEmpty())
        idle(6_000) // the ring's own countdown is back
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun shiftCapitalizesOnceAndDoubleTapLocks() {
        openGrid()
        val shift = pad.testPill('\u21E7')!!
        tap(shift)
        tap(pad.testPill('a')!!)
        tap(pad.testPill('b')!!)
        assertEquals(listOf<Any>('A', 'b'), keys)
        tap(shift); tap(shift) // double tap: caps lock
        assertTrue(pad.capsLock)
        tap(pad.testPill('c')!!); tap(pad.testPill('d')!!)
        assertEquals(listOf<Any>('A', 'b', 'C', 'D'), keys)
        tap(shift)
        assertFalse(pad.capsLock)
    }

    @Test fun theGridIsOnScreenAboveTheRingAndTouchable() {
        openGrid()
        val c = pad.testCenter()
        val rects = pad.testTouchRects()
        "qpazm".forEach { ch ->
            val p = pad.testPill(ch)!!
            assertTrue(p.x > 0 && p.x < pad.width && p.y > 0 && p.y < c.y, "$ch on screen above the ring: $p")
            assertTrue(rects.any { it.contains(p.x.toInt(), p.y.toInt()) }, "$ch touchable")
        }
        // staggered: a sits half a key right of q, z a key and a half
        val q = pad.testPill('q')!!; val a = pad.testPill('a')!!; val z = pad.testPill('z')!!
        val step = pad.testPill('w')!!.x - q.x
        assertTrue(kotlin.math.abs((a.x - q.x) - step / 2) < 2f && kotlin.math.abs((z.x - q.x) - step * 1.5f) < 2f)
    }

    @Test fun theGridHasSpaceBackspaceAndEnterWhereYouExpect() {
        openGrid()
        val m = pad.testPill('m')!!; val p = pad.testPill('p')!!
        val bksp = pad.testPill('\u232B')!!; val space = pad.testPill(' ')!!
        val enter = pad.testPill('\u21B5')!!; val v = pad.testPill('v')!!
        assertTrue(kotlin.math.abs(bksp.y - m.y) < 1f && bksp.x > m.x, "backspace at the end of the z row")
        val step = pad.testPill('w')!!.x - pad.testPill('q')!!.x
        val middle = (pad.testPill('q')!!.x + p.x) / 2
        assertTrue(space.y > m.y && kotlin.math.abs(space.x - middle) <= step, "space bar under the middle: ${space.x} vs $middle")
        assertTrue(v.x > space.x - 3 * step && v.x < space.x + 3 * step)
        assertTrue(kotlin.math.abs(enter.y - space.y) < 1f && enter.x > space.x && enter.x >= p.x - 30, "enter at the bottom right")
        tap(pad.testPill('h')!!); tap(space); tap(pad.testPill(',')!!); tap(pad.testPill('.')!!); tap(enter); tap(bksp)
        assertEquals(listOf<Any>('h', BubbleView.Key.SPACE, ',', '.', BubbleView.Key.ENTER, BubbleView.Key.DELETE), keys)
    }

    @Test fun gridBackspaceRepeatsWhileHeld() {
        openGrid()
        val bksp = pad.testPill('\u232B')!!
        touch(MotionEvent.ACTION_DOWN, bksp)
        idle(1000)
        touch(MotionEvent.ACTION_UP, bksp)
        assertTrue(keys.size > 5 && keys.all { it == BubbleView.Key.DELETE }, "repeated: $keys")
        val n = keys.size
        idle(500)
        assertEquals(n, keys.size, "stops on release")
    }

    @Test fun slidingAcrossPillsTypesTheOneYouLiftOn() {
        openGrid()
        touch(MotionEvent.ACTION_DOWN, pad.testPill('e')!!)
        touch(MotionEvent.ACTION_MOVE, pad.testPill('r')!!)
        touch(MotionEvent.ACTION_UP, pad.testPill('r')!!)
        assertEquals(listOf<Any>('r'), keys)
    }


    // ---------------------------------------------------------------- hands-free silence timeout

    @Test fun handsFreeDictationFinishesAfterTenSecondsOfSilence() {
        openRing()
        tap(pad.testCenter())
        idle(9_000)
        assertTrue(pad.talkingLatched)
        idle(1_500)
        assertFalse(pad.talkingLatched)
        assertEquals(listOf("start", "end"), talk)
    }

    @Test fun speechRestartsTheSilenceClock() {
        openRing()
        tap(pad.testCenter())
        idle(7_000)
        pad.heardSpeech()
        idle(7_000)
        assertTrue(pad.talkingLatched)
        idle(3_500)
        assertFalse(pad.talkingLatched)
    }

    @Test fun silenceTimeoutIsASetting() {
        Prefs.get(activity).edit { putInt(Prefs.SILENCE_TIMEOUT, 3) }
        pad.reloadSettings()
        openRing()
        tap(pad.testCenter())
        idle(3_500)
        assertEquals(listOf("start", "end"), talk)
    }





}
