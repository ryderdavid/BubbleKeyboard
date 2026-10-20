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
 * Not a check: "voice bar" turning the pill purple (idle and while dictating), and the voice pill
 * mid-swipe (short of the mark, and past it either way), on the Fold 8 cover screen.
 * Writes app/build/previews/gestures/<mode>-<state>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderGesturesTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun light() = render("light")

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark")

    private fun render(mode: String) {
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
        val levels = listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f, 0.2f)
        var li = 0
        fun idle(ms: Long) { var left = ms; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(levels[li++ % levels.size]); left -= st } }
        fun relayout() {
            idle(300); dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
        }
        dock.canPaste = true; dock.clipPreview = "Meet at the Camelback trailhead"
        dock.voiceInField = true
        relayout()
        val night = mode == "dark"
        val out = File("build/previews/gestures").apply { mkdirs() }
        fun shot(name: String) {
            dock.settle()
            val w = dock.width
            val appH = (70 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val field = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, field)
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c); c.restore()
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var down = 0L
        fun ev(action: Int, x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) down = t
            MotionEvent.obtain(down, t, action, x, y, 0).also { dock.dispatchTouchEvent(it); it.recycle() }
        }
        // idle: "voice bar" heard
        dock.showCommand("", false); idle(400); shot("1-idle-voicebar")
        dock.showCommand(null); idle(400)
        // dictating
        val r = dock.testItem(DockView.Act.MIC)!!
        ev(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); ev(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        dock.showListening(); dock.heardSpeech()
        idle(4500); dock.heardSpeech()
        dock.showCommand("", false); idle(400); shot("2-talking-voicebar")
        dock.showCommand("send", true); idle(200); shot("3-talking-send")
        dock.showCommand(null); idle(400)
        val v = dock.testItem(DockView.Act.MIC)!!
        val y = v.centerY() + dock.height - (v.height())  // touch y in view coordinates
        val mark = dock.testSwipeMark()
        fun drag(dx: Float, name: String) {
            dock.heardSpeech()
            val vr = dock.testItem(DockView.Act.MIC)!!
            val yy = vr.centerY()
            ev(MotionEvent.ACTION_DOWN, vr.centerX(), yy)
            for (i in 1..6) { ev(MotionEvent.ACTION_MOVE, vr.centerX() + dx * i / 6, yy); idle(16) }
            shot(name)
            ev(MotionEvent.ACTION_CANCEL, vr.centerX() + dx, yy)
            idle(400)
        }
        drag(mark * 0.55f, "4-swipe-right-half")
        drag(mark + 12 * d, "5-swipe-right-send")
        drag(-(mark * 0.55f), "6-swipe-left-half")
        drag(-(mark + 12 * d), "7-swipe-left-cancel")
    }
}
