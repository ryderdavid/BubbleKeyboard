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
    private val talk = mutableListOf<String>()
    private var canTalk = true
    private var downTime = 0L

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        pad = BubbleView(activity, null)
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) { keys.add(key) }
            override fun onLetter(ch: Char) { keys.add(ch) }
            override fun onSwitchKeyboard() { pickerShown = true }
            override fun onOpenSettings() { settingsShown = true }
            override fun shouldCapitalize() = capitalize
            override fun onTalkStart(): Boolean { talk.add(if (canTalk) "start" else "refused"); return canTalk }
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

    @Test fun holdingAnArrowRepeats() {
        openRing()
        val right = pad.testSlotCenter("RIGHT")
        touch(MotionEvent.ACTION_DOWN, right)
        idle(400 + 50 * 4)
        touch(MotionEvent.ACTION_UP, right)
        assertTrue(keys.size >= 4, "keys: $keys")
        assertTrue(keys.all { it == BubbleView.Key.RIGHT })
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

    @Test fun holdRingCenterTalksWhileHeld() {
        openRing()
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        assertEquals(listOf("start"), talk)
        assertFalse(pad.talkingLatched)
        touch(MotionEvent.ACTION_UP, c)
        assertEquals(listOf("start", "end"), talk)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun dictationThatEndsOnItsOwnUnlatches() {
        openRing()
        tap(pad.testCenter())
        pad.talkEnded() // e.g. the network dropped
        assertFalse(pad.talkingLatched)
        tap(pad.testCenter()) // the next tap starts again rather than stopping
        assertEquals(listOf("start", "start"), talk)
    }

    private fun longPress(slot: String) {
        val p = pad.testSlotCenter(slot)
        touch(MotionEvent.ACTION_DOWN, p)
        idle(600)
        touch(MotionEvent.ACTION_UP, p)
    }

    @Test fun longPressUpSwitchesKeyboards() {
        openRing()
        longPress("UP")
        assertTrue(pickerShown)
        assertTrue(keys.isEmpty(), "no arrow typed: $keys")
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun longPressDownOpensSettings() {
        openRing()
        longPress("DOWN")
        assertTrue(settingsShown)
        assertTrue(keys.isEmpty())
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun longPressLeftClosesTheRingAndDropsDictation() {
        openRing()
        tap(pad.testCenter())
        longPress("LEFT")
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertEquals(listOf("start", "cancel"), talk)
        assertTrue(keys.isEmpty())
    }

    @Test fun arrowsWithLongPressesDontRepeat() {
        openRing()
        val up = pad.testSlotCenter("UP")
        touch(MotionEvent.ACTION_DOWN, up)
        idle(300)
        touch(MotionEvent.ACTION_UP, up)
        assertEquals(listOf<Any>(BubbleView.Key.UP), keys)
    }

    @Test fun holdingTheBubbleTalksAndReleaseTypes() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        assertEquals(listOf("start"), talk)
        touch(MotionEvent.ACTION_UP, c)
        assertEquals(listOf("start", "end"), talk)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertFalse(pickerShown)
    }

    @Test fun aRefusedTalkNeitherListensNorOpensTheRing() {
        canTalk = false
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        touch(MotionEvent.ACTION_UP, c)
        assertEquals(listOf("refused"), talk)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun slidingAwayWhileTalkingCancels() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(500)
        val away = PointF(c.x - 400f, c.y - 400f)
        touch(MotionEvent.ACTION_MOVE, away)
        touch(MotionEvent.ACTION_UP, away)
        assertEquals(listOf("start", "cancel"), talk)
        assertEquals(pad.testCenter(), c) // the bubble didn't move
    }

    @Test fun quickTapAndDragDontTalk() {
        tap(pad.testCenter())
        assertTrue(talk.isEmpty())
        assertEquals(BubbleView.Mode.RING, pad.mode)
        pad.reset()
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 200f, start.y - 200f))
        idle(600)
        touch(MotionEvent.ACTION_UP, PointF(start.x - 200f, start.y - 200f))
        assertTrue(talk.isEmpty())
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
        idle(29_000)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        idle(1_500)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun ringTimeoutIsASetting() {
        Prefs.get(activity).edit { putInt(Prefs.RING_TIMEOUT, 5) }
        pad.reloadSettings()
        openRing()
        idle(5_500)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun ringStaysOpenWhileDictating() {
        openRing()
        tap(pad.testCenter())
        idle(60_000)
        assertEquals(BubbleView.Mode.RING, pad.mode)
        tap(pad.testCenter())
        idle(31_000)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun touchingTheRingRestartsTheCountdown() {
        openRing()
        idle(20_000)
        tap(pad.testSlotCenter("SPACE"))
        idle(20_000)
        assertEquals(BubbleView.Mode.RING, pad.mode)
    }

    @Test fun draggingTheBubbleMovesAndRemembersIt() {
        val start = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, start)
        val target = PointF(start.x - 300f, start.y - 600f)
        touch(MotionEvent.ACTION_MOVE, PointF(start.x - 150f, start.y - 300f))
        touch(MotionEvent.ACTION_MOVE, target)
        touch(MotionEvent.ACTION_UP, target)
        val moved = pad.testCenter()
        assertEquals(target.x, moved.x, 1f)
        assertEquals(target.y, moved.y, 1f)
        val prefs = Prefs.get(activity)
        assertTrue(prefs.all.keys.any { it.startsWith(Prefs.POSITION_PREFIX) })
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
            touch(MotionEvent.ACTION_MOVE, PointF((c.x + spot.x) / 2, (c.y + spot.y) / 2))
            touch(MotionEvent.ACTION_MOVE, spot)
            touch(MotionEvent.ACTION_UP, spot)
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
        val right = pad.testSlotCenter("RIGHT")
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

    @Test fun longStillHoldOnAbcTurnsOnCapsLock() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc)
        idle(1200)
        assertTrue(pad.capsLock)
        touch(MotionEvent.ACTION_UP, abc) // lifting on abc types nothing
        assertTrue(keys.isEmpty())
        tap(abc)
        idle(500)
        tap(pad.testLetterCenter('g'))
        assertEquals(listOf<Any>('G'), keys)
    }

    @Test fun holdingStillAgainTurnsCapsLockOff() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        repeat(2) {
            touch(MotionEvent.ACTION_DOWN, abc)
            idle(1200)
            touch(MotionEvent.ACTION_UP, abc)
            idle(200)
        }
        assertFalse(pad.capsLock)
    }

    @Test fun movingTowardALetterDoesNotTurnOnCapsLock() {
        holdOpen("LETTERS")
        touch(MotionEvent.ACTION_MOVE, pad.testLetterCenter('r'))
        idle(800)
        assertFalse(pad.capsLock)
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

    @Test fun capsLockDoesNotChangePunctuationOrNumbers() {
        openRing()
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc); idle(1200); touch(MotionEvent.ACTION_UP, abc)
        assertTrue(pad.capsLock)
        touch(MotionEvent.ACTION_DOWN, pad.testSlotCenter("ENTER"))
        idle(750)
        val one = pad.testLetterCenter('1')
        touch(MotionEvent.ACTION_MOVE, one)
        touch(MotionEvent.ACTION_UP, one)
        assertEquals(listOf<Any>('1'), keys)
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
