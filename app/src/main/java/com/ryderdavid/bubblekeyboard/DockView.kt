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

    var listener: Bar.Listener? = null

    enum class Drawer { NONE, PAD, MENU }
    var drawer = Drawer.NONE
        private set

    /** What a control does. */
    enum class Act {
        MORE, CURSOR, MIC, KEYBOARD, BACKSPACE, ENTER, SPACE_ALT, CLEAR_ALL, DISCARD, FINISH,
        CHIP_CUT, CHIP_COPY, CHIP_PASTE, CHIP_DELETE, PAD,
        /** While dictating: throw the dictation away and paste the clipboard instead. */
        TALK_PASTE,
        TILE_SETTINGS, TILE_SWITCH, TILE_COST, PANEL,
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
    override var clipPreview: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    override var voiceInField = false
        set(v) { field = v; invalidate() }
    override var voice = Bar.Voice.OFF
        private set
    private var voiceText = ""
    private var talking = false
    private var talkStartedAt = 0L
    private var silenceMs = Prefs.DEFAULT_SILENCE_TIMEOUT * 1000L
    private var silenceDeadline = 0L
    private val levels = FloatArray(WAVE_HISTORY)
    private var levelPeak = 0.08f

    private val hideVoiceRunnable = Runnable { hideVoice() }
    /** This quiet stretch has already asked for the pause cleanup. */
    private var quietSent = false
    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (!talking || silenceDeadline == 0L) return
            val now = SystemClock.uptimeMillis()
            val left = silenceDeadline - now
            val quietAt = silenceDeadline - silenceMs + Bar.quietMs(silenceMs)
            if (!quietSent && now >= quietAt) { quietSent = true; listener?.onQuiet() }
            if (left > 0) { postDelayed(this, if (quietSent) left else minOf(left, quietAt - now)); return }
            endTalk(cancel = false, polish = false)
        }
    }

    override fun showListening() {
        removeCallbacks(hideVoiceRunnable)
        voice = Bar.Voice.LISTENING
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
        voice = Bar.Voice.CLEANING
        voiceText = text
        invalidate()
    }

    /** What a tap on the message does, if anything. */
    private var messageAction: (() -> Unit)? = null

    override fun showMessage(message: String, onTap: (() -> Unit)?) {
        removeCallbacks(hideVoiceRunnable)
        voice = Bar.Voice.MESSAGE
        voiceText = message
        messageAction = onTap
        postDelayed(hideVoiceRunnable, if (onTap != null) MESSAGE_ACTION_MS else MESSAGE_MS)
        invalidate()
    }

    override fun hideVoice() {
        messageAction = null
        removeCallbacks(hideVoiceRunnable)
        voice = Bar.Voice.OFF
        voiceText = ""
        invalidate()
    }

    /** When speech was last heard (or dictation started): the hint flashes after 3 s of quiet. */
    private var lastHeardAt = 0L

    override fun heardSpeech() {
        lastHeardAt = SystemClock.uptimeMillis()
        if (talking && silenceDeadline != 0L) {
            silenceDeadline = SystemClock.uptimeMillis() + silenceMs
            quietSent = false
        }
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
        removeCallbacks(altRunnable)
        removeCallbacks(sendHoldRunnable)
        alt = Alt.NONE
        pressed = null
        setDrawer(Drawer.NONE)
        invalidate()
    }

    /** Seven equal pills at rest instead of three wide ones between ⋮ and ⌫. */
    private var equalBar = false
    /**
     * "Tiered" colors: one dark (primary) action at a time; modes tonal; keys neutral like the
     * keyboard's own keys; ↵ primary-container; ⋮ bare; an open mode (Done, ⋮) tertiary.
     */
    private var tiered = false
    /** Set for a settings-screen preview: draws this layout at rest, ignores touches and insets. */
    private var previewLayout: String? = null

    /** Turn this view into a still, untouchable picture of the bar at rest in [layout]. */
    private var previewColors: String? = null

    fun showAsPreview(layout: String, colors: String? = null) {
        previewLayout = layout
        previewColors = colors
        insetOverride = 0
        bottomInset = 0
        isClickable = false
        reloadSettings()
    }

    override fun reloadSettings() {
        palette = Palette(context)
        tiered = (previewColors ?: Prefs.get(context).getString(Prefs.BAR_COLORS, Prefs.DEFAULT_BAR_COLORS)) == Prefs.BAR_COLORS_TIERED
        equalBar = (previewLayout ?: Prefs.get(context).getString(Prefs.BAR_LAYOUT, Prefs.DEFAULT_BAR_LAYOUT)) == Prefs.BAR_LAYOUT_EQUAL
        requestLayout()
        silenceMs = Prefs.get(context).getInt(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT)
            .coerceIn(Prefs.SILENCE_TIMEOUT_RANGE) * 1000L
        invalidate()
    }

    // ---------------------------------------------------------------- talking

    override fun startVoice() = startTalk()

    private fun startTalk() {
        if (talking) return
        if (listener?.onTalkStart() == true) {
            talking = true
            talkStartedAt = SystemClock.uptimeMillis()
            lastHeardAt = talkStartedAt
            silenceDeadline = talkStartedAt + silenceMs
            quietSent = false
            removeCallbacks(silenceRunnable)
            postDelayed(silenceRunnable, Bar.quietMs(silenceMs))
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
        updateInset(WindowInsetsCompat.toWindowInsetsCompat(insets, this))
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateInset()
        requestApplyInsets()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // the keyboard window is reused between fields; re-read the insets each time it comes back
        if (visibility == VISIBLE) { updateInset(); requestApplyInsets() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateInset(); requestApplyInsets()
    }

    /**
     * How far the bar must sit above the bottom so the system's navigation (gesture handle, or
     * the back/home/recents and ⌄/switcher buttons) never draws on top of it. Insets are read
     * ignoring visibility, because while the window is being shown or the caption band is
     * hidden they can briefly report 0; and when they still say 0 in portrait, the system's own
     * navigation bar height is the floor.
     */
    private fun updateInset(given: WindowInsetsCompat? = null) {
        if (insetOverride >= 0) return
        val root = given ?: ViewCompat.getRootWindowInsets(this)
        fun bottom(type: Int): Int {
            root ?: return 0
            val shown = root.getInsets(type).bottom
            val any = if (type == WindowInsetsCompat.Type.captionBar()) 0
                else runCatching { root.getInsetsIgnoringVisibility(type).bottom }.getOrDefault(0)
            return maxOf(shown, any)
        }
        val nav = bottom(WindowInsetsCompat.Type.navigationBars())
        val mode = navMode()
        var v = maxOf(
            nav,
            bottom(WindowInsetsCompat.Type.captionBar()),
            bottom(WindowInsetsCompat.Type.tappableElement()),
            // before Android 15 the keyboard can't hide the system's ⌄/switcher band in gesture
            // navigation, so the bar sits above it (see BubbleView.imeNavFrameHeight)
            if (nav > 0 && Build.VERSION.SDK_INT < 35 && mode == NAV_GESTURE) sysDimen("navigation_bar_frame_height") else 0,
        )
        val c = resources.configuration
        val navAtBottom = c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT || c.smallestScreenWidthDp >= 600
        if (navAtBottom) v = maxOf(v, navFloor(mode))
        if (v != bottomInset) { bottomInset = v; requestLayout(); invalidate() }
    }

    /** The smallest the bottom gap can honestly be for this navigation mode. */
    private fun navFloor(mode: Int): Int = when (mode) {
        NAV_GESTURE -> if (Build.VERSION.SDK_INT < 35) sysDimen("navigation_bar_frame_height")
            else sysDimen("navigation_bar_gesture_height").takeIf { it > 0 } ?: sysDimen("navigation_bar_height")
        else -> sysDimen("navigation_bar_height")
    }

    private fun navMode(): Int = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0)
    }.getOrDefault(0)

    private fun sysDimen(name: String): Int {
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(name, "dimen", "android")
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
        if (d == Drawer.MENU) costText = runCatching { UsageLog.formatUsd(UsageLog.of(context).last(24 * 30).totalUsd) }.getOrDefault("")
        if (d != Drawer.NONE) {
            if (opening && !drawerRoom) { drawerRoom = true; requestLayout() }
        }
        invalidate()
    }
    private var costText = ""

    // ---------------------------------------------------------------- layout (y is relative to the bar's top)

    private val items = mutableListOf<Item>()
    /** Where the recording pill started (after the paste button, if any), so polishing stays put. */
    private var talkLeft = 0f
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
        // the bar at rest: ⋮ · Cursor ABC Voice · ⌫, the pills dead center; or, with equalBar,
        // seven equal pills: ⋮ space Cursor ABC Voice ⌫ ↵
        val ew = (w - 2 * pad - 6 * gap) / 7
        fun slot(i: Int) = rect(pad + i * (ew + gap), y, ew, b)
        val more = if (equalBar) slot(0) else rect(pad, y, b, b)
        val bksp = if (equalBar) slot(5) else rect(w - pad - b, y, b, b)
        val pl = more.right + gap
        val pr = bksp.left - gap
        val pw = (pr - pl - 2 * gap) / 3
        val cursorPill = if (equalBar) slot(2) else rect(pl, y, pw, b)
        val abcPill = if (equalBar) slot(3) else rect(pl + pw + gap, y, pw, b)
        val voicePill = if (equalBar) slot(4) else rect(pl + 2 * (pw + gap), y, pw, b)
        val busyVoice = voice == Bar.Voice.CLEANING || voice == Bar.Voice.MESSAGE

        when {
            talking -> {
                // [paste] · voice (tap it to finish) · ✕ · ABC: the voice pill is the "done" button
                val wide = 2 * b + gap
                val abc = rect(w - pad - wide, y, wide, b)
                val x = rect(abc.left - gap - b, y, b, b)
                var left = pad
                if (canPaste && !clipPreview.isNullOrBlank()) {
                    // two buttons wide, or three when the clip needs it (then it's ellipsized)
                    val paste = rect(pad, y, if (clipPillContentWidth() <= wide) wide else 3 * b + 2 * gap, b)
                    items.add(Item("talkPaste", Act.TALK_PASTE, paste))
                    left = paste.right + gap
                }
                talkLeft = left
                items.add(Item("voice", Act.MIC, RectF(left, y, x.left - gap, y + b)))
                items.add(Item("x", Act.DISCARD, x, corner = dp(12f)))
                items.add(Item("abc", Act.KEYBOARD, abc))
            }
            voice == Bar.Voice.CLEANING -> {
                // polishing stays in the recording pill's place; ABC stays (it waits for the text)
                val wide = 2 * b + gap
                val abc = rect(w - pad - wide, y, wide, b)
                val x = rect(abc.left - gap - b, y, b, b)
                items.add(Item("voice", Act.MIC, RectF(talkLeft.coerceAtLeast(pad), y, x.left - gap, y + b)))
                items.add(Item("abc", Act.KEYBOARD, abc))
            }
            busyVoice -> items.add(Item("voice", Act.MIC, RectF(pad, y, w - pad, y + b)))
            alt == Alt.TRAY -> {
                // ⌫ held: it opens leftward into ↵ · space · ⌫, a good third of the bar, and the
                // pills give it room by shrinking to their icons
                val trayW = w * TRAY_FRACTION
                val tray = RectF(w - pad - trayW, y, w - pad, y + b)
                val sw = (trayW - 2 * gap) / 3
                val pr2 = tray.left - gap
                val pw2 = (pr2 - pl - 2 * gap) / 3
                items.add(Item("more", Act.MORE, more))
                items.add(Item("cursor", Act.CURSOR, rect(pl, y, pw2, b)))
                items.add(Item("abc", Act.KEYBOARD, rect(pl + pw2 + gap, y, pw2, b)))
                items.add(Item("voice", Act.MIC, rect(pl + 2 * (pw2 + gap), y, pw2, b)))
                items.add(Item("tray", Act.PANEL, tray, 1f, b / 2))
                items.add(Item("enter", Act.ENTER, rect(tray.left, y, sw, b)))
                items.add(Item("space", Act.SPACE_ALT, rect(tray.left + sw + gap, y, sw, b)))
                items.add(Item("bksp", Act.BACKSPACE, rect(tray.right - sw, y, sw, b)))
            }
            equalBar -> {
                items.add(Item("more", Act.MORE, more))
                items.add(Item("space", Act.SPACE_ALT, slot(1)))
                items.add(Item("cursor", Act.CURSOR, cursorPill))
                items.add(Item("abc", Act.KEYBOARD, abcPill))
                items.add(Item("voice", Act.MIC, voicePill))
                items.add(Item("tray", Act.PANEL, RectF(bksp), 0f, b / 2))
                items.add(Item("bksp", Act.BACKSPACE, bksp))
                items.add(Item("enter", Act.ENTER, slot(6)))
            }
            else -> {
                items.add(Item("more", Act.MORE, more))
                items.add(Item("cursor", Act.CURSOR, cursorPill))
                items.add(Item("abc", Act.KEYBOARD, abcPill))
                items.add(Item("voice", Act.MIC, voicePill))
                items.add(Item("tray", Act.PANEL, RectF(bksp), 0f, b / 2))
                items.add(Item("bksp", Act.BACKSPACE, bksp))
            }
        }

        // the panels: each rests, invisible, on the control it grows out of
        val top = -drawerPx
        val padOpen = drawer == Drawer.PAD
        items.add(0, Item("padPanel", Act.PANEL, if (padOpen) RectF(dp(10f), top + dp(54f), w - dp(10f), -dp(6f)) else RectF(cursorPill), if (padOpen) 1f else 0f, if (padOpen) dp(24f) else b / 2))
        val menuOpen = drawer == Drawer.MENU
        items.add(0, Item("menuPanel", Act.PANEL, if (menuOpen) RectF(dp(4f), top + dp(4f), w - dp(4f), -dp(2f)) else RectF(more), if (menuOpen) 1f else 0f, if (menuOpen) dp(24f) else b / 2))

        when (drawer) {
            Drawer.PAD -> layoutPad(w, top)
            Drawer.MENU -> layoutMenu(w, top)
            Drawer.NONE -> Unit
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
        // Settings · Switch keyboard on top, this month's cost across the bottom
        drawerItems.add(Item(Act.TILE_SETTINGS.name, Act.TILE_SETTINGS, rect(side, top + dp(14f), tw, th)))
        drawerItems.add(Item(Act.TILE_SWITCH.name, Act.TILE_SWITCH, rect(side + tw + gap, top + dp(14f), tw, th)))
        drawerItems.add(Item(Act.TILE_COST.name, Act.TILE_COST, rect(side, top + dp(14f) + th + gap, 2 * tw + gap, th)))
    }

    @VisibleForTesting internal fun testItem(act: Act, ch: Char = ' '): RectF? {
        layoutAll()
        val it = (drawerItems + items).firstOrNull { it.act == act && it.alpha > 0f && true } ?: return null
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

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            if (p.act != Act.BACKSPACE) return
            if (repeatHalted) return
            listener?.onKey(Bar.Key.DELETE, true)
            postDelayed(this, REPEAT_MS)
        }
    }
    private var repeatHalted = false

    // ---------------------------------------------------------------- hold ↵ for space, ⌫ for delete-all

    private enum class Alt { NONE, TRAY }
    private var alt = Alt.NONE
    /** Which of the held ⌫'s keys the finger is on: ⌫, space or ↵. */
    private var trayHot = Act.BACKSPACE
    private val altRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (p.act != Act.BACKSPACE || alt != Alt.NONE) return@Runnable
        if (equalBar && drawer == Drawer.NONE) {
            // space and ↵ already have their own pills, so holding ⌫ simply keeps deleting
            consumed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onKey(Bar.Key.DELETE, true)
            postDelayed(repeatRunnable, REPEAT_MS)
            return@Runnable
        }
        alt = Alt.TRAY
        trayHot = Act.BACKSPACE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // holding only opens the tray; rapid fire is tap-then-hold (see onDown)
        invalidate()
    }
    /** When ⌫ was last tapped: pressing it again soon after and holding deletes repeatedly. */
    private var lastBkspTap = 0L
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
        if (previewLayout != null) return false
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
            Act.MIC -> if (talking) postDelayed(sendHoldRunnable, SEND_HOLD_MS)
            Act.BACKSPACE -> {
                val now = SystemClock.uptimeMillis()
                if (lastBkspTap > 0L && now - lastBkspTap < DOUBLE_TAP_MS) {
                    // the second press of a double: delete now and keep deleting while it's held
                    lastBkspTap = 0L
                    consumed = true
                    listener?.onKey(Bar.Key.DELETE, false)
                    postDelayed(repeatRunnable, BKSP_REPEAT_START_MS)
                } else postDelayed(altRunnable, ALT_HOLD_MS)
            }
            else -> Unit
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** Holding the voice pill while dictating: finish, then send. */
    private val sendHoldRunnable = Runnable {
        if (pressed?.act != Act.MIC || !talking) return@Runnable
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        endTalk(cancel = false, polish = true)
        listener?.onSendAfterTalk()
        invalidate()
    }

    private fun onMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (alt == Alt.TRAY) {
            // which of ↵ · space · ⌫ is under the finger (by column; height doesn't matter)
            val e = rectOf("enter") ?: return
            val sp = rectOf("space") ?: return
            val hot = when {
                x < (e.right + sp.left) / 2 -> Act.ENTER
                x < (sp.right + (rectOf("bksp")?.left ?: sp.right)) / 2 -> Act.SPACE_ALT
                else -> Act.BACKSPACE
            }
            if (hot != trayHot) {
                trayHot = hot
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
            return
        }
        // a sideways slide on the space pill, or on the idle Voice pill, moves the cursor
        if ((p.act == Act.SPACE_ALT || (p.act == Act.MIC && !talking)) && !sliding && abs(x - downX) > dp(SLIDE_START_DP)) {
            sliding = true
            slideX = downX
        }
        if (sliding) {
            val step = dp(SLIDE_STEP_DP)
            while (x - slideX >= step) { slideX += step; listener?.onKey(Bar.Key.RIGHT, true) }
            while (slideX - x >= step) { slideX -= step; listener?.onKey(Bar.Key.LEFT, true) }
            invalidate()
            return
        }
        if (d2(p.rect, x, y) > dp(16f) * dp(16f)) {
            removeCallbacks(repeatRunnable)
            removeCallbacks(altRunnable)
        removeCallbacks(sendHoldRunnable)
            pressed = null
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed
        pressed = null
        removeCallbacks(repeatRunnable)
        removeCallbacks(altRunnable)
        removeCallbacks(sendHoldRunnable)
        if (p == null) { alt = Alt.NONE; invalidate(); return }
        if (p.act == Act.PAD) { padUp(); return }
        if (alt == Alt.TRAY) {
            alt = Alt.NONE
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (trayHot) {
                Act.ENTER -> { listener?.onKey(Bar.Key.ENTER, false) }
                Act.SPACE_ALT -> { listener?.onKey(Bar.Key.SPACE, false) }
                else -> listener?.onKey(Bar.Key.DELETE, false)
            }
            trayHot = Act.BACKSPACE
            invalidate()
            return
        }
        if (consumed || sliding) { sliding = false; invalidate(); return }
        act(p)
        invalidate()
    }

    private fun onCancel() {
        removeCallbacks(repeatRunnable)
        removeCallbacks(altRunnable)
        removeCallbacks(sendHoldRunnable)
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
                voice == Bar.Voice.CLEANING -> Unit
                voice == Bar.Voice.MESSAGE && messageAction != null -> { val a = messageAction; hideVoice(); a?.invoke() }
                else -> startTalk()
            }
            Act.FINISH -> if (talking) endTalk(cancel = false, polish = true)
            Act.DISCARD -> if (talking) endTalk(cancel = true, polish = false)
            Act.TALK_PASTE -> if (talking) {
                // the clipboard instead of what's been said: drop the dictation, then paste
                endTalk(cancel = true, polish = false)
                l?.onEdit(Bar.EditAction.PASTE)
            }
            Act.CHIP_PASTE -> if (canPaste) l?.onEdit(Bar.EditAction.PASTE)
            Act.CHIP_CUT -> if (hasSelection) l?.onEdit(Bar.EditAction.CUT)
            Act.CHIP_COPY -> if (hasSelection) l?.onEdit(Bar.EditAction.COPY)
            Act.CHIP_DELETE -> l?.onEdit(Bar.EditAction.DELETE)
            Act.CURSOR -> setDrawer(if (drawer == Drawer.PAD) Drawer.NONE else Drawer.PAD)
            // typing is Gboard's job: ABC hands the field over
            Act.KEYBOARD -> if (voice == Bar.Voice.CLEANING && !talking) l?.onTypeAfterTalk() else if (talking) {
                // finish what's being said (polished), then Gboard takes over
                endTalk(cancel = false, polish = true)
                l?.onTypeAfterTalk()
            } else l?.onSwitchToGboard()
            Act.MORE -> setDrawer(if (drawer == Drawer.MENU) Drawer.NONE else Drawer.MENU)
            Act.ENTER -> { l?.onKey(Bar.Key.ENTER, false) }
            Act.BACKSPACE -> { listener?.onKey(Bar.Key.DELETE, false); lastBkspTap = SystemClock.uptimeMillis() } // a tap: on release, so a hold can open the tray
            Act.TILE_SETTINGS, Act.TILE_COST -> { setDrawer(Drawer.NONE); l?.onOpenSettings() }
            Act.TILE_SWITCH -> { setDrawer(Drawer.NONE); l?.onSwitchKeyboard() }
            // the space pill of the equal layout (in the ⌫ tray, space is handled on release instead)
            Act.SPACE_ALT -> { l?.onKey(Bar.Key.SPACE, false) }
            Act.PAD, Act.PANEL, Act.CLEAR_ALL -> Unit
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

    private val night get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    /** Letter keys: white on a light panel; in the dark, a step lighter than the panel so they read. */

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
        if (id == "tray") {
            fill.color = alpha(pal.bloomKey, al)
            canvas.drawRoundRect(a.rect, a.v[4], a.v[4], fill)
            return
        }
        fill.color = when (id) {
            "padPanel" -> alpha(pal.bloomKey, al)
            // the menu: tiles stand out against a solid surface (the faint panel lost them in dark mode)
            "menuPanel" -> if (tiered) alpha(pal.disc, al) else alpha(pal.cardinal, al * 0.55f)
            else -> alpha(pal.cardinal, al * 0.55f)
        }
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
        val down = pressed?.id == a.id && alt == Alt.NONE
        when (a.act) {
            Act.MIC -> drawVoicePill(canvas, r, a.v[4], al, now, down)
            Act.CURSOR -> drawPill(canvas, r, a.v[4], al, down, Act.CURSOR)
            Act.KEYBOARD -> drawPill(canvas, r, a.v[4], al, down, Act.KEYBOARD)
            Act.TALK_PASTE -> drawClipPill(canvas, r, a.v[4], al, down)
            else -> drawRound(canvas, a.act, r, a.v[4], al, down)
        }
        canvas.restore()
    }

    private fun drawRound(canvas: Canvas, act: Act, r: RectF, corner: Float, al: Float, down: Boolean) {
        val pal = palette
        val inTray = alt == Alt.TRAY && (act == Act.ENTER || act == Act.SPACE_ALT || act == Act.BACKSPACE)
        val hotAlt = inTray && act == trayHot
        // on the bar (not the letters' bottom row or the ⌫ tray): keys stay neutral in every state
        val atRest = !inTray
        val bg = when {
            // tiered: keeping is the strong action (primary); throwing away is tonal red, so it never outshouts ✓
            tiered && act == Act.DISCARD -> if (down) ColorUtils.blendARGB(pal.discard, pal.onDiscard, 0.14f) else pal.discard
            act == Act.DISCARD -> if (down) ColorUtils.blendARGB(pal.error, Color.BLACK, 0.18f) else pal.error
            act == Act.FINISH -> if (down) ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.18f) else pal.pressed
            tiered && act == Act.MORE && drawer == Drawer.MENU -> pal.letters
            tiered && down && atRest && (act == Act.BACKSPACE || act == Act.SPACE_ALT) -> ColorUtils.blendARGB(pal.cardinal, pal.onCardinal, 0.14f)
            tiered && atRest && (act == Act.BACKSPACE || act == Act.SPACE_ALT) -> pal.cardinal
            tiered && atRest && act == Act.MORE -> if (down) pal.cardinal else Color.TRANSPARENT
            down || hotAlt -> pal.pressed
            inTray -> Color.TRANSPARENT
            act == Act.CLEAR_ALL -> pal.discard
            act == Act.ENTER -> pal.center
            act == Act.MORE && drawer == Drawer.MENU -> pal.pressed
            act in TONAL -> pal.diagonal
            equalBar && drawer == Drawer.NONE && (act == Act.MORE || act == Act.SPACE_ALT) -> pal.diagonal
            else -> Color.TRANSPARENT
        }
        val fg = when {
            tiered && act == Act.DISCARD -> pal.onDiscard
            act == Act.DISCARD -> pal.onError
            act == Act.FINISH -> pal.onPressed
            tiered && act == Act.MORE && drawer == Drawer.MENU -> pal.onLetters
            tiered && atRest && (act == Act.BACKSPACE || act == Act.SPACE_ALT || act == Act.MORE) -> pal.onCardinal
            down || hotAlt -> pal.onPressed
            inTray -> pal.onDiagonal
            act == Act.ENTER -> pal.onCenter
            act == Act.CLEAR_ALL -> pal.onDiscard
            act == Act.MORE && drawer == Drawer.MENU -> pal.onPressed
            act in TONAL -> pal.onDiagonal
            equalBar && drawer == Drawer.NONE && (act == Act.MORE || act == Act.SPACE_ALT) -> pal.onDiagonal
            else -> pal.onCardinal
        }
        if (Color.alpha(bg) > 0) {
            fill.color = alpha(bg, al)
            canvas.drawRoundRect(r, corner, corner, fill)
        }
        drawGlyph(canvas, act, r.centerX(), r.centerY(), dp(22f), alpha(fg, al))
    }

    /** While dictating: a tonal pill with the paste glyph and the start of the clipboard, ellipsized. */
    private val clipPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private fun clipText() = (clipPreview ?: "").replace(Regex("\\s+"), " ").trim()

    /** How wide the paste pill would need to be to show the whole clip preview on one line. */
    private fun clipPillContentWidth(): Float {
        clipPaint.textSize = dp(13f)
        return dp(12f) + dp(18f) + dp(6f) + clipPaint.measureText(clipText()) + dp(12f)
    }

    private fun drawClipPill(canvas: Canvas, r: RectF, corner: Float, al: Float, down: Boolean) {
        val pal = palette
        fill.color = alpha(if (down) ColorUtils.blendARGB(pal.diagonal, pal.onDiagonal, 0.14f) else pal.diagonal, al)
        canvas.drawRoundRect(r, corner, corner, fill)
        val c = alpha(pal.onDiagonal, al)
        // one line: the paste glyph, then the start of the clipboard, ellipsized to fit
        val iw = dp(18f)
        val x0 = r.left + dp(12f)
        fill.color = c
        Icons.draw(canvas, Icons.Glyph.PASTE, x0 + iw / 2, r.centerY(), iw, fill)
        clipPaint.textSize = dp(13f)
        clipPaint.color = c
        val tx = x0 + iw + dp(6f)
        val shown = android.text.TextUtils.ellipsize(clipText(), clipPaint, r.right - dp(12f) - tx, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, tx, r.centerY() - (clipPaint.ascent() + clipPaint.descent()) / 2, clipPaint)
    }

    /** Cursor and ABC at rest: tonal pills with an icon and, when there's room, their name. */
    private fun drawPill(canvas: Canvas, r: RectF, corner: Float, al: Float, down: Boolean, act: Act) {
        val pal = palette
        val done = act == Act.CURSOR && drawer == Drawer.PAD
        // with equal pills, Cursor and ABC take Voice's dark fill: the three modes stand apart from the keys
        val modeDark = equalBar && drawer == Drawer.NONE && !tiered
        val bg = when {
            tiered && done -> pal.letters
            tiered && down -> ColorUtils.blendARGB(pal.diagonal, pal.onDiagonal, 0.14f)
            down && modeDark -> ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.18f)
            down || done || modeDark -> pal.pressed
            else -> pal.diagonal
        }
        val fg = when {
            tiered && done -> pal.onLetters
            tiered -> pal.onDiagonal
            down || done || modeDark -> pal.onPressed
            else -> pal.onDiagonal
        }
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
        val cleaning = voice == Bar.Voice.CLEANING && !talking
        val message = voice == Bar.Voice.MESSAGE && !talking
        val small = false
        val bg = when {
            down -> pal.onCenter
            cleaning -> pal.center
            message -> pal.discard
            small -> pal.cardinal
            else -> pal.pressed
        }
        val fg = when {
            down -> pal.center
            cleaning -> pal.onCenter
            message -> pal.onDiscard
            small -> pal.onCardinal
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
                // "Tap to finish recording": shown at the start, then morphing in from the waveform
                // every few seconds (only when the words go into the field, so nothing is hidden)
                // the whole "Tap to finish, long press to send" when it fits; otherwise its two
                // halves take turns
                text.textSize = dp(14f)
                val full = context.getString(R.string.dock_hint_full)
                val fits = text.measureText(full) <= r.width() - dp(28f)
                val (hintIdx, hintP) = if (showText) 0 to 0f else hintAt(now, if (fits) 1 else 2)
                val hint = hintP
                val hintText = when {
                    fits -> full
                    hintIdx == 0 -> context.getString(R.string.dock_tap_to_finish)
                    else -> context.getString(R.string.dock_hold_to_send)
                }
                // the waveform, centered in the pill and clear of the timer on the right
                val bw = dp(3f)
                val bg2 = dp(3f)
                val half = min(cx - (r.left + dp(16f)), (right - labelW - dp(12f)) - cx)
                val wx0 = if (showText) r.left + dp(16f) else cx - half
                val wx1 = if (showText) right - labelW - dp(12f) else cx + half
                text.textSize = dp(14f)
                // the hint gets the whole pill (the timer steps aside); shrink it if it still won't fit
                val fitW = r.width() - dp(28f)
                if (text.measureText(hintText) > fitW) text.textSize = dp(14f) * fitW / text.measureText(hintText)
                val tw = text.measureText(hintText)
                // the text is unveiled from the middle outward; bars inside the opening give way
                val open = tw / 2 * hint + dp(10f) * hint
                if (wx1 > wx0) {
                    val bars = ((wx1 - wx0 + bg2) / (bw + bg2)).toInt().coerceIn(1, levels.size)
                    val off = ((wx1 - wx0) - (bars * bw + (bars - 1) * bg2)) / 2
                    for (i in 0 until bars) {
                        val lv = levels[levels.size - bars + i]
                        val idle = 0.12f + 0.06f * sin((now / 140.0 + i).toFloat())
                        val bx = wx0 + off + i * (bw + bg2)
                        val d = abs(bx + bw / 2 - cx)
                        // inside the opening a bar fades out; outside, bars settle down as the text arrives
                        val keep = ((d - open) / dp(14f)).coerceIn(0f, 1f).let { if (hint > 0f) it else 1f }
                        val settle = 1f - 0.65f * hint
                        val h = max(dp(3f), dp(22f) * max(lv, idle) * settle)
                        tmp.set(bx, cy - h / 2, bx + bw, cy + h / 2)
                        val drained = drain < 1f && bx > r.left + r.width() * drain
                        val a = al * keep * (if (showText) 0.18f else 1f) * (1f - 0.35f * hint)
                        if (a <= 0.01f) continue
                        fill.color = alpha(if (drained) pal.onCenter else fg, a)
                        canvas.drawRoundRect(tmp, bw / 2, bw / 2, fill)
                    }
                }
                if (hint > 0f) {
                    canvas.save()
                    canvas.clipRect(cx - open, r.top, cx + open, r.bottom)
                    text.textAlign = Paint.Align.CENTER
                    text.color = alpha(if (drain < 1f && cx > r.left + r.width() * drain) pal.onCenter else fg, al * min(1f, hint * 1.4f))
                    // a slight rise and grow as it lands
                    val s = 0.94f + 0.06f * hint
                    canvas.scale(s, s, cx, cy)
                    canvas.drawText(hintText, cx, cy - (text.ascent() + text.descent()) / 2 + dp(5f) * (1f - hint), text)
                    canvas.restore()
                }
                text.textSize = dp(14f)
                text.textAlign = Paint.Align.RIGHT
                text.color = alpha(if (drain < 1f && right > r.left + r.width() * drain) pal.onCenter else fg, al * (1f - hint))
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

    /** A soft drop shadow under a rounded rectangle (stacked translucent outlines). */
    private fun drawShadowRect(canvas: Canvas, r: RectF, rad: Float) {
        for (i in 1..4) {
            fill.color = Color.argb(10, 0, 0, 0)
            tmp.set(r); tmp.inset(-i * dp(1.2f), -i * dp(1.2f)); tmp.offset(0f, dp(1.5f))
            canvas.drawRoundRect(tmp, rad + i * dp(1.2f), rad + i * dp(1.2f), fill)
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

    /**
     * The hint over the waveform: which text (0 = the first, 1 = the second when they alternate)
     * and how far it has morphed in, 0..1. It's shown as dictation starts, and again after
     * [HINT_QUIET_MS] without speech, every [HINT_EVERY_MS] while the quiet lasts. Each showing
     * morphs in, holds and morphs out; with two texts they play one after the other.
     */
    private fun hintAt(now: Long, texts: Int): Pair<Int, Float> {
        if (!talking) return 0 to 0f
        fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val one = HINT_MORPH_MS * 2 + HINT_HOLD_MS
        fun play(o: Long): Pair<Int, Float> {
            if (o < 0 || o >= one * texts) return 0 to 0f
            val k = (o / one).toInt()
            val p = o % one
            return k to when {
                p < HINT_MORPH_MS -> ease(p.toFloat() / HINT_MORPH_MS)
                p < HINT_MORPH_MS + HINT_HOLD_MS -> 1f
                else -> 1f - ease((p - HINT_MORPH_MS - HINT_HOLD_MS).toFloat() / HINT_MORPH_MS)
            }
        }
        // at the start it's already in place, then plays out
        val start = play(now - talkStartedAt + HINT_MORPH_MS)
        if (start.second > 0f) return start
        val quiet = now - lastHeardAt - HINT_QUIET_MS
        if (quiet < 0) return 0 to 0f
        return play(quiet % HINT_EVERY_MS)
    }
    private fun hintAmount(now: Long) = hintAt(now, 1).second
    @VisibleForTesting internal fun testHint(now: Long) = hintAmount(now)

    private fun elapsed(now: Long): String {
        val s = ((now - talkStartedAt) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawDrawerItem(canvas: Canvas, it: Item, al: Float) {
        val pal = palette
        val r = it.rect
        val down = pressed === it || (pressed?.id == it.id)
        when (it.act) {
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
            Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_COST -> {
                fill.color = alpha(if (down) pal.pressed else pal.bloomKey, al)
                canvas.drawRoundRect(r, dp(20f), dp(20f), fill)
                val fg = alpha(if (down) pal.onPressed else pal.onBloomKey, al)
                val sub = alpha(if (down) pal.onPressed else pal.onCardinal, al)
                val ix = r.left + dp(20f)
                val iy = r.centerY()
                when (it.act) {
                    Act.TILE_SETTINGS -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SETTINGS, ix + dp(11f), iy, dp(22f), fill) }
                    Act.TILE_SWITCH -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SWITCH_KEYBOARD, ix + dp(11f), iy, dp(22f), fill) }
                    else -> { text.color = sub; text.textSize = dp(18f); canvas.drawText("$", ix + dp(11f), iy - (text.ascent() + text.descent()) / 2, text) }
                }
                text.textAlign = Paint.Align.LEFT
                text.color = fg
                text.textSize = dp(14f)
                val title = context.getString(when (it.act) {
                    Act.TILE_SETTINGS -> R.string.dock_settings
                    Act.TILE_SWITCH -> R.string.dock_switch
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
            Act.BACKSPACE -> Icons.draw(canvas, Icons.Glyph.BACKSPACE, x, y, size, fill)
            Act.CURSOR -> Icons.draw(canvas, Icons.Glyph.TEXT_CURSOR, x, y, size, fill)
            Act.DISCARD -> Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, size, fill)
            Act.KEYBOARD -> drawKeyboard(canvas, x, y, size, c)
            Act.MORE -> for (i in -1..1) canvas.drawCircle(x, y + i * size * 0.27f, size * 0.09f, fill)
            Act.FINISH -> strokePath(canvas, x, y, size, c) { m, l -> m(5f, 12.5f); l(9.5f, 17f); l(19f, 7.5f) }
            Act.ENTER -> strokePath(canvas, x, y, size, c) { m, l -> m(19f, 5f); l(19f, 13f); l(6f, 13f); m(10f, 9f); l(6f, 13f); l(10f, 17f) }
            Act.SPACE_ALT -> drawSpace(canvas, x, y, size * 0.42f, c)
            Act.CLEAR_ALL -> {
                // a backspace with a bar to its left: everything, back to the start
                Icons.draw(canvas, Icons.Glyph.BACKSPACE, x + size * 0.08f, y, size * 0.86f, fill)
                stroke.color = c
                stroke.strokeWidth = size * 0.09f
                canvas.drawLine(x - size * 0.46f, y - size * 0.3f, x - size * 0.46f, y + size * 0.3f, stroke)
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

    private companion object {
        const val BAR_DP = 52f
        const val DRAWER_DP = 172f
        const val NAV_GESTURE = 2
        const val WAVE_HISTORY = 96
        const val DRAIN_AFTER_MS = 5000L
        const val MESSAGE_MS = 2600L
        /** The "Tap to finish recording" hint: shown first, then back every 5 s. */
        const val HINT_QUIET_MS = 3000L
        const val HINT_EVERY_MS = 10_000L
        const val HINT_MORPH_MS = 650L
        const val HINT_HOLD_MS = 1100L
        /** Holding the voice pill this long while dictating sends. */
        const val SEND_HOLD_MS = 450L
        const val MESSAGE_ACTION_MS = 6000L
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        const val ALT_HOLD_MS = 300L
        const val BKSP_REPEAT_START_MS = 250L
        /** Resting on ⌫ in the open tray this long starts deleting repeatedly. */
        /** How much of the bar the held ⌫ opens into. */
        const val TRAY_FRACTION = 0.38f
        const val PAD_HOLD_MS = 350L
        const val PAD_COL_DP = 8f
        const val PAD_LINE_DP = 22f
        const val SLIDE_START_DP = 12f
        const val SLIDE_STEP_DP = 9f
        const val DISABLED_ALPHA = 0.38f
        /** One spring for every move: a slight overshoot, settled in about a third of a second. */
        const val SPRING_K = 420f
        const val SPRING_ZETA = 0.72f
        val TONAL = setOf(Act.DISCARD, Act.FINISH, Act.BACKSPACE)
        const val NUM_LEFT = "-+*"
    }
}
