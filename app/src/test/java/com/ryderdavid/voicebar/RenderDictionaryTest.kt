package com.ryderdavid.voicebar

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ryderdavid.voicebar.voice.Dictionary
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: the Dictionary page with a few entries, and the "Learned" note on the bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderDictionaryTest {
    @Config(qualifiers = "w454dp-h1100dp-port-440dpi")
    @Test fun page() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = Dictionary.of(ctx)
        d.add("Siona", "Shauna"); d.add("Siona", "Shona")
        d.add("YODA", "you da", learned = false)
        d.add("Camelback", "camel back")
        d.add("Ezra")
        d.add("SRP", "S R P")
        val out = File("build/previews/dictionary").apply { mkdirs() }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.DICTIONARY.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "page.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun note() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dd = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * dd).toInt())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        dock.settle()
        dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
        dock.layout(0, 0, dock.width, dock.measuredHeight)
        dock.showNote(activity.getString(R.string.learned_note, "Siona")) {}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        dock.settle()
        val bmp = Bitmap.createBitmap(dock.width, dock.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFFF9F9FF.toInt()); dock.draw(c)
        val out = File("build/previews/dictionary").apply { mkdirs() }
        File(out, "note.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
