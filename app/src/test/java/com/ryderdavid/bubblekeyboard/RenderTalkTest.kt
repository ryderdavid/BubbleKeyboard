package com.ryderdavid.bubblekeyboard

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

/**
 * Not a check: the recording bar over time on the Fold 8 cover screen — the "Tap to finish
 * recording" hint morphing in and out of the waveform, with and without the paste button.
 * Writes app/build/previews/talk/<mode>-<clip>-<ms>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTalkTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun light() { render("light", true); render("light", false) }

    /** Every 40 ms for 7.6 s: frames for an animated preview (app/build/previews/talk-anim/). */
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun animation() = render("anim", true, (0L..7600L step 40L).toList(), "talk-anim")

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark", true)

    private fun render(
        mode: String, clip: Boolean,
        times: List<Long> = listOf(0L, 1400L, 1720L, 2000L, 3000L, 5000L, 5320L, 5650L, 6200L, 6550L, 6850L),
        dir: String = "talk",
    ) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
        var down = 0L
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun onTalkStart() = true
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * d).toInt())
        val looper = shadowOf(Looper.getMainLooper())
        val levels = listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f, 0.2f)
        var li = 0
        fun idle(ms: Long) { var left = ms; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(levels[li++ % levels.size]); left -= st } }
        fun relayout() {
            idle(300); dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
        }
        if (clip) { dock.canPaste = true; dock.clipPreview = "Meet at the Camelback trailhead, 6:40 sharp — bring headlamps" }
        dock.voiceInField = true
        relayout()
        val night = mode == "dark"
        val out = File("build/previews/$dir").apply { mkdirs() }
        // start dictation, then photograph moments on the clock
        val r = dock.testItem(DockView.Act.MIC)!!
        val p = PointF(r.centerX(), r.centerY())
        down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, p.x, p.y, 0).also { dock.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(down, down, MotionEvent.ACTION_UP, p.x, p.y, 0).also { dock.dispatchTouchEvent(it); it.recycle() }
        dock.showListening()
        dock.settle()
        val start = SystemClock.uptimeMillis()
        for (at in times) {
            // keep the pill from draining in the preview, as speech would
            if (at % 400L == 0L) dock.heardSpeech()
            val wait = start + at - SystemClock.uptimeMillis()
            if (wait > 0) idle(wait)
            dock.settle()
            val w = dock.width
            val appH = (70 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val field = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, field)
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt(); alpha = 140 }
            c.drawRoundRect(w / 2f - 54 * d, dock.height - 14 * d, w / 2f + 54 * d, dock.height - 10 * d, 2 * d, 2 * d, pill)
            c.restore()
            File(out, "$mode-${if (clip) "clip" else "noclip"}-${"%05d".format(at)}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
