package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.PointF
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

    private fun openRing() {
        tap(pad.testCenter())
        idle(400)
        assertEquals(BubbleView.Mode.RING, pad.mode)
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

    @Test fun tapOpensRingAndArrowsSendKeys() {
        openRing()
        tap(pad.testSlotCenter("LEFT"))
        tap(pad.testSlotCenter("UP"))
        tap(pad.testSlotCenter("DELETE"))
        assertEquals(listOf<Any>(BubbleView.Key.LEFT, BubbleView.Key.UP, BubbleView.Key.DELETE), keys)
    }

    @Test fun spaceAndEnterSendOnRelease() {
        openRing()
        val space = pad.testSlotCenter("SPACE")
        touch(MotionEvent.ACTION_DOWN, space)
        assertTrue(keys.isEmpty())
        touch(MotionEvent.ACTION_UP, space)
        tap(pad.testSlotCenter("ENTER"))
        assertEquals(listOf<Any>(BubbleView.Key.SPACE, BubbleView.Key.ENTER), keys)
    }

    @Test fun holdingBackspaceRepeats() {
        openRing()
        val del = pad.testSlotCenter("DELETE")
        touch(MotionEvent.ACTION_DOWN, del)
        idle(400 + 50 * 4)
        touch(MotionEvent.ACTION_UP, del)
        assertTrue(keys.size >= 4, "keys: $keys")
        assertTrue(keys.all { it == BubbleView.Key.DELETE })
    }

    @Test fun holdingAbcBloomsAndReleasingOnALetterTypesIt() {
        holdOpen("LETTERS")
        val g = pad.testLetterCenter('g')
        touch(MotionEvent.ACTION_MOVE, g)
        touch(MotionEvent.ACTION_UP, g)
        assertEquals(listOf<Any>('g'), keys)
        assertFalse(pad.capsLock)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun slidingBackToAbcCancels() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc)
        idle(350)
        idle(500)
        touch(MotionEvent.ACTION_MOVE, pad.testLetterCenter('t'))
        val away = PointF(abc.x, abc.y)
        touch(MotionEvent.ACTION_MOVE, away)
        touch(MotionEvent.ACTION_UP, away)
        assertTrue(keys.isEmpty())
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun tappingAbcOpensAStickyBloom() {
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        assertEquals(BubbleView.Mode.BLOOM, pad.mode)
        idle(500)
        tap(pad.testLetterCenter('q'))
        assertEquals(listOf<Any>('q'), keys)
    }

    @Test fun autoCapsCapitalizes() {
        capitalize = true
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        idle(500)
        tap(pad.testLetterCenter('h'))
        assertEquals(listOf<Any>('H'), keys)
    }

    @Test fun tapRingCenterTogglesDictation() {
        openRing()
        tap(pad.testCenter())
        assertEquals(listOf("start"), talk)
        assertTrue(pad.talkingLatched)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testSlotCenter("SPACE")) // keys still work while dictating
        tap(pad.testCenter())
        assertEquals(listOf("start", "end"), talk)
        assertFalse(pad.talkingLatched)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertEquals(listOf<Any>(BubbleView.Key.SPACE), keys)
    }

    // ---------------------------------------------------------------- the folded bubble: tap opens, hold locks in

    /** Press and hold the folded bubble past the click, then let go. */
    private fun lockIn() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        touch(MotionEvent.ACTION_UP, c)
    }

    @Test fun aTapOpensTheRingWithoutRecording() {
        tap(pad.testCenter())
        assertEquals(BubbleView.Mode.RING, pad.mode)
        assertTrue(talk.isEmpty())
    }

    @Test fun holdingLocksInOpensTheRingAndKeepsRecordingAfterRelease() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        assertTrue(pad.testPressing(), "the press builds: tick, sink, ring")
        drawOnce()
        idle(500)
        assertEquals(listOf("start"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode, "the ring is open already")
        assertTrue(pad.testLockedIn())
        drawOnce()
        touch(MotionEvent.ACTION_UP, c)
        assertTrue(pad.talkingLatched, "letting go keeps it recording")
        assertEquals(listOf("start"), talk)
        tap(pad.testCenter()) // the center confirms
        assertEquals(listOf("start", "end"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun releasingBeforeTheClickIsJustATap() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(150)
        touch(MotionEvent.ACTION_UP, c)
        assertFalse(pad.testPressing())
        assertFalse(pad.testLockedIn())
        assertTrue(talk.isEmpty())
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

    @Test fun theThumbRestingAfterTheLockInDoesntDragTheRing() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        val open = pad.testCenter()
        touch(MotionEvent.ACTION_MOVE, PointF(c.x + 60f, c.y + 60f))
        touch(MotionEvent.ACTION_UP, PointF(c.x + 60f, c.y + 60f))
        assertEquals(open, pad.testCenter())
        assertTrue(pad.talkingLatched)
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
        val del = pad.testSlotCenter("DELETE")
        touch(MotionEvent.ACTION_DOWN, del)
        idle(1000) // no repeat while it's the discard key
        touch(MotionEvent.ACTION_UP, del)
        assertEquals(listOf("start", "cancel"), talk)
        assertTrue(keys.isEmpty(), "nothing deleted: $keys")
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(del) // back to a plain backspace
        assertEquals(listOf<Any>(BubbleView.Key.DELETE), keys)
    }

    @Test fun slidingOffTheDiscardKeyKeepsListening() {
        lockIn()
        val del = pad.testSlotCenter("DELETE")
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

    @Test fun theHeldArrowComesOutWithTheFingerAndSpringsBackIfLetGoShort() {
        openRing()
        val p = pad.testSlotCenter("RIGHT")
        val chip = pad.testSideChip("RIGHT")
        touch(MotionEvent.ACTION_DOWN, p)
        idle(250)
        assertTrue(pad.testSideHintShowing())
        val half = PointF(p.x + (chip.x - p.x) * 0.5f, p.y + (chip.y - p.y) * 0.5f)
        touch(MotionEvent.ACTION_MOVE, half)
        val span = kotlin.math.hypot(chip.x - p.x, chip.y - p.y)
        assertEquals(span * 0.5f, pad.testPullOffset("RIGHT"), 2f, "follows the finger")
        touch(MotionEvent.ACTION_UP, half)
        assertFalse(pickerShown, "let go short of the socket: nothing happens")
        assertTrue(keys.isEmpty(), "and no arrow typed: $keys")
        idle(600)
        assertEquals(0f, pad.testPullOffset("RIGHT"), 0.5f, "back in its spot")
    }

    @Test fun thePulledKeyClicksInMostOfTheWayToTheSocket() {
        openRing()
        val p = pad.testSlotCenter("RIGHT")
        val chip = pad.testSideChip("RIGHT")
        touch(MotionEvent.ACTION_DOWN, p)
        idle(250)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + (chip.x - p.x) * 0.85f, p.y + (chip.y - p.y) * 0.85f))
        val span = kotlin.math.hypot(chip.x - p.x, chip.y - p.y)
        assertEquals(span, pad.testPullOffset("RIGHT"), 1f, "snapped into the socket")
        // pulled back a little stays in; further back comes out again
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + (chip.x - p.x) * 0.7f, p.y + (chip.y - p.y) * 0.7f))
        assertEquals(span, pad.testPullOffset("RIGHT"), 1f)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + (chip.x - p.x) * 0.4f, p.y + (chip.y - p.y) * 0.4f))
        assertTrue(pad.testPullOffset("RIGHT") < span * 0.5f)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + (chip.x - p.x) * 0.9f, p.y + (chip.y - p.y) * 0.9f))
        touch(MotionEvent.ACTION_UP, PointF(p.x + (chip.x - p.x) * 0.9f, p.y + (chip.y - p.y) * 0.9f))
        assertTrue(pickerShown)
    }

    @Test fun anIdleFieldOffersALonePasteChipThatPastes() {
        pad.offerPaste()
        assertTrue(pad.testEditButtons().isEmpty(), "waits for the field to sit idle")
        idle(600)
        val chip = pad.testEditButtons()
        assertEquals(1, chip.size, "just Paste")
        tap(chip[0])
        assertEquals(listOf(BubbleView.EditAction.PASTE), edits)
        assertFalse(pad.pasteOffered, "gone once used")
    }

    @Test fun anyOtherTouchTakesThePasteChipAway() {
        pad.offerPaste()
        idle(600)
        assertTrue(pad.pasteOffered)
        tap(pad.testCenter())
        assertFalse(pad.pasteOffered)
        assertTrue(edits.isEmpty())
        assertEquals(BubbleView.Mode.RING, pad.mode, "and the tap still did its own thing")
    }

    @Test fun aSelectionTurnsThePasteChipIntoTheFullSet() {
        pad.offerPaste()
        idle(600)
        pad.hasSelection = true
        assertEquals(4, pad.testEditButtons().size)
    }

    @Test fun aTripleTapTurnsTheRingIntoTheJoystick() {
        tap(pad.testCenter())
        idle(120)
        tap(pad.testCenter())
        idle(120)
        tap(pad.testCenter())
        assertEquals(BubbleView.Mode.STICK, pad.mode)
        assertEquals(listOf("start"), stickLog)
        assertTrue(talk.isEmpty(), "the taps on the way didn't start dictation")
        assertEquals(4, pad.testEditButtons().size, "cut, copy, paste and delete stand by")
    }

    @Test fun twoQuickTapsThenAPauseIsStillATapOnTheCenter() {
        tap(pad.testCenter())
        idle(120)
        tap(pad.testCenter())
        assertTrue(talk.isEmpty(), "waits to see if a third tap comes")
        idle(400)
        assertEquals(listOf("start"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode)
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

    @Test fun sideIconsStayOnScreenAndOffTheirKeys() {
        openRing() // the ring starts tucked in the lower right, so right and down have little room outside
        val d = activity.resources.displayMetrics.density
        listOf("RIGHT", "DOWN", "LEFT").forEach { slot ->
            val chip = pad.testSideChip(slot)
            val key = pad.testSlotCenter(slot)
            assertTrue(chip.x - 20 * d >= 0 && chip.x + 20 * d <= pad.width && chip.y - 20 * d >= 0 && chip.y + 20 * d <= pad.height, "$slot icon on screen: $chip")
            assertTrue(kotlin.math.hypot(chip.x - key.x, chip.y - key.y) > 30 * d, "$slot icon clear of its key")
        }
    }

    @Test fun slideOutFromRightSwitchesKeyboards() {
        openRing()
        slideOut("RIGHT")
        assertTrue(pickerShown)
        assertTrue(keys.isEmpty(), "no arrow typed: $keys")
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun slideOutFromDownOpensSettings() {
        openRing()
        slideOut("DOWN")
        assertTrue(settingsShown)
        assertTrue(keys.isEmpty())
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun slideOutFromLeftClosesTheRingAndDropsDictation() {
        openRing()
        tap(pad.testCenter())
        slideOut("LEFT")
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertEquals(listOf("start", "cancel"), talk)
        assertTrue(keys.isEmpty())
    }

    @Test fun holdingAnArrowWithoutSlidingJustTypesIt() {
        openRing()
        val right = pad.testSlotCenter("RIGHT")
        touch(MotionEvent.ACTION_DOWN, right)
        idle(1200)
        assertTrue(pad.testSideHintShowing())
        touch(MotionEvent.ACTION_UP, right)
        assertEquals(listOf<Any>(BubbleView.Key.RIGHT), keys)
        assertFalse(pickerShown)
    }

    @Test fun slidingOutAndBackCancelsTheSideFunction() {
        openRing()
        slideOut("RIGHT", release = false)
        val right = pad.testSlotCenter("RIGHT")
        touch(MotionEvent.ACTION_MOVE, right)
        touch(MotionEvent.ACTION_UP, right)
        assertFalse(pickerShown)
        assertEquals(listOf<Any>(BubbleView.Key.RIGHT), keys) // back on the key: a plain press
    }

    @Test fun tapThenTapAndHoldRepeats() {
        openRing()
        val left = pad.testSlotCenter("LEFT")
        tap(left)
        idle(100)
        touch(MotionEvent.ACTION_DOWN, left)
        idle(1000)
        touch(MotionEvent.ACTION_UP, left)
        assertTrue(keys.size > 5 && keys.all { it == BubbleView.Key.LEFT }, "repeated: $keys")
        assertEquals(BubbleView.Mode.RING, pad.mode) // no side function in the repeat
    }

    @Test fun slowSecondTapDoesntRepeat() {
        openRing()
        val left = pad.testSlotCenter("LEFT")
        tap(left)
        idle(500)
        touch(MotionEvent.ACTION_DOWN, left)
        idle(1000)
        touch(MotionEvent.ACTION_UP, left)
        assertEquals(listOf<Any>(BubbleView.Key.LEFT, BubbleView.Key.LEFT), keys)
    }

    @Test fun slideOutFromUpPinsTheRingOpen() {
        openRing()
        slideOut("UP")
        assertTrue(pad.pinned)
        assertTrue(keys.isEmpty(), "no arrow typed: $keys")
        idle(Prefs.DEFAULT_RING_TIMEOUT * 1000L + 2000)
        assertEquals(BubbleView.Mode.RING, pad.mode, "a pinned ring doesn't fold up by itself")
        assertTrue(Prefs.get(activity).getBoolean(Prefs.RING_PINNED, false), "remembered")
        pad.reset()
        assertEquals(BubbleView.Mode.RING, pad.mode, "and comes back open")
        slideOut("UP")
        assertFalse(pad.pinned)
        idle(Prefs.DEFAULT_RING_TIMEOUT * 1000L + 1000)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode, "unpinned: the countdown is back")
    }

    @Test fun aPinnedRingStillFoldsFromTheLeftArrow() {
        openRing()
        slideOut("UP")
        slideOut("LEFT")
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertTrue(pad.pinned)
    }

    @Test fun holdingAndWigglingEveryRingKeyIsSafe() {
        // a long hold with finger jitter on each key, including sliding out and back, never throws
        listOf("UP", "DELETE", "RIGHT", "ENTER", "DOWN", "LETTERS", "LEFT", "SPACE").forEach { slot ->
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
        openRing()
        idle(4_500)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        idle(1_000)
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
        tap(pad.testSlotCenter("SPACE"))
        idle(3_500)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun draggingTheBubbleMovesAndRemembersIt() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        val target = PointF(start.x - 300f, start.y - 600f)
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

    @Test fun bloomFitsOnScreenWhenParkedAtEachEdge() {
        val w = pad.width.toFloat()
        val h = pad.height.toFloat()
        // drag the pad to each edge (it clamps itself), then bloom and check every letter is on screen
        listOf(PointF(0f, h * 0.6f), PointF(w, h * 0.6f), PointF(w / 2, 0f), PointF(w / 2, h)).forEach { spot ->
            pad.reset()
            idle(200)
            val c = pad.testCenter()
            touch(MotionEvent.ACTION_DOWN, c)
            idle(200)
            touch(MotionEvent.ACTION_MOVE, PointF((c.x + spot.x) / 2, (c.y + spot.y) / 2))
            idle(200)
            touch(MotionEvent.ACTION_MOVE, spot)
            idle(200)
            touch(MotionEvent.ACTION_UP, spot)
            idle(400)
            assertEquals(BubbleView.Mode.BUBBLE, pad.mode) // dragging just moves it
            tap(pad.testCenter())
            idle(400)
            assertEquals(BubbleView.Mode.RING, pad.mode)
            tap(pad.testSlotCenter("LETTERS"))
            idle(500)
            val safe = pad.testSafeArea()
            val bounds = pad.testLetterBounds()
            assertTrue(bounds.left >= safe.left && bounds.right <= safe.right && bounds.top >= safe.top && bounds.bottom <= safe.bottom,
                "letters $bounds outside $safe with the pad at $spot")
            // and the letters keep QWERTY order along each row: q is left of p unless the fan is turned steeply
            val q = pad.testLetterCenter('q')
            val p = pad.testLetterCenter('p')
            assertTrue(q.x < p.x || kotlin.math.abs(q.y - p.y) > kotlin.math.abs(q.x - p.x), "q $q p $p at $spot")
            tap(pad.testSlotCenter("LETTERS")) // close the sticky bloom
        }
    }

    @Test fun tapOpenedBloomClosesByItself() {
        openRing()
        tap(pad.testSlotCenter("LETTERS"))
        assertEquals(BubbleView.Mode.BLOOM, pad.mode)
        idle(6500)
        assertTrue(pad.mode != BubbleView.Mode.BLOOM)
    }

    @Test fun liftingTheFirstFingerStopsRepeatEvenIfAnotherIsDown() {
        openRing()
        val right = pad.testSlotCenter("DELETE")
        touch(MotionEvent.ACTION_DOWN, right)
        // second finger down elsewhere, then the first finger lifts
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = right.x; y = right.y }, MotionEvent.PointerCoords().apply { x = 5f; y = 5f })
        val t = SystemClock.uptimeMillis()
        pad.dispatchTouchEvent(MotionEvent.obtain(downTime, t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0))
        pad.dispatchTouchEvent(MotionEvent.obtain(downTime, t, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0))
        val sent = keys.size
        idle(1000)
        assertEquals(sent, keys.size, "repeat kept going: $keys")
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

    @Test fun keysPartAroundTheFinger() {
        holdOpen("LETTERS")
        val g = pad.testLetterCenter('g')
        touch(MotionEvent.ACTION_MOVE, g)
        // neighbours are drawn farther from the finger than where they sit, so they peek out around it
        listOf('f', 'h', 't', 'y', 'v', 'b').forEach { ch ->
            val rest = pad.testLetterCenter(ch)
            val drawn = pad.testDrawnCenter(ch)
            val before = kotlin.math.hypot(rest.x - g.x, rest.y - g.y)
            val after = kotlin.math.hypot(drawn.x - g.x, drawn.y - g.y)
            assertTrue(after > before + 5f, "$ch: $before -> $after")
        }
        // and far keys move much less than near ones
        fun moved(ch: Char) = pad.testLetterCenter(ch).let { a -> pad.testDrawnCenter(ch).let { b -> kotlin.math.hypot(a.x - b.x, a.y - b.y) } }
        assertTrue(moved('p') < moved('h') / 3, "p ${moved('p')} vs h ${moved('h')}")
        touch(MotionEvent.ACTION_UP, g)
        assertEquals(listOf<Any>('g'), keys)
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

    @Test fun capsLockDoesNotChangePunctuationOrNumbers() {
        openGrid()
        val shift = pad.testPill('\u21E7')!!
        tap(shift); tap(shift)
        assertTrue(pad.capsLock)
        tap(pad.testSlotCenter("LETTERS")) // close the grid
        touch(MotionEvent.ACTION_DOWN, pad.testSlotCenter("ENTER"))
        idle(350)
        val one = pad.testLetterCenter('1')
        touch(MotionEvent.ACTION_MOVE, one)
        touch(MotionEvent.ACTION_UP, one)
        assertEquals(listOf<Any>('1'), keys)
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

    @Test fun movingTowardALetterDoesNotOpenTheGrid() {
        holdOpen("LETTERS")
        touch(MotionEvent.ACTION_MOVE, pad.testLetterCenter('r'))
        idle(800)
        assertFalse(pad.gridOpen)
        touch(MotionEvent.ACTION_UP, pad.testLetterCenter('r'))
        assertEquals(listOf<Any>('r'), keys)
    }

    @Test fun holdingSpaceBloomsPunctuation() {
        holdOpen("SPACE")
        assertEquals(BubbleView.Bloom.PUNCTUATION, pad.bloomKind)
        val q = pad.testLetterCenter('?')
        touch(MotionEvent.ACTION_MOVE, q)
        touch(MotionEvent.ACTION_UP, q)
        assertEquals(listOf<Any>('?'), keys)
    }

    @Test fun tappingSpaceStillTypesASpace() {
        openRing()
        tap(pad.testSlotCenter("SPACE"))
        assertEquals(listOf<Any>(BubbleView.Key.SPACE), keys)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun holdingEnterBloomsNumbers() {
        holdOpen("ENTER")
        assertEquals(BubbleView.Bloom.NUMBERS, pad.bloomKind)
        val seven = pad.testLetterCenter('7')
        touch(MotionEvent.ACTION_MOVE, seven)
        touch(MotionEvent.ACTION_UP, seven)
        assertEquals(listOf<Any>('7'), keys)
    }

    @Test fun everyBloomFitsOnScreenNearEdges() {
        val w = pad.width.toFloat()
        val h = pad.height.toFloat()
        listOf("SPACE", "ENTER").forEach { slot ->
            listOf(PointF(0f, h * 0.6f), PointF(w, h * 0.6f), PointF(w / 2, 0f)).forEach { spot ->
                pad.reset(); idle(200)
                val c = pad.testCenter()
                touch(MotionEvent.ACTION_DOWN, c)
                touch(MotionEvent.ACTION_MOVE, PointF((c.x + spot.x) / 2, (c.y + spot.y) / 2))
                touch(MotionEvent.ACTION_MOVE, spot)
                touch(MotionEvent.ACTION_UP, spot)
                idle(400)
                touch(MotionEvent.ACTION_DOWN, pad.testSlotCenter(slot))
                idle(750)
                val safe = pad.testSafeArea()
                val b = pad.testLetterBounds()
                assertTrue(b.left >= safe.left && b.right <= safe.right && b.top >= safe.top && b.bottom <= safe.bottom, "$slot bloom $b outside $safe at $spot")
                touch(MotionEvent.ACTION_UP, pad.testSlotCenter(slot))
            }
        }
    }

    @Test fun swellSettingControlsHowFarKeysPart() {
        fun hMovedAt(percent: Int): Float {
            Prefs.get(activity).edit { putInt(Prefs.SWELL, percent) }
            pad.reloadSettings()
            pad.reset(); idle(200)
            holdOpen("LETTERS")
            val g = pad.testLetterCenter('g')
            touch(MotionEvent.ACTION_MOVE, g)
            val rest = pad.testLetterCenter('h')
            val drawn = pad.testDrawnCenter('h')
            touch(MotionEvent.ACTION_UP, pad.testSlotCenter("LETTERS"))
            return kotlin.math.hypot(rest.x - drawn.x, rest.y - drawn.y)
        }
        val off = hMovedAt(0)
        val mid = hMovedAt(50)
        val full = hMovedAt(100)
        assertTrue(off < 1f, "0% moved $off")
        assertTrue(full > mid && mid > off, "$off < $mid < $full")
        // at full strength the next key over clears a fingertip (~25 dp radius) with room to spare
        val d = activity.resources.displayMetrics.density
        val g = pad.testLetterCenter('g')
        assertTrue(full > 30 * d, "full moved ${full / d} dp")
    }
}
