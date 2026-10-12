package com.ryderdavid.bubblekeyboard

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot

/**
 * With "Use Gboard for typing" on, ABC on the voice bar hands the field to Gboard. While Gboard is
 * up, this service floats a mic bubble just above it; a tap switches straight back to Bubble
 * Keyboard (Android 11+, via the accessibility keyboard controller). Drag the bubble to move it;
 * its spot is kept relative to the top of the keyboard.
 *
 * It reads nothing on screen: it only watches which windows exist, to find the keyboard's.
 */
class GboardBubbleService : AccessibilityService() {

    private var wm: WindowManager? = null
    private var bubble: MicBubble? = null
    private var lp: WindowManager.LayoutParams? = null
    private val imeBounds = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        update()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> update()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    private fun gboardIsCurrent(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("${Prefs.GBOARD_PACKAGE}/") == true

    /** Show the bubble when the setting is on and Gboard's window is on screen; otherwise hide it. */
    private fun update() {
        val on = Build.VERSION.SDK_INT >= 30 &&
            Prefs.get(this).getBoolean(Prefs.GBOARD_MODE, Prefs.DEFAULT_GBOARD_MODE) &&
            gboardIsCurrent()
        val ime = if (on) runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull() else null
        if (ime == null) { hide(); return }
        ime.getBoundsInScreen(imeBounds)
        if (imeBounds.height() <= 0) { hide(); return }
        show()
        place()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val size get() = dp(BUBBLE_DP).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (bubble != null) return
        val w = wm ?: return
        val v = MicBubble(this)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        v.setOnTouchListener(DragOrTap())
        runCatching { w.addView(v, p) }.onFailure { return }
        bubble = v
        lp = p
    }

    private fun hide() {
        val v = bubble ?: return
        runCatching { wm?.removeView(v) }
        bubble = null
        lp = null
    }

    /** Where the bubble sits: its saved x, and its saved gap above the keyboard's top edge. */
    private fun place() {
        val p = lp ?: return
        val v = bubble ?: return
        val prefs = Prefs.get(this)
        val screenW = resources.displayMetrics.widthPixels
        val x = prefs.getInt(Prefs.GBOARD_BUBBLE_X, -1).let { if (it < 0) screenW - size - dp(12f).toInt() else it }
        val gap = prefs.getInt(Prefs.GBOARD_BUBBLE_GAP, dp(12f).toInt())
        p.x = x.coerceIn(0, (screenW - size).coerceAtLeast(0))
        p.y = (imeBounds.top - gap - size).coerceAtLeast(0)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun switchBack() {
        if (Build.VERSION.SDK_INT < 30) return
        val id = ComponentName(this, BubbleKeyboardService::class.java).flattenToShortString()
        val ok = runCatching { softKeyboardController.switchToInputMethod(id) }.getOrDefault(false)
        if (ok) hide()
    }

    /** A tap switches back; a drag moves the bubble and remembers where it was left. */
    private inner class DragOrTap : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    (v as MicBubble).down = true; v.invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > ViewConfiguration.get(v.context).scaledTouchSlop) dragging = true
                    if (dragging) {
                        p.x = (startX + dx).toInt().coerceIn(0, resources.displayMetrics.widthPixels - size)
                        p.y = (startY + dy).toInt().coerceIn(0, (imeBounds.top - size).coerceAtLeast(0))
                        runCatching { wm?.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    (v as MicBubble).down = false; v.invalidate()
                    if (dragging) {
                        Prefs.get(this@GboardBubbleService).edit {
                            putInt(Prefs.GBOARD_BUBBLE_X, p.x)
                            putInt(Prefs.GBOARD_BUBBLE_GAP, (imeBounds.top - p.y - size).coerceAtLeast(0))
                        }
                    } else if (abs(e.rawX - downX) < dp(24f)) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        switchBack()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { (v as MicBubble).down = false; v.invalidate() }
            }
            return true
        }
    }

    /** The bubble: the voice pill's primary color, a mic, and a soft shadow. */
    private class MicBubble(context: Context) : View(context) {
        var down = false
        private val pal = Palette(context)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val rad = width / 2f - 3 * d
            for (i in 3 downTo 1) {
                fill.color = ColorUtils.setAlphaComponent(Color.BLACK, 14 + 6 * (3 - i))
                canvas.drawCircle(cx, cy + 1.5f * d, rad + i * 0.8f * d, fill)
            }
            fill.color = if (down) ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.15f) else pal.pressed
            canvas.drawCircle(cx, cy, rad, fill)
            // the mic, as on the voice pill
            val u = rad * 0.9f / 12f
            val c = pal.onPressed
            fill.color = c
            r.set(cx - 3.2f * u, cy - 9f * u, cx + 3.2f * u, cy + 3f * u)
            canvas.drawRoundRect(r, 3.2f * u, 3.2f * u, fill)
            stroke.color = c
            stroke.strokeWidth = 2f * u
            r.set(cx - 6.5f * u, cy - 5f * u, cx + 6.5f * u, cy + 6f * u)
            canvas.drawArc(r, 0f, 180f, false, stroke)
            canvas.drawLine(cx, cy + 6f * u, cx, cy + 9.5f * u, stroke)
        }
    }

    companion object {
        const val BUBBLE_DP = 52f

        /** Whether the user has turned this service on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, GboardBubbleService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
