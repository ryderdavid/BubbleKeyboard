package com.ryderdavid.bubblekeyboard

import android.animation.TimeAnimator
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
import android.view.VelocityTracker
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
class BubbleView(context: Context, attrs: AttributeSet?) : View(context, attrs), KeyboardSurface {

    enum class Mode { BUBBLE, RING, BLOOM, STICK }

    enum class Key { UP, DOWN, LEFT, RIGHT, SPACE, DELETE, ENTER }

    interface Listener {
        fun onKey(key: Key, repeat: Boolean)
        fun onLetter(ch: Char)
        /** Long-press on the up arrow: switch to another keyboard. */
        fun onSwitchKeyboard()
        /** Long-press on the down arrow: open Bubble Keyboard's settings. */
        fun onOpenSettings() {}
        /** Switch between the docked bar and the floating bubble. */
        fun onSwitchStyle(docked: Boolean) {}
        /** Whether ABC hands off to Gboard instead of opening our own keys. */
        fun gboardMode(): Boolean = false
        fun onSwitchToGboard() {}
        /** Dictation finished (not thrown away); [polish] runs Claude's cleanup even when it's off. */
        fun onTalkFinish(polish: Boolean) = onTalkEnd(false)
        /** Clear the whole field. */
        fun onDeleteAll() {}
        /** Whether the next letter should be capitalized. */
        fun shouldCapitalize(): Boolean
        /**
         * Start dictation: the bubble or ring center was held (talk while held), or the ring
         * center was tapped (talk until tapped again). False if it couldn't start.
         */
        fun onTalkStart(): Boolean = false
        /** Cut, copy or paste on the current selection. */
        fun onEdit(action: EditAction) {}
        /** Dictation should end: type it, or throw it out when [cancel]. */
        fun onTalkEnd(cancel: Boolean) {}
        /** The cursor joystick came up (a triple tap on the bubble). */
        fun onStickStart() {}
        /**
         * The knob is pushed: move [cols] characters and [lines] lines. Aiming moves a target
         * marker; while [selecting] it extends the selection from the cursor.
         */
        fun onStickStep(cols: Int, lines: Int, selecting: Boolean) {}
        /** A tap on the knob: the cursor jumps to the target. */
        fun onStickTap() {}
        /** The knob was held still: a selection starts at the cursor. */
        fun onStickSelect() {}
        /** The joystick went away (its ✕, or the keyboard closing). */
        fun onStickEnd() {}
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

    /** Hands-free dictation finishes after this long without speech (the "Stop listening after silence" setting). */
    private var silenceMs = Prefs.DEFAULT_SILENCE_TIMEOUT * 1000L
    /** Holding the bubble or the ring's center talks while held (the "Hold to talk" setting). */
    /** Tapping the folded bubble opens the ring and starts dictating right away (the "Tap to talk" setting). */
    /** How long the open ring waits untouched before folding up (the "Ring stays open" setting). */
    private var ringTimeoutMs = Prefs.DEFAULT_RING_TIMEOUT * 1000L
    /** The open ring stays open with no countdown (pulled out from the up arrow, or the settings switch). */
    var pinned = Prefs.DEFAULT_RING_PINNED
        private set
    /** Faint hints outside the ring for what holding each key does (the "Show hold hints" setting). */
    private var holdHints = Prefs.DEFAULT_HOLD_HINTS
    /** Opacity of the resting bubble (the "Bubble opacity" setting); it comes up to full as the ring opens. */
    private var restingOpacity = Prefs.DEFAULT_BUBBLE_OPACITY / 100f
    private val pushDp get() = 60f * swell
    private val pushSigmaDp get() = max(24f, pushDp / 0.9f)
    private val maxKeyRadiusDp get() = 12f + 14f * swell
    private val magnifySigmaDp get() = 16f + 16f * swell

    override fun reloadSettings() {
        val prefs = Prefs.get(context)
        bubbleDiameterDp = prefs.getInt(Prefs.BUBBLE_SIZE, Prefs.DEFAULT_BUBBLE_SIZE).toFloat()
        ringDiameterDp = prefs.getInt(Prefs.RING_SIZE, Prefs.DEFAULT_RING_SIZE).toFloat()
        rotateBloom = prefs.getBoolean(Prefs.ROTATE_BLOOM, Prefs.DEFAULT_ROTATE_BLOOM)
        swell = prefs.getInt(Prefs.SWELL, Prefs.DEFAULT_SWELL).coerceIn(Prefs.SWELL_RANGE) / 100f
        restingOpacity = prefs.getInt(Prefs.BUBBLE_OPACITY, Prefs.DEFAULT_BUBBLE_OPACITY).coerceIn(Prefs.BUBBLE_OPACITY_RANGE) / 100f
        silenceMs = prefs.getInt(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT).coerceIn(Prefs.SILENCE_TIMEOUT_RANGE) * 1000L
        ringTimeoutMs = prefs.getInt(Prefs.RING_TIMEOUT, Prefs.DEFAULT_RING_TIMEOUT).coerceIn(Prefs.RING_TIMEOUT_RANGE) * 1000L
        pinned = prefs.getBoolean(Prefs.RING_PINNED, Prefs.DEFAULT_RING_PINNED)
        holdHints = prefs.getBoolean(Prefs.HOLD_HINTS, Prefs.DEFAULT_HOLD_HINTS)
        if (mode == Mode.RING) scheduleIdle()
        clampCenter()
        invalidate()
        requestLayout()
    }

    // ring geometry, designed at a 150 dp ring and scaled from there
    private val ringScale get() = ringDiameterDp / 150f
    private val discRadius get() = dp(75f) * ringScale
    private val centerRadius get() = dp(18f) * ringScale
    private val bubbleRadius get() = dp(bubbleDiameterDp / 2f)

    private enum class SlotAction { UP, DOWN, LEFT, RIGHT, SPACE, DELETE, ENTER, LETTERS, FOLD, STICK, SETTINGS, PASTE }
    private class Slot(val action: SlotAction, val dx: Float, val dy: Float, val radiusDp: Float, val cardinal: Boolean)

    // clockwise from the top, which is also the order the slots spring out in
    // the same four as the bubble's notches: up the full keyboard, right the joystick, down settings,
    // left paste (grayed with nothing to paste; while dictating it's the discard ✕)
    private val slots = listOf(
        Slot(SlotAction.LETTERS, 0f, -49f, 18f, true),
        Slot(SlotAction.STICK, 49f, 0f, 18f, true),
        Slot(SlotAction.SETTINGS, 0f, 49f, 18f, true),
        Slot(SlotAction.PASTE, -49f, 0f, 18f, true),
    )
    private val lettersSlot = slots.indexOfFirst { it.action == SlotAction.LETTERS }
    private val spaceSlot = slots.indexOfFirst { it.action == SlotAction.SPACE }
    private val enterSlot = slots.indexOfFirst { it.action == SlotAction.ENTER }
    /** The key that turns into the discard ✕ while dictating. */
    private val deleteSlot = slots.indexOfFirst { it.action == SlotAction.PASTE }
    private val pasteSlot = deleteSlot

    /** The clipboard has text to paste (set by the service; the contents are never read here). */
    override var canPaste = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** While dictating, backspace becomes a discard key that throws the dictation away. */
    private val discardMode get() = talking
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
        Bloom.PUNCTUATION -> if (spaceSlot >= 0) spaceSlot else lettersSlot
        Bloom.NUMBERS -> if (enterSlot >= 0) enterSlot else lettersSlot
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
    override fun reset() {
        stopFling()
        if (mode == Mode.STICK) endStick(notify = true, fold = false)
        ringAnimator?.cancel()
        bloomAnimator?.cancel()
        removeCallbacks(idleRunnable)
        removeCallbacks(stickyBloomRunnable)
        removeCallbacks(talkRunnable)
        hideSideHint()
        if (talking) endTalk(cancel = true)
        gridOpen = false
        gridPressed = -1
        idleDeadline = 0L
        stopRepeat()
        removeCallbacks(holdRunnable)
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
        if (pinned) { mode = Mode.RING; ringOpening = true; ringElapsed = RING_OPEN_MS + RING_STAGGER_MS * (slots.size - 1) }
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
        removeCallbacks(sideHintRunnable)
        removeCallbacks(talkRunnable)
        flingAnimator?.cancel()
        flingAnimator = null
        velocity?.recycle()
        velocity = null
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

    /**
     * In gesture navigation the band the system draws the keyboard's ⌄ and switcher in is the
     * platform's navigation_bar_frame_height (InputMethodService's NavigationBarController sizes it
     * so), not the thin gesture inset. Not public API, so read defensively: 0 when absent or when
     * navigation isn't gestural (3-button bars already report their full height).
     */
    private fun imeNavFrameHeight(): Int {
        val gestural = runCatching {
            android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
        }.getOrDefault(false)
        if (!gestural) return 0
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun updateSafeArea() {
        // with gesture navigation the system still draws the keyboard's own back (⌄) and switcher
        // buttons, in a band taller than the gesture-pill inset; newer Android reports that band as
        // a caption bar (the "IME navigation bar"), so the strip covers whichever is tallest
        val root = ViewCompat.getRootWindowInsets(this)
        navBarInset = maxOf(
            root?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0,
            root?.getInsets(WindowInsetsCompat.Type.captionBar())?.bottom ?: 0,
            root?.getInsets(WindowInsetsCompat.Type.tappableElement())?.bottom ?: 0,
            if ((root?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0) > 0) imeNavFrameHeight() else 0,
        )
        val insets = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        safeArea.set(
            insets?.left ?: 0, insets?.top ?: 0,
            width - (insets?.right ?: 0), height - maxOf(insets?.bottom ?: 0, navBarInset)
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
        if (mode == Mode.STICK) {
            val er = dp(STICK_EXIT_DP / 2 + 6f)
            for (x in listOf(PointF().apply { set(stickExitCenter()) }, stickKeysCenter())) {
                rects.add(Rect((x.x - er).toInt(), (x.y - er).toInt(), (x.x + er).toInt(), (x.y + er).toInt()))
            }
        }
        if (gridOpen && mode == Mode.RING) {
            layoutGrid()
            rects.add(Rect(gridRect.left.toInt(), gridRect.top.toInt(), gridRect.right.toInt(), gridRect.bottom.toInt()))
        }
        if (editVisible) {
            layoutEditButtons()
            val hw = editHalfW + dp(6f)
            val hh = editHalfH + dp(6f)
            for (i in 0 until editCount) {
                val c = editCenters[i]
                rects.add(Rect((c.x - hw).toInt(), (c.y - hh).toInt(), (c.x + hw).toInt(), (c.y + hh).toInt()))
            }
        }
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
        closeGrid()
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
        if (mode != Mode.RING || talking || gridOpen || pinned) { idleDeadline = 0L; invalidate(); return }
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
    /**
     * The press on the folded bubble locked in: a firm click, the ring opens, and dictation starts
     * and keeps going after the finger lifts, until the center (done) or ✕ (discard) is tapped.
     */
    private val talkRunnable = Runnable {
        if (mode == Mode.BUBBLE && downTarget == -1 && !dragging && !talking) {
            centerLongPressed = true // this finger's release does nothing more
            talkCancelArmed = false
            pressStartAt = 0L
            expandRing()
            startLatchedTalk(lockIn = true)
            if (!talking) buzzWeak() // couldn't start (no key, no mic): the ring is open anyway
            invalidate()
        }
    }

    // ---------------------------------------------------------------- press to talk: tick, sink, click

    /** A finger is pressing the folded bubble toward hold-to-talk; 0 when not. */
    private var pressStartAt = 0L
    /** When the press locked in (for the spring-back and ripple); 0 when not animating. */
    private var engagedAt = 0L
    private val lockInSpring = OvershootInterpolator(2.6f)

    private fun endPress() {
        if (pressStartAt == 0L) return
        pressStartAt = 0L
        invalidate()
    }

    /** A tap on the ring center: start talking until the next tap. */
    private fun startLatchedTalk(lockIn: Boolean = false) {
        talkCancelArmed = false
        if (listener?.onTalkStart() == true) {
            talking = true
            talkLatched = true
            startSilenceClock()
            if (lockIn) {
                // the click, felt and seen: the button springs back up as the mic, a ripple goes out
                buzzClick()
                engagedAt = SystemClock.uptimeMillis()
            } else {
                performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            }
            pauseIdle()
        }
        invalidate()
    }

    private fun endTalk(cancel: Boolean) {
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        talkLatched = false
        talkCancelArmed = false
        stopLatchOnUp = false
        listener?.onTalkEnd(cancel)
        scheduleIdle()
        invalidate()
    }

    /** The dictation ended on its own (finished, or failed): forget that we're talking. */
    override fun talkEnded() {
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
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

    /**
     * The words are being typed into the field as you speak: the pill shrinks to its status label,
     * and the mic's halo (not the pill) breathes with your voice.
     */
    override var voiceInField = false
        set(value) { field = value; invalidate() }

    // ---------------------------------------------------------------- cut / copy / paste

    enum class EditAction(val glyph: Icons.Glyph) { CUT(Icons.Glyph.CUT), COPY(Icons.Glyph.COPY), PASTE(Icons.Glyph.PASTE), DELETE(Icons.Glyph.BACKSPACE) }

    /** Text is selected in the field: cut, copy, paste and delete sit beside the bubble or ring. */
    override var hasSelection = false
        set(value) {
            if (field == value) return
            field = value
            editShownAt = SystemClock.uptimeMillis()
            invalidate()
            requestLayout() // the touchable region changes
        }
    private var editShownAt = 0L
    private var editPressed = -1
    private val editCenters = Array(4) { PointF() }

    /** A selection brings the four buttons; in joystick mode they stand by. */
    private val editCount get() = EditAction.entries.size
    private fun editAction(i: Int) = if (editCount > 1) EditAction.entries[i] else EditAction.PASTE
    /** Cut and copy have nothing to act on (joystick mode, nothing selected): drawn faded. */
    private fun editIdle(i: Int) = mode == Mode.STICK && !hasSelection && (editAction(i) == EditAction.CUT || editAction(i) == EditAction.COPY)
    private val editHalfW get() = dp(EDIT_DP / 2)
    private val editHalfH get() = dp(EDIT_DP / 2)

    private val editVisible get() = (hasSelection || mode == Mode.STICK) &&
        (mode == Mode.BUBBLE || mode == Mode.RING || mode == Mode.STICK) && sideSlot < 0 && !cx.isNaN()

    /**
     * Lays the three buttons out in a line beside the bubble (or the open ring): a column to its
     * left or right, whichever has more room, or if neither fits, a row above or below.
     */
    private fun layoutEditButtons() {
        val n = editCount
        val base = (if (mode == Mode.BUBBLE) bubbleRadius else discRadius) + dp(EDIT_GAP_DP)
        val hw = editHalfW
        val hh = editHalfH
        val step = dp(EDIT_DP + EDIT_SPACING_DP)
        val spread = (n - 1) / 2f * step // from the middle button to an end one
        val half = dp(EDIT_DP / 2 + 4f)
        val room = mapOf(
            0 to safeArea.right - cx, // right
            1 to cx - safeArea.left, // left
            2 to cy - safeArea.top, // up
            3 to safeArea.bottom - cy, // down
        )
        // a side fits if the line of buttons stays on screen there. Beside the bubble (left/right,
        // at thumb height) comes first, the roomier of the two; then above or below
        val order = room.entries.sortedWith(compareBy<Map.Entry<Int, Float>> { if (it.key <= 1) 0 else 1 }.thenByDescending { it.value })
        val side = order.firstOrNull { (dir, space) ->
            val reach = base + if (dir <= 1) 2 * hw else 2 * hh
            space >= reach + dp(4f) && when (dir) {
                0, 1 -> cy - spread >= safeArea.top + half && cy + spread <= safeArea.bottom - half
                else -> cx - spread >= safeArea.left + hw + dp(4f) && cx + spread <= safeArea.right - hw - dp(4f)
            }
        }?.key ?: room.maxByOrNull { it.value }!!.key
        for (i in 0 until n) {
            val along = i * step - spread
            when (side) {
                0 -> editCenters[i].set(cx + base + hw, cy + along)
                1 -> editCenters[i].set(cx - base - hw, cy + along)
                2 -> editCenters[i].set(cx + along, cy - base - hh)
                else -> editCenters[i].set(cx + along, cy + base + hh)
            }
            editCenters[i].x = editCenters[i].x.coerceIn(safeArea.left + hw + dp(4f), safeArea.right - hw - dp(4f))
            editCenters[i].y = editCenters[i].y.coerceIn(safeArea.top + half, safeArea.bottom - half)
        }
        editSide = side
    }
    /** Which side of the bubble the buttons went: 0 right, 1 left, 2 above, 3 below (for the chip's slide-in). */
    private var editSide = 0

    private fun hitEdit(x: Float, y: Float): Int {
        if (!editVisible) return -1
        layoutEditButtons()
        val slop = dp(6f)
        for (i in 0 until editCount) {
            val c = editCenters[i]
            if (hypot(c.x - x, c.y - y) <= dp(EDIT_DP / 2) + slop) return i
        }
        return -1
    }

    private fun drawEditButtons(canvas: Canvas) {
        if (!editVisible) return
        layoutEditButtons()
        val pal = palette
        val now = SystemClock.uptimeMillis()
        val r = dp(EDIT_DP / 2)
        var animating = false
        for (i in 0 until editCount) {
            // a quick staggered pop, like the ring's keys
            val t = ((now - editShownAt - i * 30f) / EDIT_POP_MS).coerceIn(0f, 1f)
            if (t < 1f) animating = true
            if (t <= 0f) continue
            val scale = popOvershoot.getInterpolation(t)
            val c = editCenters[i]
            val pressed = i == editPressed
            drawSoftShadow(canvas, c.x, c.y, r * scale, alpha = 0.2f * t, dy = dp(1f), sigma = dp(1.8f))
            fill.color = withAlpha(if (pressed) pal.pressed else pal.bloomKey, t)
            canvas.drawCircle(c.x, c.y, r * scale, fill)
            fill.color = withAlpha(if (pressed) pal.onPressed else pal.onBloomKey, t * (if (editIdle(i)) 0.38f else 1f))
            Icons.draw(canvas, editAction(i).glyph, c.x, c.y, dp(20f) * scale, fill)
        }
        if (animating) postInvalidateOnAnimation()
    }

    /** What the dictation pill above the bubble shows. */
    enum class Voice { OFF, LISTENING, CLEANING, MESSAGE }
    override var voice = Voice.OFF
        private set
    private var voiceText = ""
    private var voiceShownAt = 0L
    private var voiceHiddenAt = 0L
    private var voiceLevelTarget = 0f
    private var voiceLevel = 0f
    // automatic gain: the loudest recent level and the room's quiet level, so your normal speaking
    // voice fills the throb whether you whisper or talk across a room
    private var levelPeak = LEVEL_PEAK_MIN
    private var levelFloor = 0f
    private var levelAt = 0L
    private val hideVoiceRunnable = Runnable { hideVoice() }

    override fun showListening() {
        removeCallbacks(hideVoiceRunnable)
        voice = Voice.LISTENING
        voiceText = ""
        voiceLevel = 0f
        voiceLevelTarget = 0f
        levelPeak = LEVEL_PEAK_MIN
        levelFloor = 0f
        levelAt = 0L
        voiceShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** A raw 0..1 loudness from the mic; shown normalized to the recent range of your voice. */
    override fun showLevel(level: Float) {
        val raw = level.coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        val first = levelAt == 0L
        val dt = if (first) 0f else ((now - levelAt) / 1000f).coerceAtMost(1f)
        levelAt = now
        // the peak jumps up at once and sinks slowly; the floor drops at once and creeps up slowly
        levelPeak = max(raw, max(LEVEL_PEAK_MIN, levelPeak - LEVEL_PEAK_DECAY * dt))
        levelFloor = if (first || raw < levelFloor) raw else min(raw, levelFloor + LEVEL_FLOOR_RISE * dt)
        val range = max(LEVEL_MIN_RANGE, levelPeak - levelFloor)
        voiceLevelTarget = ((raw - levelFloor) / range).coerceIn(0f, 1f)
        invalidate()
    }

    override fun showLiveText(text: String) {
        voiceText = text
        invalidate()
    }

    override fun showCleaning(text: String) {
        voice = Voice.CLEANING
        voiceText = text
        invalidate()
    }

    override fun showMessage(message: String) {
        removeCallbacks(hideVoiceRunnable)
        if (voice == Voice.OFF) voiceShownAt = SystemClock.uptimeMillis()
        voice = Voice.MESSAGE
        voiceText = message
        postDelayed(hideVoiceRunnable, MESSAGE_MS)
        invalidate()
    }

    override fun hideVoice() {
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
            spaceSlot -> openBloom(fromHold = true, kind = Bloom.PUNCTUATION)
            enterSlot -> openBloom(fromHold = true, kind = Bloom.NUMBERS)
        }
    }

    // ---------------------------------------------------------------- the full keyboard grid

    /** The QWERTY grid is out beside the ring (a tap on abc), open until abc (now a ✕) is tapped. */
    var gridOpen = false
        private set
    private var gridShownAt = 0L
    private var gridHiddenAt = 0L
    /** The next letter only, from one tap on shift; a quick second tap is caps lock instead. */
    private var shiftOnce = false
    private var lastShiftTap = 0L
    /** The field wants a capital here (start of a sentence), refreshed after each key. */
    private var gridAutoCap = false
    private var gridPressed = -1
    /** Sliding along the grid's space bar moves the cursor instead of typing a space (Gboard's gesture). */
    private var spaceSliding = false
    private var spaceSlideX = 0f

    private class Pill(val ch: Char) { val rect = RectF() }

    /** Holding the grid's comma: a pop-up above it to slide onto for the joystick. */
    private var commaMenu = false
    private var commaHot = false
    private var commaMenuAt = 0L
    private val commaChip = RectF()
    private val commaHoldRunnable = Runnable {
        val i = gridPressed
        if (!gridOpen || i < 0 || pills[i].ch != ',' || downTarget != -4) return@Runnable
        commaMenu = true
        commaHot = false
        commaMenuAt = SystemClock.uptimeMillis()
        layoutCommaChip(pills[i].rect)
        buzzWeakThenStrong()
        announceForAccessibility(context.getString(R.string.comma_joystick_hint))
        invalidate()
    }

    private fun layoutCommaChip(r: RectF) {
        val w = max(r.width() * 2.1f, dp(64f))
        val h = max(r.height() * 1.55f, dp(56f))
        val bottom = r.top - dp(8f)
        var left = r.centerX() - w / 2
        left = left.coerceIn(safeArea.left + dp(4f), safeArea.right - dp(4f) - w)
        commaChip.set(left, bottom - h, left + w, bottom)
    }

    /** The finger is on the pop-up (generously: anywhere above the comma within its width counts). */
    private fun overCommaChip(x: Float, y: Float): Boolean {
        val s = dp(14f)
        return x >= commaChip.left - s && x <= commaChip.right + s && y <= commaChip.bottom + dp(6f) && y >= commaChip.top - dp(40f)
    }

    @VisibleForTesting internal val testCommaMenu get() = commaMenu
    @VisibleForTesting internal fun testCommaChip(): PointF = PointF(commaChip.centerX(), commaChip.centerY())
    @VisibleForTesting internal fun testPillCenter(ch: Char): PointF { layoutGrid(); val r = pills.first { it.ch == ch }.rect; return PointF(r.centerX(), r.centerY()) }
    private val pills: List<Pill> = (GRID_ROWS.joinToString("") + "$SHIFT$BACKSPACE,$SPACE_CH.$ENTER").map { Pill(it) }
    /** Keys that act rather than type a character, drawn in the ring's key color. */
    private fun isFunctionPill(ch: Char) = ch == SHIFT || ch == BACKSPACE || ch == ENTER
    /** Pills whose character pops up above the finger. */
    private fun popsUp(ch: Char) = !isFunctionPill(ch) && ch != SPACE_CH
    private val gridRect = RectF()
    private var gridAbove = true

    private fun openGrid() {
        if (gridOpen) return
        gridOpen = true
        gridShownAt = SystemClock.uptimeMillis()
        shiftOnce = false
        gridAutoCap = listener?.shouldCapitalize() == true
        pauseIdle()
        invalidate()
        requestLayout()
    }

    private fun closeGrid() {
        if (!gridOpen) return
        gridOpen = false
        gridPressed = -1
        gridHiddenAt = SystemClock.uptimeMillis()
        if (mode == Mode.RING) scheduleIdle()
        invalidate()
        requestLayout()
    }

    /**
     * Lays the pills out as a staggered keyboard: rows offset by half a key and a key and a half,
     * above the ring (or below it when there's no room), shrunk only if the screen is too narrow.
     */
    private fun layoutGrid() {
        val s = min(1f, (safeArea.width() - dp(16f)) / dp(GRID_ROW_DP))
        val w = dp(PILL_W_DP) * s
        val h = dp(PILL_H_DP) * s
        val gap = dp(PILL_GAP_DP) * s
        val rowGap = dp(PILL_ROW_GAP_DP) * s
        val step = w + gap
        val bw = 10 * step - gap
        val bh = 4 * h + 3 * rowGap
        val margin = dp(8f)
        val x0 = (cx - bw / 2).coerceIn(safeArea.left + margin, max(safeArea.left + margin, safeArea.right - margin - bw))
        var y0 = cy - discRadius - dp(14f) - bh
        gridAbove = y0 >= safeArea.top + margin
        if (!gridAbove) y0 = (cy + discRadius + dp(14f)).coerceAtMost(safeArea.bottom - margin - bh)
        var i = 0
        GRID_ROWS.forEachIndexed { row, letters ->
            val indent = GRID_INDENTS[row]
            letters.forEachIndexed { col, _ ->
                val x = x0 + (indent + col) * step
                val y = y0 + row * (h + rowGap)
                pills[i++].rect.set(x, y, x + w, y + h)
            }
        }
        // shift and backspace frame the z row, as on any phone keyboard
        val y = y0 + 2 * (h + rowGap)
        pills[i++].rect.set(x0, y, x0 + 1.5f * step - gap, y + h)
        pills[i++].rect.set(x0 + 8.5f * step, y, x0 + 10f * step - gap, y + h)
        // bottom row: comma, a wide space bar, period, enter
        val y4 = y0 + 3 * (h + rowGap)
        var x = x0
        for (span in floatArrayOf(1.5f, 6f, 1f, 1.5f)) {
            pills[i++].rect.set(x, y4, x + span * step - gap, y4 + h)
            x += span * step
        }
        val pad = dp(6f)
        gridRect.set(x0 - pad, y0 - pad, x0 + bw + pad, y0 + bh + pad)
    }

    private fun hitPill(x: Float, y: Float): Int {
        if (!gridOpen || mode != Mode.RING) return -1
        layoutGrid()
        if (!gridRect.contains(x, y)) return -1
        val slop = dp(PILL_GAP_DP) / 2 + 1f
        var best = -1
        var bestD = Float.MAX_VALUE
        pills.forEachIndexed { i, p ->
            val r = p.rect
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) {
                val d = hypot(r.centerX() - x, r.centerY() - y)
                if (d < bestD) { best = i; bestD = d }
            }
        }
        return best
    }

    private val gridCaps get() = capsLock || shiftOnce || gridAutoCap

    /** Backspace on the grid repeats while held, like the ring's. */
    private val gridRepeatRunnable = object : Runnable {
        override fun run() {
            if (gridPressed < 0 || pills[gridPressed].ch != BACKSPACE) return
            sendKey(SlotAction.DELETE, repeat = true)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /** A finger came down on a pill: backspace acts at once (and repeats); everything else on release. */
    private fun pressPill(i: Int) {
        spaceSliding = false
        if (pills[i].ch == BACKSPACE) {
            sendKey(SlotAction.DELETE)
            postDelayed(gridRepeatRunnable, REPEAT_START_MS)
        }
        if (pills[i].ch == ',') postDelayed(commaHoldRunnable, HOLD_MS)
    }

    private fun tapPill(i: Int) {
        val p = pills[i]
        when (p.ch) {
            BACKSPACE -> return // already done on press
            SPACE_CH -> { sendKey(SlotAction.SPACE); invalidate(); return }
            ENTER -> { sendKey(SlotAction.ENTER); invalidate(); return }
            ',', '.' -> {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onLetter(p.ch)
                gridAutoCap = listener?.shouldCapitalize() == true
                invalidate()
                return
            }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (p.ch == SHIFT) {
            val now = SystemClock.uptimeMillis()
            when {
                capsLock -> capsLock = false
                shiftOnce && now - lastShiftTap < SHIFT_DOUBLE_TAP_MS -> { shiftOnce = false; capsLock = true; buzzWeakThenStrong() }
                else -> shiftOnce = !shiftOnce
            }
            lastShiftTap = now
        } else {
            listener?.onLetter(if (gridCaps) p.ch.uppercaseChar() else p.ch)
            shiftOnce = false
            gridAutoCap = listener?.shouldCapitalize() == true
        }
        invalidate()
    }

    private val pillPaintRect = RectF()

    private fun drawGrid(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val closing = !gridOpen && now - gridHiddenAt < GRID_CLOSE_MS
        if ((!gridOpen && !closing) || mode != Mode.RING) return
        layoutGrid()
        val pal = palette
        val ax = slotX(lettersSlot)
        val ay = slotY(lettersSlot)
        var animating = closing
        val closeT = if (closing) 1f - (now - gridHiddenAt) / GRID_CLOSE_MS.toFloat() else 1f
        // the tray behind the pills keeps them readable over any page
        val trayT = if (gridOpen) ((now - gridShownAt) / GRID_POP_MS).coerceIn(0f, 1f) else closeT
        if (trayT < 1f) animating = true
        fill.color = withAlpha(pal.disc, DISC_OPACITY * 1.1f * trayT)
        canvas.drawRoundRect(gridRect, dp(26f), dp(26f), fill)
        val caps = gridCaps
        pills.forEachIndexed { i, p ->
            val r = p.rect
            // cascade out of abc, nearest pills first
            val delay = hypot(r.centerX() - ax, r.centerY() - ay) / dp(1f) * 0.55f
            val t = if (gridOpen) ((now - gridShownAt - delay) / GRID_POP_MS).coerceIn(0f, 1f) else closeT
            if (gridOpen && t < 1f) animating = true
            if (t <= 0f) return@forEachIndexed
            val k = if (gridOpen) popOvershoot.getInterpolation(t) else t
            val px = ax + (r.centerX() - ax) * k
            val py = ay + (r.centerY() - ay) * k
            val hw = r.width() / 2 * (0.2f + 0.8f * k)
            val hh = r.height() / 2 * (0.2f + 0.8f * k)
            pillPaintRect.set(px - hw, py - hh, px + hw, py + hh)
            val pressed = i == gridPressed
            val isShift = p.ch == SHIFT
            val shiftOn = isShift && (shiftOnce || capsLock)
            val isFn = isFunctionPill(p.ch)
            val bg = when {
                pressed -> pal.pressed
                shiftOn -> pal.center
                p.ch == ENTER -> pal.center
                isFn -> pal.cardinal
                else -> pal.bloomKey
            }
            val fg = when {
                pressed -> pal.onPressed
                shiftOn || p.ch == ENTER -> pal.onCenter
                isFn -> pal.onCardinal
                else -> pal.onBloomKey
            }
            // while the space bar slides the cursor, the other keys' labels fade (the keys stay solid)
            val a = t.coerceIn(0f, 1f) * (if (spaceSliding && !pressed) 0.3f else 1f)
            fill.color = withAlpha(bg, t.coerceIn(0f, 1f))
            canvas.drawRoundRect(pillPaintRect, hw.coerceAtMost(hh), hw.coerceAtMost(hh), fill)
            if (isShift) {
                drawShiftGlyph(canvas, pillPaintRect.centerX(), pillPaintRect.centerY(), hh * 0.55f, withAlpha(fg, a), filled = capsLock)
            } else if (p.ch == SPACE_CH && spaceSliding) {
                drawSlideArrows(canvas, pillPaintRect.centerX(), pillPaintRect.centerY(), hh * 0.5f, withAlpha(fg, a))
            } else if (p.ch == BACKSPACE || p.ch == ENTER || p.ch == SPACE_CH) {
                val action = when (p.ch) { BACKSPACE -> SlotAction.DELETE; ENTER -> SlotAction.ENTER; else -> SlotAction.SPACE }
                drawSlotIcon(canvas, action, pillPaintRect.centerX(), pillPaintRect.centerY(), hh * 0.8f,
                    withAlpha(if (p.ch == SPACE_CH && !pressed) pal.onCardinal else fg, a), asKey = true)
            } else {
                text.color = withAlpha(fg, a)
                text.textSize = hh * 0.95f
                val label = if (caps) p.ch.uppercaseChar().toString() else p.ch.toString()
                canvas.drawText(label, pillPaintRect.centerX(), pillPaintRect.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
        if (gridOpen && commaMenu) drawCommaMenu(canvas)
        // the pressed pill's letter pops up above the finger
        else if (gridOpen && gridPressed >= 0 && popsUp(pills[gridPressed].ch)) {
            val r = pills[gridPressed].rect
            val w = r.width() * 1.6f
            val h = r.height() * 1.25f
            val bottom = r.top - dp(6f)
            pillPaintRect.set(r.centerX() - w / 2, bottom - h, r.centerX() + w / 2, bottom)
            drawSoftShadow(canvas, pillPaintRect.centerX(), pillPaintRect.centerY(), min(w, h) / 2, alpha = 0.22f, dy = dp(1.5f), sigma = dp(2.5f))
            fill.color = pal.pressed
            canvas.drawRoundRect(pillPaintRect, w / 2.6f, w / 2.6f, fill)
            text.color = pal.onPressed
            text.textSize = h * 0.55f
            val ch = pills[gridPressed].ch
            canvas.drawText(if (caps) ch.uppercaseChar().toString() else ch.toString(), pillPaintRect.centerX(),
                pillPaintRect.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
        if (animating) postInvalidateOnAnimation()
    }

    /**
     * The held comma's pop-up: a rounded chip like the letter preview, joined to the comma by a
     * short stem, holding the joystick's I-beam and its name. It fills with the accent when the
     * finger is on it (let go to switch); otherwise it waits in the key color.
     */
    private fun drawCommaMenu(canvas: Canvas) {
        val pal = palette
        val t = ((SystemClock.uptimeMillis() - commaMenuAt) / 140f).coerceIn(0f, 1f)
        if (t < 1f) postInvalidateOnAnimation()
        val e = snapPop.getInterpolation(t)
        val r = commaChip
        val cxm = r.centerX()
        val cym = r.centerY()
        val w = r.width() * (0.6f + 0.4f * e)
        val h = r.height() * (0.6f + 0.4f * e)
        pillPaintRect.set(cxm - w / 2, r.bottom - h, cxm + w / 2, r.bottom)
        val bg = if (commaHot) pal.pressed else pal.bloomKey
        val fg = if (commaHot) pal.onPressed else pal.pressed
        drawSoftShadow(canvas, pillPaintRect.centerX(), pillPaintRect.centerY(), min(w, h) / 2, alpha = 0.26f * t, dy = dp(2f), sigma = dp(3.5f))
        fill.color = withAlpha(bg, t)
        canvas.drawRoundRect(pillPaintRect, min(w, h) / 2.4f, min(w, h) / 2.4f, fill)
        if (!commaHot) {
            stroke.color = withAlpha(pal.pressed, 0.45f * t)
            stroke.strokeWidth = dp(1.5f)
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
            canvas.drawRoundRect(pillPaintRect, min(w, h) / 2.4f, min(w, h) / 2.4f, stroke)
            stroke.pathEffect = null
        }
        val gy = pillPaintRect.top + h * 0.40f
        drawNotchGlyph(canvas, 1, cxm, gy, h * 0.42f, withAlpha(fg, t))
        secondText.color = withAlpha(fg, t)
        secondText.textSize = h * 0.17f
        canvas.drawText(context.getString(R.string.comma_joystick), cxm, pillPaintRect.top + h * 0.82f, secondText)
        // a small up-chevron on the held comma: slide up
        val c = pills.firstOrNull { it.ch == ',' }?.rect ?: return
        stroke.color = withAlpha(pal.onPressed, 0.85f * t)
        stroke.strokeWidth = dp(1.8f)
        icon.reset()
        val s = dp(4f)
        icon.moveTo(c.centerX() - s, c.top + dp(5f) + s * 0.6f); icon.lineTo(c.centerX(), c.top + dp(5f)); icon.lineTo(c.centerX() + s, c.top + dp(5f) + s * 0.6f)
        canvas.drawPath(icon, stroke)
    }

    /** The space bar while sliding the cursor: a left and a right chevron. */
    private fun drawSlideArrows(canvas: Canvas, x: Float, y: Float, s: Float, c: Int) {
        stroke.color = c
        stroke.strokeWidth = s * 0.28f
        icon.reset()
        icon.moveTo(x - s * 1.4f, y - s); icon.lineTo(x - s * 2.4f, y); icon.lineTo(x - s * 1.4f, y + s)
        icon.moveTo(x + s * 1.4f, y - s); icon.lineTo(x + s * 2.4f, y); icon.lineTo(x + s * 1.4f, y + s)
        canvas.drawPath(icon, stroke)
    }

    /** An outlined up arrow (shift); filled with a bar under it for caps lock. */
    private fun drawShiftGlyph(canvas: Canvas, x: Float, y: Float, s: Float, c: Int, filled: Boolean) {
        icon.reset()
        icon.moveTo(x, y - s)
        icon.lineTo(x + s, y)
        icon.lineTo(x + s * 0.45f, y)
        icon.lineTo(x + s * 0.45f, y + s * 0.7f)
        icon.lineTo(x - s * 0.45f, y + s * 0.7f)
        icon.lineTo(x - s * 0.45f, y)
        icon.lineTo(x - s, y)
        icon.close()
        if (filled) {
            fill.color = c
            canvas.drawPath(icon, fill)
            stroke.color = c
            stroke.strokeWidth = s * 0.22f
            canvas.drawLine(x - s * 0.45f, y + s * 1.05f, x + s * 0.45f, y + s * 1.05f, stroke)
        } else {
            stroke.color = c
            stroke.strokeWidth = s * 0.18f
            canvas.drawPath(icon, stroke)
        }
    }

    // ---------------------------------------------------------------- hands-free silence timeout

    /** Hands-free dictation finishes at this time unless speech is heard first; 0 when not counting. */
    private var silenceDeadline = 0L
    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (!(talking && talkLatched) || silenceDeadline == 0L) return
            val left = silenceDeadline - SystemClock.uptimeMillis()
            if (left > 0) { postDelayed(this, left); return }
            silenceDeadline = 0L
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            endTalk(cancel = false)
        }
    }

    private fun startSilenceClock() {
        silenceDeadline = SystemClock.uptimeMillis() + silenceMs
        removeCallbacks(silenceRunnable)
        postDelayed(silenceRunnable, silenceMs)
    }

    /** Speech came in (new words, or a clearly loud moment): the silence clock starts over. */
    override fun heardSpeech() {
        if (talking && talkLatched && silenceDeadline != 0L) silenceDeadline = SystemClock.uptimeMillis() + silenceMs
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
            SlotAction.LETTERS, SlotAction.FOLD, SlotAction.STICK, SlotAction.SETTINGS, SlotAction.PASTE -> return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onKey(key, repeat)
        if (gridOpen) gridAutoCap = listener?.shouldCapitalize() == true
    }

    private fun commitLetter(l: Letter) {
        val caps = bloomOfLetters && (capsLock || listener?.shouldCapitalize() == true)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onLetter(if (caps) l.ch.uppercaseChar() else l.ch)
    }

    private fun label(l: Letter) = if (bloomOfLetters && bloomCaps) l.ch.uppercaseChar().toString() else l.ch.toString()

    /** Keys that repeat while held. The arrows have side functions instead (tap, tap-and-hold repeats). */
    private fun repeats(action: SlotAction) = action == SlotAction.DELETE

    /**
     * Keys with a side function, reached by holding the key and sliding outward onto the hint that
     * pops up beyond it. They type on release; a quick second tap that's held repeats instead.
     */
    private fun sideGlyph(action: SlotAction): Icons.Glyph? = when (action) {
        SlotAction.UP -> if (pinned) Icons.Glyph.UNPIN else Icons.Glyph.PIN
        SlotAction.RIGHT -> Icons.Glyph.TEXT_CURSOR
        SlotAction.DOWN -> Icons.Glyph.SETTINGS
        SlotAction.LEFT -> Icons.Glyph.CLOSE
        else -> null
    }

    /** What each side function does, for accessibility. */
    private fun sideLabel(action: SlotAction): String? = when (action) {
        SlotAction.UP -> context.getString(if (pinned) R.string.side_unpin else R.string.side_pin)
        SlotAction.RIGHT -> context.getString(R.string.side_joystick)
        SlotAction.DOWN -> context.getString(R.string.side_settings)
        SlotAction.LEFT -> context.getString(R.string.side_close)
        else -> null
    }
    private fun hasSide(action: SlotAction) = sideLabel(action) != null

    /** The key whose side hint shows, or -1; [sideArmed] once the finger is out on the hint. */
    private var sideSlot = -1
    private var sideShownAt = 0L
    private var sideArmed = false
    /** This press is the held second tap of a double tap: it repeats rather than offering the side function. */
    private var doubleTapRepeat = false
    private var lastTapSlot = -1
    private var lastTapUpAt = 0L
    private var sideFired = false

    private val sideHintRunnable = Runnable { if (pressedSlot >= 0 && !doubleTapRepeat) showSideHint(pressedSlot) }

    @VisibleForTesting fun testSideHintShowing() = sideSlot >= 0

    private fun showSideHint(slot: Int) {
        if (sideSlot == slot) return
        sideSlot = slot
        sideShownAt = SystemClock.uptimeMillis()
        releaseKeyPops() // the hint takes over from the key's pop-up
        invalidate()
    }

    private fun hideSideHint() {
        removeCallbacks(sideHintRunnable)
        if (sideSlot >= 0) {
            // a pulled key springs back to its spot
            val from = if (sideArmed) pullSpan(sideSlot) else pullT
            if (from > dp(1f)) { springSlot = sideSlot; springFrom = from; springAt = SystemClock.uptimeMillis() }
        }
        sideSlot = -1
        sideArmed = false
        pullT = 0f
        invalidate()
    }

    /** How far the held arrow is pulled out toward its socket, px from its spot. */
    private var pullT = 0f
    /** When the pulled key clicked into its socket. */
    private var snapAt = 0L
    /** A key springing back home after a pull: which, from how far, since when. */
    private var springSlot = -1
    private var springFrom = 0f
    private var springAt = 0L

    /** From [slot]'s spot to its socket, px. */
    private fun pullSpan(slot: Int): Float {
        val c = sideChipCenter(slot)
        return hypot(c.x - slotX(slot), c.y - slotY(slot)).coerceAtLeast(1f)
    }

    /** How far [i] is drawn out from its spot right now: pulled by the finger, clicked in, or springing back. */
    private fun pullOffset(i: Int, now: Long): Float {
        if (i == sideSlot && i == pressedSlot && mode == Mode.RING) return if (sideArmed) pullSpan(i) else pullT
        if (i == springSlot) {
            val t = (now - springAt) / 1000f
            if (t * 1000f >= SPRING_BACK_MS) { springSlot = -1; return 0f }
            postInvalidateOnAnimation()
            return springFrom * exp(-SPRING_BACK_DAMPING * t) * cos(SPRING_BACK_FREQ * t)
        }
        return 0f
    }

    private val chipCenter = PointF()

    /**
     * Where [slot]'s side icon goes: straight out from the ring past the key, or, when the screen
     * edge is in the way, swung around the ring to the nearest spot that fits.
     */
    private fun sideChipCenter(slot: Int): PointF {
        val keyDist = hypot(slotX(slot) - cx, slotY(slot) - cy)
        val reach = keyDist + 2 * slotRadius(slot) + dp(SOCKET_GAP_DP)
        val base = kotlin.math.atan2(slotY(slot) - cy, slotX(slot) - cx)
        val margin = slotRadius(slot) + dp(4f)
        val midX = safeArea.exactCenterX()
        val midY = safeArea.exactCenterY()
        var fallback: PointF? = null
        for (step in 0..4) {
            val candidates = if (step == 0) listOf(0f) else listOf(step * 20f, -step * 20f)
            val fits = candidates.map { deg ->
                val ang = base + Math.toRadians(deg.toDouble()).toFloat()
                PointF(cx + reach * kotlin.math.cos(ang), cy + reach * sin(ang))
            }.filter { p ->
                p.x >= safeArea.left + margin && p.x <= safeArea.right - margin &&
                    p.y >= safeArea.top + margin && p.y <= safeArea.bottom - margin
            }.minByOrNull { hypot(it.x - midX, it.y - midY) }
            if (fits != null) { chipCenter.set(fits); return chipCenter }
            if (fallback == null) fallback = PointF(cx + reach * kotlin.math.cos(base), cy + reach * sin(base))
        }
        val f = fallback!!
        chipCenter.set(
            f.x.coerceIn(safeArea.left + margin, safeArea.right - margin),
            f.y.coerceIn(safeArea.top + margin, safeArea.bottom - margin),
        )
        return chipCenter
    }

    /** Where the finger is on the way from [slot] to its side icon: (distance along that way, distance off it). */
    private fun towardChip(slot: Int, x: Float, y: Float): Pair<Float, Float> {
        val c = sideChipCenter(slot)
        val kx = slotX(slot)
        val ky = slotY(slot)
        val len = hypot(c.x - kx, c.y - ky).coerceAtLeast(1f)
        val ux = (c.x - kx) / len
        val uy = (c.y - ky) / len
        val fx = x - kx
        val fy = y - ky
        return Pair(fx * ux + fy * uy, abs(fx * uy - fy * ux))
    }

    private fun fireSide(action: SlotAction) {
        sideFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when (action) {
            SlotAction.UP -> setPinned(!pinned)
            SlotAction.RIGHT -> enterStick()
            SlotAction.DOWN -> { listener?.onOpenSettings(); closeRingNow() }
            SlotAction.LEFT -> closeRingNow()
            else -> Unit
        }
    }

    /** Pins the open ring (no countdown, and it comes back open) or unpins it; saved for next time. */
    fun setPinned(on: Boolean) {
        pinned = on
        Prefs.get(context).edit().putBoolean(Prefs.RING_PINNED, on).apply()
        pinChangedAt = SystemClock.uptimeMillis()
        scheduleIdle()
        invalidate()
    }
    private var pinChangedAt = 0L

    /** The left arrow's side function: fold up now, throwing out any dictation in progress. */
    private fun closeRingNow() {
        releaseKeyPops()
        hideSideHint()
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
        // follow the finger's speed, so a quick flick throws the bubble
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            velocity?.let {
                it.computeCurrentVelocity(1000)
                flingVx = it.getXVelocity(activePointerId).takeIf { v -> v.isFinite() } ?: 0f
                flingVy = it.getYVelocity(activePointerId).takeIf { v -> v.isFinite() } ?: 0f
            }
        }
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

    // ---------------------------------------------------------------- flinging the bubble

    private var velocity: VelocityTracker? = null
    private var flingVx = 0f
    private var flingVy = 0f
    private var flingAnimator: TimeAnimator? = null

    /**
     * One axis of a throw: it glides with friction until it meets the screen edge, then settles
     * against that edge with a small spring (a little bounce, never back into the way).
     */
    private inner class FlingAxis(var pos: Float, var v: Float, val min: Float, val max: Float) {
        private var edge = Float.NaN
        val settled get() =
            if (edge.isNaN()) abs(v) < dp(FLING_STOP_DP_S) else abs(pos - edge) < dp(0.5f) && abs(v) < dp(FLING_STOP_DP_S)

        fun step(dt: Float) {
            if (edge.isNaN()) {
                v *= exp(-FLING_FRICTION * dt)
                pos += v * dt
                if (pos < min || pos > max) {
                    // hit the edge: keep only a sliver of the speed, bounced back, for the spring
                    edge = if (pos < min) min else max
                    pos = edge
                    val rebound = (abs(v) * FLING_BOUNCE).coerceAtMost(dp(FLING_MAX_REBOUND_DP_S))
                    v = if (edge == min) rebound else -rebound
                }
            } else {
                // a damped spring pulls it back flush with the edge
                val x = pos - edge
                lastFlingBounce = max(lastFlingBounce, abs(x))
                v += (-FLING_SPRING_K * x - FLING_SPRING_C * v) * dt
                pos += v * dt
                if ((edge == min && pos < min) || (edge == max && pos > max)) { pos = edge; v = 0f }
            }
        }
    }

    /**
     * A drag that let go fast keeps going: the bubble (or the open ring) glides on, slowing down,
     * and comes to rest against the edge it was thrown toward. False when the drag was too slow.
     */
    private fun startFling(): Boolean {
        var vx = flingVx
        var vy = flingVy
        if (hypot(vx, vy) < dp(FLING_MIN_DP_S)) return false
        val maxV = dp(FLING_MAX_DP_S)
        val sp = hypot(vx, vy)
        if (sp > maxV) { vx *= maxV / sp; vy *= maxV / sp }
        updateSafeArea()
        val r = max(bubbleRadius, discRadius) + dp(4f)
        val ax = FlingAxis(cx, vx, safeArea.left + r, max(safeArea.left + r, safeArea.right - r))
        val ay = FlingAxis(cy, vy, safeArea.top + r, max(safeArea.top + r, safeArea.bottom - r))
        lastFlingBounce = 0f
        flingAnimator?.cancel()
        flingAnimator = TimeAnimator().apply {
            setTimeListener { anim, _, deltaMs ->
                var left = (deltaMs / 1000f).coerceAtMost(0.05f)
                while (left > 0f) { // small steps keep the spring steady
                    val dt = min(left, 0.004f)
                    ax.step(dt)
                    ay.step(dt)
                    left -= dt
                }
                cx = ax.pos
                cy = ay.pos
                invalidate()
                requestLayout() // the touchable region follows it
                if (ax.settled && ay.settled) {
                    anim.cancel()
                    flingAnimator = null
                    clampCenter()
                    savePosition()
                }
            }
            start()
        }
        return true
    }

    /** A new touch catches the gliding bubble where it is. */
    private fun stopFling() {
        val a = flingAnimator ?: return
        flingAnimator = null
        a.cancel()
        savePosition()
    }

    @VisibleForTesting internal val testFlinging get() = flingAnimator != null
    /** How far the last throw bounced back off an edge, in px. */
    @VisibleForTesting internal var lastFlingBounce = 0f

    private fun onDown(x: Float, y: Float): Boolean {
        stopFling()
        downX = x; downY = y; downCx = cx; downCy = cy
        gridPressed = hitPill(x, y)
        if (gridPressed >= 0) {
            downTarget = -4
            pressPill(gridPressed)
            invalidate()
            return true
        }
        if (gridOpen && mode == Mode.RING && gridRect.contains(x, y)) {
            downTarget = -4 // between pills: nothing, but don't fall through to the app
            return true
        }
        editPressed = hitEdit(x, y)
        if (editPressed >= 0) {
            downTarget = -3
            pauseIdle()
            invalidate()
            return true
        }
        dragging = false
        downTarget = -2
        if (mode == Mode.STICK) return stickDown(x, y)
        pauseIdle()
        centerLongPressed = false
        when (mode) {
            Mode.STICK -> Unit
            Mode.BUBBLE -> {
                if (hypot(x - cx, y - cy) > bubbleRadius + dp(8f)) return false
                downTarget = -1
                notchPhase = NOTCH_UNDECIDED
                notchDir = -1
                notchT = 0f
                notchSnapped = false
                notchStrain = 0f
                notchStrainStep = 0
                notchShownAt = SystemClock.uptimeMillis()
                notchSpringAt = 0L
                // a tap dictates; holding just keeps the notches up, for as long as you like
                notchDownAt = SystemClock.uptimeMillis()
                engagedAt = 0L
                buzzWeak()
                invalidate()
            }
            Mode.RING -> {
                if (hypot(x - cx, y - cy) <= centerRadius + dp(4f)) {
                    downTarget = -1
                    // a tap starts dictation, or finishes it
                    if (talking && talkLatched) stopLatchOnUp = true
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
                    sideFired = false
                    sideArmed = false
                    doubleTapRepeat = false
                    if (slot == lettersSlot && gridOpen) {
                        // abc is the grid's ✕ now: it closes on release
                    } else if (slot == lettersSlot) {
                        // abc opens the full keyboard on release
                    } else if (slot == spaceSlot || slot == enterSlot) {
                        postDelayed(holdRunnable, HOLD_MS)
                    } else if (hasSide(action)) {
                        if (slot == lastTapSlot && SystemClock.uptimeMillis() - lastTapUpAt <= DOUBLE_TAP_MS) {
                            // tap, then tap and hold: rapid fire
                            doubleTapRepeat = true
                            sendKey(action)
                            postDelayed(repeatRunnable, REPEAT_START_MS)
                        } else {
                            postDelayed(sideHintRunnable, SIDE_HINT_MS)
                        }
                    } else if (slot == deleteSlot && discardMode) {
                        // the discard key acts on release, so sliding off still backs out
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
        if (downTarget == STICK_KNOB) { stickMove(x, y); return }
        if (downTarget == STICK_EXIT || downTarget == STICK_KEYS) return
        if (downTarget == -4) {
            // on the space bar, a sideways slide moves the cursor a character per step, both ways
            if (gridPressed >= 0 && pills[gridPressed].ch == SPACE_CH) {
                if (!spaceSliding && abs(x - downX) > dp(SPACE_SLIDE_START_DP)) {
                    spaceSliding = true
                    spaceSlideX = downX
                    invalidate()
                }
                if (spaceSliding) {
                    val step = dp(SPACE_SLIDE_STEP_DP)
                    while (x - spaceSlideX >= step) { spaceSlideX += step; sendKey(SlotAction.RIGHT, repeat = true) }
                    while (spaceSlideX - x >= step) { spaceSlideX -= step; sendKey(SlotAction.LEFT, repeat = true) }
                    return
                }
            }
            if (commaMenu) {
                val hot = overCommaChip(x, y)
                if (hot != commaHot) {
                    commaHot = hot
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                return
            }
            // sliding corrects to the pill under the finger; it types on release, like a keyboard
            val i = hitPill(x, y)
            if (i != gridPressed) {
                removeCallbacks(commaHoldRunnable)
                removeCallbacks(gridRepeatRunnable) // backspace stops repeating once the finger leaves it
                gridPressed = i
                invalidate()
            }
            return
        }
        if (downTarget == -3) {
            // sliding off an edit button cancels it
            if (editPressed >= 0 && hypot(editCenters[editPressed].x - x, editCenters[editPressed].y - y) > dp(EDIT_DP / 2 + 10f)) {
                editPressed = -1
                invalidate()
            }
            return
        }
        when (mode) {
            Mode.STICK -> Unit
            Mode.BUBBLE, Mode.RING -> {
                if (downTarget != -1) {
                    if (pressedSlot >= 0 && !doubleTapRepeat && hasSide(slots[pressedSlot].action)) {
                        val slot = pressedSlot
                        val (along, across) = towardChip(slot, x, y)
                        val keyR = slotRadius(slot)
                        val chip = sideChipCenter(slot)
                        val onChip = hypot(x - chip.x, y - chip.y) < keyR + dp(10f)
                        val span = pullSpan(slot)
                        // a widening lane from the key toward its socket
                        val inLane = onChip || (along > -keyR && across < keyR * 1.6f + max(0f, along) * 0.4f)
                        if (inLane) {
                            if (along > keyR * 0.5f) showSideHint(slot)
                            if (sideSlot == slot) {
                                // the key comes out with the finger, and clicks into the socket most of the way there
                                pullT = along.coerceIn(0f, span)
                                val armed = onChip || along > span * (if (sideArmed) PULL_UNSNAP else PULL_SNAP)
                                if (armed != sideArmed) {
                                    sideArmed = armed
                                    if (armed) { snapAt = SystemClock.uptimeMillis(); buzzClick() }
                                    else performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                invalidate()
                            }
                            return
                        }
                    }
                    // sliding off a key cancels it, like a normal key
                    if (pressedSlot >= 0 && hitSlot(x, y) != pressedSlot) {
                        stopRepeat()
                        hideSideHint()
                        removeCallbacks(holdRunnable)
                                        releaseKeyPops()
                        pressedSlot = -1
                        invalidate()
                    }
                    return
                }
                // after the press locked in, the thumb resting there shouldn't drag the new ring around
                if (centerLongPressed && downTarget == -1) return
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
                if (mode == Mode.BUBBLE && downTarget == -1 && notchPhase != NOTCH_MOVING) {
                    if (notchMove(x - downX, y - downY)) return
                }
                if (!dragging && hypot(x - downX, y - downY) > touchSlop) {
                    dragging = true
                    endPress()
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
        if (downTarget == STICK_KNOB) { stickUp(); return }
        if (downTarget == STICK_KEYS) {
            downTarget = -2
            val c = stickKeysCenter()
            if (hypot(x - c.x, y - c.y) <= dp(STICK_EXIT_DP / 2 + 10f)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                stickToKeyboard()
            }
            invalidate()
            return
        }
        if (downTarget == STICK_EXIT) {
            downTarget = -2
            val c = stickExitCenter()
            if (hypot(x - c.x, y - c.y) <= dp(STICK_EXIT_DP / 2 + 10f)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                endStick(notify = true, fold = true)
            }
            invalidate()
            return
        }
        if (downTarget == -4) {
            removeCallbacks(gridRepeatRunnable)
            removeCallbacks(commaHoldRunnable)
            if (commaMenu) {
                // held comma: let go on the pop-up for the joystick; anywhere else, nothing
                val go = commaHot
                commaMenu = false
                commaHot = false
                gridPressed = -1
                downTarget = -2
                if (go) { buzzClick(); enterStick() }
                invalidate()
                return
            }
            val i = gridPressed
            gridPressed = -1
            downTarget = -2
            if (i >= 0 && !spaceSliding) tapPill(i)
            spaceSliding = false
            invalidate()
            return
        }
        if (downTarget == -3) {
            val i = editPressed
            editPressed = -1
            downTarget = -2
            if (i >= 0) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val action = editAction(i)
                listener?.onEdit(action)
            }
            scheduleIdle()
            invalidate()
            return
        }
        releaseKeyPops()
        removeCallbacks(holdRunnable)
        removeCallbacks(talkRunnable)
        removeCallbacks(sideHintRunnable)
        endPress()
        stopRepeat()
        if (mode == Mode.RING && sideArmed && pressedSlot >= 0) {
            val action = slots[pressedSlot].action
            hideSideHint()
            pressedSlot = -1
            fireSide(action)
            if (mode == Mode.RING) scheduleIdle()
            dragging = false
            return
        }
        hideSideHint()
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
            Mode.STICK -> Unit
            Mode.BUBBLE -> {
                if (notchPhase == NOTCH_SLIDING) {
                    notchRelease()
                } else if (dragging) {
                    // a drag (or a kick) just moves the bubble; it doesn't open it
                    if (!startFling()) savePosition()
                } else if (SystemClock.uptimeMillis() - notchDownAt > TAP_MAX_MS) {
                    // held, then let go without choosing a notch: the notches just go away
                    notchPhase = NOTCH_NONE
                    invalidate()
                } else if (!centerLongPressed) {
                    // a plain tap: the ring opens already listening (the left notch opens it quietly)
                    expandRing()
                    startLatchedTalk()
                    if (!talking) buzzWeak() // couldn't start (no key, no mic): the ring is open anyway
                }
            }
            Mode.RING -> {
                if (downTarget == -1) {
                    if (dragging) {
                        if (!startFling()) savePosition()
                    } else if (!centerLongPressed) {
                        startLatchedTalk()
                    }
                } else if (pressedSlot >= 0 && hitSlot(x, y) == pressedSlot) {
                    val action = slots[pressedSlot].action
                    when {
                        // a tap on abc opens a bloom that waits for one tap; space and enter just type
                        action == SlotAction.LETTERS && gridOpen -> closeGrid()
                        action == SlotAction.LETTERS -> { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); openGrid() }
                        pressedSlot == deleteSlot && discardMode -> {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            endTalk(cancel = true)
                        }
                        action == SlotAction.FOLD -> { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); closeRingNow() }
                        action == SlotAction.PASTE -> if (canPaste) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            listener?.onEdit(EditAction.PASTE)
                        }
                        action == SlotAction.STICK -> { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); enterStick() }
                        action == SlotAction.SETTINGS -> { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); listener?.onOpenSettings(); closeRingNow() }
                        hasSide(action) -> {
                            if (!doubleTapRepeat) sendKey(action)
                            lastTapSlot = pressedSlot
                            lastTapUpAt = SystemClock.uptimeMillis()
                        }
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
        endPress()
        if (notchPhase == NOTCH_SLIDING) { notchSpringFrom = if (notchSnapped) notchSnappedOff() else notchT; notchSpringDir = notchDir; notchSpringAt = SystemClock.uptimeMillis() }
        notchPhase = NOTCH_NONE
        if (downTarget == STICK_KNOB) { stickUp(cancelled = true); return }
        if (downTarget == STICK_EXIT || downTarget == STICK_KEYS) { downTarget = -2; invalidate(); return }
        if (downTarget == -4) { removeCallbacks(gridRepeatRunnable); removeCallbacks(commaHoldRunnable); commaMenu = false; gridPressed = -1; spaceSliding = false; downTarget = -2; invalidate(); return }
        if (downTarget == -3) { editPressed = -1; downTarget = -2; scheduleIdle(); invalidate(); return }
        removeCallbacks(talkRunnable)
        hideSideHint()
        if (talking && !talkLatched) endTalk(cancel = true)
        stopLatchOnUp = false
        releaseKeyPops()
        removeCallbacks(holdRunnable)
        stopRepeat()
        if (dragging) savePosition()
        dragging = false
        pressedSlot = -1
        if (mode == Mode.BLOOM) closeBloom()
        if (mode == Mode.RING) scheduleIdle()
        invalidate()
    }

    // ---------------------------------------------------------------- the bubble's notches

    /*
     * One press on the folded bubble: four notches surface around it. Slide toward one and the
     * bubble stretches after the thumb until it clicks in; let go there to do it: left opens the
     * ring, right the cursor joystick, up the full keyboard, down settings. Stay still instead and
     * the press builds to the click that starts recording. A slide between the notches' lanes (a
     * diagonal) moves the bubble.
     */
    private var notchPhase = NOTCH_NONE
    /** 0 left, 1 right, 2 up, 3 down; -1 none yet. */
    private var notchDir = -1
    private var notchT = 0f
    private var notchSnapped = false
    private var notchSnapAt = 0L
    private var notchShownAt = 0L
    private var notchSpringFrom = 0f
    /** When the finger came down on the folded bubble: a quick release is a tap. */
    private var notchDownAt = 0L
    /** Clicked in and pulling on past the notch: 0..1 toward breaking free, and the ticks felt so far. */
    private var notchStrain = 0f
    private var notchStrainStep = 0
    /** Where the clicked-in bubble sits: in the notch, leaning out a little as you pull against it. */
    private fun notchSnappedOff(): Float = notchReach(notchDir) + notchStrain * dp(NOTCH_BREAK_DP) * NOTCH_STRAIN_GIVE
    private var notchSpringDir = -1
    private var notchSpringAt = 0L
    private val notchUx = floatArrayOf(-1f, 1f, 0f, 0f)
    private val notchUy = floatArrayOf(0f, 0f, -1f, 1f)

    @VisibleForTesting internal val testNotchPhase get() = notchPhase
    @VisibleForTesting internal val testNotchStrain get() = notchStrain
    @VisibleForTesting internal fun testOpenRing() = expandRing()
    @VisibleForTesting internal fun testNotch(dir: Int): PointF = PointF(cx + notchUx[dir] * notchReach(dir), cy + notchUy[dir] * notchReach(dir))

    private val notchRadius get() = dp(NOTCH_DP / 2)

    /** How far the bubble slides to reach notch [dir]: the full way, or less where the screen edge is close. */
    private fun notchReach(dir: Int): Float {
        val full = bubbleRadius + dp(NOTCH_GAP_DP) + notchRadius
        val room = when (dir) {
            0 -> cx - safeArea.left
            1 -> safeArea.right - cx
            2 -> cy - safeArea.top
            else -> safeArea.bottom - cy
        } - max(notchRadius, bubbleRadius) - dp(4f) // the clicked-in bubble has to fit there too
        return max(dp(NOTCH_MIN_REACH_DP), min(full, room))
    }

    /** The notch whose lane ([NOTCH_LANE_DEG] either side of its direction) holds this movement, or -1. */
    private fun notchLane(dx: Float, dy: Float): Int {
        val ang = Math.toDegrees(atan2(dy, dx).toDouble())
        for (i in 0 until 4) {
            val a = Math.toDegrees(atan2(notchUy[i], notchUx[i]).toDouble())
            val diff = abs(((ang - a + 540.0) % 360.0) - 180.0)
            if (diff <= NOTCH_LANE_DEG) return i
        }
        return -1
    }

    /** The finger moved on the folded bubble. True when the notches took the movement. */
    private fun notchMove(dx: Float, dy: Float): Boolean {
        if (notchPhase == NOTCH_UNDECIDED) {
            if (hypot(dx, dy) <= dp(NOTCH_DECIDE_DP)) return true // still deciding: neither a slide nor a drag yet
            endPress()
            removeCallbacks(talkRunnable)
            val lane = notchLane(dx, dy)
            if (lane < 0) {
                notchPhase = NOTCH_MOVING // a diagonal: move the bubble
                invalidate()
                return false
            }
            notchPhase = NOTCH_SLIDING
            notchDir = lane
        }
        if (notchPhase != NOTCH_SLIDING) return false
        val past = hypot(dx, dy) - notchReach(notchDir)
        if (past > dp(NOTCH_BREAK_DP)) {
            // dragged on past the notch: the bubble tears free of it and moves with the finger
            notchPhase = NOTCH_MOVING
            notchSnapped = false
            notchStrain = 0f
            notchStrainStep = 0
            buzzRelease()
            notchDir = -1
            notchT = 0f
            dragging = true
            invalidate()
            return false
        }
        val lane = notchLane(dx, dy)
        if (lane >= 0 && lane != notchDir) { notchDir = lane; notchSnapped = false }
        val reach = notchReach(notchDir)
        notchT = (dx * notchUx[notchDir] + dy * notchUy[notchDir]).coerceIn(0f, reach)
        val snapped = notchT > reach * (if (notchSnapped) PULL_UNSNAP else PULL_SNAP) && !(notchDir == 0 && !canPaste)
        if (snapped != notchSnapped) {
            notchSnapped = snapped
            if (snapped) { notchSnapAt = SystemClock.uptimeMillis(); buzzClick() }
            else performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        // clicked in and still pulling: ticks that come closer and harder toward the break
        notchStrain = if (notchSnapped) (past / dp(NOTCH_BREAK_DP)).coerceIn(0f, 1f) else 0f
        val step = (notchStrain * NOTCH_STRAIN_TICKS).toInt()
        if (step > notchStrainStep) buzzStrain(step / NOTCH_STRAIN_TICKS.toFloat())
        notchStrainStep = step
        invalidate()
        return true
    }

    /** Let go after a slide: do the notch's action if it clicked in, else spring back. */
    private fun notchRelease() {
        val dir = notchDir
        val fire = notchSnapped
        notchSpringFrom = if (fire) notchReach(dir) else notchT
        notchSpringDir = dir
        notchSpringAt = SystemClock.uptimeMillis()
        notchPhase = NOTCH_NONE
        notchSnapped = false
        notchStrain = 0f
        notchStrainStep = 0
        dragging = false
        if (!fire) { invalidate(); return }
        when (dir) {
            0 -> listener?.onEdit(EditAction.PASTE)
            1 -> { expandRing(); enterStick() }
            2 -> { expandRing(); openGrid() }
            else -> listener?.onOpenSettings()
        }
        invalidate()
    }

    /** Where the folded bubble is drawn along its slide: following the thumb, clicked in, or springing back. */
    private fun notchOffset(now: Long): Float {
        if (notchPhase == NOTCH_SLIDING && notchDir >= 0) return if (notchSnapped) notchSnappedOff() else notchT
        if (notchSpringAt != 0L && mode == Mode.BUBBLE) {
            val t = (now - notchSpringAt) / 1000f
            if (t * 1000f >= SPRING_BACK_MS) { notchSpringAt = 0L; return 0f }
            postInvalidateOnAnimation()
            return notchSpringFrom * exp(-SPRING_BACK_DAMPING * t) * cos(SPRING_BACK_FREQ * t)
        }
        return 0f
    }

    private val notchGlyphPath = Path()

    /** The notches around the pressed bubble, the stretch toward the chosen one, and (clicked in) its icon. */
    private fun drawNotches(canvas: Canvas, now: Long) {
        val pressing = mode == Mode.BUBBLE && downTarget == -1 && (notchPhase == NOTCH_UNDECIDED || notchPhase == NOTCH_SLIDING) && !talking
        if (!pressing) return
        val pal = palette
        val fade = ((now - notchShownAt) / NOTCH_FADE_MS).coerceIn(0f, 1f)
        if (fade < 1f) postInvalidateOnAnimation()
        val r = notchRadius
        for (i in 0 until 4) {
            val on = notchPhase == NOTCH_SLIDING && notchDir == i
            if (on && notchSnapped) continue
            val reach = notchReach(i)
            val x = cx + notchUx[i] * reach
            val y = cy + notchUy[i] * reach
            // a backing so the icons read over any page, then the accent tint
            fill.color = withAlpha(pal.bloomKey, 0.88f * fade)
            canvas.drawCircle(x, y, r, fill)
            fill.color = withAlpha(pal.pressed, (if (on) 0.16f else 0.06f) * fade)
            canvas.drawCircle(x, y, r, fill)
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
            stroke.color = withAlpha(pal.pressed, (if (on) 0.7f else 0.35f) * fade)
            stroke.strokeWidth = dp(1.5f)
            canvas.drawCircle(x, y, r - dp(0.75f), stroke)
            stroke.pathEffect = null
            val off = i == 0 && !canPaste
            drawNotchGlyph(canvas, i, x, y, r * 0.95f, withAlpha(pal.pressed, (if (on) 1f else 0.6f) * fade * (if (off) DISABLED_ALPHA else 1f)))
        }
        if (notchPhase == NOTCH_SLIDING && notchDir >= 0) {
            val off = if (notchSnapped) notchSnappedOff() else notchT
            if (off > dp(2f)) {
                val span = notchReach(notchDir)
                val th = 2 * bubbleRadius * (1f - 0.55f * (off / span).coerceIn(0f, 1f))
                canvas.save()
                canvas.rotate(Math.toDegrees(atan2(notchUy[notchDir], notchUx[notchDir]).toDouble()).toFloat(), cx, cy)
                chipRect.set(cx, cy - th / 2, cx + off, cy + th / 2)
                fill.color = withAlpha(pal.pressed, if (notchSnapped) 0.2f else 0.28f)
                canvas.drawRoundRect(chipRect, th / 2, th / 2, fill)
                canvas.restore()
            }
        }
    }

    /** Over the clicked-in bubble: solid, with the notch's icon. */
    private fun drawNotchSnapped(canvas: Canvas, now: Long) {
        if (notchPhase != NOTCH_SLIDING || !notchSnapped || notchDir < 0) return
        val pal = palette
        val off = notchSnappedOff()
        val x = cx + notchUx[notchDir] * off
        val y = cy + notchUy[notchDir] * off
        val t = ((now - notchSnapAt) / SNAP_POP_MS).coerceIn(0f, 1f)
        val rr = bubbleRadius * (1f + 0.06f * snapPop.getInterpolation(t))
        if (t < 1f) postInvalidateOnAnimation()
        drawSoftShadow(canvas, x, y, rr, alpha = 0.28f, dy = dp(3f), sigma = dp(4f))
        // pulling against the notch: the bubble leans out and stretches toward the finger
        val s2 = notchStrain * notchStrain
        canvas.save()
        canvas.rotate(Math.toDegrees(atan2(notchUy[notchDir], notchUx[notchDir]).toDouble()).toFloat(), x, y)
        canvas.scale(1f + 0.16f * s2, 1f - 0.1f * s2, x, y)
        fill.color = pal.pressed
        canvas.drawCircle(x, y, rr, fill)
        canvas.restore()
        drawNotchGlyph(canvas, notchDir, x, y, rr * 0.95f, pal.onPressed)
        val f = ((now - notchSnapAt) / SNAP_FLASH_MS).coerceIn(0f, 1f)
        if (f < 1f) {
            stroke.color = withAlpha(pal.pressed, 0.5f * (1f - f))
            stroke.strokeWidth = dp(2f)
            canvas.drawCircle(x, y, rr * (1.05f + 0.3f * f), stroke)
        }
    }

    /** The notches' icons: the ring, the joystick's I-beam, the keyboard, the gear. */
    private fun drawNotchGlyph(canvas: Canvas, dir: Int, x: Float, y: Float, size: Float, c: Int) {
        when (dir) {
            0 -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.PASTE, x, y, size * 0.85f, fill) }
            1 -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.TEXT_CURSOR, x, y, size * 0.85f, fill) }
            2 -> {
                // a keyboard: an outlined body, two rows of keys and a space bar
                val w = size * 0.42f
                val h = size * 0.28f
                stroke.color = c
                stroke.strokeWidth = size * 0.08f
                chipRect.set(x - w, y - h, x + w, y + h)
                canvas.drawRoundRect(chipRect, size * 0.08f, size * 0.08f, stroke)
                fill.color = c
                val k = size * 0.055f
                for (row in 0 until 2) for (col in 0 until 4) {
                    canvas.drawCircle(x - w * 0.6f + col * w * 0.4f, y - h * 0.45f + row * h * 0.5f, k, fill)
                }
                canvas.drawLine(x - w * 0.4f, y + h * 0.5f, x + w * 0.4f, y + h * 0.5f, stroke)
            }
            else -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.SETTINGS, x, y, size * 0.8f, fill) }
        }
    }

    // ---------------------------------------------------------------- the cursor joystick

    /** When the joystick came up or went away, for the crossfade; [stickOn] which way. */
    private var stickChangedAt = 0L
    private var stickOn = false
    private fun stickProgress(): Float {
        val t = ((SystemClock.uptimeMillis() - stickChangedAt) / STICK_FADE_MS).coerceIn(0f, 1f)
        return if (stickOn) t else if (stickChangedAt == 0L) 0f else 1f - t
    }

    /** Where the knob is pushed to, from the middle, px. */
    private var knobX = 0f
    private var knobY = 0f
    private var stickGrabX = 0f
    private var stickGrabY = 0f
    private var stickMoved = false
    /** Holding the knob still armed a selection: pushing now selects. */
    var stickSelecting = false
        private set
    private var stickAccX = 0f
    private var stickAccY = 0f
    private var stickFrameAt = 0L
    /** The knob springing back to the middle after a push: from where, since when. */
    private var knobSpringX = 0f
    private var knobSpringY = 0f
    private var knobSpringAt = 0L
    private var stickPressed = false

    private val stickMaxOffset get() = discRadius - knobRadius - dp(8f)
    private val knobRadius get() = dp(KNOB_DP / 2) * ringScale

    /** Turns the open ring into the joystick. */
    private fun enterStick() {
        if (mode != Mode.RING) return
        if (talking) endTalk(cancel = false)
        closeGrid()
        hideSideHint()
        pauseIdle()
        mode = Mode.STICK
        stickOn = true
        stickChangedAt = SystemClock.uptimeMillis()
        knobX = 0f; knobY = 0f
        stickSelecting = false
        buzzWeakThenStrong()
        listener?.onStickStart()
        announceForAccessibility(context.getString(R.string.stick_on))
        invalidate()
        requestLayout()
    }

    /** Leaves the joystick: back to the folded bubble ([fold]), or just out of it (a reset). */
    private fun endStick(notify: Boolean, fold: Boolean) {
        if (mode != Mode.STICK) return
        removeCallbacks(stickHoldRunnable)
        stickFrame.cancelled = true
        removeCallbacks(stickFrame)
        stickOn = false
        stickChangedAt = SystemClock.uptimeMillis()
        stickSelecting = false
        stickPressed = false
        if (notify) listener?.onStickEnd()
        if (fold) {
            mode = Mode.RING // collapse from the open ring's look
            collapseToBubble()
        } else {
            mode = Mode.BUBBLE
            stickChangedAt = 0L
        }
        invalidate()
        requestLayout()
    }

    @VisibleForTesting internal fun testEnterStick() { if (mode == Mode.BUBBLE) { expandRing() }; enterStick() }
    @VisibleForTesting internal fun testStickExit(): PointF = PointF().apply { set(stickExitCenter()) }
    @VisibleForTesting internal fun testKnob(): PointF = PointF(cx + knobX, cy + knobY)

    private val stickHoldRunnable = Runnable {
        if (mode != Mode.STICK || stickMoved) return@Runnable
        stickSelecting = true
        buzzClick()
        listener?.onStickSelect()
        invalidate()
    }

    private fun stickDown(x: Float, y: Float): Boolean {
        val c = stickExitCenter()
        if (hypot(x - c.x, y - c.y) <= dp(STICK_EXIT_DP / 2 + 8f)) {
            downTarget = STICK_EXIT
            invalidate()
            return true
        }
        val k = stickKeysCenter()
        if (hypot(x - k.x, y - k.y) <= dp(STICK_EXIT_DP / 2 + 8f)) {
            downTarget = STICK_KEYS
            invalidate()
            return true
        }
        if (hypot(x - cx, y - cy) > discRadius + dp(4f)) return false
        // anywhere on the base grabs the knob; it moves by how far the finger goes from here
        downTarget = STICK_KNOB
        stickGrabX = x; stickGrabY = y
        stickMoved = false
        stickSelecting = false
        stickPressed = true
        knobSpringAt = 0L
        knobX = 0f; knobY = 0f
        buzzWeak()
        postDelayed(stickHoldRunnable, STICK_HOLD_MS)
        invalidate()
        return true
    }

    private fun stickMove(x: Float, y: Float) {
        var ox = x - stickGrabX
        var oy = y - stickGrabY
        val m = hypot(ox, oy)
        if (!stickMoved) {
            if (m < dp(STICK_SLOP_DP)) return
            stickMoved = true
            removeCallbacks(stickHoldRunnable)
            stickAccX = 0f; stickAccY = 0f
            stickFrameAt = SystemClock.uptimeMillis()
            stickFrame.cancelled = false
            postDelayed(stickFrame, STICK_FRAME_MS)
        }
        val max = stickMaxOffset
        if (m > max) { ox *= max / m; oy *= max / m }
        knobX = ox
        knobY = oy
        invalidate()
    }

    private fun stickUp(cancelled: Boolean = false) {
        downTarget = -2
        removeCallbacks(stickHoldRunnable)
        stickFrame.cancelled = true
        removeCallbacks(stickFrame)
        stickPressed = false
        if (!cancelled && !stickMoved && !stickSelecting) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onStickTap()
        }
        stickSelecting = false
        // the knob springs back to the middle
        knobSpringX = knobX; knobSpringY = knobY
        knobSpringAt = SystemClock.uptimeMillis()
        knobX = 0f; knobY = 0f
        invalidate()
    }

    /**
     * Each frame the knob is out: turn how far it's pushed into cursor steps. A dead zone in the
     * middle, then a crawl, curving up to the top speed at the rim; a push that's mostly sideways
     * stays on its line, and one that's mostly up or down keeps to whole lines.
     */
    private val stickFrame = object : Runnable {
        var cancelled = true
        override fun run() {
            if (cancelled || mode != Mode.STICK) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - stickFrameAt) / 1000f).coerceIn(0f, 0.05f)
            stickFrameAt = now
            val max = stickMaxOffset
            val dx = knobX / max
            val dy = knobY / max
            val adx = abs(dx)
            val ady = abs(dy)
            var cols = 0
            var lines = 0
            if (adx >= ady * STICK_AXIS_BIAS) {
                stickAccX += Math.signum(dx) * stickRate(adx, STICK_MIN_CPS, STICK_MAX_CPS) * dt
                while (stickAccX >= 1f) { cols++; stickAccX -= 1f }
                while (stickAccX <= -1f) { cols--; stickAccX += 1f }
            } else stickAccX = 0f
            if (ady >= adx * STICK_AXIS_BIAS) {
                stickAccY += Math.signum(dy) * stickRate(ady, STICK_MIN_LPS, STICK_MAX_LPS) * dt
                while (stickAccY >= 1f) { lines++; stickAccY -= 1f }
                while (stickAccY <= -1f) { lines--; stickAccY += 1f }
            } else stickAccY = 0f
            if (cols != 0 || lines != 0) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                listener?.onStickStep(cols, lines, stickSelecting)
            }
            postDelayed(this, STICK_FRAME_MS)
        }
    }

    private fun stickRate(d: Float, lo: Float, hi: Float): Float {
        if (d <= STICK_DEAD) return 0f
        val u = (d - STICK_DEAD) / (1f - STICK_DEAD)
        return lo + (hi - lo) * Math.pow(u.toDouble(), 2.2).toFloat()
    }

    /** For tests: run the stick for [ms] with the knob where it is now. */
    @VisibleForTesting internal fun testStickRun(ms: Long) {
        var t = 0L
        while (t < ms) { stickFrameAt = SystemClock.uptimeMillis() - 16; stickFrame.run(); t += 16 }
    }

    private val stickExit = PointF()
    private val stickKeys = PointF()

    /** The keyboard button: the ✕'s mirror image on the rim's lower corner on the same side. */
    private fun stickKeysCenter(): PointF {
        val e = stickExitCenter()
        val half = dp(STICK_EXIT_DP / 2 + 4f)
        val y = cy + (cy - e.y)
        if (y + half <= safeArea.bottom && y - half >= safeArea.top) { stickKeys.set(e.x, y); return stickKeys }
        // no room below: the other lower corner, or tucked beside the ✕ on the rim
        val x2 = cx - (e.x - cx)
        if (x2 - half >= safeArea.left && x2 + half <= safeArea.right) { stickKeys.set(x2, e.y); return stickKeys }
        stickKeys.set(e.x, y.coerceIn(safeArea.top + half, safeArea.bottom - half))
        return stickKeys
    }

    /** From the joystick straight to the full keyboard. */
    private fun stickToKeyboard() {
        endStick(notify = true, fold = false)
        expandRing()
        openGrid()
    }
    @VisibleForTesting internal fun testStickKeys(): PointF = PointF().apply { set(stickKeysCenter()) }

    /** The ✕ sits on the base's rim at a top corner, away from the edit buttons, wherever it fits. */
    private fun stickExitCenter(): PointF {
        val d = discRadius + dp(2f)
        val half = dp(STICK_EXIT_DP / 2 + 4f)
        if (editVisible) layoutEditButtons()
        val prefer = if (editVisible && editSide == 0) listOf(-135.0, -45.0, 135.0, 45.0) else listOf(-45.0, -135.0, 45.0, 135.0)
        for (deg in prefer) {
            val a = Math.toRadians(deg)
            val x = cx + d * cos(a).toFloat()
            val y = cy + d * sin(a).toFloat()
            if (x - half >= safeArea.left && x + half <= safeArea.right && y - half >= safeArea.top && y + half <= safeArea.bottom) {
                stickExit.set(x, y); return stickExit
            }
        }
        val a = Math.toRadians(prefer[0])
        stickExit.set(
            (cx + d * cos(a).toFloat()).coerceIn(safeArea.left + half, safeArea.right - half),
            (cy + d * sin(a).toFloat()).coerceIn(safeArea.top + half, safeArea.bottom - half),
        )
        return stickExit
    }

    private val stickIcon = Path()

    /**
     * The joystick, drawn like an on-screen game stick in the keyboard's own colors: a recessed
     * base (the disc, shaded toward its rim) with a dashed travel ring, a small dead-zone ring and
     * four rim chevrons; a faint shaft and halo when pushed; and the raised knob with a grip ring
     * and an I-beam (a select glyph once selecting). [p] fades it in and out.
     */
    private fun drawStick(canvas: Canvas, p: Float) {
        val pal = palette
        val now = SystemClock.uptimeMillis()
        val r = discRadius * (0.85f + 0.15f * p)
        val sel = stickSelecting
        val accent = pal.pressed
        // base
        drawSoftShadow(canvas, cx, cy, r, alpha = 0.16f * p, dy = dp(1f), sigma = dp(2f), clipOutShape = true)
        fill.color = withAlpha(if (sel) ColorUtils.blendARGB(pal.disc, accent, 0.10f) else pal.disc, p * 0.96f)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, shadowBlack(0.10f * p)), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
        val line = withAlpha(if (sel) accent else pal.onCardinal, (if (sel) 0.4f else 0.26f) * p)
        stroke.color = line
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(5f)), 0f)
        canvas.drawCircle(cx, cy, stickMaxOffset * (0.85f + 0.15f * p), stroke)
        stroke.pathEffect = null
        canvas.drawCircle(cx, cy, knobRadius + stickMaxOffset * STICK_DEAD, stroke)
        stroke.color = withAlpha(if (sel) accent else pal.onCardinal, (if (sel) 0.6f else 0.4f) * p)
        stroke.strokeWidth = dp(2.5f)
        val cr = r - dp(12f)
        val cw = dp(6f)
        for (k in 0 until 4) {
            val a = (k * 90.0 - 90.0) * PI / 180
            val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
            val tx = cx + ux * cr; val ty = cy + uy * cr
            // a chevron pointing outward
            stickIcon.reset()
            stickIcon.moveTo(tx - ux * cw * 0.6f - uy * cw, ty - uy * cw * 0.6f + ux * cw)
            stickIcon.lineTo(tx + ux * cw * 0.4f, ty + uy * cw * 0.4f)
            stickIcon.lineTo(tx - ux * cw * 0.6f + uy * cw, ty - uy * cw * 0.6f - ux * cw)
            canvas.drawPath(stickIcon, stroke)
        }
        // the knob: pushed, or springing back
        var kx = knobX
        var ky = knobY
        if (!stickPressed && knobSpringAt != 0L) {
            val t = (now - knobSpringAt) / 1000f
            if (t * 1000f < KNOB_SPRING_MS) {
                val f = exp(-KNOB_SPRING_DAMPING * t) * cos(KNOB_SPRING_FREQ * t)
                kx = knobSpringX * f; ky = knobSpringY * f
                postInvalidateOnAnimation()
            } else knobSpringAt = 0L
        }
        val pushed = hypot(kx, ky)
        if (pushed > dp(2f)) {
            fill.color = withAlpha(if (sel) accent else pal.onCardinal, 0.06f * p)
            canvas.drawCircle(cx, cy, pushed + knobRadius, fill)
            stroke.color = withAlpha(if (sel) accent else pal.onCardinal, 0.16f * p)
            stroke.strokeWidth = dp(7f)
            canvas.drawLine(cx, cy, cx + kx, cy + ky, stroke)
        }
        val kr = knobRadius * (if (stickPressed) 1.06f else 1f) * (0.8f + 0.2f * p)
        val x = cx + kx
        val y = cy + ky
        drawSoftShadow(canvas, x, y, kr, alpha = (if (stickPressed) 0.32f else 0.26f) * p, dy = dp(if (stickPressed) 5f else 3f), sigma = dp(if (stickPressed) 6f else 4f))
        val kBg = if (sel) accent else pal.center
        val kFg = if (sel) pal.onPressed else pal.onCenter
        fill.color = withAlpha(kBg, p)
        canvas.drawCircle(x, y, kr, fill)
        stroke.color = withAlpha(kFg, 0.16f * p)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(x, y, kr - dp(5f), stroke)
        // the glyph: an I-beam, or lines with a caret for selecting
        val u = kr * 0.9f / 12f
        fun X(v: Float) = x + (v - 12f) * u
        fun Y(v: Float) = y + (v - 12f) * u
        stickIcon.reset()
        if (sel) {
            stickIcon.moveTo(X(5f), Y(6f)); stickIcon.lineTo(X(19f), Y(6f))
            stickIcon.moveTo(X(5f), Y(12f)); stickIcon.lineTo(X(14f), Y(12f))
            stickIcon.moveTo(X(5f), Y(18f)); stickIcon.lineTo(X(11f), Y(18f))
            stickIcon.moveTo(X(16f), Y(15f)); stickIcon.lineTo(X(19f), Y(18f)); stickIcon.lineTo(X(16f), Y(21f))
        } else {
            stickIcon.moveTo(X(9f), Y(4f)); stickIcon.lineTo(X(15f), Y(4f))
            stickIcon.moveTo(X(9f), Y(20f)); stickIcon.lineTo(X(15f), Y(20f))
            stickIcon.moveTo(X(12f), Y(4f)); stickIcon.lineTo(X(12f), Y(20f))
        }
        stroke.color = withAlpha(kFg, p)
        stroke.strokeWidth = 2.1f * u
        canvas.drawPath(stickIcon, stroke)
        // the ✕ that leaves
        if (stickOn) {
            val c = stickExitCenter()
            val er = dp(STICK_EXIT_DP / 2) * (0.6f + 0.4f * p)
            val down = downTarget == STICK_EXIT
            drawSoftShadow(canvas, c.x, c.y, er, alpha = 0.26f * p, dy = dp(1.5f), sigma = dp(2.5f))
            fill.color = withAlpha(if (down) accent else pal.onBloomKey, p)
            canvas.drawCircle(c.x, c.y, er, fill)
            fill.color = withAlpha(if (down) pal.onPressed else pal.bloomKey, p)
            Icons.draw(canvas, Icons.Glyph.DISCARD, c.x, c.y, er * 1.1f, fill)
            // and its mirror: straight to the keyboard
            val k = stickKeysCenter()
            val kDown = downTarget == STICK_KEYS
            drawSoftShadow(canvas, k.x, k.y, er, alpha = 0.26f * p, dy = dp(1.5f), sigma = dp(2.5f))
            fill.color = withAlpha(if (kDown) accent else pal.onBloomKey, p)
            canvas.drawCircle(k.x, k.y, er, fill)
            drawNotchGlyph(canvas, 2, k.x, k.y, er * 1.05f, withAlpha(if (kDown) pal.onPressed else pal.bloomKey, p))
        }
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
        val frameNow = SystemClock.uptimeMillis()
        drawNotches(canvas, frameNow)
        val notchOff = if (mode == Mode.BUBBLE) notchOffset(frameNow) else 0f
        val notchD = if (notchPhase == NOTCH_SLIDING) notchDir else notchSpringDir
        val shifted = notchOff != 0f && notchD >= 0
        if (shifted) { canvas.save(); canvas.translate(notchUx[notchD] * notchOff, notchUy[notchD] * notchOff) }
        val stickP = stickProgress()
        if (stickP >= 1f) {
            drawStick(canvas, 1f)
        } else if (stickP > 0f) {
            // the ring's keys fade out as the stick's base and knob come in
            val r = max(bubbleRadius, discRadius) + dp(28f)
            val saved = canvas.saveLayerAlpha(cx - r, cy - r, cx + r, cy + r, (255 * (1f - stickP)).toInt())
            drawPad(canvas)
            canvas.restoreToCount(saved)
            drawStick(canvas, stickP)
            postInvalidateOnAnimation()
        } else if (opacity < 0.995f) {
            val r = max(bubbleRadius, discRadius) + dp(28f)
            val saved = canvas.saveLayerAlpha(cx - r, cy - r, cx + r, cy + r, (255 * opacity).toInt())
            drawPad(canvas)
            canvas.restoreToCount(saved)
        } else if (!(notchPhase == NOTCH_SLIDING && notchSnapped)) {
            drawPad(canvas)
        }
        if (shifted) canvas.restore()
        drawNotchSnapped(canvas, frameNow)
        drawGrid(canvas)
        drawEditButtons(canvas)
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
        drawVoiceThrob(canvas)

        // slots
        val frameNow = SystemClock.uptimeMillis()
        slots.indices.forEach { i ->
            val p = ringProgress(i)
            if (p <= 0.01f) return@forEach
            val slot = slots[i]
            val isLetters = i == lettersSlot
            val isPivot = mode == Mode.BLOOM && i == bloomSlot
            val alpha = p.coerceAtMost(1f) * (if (isPivot) 1f else dim)
            var x = slotX(i, p)
            var y = slotY(i, p)
            var r = slotRadius(i) * (0.3f + 0.7f * p) * (if (isPivot) 1.12f else 1f)
            val pulling = i == sideSlot && i == pressedSlot && mode == Mode.RING
            val snapped = pulling && sideArmed
            val off = pullOffset(i, frameNow)
            if (mode == Mode.RING && !pulling && p >= 1f) {
                if (holdHints) drawHoldHint(canvas, i, alpha)
                else if (slot.cardinal && hasSide(slot.action)) drawRestTab(canvas, i, alpha)
            }
            if (off != 0f) {
                val c = sideChipCenter(i)
                val hx = slotX(i)
                val hy = slotY(i)
                val span = hypot(c.x - hx, c.y - hy).coerceAtLeast(1f)
                val ux = (c.x - hx) / span
                val uy = (c.y - hy) / span
                if (pulling) drawPullBridge(canvas, hx, hy, ux, uy, off, span, r)
                x += ux * off
                y += uy * off
            }
            if (snapped) {
                val t = ((frameNow - snapAt) / SNAP_POP_MS).coerceIn(0f, 1f)
                r *= 1f + 0.06f * snapPop.getInterpolation(t)
                if (t < 1f) postInvalidateOnAnimation()
            }
            val pressed = i == pressedSlot || (isLetters && capsLock && !gridOpen)
            val discard = i == deleteSlot && discardMode
            val bg = when {
                discard -> if (pressed) pal.onDiscard else pal.discard
                pulling && !snapped -> ColorUtils.blendARGB(pal.cardinal, pal.pressed, 0.35f)
                pressed -> pal.pressed
                slot.cardinal -> pal.cardinal
                else -> pal.diagonal
            }
            val fg = when {
                discard -> if (pressed) pal.discard else pal.onDiscard
                pulling && !snapped -> pal.onCardinal
                pressed -> pal.onPressed
                slot.cardinal -> pal.onCardinal
                else -> pal.onDiagonal
            }
            if (isPivot) {
                fill.color = withAlpha(if (isLetters && !pressed) pal.letters else pal.pressed, 0.4f)
                canvas.drawCircle(x, y, r + dp(6f), fill)
            }
            if (pulling) drawSoftShadow(canvas, x, y, r, alpha = 0.26f * alpha, dy = dp(3f), sigma = dp(4f))
            else drawSoftShadow(canvas, x, y, r, alpha = 0.12f * alpha, dy = dp(0.5f), sigma = dp(0.9f))
            fill.color = withAlpha(bg, alpha)
            canvas.drawCircle(x, y, r, fill)
            if (snapped) {
                fill.color = withAlpha(fg, alpha)
                Icons.draw(canvas, sideGlyph(slot.action)!!, x, y, r * 1.1f, fill)
                val t = ((frameNow - snapAt) / SNAP_FLASH_MS).coerceIn(0f, 1f)
                if (t < 1f) {
                    stroke.color = withAlpha(pal.pressed, 0.5f * (1f - t))
                    stroke.strokeWidth = dp(2f)
                    canvas.drawCircle(x, y, r * (1.05f + 0.3f * t), stroke)
                }
            } else {
                drawSlotIcon(canvas, slot.action, x, y, r, withAlpha(fg, alpha))
            }
        }

        // center / bubble
        val baseR = bubbleRadius + (centerRadius - bubbleRadius) * discP.coerceIn(0f, 1.2f)
        // press to talk: the bubble sinks as the press builds, then springs back up when it locks in
        val now = SystemClock.uptimeMillis()
        var sink = 0f
        var pressScale = 1f
        if (pressStartAt != 0L && mode == Mode.BUBBLE) {
            val p = ((now - pressStartAt) / TALK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
            sink = p * p * (3f - 2f * p) // ease in and out
            pressScale = 1f - PRESS_SINK * sink
            postInvalidateOnAnimation()
        } else if (engagedAt != 0L) {
            val t = (now - engagedAt) / LOCK_IN_SPRING_MS
            if (t < 1f) {
                pressScale = (1f - PRESS_SINK) + PRESS_SINK * lockInSpring.getInterpolation(t)
                postInvalidateOnAnimation()
            }
        }
        val centerR = baseR * pressScale
        if (pressStartAt != 0L && mode == Mode.BUBBLE) {
            // the press, seen past the thumb: a pulse swells out from the bubble and reaches the
            // ring's edge exactly at the click, so the ring opens right where it lands
            val p = ((now - pressStartAt) / TALK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
            val grow = 1f - (1f - p) * (1f - p) // fast at first, settling into the edge
            val r = baseR + (discRadius - baseR) * grow
            fill.color = withAlpha(pal.pressed, 0.10f + 0.14f * p)
            canvas.drawCircle(cx, cy, r, fill)
            stroke.strokeWidth = dp(2.5f)
            stroke.color = withAlpha(pal.pressed, 0.35f + 0.5f * p)
            canvas.drawCircle(cx, cy, r, stroke)
        }
        // the same tight shadow as the Wispr bubble, flattening as it's pressed in
        drawSoftShadow(canvas, cx, cy, centerR, alpha = 0.16f * dim * (1f - 0.6f * sink))
        val listening = voice == Voice.LISTENING || (talking && voice == Voice.OFF)
        if (listening && mode != Mode.BLOOM) {
            fill.color = pal.pressed
            canvas.drawCircle(cx, cy, centerR, fill)
            drawMicGlyph(canvas, cx, cy, centerR * 0.42f, if (talkCancelArmed) withAlpha(pal.onPressed, 0.5f) else pal.onPressed)
        } else {
            fill.color = withAlpha(ColorUtils.blendARGB(pal.center, pal.pressed, 0.25f * sink), 0.45f + 0.55f * dim)
            canvas.drawCircle(cx, cy, centerR, fill)
            drawKeyboardGlyph(canvas, cx, cy, centerR * 0.42f, withAlpha(pal.onCenter, 0.45f + 0.55f * dim))
        }
        if (engagedAt != 0L) {
            // the click, seen: the pulse locks onto the ring's edge with a flash, then overshoots a
            // touch and fades as the ring settles in under it
            val t = (now - engagedAt) / LOCK_IN_RIPPLE_MS
            if (t < 1f) {
                val e = decelerate.getInterpolation(t)
                val edge = discRadius * (1f + 0.1f * e)
                fill.color = withAlpha(pal.pressed, 0.24f * (1f - t) * (1f - t))
                canvas.drawCircle(cx, cy, discRadius, fill)
                stroke.strokeWidth = dp(3f) * (1f - 0.5f * t)
                stroke.color = withAlpha(pal.pressed, 0.9f * (1f - t))
                canvas.drawCircle(cx, cy, edge, stroke)
                postInvalidateOnAnimation()
            } else {
                engagedAt = 0L
            }
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
        drawPinBadge(canvas, centerR)
        drawKeyPops(canvas, SystemClock.uptimeMillis())
        drawSideHint(canvas)

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

    private val chipRect = RectF()

    /**
     * The side-function hint: a dashed socket the size of the key just beyond it, showing what it
     * does. It fades and grows in; once the key clicks into it, the key itself shows the icon.
     */
    private fun drawSideHint(canvas: Canvas) {
        val slot = sideSlot
        if (slot < 0 || mode != Mode.RING || sideArmed) return
        val glyph = sideGlyph(slots[slot].action) ?: return
        val pal = palette
        val t = ((SystemClock.uptimeMillis() - sideShownAt) / SIDE_POP_MS).coerceIn(0f, 1f)
        val scale = 0.6f + 0.4f * popOvershoot.getInterpolation(t)
        val c = sideChipCenter(slot)
        val r = slotRadius(slot) * scale
        fill.color = withAlpha(pal.pressed, 0.08f * t)
        canvas.drawCircle(c.x, c.y, r, fill)
        val d = dp(1f)
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
        stroke.color = withAlpha(pal.pressed, 0.55f * t)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(c.x, c.y, r - dp(0.75f), stroke)
        stroke.pathEffect = null
        fill.color = withAlpha(pal.pressed, 0.9f * t)
        Icons.draw(canvas, glyph, c.x, c.y, r * 1.0f, fill)
    }

    /** Whether something is mid-animation and needs another frame. */
    private fun popsAnimating(now: Long): Boolean {
        if (sideSlot >= 0 && now - sideShownAt < SIDE_POP_MS) return true
        if (outgoingKey != null) return true
        if (hovered != null && now - previewStart < PREVIEW_POP_MS) return true
        return keyPops.any { p ->
            now - p.start < KEY_POP_IN_MS || p.released >= 0 || (p.bump >= 0 && now - p.bump < KEY_POP_BUMP_MS)
        }
    }

    /** Icons are drawn on a 24-unit grid, centered on the key, like the mockups. */
    /** [asKey]: a grid pill's icon, always the plain key (never the discard ✕ or the grid's close ✕). */
    private fun drawSlotIcon(canvas: Canvas, action: SlotAction, x: Float, y: Float, r: Float, c: Int, asKey: Boolean = false) {
        if ((action == SlotAction.DELETE || action == SlotAction.FOLD || action == SlotAction.PASTE) && discardMode && !asKey) {
            fill.color = c
            Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, r * 1.15f, fill)
            return
        }
        if (action == SlotAction.LETTERS && gridOpen) {
            fill.color = c
            Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, r * 1.1f, fill)
            return
        }
        // the notches' icons, so the ring reads as the same four choices
        when (action) {
            SlotAction.FOLD -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.CLOSE, x, y, r * 1.1f, fill); return }
            SlotAction.PASTE -> { drawNotchGlyph(canvas, 0, x, y, r * 0.95f, if (canPaste) c else withAlpha(c, Color.alpha(c) / 255f * DISABLED_ALPHA)); return }
            SlotAction.STICK -> { drawNotchGlyph(canvas, 1, x, y, r * 0.95f, c); return }
            SlotAction.LETTERS -> if (!asKey) { drawNotchGlyph(canvas, 2, x, y, r * 0.95f, c); return }
            SlotAction.SETTINGS -> { drawNotchGlyph(canvas, 3, x, y, r * 0.95f, c); return }
            else -> Unit
        }
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
            else -> Unit
        }
        stroke.color = c
        stroke.strokeWidth = 2.1f * u
        canvas.drawPath(icon, stroke)
    }

    /**
     * The "Show hold hints" setting: a small, very faint glyph just outside the disc beyond each key,
     * saying what holding it does (the arrows' pull-out actions, the corners' blooms).
     */
    private fun drawHoldHint(canvas: Canvas, i: Int, alpha: Float) {
        val action = slots[i].action
        if ((action == SlotAction.DELETE || action == SlotAction.FOLD || action == SlotAction.PASTE) && discardMode) return
        if (action == SlotAction.LETTERS && gridOpen) return
        val ang = atan2(slotY(i) - cy, slotX(i) - cx)
        val d = discRadius + dp(HINT_GAP_DP)
        drawHintGlyph(canvas, action, cx + d * cos(ang), cy + d * sin(ang), dp(HINT_SIZE_DP), withAlpha(palette.pressed, HINT_ALPHA * alpha))
    }

    private fun drawHintGlyph(canvas: Canvas, action: SlotAction, x: Float, y: Float, size: Float, col: Int) {
        val glyph = when (action) {
            SlotAction.DELETE -> Icons.Glyph.REPEAT
            SlotAction.LETTERS -> Icons.Glyph.SWITCH_KEYBOARD
            else -> sideGlyph(action)
        }
        if (glyph != null) {
            fill.color = col
            Icons.draw(canvas, glyph, x, y, size, fill)
            return
        }
        val label = when (action) {
            SlotAction.ENTER -> "123"
            SlotAction.SPACE -> "?!"
            else -> return
        }
        secondText.color = col
        secondText.textSize = size * 0.75f
        canvas.drawText(label, x, y - (secondText.ascent() + secondText.descent()) / 2, secondText)
    }
    private val secondText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** At rest, a short faint tab just outside the disc beyond each arrow: there's something to pull out there. */
    private fun drawRestTab(canvas: Canvas, i: Int, alpha: Float) {
        val ang = atan2(slotY(i) - cy, slotX(i) - cx)
        val d = discRadius + dp(5f)
        val tx = cx + d * cos(ang)
        val ty = cy + d * sin(ang)
        canvas.save()
        canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f, tx, ty)
        chipRect.set(tx - dp(7f), ty - dp(1.5f), tx + dp(7f), ty + dp(1.5f))
        fill.color = withAlpha(palette.pressed, REST_TAB_ALPHA * alpha)
        canvas.drawRoundRect(chipRect, dp(1.5f), dp(1.5f), fill)
        canvas.restore()
    }

    /** The pulled key's spot, and the stretch from there to the key: it narrows as it's pulled further. */
    private fun drawPullBridge(canvas: Canvas, hx: Float, hy: Float, ux: Float, uy: Float, off: Float, span: Float, r: Float) {
        stroke.color = withAlpha(palette.onCardinal, 0.18f)
        stroke.strokeWidth = dp(1.5f)
        fill.color = withAlpha(palette.onCardinal, 0.06f)
        canvas.drawCircle(hx, hy, r, fill)
        canvas.drawCircle(hx, hy, r - dp(0.75f), stroke)
        if (off < dp(2f)) return
        val th = 2 * r * (1f - 0.55f * (off / span).coerceIn(0f, 1f))
        canvas.save()
        canvas.rotate(Math.toDegrees(atan2(uy, ux).toDouble()).toFloat(), hx, hy)
        chipRect.set(hx, hy - th / 2, hx + off, hy + th / 2)
        fill.color = withAlpha(palette.pressed, if (sideArmed) 0.2f else 0.28f)
        canvas.drawRoundRect(chipRect, th / 2, th / 2, fill)
        canvas.restore()
    }

    private val snapPop = OvershootInterpolator(3f)

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

        // live typing: the words are in the field, so the pill is just its label
        val labelOnly = voiceInField && (state == Voice.LISTENING || state == Voice.CLEANING) && !(talking && talkCancelArmed)
        val body = when {
            labelOnly -> ""
            talking && talkCancelArmed -> "Release to cancel"
            state == Voice.LISTENING && voiceText.isBlank() -> context.getString(R.string.voice_start_talking)
            else -> voiceText
        }
        val label = when {
            talking && talkCancelArmed -> null
            state == Voice.CLEANING -> context.getString(R.string.voice_polishing)
            state == Voice.LISTENING -> context.getString(R.string.voice_listening)
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
        val textH = if (labelOnly) 0f else layout.getLineBottom(layout.lineCount - 1) - textTop
        pillLabel.textSize = dp(12f)
        pillLabel.color = pal.pressed
        val labelH = if (label != null) dp(18f) else 0f
        val w = max(textW, if (label != null) pillLabel.measureText(label) else 0f) + 2 * pad
        val h = if (labelOnly) dp(32f) else textH + labelH + 2 * dp(10f)

        var left = (cx - w / 2).coerceIn(safeArea.left + dp(16f), max(safeArea.left + dp(16f), safeArea.right - dp(16f) - w))
        val shownR = if (mode == Mode.BUBBLE) bubbleRadius else max(bubbleRadius, discRadius * ringProgress(0).coerceIn(0f, 1f))
        var bottom = cy - shownR - dp(12f)
        if (gridOpen && mode == Mode.RING && gridAbove) bottom = min(bottom, gridRect.top - dp(10f))
        var top = bottom - h
        if (top < safeArea.top + dp(8f)) { // no room above: sit below the bubble
            top = cy + shownR + dp(12f)
            bottom = top + h
        }
        canvas.save()
        canvas.scale(scale, scale, cx, if (bottom < cy) bottom else top)
        pillRect.set(left, top, left + w, bottom)
        val radius = dp(18f)
        // a glow in the accent color sets the pill off from any page: it breathes with your voice
        // while listening and holds steady while the text is polished
        val glowing = !voiceInField && (state == Voice.LISTENING || state == Voice.CLEANING) && !(talking && talkCancelArmed)
        if (glowing) {
            val level = if (state == Voice.LISTENING) voiceLevel else 0.35f
            drawGlow(canvas, pillRect, radius, pal.pressed, extent = dp(8f + 12f * level), peak = (0.35f + 0.4f * level) * alpha)
        }
        pillPaint.color = withAlpha(pal.bloomKey, alpha)
        pillPaint.setShadowLayer(dp(3f), 0f, dp(1f), shadowBlack(0.18f * alpha))
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
        pillPaint.clearShadowLayer()
        stroke.color = if (glowing) withAlpha(pal.pressed, 0.7f * alpha) else withAlpha(pal.onBloomKey, 0.14f * alpha)
        stroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(pillRect, radius, radius, stroke)
        var y = top + if (labelOnly) dp(8f) else dp(10f)
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

    /** A small pin on the center's shoulder while the ring is pinned; it pops in and out. */
    private fun drawPinBadge(canvas: Canvas, centerR: Float) {
        if (mode == Mode.BUBBLE) return
        val since = SystemClock.uptimeMillis() - pinChangedAt
        val t = (since / PIN_POP_MS.toFloat()).coerceIn(0f, 1f)
        val s = if (pinned) pinPop.getInterpolation(t) else 1f - t
        if (s <= 0f) return
        val r = dp(9f) * s
        val a = Math.toRadians(-45.0)
        val bx = cx + (centerR + dp(1f)) * kotlin.math.cos(a).toFloat()
        val by = cy + (centerR + dp(1f)) * sin(a).toFloat()
        fill.color = palette.pressed
        canvas.drawCircle(bx, by, r, fill)
        fill.color = palette.onPressed
        Icons.draw(canvas, Icons.Glyph.PIN, bx, by, r * 1.25f, fill)
        if (t < 1f) postInvalidateOnAnimation()
    }
    private val pinPop = android.view.animation.OvershootInterpolator(2.2f)

    /**
     * The open ring's countdown: a thin arc around the center that drains clockwise from 12 o'clock
     * until the ring folds up. Redrawn about once per degree rather than every frame.
     */
    private fun drawCountdown(canvas: Canvas, centerR: Float) {
        val now = SystemClock.uptimeMillis()
        // hands free: the same spin-down shows the silence left before the dictation finishes itself
        val silence = talking && talkLatched && silenceDeadline != 0L && mode != Mode.BLOOM
        if (!silence && (mode != Mode.RING || idleDeadline == 0L || ringOpening && ringProgress(0) < 1f)) return
        val left = ((if (silence) silenceDeadline else idleDeadline) - now).coerceAtLeast(0L)
        val fraction = left.toFloat() / (if (silence) silenceMs else ringTimeoutMs)
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

    /**
     * The voice throb: a disc that swells from just past the button out to the full ring (or, for
     * the folded bubble, out past where a thumb covers it) with your normalized loudness. Drawn
     * under the keys so they stay readable.
     */
    private fun drawVoiceThrob(canvas: Canvas) {
        val listening = voice == Voice.LISTENING || (talking && voice == Voice.OFF)
        if (!listening || mode == Mode.BLOOM) return
        // quick to swell, slower to settle, so syllables read as pulses rather than flicker
        val k = if (voiceLevelTarget > voiceLevel) 0.5f else 0.18f
        voiceLevel += (voiceLevelTarget - voiceLevel) * k
        val discP = ringProgress(0).coerceIn(0f, 1f)
        val buttonR = bubbleRadius + (centerRadius - bubbleRadius) * discP
        val minR = buttonR + dp(5f)
        val maxR = max(discRadius, bubbleRadius + dp(THUMB_CLEAR_DP))
        val r = minR + (maxR - minR) * voiceLevel
        val fade = if (talkCancelArmed) 0.35f else 1f
        val pal = palette
        fill.color = withAlpha(pal.pressed, (0.14f + 0.16f * voiceLevel) * fade)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = withAlpha(pal.pressed, (0.5f + 0.4f * voiceLevel) * fade)
        stroke.strokeWidth = dp(2.5f)
        canvas.drawCircle(cx, cy, r, stroke)
    }

    private val glowRect = RectF()

    /** A soft gaussian glow around a rounded rect, built from thin rings fading outward (no blur API needed). */
    private fun drawGlow(canvas: Canvas, rect: RectF, radius: Float, color: Int, extent: Float, peak: Float) {
        val rings = 12
        val step = extent / rings
        stroke.strokeWidth = step + 0.5f
        for (i in 0 until rings) {
            val d = step * (i + 0.5f)
            val f = d / extent
            stroke.color = withAlpha(color, peak * exp(-3.2f * f * f) * (1f - f))
            glowRect.set(rect.left - d, rect.top - d, rect.right + d, rect.bottom + d)
            canvas.drawRoundRect(glowRect, radius + d, radius + d, stroke)
        }
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

    /** Press-to-talk locking in: one firm click. */
    private fun buzzClick() {
        val v = vibrator
        when {
            v == null || !v.hasVibrator() -> performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) ->
                vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f).compose())
            v.hasAmplitudeControl() -> vibrate(VibrationEffect.createOneShot(30, 255))
            else -> vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Pulling against a clicked-in notch: a tick that hardens as [level] (0..1) nears the break. */
    private fun buzzStrain(level: Float) {
        val v = vibrator
        when {
            v == null || !v.hasVibrator() -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_TICK) ->
                vibrate(VibrationEffect.startComposition()
                    .addPrimitive(if (level < 0.6f) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK, 0.25f + 0.75f * level)
                    .compose())
            v.hasAmplitudeControl() -> vibrate(VibrationEffect.createOneShot(10, (40 + 200 * level).toInt().coerceIn(1, 255)))
            else -> vibrate(VibrationEffect.createOneShot(6, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Tearing free of a notch: a quick falling snap. */
    private fun buzzRelease() {
        val v = vibrator
        when {
            v == null || !v.hasVibrator() -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL) ->
                vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.9f).compose())
            v.hasAmplitudeControl() -> vibrate(VibrationEffect.createWaveform(longArrayOf(0, 12, 8, 20), intArrayOf(0, 255, 0, 90), -1))
            else -> vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** A light tick: the touch on the folded bubble, and caps lock off. */
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
    @VisibleForTesting internal fun testVoiceLevelTarget() = voiceLevelTarget
    @VisibleForTesting internal fun testPressing() = pressStartAt != 0L
    @VisibleForTesting internal fun testLockedIn() = engagedAt != 0L

    /** Where the grid's pill for [ch] is ('⇧' for shift), or null when the grid is closed. */
    @VisibleForTesting internal fun testPill(ch: Char): PointF? {
        if (!gridOpen) return null
        layoutGrid()
        return pills.firstOrNull { it.ch == ch }?.rect?.let { PointF(it.centerX(), it.centerY()) }
    }

    /** Where the cut, copy and paste buttons are now, or empty when hidden. */
    @VisibleForTesting internal fun testEditButtons(): List<PointF> =
        if (!editVisible) emptyList() else { layoutEditButtons(); editCenters.take(editCount).map { PointF(it.x, it.y) } }

    @VisibleForTesting internal fun testSideChip(action: String): PointF =
        PointF().apply { set(sideChipCenter(slots.indexOfFirst { it.action.name == action })) }

    /** How far [action]'s key is drawn out from its spot (pulled, clicked in, or springing back), px. */
    @VisibleForTesting internal fun testPullOffset(action: String): Float =
        pullOffset(slots.indexOfFirst { it.action.name == action }, SystemClock.uptimeMillis())

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
        private val GRID_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val GRID_INDENTS = floatArrayOf(0f, 0.5f, 1.5f)
        private const val SHIFT = '\u21E7'
        private const val BACKSPACE = '\u232B'
        private const val ENTER = '\u21B5'
        private const val SPACE_CH = ' '
        private const val PILL_W_DP = 31f
        private const val PILL_H_DP = 42f
        private const val PILL_GAP_DP = 4f
        private const val PILL_ROW_GAP_DP = 6f
        private const val GRID_ROW_DP = 10 * (PILL_W_DP + PILL_GAP_DP) - PILL_GAP_DP
        private const val GRID_POP_MS = 180f
        private const val GRID_CLOSE_MS = 120L
        private const val SHIFT_DOUBLE_TAP_MS = 350L
        /** The space bar's cursor slide: how far before it starts, and per character. */
        private const val SPACE_SLIDE_START_DP = 12f
        private const val SPACE_SLIDE_STEP_DP = 9f
        private const val STICKY_BLOOM_MS = 6000L
        // hold to talk
        private const val TALK_HOLD_MS = 400L
        private const val TALK_CANCEL_DP = 90f
        private const val MESSAGE_MS = 3000L
        private const val PILL_IN_MS = 140f
        private const val PILL_OUT_MS = 160f
        private const val PILL_MAX_LINES = 4
        private const val SIDE_HINT_MS = 220L
        private const val DOUBLE_TAP_MS = 300L
        private const val SIDE_POP_MS = 120f
        /** Between the key's edge and its socket. */
        private const val SOCKET_GAP_DP = 10f
        /** The pulled key clicks into its socket this far along (and out again below [PULL_UNSNAP]). */
        private const val PULL_SNAP = 0.8f
        private const val PULL_UNSNAP = 0.6f
        private const val SNAP_POP_MS = 220f
        private const val SNAP_FLASH_MS = 300f
        private const val SPRING_BACK_MS = 450f
        private const val SPRING_BACK_DAMPING = 12f
        private const val SPRING_BACK_FREQ = 20f
        /** Hold hints: how far outside the disc, how big, how faint. */
        private const val HINT_GAP_DP = 10f
        private const val HINT_SIZE_DP = 13f
        private const val HINT_ALPHA = 0.4f
        private const val REST_TAB_ALPHA = 0.2f
        private const val FLING_MIN_DP_S = 700f
        private const val FLING_MAX_DP_S = 5000f
        private const val FLING_STOP_DP_S = 30f
        /** Per second: how fast a throw slows down (higher stops sooner). */
        private const val PIN_POP_MS = 260L
        private const val FLING_FRICTION = 5.5f
        /** The share of the impact speed that bounces back off an edge, and its cap: a 10–20 dp bob. */
        private const val FLING_BOUNCE = 0.2f
        private const val FLING_MAX_REBOUND_DP_S = 600f
        private const val FLING_SPRING_K = 700f
        private const val FLING_SPRING_C = 29f
        /** How far the folded bubble sinks under a press before it locks in. */
        private const val PRESS_SINK = 0.12f
        private const val LOCK_IN_SPRING_MS = 260f
        private const val LOCK_IN_RIPPLE_MS = 560f
        private const val EDIT_DP = 40f
        private const val EDIT_SPACING_DP = 8f
        private const val EDIT_GAP_DP = 10f
        private const val EDIT_POP_MS = 140f
        /** The cursor joystick: its touch targets, sizes, timings and speeds. */
        /** The bubble's notches: phases, size, distances and timing. */
        private const val NOTCH_NONE = 0
        private const val NOTCH_UNDECIDED = 1
        private const val NOTCH_SLIDING = 2
        private const val NOTCH_MOVING = 3
        private const val NOTCH_DP = 44f
        private const val NOTCH_GAP_DP = 14f
        private const val NOTCH_MIN_REACH_DP = 36f
        private const val NOTCH_DECIDE_DP = 10f
        /** Longer than this on the folded bubble and letting go in place isn't a tap. */
        private const val TAP_MAX_MS = 300L
        /** How faint the paste key and notch are with nothing to paste. */
        private const val DISABLED_ALPHA = 0.32f
        /** How far past a notch the finger goes before the bubble breaks out of it and just moves. */
        private const val NOTCH_BREAK_DP = 56f
        /** Ticks felt while pulling against a clicked-in notch, before it breaks. */
        private const val NOTCH_STRAIN_TICKS = 5
        /** How much of that pull the clicked-in bubble follows (the rest is the notch holding it). */
        private const val NOTCH_STRAIN_GIVE = 0.35f
        private const val NOTCH_LANE_DEG = 35.0
        private const val NOTCH_FADE_MS = 140f
        private const val STICK_KNOB = -6
        private const val STICK_KEYS = -7
        private const val STICK_EXIT = -5
        private const val STICK_FADE_MS = 220f
        /** The stick turns its push into cursor steps at about 60 Hz. */
        private const val STICK_FRAME_MS = 16L
        private const val STICK_HOLD_MS = 350L
        private const val STICK_SLOP_DP = 8f
        private const val STICK_EXIT_DP = 36f
        private const val KNOB_DP = 52f
        private const val STICK_DEAD = 0.14f
        private const val STICK_AXIS_BIAS = 0.55f
        private const val STICK_MIN_CPS = 1.5f
        private const val STICK_MAX_CPS = 30f
        private const val STICK_MIN_LPS = 0.8f
        private const val STICK_MAX_LPS = 7f
        private const val KNOB_SPRING_MS = 420f
        private const val KNOB_SPRING_DAMPING = 11f
        private const val KNOB_SPRING_FREQ = 22f
        /** How long the field sits idle before the paste button comes up. */
        /** How far out (from the bubble's edge) the throb reaches at full voice, past a thumb. */
        private const val THUMB_CLEAR_DP = 48f
        private const val LEVEL_PEAK_MIN = 0.25f
        private const val LEVEL_PEAK_DECAY = 0.12f // per second
        private const val LEVEL_FLOOR_RISE = 0.04f // per second
        private const val LEVEL_MIN_RANGE = 0.15f
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

    }
}
