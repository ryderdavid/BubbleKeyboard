package com.ryderdavid.voicebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import com.ryderdavid.voicebar.voice.UsageLog
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
        CHIP_SELECT_ALL, CHIP_CUT, CHIP_COPY, CHIP_PASTE, CHIP_DELETE, PAD,
        /** While dictating: throw the dictation away and paste the clipboard instead. */
        TALK_PASTE,
        TILE_SETTINGS, TILE_SWITCH, TILE_COST, TILE_PASTE, PANEL,
        /** The clipboard-history row in the ⋮ menu (scrolls sideways). */
        CLIP_ROW,
        /** Clipboard · Dictations, above that row. */
        CLIP_TABS,
    }

    /** A control where it should be now: [id] ties it to its animation across layouts. */
    private class Item(val id: String, val act: Act, val rect: RectF, val alpha: Float = 1f, val corner: Float = -1f, val ch: Char = ' ')

    /** Where a control is drawn right now, springing toward its [Item]. */
    private class Anim(val id: String, var act: Act) {
        val v = FloatArray(6) // left, top, right, bottom, corner, alpha
        val vel = FloatArray(6)
        val target = FloatArray(6)
        var present = true
        /** The alpha it stands at when shown: 1, or [GHOST] for a control that's there but not pressable. */
        var full = 1f
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
    override var clips: List<ClipHistory.Clip> = emptyList()
        set(v) { field = v; clipOptions = null; clampClipScroll(); invalidate() }
    override var dictations: List<DictationHistory.Entry> = emptyList()
        set(v) { field = v; clipOptions = null; clampClipScroll(); invalidate() }
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
        commandSaid = null
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

    /** What's been said after "voice bar", while the pill is purple; null when it isn't. */
    private var commandSaid: String? = null
    private var commandUnderstood = false
    private var commandAt = 0L

    override fun showCommand(said: String?, understood: Boolean) {
        val wantsRoom = said != null
        if (wantsRoom != floatHint) { floatHint = wantsRoom; requestLayout() }
        if (said != null && commandSaid == null) {
            commandAt = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (said == null && commandSaid != null) commandAt = SystemClock.uptimeMillis()
        if (understood && !commandUnderstood) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        commandSaid = said
        commandUnderstood = said != null && understood
        invalidate()
    }

    @VisibleForTesting internal val testCommand get() = commandSaid

    override fun showCleaning(text: String) {
        voice = Bar.Voice.CLEANING
        voiceText = text
        invalidate()
    }

    /** What a tap on the message does, if anything. */
    private var messageAction: (() -> Unit)? = null

    /** The message showing is good news (a note), not a warning. */
    private var messageGood = false

    override fun showNote(message: String, onTap: (() -> Unit)?) {
        showMessage(message, onTap)
        messageGood = true
        invalidate()
    }

    override fun showMessage(message: String, onTap: (() -> Unit)?) {
        messageGood = false
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
        commandSaid = null
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
        if (swiping) swipeEnd(commit = false)
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
    /** On a wide screen, the buttons kept together at one side (or the middle) instead of spread across. */
    private var barSide = Prefs.DEFAULT_BAR_SIDE

    /** Turn this view into a still, untouchable picture of the bar at rest in [layout]. */
    private var previewColors: String? = null
    /** The seven-button bar's own key to show instead of the saved one, in a preview. */
    private var previewKey: String? = null
    /** What the seven-button bar's own key (between ⋮ and Cursor) does; the ⌫ tray's middle key is always space. */
    private var extraKey = Bar.ExtraKey.SPACE

    fun showAsPreview(layout: String, colors: String? = null, key: String? = null) {
        previewLayout = layout
        previewColors = colors
        previewKey = key
        insetOverride = 0
        bottomInset = 0
        isClickable = false
        reloadSettings()
    }

    override fun reloadSettings() {
        palette = Palette(context)
        tiered = (previewColors ?: Prefs.get(context).getString(Prefs.BAR_COLORS, Prefs.DEFAULT_BAR_COLORS)) == Prefs.BAR_COLORS_TIERED
        equalBar = (previewLayout ?: Prefs.get(context).getString(Prefs.BAR_LAYOUT, Prefs.DEFAULT_BAR_LAYOUT)) == Prefs.BAR_LAYOUT_EQUAL
        extraKey = Bar.ExtraKey.of(previewKey ?: Prefs.get(context).getString(Prefs.BAR_KEY, Prefs.DEFAULT_BAR_KEY))
        barSide = Prefs.get(context).getString(Prefs.BAR_SIDE, Prefs.DEFAULT_BAR_SIDE) ?: Prefs.DEFAULT_BAR_SIDE
        requestLayout()
        silenceMs = Prefs.get(context).getInt(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT)
            .coerceIn(Prefs.SILENCE_TIMEOUT_RANGE) * 1000L
        invalidate()
    }

    // ---------------------------------------------------------------- talking

    override fun startVoice() = startTalk()

    override fun finishVoice() { if (talking) endTalk(cancel = false, polish = false) }

    override fun showHeld(message: String, onTap: () -> Unit) {
        showNote(message, onTap)
        removeCallbacks(hideVoiceRunnable)
        postDelayed(hideVoiceRunnable, HELD_MS)
    }

    private fun startTalk() {
        if (talking) return
        if (listener?.onTalkStart() == true) {
            onUsed?.invoke("voice")
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
        commandSaid = null
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        onUsed?.invoke("finish")
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
        // gesture navigation (nothing tappable down there): on Android 15+ the keyboard hides the
        // system's ⌄/switcher band, so the bar only needs to clear the gesture handle. The insets
        // and the platform dimens still report a full button-bar's height on some phones (One UI),
        // which left a tall empty band under the bar; keep just the handle's room.
        val tappable = runCatching { root?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement())?.bottom }.getOrNull() ?: 0
        val gestures = mode == NAV_GESTURE || (nav > 0 && tappable == 0)
        if (Build.VERSION.SDK_INT >= 35 && gestures && navAtBottom) {
            v = nav.coerceIn(dp(GESTURE_ROOM_MIN_DP).toInt(), dp(GESTURE_ROOM_MAX_DP).toInt())
        } else if (navAtBottom) v = maxOf(v, navFloor(mode))
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
        setMeasuredDimension(w, ((if (room) drawerPx else floatRoom().toFloat()) + barH + bottomInset).toInt())
    }

    /**
     * Room at the top of the view that's see-through and floats over the app (the window grows
     * by it, but the service keeps the app where it was and lets touches through): the "swipe up"
     * hint above the purple pill. 0 when there's none (or a drawer has the room).
     */
    fun floatRoom(): Int = when {
        drawer != Drawer.NONE || drawerRoom -> 0
        tip != null -> tipRoom
        floatHint -> dp(FLOAT_DP).toInt()
        else -> 0
    }

    // ---------------------------------------------------------------- first-run tips

    /**
     * A first-run tip: a bubble floating over the app, pointing down at one of the bar's controls
     * ([anchor], an animation id like "voice"): step [n] of [of] of the tour, with Dismiss, Back and
     * Next (Done on the last), or, when [ask], the "again later?" question with No thanks and Later.
     */
    data class Tip(val anchor: String, val title: String, val body: String, val n: Int, val of: Int, val ask: Boolean = false)

    var tip: Tip? = null
        set(v) { if (field != v) { field = v; tipAt = SystemClock.uptimeMillis(); tipRoom = measureTipRoom(); requestLayout(); invalidate() } }
    private var tipAt = 0L
    private var tipRoom = 0
    /** Where the bubble's buttons are, in view coordinates, for taps. */
    private val tipButtons = ArrayList<Pair<RectF, Tips.Answer>>()
    /** A button on the bubble was tapped. */
    var onTipAnswer: ((Tips.Answer) -> Unit)? = null
    /** A feature was used ("voice", "finish", "abc", "cursor", "bksp"): its tip is no longer needed. */
    var onUsed: ((String) -> Unit)? = null

    /** The tip's bubble in view coordinates (fully shown), for the window's touchable area; null without one. */
    fun tipRect(): RectF? {
        val t = tip ?: return null
        val a = anims[t.anchor]?.rect ?: return null
        val w = tipWidth()
        val h = tipHeight(t)
        val left = (a.centerX() - w / 2).coerceIn(dp(8f), width - w - dp(8f))
        val bottom = barTop - dp(14f)
        return RectF(left, bottom - h, left + w, bottom)
    }

    private val tipTitle = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val tipBody = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13.5f) }
    private val tipButton = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13.5f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }

    private fun tipWidth() = min(width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat(), dp(340f) + dp(16f)) - dp(16f)

    private fun tipLayout(t: Tip): android.text.StaticLayout {
        val inner = (tipWidth() - dp(32f)).toInt().coerceAtLeast(1)
        return android.text.StaticLayout.Builder.obtain(t.body, 0, t.body.length, tipBody, inner).setLineSpacing(dp(2f), 1f).build()
    }

    /** The bubble's height plus its tail and a margin: the floating room it needs. */
    private fun tipHeight(t: Tip) = dp(16f) + dp(20f) + dp(6f) + tipLayout(t).height + dp(14f) + dp(36f) + dp(12f)

    private fun measureTipRoom(): Int = tip?.let { (tipHeight(it) + dp(18f)).toInt() } ?: 0

    private fun drawTip(canvas: Canvas, now: Long) {
        val t = tip ?: return
        val a = anims[t.anchor]?.rect ?: return
        val pal = palette
        val e = ((now - tipAt) / 280f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        val w = tipWidth()
        val h = tipHeight(t)
        val ax = a.centerX()
        val left = (ax - w / 2).coerceIn(dp(8f), width - w - dp(8f))
        val bottom = barTop - dp(14f) + dp(10f) * (1f - e)
        val box = RectF(left, bottom - h, left + w, bottom)
        val r = dp(22f)
        // the control it's about: a soft ring that breathes
        val pulse = 0.5f + 0.5f * sin(now / 320.0).toFloat()
        val ring = RectF(a).apply { offset(0f, barTop); inset(-dp(3f) - dp(2f) * pulse, -dp(3f) - dp(2f) * pulse) }
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = alpha(pal.command, e * (0.55f + 0.45f * pulse)) }
        val rr = min(ring.height(), ring.width()) / 2
        canvas.drawRoundRect(ring, rr, rr, st)
        // shadow, bubble, and a tail down to the control
        for (i in 1..5) {
            fill.color = Color.argb((12 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(box.left - i * dp(1f), box.top - i * dp(1f) + dp(2f), box.right + i * dp(1f), box.bottom + i * dp(1f) + dp(2f), r + i * dp(1f), r + i * dp(1f), fill)
        }
        fill.color = alpha(pal.command, e)
        canvas.drawRoundRect(box, r, r, fill)
        val tx = ax.coerceIn(box.left + dp(18f), box.right - dp(18f))
        val tail = android.graphics.Path().apply {
            moveTo(tx - dp(9f), box.bottom - dp(1f)); lineTo(tx, box.bottom + dp(9f)); lineTo(tx + dp(9f), box.bottom - dp(1f)); close()
        }
        canvas.drawPath(tail, fill)
        // title, with "1 of 6" on the right
        val on = pal.onCommand
        val pad = dp(16f)
        tipTitle.color = alpha(on, e)
        canvas.drawText(t.title, box.left + pad, box.top + pad - tipTitle.ascent(), tipTitle)
        tipBody.color = alpha(on, e * 0.8f)
        if (!t.ask) {
            tipBody.textAlign = Paint.Align.RIGHT
            canvas.drawText("${t.n} of ${t.of}", box.right - pad, box.top + pad - tipTitle.ascent(), tipBody)
            tipBody.textAlign = Paint.Align.LEFT
        }
        tipBody.color = alpha(on, e * 0.92f)
        canvas.save()
        canvas.translate(box.left + pad, box.top + pad + dp(20f) + dp(6f))
        tipLayout(t).draw(canvas)
        canvas.restore()
        // buttons: Dismiss / No thanks on the left (text); Back (text) and Next / Done / Later (filled) on the right
        tipButtons.clear()
        val by = box.bottom - dp(12f) - dp(36f)
        tipButton.textAlign = Paint.Align.CENTER
        val mid = by + dp(18f) - (tipButton.ascent() + tipButton.descent()) / 2
        val main = context.getString(when { t.ask -> R.string.tip_later; t.n >= t.of -> R.string.tip_done; else -> R.string.tip_next })
        val mw = tipButton.measureText(main) + dp(32f)
        val mainRect = RectF(box.right - pad + dp(4f) - mw, by, box.right - pad + dp(4f), by + dp(36f))
        fill.color = alpha(on, e)
        canvas.drawRoundRect(mainRect, dp(18f), dp(18f), fill)
        tipButton.color = alpha(pal.command, e)
        canvas.drawText(main, mainRect.centerX(), mid, tipButton)
        tipButtons += mainRect to (if (t.ask) Tips.Answer.LATER else Tips.Answer.NEXT)
        tipButton.color = alpha(on, e * 0.85f)
        if (!t.ask && t.n > 1) {
            val back = context.getString(R.string.tip_back)
            val bw = tipButton.measureText(back) + dp(24f)
            val backRect = RectF(mainRect.left - dp(4f) - bw, by, mainRect.left - dp(4f), by + dp(36f))
            canvas.drawText(back, backRect.centerX(), mid, tipButton)
            tipButtons += backRect to Tips.Answer.BACK
        }
        val leftLabel = context.getString(if (t.ask) R.string.tip_never else R.string.tip_dismiss)
        val lw = tipButton.measureText(leftLabel) + dp(24f)
        val leftRect = RectF(box.left + pad - dp(12f), by, box.left + pad - dp(12f) + lw, by + dp(36f))
        tipButton.color = alpha(on, e * 0.7f)
        canvas.drawText(leftLabel, leftRect.centerX(), mid, tipButton)
        tipButtons += leftRect to (if (t.ask) Tips.Answer.NEVER else Tips.Answer.DISMISS)
        tipButton.textAlign = Paint.Align.LEFT
        postInvalidateOnAnimation()
    }

    /** A tap in the floating room while a tip shows: its buttons, or nothing. */
    private fun tipTap(x: Float, y: Float): Boolean {
        if (tip == null || y >= barTop) return false
        val pad = dp(6f)
        // nearest first, so neighbouring buttons' padding doesn't steal a tap
        tipButtons.firstOrNull { (r, _) -> r.contains(x, y) }?.let { onTipAnswer?.invoke(it.second); return true }
        tipButtons.firstOrNull { (r, _) -> RectF(r).apply { inset(-pad, -pad) }.contains(x, y) }?.let { onTipAnswer?.invoke(it.second) }
        return true
    }

    /** The "swipe up" hint wants room above the bar. */
    private var floatHint = false

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
    /** The paste button's width while talking (0 when there wasn't one), so polishing keeps it, ghosted. */
    private var talkPasteW = 0f
    private val drawerItems = mutableListOf<Item>()

    private fun rect(l: Float, t: Float, w: Float, h: Float) = RectF(l, t, l + w, t + h)

    /**
     * The part of the bar the buttons use, as (left, width): all of it, or on a wide screen with a
     * side chosen, a phone-width stretch at the left, middle or right, within reach of a thumb.
     */
    internal fun buttonSpan(full: Float, side: String = barSide): Pair<Float, Float> {
        if (side == Prefs.BAR_SIDE_FULL || full < dp(WIDE_DP)) return 0f to full
        val w = dp(SPAN_DP)
        return when (side) {
            Prefs.BAR_SIDE_LEFT -> 0f
            Prefs.BAR_SIDE_RIGHT -> full - w
            else -> (full - w) / 2
        } to w
    }

    private fun layoutAll() {
        items.clear()
        drawerItems.clear()
        if (width <= 0) return
        val (x0, w) = buttonSpan(width.toFloat())
        try { layoutSpan(w) } finally {
            if (x0 != 0f) (items + drawerItems).map { it.rect }.distinctBy { System.identityHashCode(it) }.forEach { it.offset(x0, 0f) }
        }
    }

    private fun layoutSpan(w: Float) {
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
            talking || voice == Bar.Voice.CLEANING -> {
                // dictating: Cursor, ABC and Voice merge into the recording pill; every other key stays
                // exactly where it was (⋮, ⌫, and space and ↵ with the seven-button bar). Polishing
                // keeps the pill's place, with the keys ghosted until the text is in.
                val k = if (talking) 1f else GHOST
                val span = RectF(cursorPill.left, y, voicePill.right, y + b)
                items.add(Item("more", Act.MORE, more, k))
                if (equalBar) items.add(Item("space", Act.SPACE_ALT, slot(1), k))
                if (talking && alt == Alt.TRAY && !equalBar) {
                    // ⌫ held: the same ↵ · space · ⌫ tray as at rest; the recording pill gives it room
                    val trayW = w * TRAY_FRACTION
                    val tray = RectF(w - pad - trayW, y, w - pad, y + b)
                    val sw = (trayW - 2 * gap) / 3
                    items.add(Item("voice", Act.MIC, RectF(span.left, y, tray.left - gap, y + b)))
                    items.add(Item("tray", Act.PANEL, tray, 1f, b / 2))
                    items.add(Item("enter", Act.ENTER, rect(tray.left, y, sw, b)))
                    items.add(Item("space", Act.SPACE_ALT, rect(tray.left + sw + gap, y, sw, b)))
                    items.add(Item("bksp", Act.BACKSPACE, rect(tray.right - sw, y, sw, b)))
                } else {
                    items.add(Item("voice", Act.MIC, span))
                    items.add(Item("tray", Act.PANEL, RectF(bksp), 0f, b / 2))
                    items.add(Item("bksp", Act.BACKSPACE, bksp, k))
                }
                if (equalBar) items.add(Item("enter", Act.ENTER, slot(6), k))
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
            !talking && voice == Bar.Voice.OFF && commandSaid != null -> {
                // "voice bar" heard at rest: the Voice pill grows over Cursor and ABC, so there's
                // room to say what it's waiting for
                items.add(Item("more", Act.MORE, more))
                if (equalBar) items.add(Item("space", Act.SPACE_ALT, slot(1)))
                items.add(Item("voice", Act.MIC, RectF(cursorPill.left, y, voicePill.right, y + b)))
                items.add(Item("tray", Act.PANEL, RectF(bksp), 0f, b / 2))
                items.add(Item("bksp", Act.BACKSPACE, bksp))
                if (equalBar) items.add(Item("enter", Act.ENTER, slot(6)))
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
        items.add(0, Item("padPanel", Act.PANEL, if (padOpen) RectF(dp(10f), top + dp(54f), w - dp(10f), -dp(6f)) else RectF(cursorPill), if (padOpen) 1f else 0f, if (padOpen) dp(PANEL_CORNER_DP) else b / 2))
        val menuOpen = drawer == Drawer.MENU
        items.add(0, Item("menuPanel", Act.PANEL, if (menuOpen) RectF(dp(4f), top + dp(4f), w - dp(4f), -dp(2f)) else RectF(more), if (menuOpen) 1f else 0f, if (menuOpen) dp(PANEL_CORNER_DP) else b / 2))

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
        val chips = listOf(Act.CHIP_SELECT_ALL, Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE)
        val cw = (w - 2 * side - (chips.size - 1) * gap) / chips.size
        var x = side
        for (a in chips) {
            drawerItems.add(Item(a.name, a, rect(x, top + dp(10f), cw, ch)))
            x += cw + gap
        }
        drawerItems.add(Item("pad", Act.PAD, RectF(side, top + dp(54f), w - side, -dp(6f))))
    }

    private fun layoutMenu(w: Float, top: Float) {
        val side = dp(12f)
        val gap = dp(8f)
        val tw = (w - 2 * side - gap) / 2
        val th = (drawerPx - dp(14f) - dp(10f) - 2 * gap) / 3
        // the clipboard history across the top (three cards, scrolling), Settings · Switch keyboard,
        // then this month's cost across the bottom
        val t0 = top + dp(14f)
        val ch = dp(CLIP_ROW_DP)
        val tabs = dp(CLIP_TABS_DP)
        val rest = (drawerPx - dp(14f) - dp(10f) - tabs - ch - 2 * gap) / 2
        drawerItems.add(Item(Act.CLIP_TABS.name, Act.CLIP_TABS, rect(side, t0 - dp(4f), 2 * tw + gap, tabs)))
        val r0 = t0 - dp(4f) + tabs
        drawerItems.add(Item(Act.CLIP_ROW.name, Act.CLIP_ROW, rect(side, r0, 2 * tw + gap, ch)))
        drawerItems.add(Item(Act.TILE_SETTINGS.name, Act.TILE_SETTINGS, rect(side, r0 + ch + gap, tw, rest)))
        drawerItems.add(Item(Act.TILE_SWITCH.name, Act.TILE_SWITCH, rect(side + tw + gap, r0 + ch + gap, tw, rest)))
        drawerItems.add(Item(Act.TILE_COST.name, Act.TILE_COST, rect(side, r0 + ch + gap + rest + gap, 2 * tw + gap, rest)))
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
            if (it.alpha > 0f) a.full = it.alpha
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
            consumed = true // the release isn't one more delete
            onUsed?.invoke("bksp")
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

    /** The arrow target a tap landed on (one step that way), or null: the rest of the pad. */
    private var padArrow: IntArray? = null

    /**
     * Faint arrow targets on the trackpad's edges: (dx, dy) and where each is drawn. A tap on one
     * moves the cursor one step that way; a drag that starts on one is still a trackpad drag.
     */
    private fun padArrows(r: RectF): List<Pair<IntArray, RectF>> {
        val cx = r.centerX()
        val i = dp(8f); val long = dp(64f)
        // left and right are wide, reaching in under the hint toward the middle; up and down
        // keep the middle column between them
        val col = long / 2 + dp(6f)
        return listOf(
            intArrayOf(-1, 0) to RectF(r.left + i, r.top + i, cx - col, r.bottom - i),
            intArrayOf(1, 0) to RectF(cx + col, r.top + i, r.right - i, r.bottom - i),
            intArrayOf(0, -1) to RectF(cx - long / 2, r.top + i, cx + long / 2, r.top + i + dp(30f)),
            intArrayOf(0, 1) to RectF(cx - long / 2, r.bottom - i - dp(30f), cx + long / 2, r.bottom - i),
        )
    }

    /** The arrow under a finger at (x, y), with some slack around what's drawn. */
    private fun padArrowAt(r: RectF, x: Float, y: Float): IntArray? {
        if (!r.contains(x, y)) return null
        val a = padArrows(r)
        return when {
            x < a[0].second.right -> a[0].first
            x > a[1].second.left -> a[1].first
            y < r.centerY() -> a[2].first // the middle column: its top half is up, its bottom half down
            else -> a[3].first
        }
    }

    private fun padDown(x: Float, y: Float) {
        padArrow = drawerItems.firstOrNull { it.act == Act.PAD }?.let { padArrowAt(it.rect, x, y) }
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
        val arrow = padArrow
        // a tap on an arrow: one step that way
        if (arrow != null && !padMoved && !padSelecting) listener?.onStickStep(arrow[0], arrow[1], false)
        if (padMoved && !padSelecting) listener?.onStickTap()
        endPad()
    }

    private fun endPad() {
        removeCallbacks(padHoldRunnable)
        padArrow = null
        padActive = false
        padSelecting = false
        listener?.onStickEnd()
        invalidate()
    }

    // ---------------------------------------------------------------- clipboard and dictation history

    /** In the order the tabs are drawn: dictations first. */
    enum class ClipTab { DICTATIONS, CLIPS }
    /** Which history the ⋮ menu's row shows. */
    var clipTab = ClipTab.DICTATIONS
        private set

    override var recleaning: Long? = null
        set(v) { field = v; invalidate() }
    /** The dictation whose new cleanup was just copied, and until when its card says so. */
    private var recleaned: Long? = null
    private var recleanedUntil = 0L

    override fun showRecleaned(time: Long) {
        recleaned = time
        recleanedUntil = SystemClock.uptimeMillis() + RECLEANED_MS
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        invalidate()
        postDelayed({ invalidate() }, RECLEANED_MS + 20)
    }

    /** One card in the row: a clip, or a dictation ([time] set, [heard] what was said before cleanup). */
    private data class Card(val id: String, val text: String, val pinned: Boolean = false, val time: Long = 0L, val heard: String? = null)

    private fun cards(): List<Card> = when (clipTab) {
        ClipTab.CLIPS -> clips.map { Card(it.text, it.text, it.pinned) }
        // what was said, before cleanup: a tap cleans it again
        ClipTab.DICTATIONS -> dictations.map { Card(it.time.toString(), it.heard.ifBlank { it.typed }, time = it.time, heard = it.heard) }
    }

    /** How far the row has scrolled, in px. */
    private var clipScroll = 0f
    private var clipScrollFrom = 0f
    private var clipDownX = 0f
    private var clipScrolling = false
    private var clipHeld = false
    /** The card showing its two options after a hold, by id (null: none). */
    private var clipOptions: String? = null
    private var clipRowRect: RectF? = null
    private val tabRects = HashMap<ClipTab, RectF>()
    private val clipHoldRunnable = Runnable {
        val r = clipRowRect ?: return@Runnable
        if (clipScrolling) return@Runnable
        clipAt(r, clipDownX)?.let { c ->
            clipHeld = true
            clipOptions = c.id
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun clipCardWidth(r: RectF) = (r.width() - 2 * dp(CLIP_GAP_DP)) / 3f

    private fun clampClipScroll() {
        val r = clipRowRect ?: return
        val max = (cards().size * (clipCardWidth(r) + dp(CLIP_GAP_DP)) - dp(CLIP_GAP_DP) - r.width()).coerceAtLeast(0f)
        clipScroll = clipScroll.coerceIn(0f, max)
    }

    private fun clipAt(r: RectF, x: Float): Card? {
        val step = clipCardWidth(r) + dp(CLIP_GAP_DP)
        val i = ((x - r.left + clipScroll) / step).toInt()
        val within = (x - r.left + clipScroll) - i * step <= clipCardWidth(r)
        return cards().getOrNull(i)?.takeIf { within }
    }

    /** Where tab [tab] is drawn (after a frame), in view coordinates. */
    @VisibleForTesting internal fun testTabCenter(tab: ClipTab): PointF? = tabRects[tab]?.let { PointF(it.centerX(), it.centerY() + barTop) }

    private fun tabUp(x: Float, y: Float) {
        val tab = tabRects.entries.firstOrNull { (_, r) -> RectF(r).apply { inset(-dp(8f), -dp(8f)) }.contains(x, y) }?.key ?: return
        if (tab == clipTab) return
        clipTab = tab
        clipScroll = 0f
        clipOptions = null
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun clipDown(r: RectF, x: Float) {
        clipRowRect = RectF(r)
        clipDownX = x
        clipScrollFrom = clipScroll
        clipScrolling = false
        clipHeld = false
        if (clipOptions == null) postDelayed(clipHoldRunnable, CLIP_HOLD_MS)
    }

    private fun clipMove(x: Float) {
        if (!clipScrolling && abs(x - clipDownX) > dp(8f)) { clipScrolling = true; removeCallbacks(clipHoldRunnable); clipOptions = null }
        if (clipScrolling) { clipScroll = clipScrollFrom - (x - clipDownX); clampClipScroll(); invalidate() }
    }

    private fun clipUp(r: RectF, x: Float, y: Float) {
        removeCallbacks(clipHoldRunnable)
        if (clipScrolling || clipHeld) { clipScrolling = false; clipHeld = false; invalidate(); return }
        val all = cards()
        val c = clipAt(r, x) ?: run { clipOptions = null; invalidate(); return }
        val options = clipOptions
        if (options != null) {
            // a card's two options: the left half (pin / as said), the right half deletes; any other card closes them
            if (c.id == options) {
                val step = clipCardWidth(r) + dp(CLIP_GAP_DP)
                val mid = r.left - clipScroll + all.indexOf(c) * step + clipCardWidth(r) / 2
                val left = x < mid
                when (clipTab) {
                    ClipTab.CLIPS -> listener?.onClip(if (!left) Bar.ClipAction.DELETE else if (c.pinned) Bar.ClipAction.UNPIN else Bar.ClipAction.PIN, c.text)
                    ClipTab.DICTATIONS -> {
                        if (left) { setDrawer(Drawer.NONE); if (talking) endTalk(cancel = true, polish = false) }
                        listener?.onDictation(if (left) Bar.DictationAction.PASTE_HEARD else Bar.DictationAction.DELETE, c.time)
                    }
                }
            }
            clipOptions = null
            invalidate()
            return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // a dictation: cleaned again and copied, with the menu left open to watch it happen
        if (clipTab == ClipTab.DICTATIONS) {
            if (recleaning == null) listener?.onDictation(Bar.DictationAction.RECLEAN, c.time)
            return
        }
        setDrawer(Drawer.NONE)
        // while dictating: this instead of what's being said
        if (talking) endTalk(cancel = true, polish = false)
        listener?.onClip(Bar.ClipAction.PASTE, c.text)
    }

    private fun drawClipTabs(canvas: Canvas, r: RectF, al: Float) {
        val pal = palette
        text.textSize = dp(13f)
        text.textAlign = Paint.Align.LEFT
        // the first tab's pill starts on the cards' left edge; text inset inside it
        var left = r.left
        for (tab in ClipTab.entries) {
            val label = context.getString(if (tab == ClipTab.CLIPS) R.string.clips_tab else R.string.dictations_tab)
            val w = text.measureText(label)
            val on = tab == clipTab
            val tr = RectF(left, r.top + dp(1f), left + w + 2 * dp(12f), r.bottom - dp(3f))
            val x = left + dp(12f)
            left = tr.right + dp(4f)
            tabRects[tab] = tr
            if (on) { fill.color = alpha(pal.tile, al); canvas.drawRoundRect(tr, tr.height() / 2, tr.height() / 2, fill) }
            text.color = alpha(if (on) pal.onBloomKey else pal.onCardinal, al * (if (on) 1f else 0.7f))
            canvas.drawText(label, x, tr.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawClipRow(canvas: Canvas, r: RectF, al: Float) {
        clipRowRect = RectF(r)
        val pal = palette
        val cw = clipCardWidth(r)
        val gap = dp(CLIP_GAP_DP)
        val corner = dp(INNER_CORNER_DP)
        val all = cards()
        canvas.save()
        canvas.clipRect(r)
        if (all.isEmpty()) {
            fill.color = alpha(pal.tile, al * GHOST)
            canvas.drawRoundRect(r, corner, corner, fill)
            text.textSize = dp(13f)
            text.color = alpha(pal.onCardinal, al * 0.8f)
            canvas.drawText(context.getString(if (clipTab == ClipTab.CLIPS) R.string.clips_empty else R.string.dictations_empty),
                r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            canvas.restore()
            return
        }
        clipPaint.textSize = dp(13f)
        val line = clipPaint.fontSpacing
        for ((i, c) in all.withIndex()) {
            val left = r.left - clipScroll + i * (cw + gap)
            if (left > r.right || left + cw < r.left) continue
            val card = RectF(left, r.top, left + cw, r.bottom)
            if (clipTab == ClipTab.DICTATIONS && c.time == recleaned && SystemClock.uptimeMillis() < recleanedUntil) {
                // the new cleanup is on the clipboard
                fill.color = alpha(pal.pressed, al)
                canvas.drawRoundRect(card, corner, corner, fill)
                text.textSize = dp(13f)
                text.color = alpha(pal.onPressed, al)
                canvas.drawText(context.getString(R.string.dictations_copied), card.centerX(), card.centerY() - (text.ascent() + text.descent()) / 2, text)
                continue
            }
            fill.color = alpha(pal.tile, al)
            canvas.drawRoundRect(card, corner, corner, fill)
            if (clipOptions == c.id) {
                // two halves: Pin (Unpin) or As said on the left, Delete on the right
                val half = RectF(card.left, card.top, card.centerX(), card.bottom)
                fill.color = alpha(pal.pressed, al)
                canvas.drawRoundRect(half, corner, corner, fill)
                canvas.drawRect(half.right - corner, half.top, half.right, half.bottom, fill)
                text.textSize = dp(12f)
                text.color = alpha(pal.onPressed, al)
                val leftLabel = when {
                    clipTab == ClipTab.DICTATIONS -> R.string.dictations_as_said
                    c.pinned -> R.string.clips_unpin
                    else -> R.string.clips_pin
                }
                canvas.drawText(context.getString(leftLabel), half.centerX(), half.centerY() - (text.ascent() + text.descent()) / 2, text)
                text.color = alpha(pal.onBloomKey, al)
                canvas.drawText(context.getString(R.string.clips_delete), (card.centerX() + card.right) / 2, card.centerY() - (text.ascent() + text.descent()) / 2, text)
                continue
            }
            val pad = dp(10f)
            val textW = cw - 2 * pad - (if (c.pinned) dp(14f) else 0f)
            clipPaint.color = alpha(pal.onBloomKey, al)
            clipPaint.textAlign = Paint.Align.LEFT
            val words = c.text.replace(Regex("\\s+"), " ").trim()
            val maxLines = ((card.height() - 2 * pad) / line).toInt().coerceIn(1, 3)
            var rest = words
            var y = card.top + pad - clipPaint.ascent()
            for (ln in 0 until maxLines) {
                if (rest.isEmpty()) break
                val shown = if (ln == maxLines - 1) android.text.TextUtils.ellipsize(rest, android.text.TextPaint(clipPaint), textW, android.text.TextUtils.TruncateAt.END).toString()
                    else { val n = clipPaint.breakText(rest, true, textW, null); val cut = rest.take(n); if (n < rest.length && cut.contains(' ')) cut.substringBeforeLast(' ') else cut }
                canvas.drawText(shown, card.left + pad, y, clipPaint)
                rest = rest.drop(shown.length).trimStart()
                y += line
            }
            if (c.pinned) { fill.color = alpha(pal.onBloomKey, al * 0.7f); Icons.draw(canvas, Icons.Glyph.PIN, card.right - pad - dp(6f), card.top + pad + dp(6f), dp(12f), fill) }
            if (clipTab == ClipTab.DICTATIONS && c.time == recleaning) {
                // being cleaned again: the recording pill's polishing glow, sweeping across the card
                canvas.save()
                pulseClip.reset(); pulseClip.addRoundRect(card, corner, corner, android.graphics.Path.Direction.CW)
                canvas.clipPath(pulseClip)
                val p = ((SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat()).let { it * it * (3f - 2f * it) }
                val sw = card.width() * 0.7f
                val sx = card.left - sw + (card.width() + sw) * p
                // the accent, stronger than the pill's: a white card hides a faint glow
                val glow = alpha(pal.pressed, al * 0.75f)
                pulsePaint.shader = android.graphics.LinearGradient(sx, 0f, sx + sw, 0f,
                    intArrayOf(glow and 0x00ffffff, glow, glow and 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(sx, card.top, sx + sw, card.bottom, pulsePaint)
                canvas.restore()
                postInvalidateOnAnimation()
            }
        }
        canvas.restore()
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
        if (e.actionMasked == MotionEvent.ACTION_DOWN && tip != null && e.y < barTop) return true
        if (e.actionMasked == MotionEvent.ACTION_UP && tip != null && e.y < barTop && pressed == null) return tipTap(e.x, e.y)
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
        val all = drawerItems + items.filter { it.act != Act.PANEL && it.alpha != GHOST }
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
            Act.CLIP_ROW -> { clipDown(it.rect, x); return }
            Act.CLIP_TABS -> return
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

    // ---------------------------------------------------------------- swiping the voice pill

    /** The voice pill is being dragged sideways while dictating: right sends, left cancels. */
    private var swiping = false
    private var swipeDx = 0f
    /** -1 past the cancel mark, 1 past the send mark, else 0. */
    private var swipeArmed = 0
    /** A let-go short of either mark springs back from here. */
    private var springFrom = 0f
    private var springAt = 0L

    private fun swipeMark(r: RectF) = min(dp(SWIPE_MARK_DP), r.width() * 0.4f)

    /** How far the pill is drawn off center right now. */
    private fun swipeOffset(now: Long): Float {
        if (swiping) return swipeDx
        if (springFrom == 0f) return 0f
        val t = ((now - springAt) / SPRING_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) { springFrom = 0f; return 0f }
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return springFrom * (1f - e)
    }

    private fun swipeMove(x: Float, r: RectF) {
        val lim = r.width() * 0.6f
        swipeDx = (x - downX).coerceIn(-lim, lim)
        val mark = swipeMark(r)
        val armed = when { swipeDx >= mark -> 1; swipeDx <= -mark -> -1; else -> 0 }
        if (armed != swipeArmed) {
            swipeArmed = armed
            performHapticFeedback(if (armed != 0) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
        invalidate()
    }

    /** Let go: past a mark, its action; short of it, the pill springs back. */
    private fun swipeEnd(commit: Boolean) {
        val armed = if (commit) swipeArmed else 0
        swiping = false
        swipeArmed = 0
        if (armed == 0 || !talking) {
            springFrom = swipeDx; springAt = SystemClock.uptimeMillis()
        } else {
            springFrom = 0f
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            if (armed > 0) {
                endTalk(cancel = false, polish = true)
                listener?.onSendAfterTalk()
            } else endTalk(cancel = true, polish = false)
        }
        swipeDx = 0f
        invalidate()
    }

    @VisibleForTesting internal fun testSwipeMark() = swipeMark(rectOf("voice")!!)

    private fun onMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (p.act == Act.CLIP_ROW) { clipMove(x); return }
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
        // the purple "voice bar" pill swiped up: not a command after all
        if (p.act == Act.MIC && commandSaid != null && !swiping && !sliding &&
            downY - y > dp(SWIPE_UP_DP) && downY - y > abs(x - downX)) {
            consumed = true
            pressed = null
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener?.onDismissCommand()
            invalidate()
            return
        }
        // dictating: the voice pill slides sideways, right to send and left to cancel
        if (p.act == Act.MIC && talking) {
            if (!swiping && abs(x - downX) > dp(SLIDE_START_DP)) swiping = true
            if (swiping) swipeMove(x, p.rect)
            return
        }
        // a sideways slide on the space pill, or on the idle Voice pill, moves the cursor
        if (((p.act == Act.SPACE_ALT && extraKey == Bar.ExtraKey.SPACE) || (p.act == Act.MIC && !talking)) && !sliding && abs(x - downX) > dp(SLIDE_START_DP)) {
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
            pressed = null
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed
        pressed = null
        removeCallbacks(repeatRunnable)
        removeCallbacks(altRunnable)
        if (swiping) { swipeEnd(commit = true); return }
        if (p == null) { alt = Alt.NONE; invalidate(); return }
        if (p.act == Act.PAD) { padUp(); return }
        if (p.act == Act.CLIP_ROW) { clipUp(p.rect, x, y); return }
        if (p.act == Act.CLIP_TABS) { tabUp(x, y); return }
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
        if (swiping) swipeEnd(commit = false)
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
            Act.CHIP_SELECT_ALL -> l?.onEdit(Bar.EditAction.SELECT_ALL)
            Act.CHIP_CUT -> if (hasSelection) l?.onEdit(Bar.EditAction.CUT)
            Act.CHIP_COPY -> if (hasSelection) l?.onEdit(Bar.EditAction.COPY)
            Act.CHIP_DELETE -> l?.onEdit(Bar.EditAction.DELETE)
            Act.CURSOR -> { onUsed?.invoke("cursor"); setDrawer(if (drawer == Drawer.PAD) Drawer.NONE else Drawer.PAD) }
            // typing is Gboard's job: ABC hands the field over
            Act.KEYBOARD -> if (voice == Bar.Voice.CLEANING && !talking) l?.onTypeAfterTalk() else if (talking) {
                // finish what's being said (polished), then Gboard takes over
                endTalk(cancel = false, polish = true)
                l?.onTypeAfterTalk()
            } else { onUsed?.invoke("abc"); l?.onSwitchToGboard() }
            Act.MORE -> setDrawer(if (drawer == Drawer.MENU) Drawer.NONE else Drawer.MENU)
            Act.ENTER -> { l?.onKey(Bar.Key.ENTER, false) }
            Act.BACKSPACE -> { listener?.onKey(Bar.Key.DELETE, false); lastBkspTap = SystemClock.uptimeMillis() } // a tap: on release, so a hold can open the tray
            Act.TILE_SETTINGS, Act.TILE_COST -> { setDrawer(Drawer.NONE); l?.onOpenSettings() }
            Act.TILE_SWITCH -> { setDrawer(Drawer.NONE); l?.onSwitchKeyboard() }
            Act.TILE_PASTE -> if (!clipPreview.isNullOrBlank()) {
                setDrawer(Drawer.NONE)
                // while dictating: the clipboard instead of what's been said (as the paste button did)
                if (talking) endTalk(cancel = true, polish = false)
                l?.onEdit(Bar.EditAction.PASTE)
            }
            // the equal layout's own key (in the ⌫ tray, space is handled on release instead); mid-dictation
            // it acts at the cursor like any key, so a paste there keeps what's been said
            Act.SPACE_ALT -> when (extraKey) {
                Bar.ExtraKey.SPACE -> l?.onKey(Bar.Key.SPACE, false)
                Bar.ExtraKey.PASTE -> l?.onEdit(Bar.EditAction.PASTE)
                Bar.ExtraKey.UNDO -> l?.onKey(Bar.Key.UNDO, false)
                Bar.ExtraKey.SELECT_ALL -> l?.onEdit(Bar.EditAction.SELECT_ALL)
                Bar.ExtraKey.LAST_DICTATION -> l?.onRepeatDictation()
            }
            Act.PAD, Act.PANEL, Act.CLEAR_ALL, Act.CLIP_ROW, Act.CLIP_TABS -> Unit
        }
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
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
        // the surface starts below any floating room (that stays see-through, over the app)
        val surfTop = floatRoom().toFloat()
        fill.color = pal.disc
        canvas.drawRect(0f, surfTop, width.toFloat(), height.toFloat(), fill)
        // a hairline where the app ends and the keyboard begins
        fill.color = pal.outline
        canvas.drawRect(0f, surfTop, width.toFloat(), surfTop + max(1f, dp(1f)), fill)
        if (commandSaid != null) drawWaveOffHint(canvas, now)
        else if (tip != null && drawer == Drawer.NONE && !drawerRoom) drawTip(canvas, now)
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
            // solid surface container, like the settings page behind its rows
            "menuPanel" -> alpha(pal.disc, al)
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
        // ghosted controls keep their full size; only coming and going scales them
        val s = 0.35f + 0.65f * (al / a.full).coerceAtMost(1f)
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
        val sw = swipeOffset(now)
        if (sw == 0f) { drawVoicePillBody(canvas, r, rad, al, now, down && !swiping); return }
        // sliding: the pill squeezes away from the finger's side, uncovering where it's headed;
        // past the mark it says what letting go will do
        drawSwipeTrack(canvas, r, rad, al, sw)
        swipeBody.set(r)
        if (sw > 0f) swipeBody.left += sw else swipeBody.right += sw
        val armed = swiping && abs(sw) >= swipeMark(r)
        swipeNote = if (!armed) null else context.getString(if (sw > 0f) R.string.dock_let_go_send else R.string.dock_let_go_cancel)
        drawVoicePillBody(canvas, swipeBody, rad, al, now, false)
        swipeNote = null
        if (!swiping) postInvalidateOnAnimation()
    }

    private val swipeBody = RectF()

    /** Which gesture hint to draw: 0 the text, 1 arrow-tag chips, 2 end handles (the one in use). */
    @VisibleForTesting internal var hintStyle = 2

    private val hintPath = android.graphics.Path()
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = android.graphics.CornerPathEffect(0f) }

    /**
     * The gesture hint with some shape to it: "Tap to finish" in the middle, and Cancel and Send
     * as chips at the ends that point the way to swipe (1), or as round handles at the pill's ends (2).
     */
    /** Whether the shaped hint has room for "Tap to finish" between its ends. */
    private fun gestureHintFits(r: RectF): Boolean {
        text.textSize = dp(13.5f)
        val mid = text.measureText(context.getString(R.string.dock_tap_to_finish))
        text.textSize = dp(14f)
        val ends = if (hintStyle == 1) 2 * (dp(14f) + dp(9f) + dp(15f) + dp(11f)) else 2 * (r.height() - dp(8f) + dp(28f))
        return ends + mid + dp(16f) <= r.width() - dp(12f)
    }

    private fun drawGestureHint(canvas: Canvas, r: RectF, hint: Float, al: Float, fg: Int, withMid: Boolean) {
        val pal = palette
        val cy = r.centerY()
        val a = al * min(1f, hint * 1.3f)
        val slide = dp(14f) * (1f - hint)
        val mid = context.getString(R.string.dock_tap_to_finish)
        val cancel = context.getString(R.string.dock_swipe_cancel)
        val send = context.getString(R.string.dock_swipe_send)
        text.textAlign = Paint.Align.CENTER
        if (hintStyle == 1) {
            val h = dp(28f)
            val point = dp(14f)
            val pad = dp(9f)
            val g = dp(15f)
            text.textSize = dp(12.5f)
            text.isFakeBoldText = true
            val midW = run { val t = text.textSize; text.textSize = dp(13.5f); text.isFakeBoldText = false; val w = text.measureText(mid); text.textSize = t; text.isFakeBoldText = true; w }
            fun chipW(label: String?) = point + pad + g + (if (label != null) dp(5f) + text.measureText(label) else 0f) + pad + dp(2f)
            val inset = dp(6f)
            var labels = true
            if (chipW(cancel) + chipW(send) + midW + dp(24f) > r.width() - 2 * inset) labels = false
            val showMid = withMid && chipW(null) * 2 + midW + dp(20f) <= r.width() - 2 * inset
            // left: points left, error container; right: points right, primary container
            fun tag(x0: Float, w: Float, left: Boolean, bg: Int, on: Int, glyph: Icons.Glyph, label: String?) {
                val top = cy - h / 2; val bot = cy + h / 2; val x1 = x0 + w
                hintPath.reset()
                if (left) {
                    hintPath.moveTo(x0, cy)
                    hintPath.lineTo(x0 + point, top)
                    hintPath.lineTo(x1 - h / 2, top)
                    hintPath.arcTo(x1 - h, top, x1, bot, -90f, 180f, false)
                    hintPath.lineTo(x0 + point, bot)
                } else {
                    hintPath.moveTo(x1, cy)
                    hintPath.lineTo(x1 - point, bot)
                    hintPath.lineTo(x0 + h / 2, bot)
                    hintPath.arcTo(x0, top, x0 + h, bot, 90f, 180f, false)
                    hintPath.lineTo(x1 - point, top)
                }
                hintPath.close()
                tagPaint.pathEffect = android.graphics.CornerPathEffect(dp(3f))
                tagPaint.color = alpha(bg, a)
                canvas.drawPath(hintPath, tagPaint)
                fill.color = alpha(on, a)
                text.color = alpha(on, a)
                val base = cy - (text.ascent() + text.descent()) / 2
                if (left) {
                    var x = x0 + point + pad - dp(2f)
                    Icons.draw(canvas, glyph, x + g / 2, cy, g, fill); x += g
                    if (label != null) { text.textAlign = Paint.Align.LEFT; canvas.drawText(label, x + dp(5f), base, text) }
                } else {
                    var x = x1 - point - pad + dp(2f)
                    Icons.draw(canvas, glyph, x - g / 2, cy, g, fill); x -= g
                    if (label != null) { text.textAlign = Paint.Align.RIGHT; canvas.drawText(label, x - dp(5f), base, text) }
                }
                text.textAlign = Paint.Align.CENTER
            }
            val lw = chipW(if (labels) cancel else null)
            val rw = chipW(if (labels) send else null)
            tag(r.left + inset - slide, lw, true, pal.discard, pal.onDiscard, Icons.Glyph.DISCARD, if (labels) cancel else null)
            tag(r.right - inset - rw + slide, rw, false, pal.center, pal.onCenter, Icons.Glyph.SEND, if (labels) send else null)
            text.isFakeBoldText = false
            if (showMid) {
                text.textSize = dp(13.5f)
                text.color = alpha(fg, a)
                val c = (r.left + inset + lw + r.right - inset - rw) / 2
                canvas.drawText(mid, c, cy - (text.ascent() + text.descent()) / 2, text)
            }
        } else {
            // round handles tucked into the pill's ends, with chevrons pointing out toward them
            val d = r.height() - dp(8f)
            val g = dp(17f)
            val lc = r.left + dp(4f) + d / 2 - slide
            val rc = r.right - dp(4f) - d / 2 + slide
            fill.color = alpha(pal.discard, a); canvas.drawCircle(lc, cy, d / 2, fill)
            fill.color = alpha(pal.onDiscard, a); Icons.draw(canvas, Icons.Glyph.DISCARD, lc, cy, g, fill)
            fill.color = alpha(pal.center, a); canvas.drawCircle(rc, cy, d / 2, fill)
            fill.color = alpha(pal.onCenter, a); Icons.draw(canvas, Icons.Glyph.SEND, rc + dp(1f), cy, g, fill)
            // chevrons: ‹‹ beside the left handle, ›› beside the right, fading toward the middle
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val ch = dp(5f)
            for (i in 0..1) {
                stroke.color = alpha(fg, a * (if (i == 0) 0.85f else 0.45f))
                val lx = lc + d / 2 + dp(9f) + i * dp(8f)
                hintPath.reset(); hintPath.moveTo(lx + ch * 0.6f, cy - ch); hintPath.lineTo(lx, cy); hintPath.lineTo(lx + ch * 0.6f, cy + ch)
                canvas.drawPath(hintPath, stroke)
                val rx = rc - d / 2 - dp(9f) - i * dp(8f)
                hintPath.reset(); hintPath.moveTo(rx - ch * 0.6f, cy - ch); hintPath.lineTo(rx, cy); hintPath.lineTo(rx - ch * 0.6f, cy + ch)
                canvas.drawPath(hintPath, stroke)
            }
            text.textSize = dp(13.5f)
            val room = (rc - d / 2 - dp(28f)) - (lc + d / 2 + dp(28f))
            if (withMid && text.measureText(mid) <= room) {
                text.color = alpha(fg, a)
                canvas.drawText(mid, r.centerX(), cy - (text.ascent() + text.descent()) / 2, text)
            }
        }
        text.textSize = dp(14f)
    }
    /** Drawn in the pill instead of the waveform while a swipe is past its mark. */
    private var swipeNote: String? = null

    private fun drawSwipeTrack(canvas: Canvas, r: RectF, rad: Float, al: Float, sw: Float) {
        val pal = palette
        val send = sw > 0f
        val p = (abs(sw) / swipeMark(r)).coerceIn(0f, 1f)
        val armed = p >= 1f
        val (bg, fg) = when {
            send && armed -> pal.onCenter to pal.center
            send -> pal.center to pal.onCenter
            armed -> pal.error to pal.onError
            else -> pal.discard to pal.onDiscard
        }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        // the icon (and the label, once there's room) in the space the pill has left
        val gap = abs(sw)
        val label = context.getString(if (send) R.string.dock_swipe_send else R.string.dock_swipe_cancel)
        text.textSize = dp(14f)
        text.isFakeBoldText = armed
        val iw = dp(18f)
        val total = iw + dp(6f) + text.measureText(label)
        val a = al * ((gap - dp(12f)) / dp(36f)).coerceIn(0f, 1f)
        if (a > 0.01f) {
            fill.color = alpha(fg, a)
            text.color = alpha(fg, a)
            val cy = r.centerY()
            val base = cy - (text.ascent() + text.descent()) / 2
            val s = 0.8f + 0.2f * p
            val withLabel = gap >= total + dp(28f)
            val glyph = if (send) Icons.Glyph.SEND else Icons.Glyph.DISCARD
            // the uncovered gap: from the pill's edge to where the squeezed pill now starts
            val g0 = if (send) r.left else r.right - gap
            val gc = g0 + gap / 2
            canvas.save(); canvas.scale(s, s, gc, cy)
            if (withLabel) {
                val x0 = gc - total / 2
                Icons.draw(canvas, glyph, x0 + iw / 2, cy, iw, fill)
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, x0 + iw + dp(6f), base, text)
            } else Icons.draw(canvas, glyph, gc, cy, iw, fill)
            canvas.restore()
            text.textAlign = Paint.Align.CENTER
        }
        text.isFakeBoldText = false
    }

    private val pulseClip = android.graphics.Path()
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawVoicePillBody(canvas: Canvas, r: RectF, rad: Float, al: Float, now: Long, down: Boolean) {
        val pal = palette
        val cleaning = voice == Bar.Voice.CLEANING && !talking
        val message = voice == Bar.Voice.MESSAGE && !talking
        val small = false
        // "voice bar" heard: Material You purple, fading in (and back out when it's let go)
        val cmdT = ((SystemClock.uptimeMillis() - commandAt) / COMMAND_FADE_MS.toFloat()).coerceIn(0f, 1f)
        val cmd = if (!talking && voice != Bar.Voice.OFF) 0f else if (commandSaid != null) cmdT else 1f - cmdT
        if (cmd in 0.001f..0.999f) postInvalidateOnAnimation()
        val bg0 = when {
            down -> pal.onCenter
            cleaning -> pal.center
            message -> if (messageGood) pal.center else pal.discard
            small -> pal.cardinal
            else -> pal.pressed
        }
        val bg = if (cmd > 0f) ColorUtils.blendARGB(bg0, pal.command, cmd) else bg0
        val fg0 = when {
            down -> pal.center
            cleaning -> pal.onCenter
            message -> if (messageGood) pal.onCenter else pal.onDiscard
            small -> pal.onCardinal
            else -> pal.onPressed
        }
        val fg = if (cmd > 0f) ColorUtils.blendARGB(fg0, pal.onCommand, cmd) else fg0
        if (cmd > 0f) {
            // a soft glow, breathing while it listens for the command
            val breathe = 0.65f + 0.35f * sin(now / 260.0).toFloat()
            for (i in 1..5) {
                fill.color = alpha(pal.command, al * cmd * breathe * 0.2f * (1f - i / 6f))
                tmp.set(r); tmp.inset(-i * dp(1.1f), -i * dp(1.1f))
                canvas.drawRoundRect(tmp, rad + i * dp(1.1f), rad + i * dp(1.1f), fill)
            }
            postInvalidateOnAnimation()
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
                // a soft glow sweeping left to right inside the pill, under the label
                canvas.save()
                pulseClip.reset(); pulseClip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
                canvas.clipPath(pulseClip)
                val p = ((now % PULSE_MS) / PULSE_MS.toFloat()).let { it * it * (3f - 2f * it) }
                val sw = r.width() * 0.45f
                val sx = r.left - sw + (r.width() + sw) * p
                val glow = alpha(fg, al * 0.22f)
                pulsePaint.shader = android.graphics.LinearGradient(sx, 0f, sx + sw, 0f,
                    intArrayOf(glow and 0x00ffffff, glow, glow and 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(sx, r.top, sx + sw, r.bottom, pulsePaint)
                canvas.restore()
                canvas.drawText(context.getString(R.string.dock_polishing), cx, base, text)
                postInvalidateOnAnimation()
            }
            message -> {
                var t = voiceText
                while (t.isNotEmpty() && text.measureText(t) > r.width() - dp(24f)) t = t.dropLast(1)
                canvas.drawText(t, cx, base, text)
            }
            talking && swipeNote != null -> {
                val note = swipeNote.orEmpty()
                text.isFakeBoldText = true
                val fit = r.width() - dp(20f)
                if (text.measureText(note) > fit) text.textSize = dp(14f) * fit / text.measureText(note)
                canvas.drawText(note, cx, cy - (text.ascent() + text.descent()) / 2, text)
                text.isFakeBoldText = false
                text.textSize = dp(14f)
            }
            talking && commandSaid != null -> {
                // what's being said after "voice bar", instead of the waveform and timer
                val said = commandSaid.orEmpty()
                val label = if (said.isBlank()) context.getString(R.string.dock_command_wait)
                else context.getString(R.string.dock_command_said, said)
                text.isFakeBoldText = commandUnderstood
                val fit = r.width() - dp(64f)
                if (text.measureText(label) > fit) text.textSize = dp(14f) * fit / text.measureText(label)
                canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2, text)
                text.isFakeBoldText = false
                text.textSize = dp(14f)
                drawUpChevron(canvas, r.right - dp(22f), cy, alpha(fg, al * 0.75f))
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
                // the whole "← Cancel · Tap to finish · Send →" when it fits; otherwise "Tap to
                // finish" and "← Cancel · Send →" take turns
                text.textSize = dp(14f)
                val full = context.getString(R.string.dock_hint_full)
                val fits = text.measureText(full) <= r.width() - dp(28f)
                // shaped hints: everything at once when it fits, otherwise "Tap to finish", then the shapes
                val shapesFit = hintStyle != 0 && gestureHintFits(r)
                val (hintIdx, hintP) = if (showText) 0 to 0f else hintAt(now, if ((hintStyle == 0 && fits) || shapesFit) 1 else 2)
                val shapes = hintStyle != 0 && (shapesFit || hintIdx == 1)
                val hint = hintP
                val hintText = when {
                    hintStyle != 0 -> context.getString(R.string.dock_tap_to_finish)
                    fits -> full
                    hintIdx == 0 -> context.getString(R.string.dock_tap_to_finish)
                    else -> context.getString(R.string.dock_swipe_hint)
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
                val open = if (shapes) r.width() / 2 * hint else tw / 2 * hint + dp(10f) * hint
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
                if (hint > 0f && shapes) {
                    drawGestureHint(canvas, r, hint, al, fg, withMid = shapesFit)
                } else if (hint > 0f) {
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
            commandSaid != null -> {
                // idle, and "voice bar" just heard: waiting for "listen"
                val full = context.getString(R.string.dock_wake_listen)
                val label = if (text.measureText(full) <= r.width() - dp(64f)) full else context.getString(R.string.dock_command_wait)
                val fit = r.width() - dp(56f)
                drawUpChevron(canvas, r.right - dp(22f), cy, alpha(fg, al * 0.75f))
                if (text.measureText(label) > fit) text.textSize = dp(14f) * fit / text.measureText(label)
                canvas.drawText(label, cx, base, text)
                text.textSize = dp(14f)
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
     * Floating just above the bar, over the app: a purple chip pointing up at the swipe, so it's
     * clear the purple pill can be waved off ("keep as words" while dictating).
     */
    private fun drawWaveOffHint(canvas: Canvas, now: Long) {
        val v = anims["voice"]?.rect ?: return
        val pal = palette
        val t = ((now - commandAt) / 260f).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t)
        val label = context.getString(if (talking) R.string.dock_waveoff_talking else R.string.dock_waveoff_rest)
        text.textSize = dp(13f)
        text.isFakeBoldText = true
        val iw = dp(14f)
        val cw = dp(14f) + iw + dp(6f) + text.measureText(label) + dp(16f)
        val ch = dp(30f)
        val cx = (v.centerX()).coerceIn(cw / 2 + dp(8f), width - cw / 2 - dp(8f))
        val bottom = barTop - dp(8f) + dp(10f) * (1f - e)
        tmp.set(cx - cw / 2, bottom - ch, cx + cw / 2, bottom)
        // a soft shadow, then the chip
        for (i in 1..4) {
            fill.color = Color.argb((14 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(tmp.left - i * dp(1f), tmp.top - i * dp(1f) + dp(1.5f), tmp.right + i * dp(1f), tmp.bottom + i * dp(1f) + dp(1.5f), ch / 2 + i * dp(1f), ch / 2 + i * dp(1f), fill)
        }
        fill.color = alpha(pal.command, e)
        canvas.drawRoundRect(tmp, ch / 2, ch / 2, fill)
        // a little tail pointing down at the pill
        val tail = android.graphics.Path().apply {
            moveTo(cx - dp(6f), tmp.bottom - dp(1f)); lineTo(cx, tmp.bottom + dp(5f)); lineTo(cx + dp(6f), tmp.bottom - dp(1f)); close()
        }
        canvas.drawPath(tail, fill)
        // the arrow nudges upward, over and over
        val bob = dp(2.5f) * (0.5f + 0.5f * sin(now / 180.0).toFloat())
        val ax = tmp.left + dp(14f) + iw / 2
        drawUpChevron(canvas, ax, tmp.centerY() + dp(1f) - bob, alpha(pal.onCommand, e))
        text.color = alpha(pal.onCommand, e)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, ax + iw / 2 + dp(6f), tmp.centerY() - (text.ascent() + text.descent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = false
        text.textSize = dp(14f)
        postInvalidateOnAnimation()
    }

    /** A small ^ at the purple pill's end: swipe up to wave "voice bar" off. */
    private fun drawUpChevron(canvas: Canvas, x: Float, cy: Float, c: Int) {
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = c }
        val h = dp(4f); val w = dp(6f)
        for (i in 0..1) {
            val y = cy + dp(1f) - i * dp(6f) + dp(2f)
            st.alpha = (android.graphics.Color.alpha(c) * (if (i == 0) 1f else 0.55f)).toInt()
            canvas.drawLine(x - w, y + h / 2, x, y - h / 2, st)
            canvas.drawLine(x, y - h / 2, x + w, y + h / 2, st)
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
            Act.CHIP_SELECT_ALL, Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE -> {
                val enabled = when (it.act) {
                    Act.CHIP_CUT, Act.CHIP_COPY -> hasSelection
                    Act.CHIP_PASTE -> canPaste
                    else -> true
                }
                val a = al * if (enabled) 1f else DISABLED_ALPHA
                fill.color = alpha(if (down) pal.pressed else pal.diagonal, a)
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                val fg = alpha(if (down) pal.onPressed else pal.onDiagonal, a)
                val g = when (it.act) { Act.CHIP_SELECT_ALL -> Icons.Glyph.SELECT_ALL; Act.CHIP_CUT -> Icons.Glyph.CUT; Act.CHIP_COPY -> Icons.Glyph.COPY; Act.CHIP_PASTE -> Icons.Glyph.PASTE; else -> Icons.Glyph.BACKSPACE }
                text.textSize = dp(13f)
                text.color = fg
                val iw = dp(18f)
                // "Select all" when it fits, else "All"; any label that still won't fit is left off
                val label = context.getString(when (it.act) {
                    Act.CHIP_SELECT_ALL -> if (iw + dp(6f) + text.measureText(context.getString(R.string.dock_select_all)) <= r.width() - dp(16f)) R.string.dock_select_all else R.string.dock_all
                    Act.CHIP_CUT -> R.string.dock_cut; Act.CHIP_COPY -> R.string.dock_copy; Act.CHIP_PASTE -> R.string.dock_paste; else -> R.string.dock_delete
                }).let { l -> if (iw + dp(6f) + text.measureText(l) <= r.width() - dp(12f)) l else "" }
                val total = if (label.isEmpty()) iw else iw + dp(6f) + text.measureText(label)
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
                val arrows = padArrows(r)
                if (!padSelecting) for ((d, a) in arrows) {
                    val hot = padActive && !padMoved && padArrow?.contentEquals(d) == true
                    val side = d[0] != 0
                    // very faint, under the hint: the pad still reads as a pad, the targets as somewhere to tap
                    fill.color = alpha(if (hot) pal.pressed else pal.onCardinal, (if (hot) 0.22f else if (side) 0.03f else 0.045f) * al)
                    canvas.drawRoundRect(a, dp(20f), dp(20f), fill)
                    arrowPaint.color = alpha(if (hot) pal.pressed else pal.onCardinal, (if (hot) 1f else 0.28f) * al)
                    val c = dp(5f)
                    // the side chevrons sit out by the edges, clear of the hint
                    val ax = when { d[0] < 0 -> a.left + dp(20f); d[0] > 0 -> a.right - dp(20f); else -> a.centerX() }
                    val ay = a.centerY()
                    val path = android.graphics.Path()
                    when {
                        d[0] < 0 -> { path.moveTo(ax + c * 0.6f, ay - c); path.lineTo(ax - c * 0.6f, ay); path.lineTo(ax + c * 0.6f, ay + c) }
                        d[0] > 0 -> { path.moveTo(ax - c * 0.6f, ay - c); path.lineTo(ax + c * 0.6f, ay); path.lineTo(ax - c * 0.6f, ay + c) }
                        d[1] < 0 -> { path.moveTo(ax - c, ay + c * 0.6f); path.lineTo(ax, ay - c * 0.6f); path.lineTo(ax + c, ay + c * 0.6f) }
                        else -> { path.moveTo(ax - c, ay - c * 0.6f); path.lineTo(ax, ay + c * 0.6f); path.lineTo(ax + c, ay - c * 0.6f) }
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                // the hint on top, between the side chevrons
                val room = arrows[1].second.right - arrows[0].second.left - dp(80f)
                val shown = android.text.TextUtils.ellipsize(msg, android.text.TextPaint(text), room, android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(shown, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                if (padActive) {
                    val px = padX.coerceIn(r.left, r.right)
                    val py = padY.coerceIn(r.top, r.bottom)
                    fill.color = alpha(pal.pressed, 0.22f * al)
                    canvas.drawCircle(px, py, dp(26f), fill)
                    fill.color = alpha(pal.pressed, al)
                    canvas.drawCircle(px, py, dp(6f), fill)
                }
            }
            Act.CLIP_ROW -> drawClipRow(canvas, r, al)
            Act.CLIP_TABS -> drawClipTabs(canvas, r, al)
            Act.TILE_SETTINGS, Act.TILE_SWITCH, Act.TILE_COST, Act.TILE_PASTE -> {
                val al = al * it.alpha
                fill.color = alpha(if (down) pal.pressed else pal.tile, al)
                canvas.drawRoundRect(r, dp(INNER_CORNER_DP), dp(INNER_CORNER_DP), fill)
                val fg = alpha(if (down) pal.onPressed else pal.onBloomKey, al)
                val sub = alpha(if (down) pal.onPressed else pal.onCardinal, al)
                val ix = r.left + dp(20f)
                val iy = r.centerY()
                when (it.act) {
                    Act.TILE_SETTINGS -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SETTINGS, ix + dp(11f), iy, dp(22f), fill) }
                    Act.TILE_SWITCH -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.SWITCH_KEYBOARD, ix + dp(11f), iy, dp(22f), fill) }
                    Act.TILE_PASTE -> { fill.color = sub; Icons.draw(canvas, Icons.Glyph.PASTE, ix + dp(11f), iy, dp(22f), fill) }
                    else -> { text.color = sub; text.textSize = dp(18f); canvas.drawText("$", ix + dp(11f), iy - (text.ascent() + text.descent()) / 2, text) }
                }
                text.textAlign = Paint.Align.LEFT
                text.color = fg
                text.textSize = dp(14f)
                val title = context.getString(when (it.act) {
                    Act.TILE_SETTINGS -> R.string.dock_settings
                    Act.TILE_SWITCH -> R.string.dock_switch
                    Act.TILE_PASTE -> if (clipPreview.isNullOrBlank()) R.string.dock_paste_empty else R.string.dock_paste
                    else -> R.string.dock_cost
                })
                val tx = ix + dp(34f)
                val sub2 = when {
                    it.act == Act.TILE_COST -> costText
                    it.act == Act.TILE_PASTE -> clipPreview.orEmpty()
                    else -> ""
                }
                if (sub2.isNotEmpty()) {
                    canvas.drawText(title, tx, iy - dp(3f), text)
                    clipPaint.color = sub
                    clipPaint.textSize = dp(12f)
                    val shown = android.text.TextUtils.ellipsize(sub2, clipPaint, r.right - dp(16f) - tx, android.text.TextUtils.TruncateAt.END).toString()
                    canvas.drawText(shown, tx, iy + dp(14f), clipPaint)
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
            Act.SPACE_ALT -> when (if (equalBar) extraKey else Bar.ExtraKey.SPACE) {
                Bar.ExtraKey.SPACE -> drawSpace(canvas, x, y, size * 0.42f, c)
                Bar.ExtraKey.PASTE -> Icons.draw(canvas, Icons.Glyph.PASTE, x, y, size, fill)
                Bar.ExtraKey.UNDO -> Icons.draw(canvas, Icons.Glyph.UNDO, x, y, size, fill)
                Bar.ExtraKey.SELECT_ALL -> Icons.draw(canvas, Icons.Glyph.SELECT_ALL, x, y, size, fill)
                Bar.ExtraKey.LAST_DICTATION -> Icons.draw(canvas, Icons.Glyph.HISTORY, x, y, size, fill)
            }
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
        /** Wider than this, the buttons can keep to one side; and how wide they are then. */
        const val WIDE_DP = 600f
        const val SPAN_DP = 440f
        const val PULSE_MS = 1400L
        const val DRAWER_DP = 220f
        const val CLIP_TABS_DP = 30f
        /** Material 3 shape scale: panels are extra large (28), what sits inside them large (16): 28 less their 12 inset. */
        const val PANEL_CORNER_DP = 28f
        const val INNER_CORNER_DP = 16f
        /** The clipboard-history row in the ⋮ menu: card height, gap, and how long a hold is. */
        const val CLIP_ROW_DP = 72f
        const val CLIP_GAP_DP = 8f
        const val CLIP_HOLD_MS = 450L
        /** How long a dictation card says "Copied" after its new cleanup goes on the clipboard. */
        const val RECLEANED_MS = 1500L
        const val NAV_GESTURE = 2
        const val WAVE_HISTORY = 96
        const val DRAIN_AFTER_MS = 5000L
        const val MESSAGE_MS = 2600L
        /** Held words (auto-listen in a chat) wait this long for a tap. */
        const val HELD_MS = 60_000L
        /** How faint a control is when it's still there but can't be pressed (Material's disabled 38%). */
        const val GHOST = 0.38f
        /** The "Tap to finish recording" hint: shown first, then back every 5 s. */
        const val HINT_QUIET_MS = 3000L
        const val HINT_EVERY_MS = 10_000L
        const val HINT_MORPH_MS = 650L
        const val HINT_HOLD_MS = 1100L
        /** Holding the voice pill this long while dictating sends. */
        /** How far the voice pill must slide to send (right) or cancel (left), at most. */
        const val SWIPE_MARK_DP = 84f
        const val SPRING_MS = 260L
        const val COMMAND_FADE_MS = 220L
        /** The see-through room above the bar for the floating "swipe up" hint. */
        const val FLOAT_DP = 50f
        /** With gesture navigation, the room kept under the bar for the handle. */
        const val GESTURE_ROOM_MIN_DP = 16f
        const val GESTURE_ROOM_MAX_DP = 22f
        /** How far up the purple pill must be swiped to wave "voice bar" off. */
        const val SWIPE_UP_DP = 22f
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
