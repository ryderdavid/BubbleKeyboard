package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ryderdavid.bubblekeyboard.voice.UsageLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The docked style: a one-row, 48 dp bar under the app, in the same surface color as the
 * navigation area so the two read as one. Left to right: paste, the cursor trackpad, the mic pill
 * (the one primary-colored thing), the keyboard drawer, and the overflow. A drawer can rise above
 * the bar for the letters, the trackpad, or the overflow menu; the bar then becomes its bottom row.
 *
 * Dictation, the trackpad and every key report through [BubbleView.Listener], so the service
 * drives this exactly as it drives the bubble.
 */
class DockView(context: Context, attrs: AttributeSet?) : View(context, attrs), KeyboardSurface {

    var listener: BubbleView.Listener? = null

    enum class Drawer { NONE, KEYS, PAD, MENU }
    var drawer = Drawer.NONE
        private set

    /** What a bar or drawer control does. */
    enum class Act {
        PASTE, CURSOR, MIC, KEYBOARD, MORE, CUT, COPY, DELETE, DISCARD, FINISH, HIDE, COMMA, PERIOD, SPACE, ENTER,
        LETTER, SHIFT, BACKSPACE, CHIP_CUT, CHIP_COPY, CHIP_PASTE, CHIP_DELETE, PAD,
        TILE_SETTINGS, TILE_SWITCH, TILE_BUBBLE, TILE_COST,
    }

    private class Item(val act: Act, val rect: RectF = RectF(), val ch: Char = ' ')

    private var palette = Palette(context)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------------------------------------------------------------- the service's side

    override var hasSelection = false
        set(v) { if (field != v) { field = v; invalidate() } }
    override var canPaste = false
        set(v) { if (field != v) { field = v; invalidate() } }
    override var voiceInField = false
        set(v) { field = v; invalidate() }
    override var voice = BubbleView.Voice.OFF
        private set
    private var voiceText = ""
    private var talking = false
    private var talkStartedAt = 0L
    private var silenceMs = Prefs.DEFAULT_SILENCE_TIMEOUT * 1000L
    private var silenceDeadline = 0L
    private val levels = FloatArray(WAVE_HISTORY)
    private var levelPeak = 0.08f

    private val hideVoiceRunnable = Runnable { hideVoice() }
    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (!talking || silenceDeadline == 0L) return
            val left = silenceDeadline - SystemClock.uptimeMillis()
            if (left > 0) { postDelayed(this, left); return }
            endTalk(cancel = false)
        }
    }

    override fun showListening() {
        removeCallbacks(hideVoiceRunnable)
        voice = BubbleView.Voice.LISTENING
        voiceText = ""
        levels.fill(0f)
        levelPeak = 0.08f
        invalidate()
    }

    override fun showLevel(level: Float) {
        val raw = level.coerceIn(0f, 1f)
        levelPeak = max(raw, levelPeak * 0.985f).coerceAtLeast(0.08f)
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = (raw / levelPeak).coerceIn(0f, 1f)
        invalidate()
    }

    override fun showLiveText(text: String) { voiceText = text; invalidate() }

    override fun showCleaning(text: String) {
        voice = BubbleView.Voice.CLEANING
        voiceText = text
        invalidate()
    }

    override fun showMessage(message: String) {
        removeCallbacks(hideVoiceRunnable)
        voice = BubbleView.Voice.MESSAGE
        voiceText = message
        postDelayed(hideVoiceRunnable, MESSAGE_MS)
        invalidate()
    }

    override fun hideVoice() {
        removeCallbacks(hideVoiceRunnable)
        voice = BubbleView.Voice.OFF
        voiceText = ""
        invalidate()
    }

    override fun heardSpeech() {
        if (talking && silenceDeadline != 0L) silenceDeadline = SystemClock.uptimeMillis() + silenceMs
    }

    override fun talkEnded() {
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        invalidate()
    }

    override fun reset() {
        if (padActive) endPad()
        if (talking) endTalk(cancel = true)
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        shiftOnce = false
        capsLock = false
        pressed = null
        setDrawer(Drawer.NONE)
        invalidate()
    }

    override fun reloadSettings() {
        palette = Palette(context)
        silenceMs = Prefs.get(context).getInt(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT)
            .coerceIn(Prefs.SILENCE_TIMEOUT_RANGE) * 1000L
        invalidate()
    }

    // ---------------------------------------------------------------- talking

    private fun startTalk() {
        if (talking) return
        if (listener?.onTalkStart() == true) {
            talking = true
            talkStartedAt = SystemClock.uptimeMillis()
            silenceDeadline = talkStartedAt + silenceMs
            removeCallbacks(silenceRunnable)
            postDelayed(silenceRunnable, silenceMs)
            if (drawer == Drawer.MENU || drawer == Drawer.PAD) setDrawer(Drawer.NONE)
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
        invalidate()
    }

    private fun endTalk(cancel: Boolean) {
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        listener?.onTalkEnd(cancel)
        invalidate()
    }

    @VisibleForTesting internal val testTalking get() = talking

    // ---------------------------------------------------------------- geometry

    private var bottomInset = 0
    private val barH get() = dp(BAR_DP)
    private val drawerH get() = if (drawer == Drawer.NONE) 0f else dp(DRAWER_DP)
    private val barTop get() = drawerH

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateInset()
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateInset()
        requestApplyInsets()
    }

    private fun updateInset() {
        val root = ViewCompat.getRootWindowInsets(this)
        val nav = root?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val v = maxOf(
            nav,
            root?.getInsets(WindowInsetsCompat.Type.captionBar())?.bottom ?: 0,
            root?.getInsets(WindowInsetsCompat.Type.tappableElement())?.bottom ?: 0,
            // before Android 15 the keyboard can't hide the system's ⌄/switcher band in gesture
            // navigation, so the bar sits above it (see BubbleView.imeNavFrameHeight)
            if (nav > 0 && Build.VERSION.SDK_INT < 35) imeNavFrameHeight() else 0,
        )
        if (insetOverride >= 0) return
        if (v != bottomInset) { bottomInset = v; requestLayout(); invalidate() }
    }
    private var insetOverride = -1

    private fun imeNavFrameHeight(): Int {
        val gestural = runCatching {
            android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
        }.getOrDefault(false)
        if (!gestural) return 0
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    @VisibleForTesting fun testSetBottomInset(px: Int) { insetOverride = px; bottomInset = px; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (drawerH + barH + bottomInset).toInt())
    }

    private fun setDrawer(d: Drawer) {
        if (drawer == d) return
        drawer = d
        drawerShownAt = SystemClock.uptimeMillis()
        if (d == Drawer.KEYS) autoCap = listener?.shouldCapitalize() == true
        if (d == Drawer.MENU) costText = runCatching { UsageLog.formatUsd(UsageLog.of(context).last(24 * 30).totalUsd) }.getOrDefault("")
        requestLayout()
        invalidate()
    }
    private var drawerShownAt = 0L
    private var costText = ""

    @VisibleForTesting internal fun testItem(act: Act, ch: Char = ' '): RectF? {
        layoutAll()
        return items.firstOrNull { it.act == act && (act != Act.LETTER || it.ch == ch) }?.rect?.let { RectF(it) }
    }
    @VisibleForTesting internal fun testHas(act: Act): Boolean { layoutAll(); return items.any { it.act == act } }

    // ---------------------------------------------------------------- layout

    private val items = mutableListOf<Item>()

    /** The bar's controls for the current state, left to right; [Act.MIC] and [Act.SPACE] stretch. */
    private fun barActs(): List<Act> = when {
        // polishing, or a message: the pill takes the whole bar for a moment
        voice == BubbleView.Voice.CLEANING && !talking -> listOf(Act.MIC)
        voice == BubbleView.Voice.MESSAGE && !talking -> listOf(Act.MIC)
        talking -> listOf(Act.DISCARD, Act.MIC, Act.FINISH)
        drawer == Drawer.KEYS -> listOf(Act.HIDE, Act.MIC, Act.COMMA, Act.SPACE, Act.PERIOD, Act.ENTER)
        // the trackpad's chips above already cut, copy and paste: here, just its toggle
        drawer == Drawer.PAD -> listOf(Act.CURSOR, Act.MIC, Act.BACKSPACE, Act.ENTER, Act.KEYBOARD, Act.MORE)
        hasSelection -> listOf(Act.CUT, Act.COPY, Act.PASTE, Act.MIC, Act.BACKSPACE, Act.ENTER, Act.MORE)
        else -> listOf(Act.PASTE, Act.CURSOR, Act.MIC, Act.BACKSPACE, Act.ENTER, Act.KEYBOARD, Act.MORE)
    }

    private fun layoutAll() {
        items.clear()
        val w = width.toFloat()
        // the bar
        val acts = barActs()
        val pad = dp(8f)
        val crowded = acts.size >= 7
        val gap = dp(if (crowded) 4f else 6f)
        val b = dp(if (crowded) 38f else 40f)
        val flexCount = acts.count { it == Act.MIC || it == Act.SPACE }.coerceAtLeast(1)
        val keyboardMic = drawer == Drawer.KEYS && !talking && voice != BubbleView.Voice.CLEANING
        val fixed = acts.count { !(it == Act.SPACE || (it == Act.MIC && !keyboardMic)) }
        val flexW = (w - 2 * pad - gap * (acts.size - 1) - b * fixed) / (if (keyboardMic) 1 else flexCount)
        var x = pad
        val top = barTop + (barH - b) / 2
        for (a in acts) {
            val stretch = a == Act.SPACE || (a == Act.MIC && !keyboardMic)
            val ww = if (stretch) flexW else b
            items.add(Item(a, RectF(x, top, x + ww, top + b)))
            x += ww + gap
        }
        when (drawer) {
            Drawer.KEYS -> layoutKeys(w)
            Drawer.PAD -> layoutPad(w)
            Drawer.MENU -> layoutMenu(w)
            Drawer.NONE -> Unit
        }
    }

    private fun layoutKeys(w: Float) {
        val side = dp(5f)
        val gap = dp(5f)
        val kh = dp(KEY_DP)
        val kw = (w - 2 * side - gap * 9) / 10
        var y = dp(10f)
        for ((r, row) in ROWS.withIndex()) {
            if (r < 2) {
                val rowW = row.length * kw + (row.length - 1) * gap
                var x = (w - rowW) / 2
                for (ch in row) { items.add(Item(Act.LETTER, RectF(x, y, x + kw, y + kh), ch)); x += kw + gap }
            } else {
                val wide = (w - 2 * side - row.length * kw - (row.length + 1) * gap) / 2
                var x = side
                items.add(Item(Act.SHIFT, RectF(x, y, x + wide, y + kh))); x += wide + gap
                for (ch in row) { items.add(Item(Act.LETTER, RectF(x, y, x + kw, y + kh), ch)); x += kw + gap }
                items.add(Item(Act.BACKSPACE, RectF(x, y, x + wide, y + kh)))
            }
            y += kh + dp(8f)
        }
    }

    private fun layoutPad(w: Float) {
        val side = dp(10f)
        val ch = dp(36f)
        val gap = dp(8f)
        val cw = (w - 2 * side - 3 * gap) / 4
        var x = side
        for (a in listOf(Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE)) {
            items.add(Item(a, RectF(x, dp(10f), x + cw, dp(10f) + ch)))
            x += cw + gap
        }
        items.add(Item(Act.PAD, RectF(side, dp(10f) + ch + gap, w - side, drawerH - dp(6f))))
    }

    private fun layoutMenu(w: Float) {
        val side = dp(10f)
        val gap = dp(8f)
        val tw = (w - 2 * side - gap) / 2
        val th = (drawerH - dp(10f) - dp(6f) - gap) / 2
        val tiles = listOf(Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_BUBBLE, Act.TILE_COST)
        for ((i, a) in tiles.withIndex()) {
            val x = side + (i % 2) * (tw + gap)
            val y = dp(10f) + (i / 2) * (th + gap)
            items.add(Item(a, RectF(x, y, x + tw, y + th)))
        }
    }

    // ---------------------------------------------------------------- keys

    private var shiftOnce = false
    private var capsLock = false
    private var autoCap = false
    private var lastShiftTap = 0L
    private val caps get() = capsLock || shiftOnce || autoCap

    private fun typeLetter(ch: Char) {
        listener?.onLetter(if (caps) ch.uppercaseChar() else ch)
        if (shiftOnce) shiftOnce = false
        autoCap = listener?.shouldCapitalize() == true
        invalidate()
    }

    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        when {
            capsLock -> { capsLock = false; shiftOnce = false }
            now - lastShiftTap < DOUBLE_TAP_MS && shiftOnce -> { capsLock = true; shiftOnce = false }
            else -> shiftOnce = !(shiftOnce || autoCap)
        }
        if (!shiftOnce && !capsLock) autoCap = false
        lastShiftTap = now
        invalidate()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            if (p.act != Act.BACKSPACE) return
            listener?.onKey(BubbleView.Key.DELETE, true)
            postDelayed(this, REPEAT_MS)
        }
    }

    private val commaHoldRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (p.act != Act.COMMA) return@Runnable
        pressed = null
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setDrawer(Drawer.PAD)
    }

    // ---------------------------------------------------------------- the trackpad

    private var padActive = false
    private var padSelecting = false
    private var padMoved = false
    private var padX = 0f
    private var padY = 0f
    private var padAccX = 0f
    private var padAccY = 0f
    private val padHoldRunnable = Runnable {
        if (!padActive || padMoved) return@Runnable
        padSelecting = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onStickSelect()
        invalidate()
    }

    private fun padDown(x: Float, y: Float) {
        padActive = true
        padSelecting = false
        padMoved = false
        padX = x; padY = y; padAccX = 0f; padAccY = 0f
        listener?.onStickStart()
        postDelayed(padHoldRunnable, PAD_HOLD_MS)
        invalidate()
    }

    private fun padMove(x: Float, y: Float) {
        padAccX += x - padX
        padAccY += y - padY
        padX = x; padY = y
        if (!padMoved && (abs(padAccX) > dp(4f) || abs(padAccY) > dp(4f))) {
            padMoved = true
            removeCallbacks(padHoldRunnable)
        }
        val cs = dp(PAD_COL_DP)
        val ls = dp(PAD_LINE_DP)
        val cols = (padAccX / cs).toInt()
        val lines = (padAccY / ls).toInt()
        if (cols != 0 || lines != 0) {
            padAccX -= cols * cs
            padAccY -= lines * ls
            listener?.onStickStep(cols, lines, padSelecting)
        }
        invalidate()
    }

    private fun padUp() {
        removeCallbacks(padHoldRunnable)
        if (padMoved && !padSelecting) listener?.onStickTap()
        endPad()
    }

    private fun endPad() {
        removeCallbacks(padHoldRunnable)
        padActive = false
        padSelecting = false
        listener?.onStickEnd()
        invalidate()
    }

    // ---------------------------------------------------------------- touch

    private var pressed: Item? = null
    private var downX = 0f
    private var downY = 0f
    private var sliding = false
    private var slideX = 0f
    /** The press already did its thing (a hold); its release does nothing more. */
    private var consumed = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { padPointer = e.getPointerId(0); onDown(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> {
                if (pressed?.act == Act.PAD) {
                    val i = e.findPointerIndex(padPointer)
                    if (i >= 0) padMove(e.getX(i), e.getY(i))
                } else onMove(e.x, e.y)
            }
            // on the trackpad another finger takes over the dragging while the first stays down,
            // so a selection can run longer than one sweep of the thumb
            MotionEvent.ACTION_POINTER_DOWN -> if (pressed?.act == Act.PAD) {
                val i = e.actionIndex
                padHandOff(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (pressed?.act == Act.PAD && e.getPointerId(e.actionIndex) == padPointer) {
                // the dragging finger lifted: carry on with one that's still down
                val keep = (0 until e.pointerCount).firstOrNull { it != e.actionIndex }
                if (keep != null) padHandOff(e.getPointerId(keep), e.getX(keep), e.getY(keep))
            }
            MotionEvent.ACTION_UP -> onUp(e.x, e.y)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private var padPointer = 0

    /** A different finger drives the trackpad from here; where it is now is its starting point. */
    private fun padHandOff(id: Int, x: Float, y: Float) {
        padPointer = id
        padX = x
        padY = y
        padMoved = true // more than one finger is never a tap
        removeCallbacks(padHoldRunnable)
        invalidate()
    }

    private fun hit(x: Float, y: Float): Item? {
        layoutAll()
        val slop = dp(4f)
        return items.firstOrNull { it.act == Act.PAD && it.rect.contains(x, y) }
            ?: items.minByOrNull { d2(it.rect, x, y) }?.takeIf { d2(it.rect, x, y) <= slop * slop }
    }

    private fun d2(r: RectF, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right))
        val dy = max(0f, max(r.top - y, y - r.bottom))
        return dx * dx + dy * dy
    }

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y
        sliding = false
        consumed = false
        val it = hit(x, y)
        pressed = it
        if (it == null) return
        when (it.act) {
            Act.PAD -> { padDown(x, y); return }
            Act.BACKSPACE -> { listener?.onKey(BubbleView.Key.DELETE, false); postDelayed(repeatRunnable, REPEAT_START_MS) }
            Act.COMMA -> postDelayed(commaHoldRunnable, HOLD_MS)
            else -> Unit
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun onMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (p.act == Act.PAD) { padMove(x, y); return }
        // a sideways slide on the space bar, or on the idle mic pill, moves the cursor
        if ((p.act == Act.SPACE || (p.act == Act.MIC && !talking && drawer != Drawer.KEYS)) && !sliding && abs(x - downX) > dp(SLIDE_START_DP)) {
            sliding = true
            slideX = downX
        }
        if (sliding) {
            val step = dp(SLIDE_STEP_DP)
            while (x - slideX >= step) { slideX += step; listener?.onKey(BubbleView.Key.RIGHT, true) }
            while (slideX - x >= step) { slideX -= step; listener?.onKey(BubbleView.Key.LEFT, true) }
            invalidate()
            return
        }
        if (p.act == Act.LETTER) {
            // slide-and-lift corrects to the key under the finger
            val n = hit(x, y)
            if (n != null && n.act == Act.LETTER && n.ch != p.ch) { pressed = n; invalidate() }
            return
        }
        if (d2(p.rect, x, y) > dp(16f) * dp(16f)) {
            removeCallbacks(repeatRunnable)
            removeCallbacks(commaHoldRunnable)
            pressed = null
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed
        pressed = null
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        if (p == null) { invalidate(); return }
        if (p.act == Act.PAD) { padUp(); return }
        if (consumed || sliding) { sliding = false; invalidate(); return }
        act(p)
        invalidate()
    }

    private fun onCancel() {
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        if (padActive) endPad()
        pressed = null
        sliding = false
        invalidate()
    }

    private fun act(p: Item) {
        val l = listener
        when (p.act) {
            Act.MIC -> when {
                talking -> endTalk(cancel = false)
                voice == BubbleView.Voice.CLEANING -> Unit
                else -> startTalk()
            }
            Act.FINISH -> if (talking) endTalk(cancel = false)
            Act.DISCARD -> if (talking) endTalk(cancel = true)
            Act.PASTE, Act.CHIP_PASTE -> if (canPaste) l?.onEdit(BubbleView.EditAction.PASTE)
            Act.CUT, Act.CHIP_CUT -> if (hasSelection) l?.onEdit(BubbleView.EditAction.CUT)
            Act.COPY, Act.CHIP_COPY -> if (hasSelection) l?.onEdit(BubbleView.EditAction.COPY)
            Act.DELETE, Act.CHIP_DELETE -> l?.onEdit(BubbleView.EditAction.DELETE)
            Act.CURSOR -> setDrawer(if (drawer == Drawer.PAD) Drawer.NONE else Drawer.PAD)
            Act.KEYBOARD -> setDrawer(Drawer.KEYS)
            Act.MORE -> setDrawer(if (drawer == Drawer.MENU) Drawer.NONE else Drawer.MENU)
            Act.HIDE -> setDrawer(Drawer.NONE)
            Act.COMMA -> l?.onLetter(',')
            Act.PERIOD -> l?.onLetter('.')
            Act.SPACE -> { l?.onKey(BubbleView.Key.SPACE, false); autoCap = l?.shouldCapitalize() == true }
            Act.ENTER -> { l?.onKey(BubbleView.Key.ENTER, false); autoCap = l?.shouldCapitalize() == true }
            Act.LETTER -> typeLetter(p.ch)
            Act.SHIFT -> tapShift()
            Act.BACKSPACE -> Unit // done on press
            Act.TILE_SETTINGS, Act.TILE_COST -> { setDrawer(Drawer.NONE); l?.onOpenSettings() }
            Act.TILE_SWITCH -> { setDrawer(Drawer.NONE); l?.onSwitchKeyboard() }
            Act.TILE_BUBBLE -> { setDrawer(Drawer.NONE); l?.onSwitchStyle(docked = false) }
            Act.PAD -> Unit
        }
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val icon = Path()
    private val tmp = RectF()

    private fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (Color.alpha(c) * a.coerceIn(0f, 1f)).toInt())

    override fun onDraw(canvas: Canvas) {
        val pal = palette
        layoutAll()
        // one surface for drawer, bar and the navigation area under it
        fill.color = pal.disc
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // a hairline where the app ends and the keyboard begins
        fill.color = pal.outline
        canvas.drawRect(0f, 0f, width.toFloat(), max(1f, dp(1f)), fill)
        val now = SystemClock.uptimeMillis()
        val rise = ((now - drawerShownAt) / DRAWER_RISE_MS).coerceIn(0f, 1f)
        val lift = (1f - easeOut(rise)) * dp(18f)
        if (rise < 1f) postInvalidateOnAnimation()
        canvas.save()
        canvas.translate(0f, lift)
        for (it in items) if (it.rect.bottom <= barTop + 0.5f) drawDrawerItem(canvas, it, rise)
        canvas.restore()
        for (it in items) if (it.rect.top >= barTop) drawBarItem(canvas, it, now)
    }

    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun drawBarItem(canvas: Canvas, it: Item, now: Long) {
        val pal = palette
        val r = it.rect
        val down = pressed === it
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.height() / 2
        when (it.act) {
            Act.MIC -> { drawMicPill(canvas, it, now, down); return }
            Act.SPACE -> {
                fill.color = if (down) pal.pressed else pal.bloomKey
                canvas.drawRoundRect(r, rad, rad, fill)
                val c = if (down) pal.onPressed else pal.onCardinal
                if (sliding && down) drawSlideArrows(canvas, cx, cy, dp(4f), c)
                else drawSpace(canvas, cx, cy, dp(18f), c)
                return
            }
            else -> Unit
        }
        val tonal = it.act in TONAL
        val active = (it.act == Act.CURSOR && drawer == Drawer.PAD) || (it.act == Act.MORE && drawer == Drawer.MENU)
        val enabled = when (it.act) {
            Act.PASTE -> canPaste
            Act.CUT, Act.COPY -> hasSelection
            else -> true
        }
        val bg = when {
            down -> pal.pressed
            active -> pal.pressed // a toggle that's on reads as clearly on
            it.act == Act.ENTER -> pal.center
            it.act == Act.DISCARD -> pal.discard
            tonal -> pal.diagonal
            else -> Color.TRANSPARENT
        }
        val fg = when {
            down -> pal.onPressed
            active -> pal.onPressed
            it.act == Act.ENTER -> pal.onCenter
            it.act == Act.DISCARD -> pal.onDiscard
            tonal -> pal.onDiagonal
            else -> pal.onCardinal
        }
        val a = if (enabled) 1f else DISABLED_ALPHA
        if (Color.alpha(bg) > 0) {
            fill.color = alpha(bg, a)
            // the finish key is a rounded square: the one shape that says "done" at a glance
            val corner = if (it.act == Act.FINISH) dp(12f) else rad
            canvas.drawRoundRect(r, corner, corner, fill)
        }
        drawGlyph(canvas, it.act, cx, cy, dp(22f), alpha(fg, a))
    }

    private fun drawMicPill(canvas: Canvas, it: Item, now: Long, down: Boolean) {
        val pal = palette
        val r = it.rect
        val rad = r.height() / 2
        val cleaning = voice == BubbleView.Voice.CLEANING
        val message = voice == BubbleView.Voice.MESSAGE && !talking
        val keysMode = drawer == Drawer.KEYS && !talking && !cleaning
        val bg = when {
            down -> pal.onCenter
            cleaning -> pal.center
            message -> pal.discard
            keysMode -> pal.diagonal
            else -> pal.pressed
        }
        val fg = when {
            down -> pal.center
            cleaning -> pal.onCenter
            message -> pal.onDiscard
            keysMode -> pal.onDiagonal
            else -> pal.onPressed
        }
        fill.color = bg
        canvas.drawRoundRect(r, rad, rad, fill)
        val cx = r.centerX()
        val cy = r.centerY()
        text.color = fg
        text.textSize = dp(14f)
        val base = cy - (text.ascent() + text.descent()) / 2
        when {
            talking || cleaning -> {
                canvas.save()
                canvas.clipRect(r)
                if (cleaning) {
                    canvas.drawText(context.getString(R.string.dock_polishing), cx, base, text)
                    val p = ((now % 1100L) / 1100f)
                    val sw = r.width() * 0.25f
                    val sx = r.left - sw + (r.width() + sw) * p
                    tmp.set(sx, r.bottom - dp(3f), sx + sw, r.bottom)
                    fill.color = fg
                    canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
                    canvas.restore()
                    postInvalidateOnAnimation()
                    return
                }
                val showText = !voiceInField && voiceText.isNotBlank()
                // quiet for a while: the pill's color drains away right to left until it stops
                val drain = silenceDrain(now)
                if (drain < 1f) {
                    fill.color = alpha(pal.center, 1f)
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.save()
                    canvas.clipRect(r.left, r.top, r.left + r.width() * drain, r.bottom)
                    fill.color = bg
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.restore()
                }
                text.textAlign = Paint.Align.RIGHT
                val right = r.right - dp(16f)
                val label = elapsed(now)
                val labelW = text.measureText(label)
                // the waveform: across the whole pill up to the timer, newest on the right
                val bw = dp(3f)
                val bg2 = dp(3f)
                val wx0 = r.left + dp(16f)
                val wx1 = right - labelW - dp(12f)
                val bars = ((wx1 - wx0 + bg2) / (bw + bg2)).toInt().coerceIn(1, levels.size)
                val off = (wx1 - wx0) - (bars * bw + (bars - 1) * bg2)
                for (i in 0 until bars) {
                    val lv = levels[levels.size - bars + i]
                    val idle = 0.12f + 0.06f * sin((now / 140.0 + i).toFloat())
                    val h = dp(22f) * max(lv, idle)
                    val bx = wx0 + off + i * (bw + bg2)
                    tmp.set(bx, cy - h / 2, bx + bw, cy + h / 2)
                    val inDrained = drain < 1f && bx > r.left + r.width() * drain
                    fill.color = alpha(if (inDrained) pal.onCenter else fg, if (showText) 0.18f else 1f)
                    canvas.drawRoundRect(tmp, bw / 2, bw / 2, fill)
                }
                text.color = if (drain < 1f && right > r.left + r.width() * drain) pal.onCenter else fg
                canvas.drawText(label, right, base, text)
                if (showText) {
                    // what's heard so far, the tail end kept in view, over a faint waveform
                    val left = wx0
                    val room = right - labelW - dp(10f) - left
                    text.textAlign = Paint.Align.LEFT
                    text.color = fg
                    var t = voiceText
                    while (t.isNotEmpty() && text.measureText(t) > room) t = t.substring(1)
                    canvas.drawText(t, left, base, text)
                }
                text.textAlign = Paint.Align.CENTER
                if (cleaning) {
                    // a sweep along the pill's base while Claude tidies
                    val p = ((now % 1100L) / 1100f)
                    val sw = r.width() * 0.35f
                    val sx = r.left - sw + (r.width() + sw) * p
                    tmp.set(sx, r.bottom - dp(3f), sx + sw, r.bottom)
                    fill.color = fg
                    canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
                }
                canvas.restore()
                postInvalidateOnAnimation()
            }
            message -> {
                var t = voiceText
                while (t.isNotEmpty() && text.measureText(t) > r.width() - dp(24f)) t = t.dropLast(1)
                canvas.drawText(t, cx, base, text)
            }
            else -> {
                val label = if (r.width() > dp(140f) && drawer != Drawer.KEYS) context.getString(R.string.dock_speak) else ""
                val iw = dp(20f)
                val total = if (label.isEmpty()) iw else iw + dp(8f) + text.measureText(label)
                val x0 = cx - total / 2
                drawMic(canvas, x0 + iw / 2, cy, iw, fg)
                if (label.isNotEmpty()) {
                    text.textAlign = Paint.Align.LEFT
                    canvas.drawText(label, x0 + iw + dp(8f), base, text)
                    text.textAlign = Paint.Align.CENTER
                }
            }
        }
    }

    /**
     * 1 while speech is recent; after [DRAIN_AFTER_MS] of quiet it falls to 0 at the silence
     * timeout, so the pill can show the time left as color draining away.
     */
    private fun silenceDrain(now: Long): Float {
        if (!talking || silenceDeadline == 0L) return 1f
        val start = min(DRAIN_AFTER_MS, silenceMs / 2)
        val left = (silenceDeadline - now).toFloat()
        val span = (silenceMs - start).toFloat()
        return (left / span).coerceIn(0f, 1f)
    }

    @VisibleForTesting internal fun testDrain() = silenceDrain(SystemClock.uptimeMillis())

    private fun elapsed(now: Long): String {
        val s = ((now - talkStartedAt) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawDrawerItem(canvas: Canvas, it: Item, rise: Float) {
        val pal = palette
        val r = it.rect
        val down = pressed === it
        when (it.act) {
            Act.LETTER, Act.SHIFT, Act.BACKSPACE -> {
                val fn = it.act != Act.LETTER
                val bg = when {
                    down -> pal.pressed
                    it.act == Act.SHIFT && (capsLock || shiftOnce) -> pal.center
                    fn -> pal.cardinal
                    else -> pal.bloomKey
                }
                val fg = when {
                    down -> pal.onPressed
                    it.act == Act.SHIFT && (capsLock || shiftOnce) -> pal.onCenter
                    fn -> pal.onCardinal
                    else -> pal.onBloomKey
                }
                val rad = min(r.width(), r.height()) / 2
                if (down) {
                    tmp.set(r); tmp.inset(-dp(2f), -dp(2f))
                    fill.color = bg
                    canvas.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill)
                } else {
                    fill.color = bg
                    canvas.drawRoundRect(r, rad, rad, fill)
                }
                when (it.act) {
                    Act.LETTER -> {
                        text.color = fg
                        text.textSize = dp(20f)
                        val s = if (caps) it.ch.uppercaseChar().toString() else it.ch.toString()
                        canvas.drawText(s, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                    }
                    Act.SHIFT -> drawShift(canvas, r.centerX(), r.centerY(), dp(9f), fg, capsLock)
                    else -> { fill.color = fg; Icons.draw(canvas, Icons.Glyph.BACKSPACE, r.centerX(), r.centerY(), dp(22f), fill) }
                }
            }
            Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE -> {
                val enabled = when (it.act) {
                    Act.CHIP_CUT, Act.CHIP_COPY -> hasSelection
                    Act.CHIP_PASTE -> canPaste
                    else -> true
                }
                val a = if (enabled) 1f else DISABLED_ALPHA
                fill.color = alpha(if (down) pal.pressed else pal.diagonal, a)
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                val fg = alpha(if (down) pal.onPressed else pal.onDiagonal, a)
                val g = when (it.act) { Act.CHIP_CUT -> Icons.Glyph.CUT; Act.CHIP_COPY -> Icons.Glyph.COPY; Act.CHIP_PASTE -> Icons.Glyph.PASTE; else -> Icons.Glyph.BACKSPACE }
                val label = context.getString(when (it.act) { Act.CHIP_CUT -> R.string.dock_cut; Act.CHIP_COPY -> R.string.dock_copy; Act.CHIP_PASTE -> R.string.dock_paste; else -> R.string.dock_delete })
                text.textSize = dp(13f)
                text.color = fg
                val iw = dp(18f)
                val total = iw + dp(6f) + text.measureText(label)
                val x0 = r.centerX() - total / 2
                fill.color = fg
                Icons.draw(canvas, g, x0 + iw / 2, r.centerY(), iw, fill)
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, x0 + iw + dp(6f), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                text.textAlign = Paint.Align.CENTER
            }
            Act.PAD -> {
                fill.color = pal.bloomKey
                canvas.drawRoundRect(r, dp(24f), dp(24f), fill)
                text.textSize = dp(13f)
                text.color = alpha(pal.onCardinal, 0.8f)
                val msg = context.getString(if (padSelecting) R.string.dock_pad_selecting_more else R.string.dock_pad_hint)
                canvas.drawText(msg, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                if (padActive) {
                    fill.color = alpha(pal.pressed, 0.22f)
                    canvas.drawCircle(padX.coerceIn(r.left, r.right), padY.coerceIn(r.top, r.bottom), dp(26f), fill)
                    fill.color = pal.pressed
                    canvas.drawCircle(padX.coerceIn(r.left, r.right), padY.coerceIn(r.top, r.bottom), dp(6f), fill)
                }
            }
            Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_BUBBLE, Act.TILE_COST -> {
                fill.color = if (down) pal.pressed else pal.bloomKey
                canvas.drawRoundRect(r, dp(20f), dp(20f), fill)
                val fg = if (down) pal.onPressed else pal.onBloomKey
                val sub = if (down) pal.onPressed else pal.onCardinal
                val ix = r.left + dp(20f)
                val iy = r.centerY()
                when (it.act) {
                    Act.TILE_SETTINGS -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SETTINGS, ix + dp(11f), iy, dp(22f), fill) }
                    Act.TILE_SWITCH -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SWITCH_KEYBOARD, ix + dp(11f), iy, dp(22f), fill) }
                    Act.TILE_BUBBLE -> { stroke.color = sub; stroke.strokeWidth = dp(2f); canvas.drawCircle(ix + dp(11f), iy, dp(8f), stroke) }
                    else -> { text.color = sub; text.textSize = dp(18f); canvas.drawText("$", ix + dp(11f), iy - (text.ascent() + text.descent()) / 2, text) }
                }
                text.textAlign = Paint.Align.LEFT
                text.color = fg
                text.textSize = dp(14f)
                val title = context.getString(when (it.act) {
                    Act.TILE_SETTINGS -> R.string.dock_settings
                    Act.TILE_SWITCH -> R.string.dock_switch
                    Act.TILE_BUBBLE -> R.string.dock_bubble
                    else -> R.string.dock_cost
                })
                val tx = ix + dp(34f)
                if (it.act == Act.TILE_COST && costText.isNotEmpty()) {
                    canvas.drawText(title, tx, iy - dp(3f), text)
                    text.color = sub
                    text.textSize = dp(12f)
                    canvas.drawText(costText, tx, iy + dp(14f), text)
                } else {
                    canvas.drawText(title, tx, iy - (text.ascent() + text.descent()) / 2, text)
                }
                text.textAlign = Paint.Align.CENTER
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- glyphs (24-unit grid, 2-unit strokes)

    private fun drawGlyph(canvas: Canvas, act: Act, x: Float, y: Float, size: Float, c: Int) {
        fill.color = c
        when (act) {
            Act.PASTE -> Icons.draw(canvas, Icons.Glyph.PASTE, x, y, size, fill)
            Act.CUT -> Icons.draw(canvas, Icons.Glyph.CUT, x, y, size, fill)
            Act.COPY -> Icons.draw(canvas, Icons.Glyph.COPY, x, y, size, fill)
            Act.DELETE, Act.BACKSPACE -> Icons.draw(canvas, Icons.Glyph.BACKSPACE, x, y, size, fill)
            Act.CURSOR -> Icons.draw(canvas, Icons.Glyph.TEXT_CURSOR, x, y, size, fill)
            Act.DISCARD -> Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, size, fill)
            Act.KEYBOARD -> drawKeyboard(canvas, x, y, size, c)
            Act.MORE -> for (i in -1..1) canvas.drawCircle(x, y + i * size * 0.27f, size * 0.09f, fill)
            Act.FINISH -> strokePath(canvas, x, y, size, c) { m, l -> m(5f, 12.5f); l(9.5f, 17f); l(19f, 7.5f) }
            Act.HIDE -> strokePath(canvas, x, y, size, c) { m, l -> m(6f, 9.5f); l(12f, 15.5f); l(18f, 9.5f) }
            Act.ENTER -> strokePath(canvas, x, y, size, c) { m, l -> m(19f, 5f); l(19f, 13f); l(6f, 13f); m(10f, 9f); l(6f, 13f); l(10f, 17f) }
            Act.COMMA, Act.PERIOD -> {
                text.color = c
                text.textSize = size * 1.05f
                canvas.drawText(if (act == Act.COMMA) "," else ".", x, y - (text.ascent() + text.descent()) / 2, text)
            }
            else -> Unit
        }
    }

    private inline fun strokePath(canvas: Canvas, x: Float, y: Float, size: Float, c: Int, build: ((Float, Float) -> Unit, (Float, Float) -> Unit) -> Unit) {
        val u = size / 24f
        icon.reset()
        build({ a, b -> icon.moveTo(x + (a - 12f) * u, y + (b - 12f) * u) }, { a, b -> icon.lineTo(x + (a - 12f) * u, y + (b - 12f) * u) })
        stroke.color = c
        stroke.strokeWidth = 2f * u
        canvas.drawPath(icon, stroke)
    }

    private fun drawMic(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        val u = size / 24f
        stroke.color = c
        stroke.strokeWidth = 2f * u
        tmp.set(x - 3.2f * u, y - 9f * u, x + 3.2f * u, y + 3f * u)
        fill.color = c
        canvas.drawRoundRect(tmp, 3.2f * u, 3.2f * u, fill)
        tmp.set(x - 6.5f * u, y - 5f * u, x + 6.5f * u, y + 6f * u)
        canvas.drawArc(tmp, 0f, 180f, false, stroke)
        canvas.drawLine(x, y + 6f * u, x, y + 9.5f * u, stroke)
    }

    private fun drawKeyboard(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        val w = size * 0.42f
        val h = size * 0.28f
        stroke.color = c
        stroke.strokeWidth = size * 0.08f
        tmp.set(x - w, y - h, x + w, y + h)
        canvas.drawRoundRect(tmp, size * 0.08f, size * 0.08f, stroke)
        fill.color = c
        val k = size * 0.055f
        for (row in 0 until 2) for (col in 0 until 4) canvas.drawCircle(x - w * 0.6f + col * w * 0.4f, y - h * 0.45f + row * h * 0.5f, k, fill)
        canvas.drawLine(x - w * 0.4f, y + h * 0.5f, x + w * 0.4f, y + h * 0.5f, stroke)
    }

    private fun drawSpace(canvas: Canvas, x: Float, y: Float, half: Float, c: Int) {
        stroke.color = c
        stroke.strokeWidth = dp(2f)
        icon.reset()
        icon.moveTo(x - half, y - dp(3f)); icon.lineTo(x - half, y + dp(3f)); icon.lineTo(x + half, y + dp(3f)); icon.lineTo(x + half, y - dp(3f))
        canvas.drawPath(icon, stroke)
    }

    private fun drawSlideArrows(canvas: Canvas, x: Float, y: Float, s: Float, c: Int) {
        stroke.color = c
        stroke.strokeWidth = s * 0.5f
        icon.reset()
        icon.moveTo(x - s * 1.4f, y - s); icon.lineTo(x - s * 2.4f, y); icon.lineTo(x - s * 1.4f, y + s)
        icon.moveTo(x + s * 1.4f, y - s); icon.lineTo(x + s * 2.4f, y); icon.lineTo(x + s * 1.4f, y + s)
        canvas.drawPath(icon, stroke)
    }

    private fun drawShift(canvas: Canvas, x: Float, y: Float, s: Float, c: Int, filled: Boolean) {
        icon.reset()
        icon.moveTo(x, y - s); icon.lineTo(x + s, y); icon.lineTo(x + s * 0.45f, y); icon.lineTo(x + s * 0.45f, y + s * 0.7f)
        icon.lineTo(x - s * 0.45f, y + s * 0.7f); icon.lineTo(x - s * 0.45f, y); icon.lineTo(x - s, y); icon.close()
        if (filled) { fill.color = c; canvas.drawPath(icon, fill) } else { stroke.color = c; stroke.strokeWidth = dp(1.8f); canvas.drawPath(icon, stroke) }
    }

    private companion object {
        const val BAR_DP = 52f
        const val DRAWER_DP = 168f
        const val KEY_DP = 42f
        const val WAVE_HISTORY = 96
        const val DRAIN_AFTER_MS = 5000L
        const val MESSAGE_MS = 2600L
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        const val HOLD_MS = 350L
        const val PAD_HOLD_MS = 350L
        const val PAD_COL_DP = 8f
        const val PAD_LINE_DP = 22f
        const val SLIDE_START_DP = 12f
        const val SLIDE_STEP_DP = 9f
        const val DRAWER_RISE_MS = 220f
        const val DISABLED_ALPHA = 0.38f
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val TONAL = setOf(Act.PASTE, Act.CUT, Act.COPY, Act.DISCARD, Act.FINISH, Act.DELETE, Act.BACKSPACE)
    }
}
