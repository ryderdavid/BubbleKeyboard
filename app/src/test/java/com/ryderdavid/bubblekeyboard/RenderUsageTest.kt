package com.ryderdavid.bubblekeyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ryderdavid.bubblekeyboard.voice.Pricing
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import kotlin.math.sin
import kotlin.test.Test

/** Not a check: the usage and cost page (a mock with made-up days), into app/build/previews/usage/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderUsageTest {
    @Config(qualifiers = "w454dp-h2300dp-port-440dpi")
    @Test fun light() = render("light")

    @Config(qualifiers = "w454dp-h2300dp-port-night-440dpi")
    @Test fun dark() = render("dark")

    private val today = LocalDate.of(2026, 10, 16)

    /** Weekdays busier than weekends, a few heavy days, and Claude's share tracking dictation. */
    private fun days(): List<DayUsage> = (0 until 46).map { back ->
        val d = today.minusDays(back.toLong())
        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        val wave = 1 + 0.35 * sin(back * 1.7) + if (back % 11 == 3) 0.9 else 0.0
        val minutes = (if (weekend) 9.0 else 26.0) * wave
        val audio = (minutes * 60_000).toLong()
        val dictations = (minutes * 1.6).toInt()
        val inT = dictations * 2200L; val outT = dictations * 140L
        DayUsage(d, audio, inT, outT, Pricing.scribeCost(audio), Pricing.claudeCost("claude-haiku-5-5", inT, outT)!!)
    }.sortedBy { it.date }

    private val none = object : UsageActions {
        override fun range(days: Int) {}
        override fun exportCsv() {}
        override fun exportJson() {}
    }

    private fun render(mode: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { BubbleTheme { UsageAnalyticsScreen(days(), today, 30, LocalDate.of(2026, 10, 14), none) {} } }
        repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val out = File("build/previews/usage").apply { mkdirs() }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$mode.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
