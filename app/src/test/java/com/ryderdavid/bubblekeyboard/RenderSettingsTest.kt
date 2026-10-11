package com.ryderdavid.bubblekeyboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: renders each settings page, light and dark, into app/build/previews/settings/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-port-xxhdpi")
class RenderSettingsTest {

    @Test fun light() = renderAll("light")

    @Config(qualifiers = "+night")
    @Test fun dark() = renderAll("dark")

    /** The voice page whole (a tall screen), with some dictation logged so the cost rows show. */
    @Config(qualifiers = "w411dp-h1500dp")
    @Test fun voiceUsage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val log = com.ryderdavid.bubblekeyboard.voice.UsageLog.of(ctx)
        log.clear()
        repeat(40) { log.addAudio(25_000); log.addClaude("claude-haiku-5-5", 420, 60) }
        val out = File("build/previews/settings").apply { mkdirs() }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.VOICE.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "voice-usage.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun renderAll(mode: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear(); putBoolean(Prefs.RING_PINNED, false) }
        val out = File("build/previews/settings").apply { mkdirs() }
        for (page in Page.entries) {
            val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page.name)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
            repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            val root = controller.get().window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$mode-${page.ordinal}-${page.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            controller.pause().stop().destroy()
        }
        @Suppress("UNUSED_VARIABLE") val unused = RuntimeEnvironment.getApiLevel()
    }
}
