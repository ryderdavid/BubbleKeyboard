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
    private var downTime = 0L

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        pad = BubbleView(activity, null)
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) { keys.add(key) }
            override fun onLetter(ch: Char) { keys.add(ch) }
            override fun onLongPressCenter() { pickerShown = true }
            override fun shouldCapitalize() = capitalize
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
        openRing()
        touch(MotionEvent.ACTION_DOWN, pad.testSlotCenter("LETTERS"))
        idle(350)
        assertEquals(BubbleView.Mode.BLOOM, pad.mode)
        idle(500) // let the fan finish opening
        val g = pad.testLetterCenter('g')
        touch(MotionEvent.ACTION_MOVE, g)
        touch(MotionEvent.ACTION_UP, g)
        assertEquals(listOf<Any>('g'), keys)
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

    @Test fun tapCenterOfRingCollapsesToBubble() {
        openRing()
        tap(pad.testCenter())
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
        assertTrue(keys.isEmpty())
    }

    @Test fun longPressCenterShowsKeyboardPickerWithoutExpanding() {
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c)
        idle(600)
        touch(MotionEvent.ACTION_UP, c)
        assertTrue(pickerShown)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
    }

    @Test fun ringFoldsBackAfterIdle() {
        openRing()
        idle(3200)
        assertEquals(BubbleView.Mode.BUBBLE, pad.mode)
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
}
