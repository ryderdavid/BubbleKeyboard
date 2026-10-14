package com.ryderdavid.bubblekeyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
 * Not a check: the paste button while dictating, on a clock: it shows the clip, tucks down to one
 * button, opens on a tap for a few seconds, and tucks back. Every 40 ms for the GIF, on the cover
 * and unfolded. Writes app/build/previews/paste/<screen>-<ms>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPasteTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun cover() = render("cover")

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun coverDark() = render("cover-dark")

    @Config(qualifiers = "w890dp-h665dp-land-440dpi")
    @Test fun wide() = render("wide")

    private fun render(screen: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
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
        var li = 0
        val levels = listOf(0.2f, 0.6f, 0.9f, 0.4f, 0.7f, 0.3f)
        fun idle(ms: Long) { var left = ms; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(levels[li++ % levels.size]); left -= st } }
        idle(300); dock.settle()
        dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
        dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
        dock.canPaste = true
        dock.clipPreview = "Meet at the Camelback trailhead, 6:40 sharp — bring headlamps"
        dock.voiceInField = true
        fun tap(r: android.graphics.RectF) {
            val t = SystemClock.uptimeMillis()
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
            MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
        }
        tap(dock.testItem(DockView.Act.MIC)!!)
        dock.showListening(); dock.heardSpeech()
        val start = SystemClock.uptimeMillis()
        val out = File("build/previews/paste").apply { mkdirs() }
        val night = screen.contains("dark")
        var tapped = false
        for (at in 0L..8400L step 40L) {
            val wait = start + at - SystemClock.uptimeMillis()
            if (wait > 0) idle(wait)
            dock.heardSpeech()
            if (!tapped && at >= 3600L) { tapped = true; tap(dock.testItem(DockView.Act.TALK_PASTE)!!) }
            val w = dock.width
            val appH = (16 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c); c.restore()
            File(out, "$screen-${"%05d".format(at)}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
