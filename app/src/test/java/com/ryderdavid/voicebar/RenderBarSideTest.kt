package com.ryderdavid.voicebar

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bar on an unfolded Fold 8 (about 900 dp wide) with its buttons kept to one side; previews into app/build/previews/barside/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderBarSideTest {
    @Config(qualifiers = "w900dp-h700dp-land-420dpi")
    @Test fun wide() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val out = File("build/previews/barside").apply { mkdirs() }
        val d = activity.resources.displayMetrics.density
        for ((side, talking) in listOf(Prefs.BAR_SIDE_FULL to false, Prefs.BAR_SIDE_LEFT to false, Prefs.BAR_SIDE_CENTER to false, Prefs.BAR_SIDE_RIGHT to false, Prefs.BAR_SIDE_RIGHT to true)) {
            Prefs.get(activity).edit { clear(); putString(Prefs.BAR_SIDE, side) }
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
                idle(300)
            }
            val voice = dock.testItem(DockView.Act.MIC)!!
            if (side == Prefs.BAR_SIDE_RIGHT && !talking) assertEquals(dock.width.toFloat() - 6 * d, voice.right + 5 * d + 40 * d + 0f, 2 * d)
            val bmp = Bitmap.createBitmap(dock.width, dock.height, Bitmap.Config.ARGB_8888)
            dock.draw(Canvas(bmp))
            File(out, "dock-$side${if (talking) "-talking" else ""}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Config(qualifiers = "w454dp-h1400dp-port-440dpi")
    @Test fun lookPage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear(); putBoolean(Prefs.SETUP_DONE, true); putString(Prefs.BAR_SIDE, Prefs.BAR_SIDE_RIGHT) }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.LOOK.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/previews/barside").apply { mkdirs() }, "look.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
