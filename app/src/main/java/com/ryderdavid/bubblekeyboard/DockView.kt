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
import kotlin.math.sqrt

/**
 * The docked style: a one-row bar under the app, in the navigation area's surface color.
 *
 * At rest it holds ⋮, three equal pills in the middle (Cursor, Voice, ABC), then ⌫ and ↵. Each
 * pill grows into its tool: Voice stretches across the bar (✕ and ✓ grow in at the ends), ABC
 * swells up into the keyboard and lands as the space bar, Cursor swells up into the trackpad and
 * becomes Done. Every control springs between layouts; ones that leave shrink to nothing where
 * they stand, ones that arrive grow out of nothing where they land.
 *
 * Hold ↵ and ⌫ turns into space (slide onto it); hold ⌫ and ↵ turns into delete-all.
 */
class DockView(context: Context, attrs: AttributeSet?) : View(context, attrs), KeyboardSurface {

    var listener: BubbleView.Listener? = null

    enum class Drawer { NONE, KEYS, PAD, MENU }
    var drawer = Drawer.NONE
        private set

    /** What a control does. */
    enum class Act {
        MORE, CURSOR, MIC, KEYBOARD, BACKSPACE, ENTER, SPACE_ALT, CLEAR_ALL, DISCARD, FINISH,
        HIDE, COMMA, PERIOD, SPACE, LETTER, SHIFT, KEY_BACKSPACE,
        CHIP_CUT, CHIP_COPY, CHIP_PASTE, CHIP_DELETE, PAD,
        TILE_SETTINGS, TILE_SWITCH, TILE_BUBBLE, TILE_COST, PANEL,
    }

    /** A control where it should be now: [id] ties it to its animation across layouts. */
    private class Item(val id: String, val act: Act, val rect: RectF, val alpha: Float = 1f, val corner: Float = -1f, val ch: Char = ' ')

    /** Where a control is drawn right now, springing toward its [Item]. */
    private class Anim(val id: String, var act: Act) {
        val v = FloatArray(6) // left, top, right, bottom, corner, alpha
        val vel = FloatArray(6)
        val target = FloatArray(6)
        var present = true
        val rect = RectF()
    }

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
            endTalk(cancel = false, polish = false)
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
        if (talking) endTalk(cancel = true, polish = false)
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        removeCallbacks(altRunnable)
        alt = Alt.NONE
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

    /** ✓ (and a tap on the pill) always polishes; the silence timeout follows the setting. */
    private fun endTalk(cancel: Boolean, polish: Boolean) {
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        if (cancel) listener?.onTalkEnd(true) else listener?.onTalkFinish(polish)
        invalidate()
    }

    @VisibleForTesting internal val testTalking get() = talking

    // ---------------------------------------------------------------- geometry

    private var bottomInset = 0
    private var insetOverride = -1
    private val barH get() = dp(BAR_DP)
    private val drawerPx get() = dp(DRAWER_DP)
    /** The drawer's room stays while a closing panel shrinks back into its pill. */
    private var drawerRoom = false
    private val barTop get() = height - bottomInset - barH

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
        if (insetOverride >= 0) return
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
        if (v != bottomInset) { bottomInset = v; requestLayout(); invalidate() }
    }

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
        val room = drawer != Drawer.NONE || drawerRoom
        setMeasuredDimension(w, ((if (room) drawerPx else 0f) + barH + bottomInset).toInt())
    }

    private fun setDrawer(d: Drawer) {
        if (drawer == d) return
        val opening = drawer == Drawer.NONE
        drawer = d
        if (d == Drawer.KEYS) autoCap = listener?.shouldCapitalize() == true
        if (d == Drawer.MENU) costText = runCatching { UsageLog.formatUsd(UsageLog.of(context).last(24 * 30).totalUsd) }.getOrDefault("")
        if (d != Drawer.NONE) {
            if (opening && !drawerRoom) { drawerRoom = true; requestLayout() }
        }
        invalidate()
    }
    private var costText = ""

    // ---------------------------------------------------------------- layout (y is relative to the bar's top)

    private val items = mutableListOf<Item>()
    private val drawerItems = mutableListOf<Item>()

    private fun rect(l: Float, t: Float, w: Float, h: Float) = RectF(l, t, l + w, t + h)

    private fun layoutAll() {
        items.clear()
        drawerItems.clear()
        val w = width.toFloat()
        if (w <= 0f) return
        val pad = dp(6f)
        val gap = dp(5f)
        val b = dp(40f)
        val y = (barH - b) / 2
        // the bar at rest: ⋮ · Cursor Voice ABC · ⌫ ↵
        val more = rect(pad, y, b, b)
        val enter = rect(w - pad - b, y, b, b)
        val bksp = rect(enter.left - gap - b, y, b, b)
        val pl = more.right + gap
        val pr = bksp.left - gap
        val pw = (pr - pl - 2 * gap) / 3
        val cursorPill = rect(pl, y, pw, b)
        val voicePill = rect(pl + pw + gap, y, pw, b)
        val abcPill = rect(pl + 2 * (pw + gap), y, pw, b)
        val busyVoice = voice == BubbleView.Voice.CLEANING || voice == BubbleView.Voice.MESSAGE

        when {
            talking -> {
                items.add(Item("x", Act.DISCARD, rect(pad, y, b, b)))
                items.add(Item("voice", Act.MIC, RectF(pad + b + gap, y, w - pad - b - gap, y + b)))
                items.add(Item("check", Act.FINISH, rect(w - pad - b, y, b, b), corner = dp(12f)))
            }
            busyVoice && drawer != Drawer.KEYS -> items.add(Item("voice", Act.MIC, RectF(pad, y, w - pad, y + b)))
            drawer == Drawer.KEYS -> {
                // ⋮ , [space] . ⌄ mic ↵ — ABC lands as the space bar with its ⌄ where the ABC pill
                // ended (one spot opens and closes the keyboard), Voice shrinks into a mic where ⌫ was
                val small = b * 0.72f
                items.add(Item("more", Act.MORE, more))
                val mic = RectF(bksp)
                items.add(Item("voice", Act.MIC, mic))
                val hide = rect(abcPill.right - b, y, b, b)
                val comma = rect(more.right + gap, y, small, b)
                val period = rect(hide.left - gap - small, y, small, b)
                items.add(Item("comma", Act.COMMA, comma))
                items.add(Item("abc", Act.SPACE, RectF(comma.right + gap, y, period.left - gap, y + b)))
                items.add(Item("period", Act.PERIOD, period))
                items.add(Item("hide", Act.HIDE, hide))
                items.add(Item("enter", Act.ENTER, enter))
            }
            else -> {
                items.add(Item("more", Act.MORE, more))
                items.add(Item("cursor", Act.CURSOR, cursorPill))
                items.add(Item("voice", Act.MIC, voicePill))
                items.add(Item("abc", Act.KEYBOARD, abcPill))
                items.add(Item("bksp", if (alt == Alt.SPACE) Act.SPACE_ALT else Act.BACKSPACE, bksp))
                items.add(Item("enter", if (alt == Alt.CLEAR) Act.CLEAR_ALL else Act.ENTER, enter))
            }
        }

        // the panels: each rests, invisible, on the control it grows out of
        val top = -drawerPx
        val keysOpen = drawer == Drawer.KEYS
        items.add(0, Item("keysPanel", Act.PANEL, if (keysOpen) RectF(dp(4f), top + dp(4f), w - dp(4f), -dp(2f)) else RectF(abcPill), if (keysOpen) 1f else 0f, if (keysOpen) dp(24f) else b / 2))
        val padOpen = drawer == Drawer.PAD
        items.add(0, Item("padPanel", Act.PANEL, if (padOpen) RectF(dp(10f), top + dp(54f), w - dp(10f), -dp(6f)) else RectF(cursorPill), if (padOpen) 1f else 0f, if (padOpen) dp(24f) else b / 2))
        val menuOpen = drawer == Drawer.MENU
        items.add(0, Item("menuPanel", Act.PANEL, if (menuOpen) RectF(dp(4f), top + dp(4f), w - dp(4f), -dp(2f)) else RectF(more), if (menuOpen) 1f else 0f, if (menuOpen) dp(24f) else b / 2))

        when (drawer) {
            Drawer.KEYS -> layoutKeys(w, top)
            Drawer.PAD -> layoutPad(w, top)
            Drawer.MENU -> layoutMenu(w, top)
            Drawer.NONE -> Unit
        }
    }

    private fun layoutKeys(w: Float, top: Float) {
        val side = dp(5f)
        val gap = dp(5f)
        val kh = dp(KEY_DP)
        val kw = (w - 2 * side - gap * 9) / 10
        var y = top + dp(12f)
        for ((r, row) in ROWS.withIndex()) {
            if (r < 2) {
                val rowW = row.length * kw + (row.length - 1) * gap
                var x = (w - rowW) / 2
                for (ch in row) { drawerItems.add(Item("k$ch", Act.LETTER, rect(x, y, kw, kh), ch = ch)); x += kw + gap }
            } else {
                val wide = (w - 2 * side - row.length * kw - (row.length + 1) * gap) / 2
                var x = side
                drawerItems.add(Item("shift", Act.SHIFT, rect(x, y, wide, kh))); x += wide + gap
                for (ch in row) { drawerItems.add(Item("k$ch", Act.LETTER, rect(x, y, kw, kh), ch = ch)); x += kw + gap }
                drawerItems.add(Item("kbksp", Act.KEY_BACKSPACE, rect(x, y, wide, kh)))
            }
            y += kh + dp(8f)
        }
    }

    private fun layoutPad(w: Float, top: Float) {
        val side = dp(10f)
        val ch = dp(36f)
        val gap = dp(8f)
        val cw = (w - 2 * side - 3 * gap) / 4
        var x = side
        for (a in listOf(Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE)) {
            drawerItems.add(Item(a.name, a, rect(x, top + dp(10f), cw, ch)))
            x += cw + gap
        }
        drawerItems.add(Item("pad", Act.PAD, RectF(side, top + dp(54f), w - side, -dp(6f))))
    }

    private fun layoutMenu(w: Float, top: Float) {
        val side = dp(12f)
        val gap = dp(8f)
        val tw = (w - 2 * side - gap) / 2
        val th = (drawerPx - dp(14f) - dp(10f) - gap) / 2
        for ((i, a) in listOf(Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_BUBBLE, Act.TILE_COST).withIndex()) {
            val x = side + (i % 2) * (tw + gap)
            val y = top + dp(14f) + (i / 2) * (th + gap)
            drawerItems.add(Item(a.name, a, rect(x, y, tw, th)))
        }
    }

    @VisibleForTesting internal fun testItem(act: Act, ch: Char = ' '): RectF? {
        layoutAll()
        val it = (drawerItems + items).firstOrNull { it.act == act && it.alpha > 0f && (act != Act.LETTER || it.ch == ch) } ?: return null
        return RectF(it.rect).apply { offset(0f, barTop) }
    }
    @VisibleForTesting internal fun testHas(act: Act): Boolean = testItem(act) != null
    /** Where control [id] is drawn right now (mid-animation included), in view coordinates. */
    @VisibleForTesting internal fun testAnimRect(id: String): RectF? = anims[id]?.rect?.let { RectF(it).apply { offset(0f, barTop) } }
    @VisibleForTesting internal fun testFrame() { layoutAll(); step(SystemClock.uptimeMillis()) }

    // ---------------------------------------------------------------- motion

    private val anims = LinkedHashMap<String, Anim>()
    private var lastFrame = 0L

    /** Springs every control toward its place; true while anything is still moving. */
    private fun step(now: Long): Boolean {
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 1f / 30f)
        lastFrame = now
        val seen = HashSet<String>()
        for (it in items) {
            seen.add(it.id)
            val a = anims.getOrPut(it.id) {
                // a newcomer grows out of nothing where it lands (a panel, out of its pill)
                Anim(it.id, it.act).also { n ->
                    n.v[0] = it.rect.left; n.v[1] = it.rect.top; n.v[2] = it.rect.right; n.v[3] = it.rect.bottom
                    n.v[4] = if (it.corner >= 0f) it.corner else it.rect.height() / 2
                    n.v[5] = if (it.act == Act.PANEL) it.alpha else 0f
                }
            }
            a.act = it.act
            a.present = true
            a.target[0] = it.rect.left; a.target[1] = it.rect.top; a.target[2] = it.rect.right; a.target[3] = it.rect.bottom
            a.target[4] = if (it.corner >= 0f) it.corner else it.rect.height() / 2
            a.target[5] = it.alpha
        }
        for ((id, a) in anims) if (id !in seen) { a.present = false; a.target[5] = 0f }
        var moving = false
        val k = SPRING_K
        val c = 2f * SPRING_ZETA * sqrt(k)
        for (a in anims.values) {
            for (i in 0 until 6) {
                if (dt == 0f) continue
                val damping = if (i == 5) 2f * sqrt(k) else c // alpha never overshoots
                a.vel[i] += (k * (a.target[i] - a.v[i]) - damping * a.vel[i]) * dt
                a.v[i] += a.vel[i] * dt
                if (abs(a.target[i] - a.v[i]) > 0.5f || abs(a.vel[i]) > 1f) moving = true else { a.v[i] = a.target[i]; a.vel[i] = 0f }
            }
            a.v[5] = a.v[5].coerceIn(0f, 1f)
            a.rect.set(a.v[0], a.v[1], a.v[2], a.v[3])
        }
        anims.entries.removeAll { !it.value.present && it.value.v[5] <= 0.01f && it.value.target[5] == 0f }
        // the drawer's room goes once nothing is left in it
        if (drawer == Drawer.NONE && drawerRoom) {
            val busy = anims.values.any { it.rect.top < -dp(2f) && it.v[5] > 0.02f }
            if (!busy) { drawerRoom = false; requestLayout() }
        }
        return moving
    }

    /** Jump straight to the end of every move (tests, and the first frame). */
    @VisibleForTesting internal fun settle() {
        layoutAll()
        lastFrame = 0L
        step(SystemClock.uptimeMillis())
        for (a in anims.values) { System.arraycopy(a.target, 0, a.v, 0, 6); a.vel.fill(0f); a.rect.set(a.v[0], a.v[1], a.v[2], a.v[3]) }
        anims.entries.removeAll { !it.value.present }
        if (drawer == Drawer.NONE && drawerRoom) { drawerRoom = false; requestLayout() }
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
            if (p.act != Act.BACKSPACE && p.act != Act.KEY_BACKSPACE) return
            if (repeatHalted) return
            listener?.onKey(BubbleView.Key.DELETE, true)
            postDelayed(this, REPEAT_MS)
        }
    }
    private var repeatHalted = false

    private val commaHoldRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (p.act != Act.COMMA) return@Runnable
        pressed = null
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setDrawer(Drawer.PAD)
    }

    // ---------------------------------------------------------------- hold ↵ for space, ⌫ for delete-all

    private enum class Alt { NONE, SPACE, CLEAR }
    private var alt = Alt.NONE
    private var altHot = false
    private val altRunnable = Runnable {
        val p = pressed ?: return@Runnable
        alt = when (p.act) { Act.ENTER -> Alt.SPACE; Act.BACKSPACE -> Alt.CLEAR; else -> return@Runnable }
        altHot = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }
    @VisibleForTesting internal val testAlt get() = alt.name

    // ---------------------------------------------------------------- the trackpad

    private var padActive = false
    private var padSelecting = false
    private var padMoved = false
    private var padX = 0f
    private var padY = 0f
    private var padAccX = 0f
    private var padAccY = 0f
    private var padPointer = 0
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

    /** A different finger drives the trackpad from here; where it is now is its starting point. */
    private fun padHandOff(id: Int, x: Float, y: Float) {
        padPointer = id
        padX = x
        padY = y
        padMoved = true // more than one finger is never a tap
        removeCallbacks(padHoldRunnable)
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
        val oy = barTop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { padPointer = e.getPointerId(0); onDown(e.x, e.y - oy) }
            MotionEvent.ACTION_MOVE -> {
                if (pressed?.act == Act.PAD) {
                    val i = e.findPointerIndex(padPointer)
                    if (i >= 0) padMove(e.getX(i), e.getY(i) - oy)
                } else onMove(e.x, e.y - oy)
            }
            // on the trackpad another finger takes over the dragging while the first stays down,
            // so a selection can run longer than one sweep of the thumb
            MotionEvent.ACTION_POINTER_DOWN -> if (pressed?.act == Act.PAD) {
                val i = e.actionIndex
                padHandOff(e.getPointerId(i), e.getX(i), e.getY(i) - oy)
            }
            MotionEvent.ACTION_POINTER_UP -> if (pressed?.act == Act.PAD && e.getPointerId(e.actionIndex) == padPointer) {
                val keep = (0 until e.pointerCount).firstOrNull { it != e.actionIndex }
                if (keep != null) padHandOff(e.getPointerId(keep), e.getX(keep), e.getY(keep) - oy)
            }
            MotionEvent.ACTION_UP -> onUp(e.x, e.y - oy)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun hit(x: Float, y: Float): Item? {
        layoutAll()
        val slop = dp(4f)
        val all = drawerItems + items.filter { it.act != Act.PANEL }
        return all.firstOrNull { it.act == Act.PAD && it.rect.contains(x, y) }
            ?: all.minByOrNull { d2(it.rect, x, y) }?.takeIf { d2(it.rect, x, y) <= slop * slop }
    }

    private fun d2(r: RectF, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right))
        val dy = max(0f, max(r.top - y, y - r.bottom))
        return dx * dx + dy * dy
    }

    private fun rectOf(id: String): RectF? { layoutAll(); return items.firstOrNull { it.id == id }?.rect }

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y
        sliding = false
        consumed = false
        repeatHalted = false
        alt = Alt.NONE
        val it = hit(x, y)
        pressed = it
        if (it == null) return
        when (it.act) {
            Act.PAD -> { padDown(x, y); return }
            Act.BACKSPACE -> {
                listener?.onKey(BubbleView.Key.DELETE, false)
                postDelayed(repeatRunnable, REPEAT_START_MS)
                postDelayed(altRunnable, ALT_HOLD_MS)
            }
            Act.KEY_BACKSPACE -> { listener?.onKey(BubbleView.Key.DELETE, false); postDelayed(repeatRunnable, REPEAT_START_MS) }
            Act.ENTER -> if (drawer != Drawer.KEYS) postDelayed(altRunnable, ALT_HOLD_MS)
            Act.COMMA -> postDelayed(commaHoldRunnable, HOLD_MS)
            else -> Unit
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun onMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (alt != Alt.NONE) {
            // held ↵ or ⌫: is the finger on the key that changed?
            val r = rectOf(if (alt == Alt.SPACE) "bksp" else "enter") ?: return
            val hot = d2(r, x, y) <= dp(10f) * dp(10f)
            if (hot != altHot) { altHot = hot; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); invalidate() }
            if (alt == Alt.CLEAR && d2(p.rect, x, y) > dp(8f) * dp(8f)) repeatHalted = true
            return
        }
        // a sideways slide on the space bar, or on the idle Voice pill, moves the cursor
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
            val n = hit(x, y)
            if (n != null && n.act == Act.LETTER && n.ch != p.ch) { pressed = n; invalidate() }
            return
        }
        if (d2(p.rect, x, y) > dp(16f) * dp(16f)) {
            removeCallbacks(repeatRunnable)
            removeCallbacks(commaHoldRunnable)
            removeCallbacks(altRunnable)
            pressed = null
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed
        pressed = null
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        removeCallbacks(altRunnable)
        if (p == null) { alt = Alt.NONE; invalidate(); return }
        if (p.act == Act.PAD) { padUp(); return }
        if (alt != Alt.NONE) {
            val a = alt
            alt = Alt.NONE
            if (altHot) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (a == Alt.SPACE) listener?.onKey(BubbleView.Key.SPACE, false) else listener?.onDeleteAll()
            }
            altHot = false
            invalidate()
            return
        }
        if (consumed || sliding) { sliding = false; invalidate(); return }
        act(p)
        invalidate()
    }

    private fun onCancel() {
        removeCallbacks(repeatRunnable)
        removeCallbacks(commaHoldRunnable)
        removeCallbacks(altRunnable)
        if (padActive) endPad()
        alt = Alt.NONE
        pressed = null
        sliding = false
        invalidate()
    }

    private fun act(p: Item) {
        val l = listener
        when (p.act) {
            Act.MIC -> when {
                talking -> endTalk(cancel = false, polish = true)
                voice == BubbleView.Voice.CLEANING -> Unit
                else -> startTalk()
            }
            Act.FINISH -> if (talking) endTalk(cancel = false, polish = true)
            Act.DISCARD -> if (talking) endTalk(cancel = true, polish = false)
            Act.CHIP_PASTE -> if (canPaste) l?.onEdit(BubbleView.EditAction.PASTE)
            Act.CHIP_CUT -> if (hasSelection) l?.onEdit(BubbleView.EditAction.CUT)
            Act.CHIP_COPY -> if (hasSelection) l?.onEdit(BubbleView.EditAction.COPY)
            Act.CHIP_DELETE -> l?.onEdit(BubbleView.EditAction.DELETE)
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
            Act.BACKSPACE, Act.KEY_BACKSPACE -> Unit // done on press
            Act.TILE_SETTINGS, Act.TILE_COST -> { setDrawer(Drawer.NONE); l?.onOpenSettings() }
            Act.TILE_SWITCH -> { setDrawer(Drawer.NONE); l?.onSwitchKeyboard() }
            Act.TILE_BUBBLE -> { setDrawer(Drawer.NONE); l?.onSwitchStyle(docked = false) }
            Act.PAD, Act.PANEL, Act.SPACE_ALT, Act.CLEAR_ALL -> Unit
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
        val now = SystemClock.uptimeMillis()
        layoutAll()
        if (anims.isEmpty()) settle()
        val moving = step(now)
        fill.color = pal.disc
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // a hairline where the app ends and the keyboard begins
        fill.color = pal.outline
        canvas.drawRect(0f, 0f, width.toFloat(), max(1f, dp(1f)), fill)
        canvas.save()
        canvas.translate(0f, barTop)
        // panels first, then what's inside them, then the bar
        for ((id, a) in anims) if (a.act == Act.PANEL) drawPanel(canvas, id, a)
        val open = when (drawer) {
            Drawer.KEYS -> anims["keysPanel"]
            Drawer.PAD -> anims["padPanel"]
            Drawer.MENU -> anims["menuPanel"]
            Drawer.NONE -> null
        }
        val p = open?.let {
            val full = it.target[3] - it.target[1]
            ((it.rect.height() - dp(40f)) / max(1f, full - dp(40f))).coerceIn(0f, 1f)
        } ?: 0f
        val contentAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (contentAlpha > 0f) for (it in drawerItems) drawDrawerItem(canvas, it, contentAlpha)
        // leaving controls under the ones that stay or arrive
        for ((_, a) in anims) if (a.act != Act.PANEL && !a.present) drawControl(canvas, a, now)
        for ((_, a) in anims) if (a.act != Act.PANEL && a.present) drawControl(canvas, a, now)
        canvas.restore()
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawPanel(canvas: Canvas, id: String, a: Anim) {
        val al = a.v[5]
        if (al <= 0.01f) return
        val pal = palette
        fill.color = alpha(if (id == "padPanel") pal.bloomKey else pal.cardinal, al * if (id == "padPanel") 1f else 0.55f)
        canvas.drawRoundRect(a.rect, a.v[4], a.v[4], fill)
    }

    /** One bar control, at its animated place; leaving or arriving ones are scaled by their alpha. */
    private fun drawControl(canvas: Canvas, a: Anim, now: Long) {
        val al = a.v[5]
        if (al <= 0.01f) return
        val r = a.rect
        canvas.save()
        val s = 0.35f + 0.65f * al
        canvas.scale(s, s, r.centerX(), r.centerY())
        val down = pressed?.id == a.id
        when (a.act) {
            Act.MIC -> drawVoicePill(canvas, r, a.v[4], al, now, down)
            Act.CURSOR -> drawPill(canvas, r, a.v[4], al, down, Act.CURSOR)
            Act.KEYBOARD -> drawPill(canvas, r, a.v[4], al, down, Act.KEYBOARD)
            Act.SPACE -> {
                val pal = palette
                fill.color = alpha(if (down) pal.pressed else pal.bloomKey, al)
                canvas.drawRoundRect(r, a.v[4], a.v[4], fill)
                val c = alpha(if (down) pal.onPressed else pal.onCardinal, al)
                if (sliding && down) drawSlideArrows(canvas, r.centerX(), r.centerY(), dp(4f), c) else drawSpace(canvas, r.centerX(), r.centerY(), dp(18f), c)
            }
            else -> drawRound(canvas, a.act, r, a.v[4], al, down)
        }
        canvas.restore()
    }

    private fun drawRound(canvas: Canvas, act: Act, r: RectF, corner: Float, al: Float, down: Boolean) {
        val pal = palette
        val hotAlt = (act == Act.SPACE_ALT || act == Act.CLEAR_ALL) && altHot
        val bg = when {
            down || hotAlt -> pal.pressed
            act == Act.SPACE_ALT -> pal.center
            act == Act.CLEAR_ALL -> pal.discard
            act == Act.ENTER -> pal.center
            act == Act.MORE && drawer == Drawer.MENU -> pal.pressed
            act == Act.HIDE -> Color.TRANSPARENT
            act in TONAL -> pal.diagonal
            else -> Color.TRANSPARENT
        }
        val fg = when {
            down || hotAlt -> pal.onPressed
            act == Act.SPACE_ALT || act == Act.ENTER -> pal.onCenter
            act == Act.CLEAR_ALL -> pal.onDiscard
            act == Act.MORE && drawer == Drawer.MENU -> pal.onPressed
            act in TONAL -> pal.onDiagonal
            else -> pal.onCardinal
        }
        if (Color.alpha(bg) > 0) {
            fill.color = alpha(bg, al)
            canvas.drawRoundRect(r, corner, corner, fill)
        }
        drawGlyph(canvas, act, r.centerX(), r.centerY(), dp(22f), alpha(fg, al))
    }

    /** Cursor and ABC at rest: tonal pills with an icon and, when there's room, their name. */
    private fun drawPill(canvas: Canvas, r: RectF, corner: Float, al: Float, down: Boolean, act: Act) {
        val pal = palette
        val done = act == Act.CURSOR && drawer == Drawer.PAD
        val bg = when { down -> pal.pressed; done -> pal.pressed; else -> pal.diagonal }
        val fg = when { down || done -> pal.onPressed; else -> pal.onDiagonal }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, corner, corner, fill)
        val label = context.getString(when {
            done -> R.string.dock_done
            act == Act.CURSOR -> R.string.dock_cursor
            else -> R.string.dock_abc
        })
        val c = alpha(fg, al)
        text.textSize = dp(14f)
        val iw = dp(20f)
        val showLabel = r.width() >= iw + dp(6f) + text.measureText(label) + dp(14f)
        val total = if (showLabel) iw + dp(6f) + text.measureText(label) else iw
        val x0 = r.centerX() - total / 2
        val glyph = if (done) Act.FINISH else act
        if (act == Act.KEYBOARD && showLabel) {
            // ABC says it in letters; the icon would only repeat it
            text.color = c
            canvas.drawText(label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            return
        }
        drawGlyph(canvas, glyph, x0 + iw / 2, r.centerY(), iw, c)
        if (showLabel) {
            text.color = c
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(label, x0 + iw + dp(6f), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawVoicePill(canvas: Canvas, r: RectF, rad: Float, al: Float, now: Long, down: Boolean) {
        val pal = palette
        val cleaning = voice == BubbleView.Voice.CLEANING && !talking
        val message = voice == BubbleView.Voice.MESSAGE && !talking
        val small = drawer == Drawer.KEYS && !talking && !cleaning && !message
        val bg = when {
            down -> pal.onCenter
            cleaning -> pal.center
            message -> pal.discard
            small -> pal.diagonal
            else -> pal.pressed
        }
        val fg = when {
            down -> pal.center
            cleaning -> pal.onCenter
            message -> pal.onDiscard
            small -> pal.onDiagonal
            else -> pal.onPressed
        }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        val cx = r.centerX()
        val cy = r.centerY()
        text.color = alpha(fg, al)
        text.textSize = dp(14f)
        val base = cy - (text.ascent() + text.descent()) / 2
        when {
            cleaning -> {
                canvas.save()
                canvas.clipRect(r)
                canvas.drawText(context.getString(R.string.dock_polishing), cx, base, text)
                val p = ((now % 1100L) / 1100f)
                val sw = r.width() * 0.25f
                val sx = r.left - sw + (r.width() + sw) * p
                tmp.set(sx, r.bottom - dp(3f), sx + sw, r.bottom)
                fill.color = alpha(fg, al)
                canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
                canvas.restore()
                postInvalidateOnAnimation()
            }
            message -> {
                var t = voiceText
                while (t.isNotEmpty() && text.measureText(t) > r.width() - dp(24f)) t = t.dropLast(1)
                canvas.drawText(t, cx, base, text)
            }
            talking -> {
                canvas.save()
                canvas.clipRect(r)
                val showText = !voiceInField && voiceText.isNotBlank()
                // quiet for a while: the pill's color drains away right to left until it stops
                val drain = silenceDrain(now)
                if (drain < 1f) {
                    fill.color = alpha(pal.center, al)
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.save()
                    canvas.clipRect(r.left, r.top, r.left + r.width() * drain, r.bottom)
                    fill.color = alpha(bg, al)
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
                if (wx1 > wx0) {
                    val bars = ((wx1 - wx0 + bg2) / (bw + bg2)).toInt().coerceIn(1, levels.size)
                    val off = (wx1 - wx0) - (bars * bw + (bars - 1) * bg2)
                    for (i in 0 until bars) {
                        val lv = levels[levels.size - bars + i]
                        val idle = 0.12f + 0.06f * sin((now / 140.0 + i).toFloat())
                        val h = dp(22f) * max(lv, idle)
                        val bx = wx0 + off + i * (bw + bg2)
                        tmp.set(bx, cy - h / 2, bx + bw, cy + h / 2)
                        val drained = drain < 1f && bx > r.left + r.width() * drain
                        fill.color = alpha(if (drained) pal.onCenter else fg, al * if (showText) 0.18f else 1f)
                        canvas.drawRoundRect(tmp, bw / 2, bw / 2, fill)
                    }
                }
                text.color = alpha(if (drain < 1f && right > r.left + r.width() * drain) pal.onCenter else fg, al)
                canvas.drawText(label, right, base, text)
                if (showText) {
                    val room = right - labelW - dp(10f) - wx0
                    text.textAlign = Paint.Align.LEFT
                    text.color = alpha(fg, al)
                    var t = voiceText
                    while (t.isNotEmpty() && text.measureText(t) > room) t = t.substring(1)
                    canvas.drawText(t, wx0, base, text)
                }
                text.textAlign = Paint.Align.CENTER
                canvas.restore()
                postInvalidateOnAnimation()
            }
            else -> {
                val label = if (!small) context.getString(R.string.dock_voice) else ""
                val iw = dp(20f)
                val showLabel = label.isNotEmpty() && r.width() >= iw + dp(6f) + text.measureText(label) + dp(14f)
                val total = if (showLabel) iw + dp(6f) + text.measureText(label) else iw
                val x0 = cx - total / 2
                drawMic(canvas, x0 + iw / 2, cy, iw, alpha(fg, al))
                if (showLabel) {
                    text.textAlign = Paint.Align.LEFT
                    canvas.drawText(label, x0 + iw + dp(6f), base, text)
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

    private fun drawDrawerItem(canvas: Canvas, it: Item, al: Float) {
        val pal = palette
        val r = it.rect
        val down = pressed === it || (pressed?.id == it.id)
        when (it.act) {
            Act.LETTER, Act.SHIFT, Act.KEY_BACKSPACE -> {
                val fn = it.act != Act.LETTER
                val shiftOn = it.act == Act.SHIFT && (capsLock || shiftOnce)
                val bg = when { down -> pal.pressed; shiftOn -> pal.center; fn -> pal.cardinal; else -> pal.bloomKey }
                val fg = when { down -> pal.onPressed; shiftOn -> pal.onCenter; fn -> pal.onCardinal; else -> pal.onBloomKey }
                val rad = min(r.width(), r.height()) / 2
                fill.color = alpha(bg, al)
                if (down) { tmp.set(r); tmp.inset(-dp(2f), -dp(2f)); canvas.drawRoundRect(tmp, rad + dp(2f), rad + dp(2f), fill) } else canvas.drawRoundRect(r, rad, rad, fill)
                val c = alpha(fg, al)
                when (it.act) {
                    Act.LETTER -> {
                        text.color = c
                        text.textSize = dp(20f)
                        val s = if (caps) it.ch.uppercaseChar().toString() else it.ch.toString()
                        canvas.drawText(s, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                    }
                    Act.SHIFT -> drawShift(canvas, r.centerX(), r.centerY(), dp(9f), c, capsLock)
                    else -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.BACKSPACE, r.centerX(), r.centerY(), dp(22f), fill) }
                }
            }
            Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE -> {
                val enabled = when (it.act) {
                    Act.CHIP_CUT, Act.CHIP_COPY -> hasSelection
                    Act.CHIP_PASTE -> canPaste
                    else -> true
                }
                val a = al * if (enabled) 1f else DISABLED_ALPHA
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
                text.textSize = dp(13f)
                text.color = alpha(pal.onCardinal, 0.8f * al)
                val msg = context.getString(if (padSelecting) R.string.dock_pad_selecting_more else R.string.dock_pad_hint)
                canvas.drawText(msg, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                if (padActive) {
                    val px = padX.coerceIn(r.left, r.right)
                    val py = padY.coerceIn(r.top, r.bottom)
                    fill.color = alpha(pal.pressed, 0.22f * al)
                    canvas.drawCircle(px, py, dp(26f), fill)
                    fill.color = alpha(pal.pressed, al)
                    canvas.drawCircle(px, py, dp(6f), fill)
                }
            }
            Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_BUBBLE, Act.TILE_COST -> {
                fill.color = alpha(if (down) pal.pressed else pal.bloomKey, al)
                canvas.drawRoundRect(r, dp(20f), dp(20f), fill)
                val fg = alpha(if (down) pal.onPressed else pal.onBloomKey, al)
                val sub = alpha(if (down) pal.onPressed else pal.onCardinal, al)
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
            Act.BACKSPACE, Act.KEY_BACKSPACE -> Icons.draw(canvas, Icons.Glyph.BACKSPACE, x, y, size, fill)
            Act.CURSOR -> Icons.draw(canvas, Icons.Glyph.TEXT_CURSOR, x, y, size, fill)
            Act.DISCARD -> Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, size, fill)
            Act.KEYBOARD -> drawKeyboard(canvas, x, y, size, c)
            Act.MORE -> for (i in -1..1) canvas.drawCircle(x, y + i * size * 0.27f, size * 0.09f, fill)
            Act.FINISH -> strokePath(canvas, x, y, size, c) { m, l -> m(5f, 12.5f); l(9.5f, 17f); l(19f, 7.5f) }
            Act.HIDE -> strokePath(canvas, x, y, size, c) { m, l -> m(6f, 9.5f); l(12f, 15.5f); l(18f, 9.5f) }
            Act.ENTER -> strokePath(canvas, x, y, size, c) { m, l -> m(19f, 5f); l(19f, 13f); l(6f, 13f); m(10f, 9f); l(6f, 13f); l(10f, 17f) }
            Act.SPACE_ALT -> drawSpace(canvas, x, y, size * 0.42f, c)
            Act.CLEAR_ALL -> {
                // a backspace with a bar to its left: everything, back to the start
                Icons.draw(canvas, Icons.Glyph.BACKSPACE, x + size * 0.08f, y, size * 0.86f, fill)
                stroke.color = c
                stroke.strokeWidth = size * 0.09f
                canvas.drawLine(x - size * 0.46f, y - size * 0.3f, x - size * 0.46f, y + size * 0.3f, stroke)
            }
            Act.COMMA, Act.PERIOD -> {
                text.color = c
                text.textSize = size * 1.05f
                canvas.drawText(if (act == Act.COMMA) "," else ".", x, y - (text.ascent() + text.descent()) / 2, text)
            }
            Act.MIC -> drawMic(canvas, x, y, size, c)
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
        const val DRAWER_DP = 172f
        const val KEY_DP = 42f
        const val WAVE_HISTORY = 96
        const val DRAIN_AFTER_MS = 5000L
        const val MESSAGE_MS = 2600L
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        const val HOLD_MS = 350L
        const val ALT_HOLD_MS = 350L
        const val PAD_HOLD_MS = 350L
        const val PAD_COL_DP = 8f
        const val PAD_LINE_DP = 22f
        const val SLIDE_START_DP = 12f
        const val SLIDE_STEP_DP = 9f
        const val DISABLED_ALPHA = 0.38f
        /** One spring for every move: a slight overshoot, settled in about a third of a second. */
        const val SPRING_K = 420f
        const val SPRING_ZETA = 0.72f
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val TONAL = setOf(Act.DISCARD, Act.FINISH, Act.BACKSPACE)
    }
}
