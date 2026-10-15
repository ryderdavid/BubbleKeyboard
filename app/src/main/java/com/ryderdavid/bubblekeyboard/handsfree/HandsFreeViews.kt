package com.ryderdavid.bubblekeyboard.handsfree

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.ryderdavid.bubblekeyboard.Palette
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The full-screen layer hands-free mode draws over everything, never touchable: the cursor (an
 * arrow that glides to what it's about to press and ripples when it does), number badges on what
 * you can tap, and a trail for swipes and scrolls. Coordinates are the screen's.
 */
class CursorLayer(context: Context) : View(context) {
    private val pal = Palette(context)
    private val d = resources.displayMetrics.density
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    var cursorX = -1f; private set
    var cursorY = -1f; private set
    private var shown = false

    private var rippleX = 0f
    private var rippleY = 0f
    private var ripple = -1f // 0..1 while rippling
    private var rippleLong = false

    private var trail: FloatArray? = null // x1,y1,x2,y2
    private var trailT = -1f

    /** A question beside the cursor ("Tap Send SMS?"), with a smaller second line. Null: none. */
    var callout: Pair<String, String>? = null
        set(v) { field = v; invalidate() }

    /** Number badges: the element's top-left corner and its number. */
    var badges: List<Triple<Int, Int, Int>> = emptyList()
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 12 * d; textAlign = Paint.Align.CENTER
    }

    private var glide: ValueAnimator? = null

    /** Moves the cursor to x,y, gliding from where it was; [then] runs when it arrives. */
    fun moveTo(x: Float, y: Float, then: (() -> Unit)? = null) {
        glide?.cancel()
        if (!shown || cursorX < 0) {
            cursorX = x; cursorY = y; shown = true; invalidate(); then?.invoke(); return
        }
        val sx = cursorX; val sy = cursorY
        val dist = hypot(x - sx, y - sy)
        if (dist < 2) { then?.invoke(); return }
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (180 + dist / d * 0.35f).toLong().coerceAtMost(420)
            interpolator = ease
            addUpdateListener { a -> val t = a.animatedValue as Float; cursorX = sx + (x - sx) * t; cursorY = sy + (y - sy) * t; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) then?.invoke() }
            })
            start()
        }
    }

    /** Puts the cursor in the middle of the screen if it isn't anywhere yet. */
    fun ensureCursor() { if (!shown) { cursorX = width / 2f; cursorY = height / 2f; shown = true; invalidate() } }

    fun ripple(x: Float, y: Float, long: Boolean = false) {
        rippleX = x; rippleY = y; rippleLong = long
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (long) 650 else 380
            addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { ripple = -1f; invalidate() }
            })
            start()
        }
    }

    fun trail(x1: Float, y1: Float, x2: Float, y2: Float) {
        trail = floatArrayOf(x1, y1, x2, y2)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener { trailT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { trailT = -1f; trail = null; invalidate() }
            })
            start()
        }
    }

    /** For previews: everything at a moment. */
    fun testShow(x: Float, y: Float, rippleAt: Float = -1f, trailAt: FloatArray? = null, trailProgress: Float = -1f) {
        cursorX = x; cursorY = y; shown = true
        rippleX = x; rippleY = y; ripple = rippleAt
        trail = trailAt; trailT = trailProgress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        drawBadges(canvas)
        drawTrail(canvas)
        drawRipple(canvas)
        if (shown) drawCursor(canvas, cursorX, cursorY)
        if (shown) callout?.let { drawCallout(canvas, it.first, it.second) }
        canvas.restore()
    }

    private val calloutMain = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val calloutSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * d }

    /** The question in a bubble beside the cursor: above it, or below near the top; kept on screen. */
    private fun drawCallout(canvas: Canvas, text: String, sub: String) {
        val pad = 14 * d
        val w = max(calloutMain.measureText(text), calloutSub.measureText(sub)) + pad * 2
        val h = 54 * d
        val sw = resources.displayMetrics.widthPixels.toFloat()
        var left = (cursorX - w + 24 * d).coerceIn(8 * d, sw - w - 8 * d)
        var top = cursorY - h - 26 * d
        if (top < 60 * d) top = cursorY + 34 * d
        val r = RectF(left, top, left + w, top + h)
        fill.color = pal.discard
        fill.setShadowLayer(8 * d, 0f, 2 * d, 0x55000000)
        canvas.drawRoundRect(r, 18 * d, 18 * d, fill)
        fill.clearShadowLayer()
        calloutMain.color = pal.onDiscard
        calloutSub.color = ColorUtils.setAlphaComponent(pal.onDiscard, 210)
        canvas.drawText(text, r.left + pad, r.top + 23 * d, calloutMain)
        canvas.drawText(sub, r.left + pad, r.top + 42 * d, calloutSub)
    }

    private fun drawBadges(canvas: Canvas) {
        if (badges.isEmpty()) return
        val h = 20 * d
        for ((x, y, n) in badges) {
            val label = n.toString()
            val w = max(h, badgeText.measureText(label) + 10 * d)
            val r = RectF(x.toFloat(), y.toFloat(), x + w, y + h)
            fill.color = pal.command
            fill.setShadowLayer(3 * d, 0f, 1 * d, 0x55000000)
            canvas.drawRoundRect(r, h / 2, h / 2, fill)
            fill.clearShadowLayer()
            badgeText.color = pal.onCommand
            canvas.drawText(label, r.centerX(), r.centerY() - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
        }
    }

    private fun drawTrail(canvas: Canvas) {
        val t = trail ?: return
        if (trailT < 0) return
        val p = ease.getInterpolation(min(1f, trailT * 1.4f))
        val ex = t[0] + (t[2] - t[0]) * p
        val ey = t[1] + (t[3] - t[1]) * p
        val fade = if (trailT > 0.6f) 1 - (trailT - 0.6f) / 0.4f else 1f
        stroke.color = ColorUtils.setAlphaComponent(pal.command, (200 * fade).toInt())
        stroke.strokeWidth = 6 * d
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(t[0], t[1], ex, ey, stroke)
        fill.color = ColorUtils.setAlphaComponent(pal.command, (230 * fade).toInt())
        canvas.drawCircle(ex, ey, 11 * d, fill)
    }

    private fun drawRipple(canvas: Canvas) {
        if (ripple < 0) return
        val r = (10 + 30 * ease.getInterpolation(ripple)) * d
        fill.color = ColorUtils.setAlphaComponent(pal.command, (110 * (1 - ripple)).toInt())
        canvas.drawCircle(rippleX, rippleY, r, fill)
        stroke.color = ColorUtils.setAlphaComponent(pal.command, (255 * (1 - ripple)).toInt())
        stroke.strokeWidth = 2.5f * d
        canvas.drawCircle(rippleX, rippleY, r, stroke)
        if (rippleLong) canvas.drawCircle(rippleX, rippleY, r * 0.6f, stroke)
    }

    /** A pointer arrow, tip at x,y: white with a dark edge and a soft colored halo, readable on anything. */
    private fun drawCursor(canvas: Canvas, x: Float, y: Float) {
        val s = d * 1.15f
        val arrow = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 21f * s); lineTo(5.2f * s, 16.2f * s); lineTo(8.8f * s, 24f * s)
            lineTo(12.2f * s, 22.5f * s); lineTo(8.7f * s, 14.9f * s); lineTo(15.5f * s, 14.9f * s); close()
            offset(x, y)
        }
        fill.color = ColorUtils.setAlphaComponent(pal.command, 70)
        canvas.drawCircle(x, y, 14 * d, fill)
        fill.color = 0xFFFFFFFF.toInt()
        fill.setShadowLayer(4 * d, 0f, 1.5f * d, 0x66000000)
        canvas.drawPath(arrow, fill)
        fill.clearShadowLayer()
        stroke.color = 0xFF1B1B1F.toInt()
        stroke.strokeWidth = 1.6f * d
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrow, stroke)
    }
}

/**
 * The hands-free pill at the top of the screen: a pulsing dot while it listens, what it heard,
 * what it's doing, and the question when it needs a yes. Tapping it ends hands-free mode.
 */
@SuppressLint("ViewConstructor")
class HandsFreePill(context: Context, private val onTap: () -> Unit) : View(context) {
    enum class Tone { LISTENING, HEARD, WORKING, ASK, PROBLEM }

    private val pal = Palette(context)
    private val d = resources.displayMetrics.density
    private var tone = Tone.LISTENING
    private var text = ""
    private var hint = ""
    private var level = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d }

    /** The pill sits in the status bar: this tall (set by the session to the bar's height). */
    var pillHeight = (30 * d).toInt()

    fun show(tone: Tone, text: String, hint: String = "") {
        this.tone = tone; this.text = text; this.hint = hint
        requestLayout(); invalidate()
    }

    fun level(v: Float) { level = level * 0.6f + v * 0.4f; if (tone == Tone.LISTENING || tone == Tone.HEARD) invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), pillHeight)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) onTap()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 2 * d
        val h = pillHeight - inset * 2
        val (bgc, fg) = when (tone) {
            Tone.ASK -> pal.discard to pal.onDiscard
            Tone.PROBLEM -> pal.discard to pal.onDiscard
            else -> pal.command to pal.onCommand
        }
        val pad = 12 * d
        val dotR = 4 * d
        main.textSize = min(14 * d, h * 0.5f)
        sub.textSize = main.textSize * 0.86f
        val maxText = width - pad * 2 - dotR * 2 - 8 * d
        val line = TextUtils.ellipsize(text, main, maxText, TextUtils.TruncateAt.START).toString()
        val room = maxText - main.measureText(line) - main.measureText(" · ")
        val subLine = if (hint.isEmpty() || room < 40 * d) "" else TextUtils.ellipsize(hint, sub, room, TextUtils.TruncateAt.END).toString()
        val tw = main.measureText(line) + if (subLine.isEmpty()) 0f else main.measureText(" · ") + sub.measureText(subLine)
        val w = min(width.toFloat(), pad * 2 + dotR * 2 + 8 * d + tw)
        val r = RectF(0f, inset, w, inset + h)
        bg.color = bgc
        bg.setShadowLayer(6 * d, 0f, 2 * d, 0x44000000)
        canvas.drawRoundRect(r, h / 2, h / 2, bg)
        bg.clearShadowLayer()
        val cx = pad + dotR
        val cy = r.centerY()
        val listening = tone == Tone.LISTENING || tone == Tone.HEARD
        if (listening) {
            dot.color = ColorUtils.setAlphaComponent(fg, 70)
            canvas.drawCircle(cx, cy, dotR + 7 * d * level, dot)
        }
        dot.color = fg
        if (tone == Tone.WORKING) {
            dot.style = Paint.Style.STROKE; dot.strokeWidth = 2 * d
            canvas.drawCircle(cx, cy, dotR, dot); dot.style = Paint.Style.FILL
        } else canvas.drawCircle(cx, cy, dotR, dot)
        main.color = fg
        main.typeface = Typeface.create("sans-serif-medium", if (tone == Tone.HEARD) Typeface.ITALIC else Typeface.NORMAL)
        val tx = cx + dotR + 8 * d
        val base = cy - (main.descent() + main.ascent()) / 2
        canvas.drawText(line, tx, base, main)
        if (subLine.isNotEmpty()) {
            sub.color = ColorUtils.setAlphaComponent(fg, 190)
            val sx = tx + main.measureText(line)
            canvas.drawText(" · ", sx, base, sub)
            canvas.drawText(subLine, sx + sub.measureText(" · "), base, sub)
        }
    }
}
