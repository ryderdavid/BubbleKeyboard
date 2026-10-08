package com.ryderdavid.bubblekeyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
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
import kotlin.math.PI
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
        /** Long-press on the up arrow: switch to another keyboard. */
        fun onSwitchKeyboard()
        /** Long-press on the down arrow: open Bubble Keyboard's settings. */
        fun onOpenSettings() {}
        /** Whether the next letter should be capitalized. */
        fun shouldCapitalize(): Boolean
        /**
         * Start dictation: the bubble or ring center was held (talk while held), or the ring
         * center was tapped (talk until tapped again). False if it couldn't start.
         */
        fun onTalkStart(): Boolean = false
        /** Dictation should end: type it, or throw it out when [cancel]. */
        fun onTalkEnd(cancel: Boolean) {}
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

    /**
     * How strongly keys swell and part around the finger, 0..1 (the "Finger swell" setting).
     * At 0 they barely grow and don't move; at 1 the nearest keys are pushed 60 dp out from under
     * the fingertip and grow to 52 dp. The push spread always stays wide enough (push <= 0.9 sigma)
     * that keys never cross each other.
     */
    private var swell = Prefs.DEFAULT_SWELL / 100f

    /** How long the open ring waits untouched before folding up (the "Ring stays open" setting). */
    private var ringTimeoutMs = Prefs.DEFAULT_RING_TIMEOUT * 1000L
    /** Opacity of the resting bubble (the "Bubble opacity" setting); it comes up to full as the ring opens. */
    private var restingOpacity = Prefs.DEFAULT_BUBBLE_OPACITY / 100f
    private val pushDp get() = 60f * swell
    private val pushSigmaDp get() = max(24f, pushDp / 0.9f)
    private val maxKeyRadiusDp get() = 12f + 14f * swell
    private val magnifySigmaDp get() = 16f + 16f * swell

    fun reloadSettings() {
        val prefs = Prefs.get(context)
        bubbleDiameterDp = prefs.getInt(Prefs.BUBBLE_SIZE, Prefs.DEFAULT_BUBBLE_SIZE).toFloat()
        ringDiameterDp = prefs.getInt(Prefs.RING_SIZE, Prefs.DEFAULT_RING_SIZE).toFloat()
        rotateBloom = prefs.getBoolean(Prefs.ROTATE_BLOOM, Prefs.DEFAULT_ROTATE_BLOOM)
        swell = prefs.getInt(Prefs.SWELL, Prefs.DEFAULT_SWELL).coerceIn(Prefs.SWELL_RANGE) / 100f
        restingOpacity = prefs.getInt(Prefs.BUBBLE_OPACITY, Prefs.DEFAULT_BUBBLE_OPACITY).coerceIn(Prefs.BUBBLE_OPACITY_RANGE) / 100f
        ringTimeoutMs = prefs.getInt(Prefs.RING_TIMEOUT, Prefs.DEFAULT_RING_TIMEOUT).coerceIn(Prefs.RING_TIMEOUT_RANGE) * 1000L
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
    // preview pop animation
    private var previewStart = 0L
    private var lastPreviewX = 0f
    private var lastPreviewY = 0f
    private var outgoingKey: Letter? = null
    private var outgoingStart = 0L
    private var outgoingX = 0f
    private var outgoingY = 0f
    private val popOvershoot = OvershootInterpolator(2.0f)
    private val decelerate = android.view.animation.DecelerateInterpolator(1.6f)

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
        removeCallbacks(talkRunnable)
        removeCallbacks(slotLongPressRunnable)
        if (talking) endTalk(cancel = true)
        idleDeadline = 0L
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
        outgoingKey = null
        keyPops.clear()
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
        removeCallbacks(slotLongPressRunnable)
        removeCallbacks(talkRunnable)
        stopRepeat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSafeArea()
        if (cx.isNaN() || oldw != w) readPosition()
        clampCenter()
    }

    /** Height of the system navigation buttons at the bottom of the window, drawn over our strip. */
    private var navBarInset = 0

    @VisibleForTesting fun testSetNavBarInset(px: Int) { navBarInset = px; invalidate() }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        updateSafeArea()
        clampCenter()
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    private fun updateSafeArea() {
        navBarInset = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
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
            val pad = dp(maxKeyRadiusDp + pushDp + 4f)
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

    private val idleRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.RING || pressedSlot >= 0 || talking) return
            // let the polished text land before folding up
            if (voice == Voice.CLEANING) postDelayed(this, 500) else collapseToBubble()
        }
    }
    /** When the open ring folds up by itself; 0 while the countdown is paused (a finger is down, dictating). */
    private var idleDeadline = 0L

    private fun scheduleIdle() {
        removeCallbacks(idleRunnable)
        if (mode != Mode.RING || talking) { idleDeadline = 0L; invalidate(); return }
        idleDeadline = SystemClock.uptimeMillis() + ringTimeoutMs
        postDelayed(idleRunnable, ringTimeoutMs)
        invalidate()
    }

    private fun pauseIdle() {
        removeCallbacks(idleRunnable)
        idleDeadline = 0L
    }

    // ---------------------------------------------------------------- bloom open / close

    // ---------------------------------------------------------------- hold to talk

    private var talking = false
    /** Started by a tap on the ring center: keeps going until the center is tapped again. */
    private var talkLatched = false
    private var talkCancelArmed = false
    /** The finger now down will stop latched dictation when it lifts. */
    private var stopLatchOnUp = false
    private val talkRunnable = Runnable {
        if ((mode == Mode.BUBBLE || mode == Mode.RING) && downTarget == -1 && !dragging && !talking) {
            // either way this hold neither opens nor closes the ring when it ends
            centerLongPressed = true
            talkCancelArmed = false
            talkLatched = false
            talking = listener?.onTalkStart() == true
            if (talking) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    /** A tap on the ring center: start talking until the next tap. */
    private fun startLatchedTalk() {
        talkCancelArmed = false
        if (listener?.onTalkStart() == true) {
            talking = true
            talkLatched = true
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            pauseIdle()
        }
        invalidate()
    }

    private fun endTalk(cancel: Boolean) {
        talking = false
        talkLatched = false
        talkCancelArmed = false
        stopLatchOnUp = false
        listener?.onTalkEnd(cancel)
        scheduleIdle()
        invalidate()
    }

    /** The dictation ended on its own (finished, or failed): forget that we're talking. */
    fun talkEnded() {
        if (!talking && !talkLatched) return
        talking = false
        talkLatched = false
        talkCancelArmed = false
        stopLatchOnUp = false
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) scheduleIdle()
        invalidate()
    }

    /** Whether dictation started by a ring-center tap is running (for tests). */
    @VisibleForTesting val talkingLatched get() = talking && talkLatched

    /** What the dictation pill above the bubble shows. */
    enum class Voice { OFF, LISTENING, CLEANING, MESSAGE }
    var voice = Voice.OFF
        private set
    private var voiceText = ""
    private var voiceShownAt = 0L
    private var voiceHiddenAt = 0L
    private var voiceLevelTarget = 0f
    private var voiceLevel = 0f
    private val hideVoiceRunnable = Runnable { hideVoice() }

    fun showListening() {
        removeCallbacks(hideVoiceRunnable)
        voice = Voice.LISTENING
        voiceText = ""
        voiceLevel = 0f
        voiceLevelTarget = 0f
        voiceShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun showLevel(level: Float) {
        voiceLevelTarget = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun showLiveText(text: String) {
        voiceText = text
        invalidate()
    }

    fun showCleaning(text: String) {
        voice = Voice.CLEANING
        voiceText = text
        invalidate()
    }

    fun showMessage(message: String) {
        removeCallbacks(hideVoiceRunnable)
        if (voice == Voice.OFF) voiceShownAt = SystemClock.uptimeMillis()
        voice = Voice.MESSAGE
        voiceText = message
        postDelayed(hideVoiceRunnable, MESSAGE_MS)
        invalidate()
    }

    fun hideVoice() {
        removeCallbacks(hideVoiceRunnable)
        if (voice == Voice.OFF) return
        hiddenVoice = voice
        voice = Voice.OFF
        voiceHiddenAt = SystemClock.uptimeMillis()
        invalidate()
    }
    private var hiddenVoice = Voice.OFF

    /** The finger now down already did its hold action (talking), so its release does nothing else. */
    private var centerLongPressed = false

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
        releaseKeyPops()
        outgoingKey = null
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
        val m = dp(maxKeyRadiusDp + 2f)
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
        if (newHover != hovered) {
            val now = SystemClock.uptimeMillis()
            if (hovered != null) {
                outgoingKey = hovered
                outgoingStart = now
                outgoingX = lastPreviewX
                outgoingY = lastPreviewY
            }
            previewStart = now
        }
        hovered = newHover
        invalidate()
    }

    /** The finger is on the keys (not still fanning out), so they swell and part around it. */
    private val swelling get() = !pointerX.isNaN() && !(bloomOpening && bloomElapsed < BLOOM_OPEN_MS)

    /**
     * Where a key is drawn: pushed straight away from the finger, most for the nearest keys, so
     * they come out from under the fingertip. d + push(d) only grows with d (the push spread is kept wide), so keys never cross each other.
     */
    private fun swollenPosition(l: Letter, radius: Float, out: PointF) {
        var x = l.x
        var y = l.y
        if (swelling) {
            val dx = l.x - pointerX
            val dy = l.y - pointerY
            val d = hypot(dx, dy)
            if (d > 0.5f) {
                val sigma = dp(pushSigmaDp)
                val push = dp(pushDp) * exp(-(d * d) / (2 * sigma * sigma))
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
        val sigma = dp(magnifySigmaDp)
        val t = exp(-(d * d) / (2 * sigma * sigma))
        return dp(BASE_KEY_RADIUS_DP + (maxKeyRadiusDp - BASE_KEY_RADIUS_DP) * t)
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

    /** Keys that repeat while held. Up, down and left have long-press actions instead. */
    private fun repeats(action: SlotAction) = when (action) {
        SlotAction.RIGHT, SlotAction.DELETE -> true
        else -> false
    }

    /** Keys whose long-press does something else: they type on release instead of on touch. */
    private fun hasLongPress(action: SlotAction) = when (action) {
        SlotAction.UP, SlotAction.DOWN, SlotAction.LEFT -> true
        else -> false
    }

    private var slotLongPressed = false
    private val slotLongPressRunnable = Runnable {
        if (pressedSlot < 0) return@Runnable
        val action = slots[pressedSlot].action
        slotLongPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        bumpKeyPop(pressedSlot)
        when (action) {
            SlotAction.UP -> listener?.onSwitchKeyboard()
            SlotAction.DOWN -> { listener?.onOpenSettings(); closeRingNow() }
            SlotAction.LEFT -> closeRingNow()
            else -> Unit
        }
    }

    /** Long-press on the left arrow: fold up now, throwing out any dictation in progress. */
    private fun closeRingNow() {
        releaseKeyPops()
        pressedSlot = -1
        if (talking) endTalk(cancel = true)
        collapseToBubble()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (pressedSlot < 0) return
            repeatCount++
            bumpKeyPop(pressedSlot)
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
        pauseIdle()
        centerLongPressed = false
        when (mode) {
            Mode.BUBBLE -> {
                if (hypot(x - cx, y - cy) > bubbleRadius + dp(8f)) return false
                downTarget = -1
                postDelayed(talkRunnable, TALK_HOLD_MS)
            }
            Mode.RING -> {
                if (hypot(x - cx, y - cy) <= centerRadius + dp(4f)) {
                    downTarget = -1
                    // tap: toggle dictation; hold: talk while held
                    if (talking && talkLatched) stopLatchOnUp = true
                    else if (!talking) postDelayed(talkRunnable, TALK_HOLD_MS)
                } else {
                    val slot = hitSlot(x, y)
                    if (slot < 0) {
                        if (hypot(x - cx, y - cy) > discRadius + dp(4f)) return false
                        return true // on the disc, but not on a key
                    }
                    downTarget = slot
                    pressedSlot = slot
                    popKey(slot)
                    val action = slots[slot].action
                    slotLongPressed = false
                    if (slot == lettersSlot || slot == spaceSlot || slot == enterSlot) {
                        postDelayed(holdRunnable, HOLD_MS)
                        if (slot == lettersSlot) postDelayed(capsRunnable, CAPS_HOLD_MS)
                    } else if (hasLongPress(action)) {
                        postDelayed(slotLongPressRunnable, SLOT_LONG_PRESS_MS)
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
                        removeCallbacks(slotLongPressRunnable)
                        removeCallbacks(holdRunnable)
                        removeCallbacks(capsRunnable)
                        releaseKeyPops()
                        pressedSlot = -1
                        invalidate()
                    }
                    return
                }
                if (talking && !talkLatched) {
                    // slide well away from the bubble to throw the dictation out
                    val armed = hypot(x - cx, y - cy) > dp(TALK_CANCEL_DP)
                    if (armed != talkCancelArmed) {
                        talkCancelArmed = armed
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                    }
                    return
                }
                if (!dragging && hypot(x - downX, y - downY) > touchSlop) {
                    dragging = true
                    removeCallbacks(talkRunnable)
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
        releaseKeyPops()
        removeCallbacks(holdRunnable)
        removeCallbacks(capsRunnable)
        removeCallbacks(talkRunnable)
        removeCallbacks(slotLongPressRunnable)
        stopRepeat()
        if (slotLongPressed && mode == Mode.BUBBLE) {
            // the long-press already folded the ring up; this release is spent
            slotLongPressed = false
            dragging = false
            return
        }
        if (downTarget == -1 && stopLatchOnUp) {
            if (dragging) savePosition()
            endTalk(cancel = false)
            dragging = false
            return
        }
        if (talking && !talkLatched && downTarget == -1) {
            endTalk(cancel = talkCancelArmed)
            dragging = false
            return
        }
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
                        startLatchedTalk()
                    }
                } else if (pressedSlot >= 0 && hitSlot(x, y) == pressedSlot) {
                    val action = slots[pressedSlot].action
                    when {
                        // a tap on abc opens a bloom that waits for one tap; space and enter just type
                        action == SlotAction.LETTERS -> { pressedSlot = -1; openBloom(fromHold = false); return }
                        hasLongPress(action) -> if (!slotLongPressed) sendKey(action)
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
        removeCallbacks(talkRunnable)
        removeCallbacks(slotLongPressRunnable)
        if (talking && !talkLatched) endTalk(cancel = true)
        stopLatchOnUp = false
        releaseKeyPops()
        removeCallbacks(holdRunnable)
        removeCallbacks(capsRunnable)
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


    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowClip = Path()
    private val shadowColors = IntArray(SHADOW_STOPS + 2)
    private val shadowPositions = FloatArray(SHADOW_STOPS + 2)

    /**
     * The drop shadow of a circle of radius [r] at ([x], [y]): the circle in black at [alpha],
     * moved down by [dy] and gaussian-blurred with standard deviation [sigma]. The defaults match
     * the Wispr Flow bubble, measured from a screenshot: 16 % black, 0.9 dp down, sigma 1.5 dp, so
     * the shadow is gone about 4 dp past the edge. Drawn as a radial gradient through the blurred
     * edge profile, which costs one draw and needs no blur support from the device.
     */
    private fun drawSoftShadow(
        canvas: Canvas, x: Float, y: Float, r: Float,
        alpha: Float = 0.16f, dy: Float = dp(0.9f), sigma: Float = dp(1.5f), clipOutShape: Boolean = false,
    ) {
        if (alpha <= 0.003f || r <= 0f || sigma <= 0f) return
        val outer = r + 3f * sigma
        val inner = max(0f, r - 3f * sigma)
        shadowPositions[0] = 0f
        shadowColors[0] = shadowBlack(alpha * phi(r / sigma))
        for (i in 0..SHADOW_STOPS) {
            val d = inner + (outer - inner) * i / SHADOW_STOPS
            shadowPositions[i + 1] = d / outer
            shadowColors[i + 1] = shadowBlack(alpha * phi((r - d) / sigma))
        }
        shadowPaint.shader = RadialGradient(x, y + dy, outer, shadowColors, shadowPositions, Shader.TileMode.CLAMP)
        if (clipOutShape) {
            canvas.save()
            shadowClip.reset()
            shadowClip.addCircle(x, y, r, Path.Direction.CW)
            canvas.clipOutPath(shadowClip)
        }
        canvas.drawCircle(x, y + dy, outer, shadowPaint)
        if (clipOutShape) canvas.restore()
    }

    private fun shadowBlack(a: Float) = Color.argb((255 * a).toInt().coerceIn(0, 255), 0, 0, 0)

    /** Standard normal cumulative distribution (Abramowitz-Stegun erf, good to ~1e-7). */
    private fun phi(z: Float): Float {
        val x = abs(z) / 1.41421356f
        val t = 1f / (1f + 0.3275911f * x)
        val erf = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * exp(-x * x)
        return if (z >= 0) 0.5f * (1f + erf) else 0.5f * (1f - erf)
    }

    private fun withAlpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (Color.alpha(c) * a.coerceIn(0f, 1f)).toInt())

    override fun onDraw(canvas: Canvas) {
        // a solid, theme-tinted base under the system's navigation buttons, like a Material 3 app's
        // navigation bar, instead of the system's gray contrast scrim
        if (navBarInset > 0) {
            fill.color = palette.navStrip
            canvas.drawRect(0f, (height - navBarInset).toFloat(), width.toFloat(), height.toFloat(), fill)
        }
        if (cx.isNaN()) return
        // fade the resting bubble (and its shadow) as one layer, only around the bubble
        val opacity = if (talking || voice != Voice.OFF) 1f
            else restingOpacity + (1f - restingOpacity) * ringProgress(0).coerceIn(0f, 1f)
        if (opacity < 0.995f) {
            val r = max(bubbleRadius, discRadius) + dp(28f)
            val saved = canvas.saveLayerAlpha(cx - r, cy - r, cx + r, cy + r, (255 * opacity).toInt())
            drawPad(canvas)
            canvas.restoreToCount(saved)
        } else {
            drawPad(canvas)
        }
        drawVoicePill(canvas)
    }

    private fun drawPad(canvas: Canvas) {
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
            val shown = discP.coerceIn(0f, 1f)
            // the disc is translucent, so its shadow stays outside it instead of muddying what shows through
            drawSoftShadow(canvas, cx, cy, r, alpha = 0.14f * shown, dy = dp(1f), sigma = dp(2f), clipOutShape = true)
            fill.color = withAlpha(pal.disc, shown * DISC_OPACITY * (0.45f + 0.55f * dim))
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
            drawSoftShadow(canvas, x, y, r, alpha = 0.12f * alpha, dy = dp(0.5f), sigma = dp(0.9f))
            fill.color = withAlpha(bg, alpha)
            canvas.drawCircle(x, y, r, fill)
            drawSlotIcon(canvas, slot.action, x, y, r, withAlpha(fg, alpha))
        }

        // center / bubble
        val centerR = bubbleRadius + (centerRadius - bubbleRadius) * discP.coerceIn(0f, 1.2f)
        // the same tight shadow as the Wispr bubble
        drawSoftShadow(canvas, cx, cy, centerR, alpha = 0.16f * dim)
        val listening = voice == Voice.LISTENING || (talking && voice == Voice.OFF)
        if (listening && mode != Mode.BLOOM) {
            voiceLevel += (voiceLevelTarget - voiceLevel) * 0.35f
            // a halo that breathes with the voice
            fill.color = withAlpha(pal.pressed, 0.22f)
            canvas.drawCircle(cx, cy, centerR + dp(4f + 14f * voiceLevel), fill)
            fill.color = pal.pressed
            canvas.drawCircle(cx, cy, centerR, fill)
            drawMicGlyph(canvas, cx, cy, centerR * 0.42f, if (talkCancelArmed) withAlpha(pal.onPressed, 0.5f) else pal.onPressed)
        } else {
            fill.color = withAlpha(pal.center, 0.45f + 0.55f * dim)
            canvas.drawCircle(cx, cy, centerR, fill)
            drawKeyboardGlyph(canvas, cx, cy, centerR * 0.42f, withAlpha(pal.onCenter, 0.45f + 0.55f * dim))
        }
        if (voice == Voice.CLEANING && mode != Mode.BLOOM) {
            // a spinning arc while the cleanup model works
            val sweep = (SystemClock.uptimeMillis() % 900L) / 900f * 360f
            stroke.color = pal.pressed
            stroke.strokeWidth = dp(3f)
            iconRect.set(cx - centerR - dp(5f), cy - centerR - dp(5f), cx + centerR + dp(5f), cy + centerR + dp(5f))
            canvas.drawArc(iconRect, sweep, 100f, false, stroke)
        }

        drawCountdown(canvas, centerR)
        drawKeyPops(canvas, SystemClock.uptimeMillis())

        // bloom
        if (bloomShown) drawBloom(canvas, pal.pressed, pal.onPressed, pal.bloomKey, pal.onBloomKey)
        if (popsAnimating(SystemClock.uptimeMillis())) postInvalidateOnAnimation()
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
            drawSoftShadow(canvas, x, y, r, alpha = 0.14f * p.coerceIn(0f, 1f), dy = dp(0.6f), sigma = dp(1f))
            fill.color = withAlpha(if (on) accent else keyBg, p.coerceAtMost(1f))
            canvas.drawCircle(x, y, r, fill)
            text.color = withAlpha(if (on) onAccent else keyText, p.coerceAtMost(1f))
            text.textSize = r * 0.95f
            canvas.drawText(label(l), x, y - (text.ascent() + text.descent()) / 2, text)
        }
        // preview of the hovered letter above the finger, which covers the key itself
        val now = SystemClock.uptimeMillis()
        val pr = dp(26f)
        // the previous letter shrinks and fades as the next one pops up
        outgoingKey?.let { o ->
            val t = (now - outgoingStart) / PREVIEW_OUT_MS
            if (t >= 1f) {
                outgoingKey = null
            } else {
                drawPreviewBubble(canvas, o, outgoingX, outgoingY + dp(6f) * t, pr * (1f - 0.35f * t), 1f - t, accent, onAccent)
            }
        }
        val h = hovered ?: return
        if (bloomOpening && bloomElapsed < BLOOM_OPEN_MS) return
        val fingerX = if (pointerX.isNaN()) h.x else pointerX
        val fingerY = if (pointerY.isNaN()) h.y else pointerY
        val targetX = fingerX.coerceIn(safeArea.left + pr, safeArea.right - pr)
        // sit clear of the keys pushed up past the fingertip, which travel further as the swell grows
        val lift = dp(max(72f, 36f + pushDp + maxKeyRadiusDp))
        var targetY = fingerY - lift
        if (targetY - pr < safeArea.top) targetY = fingerY + lift
        // pop: rises out of the key under the finger and overshoots slightly into place; it is
        // visible from the first frame, so it never arrives later than the old instant preview
        val t = ((now - previewStart) / PREVIEW_POP_MS).coerceIn(0f, 1f)
        val travel = decelerate.getInterpolation(t)
        val grow = popOvershoot.getInterpolation(t)
        val px = h.x + (targetX - h.x) * travel
        val py = h.y + (targetY - h.y) * travel
        lastPreviewX = px
        lastPreviewY = py
        drawPreviewBubble(canvas, h, px, py, pr * (0.45f + 0.55f * grow), min(1f, 0.55f + 1.5f * t), accent, onAccent)
    }

    private fun drawPreviewBubble(canvas: Canvas, l: Letter, x: Float, y: Float, r: Float, alpha: Float, accent: Int, onAccent: Int) {
        if (alpha <= 0.01f || r <= 0f) return
        drawSoftShadow(canvas, x, y, r, alpha = 0.18f * alpha, dy = dp(1.2f), sigma = dp(2f))
        fill.color = withAlpha(accent, alpha)
        canvas.drawCircle(x, y, r, fill)
        text.color = withAlpha(onAccent, alpha)
        text.textSize = r * 1.1f
        canvas.drawText(label(l), x, y - (text.ascent() + text.descent()) / 2, text)
    }

    // ---------------------------------------------------------------- ring key pop-ups

    /** A pop-up above a pressed ring key: springs up on press, pulses on each repeat, fades on release. */
    private class KeyPop(val slot: Int, val start: Long) {
        var released = -1L
        var bump = -1L
    }
    private val keyPops = ArrayList<KeyPop>()

    private fun popKey(slot: Int) {
        keyPops.removeAll { it.slot == slot }
        keyPops.add(KeyPop(slot, SystemClock.uptimeMillis()))
        invalidate()
    }

    private fun releaseKeyPops() {
        val now = SystemClock.uptimeMillis()
        keyPops.forEach { if (it.released < 0) it.released = now }
        invalidate()
    }

    private fun bumpKeyPop(slot: Int) {
        keyPops.firstOrNull { it.slot == slot && it.released < 0 }?.bump = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawKeyPops(canvas: Canvas, now: Long) {
        if (keyPops.isEmpty()) return
        val pal = palette
        val it = keyPops.iterator()
        while (it.hasNext()) {
            val pop = it.next()
            var alpha = 1f
            var scale: Float
            val tIn = ((now - pop.start) / KEY_POP_IN_MS).coerceIn(0f, 1f)
            scale = 0.4f + 0.6f * popOvershoot.getInterpolation(tIn)
            if (pop.released >= 0) {
                val tOut = (now - pop.released) / KEY_POP_OUT_MS
                if (tOut >= 1f) { it.remove(); continue }
                alpha = 1f - tOut
                scale *= 1f - 0.2f * tOut
            }
            if (pop.bump >= 0) {
                val b = (now - pop.bump) / KEY_POP_BUMP_MS
                if (b < 1f) scale *= 1f + 0.1f * sin(PI.toFloat() * b)
            }
            val keyX = slotX(pop.slot)
            val keyY = slotY(pop.slot)
            val keyR = slotRadius(pop.slot)
            val popR = keyR * 1.3f * scale
            val targetY = keyY - keyR - keyR * 1.3f - dp(6f)
            val travel = decelerate.getInterpolation(tIn)
            val y = keyY + (targetY - keyY) * travel
            drawSoftShadow(canvas, keyX, y, popR, alpha = 0.18f * alpha, dy = dp(1.2f), sigma = dp(2f))
            fill.color = withAlpha(pal.pressed, alpha)
            canvas.drawCircle(keyX, y, popR, fill)
            drawSlotIcon(canvas, slots[pop.slot].action, keyX, y, popR, withAlpha(pal.onPressed, alpha))
        }
    }

    /** Whether something is mid-animation and needs another frame. */
    private fun popsAnimating(now: Long): Boolean {
        if (outgoingKey != null) return true
        if (hovered != null && now - previewStart < PREVIEW_POP_MS) return true
        return keyPops.any { p ->
            now - p.start < KEY_POP_IN_MS || p.released >= 0 || (p.bump >= 0 && now - p.bump < KEY_POP_BUMP_MS)
        }
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

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillText = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pillLabel = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val pillRect = RectF()

    /**
     * The dictation pill above the bubble: live words while listening (newest lines kept in view),
     * the transcript with "Polishing" while the cleanup model runs, or a short message. Pops in.
     */
    private fun drawVoicePill(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val shown = voice != Voice.OFF || talking
        val fadeOut = if (!shown) ((now - voiceHiddenAt) / PILL_OUT_MS).coerceIn(0f, 1f) else 0f
        if (!shown && fadeOut >= 1f) return
        if (cx.isNaN()) return
        val state = if (shown) (if (voice == Voice.OFF) Voice.LISTENING else voice) else hiddenVoice
        val pal = palette
        val tIn = ((now - voiceShownAt) / PILL_IN_MS).coerceIn(0f, 1f)
        val alpha = if (shown) tIn else 1f - fadeOut
        val scale = if (shown) 0.9f + 0.1f * popOvershoot.getInterpolation(tIn) else 1f - 0.05f * fadeOut

        val body = when {
            talking && talkCancelArmed -> "Release to cancel"
            state == Voice.LISTENING && voiceText.isBlank() -> "Listening…"
            else -> voiceText
        }
        val label = when {
            talking && talkCancelArmed -> null
            state == Voice.CLEANING -> "Polishing…"
            else -> null
        }
        val dim = state == Voice.LISTENING && voiceText.isBlank() || talkCancelArmed

        val pad = dp(14f)
        val maxW = min(dp(320f), safeArea.width() - dp(32f))
        pillText.textSize = dp(16f)
        pillText.color = withAlpha(pal.onBloomKey, if (dim) 0.6f else 1f)
        val layout = android.text.StaticLayout.Builder
            .obtain(body, 0, body.length, pillText, (maxW - 2 * pad).toInt().coerceAtLeast(1))
            .setIncludePad(false)
            .build()
        var textW = 0f
        for (i in 0 until layout.lineCount) textW = max(textW, layout.getLineWidth(i))
        val lines = min(layout.lineCount, PILL_MAX_LINES)
        val firstLine = layout.lineCount - lines
        val textTop = layout.getLineTop(firstLine).toFloat()
        val textH = layout.getLineBottom(layout.lineCount - 1) - textTop
        pillLabel.textSize = dp(12f)
        pillLabel.color = pal.pressed
        val labelH = if (label != null) dp(18f) else 0f
        val w = max(textW, if (label != null) pillLabel.measureText(label) else 0f) + 2 * pad
        val h = textH + labelH + 2 * dp(10f)

        var left = (cx - w / 2).coerceIn(safeArea.left + dp(16f), max(safeArea.left + dp(16f), safeArea.right - dp(16f) - w))
        val shownR = if (mode == Mode.BUBBLE) bubbleRadius else max(bubbleRadius, discRadius * ringProgress(0).coerceIn(0f, 1f))
        var bottom = cy - shownR - dp(12f)
        var top = bottom - h
        if (top < safeArea.top + dp(8f)) { // no room above: sit below the bubble
            top = cy + shownR + dp(12f)
            bottom = top + h
        }
        canvas.save()
        canvas.scale(scale, scale, cx, if (bottom < cy) bottom else top)
        pillRect.set(left, top, left + w, bottom)
        pillPaint.color = withAlpha(pal.bloomKey, alpha)
        pillPaint.setShadowLayer(dp(3f), 0f, dp(1f), shadowBlack(0.18f * alpha))
        val radius = dp(18f)
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
        pillPaint.clearShadowLayer()
        var y = top + dp(10f)
        if (label != null) {
            pillLabel.alpha = (255 * alpha).toInt()
            canvas.drawText(label, left + pad, y + dp(12f), pillLabel)
            y += labelH
        }
        canvas.clipRect(left, y, left + w, y + textH)
        canvas.translate(left + pad, y - textTop)
        pillText.alpha = (255 * alpha * (if (dim) 0.6f else 1f)).toInt()
        layout.draw(canvas)
        canvas.restore()

        if (shown || fadeOut < 1f) {
            if (talking || voice == Voice.LISTENING || voice == Voice.CLEANING || tIn < 1f || !shown) postInvalidateOnAnimation()
        }
    }

    /**
     * The open ring's countdown: a thin arc around the center that drains clockwise from 12 o'clock
     * until the ring folds up. Redrawn about once per degree rather than every frame.
     */
    private fun drawCountdown(canvas: Canvas, centerR: Float) {
        if (mode != Mode.RING || idleDeadline == 0L || ringOpening && ringProgress(0) < 1f) return
        val now = SystemClock.uptimeMillis()
        val left = (idleDeadline - now).coerceAtLeast(0L)
        val fraction = left.toFloat() / ringTimeoutMs
        if (fraction <= 0f) return
        val r = centerR + dp(4.5f)
        iconRect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = dp(2.5f)
        stroke.color = withAlpha(palette.onCenter, 0.12f)
        canvas.drawCircle(cx, cy, r, stroke)
        stroke.color = withAlpha(palette.pressed, 0.75f)
        val sweep = 360f * fraction
        canvas.drawArc(iconRect, -90f + (360f - sweep), sweep, false, stroke)
        postInvalidateDelayed((ringTimeoutMs / 360L).coerceIn(16L, 250L))
    }

    /** A microphone on the same grid as the keyboard glyph. */
    private fun drawMicGlyph(canvas: Canvas, x: Float, y: Float, half: Float, c: Int) {
        val u = half / 9f
        stroke.color = c
        stroke.strokeWidth = 2f * u
        fill.color = c
        iconRect.set(x - 3.2f * u, y - 9f * u, x + 3.2f * u, y + 2.5f * u)
        canvas.drawRoundRect(iconRect, 3.2f * u, 3.2f * u, fill)
        iconRect.set(x - 6.5f * u, y - 5f * u, x + 6.5f * u, y + 6f * u)
        canvas.drawArc(iconRect, 0f, 180f, false, stroke)
        canvas.drawLine(x, y + 6f * u, x, y + 9f * u, stroke)
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
        private const val SHADOW_STOPS = 10
        // the letter preview pops up out of the key in 110 ms; the old one leaves in 90 ms
        private const val PREVIEW_POP_MS = 110f
        private const val PREVIEW_OUT_MS = 90f
        // ring key pop-ups
        private const val KEY_POP_IN_MS = 90f
        private const val KEY_POP_OUT_MS = 140f
        private const val KEY_POP_BUMP_MS = 80f
        /** The ring's disc lets the app show through a little. */
        private const val DISC_OPACITY = 0.75f
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
        // hold to talk
        private const val TALK_HOLD_MS = 400L
        private const val TALK_CANCEL_DP = 90f
        private const val MESSAGE_MS = 3000L
        private const val PILL_IN_MS = 140f
        private const val PILL_OUT_MS = 160f
        private const val PILL_MAX_LINES = 4
        private const val SLOT_LONG_PRESS_MS = 500L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

    }
}
