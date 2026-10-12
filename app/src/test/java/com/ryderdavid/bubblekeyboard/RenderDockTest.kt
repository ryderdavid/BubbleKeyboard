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
        dock.listener = object : BubbleView.Listener {
            override fun onKey(key: BubbleView.Key, repeat: Boolean) {}
            override fun onLetter(ch: Char) {}
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
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            idle(300)
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
        fun shot(name: String) {
            relayout()
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
        shot("1-idle")
        dock.canPaste = true
        tap(DockView.Act.MIC)
        dock.showListening()
        repeat(16) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f)[i % 6]) }
        shot("2-listening")
        repeat(80) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f)[i % 7]) }
        idle(7_000)
        shot("2b-draining")
        dock.heardSpeech()
        dock.voiceInField = false
        dock.showLiveText("Running ten minutes late, start without me")
        shot("3-listening-text")
        dock.showCleaning("raw")
        dock.talkEnded()
        shot("4-polishing")
        dock.hideVoice()
        dock.hasSelection = true
        shot("5-selection")
        dock.hasSelection = false
        tap(DockView.Act.KEYBOARD)
        shot("6-keys")
        tap(DockView.Act.HIDE)
        tap(DockView.Act.CURSOR)
        shot("7-trackpad")
        tap(DockView.Act.CURSOR)
        tap(DockView.Act.MORE)
        shot("8-menu")
        tap(DockView.Act.MORE)
        dock.showMessage("Add your ElevenLabs key in settings")
        shot("9-message")
    }
}
