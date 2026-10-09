package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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
import kotlin.test.Test

/**
 * Not a check: renders the bubble, ring and bloom over a fake app screen into
 * app/build/previews/, so the look (shadows, translucency) can be reviewed without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class RenderPreviewTest {

    @Test fun renderPreviews() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val pad = BubbleView(activity, null)
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) {}
            override fun onLetter(ch: Char) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
        }
        activity.setContentView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        idle(0)
        val out = File("build/previews").apply { mkdirs() }
        var downTime = 0L
        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            pad.dispatchTouchEvent(e)
            e.recycle()
        }

        save(pad, File(out, "1-bubble.png"))
        save(pad, File(out, "0-bubble-plain.png"), plain = true)
        Prefs.get(activity).edit { putInt(Prefs.BUBBLE_OPACITY, 50) }
        pad.reloadSettings()
        save(pad, File(out, "1b-bubble-50pct.png"))
        Prefs.get(activity).edit { putInt(Prefs.BUBBLE_OPACITY, 100) }
        pad.reloadSettings()
        touch(MotionEvent.ACTION_DOWN, pad.testCenter()); touch(MotionEvent.ACTION_UP, pad.testCenter())
        idle(500)
        save(pad, File(out, "2-ring.png"))
        val abc = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abc)
        idle(350)
        val d = activity.resources.displayMetrics.density
        touch(MotionEvent.ACTION_MOVE, PointF(abc.x + 12 * d, abc.y + 12 * d))
        idle(400)
        val g = pad.testLetterCenter('g')
        touch(MotionEvent.ACTION_MOVE, g)
        idle(50)
        save(pad, File(out, "3-bloom-swell70.png"))
        Prefs.get(activity).edit { putInt(Prefs.SWELL, 100) }
        pad.reloadSettings()
        idle(50)
        touch(MotionEvent.ACTION_MOVE, PointF(g.x + 1, g.y))
        idle(50)
        save(pad, File(out, "4-bloom-swell100.png"))
    }

    @Test fun renderVoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val pad = BubbleView(activity, null)
        activity.setContentView(pad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        idle(0)
        val out = File("build/previews").apply { mkdirs() }
        pad.showListening()
        pad.showLevel(0f)
        idle(300)
        save(pad, File(out, "5-voice-listening.png"), leftFrac = 0f)
        pad.showLevel(0.8f)
        pad.showLiveText("Hey, can we move the design review to Wednesday afternoon? Tuesday is")
        idle(300)
        save(pad, File(out, "6-voice-live.png"), leftFrac = 0f)
        pad.showCleaning("hey um can we move the design review to wednesday afternoon tuesday is packed for me")
        idle(200)
        save(pad, File(out, "7-voice-polishing.png"), leftFrac = 0f)
        pad.hideVoice()
        idle(300)
        pad.showMessage("Add your ElevenLabs API key in Bubble Keyboard settings")
        idle(300)
        save(pad, File(out, "8-voice-message.png"), leftFrac = 0f)

        // the open ring: countdown arc a third of the way down, then dictating from the center
        pad.hideVoice()
        pad.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) {}
            override fun onLetter(ch: Char) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
            override fun onTalkStart() = true
        }
        idle(400)
        var downTime = 0L
        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            pad.dispatchTouchEvent(e)
            e.recycle()
        }
        val c = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c)
        idle(10_000)
        save(pad, File(out, "9-ring-countdown.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c)
        pad.showListening()
        pad.showLevel(0.6f)
        pad.showLiveText("Running ten minutes late, start without me")
        idle(300)
        save(pad, File(out, "10-ring-dictating.png"), leftFrac = 0f)
        pad.testSetNavBarInset((48 * activity.resources.displayMetrics.density).toInt())
        save(pad, File(out, "11-nav-strip.png"), leftFrac = 0f)
        pad.testSetNavBarInset(0)

        // side functions: hold up for the hint, slide out onto it to arm it
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c) // stop dictating
        pad.hideVoice()
        idle(300)
        val d = activity.resources.displayMetrics.density
        val right = pad.testSlotCenter("RIGHT")
        touch(MotionEvent.ACTION_DOWN, right)
        idle(300)
        save(pad, File(out, "12-side-hint.png"), leftFrac = 0f)
        val chip = pad.testSideChip("RIGHT")
        touch(MotionEvent.ACTION_MOVE, PointF((right.x + chip.x) / 2, (right.y + chip.y) / 2))
        touch(MotionEvent.ACTION_MOVE, chip)
        idle(200)
        save(pad, File(out, "13-side-armed.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_MOVE, right)
        touch(MotionEvent.ACTION_UP, right)
        idle(300)
        val left = pad.testSlotCenter("LEFT")
        touch(MotionEvent.ACTION_DOWN, left)
        idle(300)
        save(pad, File(out, "13b-side-hint-left.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_UP, left)
        idle(300)
        val down = pad.testSlotCenter("DOWN")
        touch(MotionEvent.ACTION_DOWN, down)
        idle(300)
        save(pad, File(out, "14-side-hint-down.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_UP, down)

        // the pill's glow at a loud moment
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c)
        pad.showListening()
        pad.showLevel(1f)
        idle(200)
        save(pad, File(out, "14b-pill-listening-empty.png"), leftFrac = 0f)
        pad.showLiveText("Running ten minutes late, start without me")
        idle(600)
        repeat(12) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) } // let the level settle
        save(pad, File(out, "15-pill-glow-loud.png"), leftFrac = 0f)

        // typing into the field as you speak: label-only pill, the mic's halo breathes
        pad.voiceInField = true
        pad.showListening()
        pad.showLevel(1f)
        idle(300)
        repeat(12) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        save(pad, File(out, "16-live-listening.png"), leftFrac = 0f)
        pad.showCleaning("raw words")
        idle(200)
        save(pad, File(out, "17-live-polishing.png"), leftFrac = 0f)

        // the throb, folded bubble and open ring: quiet room then a normal voice
        fun settle(level: Float) {
            repeat(10) { pad.showLevel(level); idle(100); pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        }
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c)
        pad.hideVoice(); pad.reset(); idle(400)
        pad.showListening()
        settle(0.1f)
        save(pad, File(out, "18-throb-bubble-quiet.png"), leftFrac = 0f)
        settle(0.1f); pad.showLevel(0.55f); idle(100)
        repeat(6) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        save(pad, File(out, "19-throb-bubble-voice.png"), leftFrac = 0f)
        pad.hideVoice()
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c); idle(500) // open the ring
        pad.showListening()
        settle(0.1f); pad.showLevel(0.4f); idle(100)
        repeat(6) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        save(pad, File(out, "20-throb-ring-voice.png"), leftFrac = 0f)

        // a selection: cut / copy / paste beside the bubble, then beside the open ring
        pad.hideVoice(); pad.reset(); idle(400)
        pad.hasSelection = true
        idle(300)
        save(pad, File(out, "21-edit-bubble.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c); idle(500)
        save(pad, File(out, "22-edit-ring.png"), leftFrac = 0f)
        pad.hasSelection = false

        // listening hands free: backspace turns into the red discard key
        pad.voiceInField = true // the tap that opened the ring (tap to talk) is already listening
        pad.showListening()
        repeat(6) { pad.showLevel(0.1f); idle(100) }
        pad.showLevel(0.5f); idle(100)
        repeat(6) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        save(pad, File(out, "23-discard-key.png"), leftFrac = 0f)

        // the full keyboard grid: hold abc still, then press a pill
        pad.hideVoice(); pad.reset(); idle(400)
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c); idle(500)
        val abcKey = pad.testSlotCenter("LETTERS")
        touch(MotionEvent.ACTION_DOWN, abcKey); idle(1000); touch(MotionEvent.ACTION_UP, abcKey); idle(500)
        save(pad, File(out, "24-grid.png"), leftFrac = 0f)
        val gKey = pad.testPill('g')!!
        touch(MotionEvent.ACTION_DOWN, gKey); idle(50)
        save(pad, File(out, "25-grid-press.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_UP, gKey)
        touch(MotionEvent.ACTION_DOWN, abcKey); touch(MotionEvent.ACTION_UP, abcKey); idle(300)

        // hands free: the spin-down shows the silence left
        touch(MotionEvent.ACTION_DOWN, c); touch(MotionEvent.ACTION_UP, c)
        pad.showListening()
        idle(4_000)
        repeat(4) { pad.draw(Canvas(Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888))) }
        save(pad, File(out, "26-silence-arc.png"), leftFrac = 0f)

        // press to talk on the folded bubble: sinking, then the lock-in ripple
        pad.hideVoice(); pad.reset(); idle(400)
        val cb = pad.testCenter()
        touch(MotionEvent.ACTION_DOWN, cb); idle(120)
        save(pad, File(out, "27a-press-early.png"), leftFrac = 0f)
        idle(150)
        save(pad, File(out, "27-press-sink.png"), leftFrac = 0f)
        idle(150)
        save(pad, File(out, "28-lock-in.png"), leftFrac = 0f)
        touch(MotionEvent.ACTION_UP, cb)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The pad over a pale page with some "text", cropped to the lower right where it sits. */
    private fun save(pad: BubbleView, file: File, plain: Boolean = false, leftFrac: Float = 0.30f) {
        val w = pad.width
        val h = pad.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xF9, 0xF9, 0xF7))
        if (plain) {
            pad.draw(c)
            val top = (h * 0.42f).toInt()
            val left = (w * leftFrac).toInt()
            Bitmap.createBitmap(bmp, left, top, w - left, h - top).compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
            return
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2A, 0x2A, 0x2A) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x30, 0x30, 0x30); textSize = 46f }
        var y = h * 0.45f
        var i = 0
        while (y < h) {
            c.drawText(listOf("The quick brown fox jumps over the lazy dog again and again", "while the bubble floats above the page text and lets it show", "through the translucent disc behind the ring of keys, which", "is the whole point of this little preview of the keyboard.").get(i % 4), 60f, y, text)
            y += 70f; i++
        }
        c.drawRect(0f, h * 0.40f, w.toFloat(), h * 0.40f + 6f, line)
        pad.draw(c)
        val top = (h * 0.42f).toInt()
        val left = (w * leftFrac).toInt()
        Bitmap.createBitmap(bmp, left, top, w - left, h - top).compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
    }
}
