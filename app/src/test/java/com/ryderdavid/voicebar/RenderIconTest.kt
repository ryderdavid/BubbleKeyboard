package com.ryderdavid.voicebar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.Test

/** Not a check: the launcher icon's layers as the app ships them. Writes app/build/previews/icon/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class RenderIconTest {
    @Test fun icon() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = File("build/previews/icon").apply { mkdirs() }
        for ((name, id) in listOf("foreground" to R.drawable.ic_launcher_foreground, "monochrome" to R.drawable.ic_launcher_monochrome)) {
            val d = ctx.getDrawable(id)!!
            val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (name == "foreground") ctx.getColor(R.color.launcher_background) else 0xFFE3E1EC.toInt())
            d.setBounds(0, 0, 432, 432)
            if (name == "monochrome") d.setTint(0xFF3A3F52.toInt())
            d.draw(c)
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
