package com.ryderdavid.bubblekeyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole keyboard: a small bubble that expands into a ring of keys around its center, and a
 * QWERTY "bloom" that fans out from the abc slot while it is held.
 *
 * The view fills the whole IME window but only the bubble / ring / bloom take touches; the rest
 * falls through to the app via [getTouchableRegion], which the service passes on in onComputeInsets.
 */
class BubbleView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Mode { BUBBLE, RING, BLOOM }

    enum class Key { UP, DOWN, LEFT, RIGHT, SPACE, DELETE, ENTER }

    interface Listener {
        fun onKey(key: Key, repeat: Boolean)
        fun onLetter(ch: Char)
        /** Long-press on the center: switch to another keyboard. */
        fun onLongPressCenter()
        /** Whether the next letter should be capitalized. */
        fun shouldCapitalize(): Boolean
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---------------------------------------------------------------- settings
    private var bubbleDiameterDp = Prefs.DEFAULT_BUBBLE_SIZE.toFloat()
    private var ringDiameterDp = Prefs.DEFAULT_RING_SIZE.toFloat()
    private var rotateBloom = Prefs.DEFAULT_ROTATE_BLOOM
    private val palette = Palette(context)

    fun reloadSettings() {
        val prefs = Prefs.get(context)
        bubbleDiameterDp = prefs.getInt(Prefs.BUBBLE_SIZE, Prefs.DEFAULT_BUBBLE_SIZE).toFloat()
        ringDiameterDp = prefs.getInt(Prefs.RING_SIZE, Prefs.DEFAULT_RING_SIZE).toFloat()
        rotateBloom = prefs.getBoolean(Prefs.ROTATE_BLOOM, Prefs.DEFAULT_ROTATE_BLOOM)
        clampCenter()
        invalidate()
        requestLayout()
    }

    // ring geometry, designed at a 150 dp ring and scaled from there
    private val ringScale get() = ringDiameterDp / 150f
    private val discRadius get() = dp(75f) * ringScale
    private val centerRadius get() = dp(18f) * ringScale
    private val bubbleRadius get() = dp(bubbleDiameterDp / 2f)

    private enum class SlotAction { UP, DOWN, LEFT, RIGHT, SPACE, DELETE, ENTER, LETTERS }
    private class Slot(val action: SlotAction, val dx: Float, val dy: Float, val radiusDp: Float, val cardinal: Boolean)

    // clockwise from the top, which is also the order the slots spring out in
    private val slots = listOf(
        Slot(SlotAction.UP, 0f, -49f, 14.5f, true),
        Slot(SlotAction.DELETE, 34.5f, -34.5f, 13f, false),
        Slot(SlotAction.RIGHT, 49f, 0f, 14.5f, true),
        Slot(SlotAction.ENTER, 34.5f, 34.5f, 13f, false),
        Slot(SlotAction.DOWN, 0f, 49f, 14.5f, true),
        Slot(SlotAction.LETTERS, -34.5f, 34.5f, 13f, false),
        Slot(SlotAction.LEFT, -49f, 0f, 14.5f, true),
        Slot(SlotAction.SPACE, -34.5f, -34.5f, 13f, false),
    )
    private val lettersSlot = slots.indexOfFirst { it.action == SlotAction.LETTERS }
    private val spaceSlot = slots.indexOfFirst { it.action == SlotAction.SPACE }
    private val enterSlot = slots.indexOfFirst { it.action == SlotAction.ENTER }
    private fun slotX(i: Int, progress: Float = 1f) = cx + dp(slots[i].dx) * ringScale * progress
    private fun slotY(i: Int, progress: Float = 1f) = cy + dp(slots[i].dy) * ringScale * progress
    private fun slotRadius(i: Int) = dp(slots[i].radiusDp) * ringScale

    // ---------------------------------------------------------------- state
    var mode = Mode.BUBBLE
        private set
    private var cx = Float.NaN
    private var cy = Float.NaN
    private val safeArea = Rect()

    // ring animation: elapsed ms on the current open / close run
    private var ringOpening = false
    private var ringElapsed = RING_CLOSE_MS
    private var ringAnimator: ValueAnimator? = null

    // bloom
    private class Letter(val ch: Char, val row: Int, val index: Int, val rowSize: Int, var x: Float = 0f, var y: Float = 0f)
    private fun bloomRows(vararg rows: String) = buildList {
        rows.forEachIndexed { row, s -> s.forEachIndexed { i, ch -> add(Letter(ch, row, i, s.length)) } }
    }
    // rows run outer to inner; the inner row sits closest to the finger, so it holds the commonest keys
    private val letters = bloomRows("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val punctuation = bloomRows("%*+=_~<>[]", ":;()/&@#$", ".,?!'\"-")
    private val numbers = bloomRows("1234567890", "+-*/=%.,:", "()$€£#°")

    /** The three blooms, each held from its own ring key: letters from abc, punctuation from space, numbers from enter. */
    enum class Bloom { LETTERS, PUNCTUATION, NUMBERS }
    var bloomKind = Bloom.LETTERS
        private set
    private val bloomOfLetters get() = bloomKind == Bloom.LETTERS
    private val bloomKeys get() = when (bloomKind) {
        Bloom.LETTERS -> letters
        Bloom.PUNCTUATION -> punctuation
        Bloom.NUMBERS -> numbers
    }
    private val bloomSlot get() = when (bloomKind) {
        Bloom.LETTERS -> lettersSlot
        Bloom.PUNCTUATION -> spaceSlot
        Bloom.NUMBERS -> enterSlot
    }

    /** Letters come out in capitals until caps lock is turned off again. */
    var capsLock = false
        private set
    private var bloomOpening = false
    private var bloomElapsed = BLOOM_CLOSE_MS
    private var bloomCaps = false
    private var bloomAnimator: ValueAnimator? = null
    private var bloomPivotX = 0f
    private var bloomPivotY = 0f
    private var bloomFromHold = false
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN
    private var hovered: Letter? = null

    // touch tracking
    private var downX = 0f
    private var downY = 0f
    private var downCx = 0f
    private var downCy = 0f
    private var dragging = false
    private var downTarget = -2 // -2 none, -1 center / bubble, >= 0 slot index
    private var pressedSlot = -1
    private var repeatCount = 0

    private val overshoot = OvershootInterpolator(1.0f)

    // ---------------------------------------------------------------- lifecycle

    init {
        reloadSettings()
        isHapticFeedbackEnabled = true
    }

    /** Back to a resting bubble, e.g. when the pad gets enabled or a new field is focused. */
    fun reset() {
        ringAnimator?.cancel()
        bloomAnimator?.cancel()
        removeCallbacks(idleRunnable)
        removeCallbacks(stickyBloomRunnable)
        removeCallbacks(centerLongPressRunnable)
        stopRepeat()
        removeCallbacks(holdRunnable)
        removeCallbacks(capsRunnable)
        capsLock = false
        mode = Mode.BUBBLE
        ringOpening = false
        ringElapsed = RING_CLOSE_MS
        bloomOpening = false
        bloomElapsed = BLOOM_CLOSE_MS
        hovered = null
        pressedSlot = -1
        invalidate()
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ringAnimator?.cancel()
        bloomAnimator?.cancel()
        removeCallbacks(idleRunnable)
        removeCallbacks(holdRunnable)
        removeCallbacks(stickyBloomRunnable)
        removeCallbacks(centerLongPressRunnable)
        stopRepeat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSafeArea()
        if (cx.isNaN() || oldw != w) readPosition()
        clampCenter()
    }

    private fun updateSafeArea() {
        val insets = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        safeArea.set(
            insets?.left ?: 0, insets?.top ?: 0,
            width - (insets?.right ?: 0), height - (insets?.bottom ?: 0)
        )
        if (safeArea.isEmpty) safeArea.set(0, 0, width, height)
    }

    // ---------------------------------------------------------------- position

    private fun positionKey(axis: String) = "${Prefs.POSITION_PREFIX}${axis}_${resources.displayMetrics.widthPixels}"

    private fun readPosition() {
        val prefs = Prefs.get(context)
        val x = prefs.getFloat(positionKey("x"), -1f)
        val y = prefs.getFloat(positionKey("y"), -1f)
        if (x < 0 || y < 0) {
            // default: lower right, where a FAB would sit
            cx = width - dp(16f) - max(bubbleRadius, discRadius)
            cy = height - dp(96f) - max(bubbleRadius, discRadius)
        } else {
            cx = x
            cy = y
        }
    }

    private fun savePosition() {
        Prefs.get(context).edit {
            putFloat(positionKey("x"), cx)
            putFloat(positionKey("y"), cy)
        }
    }

    /** Keep the whole ring on screen, so it can always expand where it sits. */
    private fun clampCenter() {
        if (width == 0 || height == 0) return
        updateSafeArea()
        if (cx.isNaN()) readPosition()
        val r = max(bubbleRadius, discRadius) + dp(4f)
        cx = cx.coerceIn(safeArea.left + r, max(safeArea.left + r, safeArea.right - r))
        cy = cy.coerceIn(safeArea.top + r, max(safeArea.top + r, safeArea.bottom - r))
    }

    // ---------------------------------------------------------------- touch region

    /** The area that takes touches, in this view's coordinates. Everything else reaches the app. */
    fun getTouchableRegion(out: Region) {
        out.setEmpty()
        val rects = touchRects()
        if (rects.isEmpty()) return
        // the bubble and ring are round: corners of their squares belong to the app
        val circle = rects[0]
        regionPath.reset()
        regionPath.addCircle(circle.exactCenterX(), circle.exactCenterY(), circle.width() / 2f, Path.Direction.CW)
        regionClip.set(circle)
        regionTmp.setPath(regionPath, regionClip)
        out.op(regionTmp, Region.Op.UNION)
        rects.drop(1).forEach { out.op(it, Region.Op.UNION) }
    }
    private val regionPath = Path()
    private val regionClip = Region()
    private val regionTmp = Region()

    private fun touchRects(): List<Rect> {
        if (cx.isNaN()) return emptyList()
        val r = when (mode) {
            Mode.BUBBLE -> bubbleRadius
            else -> discRadius
        } + dp(4f)
        val rects = mutableListOf(Rect((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()))
        if (mode == Mode.BLOOM) {
            val pad = dp(MAX_KEY_RADIUS_DP + PUSH_DP + 4f)
            var minX = bloomPivotX; var maxX = bloomPivotX; var minY = bloomPivotY; var maxY = bloomPivotY
            bloomKeys.forEach {
                minX = min(minX, it.x); maxX = max(maxX, it.x)
                minY = min(minY, it.y); maxY = max(maxY, it.y)
            }
            rects.add(Rect((minX - pad).toInt(), (minY - pad).toInt(), (maxX + pad).toInt(), (maxY + pad).toInt()))
        }
        return rects
    }

    // ---------------------------------------------------------------- ring open / close

    private fun expandRing() {
        if (mode != Mode.BUBBLE) return
        mode = Mode.RING
        runRing(opening = true)
        scheduleIdle()
        requestLayout()
    }

    private fun collapseToBubble() {
        if (mode == Mode.BUBBLE) return
        if (mode == Mode.BLOOM) closeBloom(animate = false)
        mode = Mode.BUBBLE
        runRing(opening = false)
        requestLayout()
    }

    private fun runRing(opening: Boolean) {
        ringAnimator?.cancel()
        ringOpening = opening
        val total = if (opening) RING_OPEN_MS + RING_STAGGER_MS * (slots.size - 1) else RING_CLOSE_MS
        ringElapsed = 0f
        ringAnimator = ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { ringElapsed = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** 0 = folded into the bubble, 1 = fully out (may overshoot slightly while opening). */
    private fun ringProgress(index: Int): Float {
        return if (ringOpening) {
            val t = ((ringElapsed - index * RING_STAGGER_MS) / RING_OPEN_MS).coerceIn(0f, 1f)
            overshoot.getInterpolation(t)
        } else {
            1f - (ringElapsed / RING_CLOSE_MS).coerceIn(0f, 1f)
        }
    }

    private val idleRunnable = Runnable { if (mode == Mode.RING && pressedSlot < 0) collapseToBubble() }
    private fun scheduleIdle() {
        removeCallbacks(idleRunnable)
        postDelayed(idleRunnable, IDLE_MS)
    }

    // ---------------------------------------------------------------- bloom open / close

    private var centerLongPressed = false
    private val centerLongPressRunnable = Runnable {
        if (downTarget == -1 && !dragging) {
            centerLongPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onLongPressCenter()
        }
    }

    private val stickyBloomRunnable = Runnable { if (mode == Mode.BLOOM && !bloomFromHold) closeBloom() }

    private val holdRunnable = Runnable {
        when (pressedSlot) {
            lettersSlot -> openBloom(fromHold = true, kind = Bloom.LETTERS)
            spaceSlot -> openBloom(fromHold = true, kind = Bloom.PUNCTUATION)
            enterSlot -> openBloom(fromHold = true, kind = Bloom.NUMBERS)
        }
    }

    /** A long, still hold on abc toggles caps lock: a weak then a strong buzz for on, one weak buzz for off. */
    private val capsRunnable = Runnable {
        val holdingAbc = downTarget == lettersSlot && (pressedSlot == lettersSlot || (mode == Mode.BLOOM && bloomOfLetters && bloomFromHold))
        if (!holdingAbc) return@Runnable
        capsLock = !capsLock
        bloomCaps = capsLock || listener?.shouldCapitalize() == true
        if (capsLock) buzzWeakThenStrong() else buzzWeak()
        invalidate()
    }

    private fun openBloom(fromHold: Boolean, kind: Bloom = Bloom.LETTERS) {
        removeCallbacks(idleRunnable)
        bloomFromHold = fromHold
        bloomKind = kind
        bloomPivotX = slotX(bloomSlot)
        bloomPivotY = slotY(bloomSlot)
        layoutBloom()
        bloomCaps = bloomOfLetters && (capsLock || listener?.shouldCapitalize() == true)
        mode = Mode.BLOOM
        hovered = null
        if (fromHold) {
            pointerX = bloomPivotX
            pointerY = bloomPivotY
        } else {
            pointerX = Float.NaN
            pointerY = Float.NaN
        }
        bloomAnimator?.cancel()
        bloomOpening = true
        val total = BLOOM_OPEN_MS + BLOOM_ROW_STAGGER_MS * 2 + BLOOM_KEY_STAGGER_MS * 9
        bloomElapsed = 0f
        bloomAnimator = ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { bloomElapsed = it.animatedValue as Float; invalidate() }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        removeCallbacks(stickyBloomRunnable)
        if (!fromHold) postDelayed(stickyBloomRunnable, STICKY_BLOOM_MS)
        requestLayout()
    }

    private fun closeBloom(animate: Boolean = true) {
        if (mode != Mode.BLOOM) return
        removeCallbacks(stickyBloomRunnable)
        mode = Mode.RING
        hovered = null
        pressedSlot = -1
        bloomAnimator?.cancel()
        bloomOpening = false
        if (animate) {
            bloomElapsed = 0f
            bloomAnimator = ValueAnimator.ofFloat(0f, BLOOM_CLOSE_MS).apply {
                duration = BLOOM_CLOSE_MS.toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { bloomElapsed = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            bloomElapsed = BLOOM_CLOSE_MS
        }
        scheduleIdle()
        requestLayout()
    }

    private fun bloomProgress(letter: Letter): Float {
        return if (bloomOpening) {
            // inner row first, so the fan grows outward from the finger
            val delay = (2 - letter.row) * BLOOM_ROW_STAGGER_MS + letter.index * BLOOM_KEY_STAGGER_MS
            overshoot.getInterpolation(((bloomElapsed - delay) / BLOOM_OPEN_MS).coerceIn(0f, 1f))
        } else {
            1f - (bloomElapsed / BLOOM_CLOSE_MS).coerceIn(0f, 1f)
        }
    }

    /**
     * Places the letters on three arcs around the abc slot. The fan turns toward the middle of
     * the screen (up to 60°), flips to open downward near the top, and is finally nudged inside
     * the safe area.
     */
    private fun layoutBloom() {
        updateSafeArea()
        val pivotX = bloomPivotX
        val pivotY = bloomPivotY
        var rotation = 0.0
        var flip = false
        if (rotateBloom) {
            val dir = Math.toDegrees(atan2(-(safeArea.exactCenterY() - pivotY).toDouble(), (safeArea.exactCenterX() - pivotX).toDouble()))
            var d = dir - 90.0
            while (d > 180) d -= 360.0
            while (d <= -180) d += 360.0
            if (abs(d) > 120) flip = true else rotation = d.coerceIn(-60.0, 60.0)
        }
        val keys = bloomKeys
        keys.forEach { l ->
            val row = ROWS[l.row]
            val n = l.rowSize
            val angle = Math.toRadians(row.startDeg + (row.endDeg - row.startDeg) * l.index / (n - 1.0) + rotation)
            val r = dp(row.radiusDp)
            l.x = pivotX + (r * cos(angle)).toFloat()
            l.y = if (flip) pivotY + (r * sin(angle)).toFloat() else pivotY - (r * sin(angle)).toFloat()
        }
        // nudge inside the safe area
        val m = dp(MAX_KEY_RADIUS_DP + 2f)
        val minX = keys.minOf { it.x } - m
        val maxX = keys.maxOf { it.x } + m
        val minY = keys.minOf { it.y } - m
        val maxY = keys.maxOf { it.y } + m
        val sx = max(0f, safeArea.left - minX) - max(0f, maxX - safeArea.right)
        val sy = max(0f, safeArea.top - minY) - max(0f, maxY - safeArea.bottom)
        keys.forEach { it.x += sx; it.y += sy }
    }

    private fun updateHover(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        val nearest = bloomKeys.minByOrNull { hypot(it.x - x, it.y - y) }
        val newHover = if (nearest != null && hypot(nearest.x - x, nearest.y - y) < dp(HOVER_RADIUS_DP)) nearest else null
        if (newHover != hovered && newHover != null)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hovered = newHover
        invalidate()
    }

    /** The finger is on the keys (not still fanning out), so they swell and part around it. */
    private val swelling get() = !pointerX.isNaN() && !(bloomOpening && bloomElapsed < BLOOM_OPEN_MS)

    /**
     * Where a key is drawn: pushed straight away from the finger, most for the nearest keys, so
     * they come out from under the fingertip. d + push(d) only grows with d (push <= 0.6 sigma
     * per sigma), so keys never cross each other.
     */
    private fun swollenPosition(l: Letter, radius: Float, out: PointF) {
        var x = l.x
        var y = l.y
        if (swelling) {
            val dx = l.x - pointerX
            val dy = l.y - pointerY
            val d = hypot(dx, dy)
            if (d > 0.5f) {
                val sigma = dp(PUSH_SIGMA_DP)
                val push = dp(PUSH_DP) * exp(-(d * d) / (2 * sigma * sigma))
                x += dx / d * push
                y += dy / d * push
            }
            x = x.coerceIn(safeArea.left + radius, max(safeArea.left + radius, safeArea.right - radius))
            y = y.coerceIn(safeArea.top + radius, max(safeArea.top + radius, safeArea.bottom - radius))
        }
        out.set(x, y)
    }
    private val swollen = PointF()

    private fun letterRadius(l: Letter): Float {
        if (!swelling) return dp(BASE_KEY_RADIUS_DP)
        val d = hypot(l.x - pointerX, l.y - pointerY)
        val sigma = dp(MAGNIFY_SIGMA_DP)
        val t = exp(-(d * d) / (2 * sigma * sigma))
        return dp(BASE_KEY_RADIUS_DP + (MAX_KEY_RADIUS_DP - BASE_KEY_RADIUS_DP) * t)
    }

    // ---------------------------------------------------------------- input

    private fun sendKey(action: SlotAction, repeat: Boolean = false) {
        val key = when (action) {
            SlotAction.UP -> Key.UP
            SlotAction.DOWN -> Key.DOWN
            SlotAction.LEFT -> Key.LEFT
            SlotAction.RIGHT -> Key.RIGHT
            SlotAction.SPACE -> Key.SPACE
            SlotAction.DELETE -> Key.DELETE
            SlotAction.ENTER -> Key.ENTER
            SlotAction.LETTERS -> return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onKey(key, repeat)
    }

    private fun commitLetter(l: Letter) {
        val caps = bloomOfLetters && (capsLock || listener?.shouldCapitalize() == true)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onLetter(if (caps) l.ch.uppercaseChar() else l.ch)
    }

    private fun label(l: Letter) = if (bloomOfLetters && bloomCaps) l.ch.uppercaseChar().toString() else l.ch.toString()

    private fun repeats(action: SlotAction) = when (action) {
        SlotAction.UP, SlotAction.DOWN, SlotAction.LEFT, SlotAction.RIGHT, SlotAction.DELETE -> true
        else -> false
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (pressedSlot < 0) return
            repeatCount++
            sendKey(slots[pressedSlot].action, repeat = true)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun stopRepeat() {
        removeCallbacks(repeatRunnable)
        repeatCount = 0
    }

    private fun hitSlot(x: Float, y: Float): Int {
        val slop = dp(6f)
        var best = -1
        var bestD = Float.MAX_VALUE
        slots.indices.forEach { i ->
            val d = hypot(slotX(i) - x, slotY(i) - y)
            if (d < slotRadius(i) + slop && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    /** Follows the first finger only; extra fingers are ignored, and lifting the first one ends the gesture. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val consumed = onDown(event.x, event.y)
                if (!consumed) activePointerId = MotionEvent.INVALID_POINTER_ID
                return consumed
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointerId)
                if (i >= 0) onMove(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                if (event.getPointerId(i) == activePointerId) {
                    onUp(event.getX(i), event.getY(i))
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) == activePointerId) onUp(event.x, event.y)
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                onCancel()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun onDown(x: Float, y: Float): Boolean {
        downX = x; downY = y; downCx = cx; downCy = cy
        dragging = false
        downTarget = -2
        removeCallbacks(idleRunnable)
        centerLongPressed = false
        when (mode) {
            Mode.BUBBLE -> {
                if (hypot(x - cx, y - cy) > bubbleRadius + dp(8f)) return false
                downTarget = -1
                postDelayed(centerLongPressRunnable, CENTER_LONG_PRESS_MS)
            }
            Mode.RING -> {
                if (hypot(x - cx, y - cy) <= centerRadius + dp(4f)) {
                    downTarget = -1
                    postDelayed(centerLongPressRunnable, CENTER_LONG_PRESS_MS)
                } else {
                    val slot = hitSlot(x, y)
                    if (slot < 0) {
                        if (hypot(x - cx, y - cy) > discRadius + dp(4f)) return false
                        return true // on the disc, but not on a key
                    }
                    downTarget = slot
                    pressedSlot = slot
                    val action = slots[slot].action
                    if (slot == lettersSlot || slot == spaceSlot || slot == enterSlot) {
                        postDelayed(holdRunnable, HOLD_MS)
                        if (slot == lettersSlot) postDelayed(capsRunnable, CAPS_HOLD_MS)
                    } else if (repeats(action)) {
                        sendKey(action)
                        postDelayed(repeatRunnable, REPEAT_START_MS)
                    }
                    invalidate()
                }
            }
            Mode.BLOOM -> {
                removeCallbacks(stickyBloomRunnable)
                // a bloom opened by a tap: the next touch picks a key, or touching its ring key again closes it
                if (!bloomFromHold && hitSlot(x, y) == bloomSlot) {
                    downTarget = bloomSlot
                    return true
                }
                updateHover(x, y)
            }
        }
        return true
    }

    private fun onMove(x: Float, y: Float) {
        if (hypot(x - downX, y - downY) > dp(CAPS_STILL_DP)) removeCallbacks(capsRunnable)
        when (mode) {
            Mode.BUBBLE, Mode.RING -> {
                if (downTarget != -1) {
                    // sliding off a key cancels it, like a normal key
                    if (pressedSlot >= 0 && hitSlot(x, y) != pressedSlot) {
                        stopRepeat()
                        removeCallbacks(holdRunnable)
                        removeCallbacks(capsRunnable)
                        pressedSlot = -1
                        invalidate()
                    }
                    return
                }
                if (!dragging && hypot(x - downX, y - downY) > touchSlop) {
                    dragging = true
                    removeCallbacks(centerLongPressRunnable)
                }
                if (dragging) {
                    cx = downCx + x - downX
                    cy = downCy + y - downY
                    clampCenter()
                    invalidate()
                    requestLayout()
                }
            }
            Mode.BLOOM -> if (downTarget != bloomSlot || bloomFromHold) updateHover(x, y)
        }
    }

    private fun onUp(x: Float, y: Float) {
        removeCallbacks(holdRunnable)
        removeCallbacks(capsRunnable)
        removeCallbacks(centerLongPressRunnable)
        stopRepeat()
        when (mode) {
            Mode.BUBBLE -> {
                if (dragging) savePosition()
                if (!centerLongPressed) expandRing()
            }
            Mode.RING -> {
                if (downTarget == -1) {
                    if (dragging) {
                        savePosition()
                    } else if (!centerLongPressed) {
                        dragging = false
                        collapseToBubble()
                        return
                    }
                } else if (pressedSlot >= 0 && hitSlot(x, y) == pressedSlot) {
                    val action = slots[pressedSlot].action
                    when {
                        // a tap on abc opens a bloom that waits for one tap; space and enter just type
                        action == SlotAction.LETTERS -> { pressedSlot = -1; openBloom(fromHold = false); return }
                        !repeats(action) -> sendKey(action)
                    }
                }
                pressedSlot = -1
                invalidate()
                scheduleIdle()
            }
            Mode.BLOOM -> {
                if (!bloomFromHold && downTarget == bloomSlot) {
                    closeBloom()
                    return
                }
                updateHover(x, y)
                hovered?.let { commitLetter(it) }
                closeBloom()
            }
        }
        dragging = false
    }

    private fun onCancel() {
        removeCallbacks(holdRunnable)
        removeCallbacks(capsRunnable)
        removeCallbacks(centerLongPressRunnable)
        stopRepeat()
        if (dragging) savePosition()
        dragging = false
        pressedSlot = -1
        if (mode == Mode.BLOOM) closeBloom()
        if (mode == Mode.RING) scheduleIdle()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val icon = Path()
    private val iconRect = RectF()


    private fun withAlpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (Color.alpha(c) * a.coerceIn(0f, 1f)).toInt())

    override fun onDraw(canvas: Canvas) {
        if (cx.isNaN()) return
        val pal = palette

        val discP = ringProgress(0)
        val bloomShown = mode == Mode.BLOOM || (!bloomOpening && bloomElapsed < BLOOM_CLOSE_MS)
        val bloomFade = if (bloomShown) {
            if (bloomOpening) (bloomElapsed / BLOOM_OPEN_MS).coerceIn(0f, 1f) else 1f - (bloomElapsed / BLOOM_CLOSE_MS).coerceIn(0f, 1f)
        } else 0f
        val dim = 1f - 0.7f * bloomFade

        // shadow + disc
        if (discP > 0.01f) {
            val r = bubbleRadius + (discRadius - bubbleRadius) * discP
            fill.color = withAlpha(Color.BLACK, 0.18f * discP.coerceAtMost(1f))
            canvas.drawCircle(cx, cy + dp(3f), r + dp(1f), fill)
            fill.color = withAlpha(pal.disc, discP.coerceAtMost(1f) * (0.35f + 0.65f * dim))
            canvas.drawCircle(cx, cy, r, fill)
        }

        // slots
        slots.indices.forEach { i ->
            val p = ringProgress(i)
            if (p <= 0.01f) return@forEach
            val slot = slots[i]
            val isLetters = i == lettersSlot
            val isPivot = mode == Mode.BLOOM && i == bloomSlot
            val alpha = p.coerceAtMost(1f) * (if (isPivot) 1f else dim)
            val x = slotX(i, p)
            val y = slotY(i, p)
            val r = slotRadius(i) * (0.3f + 0.7f * p) * (if (isPivot) 1.12f else 1f)
            val pressed = i == pressedSlot || (isLetters && capsLock)
            val bg = when {
                pressed -> pal.pressed
                isLetters -> pal.letters
                slot.cardinal -> pal.cardinal
                else -> pal.diagonal
            }
            val fg = when {
                pressed -> pal.onPressed
                isLetters -> pal.onLetters
                slot.cardinal -> pal.onCardinal
                else -> pal.onDiagonal
            }
            if (isPivot) {
                fill.color = withAlpha(if (isLetters && !pressed) pal.letters else pal.pressed, 0.4f)
                canvas.drawCircle(x, y, r + dp(6f), fill)
            }
            fill.color = withAlpha(bg, alpha)
            canvas.drawCircle(x, y, r, fill)
            drawSlotIcon(canvas, slot.action, x, y, r, withAlpha(fg, alpha))
        }

        // center / bubble
        val centerR = bubbleRadius + (centerRadius - bubbleRadius) * discP.coerceIn(0f, 1.2f)
        fill.color = withAlpha(Color.BLACK, 0.2f)
        canvas.drawCircle(cx, cy + dp(2f), centerR, fill)
        fill.color = withAlpha(pal.center, 0.45f + 0.55f * dim)
        canvas.drawCircle(cx, cy, centerR, fill)
        drawKeyboardGlyph(canvas, cx, cy, centerR * 0.42f, withAlpha(pal.onCenter, 0.45f + 0.55f * dim))

        // bloom
        if (bloomShown) drawBloom(canvas, pal.pressed, pal.onPressed, pal.bloomKey, pal.onBloomKey)
    }

    private fun drawBloom(canvas: Canvas, accent: Int, onAccent: Int, keyBg: Int, keyText: Int) {
        // draw the hovered key last so it sits on top
        val ordered = bloomKeys.sortedBy { if (it === hovered) 1 else 0 }
        ordered.forEach { l ->
            val p = bloomProgress(l)
            if (p <= 0.01f) return@forEach
            val on = l === hovered
            // the key under the finger can't be seen anyway; it shows in the preview above
            if (on && swelling) return@forEach
            val fullR = letterRadius(l)
            swollenPosition(l, fullR, swollen)
            val x = bloomPivotX + (swollen.x - bloomPivotX) * p
            val y = bloomPivotY + (swollen.y - bloomPivotY) * p
            val r = fullR * (0.2f + 0.8f * p.coerceAtMost(1f))
            fill.color = withAlpha(Color.BLACK, 0.16f * p.coerceAtMost(1f))
            canvas.drawCircle(x, y + dp(1.5f), r, fill)
            fill.color = withAlpha(if (on) accent else keyBg, p.coerceAtMost(1f))
            canvas.drawCircle(x, y, r, fill)
            text.color = withAlpha(if (on) onAccent else keyText, p.coerceAtMost(1f))
            text.textSize = r * 0.95f
            canvas.drawText(label(l), x, y - (text.ascent() + text.descent()) / 2, text)
        }
        // preview of the hovered letter above the finger, which covers the key itself
        val h = hovered ?: return
        if (bloomOpening && bloomElapsed < BLOOM_OPEN_MS) return
        val pr = dp(26f)
        val px = (if (pointerX.isNaN()) h.x else pointerX).coerceIn(safeArea.left + pr, safeArea.right - pr)
        var py = (if (pointerY.isNaN()) h.y else pointerY) - dp(72f)
        if (py - pr < safeArea.top) py = (if (pointerY.isNaN()) h.y else pointerY) + dp(72f)
        fill.color = withAlpha(Color.BLACK, 0.2f)
        canvas.drawCircle(px, py + dp(3f), pr, fill)
        fill.color = accent
        canvas.drawCircle(px, py, pr, fill)
        text.color = onAccent
        text.textSize = pr * 1.1f
        canvas.drawText(label(h), px, py - (text.ascent() + text.descent()) / 2, text)
    }

    /** Icons are drawn on a 24-unit grid, centered on the key, like the mockups. */
    private fun drawSlotIcon(canvas: Canvas, action: SlotAction, x: Float, y: Float, r: Float, c: Int) {
        if (action == SlotAction.LETTERS) {
            text.color = c
            text.textSize = r * 0.62f
            if (capsLock) {
                canvas.drawText("ABC", x, y - (text.ascent() + text.descent()) / 2 - r * 0.12f, text)
                stroke.color = c
                stroke.strokeWidth = r * 0.12f
                canvas.drawLine(x - r * 0.3f, y + r * 0.42f, x + r * 0.3f, y + r * 0.42f, stroke)
            } else {
                canvas.drawText("abc", x, y - (text.ascent() + text.descent()) / 2, text)
            }
            return
        }
        val u = r * 0.9f / 12f // 24 units span ~0.9 of the diameter
        fun X(v: Float) = x + (v - 12f) * u
        fun Y(v: Float) = y + (v - 12f) * u
        icon.reset()
        when (action) {
            SlotAction.UP -> { icon.moveTo(X(6f), Y(15f)); icon.lineTo(X(12f), Y(9f)); icon.lineTo(X(18f), Y(15f)) }
            SlotAction.DOWN -> { icon.moveTo(X(6f), Y(9f)); icon.lineTo(X(12f), Y(15f)); icon.lineTo(X(18f), Y(9f)) }
            SlotAction.LEFT -> { icon.moveTo(X(15f), Y(6f)); icon.lineTo(X(9f), Y(12f)); icon.lineTo(X(15f), Y(18f)) }
            SlotAction.RIGHT -> { icon.moveTo(X(9f), Y(6f)); icon.lineTo(X(15f), Y(12f)); icon.lineTo(X(9f), Y(18f)) }
            SlotAction.SPACE -> { icon.moveTo(X(4f), Y(10f)); icon.lineTo(X(4f), Y(14f)); icon.lineTo(X(20f), Y(14f)); icon.lineTo(X(20f), Y(10f)) }
            SlotAction.DELETE -> {
                icon.moveTo(X(21f), Y(5f)); icon.lineTo(X(9f), Y(5f)); icon.lineTo(X(3f), Y(12f))
                icon.lineTo(X(9f), Y(19f)); icon.lineTo(X(21f), Y(19f)); icon.close()
                icon.moveTo(X(17f), Y(9f)); icon.lineTo(X(12f), Y(15f))
                icon.moveTo(X(12f), Y(9f)); icon.lineTo(X(17f), Y(15f))
            }
            SlotAction.ENTER -> {
                icon.moveTo(X(20f), Y(5f)); icon.lineTo(X(20f), Y(12f))
                iconRect.set(X(14f), Y(9f), X(20f), Y(15f))
                icon.arcTo(iconRect, 0f, 90f)
                icon.lineTo(X(6f), Y(15f))
                icon.moveTo(X(9f), Y(11f)); icon.lineTo(X(5f), Y(15f)); icon.lineTo(X(9f), Y(19f))
            }
            SlotAction.LETTERS -> Unit
        }
        stroke.color = c
        stroke.strokeWidth = 2.1f * u
        canvas.drawPath(icon, stroke)
    }

    private fun drawKeyboardGlyph(canvas: Canvas, x: Float, y: Float, half: Float, c: Int) {
        val u = half / 9f // the glyph is 18 x 12 units
        stroke.color = c
        stroke.strokeWidth = 2f * u
        iconRect.set(x - 9 * u, y - 6 * u, x + 9 * u, y + 6 * u)
        canvas.drawRoundRect(iconRect, 2.5f * u, 2.5f * u, stroke)
        fill.color = c
        listOf(-5f, -1f, 3f, 5f).forEach { canvas.drawCircle(x + it * u, y - 2 * u, 1.1f * u, fill) }
        canvas.drawLine(x - 4 * u, y + 2 * u, x + 4 * u, y + 2 * u, stroke)
    }

    // ---------------------------------------------------------------- haptics

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)

    private fun vibrate(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else @Suppress("DEPRECATION") v.vibrate(effect)
    }

    /** Caps lock on: a weak buzz, then a strong one about 70 ms later. */
    private fun buzzWeakThenStrong() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            postDelayed({ performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }, 90)
            return
        }
        when {
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK
            ) -> vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 70)
                    .compose()
            )
            v.hasAmplitudeControl() -> vibrate(VibrationEffect.createWaveform(longArrayOf(0, 15, 70, 35), intArrayOf(0, 60, 0, 255), -1))
            else -> vibrate(VibrationEffect.createWaveform(longArrayOf(0, 8, 80, 40), -1))
        }
    }

    /** Caps lock off: one weak buzz. */
    private fun buzzWeak() {
        val v = vibrator
        when {
            v == null || !v.hasVibrator() -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) ->
                vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f).compose())
            v.hasAmplitudeControl() -> vibrate(VibrationEffect.createOneShot(15, 60))
            else -> vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---------------------------------------------------------------- test hooks

    @VisibleForTesting internal fun testCenter() = PointF(cx, cy)
    @VisibleForTesting internal fun testSlotCenter(action: String): PointF {
        val i = slots.indexOfFirst { it.action.name == action }
        return PointF(slotX(i), slotY(i))
    }
    /** Where a key of the open bloom sits at rest (the finger aims at this). */
    @VisibleForTesting internal fun testLetterCenter(ch: Char): PointF = bloomKeys.first { it.ch == ch }.let { PointF(it.x, it.y) }
    /** Where a key of the open bloom is drawn right now, after parting around the finger. */
    @VisibleForTesting internal fun testDrawnCenter(ch: Char): PointF = bloomKeys.first { it.ch == ch }.let {
        val out = PointF(); swollenPosition(it, letterRadius(it), out); out
    }
    @VisibleForTesting internal fun testLetterBounds() = RectF(
        bloomKeys.minOf { it.x }, bloomKeys.minOf { it.y }, bloomKeys.maxOf { it.x }, bloomKeys.maxOf { it.y }
    )
    @VisibleForTesting internal fun testSafeArea() = Rect(safeArea)
    @VisibleForTesting internal fun testTouchRects() = touchRects()

    private class Row(val radiusDp: Float, val startDeg: Double, val endDeg: Double)

    companion object {
        private val ROWS = listOf(Row(92f, 175.0, 5.0), Row(68f, 172.0, 8.0), Row(44f, 170.0, 10.0))

        private const val BASE_KEY_RADIUS_DP = 9f
        private const val MAX_KEY_RADIUS_DP = 19f
        private const val MAGNIFY_SIGMA_DP = 22f
        // keys part around the finger: pushed up to 28 dp, falling off over ~30 dp
        private const val PUSH_DP = 28f
        private const val PUSH_SIGMA_DP = 30f
        private const val HOVER_RADIUS_DP = 20f

        // timings, already at the "twice as fast" pace
        private const val RING_OPEN_MS = 210f
        private const val RING_STAGGER_MS = 14f
        private const val RING_CLOSE_MS = 100f
        private const val BLOOM_OPEN_MS = 230f
        private const val BLOOM_ROW_STAGGER_MS = 35f
        private const val BLOOM_KEY_STAGGER_MS = 6f
        private const val BLOOM_CLOSE_MS = 100f
        private const val HOLD_MS = 320L
        private const val CAPS_HOLD_MS = 1100L
        private const val CAPS_STILL_DP = 8f
        private const val STICKY_BLOOM_MS = 6000L
        private const val CENTER_LONG_PRESS_MS = 500L
        private const val IDLE_MS = 3000L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

    }
}
