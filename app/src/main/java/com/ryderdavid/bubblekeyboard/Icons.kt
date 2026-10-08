package com.ryderdavid.bubblekeyboard

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * Glyphs for the side-function chips, from the real artwork:
 * gear and cancel from Material Symbols Rounded (Apache 2.0, 960-unit viewBox),
 * the keyboard switcher from AOSP's ic_ime_switcher (Apache 2.0, 24-unit viewBox).
 */
object Icons {
    enum class Glyph(val data: String, val viewBox: Float, val yOffset: Float) {
        SETTINGS("M433-80q-27 0-46.5-18T363-142l-9-66q-13-5-24.5-12T307-235l-62 26q-25 11-50 2t-39-32l-47-82q-14-23-8-49t27-43l53-40q-1-7-1-13.5v-27q0-6.5 1-13.5l-53-40q-21-17-27-43t8-49l47-82q14-23 39-32t50 2l62 26q11-8 23-15t24-12l9-66q4-26 23.5-44t46.5-18h94q27 0 46.5 18t23.5 44l9 66q13 5 24.5 12t22.5 15l62-26q25-11 50-2t39 32l47 82q14 23 8 49t-27 43l-53 40q1 7 1 13.5v27q0 6.5-2 13.5l53 40q21 17 27 43t-8 49l-48 82q-14 23-39 32t-50-2l-60-26q-11 8-23 15t-24 12l-9 66q-4 26-23.5 44T527-80h-94Zm7-80h79l14-106q31-8 57.5-23.5T639-327l99 41 39-68-86-65q5-14 7-29.5t2-31.5q0-16-2-31.5t-7-29.5l86-65-39-68-99 42q-22-23-48.5-38.5T533-694l-13-106h-79l-14 106q-31 8-57.5 23.5T321-633l-99-41-39 68 86 64q-5 15-7 30t-2 32q0 16 2 31t7 30l-86 65 39 68 99-42q22 23 48.5 38.5T427-266l13 106Zm42-180q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Zm-2-140Z", 960f, 960f),
        CLOSE("m480-424 116 116q11 11 28 11t28-11q11-11 11-28t-11-28L536-480l116-116q11-11 11-28t-11-28q-11-11-28-11t-28 11L480-536 364-652q-11-11-28-11t-28 11q-11 11-11 28t11 28l116 116-116 116q-11 11-11 28t11 28q11 11 28 11t28-11l116-116Zm0 344q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z", 960f, 960f),
        SWITCH_KEYBOARD("M19,7h2v2h-2V7zM15,7h2v2h-2V7zM3,7h2v2H3V7zM7,7h2v2H7V7zM11,7h2v2h-2V7zM19,11h2v2h-2V11zM15,11h2v2h-2V11zM3,11h2v2H3V11zM7,11h2v2H7V11zM11,11h2v2h-2V11zM7,15h10v2H7V15z", 24f, 0f),
    }

    private val paths = HashMap<Glyph, Path>()
    private val matrix = Matrix()
    private val scratch = Path()

    private fun path(g: Glyph) = paths.getOrPut(g) { PathParser.createPathFromPathData(g.data) }

    /** Draws [g] filled with [paint]'s color, [size] px square, centered on ([x], [y]). */
    fun draw(canvas: Canvas, g: Glyph, x: Float, y: Float, size: Float, paint: Paint) {
        val s = size / g.viewBox
        matrix.reset()
        matrix.postTranslate(0f, g.yOffset)
        matrix.postScale(s, s)
        matrix.postTranslate(x - size / 2, y - size / 2)
        scratch.reset()
        path(g).transform(matrix, scratch)
        val style = paint.style
        paint.style = Paint.Style.FILL
        canvas.drawPath(scratch, paint)
        paint.style = style
    }
}
