package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.edit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Not a check: plays a scripted session (key presses with pop-ups, then a finger scrubbing across
 * the letter bloom) and writes one PNG per 1/30 s into app/build/previews/anim/, with a translucent
 * fingertip drawn where the finger is. tools/make_gif.py turns them into an animation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class RenderAnimationTest {
    private lateinit var pad: BubbleView
    private var downTime = 0L
    private var frame = 0
    private var finger: PointF? = null
    private lateinit var out: File
    private lateinit var crop: RectF

    @Test fun renderScrubAnimation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        pad = BubbleView(activity, null)
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) {}
            override fun onLetter(ch: Char) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
        }
        activity.setContentView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(0))
        out = File("build/previews/anim").apply { deleteRecursively(); mkdirs() }
        val d = activity.resources.displayMetrics.density
        val c = pad.testCenter()
        // a portrait window around the ring and where the bloom will fan out (up-left of it)
        crop = RectF(c.x - 260 * d, c.y - 250 * d, min(pad.width.toFloat(), c.x + 100 * d), min(pad.height.toFloat(), c.y + 95 * d))

        hold(10)
        // open the ring
        press(c); hold(3); release(c); hold(12)
        // a few ring keys, each with its pop-up
        tapKey("DELETE", 5)
        hold(6)
        tapKey("SPACE", 5)
        hold(6)
        // hold an arrow: pop-up pulses with each repeat
        tapKey("RIGHT", 26)
        hold(10)

        // hold abc, then scrub across the letters
        val abc = pad.testSlotCenter("LETTERS")
        press(abc)
        hold(12) // bloom opens at 320 ms
        val path = listOf('v', 'g', 'h', 'y', 't', 'f', 'd', 'e', 'r')
        var from = PointF(abc.x, abc.y)
        path.forEach { ch ->
            val to = pad.testLetterCenter(ch)
            glide(from, to, 7)
            hold(8)
            from = to
        }
        release(from)
        hold(14)
    }

    private fun tapKey(slot: String, frames: Int) {
        val p = pad.testSlotCenter(slot)
        press(p); hold(frames); release(p)
    }

    private fun press(p: PointF) {
        finger = p
        downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, p)
    }

    private fun release(p: PointF) {
        send(MotionEvent.ACTION_UP, p)
        finger = null
    }

    private fun glide(a: PointF, b: PointF, frames: Int) {
        for (i in 1..frames) {
            val t = i / frames.toFloat()
            val e = t * t * (3 - 2 * t)
            val p = PointF(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
            finger = p
            send(MotionEvent.ACTION_MOVE, p)
            step()
        }
    }

    private fun hold(frames: Int) = repeat(frames) { step() }

    private fun send(action: Int, p: PointF) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
        pad.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun step() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(33))
        save()
    }

    private fun save() {
        val w = pad.width
        val h = pad.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xF7, 0xF6, 0xF2))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x38, 0x38, 0x38); textSize = 44f }
        var y = 80f
        var i = 0
        while (y < h) {
            c.drawText(listOf("The quick brown fox jumps over the lazy dog again and again", "while the bubble floats above the page text and lets it show", "through the translucent disc behind the ring of keys, which", "is the whole point of this little preview of the keyboard.").get(i % 4), 30f, y, text)
            y += 68f; i++
        }
        pad.draw(c)
        finger?.let { f ->
            // a fingertip seen from above: the touch point sits near the top of the pad of the finger
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 150, 104, 84) }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.argb(150, 110, 72, 56) }
            val r = 44f * 1.0f
            val body = RectF(f.x - r * 1.05f, f.y - r * 0.75f, f.x + r * 1.05f, f.y + r * 6f)
            c.save(); c.rotate(-10f, f.x, f.y)
            c.drawRoundRect(body, r, r, skin); c.drawRoundRect(body, r, r, edge)
            c.restore()
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            c.drawCircle(f.x, f.y, 7f, dot)
        }
        val l = max(0, crop.left.toInt()); val t = max(0, crop.top.toInt())
        val r2 = min(w, crop.right.toInt()); val b = min(h, crop.bottom.toInt())
        Bitmap.createBitmap(bmp, l, t, r2 - l, b - t)
            .compress(Bitmap.CompressFormat.PNG, 100, File(out, "frame_%04d.png".format(frame++)).outputStream())
    }
}
