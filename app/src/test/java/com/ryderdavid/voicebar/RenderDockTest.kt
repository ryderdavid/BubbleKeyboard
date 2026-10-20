package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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

/** Not a check: renders the docked bar's states under a fake app into app/build/previews/dock/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class RenderDockTest {
    @Test fun light() = renderAll("light")

    @Config(qualifiers = "+night")
    @Test fun dark() = renderAll("dark")

    private fun renderAll(mode: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
        var down = 0L
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
            override fun onTalkStart() = true
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * d).toInt())
        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun relayout() {
            idle(300)
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            dock.settle()
        }
        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            dock.dispatchTouchEvent(e); e.recycle()
        }
        fun tap(a: DockView.Act) { val r = dock.testItem(a)!!; val p = PointF(r.centerX(), r.centerY()); touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(30) }
        val out = File("build/previews/dock").apply { mkdirs() }
        val pal = Palette(activity)
        val night = mode == "dark"
        fun shotImpl(name: String, lay: Boolean) {
            if (lay) relayout()
            val w = dock.width
            val appH = (300 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt() }
            val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            c.drawRoundRect(16 * d, 130 * d, 280 * d, 180 * d, 20 * d, 20 * d, bubble)
            c.drawText("Still on for the trailhead at 6:40?", 30 * d, 160 * d, p)
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, bubble)
            c.drawText("Message", 34 * d, appH - 30 * d, p.apply { alpha = 150 })
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c)
            // the gesture pill the system draws over our surface
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBloomKey; alpha = 140 }
            val h = dock.height.toFloat()
            c.drawRoundRect(w / 2f - 54 * d, h - 14 * d, w / 2f + 54 * d, h - 10 * d, 2 * d, 2 * d, pill)
            c.restore()
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun shotRaw(name: String) = shotImpl(name, false)
        fun shot(name: String) = shotImpl(name, true)
        fun hold(a: DockView.Act) { val r = dock.testItem(a)!!; touch(MotionEvent.ACTION_DOWN, PointF(r.centerX(), r.centerY())); idle(400) }
        fun release(a: DockView.Act) { val r = dock.testItem(a)!!; touch(MotionEvent.ACTION_UP, PointF(r.centerX(), r.centerY())); idle(30) }
        dock.settle()
        shot("01-idle")
        // the morph, frame by frame: Voice stretching out as the rest shrink away
        tap(DockView.Act.MIC)
        dock.showListening()
        repeat(16) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f)[i % 6]) }
        for (f in 1..3) { dock.testFrame(); shotRaw("02-morph-$f"); idle(40); dock.testFrame() }
        dock.settle()
        shot("03-listening")
        repeat(80) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f)[i % 7]) }
        idle(7_000)
        dock.settle()
        shot("04-draining")
        dock.heardSpeech()
        dock.voiceInField = false
        dock.showLiveText("Running ten minutes late, start without me")
        dock.settle()
        shot("05-listening-text")
        tap(DockView.Act.MIC)
        dock.showCleaning("raw")
        dock.talkEnded()
        dock.settle()
        shot("06-polishing")
        dock.hideVoice()
        dock.settle()
        hold(DockView.Act.BACKSPACE)
        dock.settle()
        shot("07-hold-backspace")
        run {
            val sp = dock.testItem(DockView.Act.SPACE_ALT)!!
            touch(MotionEvent.ACTION_MOVE, PointF(sp.centerX(), sp.centerY()))
        }
        dock.settle()
        shot("08-tray-space")
        run {
            val sp = dock.testItem(DockView.Act.SPACE_ALT)!!
            touch(MotionEvent.ACTION_UP, PointF(sp.centerX(), sp.centerY())); idle(30)
        }
        dock.canPaste = true
        tap(DockView.Act.CURSOR)
        dock.settle()
        shot("10-trackpad")
        run {
            val pad = dock.testItem(DockView.Act.PAD)!!
            touch(MotionEvent.ACTION_DOWN, PointF(pad.left + dock.resources.displayMetrics.density * 28f, pad.centerY()))
            shot("10b-trackpad-tap-left")
            touch(MotionEvent.ACTION_UP, PointF(pad.left + dock.resources.displayMetrics.density * 28f, pad.centerY())); idle(30)
        }
        tap(DockView.Act.CURSOR)
        dock.settle()
        dock.clips = listOf(
            ClipHistory.Clip("Pick up oat milk and the dry cleaning on the way home", 9, pinned = true),
            ClipHistory.Clip("https://ryder.works/Voicebar/", 8),
            ClipHistory.Clip("Meet at the north entrance of the station at noon", 7),
            ClipHistory.Clip("4417 N 2nd St", 6),
            ClipHistory.Clip("Thanks! Talk soon", 5),
        )
        tap(DockView.Act.MORE)
        dock.settle()
        shot("11-menu")
        run {
            val row = dock.testItem(DockView.Act.CLIP_ROW)!!
            val p = PointF(row.left + row.width() / 2, row.centerY())
            touch(MotionEvent.ACTION_DOWN, p); idle(600); touch(MotionEvent.ACTION_UP, p); idle(30)
            shot("11b-menu-clip-options")
            val q = PointF(row.left + row.width() / 2, row.centerY())
            touch(MotionEvent.ACTION_DOWN, q); touch(MotionEvent.ACTION_MOVE, PointF(q.x - row.width() / 2, q.y)); idle(16)
            shot("11c-menu-clips-scrolled")
            touch(MotionEvent.ACTION_UP, PointF(q.x - row.width() / 2, q.y)); idle(30)
        }
        tap(DockView.Act.MORE)
        dock.settle()
        dock.showMessage("Add your ElevenLabs key in settings")
        dock.settle()
        shot("12-message")
    }
}
