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

/** Not a check: the first-run tips on the bar, Fold 8 cover screen. Writes app/build/previews/tips/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTipsTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun light() = render("light", false)

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark", true)

    private fun render(mode: String, night: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val r = activity.resources
        fun s(id: Int) = r.getString(id)
        val tips = listOf(
            Triple("1-voice", DockView.Tip("voice", s(R.string.tip_voice_title), s(R.string.tip_voice), 1, 6), false),
            Triple("2-finish", DockView.Tip("voice", s(R.string.tip_finish_title), s(R.string.tip_finish), 2, 6), false),
            Triple("3-abc", DockView.Tip("abc", s(R.string.tip_abc_title), s(R.string.tip_abc), 3, 6), false),
            Triple("4-cursor", DockView.Tip("cursor", s(R.string.tip_cursor_title), s(R.string.tip_cursor), 4, 6), false),
            Triple("5-bksp", DockView.Tip("bksp", s(R.string.tip_bksp_title), s(R.string.tip_bksp), 5, 6), false),
            Triple("6-voicebar", DockView.Tip("voice", s(R.string.tip_voicebar_title), s(R.string.tip_voicebar), 6, 6), false),
            Triple("7-ask", DockView.Tip("abc", s(R.string.tip_ask_title), s(R.string.tip_ask), 3, 6, ask = true), false),
            Triple("8-polishing", null, true),
        )
        val out = File("build/previews/tips").apply { mkdirs() }
        val d = r.displayMetrics.density
        for ((name, tip, talking) in tips) {
            val dock = DockView(activity, null)
            dock.listener = object : Bar.Listener {
                override fun onKey(key: Bar.Key, repeat: Boolean) {}
                override fun onSwitchKeyboard() {}
                override fun onTalkStart() = true
            }
            dock.reloadSettings()
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dock.testSetBottomInset((24 * d).toInt())
            val looper = shadowOf(Looper.getMainLooper())
            fun idle(ms: Long) { looper.idleFor(Duration.ofMillis(ms)); dock.settle() }
            idle(300)
            if (talking) {
                val m = dock.testItem(DockView.Act.MIC)!!
                val t = SystemClock.uptimeMillis()
                MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, m.centerX(), m.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
                MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, m.centerX(), m.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
                dock.showListening()
                dock.showLiveText("Meet at the trailhead at")
                idle(600)
                dock.heardSpeech()
                // finish: the pill polishes, with its pulse partway across
                dock.testItem(DockView.Act.MIC)!!.let { m ->
                    val t2 = SystemClock.uptimeMillis()
                    MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, m.centerX(), m.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
                    MotionEvent.obtain(t2, t2 + 40, MotionEvent.ACTION_UP, m.centerX(), m.centerY(), 0).also { dock.dispatchTouchEvent(it); it.recycle() }
                }
                dock.showCleaning("Meet at the trailhead at")
                idle(500)
            }
            dock.tip = tip
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            idle(700)
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            dock.settle()
            // the app above, then the bar (its top floats over the app)
            val w = dock.width
            val appH = (300 * d).toInt()
            val total = appH + dock.height - dock.floatRoom()
            val bmp = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            for (i in 0 until 4) c.drawRoundRect(16 * d, (24 + i * 40) * d, (200 + (i % 2) * 90) * d, (56 + i * 40) * d, 16 * d, 16 * d, p)
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, p)
            c.save(); c.translate(0f, (appH - dock.floatRoom()).toFloat()); dock.draw(c); c.restore()
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
