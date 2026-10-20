package com.ryderdavid.voicebar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: the first-launch setup, each page in a typical state, into app/build/previews/wizard/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderWizardTest {
    @Config(qualifiers = "w454dp-h1000dp-port-440dpi")
    @Test fun light() = renderAll("light")

    @Config(qualifiers = "w454dp-h1000dp-port-night-440dpi")
    @Test fun dark() = renderAll("dark")

    private val none = object : WizardActions {
        override fun next() {}
        override fun back() {}
        override fun skip() {}
        override fun goTo(step: WizardStep) {}
        override fun openKeyboardSettings() {}
        override fun pickKeyboard() {}
        override fun getGboard() {}
        override fun askMic() {}
        override fun openUrl(url: String) {}
        override fun keyText(key: WizardActions.Key, text: String) {}
        override fun pasteKey(key: WizardActions.Key) {}
        override fun checkKey(key: WizardActions.Key) {}
        override fun downloadParakeet() {}
        override fun openAccessibility() {}
        override fun openAppInfo() {}
        override fun allowBackground() {}
        override fun tryIt() {}
        override fun finish() {}
    }

    private fun renderAll(mode: String) {
        val fresh = WizardState(sideloaded = true)
        val ready = SettingsActivity.Setup(enabled = true, current = true, mic = true, gboard = true, gboardBubble = false)
        val frames = listOf(
            "01-welcome" to (WizardStep.WELCOME to fresh),
            "02-keyboards" to (WizardStep.KEYBOARDS to fresh.copy(setup = SettingsActivity.Setup(enabled = true))),
            "03-switch" to (WizardStep.SWITCH to fresh.copy(setup = SettingsActivity.Setup(enabled = true, gboard = true))),
            "04-mic" to (WizardStep.MIC to fresh.copy(setup = SettingsActivity.Setup(enabled = true, current = true, gboard = true))),
            "05-speech-bad" to (WizardStep.SPEECH to fresh.copy(setup = ready, eleven = KeyField("sk_3f9a1c27b8e04d6aa", check = KeyCheck.BAD, message = "ElevenLabs didn’t accept that key"))),
            "06-speech-ok" to (WizardStep.SPEECH to fresh.copy(setup = ready, eleven = KeyField("sk_3f9a1c27b8e04d6aa1", saved = true, check = KeyCheck.OK))),
            "06b-speech-parakeet" to (WizardStep.SPEECH to fresh.copy(setup = ready, parakeet = com.ryderdavid.voicebar.voice.ModelPack.State.Downloading(281_000_000, 663_000_000))),
            "07-cleanup" to (WizardStep.CLEANUP to fresh.copy(setup = ready, eleven = KeyField(saved = true))),
            "08-companion" to (WizardStep.COMPANION to fresh.copy(setup = ready, eleven = KeyField(saved = true), claude = KeyField(saved = true))),
            "08b-companion-on" to (WizardStep.COMPANION to fresh.copy(setup = ready.copy(gboardBubble = true, unrestricted = true), eleven = KeyField(saved = true))),
            "09-done" to (WizardStep.DONE to fresh.copy(setup = ready, eleven = KeyField(saved = true), skipped = setOf(WizardStep.COMPANION))),
        )
        val out = File("build/previews/wizard").apply { mkdirs() }
        for ((name, pair) in frames) {
            val (step, state) = pair
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent { VoicebarTheme { SetupWizard(step, state, none) } }
            repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            val root = controller.get().window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            controller.pause().stop().destroy()
        }
    }
}
