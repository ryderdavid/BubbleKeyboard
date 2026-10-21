package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
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
 * Not a check: the seven-button bar at rest with each choice of its own key, classic and tiered
 * colors, light and dark, stacked on a Fold cover screen's width.
 * Writes app/build/previews/extra-key/<theme>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderExtraKeyTest {
    @Config(qualifiers = "w454dp-h717dp-port-notnight-440dpi")
    @Test fun light() = render("light", 0xFFF9F9FF.toInt(), 0xFF191C20.toInt())

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark", 0xFF111318.toInt(), 0xFFE2E2E9.toInt())

    private fun render(theme: String, bg: Int, ink: Int) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = activity.resources.displayMetrics.density
        val w = activity.resources.displayMetrics.widthPixels
        val rows = Bar.ExtraKey.entries.flatMap { k -> listOf(Prefs.BAR_COLORS_CLASSIC, Prefs.BAR_COLORS_TIERED).map { k to it } }
        val bars = rows.map { (key, colors) ->
            Prefs.get(activity).edit { clear() }
            val dock = DockView(activity, null)
            dock.showAsPreview(Prefs.BAR_LAYOUT_EQUAL, colors, key.pref)
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, w, dock.measuredHeight)
            dock.settle()
            Triple("${key.name.lowercase().replace('_', ' ')} · $colors", dock, dock.measuredHeight)
        }
        val label = 22 * d
        val h = bars.sumOf { it.third + label.toInt() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13 * d }
        var y = 0f
        for ((name, dock, bh) in bars) {
            c.drawText(name, 12 * d, y + 16 * d, p)
            y += label
            c.save(); c.translate(0f, y); dock.draw(c); c.restore()
            y += bh
        }
        val out = File("build/previews/extra-key").apply { mkdirs() }
        File(out, "$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
