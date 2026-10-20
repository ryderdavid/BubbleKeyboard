package com.ryderdavid.voicebar

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
 * Not a check: the gesture hint styles (0 text, 1 arrow-tag chips, 2 end handles) as they look
 * fully in, on the cover screen (with and without the paste button) and unfolded.
 * Writes app/build/previews/hints/<screen>-<style>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderHintStylesTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun cover() { render("cover-clip", true); render("cover", false) }

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun coverDark() = render("cover-dark", true)

    @Config(qualifiers = "w890dp-h665dp-land-440dpi")
    @Test fun wide() = render("wide", true)

    private fun render(screen: String, clip: Boolean) {
        for (style in 0..2) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            Prefs.get(activity).edit { clear() }
            val dock = DockView(activity, null)
            dock.listener = object : Bar.Listener {
                override fun onKey(key: Bar.Key, repeat: Boolean) {}
                override fun onSwitchKeyboard() {}
                override fun onTalkStart() = true
            }
            dock.reloadSettings()
            dock.hintStyle = style
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val d = activity.resources.displayMetrics.density
            dock.testSetBottomInset((24 * d).toInt())
            val looper = shadowOf(Looper.getMainLooper())
            fun idle(ms: Long) { var left = ms; var i = 0; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(listOf(0.2f, 0.6f, 0.9f, 0.4f)[i++ % 4]); left -= st } }
            idle(300); dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
            if (clip) { dock.canPaste = true; dock.clipPreview = "Meet at the Camelback trailhead" }
            dock.voiceInField = true
            val r = dock.testItem(DockView.Act.MIC)!!
            val t = SystemClock.uptimeMillis()
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
            MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
            dock.showListening(); dock.heardSpeech()
            for ((k, at) in listOf("a" to 500L, "b" to 2600L)) {
            idle(if (k == "a") 500 else 2100); dock.heardSpeech(); dock.settle()
            val night = screen.contains("dark")
            val w = dock.width
            val appH = (16 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c); c.restore()
            val out = File("build/previews/hints").apply { mkdirs() }
            File(out, "$screen-$style-$k.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
