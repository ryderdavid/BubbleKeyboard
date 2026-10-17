package com.ryderdavid.bubblekeyboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ryderdavid.bubblekeyboard.voice.ModelPack
import com.ryderdavid.bubblekeyboard.voice.Voiceprint
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: "Only my voice", its page in a few states and its group on the Voice page, into app/build/previews/voiceprint/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderVoiceprintTest {
    @Config(qualifiers = "w454dp-h1700dp-port-440dpi")
    @Test fun light() = renderAll("light")

    @Config(qualifiers = "w454dp-h1700dp-port-night-440dpi")
    @Test fun dark() = renderAll("dark")

    @Config(qualifiers = "w454dp-h2900dp-port-440dpi")
    @Test fun voicePage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear(); putBoolean(Prefs.SETUP_DONE, true); putBoolean(Prefs.VOICEPRINT, true); putInt(Prefs.VOICEPRINT_STRENGTH, 4) }
        Voiceprint.save(ctx, floatArrayOf(1f, 0f))
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.VOICE.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        snap(controller.get(), "voice-page")
        controller.pause().stop().destroy()
    }

    private val none = object : VoiceprintActions {
        override fun download() {}
        override fun record() {}
        override fun stopRecording() {}
        override fun check() {}
        override fun stopCheck() {}
        override fun forget() {}
    }

    private fun renderAll(mode: String) {
        val frames = listOf(
            "1-fresh" to VoiceprintState(),
            "2-downloading" to VoiceprintState(model = ModelPack.State.Downloading(11_000_000, 26_485_263)),
            "3-recording" to VoiceprintState(model = ModelPack.State.Ready, rec = Rec.Listening(12.4f)),
            "4-saved-me" to VoiceprintState(model = ModelPack.State.Ready, hasPrint = true, rec = Rec.Done(false), check = Check(0.71f, true, 0.46f)),
            "5-check-other" to VoiceprintState(model = ModelPack.State.Ready, hasPrint = true, check = Check(0.18f, false, 0.46f)),
        )
        for ((name, state) in frames) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent { BubbleTheme { VoiceprintScreen(state, none) {} } }
            snap(controller.get(), "$mode-$name")
            controller.pause().stop().destroy()
        }
    }

    private fun snap(a: android.app.Activity, name: String) {
        repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val out = File("build/previews/voiceprint").apply { mkdirs() }
        val root = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
