package com.ryderdavid.bubblekeyboard.handsfree

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/**
 * Not a check: hands-free mode over a chat on the Fold 8 cover screen. The cursor gliding to
 * Send and the yes/no question; number badges; a scroll trail. Writes app/build/previews/handsfree/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderHandsFreeTest {
    @Config(qualifiers = "w454dp-h960dp-port-440dpi")
    @Test fun light() = render("light", false)

    @Config(qualifiers = "w454dp-h960dp-port-night-440dpi")
    @Test fun dark() = render("dark", true)

    private fun render(mode: String, night: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = activity.resources.displayMetrics.density
        val w = (454 * d).toInt()
        val h = (960 * d).toInt()
        val out = File("build/previews/handsfree").apply { mkdirs() }

        // a chat app, drawn by hand, with the element list hands-free would read from it
        val bgc = if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt()
        val onBg = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt()
        val bubbleIn = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt()
        val bubbleOut = if (night) 0xFF284777.toInt() else 0xFFD6E3FF.toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; color = onBg }
        val elements = ArrayList<Element>()
        fun app(c: Canvas) {
            c.drawColor(bgc)
            tp.textSize = 11 * d; c.drawText("9:41", 20 * d, 20 * d, tp)
            tp.textSize = 18 * d; tp.typeface = Typeface.DEFAULT_BOLD
            c.drawText("←", 18 * d, 66 * d, tp)
            p.color = 0xFF8E6BBF.toInt(); c.drawCircle(70 * d, 60 * d, 17 * d, p)
            c.drawText("Siona Okafor", 98 * d, 66 * d, tp)
            tp.typeface = Typeface.DEFAULT
            c.drawText("☏", 410 * d, 66 * d, tp)
            tp.textSize = 15 * d
            var y = 120 * d
            val msgs = listOf(
                false to "Are you coming to Camelback Saturday?", true to "Yes! Trailhead at 6:40",
                false to "Bring the GoPro", true to "Will do", false to "See you there 🌄",
            )
            for ((mine, t) in msgs) {
                val tw = tp.measureText(t)
                val r = if (mine) RectF(w - tw - 44 * d, y, w - 16 * d, y + 40 * d) else RectF(16 * d, y, 44 * d + tw, y + 40 * d)
                p.color = if (mine) bubbleOut else bubbleIn
                c.drawRoundRect(r, 18 * d, 18 * d, p)
                c.drawText(t, r.left + 14 * d, r.centerY() + 5 * d, tp)
                y += 56 * d
            }
            // compose row
            val fy = h - 120 * d
            p.color = bubbleIn; c.drawRoundRect(RectF(16 * d, fy, w - 76 * d, fy + 52 * d), 26 * d, 26 * d, p)
            tp.color = onBg.let { (it and 0x00FFFFFF) or 0x99000000.toInt() }; c.drawText("Text message", 34 * d, fy + 32 * d, tp); tp.color = onBg
            p.color = 0xFF3D5F90.toInt(); c.drawCircle(w - 42 * d, fy + 26 * d, 24 * d, p)
            tp.color = 0xFFFFFFFF.toInt(); c.drawText("➤", w - 50 * d, fy + 32 * d, tp); tp.color = onBg
        }
        val fy = (h - 120 * d).toInt()
        elements += Element(1, "Navigate up", "button", (8 * d).toInt(), (40 * d).toInt(), (44 * d).toInt(), (80 * d).toInt(), clickable = true)
        elements += Element(2, "Siona Okafor", "item", (52 * d).toInt(), (40 * d).toInt(), (240 * d).toInt(), (80 * d).toInt(), clickable = true)
        elements += Element(3, "Call", "button", (398 * d).toInt(), (40 * d).toInt(), (440 * d).toInt(), (80 * d).toInt(), clickable = true)
        for (i in 0 until 5) {
            val top = (120 * d + i * 56 * d).toInt()
            val mine = i % 2 == 1
            elements += Element(4 + i, "msg", "item", if (mine) (220 * d).toInt() else (16 * d).toInt(), top, if (mine) w - (16 * d).toInt() else (300 * d).toInt(), top + (40 * d).toInt(), longClickable = true)
        }
        elements += Element(9, "Text message", "field", (16 * d).toInt(), fy, w - (76 * d).toInt(), fy + (52 * d).toInt(), clickable = true, editable = true)
        elements += Element(10, "Send SMS", "button", w - (66 * d).toInt(), fy + (2 * d).toInt(), w - (18 * d).toInt(), fy + (50 * d).toInt(), clickable = true)
        val screen = Screen("messages", w, h, elements)

        val layer = CursorLayer(activity)
        val pill = HandsFreePill(activity) {}
        fun frame(name: String, setup: () -> Unit) {
            setup()
            layer.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, w, h)
            val pw = (w * 0.47f).toInt() // up to the cover screen's camera
            pill.pillHeight = (30 * d).toInt()
            pill.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), 0)
            pill.layout(0, 0, pw, pill.measuredHeight)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            app(c)
            layer.draw(c)
            c.save(); c.translate(8 * d, 0f); pill.draw(c); c.restore()
            // the camera, for reference
            p.color = 0xFF000000.toInt(); c.drawCircle(w / 2f, 15 * d, 7 * d, p)
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val send = screen.byId(10)!!
        frame("1-listening") { layer.badges = emptyList(); layer.testShow(w / 2f, h / 2f); pill.show(HandsFreePill.Tone.HEARD, "tap send") }
        frame("2-ask") { layer.testShow(send.centerX.toFloat(), send.centerY.toFloat()); layer.callout = "Tap Send SMS?" to "Say “yes” or “no”"; pill.show(HandsFreePill.Tone.ASK, "Tap Send SMS?", "Say “yes” or “no”") }
        frame("3-tapped") { layer.callout = null; layer.testShow(send.centerX.toFloat(), send.centerY.toFloat(), rippleAt = 0.35f); pill.show(HandsFreePill.Tone.WORKING, "Tap Send SMS") }
        frame("4-numbers") {
            layer.badges = screen.numbered.mapIndexed { i, e -> Triple(e.left, e.top, i + 1) }
            layer.testShow(screen.byId(2)!!.centerX.toFloat(), screen.byId(2)!!.centerY.toFloat())
            pill.show(HandsFreePill.Tone.LISTENING, "Hands-free · listening", "✓ Show numbers")
        }
        frame("5-scroll") {
            layer.badges = emptyList()
            layer.testShow(w * 0.7f, h * 0.55f, trailAt = floatArrayOf(w / 2f, h * 0.6f, w / 2f, h * 0.3f), trailProgress = 0.45f)
            pill.show(HandsFreePill.Tone.WORKING, "Scroll up")
        }
        frame("6-goal") {
            layer.testShow(screen.byId(9)!!.centerX.toFloat(), screen.byId(9)!!.centerY.toFloat(), rippleAt = 0.5f)
            pill.show(HandsFreePill.Tone.WORKING, "Step 2", "reply to Siona saying I'm running late")
        }
    }
}
