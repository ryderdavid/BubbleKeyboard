package com.ryderdavid.voicebar.handsfree

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.ryderdavid.voicebar.Prefs
import com.ryderdavid.voicebar.R
import com.ryderdavid.voicebar.voice.Dictionary
import com.ryderdavid.voicebar.voice.ElevenLabsTranscriber
import com.ryderdavid.voicebar.voice.MicRecorder
import com.ryderdavid.voicebar.voice.Secrets
import com.ryderdavid.voicebar.voice.Transcriber
import com.ryderdavid.voicebar.voice.UsageLog
import com.ryderdavid.voicebar.voice.WakeWord
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Hands-free mode: the phone listens (ElevenLabs, streaming, ending each command at a pause) and
 * does what you say on whatever is on screen: tap, hold, type, scroll, swipe, go back, open apps.
 * Common commands are understood on the phone; anything else goes to Claude with the screen's
 * elements (and a screenshot when the app hides them). A cursor on screen shows where each tap
 * lands. Anything that sends, buys or deletes waits for a spoken "yes".
 *
 * Lives inside the accessibility service ([service]), which can read every app's screen and act
 * on it. Main thread for the UI; screen reading, planning and acting run on one worker thread, in
 * order.
 */
class HandsFreeSession(private val service: AccessibilityService, private val onEnd: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-handsfree").apply { isDaemon = true } }
    private val wm = service.getSystemService(WindowManager::class.java)
    private val d = service.resources.displayMetrics.density
    private val usage = UsageLog.of(service)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val planHttp = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    private var layer: CursorLayer? = null
    private var pill: HandsFreePill? = null
    private var mic: MicRecorder? = null
    private var transcriber: Transcriber? = null
    @Volatile private var active = false
    private var restarts = 0

    /** Shown number badges, refreshed as the screen changes. */
    @Volatile private var numbers = false
    /** An action waiting for "yes", and what to do after it (a goal's next step). */
    private var pending: Pair<Action, (() -> Unit)?>? = null
    @Volatile private var goalRunning = false
    @Volatile private var goalCancelled = false

    // the last screen read, and its nodes by element id (worker thread)
    private var screen: Screen? = null
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    private val idle = Runnable { say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_idle_off)); main.postDelayed({ stop() }, 1500) }
    private val askTimeout = Runnable { if (pending != null) { pending = null; goalRunning = false; listening(service.getString(R.string.hf_cancelled)) } }
    private val badgeRefresh = Runnable { if (numbers) exec { refreshBadges() } }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stop() }
    }

    val isActive get() = active

    /** Runs on the worker thread, in order; nothing once stopped. */
    private fun exec(block: () -> Unit) {
        if (!work.isShutdown) runCatching { work.execute { runCatching(block).onFailure { say(HandsFreePill.Tone.PROBLEM, it.message ?: "Something went wrong") } } }
    }

    // ------------------------------------------------------------------ start and stop

    fun start() {
        if (active) return
        active = true
        WakeWord.hold(HOLD, true) // the wake-word listener lets go of the microphone
        addViews()
        say(HandsFreePill.Tone.WORKING, service.getString(R.string.hf_starting))
        runCatching { service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        // give the wake-word loop a moment to release the microphone
        main.postDelayed({ if (active) listen() }, 350)
        main.postDelayed(idle, IDLE_MS)
    }

    fun stop() {
        if (!active) return
        active = false
        goalCancelled = true
        main.removeCallbacksAndMessages(null)
        runCatching { service.unregisterReceiver(screenOff) }
        val m = mic; mic = null
        val t = transcriber; transcriber = null
        exec { m?.stop(); t?.cancel() }
        work.shutdown()
        goalRunning = false
        removeViews()
        WakeWord.hold(HOLD, false)
        onEnd()
    }

    private fun overlay(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun addViews() {
        val l = CursorLayer(service)
        val p = HandsFreePill(service) { stop() }
        val m = service.resources.displayMetrics
        runCatching { wm.addView(l, overlay(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false)) }
        // in the status bar, from the left, clear of a camera cutout: it never covers the app
        val bar = statusBarHeight().coerceAtLeast((26 * d).toInt())
        p.pillHeight = bar
        val margin = (8 * d).toInt()
        val width = pillRoom(m.widthPixels) - margin * 2
        runCatching {
            wm.addView(p, overlay(width, bar, true).apply { gravity = Gravity.TOP or Gravity.START; x = margin; y = 0 })
        }
        layer = l; pill = p
    }

    private fun removeViews() {
        layer?.let { runCatching { wm.removeViewImmediate(it) } }
        pill?.let { runCatching { wm.removeViewImmediate(it) } }
        layer = null; pill = null
    }

    /** How wide the pill can be: up to a cutout in the top edge, or most of the width. */
    private fun pillRoom(screenWidth: Int): Int {
        val cut = if (Build.VERSION.SDK_INT >= 30) runCatching {
            wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.filter { it.top <= 0 && it.left > screenWidth / 5 }?.minOfOrNull { it.left }
        }.getOrNull() else null
        return cut ?: (screenWidth * 0.62f).toInt()
    }

    @Suppress("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * d).toInt()
    }

    // ------------------------------------------------------------------ listening

    private fun listen() {
        val local = com.ryderdavid.voicebar.voice.Parakeet.inUse(service)
        val key = Secrets.get(service, Secrets.ELEVENLABS)
        if (!local && key.isNullOrBlank()) {
            say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_no_elevenlabs))
            main.postDelayed({ stop() }, 3000)
            return
        }
        // Parakeet on the phone commits each utterance at a pause by itself, like ElevenLabs' VAD
        val t: Transcriber = com.ryderdavid.voicebar.voice.VoiceFilterTranscriber.wrap(service,
            if (local) com.ryderdavid.voicebar.voice.ParakeetTranscriber(service)
            else ElevenLabsTranscriber(key!!, http, onAudio = usage::addAudio, keyterms = keyterms(), vad = true))
        transcriber = t
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) = main.post { if (active && transcriber === t && text.isNotBlank() && pending == null && !goalRunning) say(HandsFreePill.Tone.HEARD, text) }.let { }
            override fun onCommitted(text: String) = main.post { if (active && transcriber === t) heard(text) }.let { }
            override fun onError(message: String) = main.post {
                if (!active || transcriber !== t) return@post
                // the connection dropped (or the service ended the session): reconnect, a few times
                transcriber = null
                mic?.stop(); mic = null
                t.cancel()
                if (++restarts > MAX_RESTARTS) { say(HandsFreePill.Tone.PROBLEM, message); main.postDelayed({ stop() }, 2500); return@post }
                main.postDelayed({ if (active) listen() }, 800)
            }.let { }
        })
        val m = MicRecorder()
        val ok = m.start({ pcm -> t.send(pcm) }, { lvl -> main.post { pill?.level(lvl) } })
        if (!ok) {
            t.cancel(); transcriber = null
            say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_no_mic))
            main.postDelayed({ stop() }, 2500)
            return
        }
        mic = m
        if (pending == null && !goalRunning) listening()
    }

    /** The dictionary's words, so names you've taught it are heard in commands too. */
    private fun keyterms(): List<String> =
        if (Prefs.get(service).getBoolean(Prefs.DICT_KEYTERMS, Prefs.DEFAULT_DICT_KEYTERMS)) runCatching { Dictionary.of(service).keyterms() }.getOrDefault(emptyList()) else emptyList()

    private fun listening(note: String = "") =
        say(HandsFreePill.Tone.LISTENING, service.getString(R.string.hf_listening), note)

    private fun say(tone: HandsFreePill.Tone, text: String, hint: String = "") = main.post {
        pill?.show(tone, text, hint)
        if (tone != HandsFreePill.Tone.ASK) layer?.callout = null
    }.let { }

    // ------------------------------------------------------------------ what was said

    /** Main thread: one utterance, ended by a pause. */
    private fun heard(raw: String) {
        if (raw.isBlank()) return
        restarts = 0
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
        val cmd = Commands.parse(raw)
        if (cmd == Command.Stop) { stop(); return }

        pending?.let { (action, after) ->
            main.removeCallbacks(askTimeout)
            pending = null
            when (cmd) {
                Command.Yes -> { exec { perform(action); after?.invoke() }; return }
                Command.No -> { goalCancelled = true; goalRunning = false; listening(service.getString(R.string.hf_cancelled)); return }
                else -> { goalCancelled = true; goalRunning = false } // something new: forget the question and do that
            }
        }
        if (goalRunning) {
            if (cmd == Command.No) { goalCancelled = true; listening(service.getString(R.string.hf_cancelled)) }
            else say(HandsFreePill.Tone.WORKING, service.getString(R.string.hf_busy))
            return
        }
        if (cmd == Command.Yes || cmd == Command.No) { listening(); return }
        say(HandsFreePill.Tone.WORKING, raw.trim())
        exec { handle(cmd, raw) }
    }

    /** Worker thread. */
    private fun handle(cmd: Command, raw: String) {
        when (cmd) {
            Command.ShowNumbers -> { numbers = true; refreshBadges(); listening() }
            Command.HideNumbers -> { numbers = false; main.post { layer?.badges = emptyList() }; listening() }
            is Command.TapNumber -> {
                val s = read()
                val e = s.byNumber(cmd.n) ?: return listening(service.getString(R.string.hf_no_number, cmd.n))
                act(Action.Tap(e.id, long = cmd.long, say = tapNote(e, cmd.long), risky = Commands.risky(e.label)))
            }
            is Command.TapName -> {
                val s = read()
                val e = s.find(cmd.name)
                if (e == null) plan(raw) else act(Action.Tap(e.id, long = cmd.long, say = tapNote(e, cmd.long), risky = Commands.risky(e.label)))
            }
            is Command.Open -> {
                val e = read().find(cmd.name)
                when {
                    e != null && e.actionable -> act(Action.Tap(e.id, say = tapNote(e, false), risky = Commands.risky(e.label)))
                    launcherMatch(cmd.name) != null -> act(Action.OpenApp(cmd.name, "Open ${launcherMatch(cmd.name)!!.first}"))
                    else -> plan(raw)
                }
            }
            is Command.FocusName -> {
                val e = read().find(cmd.name, wantField = true)
                when {
                    e != null && e.editable -> act(Action.Focus(e.id, "Focus ${e.label.ifBlank { "field" }}"))
                    e != null -> act(Action.Tap(e.id, say = tapNote(e, false), risky = Commands.risky(e.label)))
                    launcherMatch(cmd.name) != null -> act(Action.OpenApp(cmd.name, "Open ${launcherMatch(cmd.name)!!.first}"))
                    else -> plan(raw)
                }
            }
            is Command.Type -> { read(); act(Action.Type(cmd.text, say = "Type “${cmd.text.take(24)}”")) }
            Command.Clear -> { read(); act(Action.SetText("", say = service.getString(R.string.hf_clear))) }
            is Command.TapCursor -> {
                val l = layer ?: return
                if (l.cursorX < 0) { main.post { l.ensureCursor() }; return listening(service.getString(R.string.hf_cursor_hint)) }
                act(Action.Tap(x = l.cursorX.toInt(), y = l.cursorY.toInt(), long = cmd.long, say = if (cmd.long) "Hold" else "Tap"))
            }
            is Command.CursorTo -> {
                val e = read().find(cmd.name) ?: return listening(service.getString(R.string.hf_not_found, cmd.name))
                glide(e.centerX.toFloat(), e.centerY.toFloat())
                listening(e.label.take(40))
            }
            is Command.Do -> { if (cmd.action !is Action.Global && cmd.action !is Action.Cursor) read(); act(cmd.action) }
            is Command.Goal -> goal(cmd.goal)
            is Command.Ask -> plan(raw)
            else -> listening()
        }
    }

    private fun tapNote(e: Element, long: Boolean) =
        (if (long) "Hold " else "Tap ") + (e.label.ifBlank { e.role }).take(40)

    // ------------------------------------------------------------------ Claude

    private fun planner(): Planner? {
        val key = Secrets.get(service, Secrets.ANTHROPIC)?.takeIf { it.isNotBlank() } ?: return null
        val model = Prefs.get(service).getString(Prefs.HANDS_FREE_MODEL, null)?.trim()?.ifBlank { null } ?: PlannerRequest.DEFAULT_MODEL
        return Planner(key, model, planHttp, onUsage = usage::addClaude)
    }

    /** One step for a command the phone couldn't map itself. Worker thread. */
    private fun plan(command: String) {
        val p = planner() ?: return say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_no_anthropic))
        say(HandsFreePill.Tone.WORKING, service.getString(R.string.hf_thinking), command.trim())
        val s = read()
        val action = p.next(command, s, shot = if (s.opaque) shot() else null)
            ?: return say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_planner_failed))
        act(action)
    }

    /** "Go ahead and …": up to [MAX_STEPS] steps, each planned on the screen it leaves. Worker thread. */
    private fun goal(goal: String) {
        val p = planner() ?: return say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_no_anthropic))
        goalRunning = true
        goalCancelled = false
        val done = ArrayList<String>()
        fun step() {
            if (!active || goalCancelled) { goalRunning = false; return }
            if (done.size >= MAX_STEPS) { goalRunning = false; return listening(service.getString(R.string.hf_goal_limit)) }
            say(HandsFreePill.Tone.WORKING, service.getString(R.string.hf_step, done.size + 1), goal)
            val s = read()
            val a = p.next(goal, s, goal = true, done = done, shot = if (s.opaque) shot() else null)
            if (a == null) { goalRunning = false; return say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_planner_failed)) }
            if (a is Action.Done) { goalRunning = false; return listening(a.say) }
            val next = { done += a.say; Thread.sleep(SETTLE_MS); step() }
            // waits for "yes" (goalRunning stays on); "no", a timeout or another command ends the goal
            if (a.risky) { ask(a) { exec { next() } }; return }
            perform(a)
            next()
        }
        step()
    }

    // ------------------------------------------------------------------ acting

    /** Does [a], or asks first when it's risky. Worker thread. */
    private fun act(a: Action) {
        if (a is Action.Done) return listening(a.say)
        if (a.risky) return ask(a, null)
        perform(a)
    }

    private fun ask(a: Action, after: (() -> Unit)?) {
        // point at it while asking
        target(a)?.let { (x, y) -> glide(x, y) }
        main.post {
            pending = a to after
            val q = a.say.ifBlank { "Do that" } + "?"
            pill?.show(HandsFreePill.Tone.ASK, q, service.getString(R.string.hf_say_yes))
            // the question beside the cursor too, where you're looking
            layer?.let { l -> if (target(a) == null) l.ensureCursor(); l.callout = q to service.getString(R.string.hf_say_yes) }
            main.removeCallbacks(askTimeout)
            main.postDelayed(askTimeout, ASK_MS)
        }
    }

    /** Where an action lands on screen, for the cursor. */
    private fun target(a: Action): Pair<Float, Float>? = when (a) {
        is Action.Tap -> a.id?.let { screen?.byId(it) }?.let { it.centerX.toFloat() to it.centerY.toFloat() }
            ?: if (a.x != null && a.y != null) a.x.toFloat() to a.y.toFloat() else null
        is Action.Type -> field(a.id)?.let { it.centerX.toFloat() to it.centerY.toFloat() }
        is Action.Focus -> screen?.byId(a.id)?.let { it.centerX.toFloat() to it.centerY.toFloat() }
        else -> null
    }

    /** Worker thread: does it now. */
    private fun perform(a: Action) {
        say(HandsFreePill.Tone.WORKING, a.say.ifBlank { "…" })
        val ok = when (a) {
            is Action.Tap -> tap(a)
            is Action.Type -> type(a)
            is Action.SetText -> setText(a.id, a.text)
            is Action.Focus -> focus(a.id)
            is Action.Scroll -> scroll(a)
            is Action.Swipe -> swipe(a.direction)
            is Action.Drag -> { trail(a.x1, a.y1, a.x2, a.y2); gesture(stroke(a.x1, a.y1, a.x2, a.y2, 600)) }
            is Action.Pinch -> pinch(a.zoomIn)
            is Action.Global -> global(a.what)
            is Action.OpenApp -> openApp(a.name)
            is Action.Cursor -> { cursorBy(a.dx, a.dy); true }
            is Action.Done -> true
        }
        if (!ok) say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_didnt_work, a.say))
        else if (!goalRunning) listening("✓ " + a.say)
        if (numbers) { main.removeCallbacks(badgeRefresh); main.postDelayed(badgeRefresh, SETTLE_MS) }
    }

    private fun tap(a: Action.Tap): Boolean {
        val node = a.id?.let { nodes[it] }
        val (x, y) = target(a) ?: return false
        glideAndWait(x, y)
        main.post { layer?.ripple(x, y, a.long) }
        // the element's own click when it has one (works even if something covers it); else a real touch
        val clickable = node?.let { clickableSelfOrParent(it, a.long) }
        if (clickable != null) {
            val done = runCatching { clickable.performAction(if (a.long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (done) return true
        }
        return gesture(stroke(x.toInt(), y.toInt(), x.toInt(), y.toInt(), if (a.long) 700 else 60))
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 5) {
            if (if (long) c.isLongClickable else c.isClickable) return c
            c = c.parent; depth++
        }
        return null
    }

    /** The field to type into: [id], the focused one, or the only one. */
    private fun field(id: Int?): Element? {
        val s = screen ?: return null
        return id?.let { s.byId(it) }?.takeIf { it.editable } ?: s.focusedField ?: s.fields.singleOrNull()
    }

    private fun focus(id: Int?): Boolean {
        val e = field(id) ?: return false
        val n = nodes[e.id] ?: return false
        glideAndWait(e.centerX.toFloat(), e.centerY.toFloat())
        main.post { layer?.ripple(e.centerX.toFloat(), e.centerY.toFloat()) }
        if (!n.isFocused) {
            runCatching { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            runCatching { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            Thread.sleep(250)
        }
        return true
    }

    private fun type(a: Action.Type): Boolean {
        val e = field(a.id) ?: return false.also { say(HandsFreePill.Tone.PROBLEM, service.getString(R.string.hf_no_field)) }
        if (!focus(e.id)) return false
        val n = fresh(e.id) ?: return false
        val existing = if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val words = Dictionary.of(service).applySure(a.text.trim())
        val text = when {
            existing.isEmpty() -> words
            existing.endsWith(" ") || existing.endsWith("\n") -> existing + words
            else -> "$existing $words"
        }
        if (!setNode(n, text)) return false
        if (a.submit && Build.VERSION.SDK_INT >= 30) runCatching { n.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }
        return true
    }

    private fun setText(id: Int?, text: String): Boolean {
        val e = field(id) ?: return false
        val n = fresh(e.id) ?: return false
        return setNode(n, text)
    }

    private fun setNode(n: AccessibilityNodeInfo, text: String): Boolean {
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        if (ok) n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        })
        return ok
    }

    /** The node again, refreshed (its text may have changed). */
    private fun fresh(id: Int): AccessibilityNodeInfo? = nodes[id]?.also { runCatching { it.refresh() } }

    private fun scroll(a: Action.Scroll): Boolean {
        val s = screen
        val e = a.id?.let { s?.byId(it) } ?: s?.mainScroll
        val n = e?.let { nodes[it.id] }
        val area = e?.let { Rect(it.left, it.top, it.right, it.bottom) } ?: contentRect()
        return when (val amount = a.amount) {
            Action.Amount.Step -> stepScroll(n, area, a.direction) || fling(area, a.direction, content = true)
            is Action.Amount.Screens -> dragScroll(area, a.direction, amount.n)
            Action.Amount.ToEnd -> scrollToEnd(n, area, a.direction)
        }
    }

    /** The node's own scroll action in [dir], or null when it has none. */
    private fun scrollAction(n: AccessibilityNodeInfo, dir: Action.Dir): Int? {
        val specific = when (dir) {
            Action.Dir.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Action.Dir.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Action.Dir.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Action.Dir.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }.id
        val generic = when (dir) {
            Action.Dir.DOWN, Action.Dir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val actions = runCatching { n.actionList.map { it.id }.toSet() }.getOrDefault(emptySet())
        return if (specific in actions) specific else if (generic in actions) generic else null
    }

    /** One step of the app's own scrolling. */
    private fun stepScroll(n: AccessibilityNodeInfo?, area: Rect, dir: Action.Dir): Boolean {
        val id = n?.let { scrollAction(it, dir) } ?: return false
        drawScrollTrail(area, dir, 0.5f)
        return runCatching { n.performAction(id) }.getOrDefault(false)
    }

    /**
     * Exactly [screens] heights (or widths) of [area]: the finger drags that far in slow strokes
     * and holds still before lifting, so the list doesn't fling on past it. Each stroke adds the
     * touch slop the app swallows before it starts moving.
     */
    private fun dragScroll(area: Rect, dir: Action.Dir, screens: Float): Boolean {
        val vertical = dir == Action.Dir.UP || dir == Action.Dir.DOWN
        val span = if (vertical) area.height() else area.width()
        if (span <= 0) return false
        val total = span * screens
        // strokes of at most ~70% of the area, inside its middle, away from the edges (and back gestures)
        val perStroke = span * 0.7f
        val strokes = kotlin.math.ceil(total / perStroke).toInt().coerceAtLeast(1)
        val each = total / strokes
        val slop = android.view.ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        var ok = false
        repeat(strokes) {
            val len = each + slop
            val cx = area.exactCenterX(); val cy = area.exactCenterY()
            // content moves with the finger: to scroll down, the finger moves up
            val (x1, y1, x2, y2) = when (dir) {
                Action.Dir.DOWN -> listOf(cx, cy + len / 2, cx, cy - len / 2)
                Action.Dir.UP -> listOf(cx, cy - len / 2, cx, cy + len / 2)
                Action.Dir.RIGHT -> listOf(cx + len / 2, cy, cx - len / 2, cy)
                Action.Dir.LEFT -> listOf(cx - len / 2, cy, cx + len / 2, cy)
            }
            trail(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
            ok = heldDrag(x1, y1, x2, y2, (len / d * 1.6f).toLong().coerceIn(220, 900)) || ok
            Thread.sleep(120)
        }
        return ok
    }

    /** Drags from 1 to 2 over [ms], then holds still a moment before lifting: no fling. */
    private fun heldDrag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val move = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val first = GestureDescription.StrokeDescription(move, 0, ms, true)
        val hold = Path().apply { moveTo(x2, y2); lineTo(x2 + 0.5f, y2) }
        val last = first.continueStroke(hold, 0, 160, false)
        return gesture(first) && gesture(last)
    }

    /**
     * All the way to the top or bottom: the app's own scroll action again and again (fast), else
     * flings, until what's showing stops changing.
     */
    private fun scrollToEnd(n: AccessibilityNodeInfo?, area: Rect, dir: Action.Dir): Boolean {
        drawScrollTrail(area, dir, 0.9f)
        var moved = false
        var same = 0
        var last = signature(n, area)
        val id = n?.let { scrollAction(it, dir) }
        for (i in 0 until if (id != null) 60 else 20) {
            val acted = if (id != null) runCatching { n.performAction(id) }.getOrDefault(false)
                else fling(area, dir, content = true)
            if (!acted) break
            Thread.sleep(if (id != null) 90 else 350)
            val now = signature(n, area)
            if (now == last) { if (++same >= 2) break } else { same = 0; moved = true }
            last = now
        }
        // some apps report a scroll action but stop short (nested lists): finish with flings
        if (id != null) {
            same = 0
            for (i in 0 until 8) {
                fling(area, dir, content = true)
                Thread.sleep(350)
                val now = signature(n, area)
                if (now == last) { if (++same >= 2) break } else { same = 0; moved = true }
                last = now
            }
        }
        return moved || last.isNotEmpty()
    }

    /** What's showing in the scrolling area: its visible text and where it sits. Changes when it scrolls. */
    private fun signature(n: AccessibilityNodeInfo?, area: Rect): String {
        val root = n?.also { runCatching { it.refresh() } } ?: runCatching { service.rootInActiveWindow }.getOrNull() ?: return ""
        val sb = StringBuilder()
        val b = Rect()
        var budget = 80
        fun walk(x: AccessibilityNodeInfo) {
            if (budget-- <= 0) return
            if (x.isVisibleToUser) {
                x.getBoundsInScreen(b)
                if (Rect.intersects(b, area)) {
                    val t = x.text ?: x.contentDescription
                    if (!t.isNullOrEmpty()) sb.append(t.take(40)).append('@').append(b.top).append(',').append(b.left).append('|')
                }
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let(::walk)
        }
        runCatching { walk(root) }
        return sb.toString()
    }

    private fun drawScrollTrail(area: Rect, dir: Action.Dir, share: Float) {
        val cx = area.centerX(); val cy = area.centerY()
        val h = (area.height() * share / 2).toInt(); val w = (area.width() * share / 2).toInt()
        when (dir) {
            Action.Dir.DOWN -> trail(cx, cy + h, cx, cy - h)
            Action.Dir.UP -> trail(cx, cy - h, cx, cy + h)
            Action.Dir.RIGHT -> trail(cx + w, cy, cx - w, cy)
            Action.Dir.LEFT -> trail(cx - w, cy, cx + w, cy)
        }
    }

    /** The screen minus the status bar and the navigation area: where app content scrolls. */
    private fun contentRect(): Rect {
        val r = screenRect()
        return Rect(r.left, r.top + statusBarHeight(), r.right, r.bottom - (48 * d).toInt())
    }

    private fun swipe(dir: Action.Dir) = fling(screenRect(), dir, content = false)

    /** A finger stroke across [r]. For content scrolling the finger moves opposite to the direction. */
    private fun fling(r: Rect, dir: Action.Dir, content: Boolean): Boolean {
        val finger = if (!content) dir else when (dir) {
            Action.Dir.DOWN -> Action.Dir.UP; Action.Dir.UP -> Action.Dir.DOWN; Action.Dir.LEFT -> Action.Dir.RIGHT; Action.Dir.RIGHT -> Action.Dir.LEFT
        }
        val cx = r.centerX(); val cy = r.centerY()
        val dx = (r.width() * 0.32f).toInt(); val dy = (r.height() * 0.3f).toInt()
        val (x1, y1, x2, y2) = when (finger) {
            Action.Dir.UP -> listOf(cx, cy + dy, cx, cy - dy)
            Action.Dir.DOWN -> listOf(cx, cy - dy, cx, cy + dy)
            Action.Dir.LEFT -> listOf(cx + dx, cy, cx - dx, cy)
            Action.Dir.RIGHT -> listOf(cx - dx, cy, cx + dx, cy)
        }
        trail(x1, y1, x2, y2)
        return gesture(stroke(x1, y1, x2, y2, 280))
    }

    private fun pinch(zoomIn: Boolean): Boolean {
        val r = screenRect()
        val cx = r.centerX(); val cy = r.centerY()
        val near = (40 * d).toInt(); val far = (min(r.width(), r.height()) * 0.35f).toInt()
        val (a, b) = if (zoomIn) near to far else far to near
        return gesture(stroke(cx - a, cy - a, cx - b, cy - b, 450), stroke(cx + a, cy + a, cx + b, cy + b, 450))
    }

    private fun global(what: Action.System): Boolean = service.performGlobalAction(when (what) {
        Action.System.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        Action.System.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        Action.System.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        Action.System.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        Action.System.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        Action.System.LOCK -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
        Action.System.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
    })

    /** The installed app whose name best matches: (label, package). */
    private fun launcherMatch(name: String): Pair<String, String>? {
        val pm = service.packageManager
        val apps = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        }.getOrDefault(emptyList()).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return AppMatch.best(name, apps)
    }

    private fun openApp(name: String): Boolean {
        val (_, pkg) = launcherMatch(name) ?: return false
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun cursorBy(dx: Int, dy: Int) {
        val r = screenRect()
        val unit = min(r.width(), r.height()) / 1000f
        main.post {
            val l = layer ?: return@post
            l.ensureCursor()
            l.moveTo((l.cursorX + dx * unit).coerceIn(0f, r.width() - 1f), (l.cursorY + dy * unit).coerceIn(0f, r.height() - 1f))
        }
    }

    private fun glide(x: Float, y: Float) = main.post { layer?.moveTo(x, y) }.let { }

    /** Moves the cursor there and waits for it to arrive (briefly), so you see where it lands. */
    private fun glideAndWait(x: Float, y: Float) {
        val arrived = CountDownLatch(1)
        main.post { layer?.moveTo(x, y) { arrived.countDown() } ?: arrived.countDown() }
        arrived.await(600, TimeUnit.MILLISECONDS)
    }

    private fun trail(x1: Int, y1: Int, x2: Int, y2: Int) =
        main.post { layer?.trail(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) }.let { }

    private fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): GestureDescription.StrokeDescription {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return GestureDescription.StrokeDescription(p, 0, ms.coerceAtLeast(1))
    }

    /** Dispatches the strokes as real touches and waits for them to finish. */
    private fun gesture(vararg strokes: GestureDescription.StrokeDescription): Boolean {
        val g = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
        val done = CountDownLatch(1)
        var ok = false
        val sent = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, main)
        if (!sent) return false
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun screenRect(): Rect {
        val m = service.resources.displayMetrics
        val wmRect = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else Rect(0, 0, m.widthPixels, m.heightPixels)
        return Rect(wmRect)
    }

    // ------------------------------------------------------------------ the screen

    /** Something on screen changed: refresh the badges, a little debounced. */
    fun onScreenChanged() {
        if (!active || !numbers) return
        main.removeCallbacks(badgeRefresh)
        main.postDelayed(badgeRefresh, 350)
    }

    private fun refreshBadges() {
        val s = read()
        val shown = s.numbered.mapIndexed { i, e -> Triple(e.left, e.top, i + 1) }
        main.post { layer?.badges = shown }
    }

    /**
     * Reads the screen into elements (worker thread). Windows top-most first; something hidden
     * behind a window above it (the app under the notification shade) is left out. Keyboards and
     * our own overlays are skipped, and passwords are never read.
     */
    private fun read(noHiding: Boolean = false): Screen {
        val r = screenRect()
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
            .sortedByDescending { it.layer }
        val above = ArrayList<Rect>()
        val raw = ArrayList<Pair<Element, AccessibilityNodeInfo>>()
        var budget = MAX_NODES
        var app = ""
        val b = Rect()
        val statusBar = statusBarHeight()

        fun labelOf(n: AccessibilityNodeInfo): String {
            if (n.isPassword) return "(password)"
            val own = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
            if (own.isNotEmpty()) return own.take(120)
            if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            // an unlabeled button: its children's words, or its resource name ("send_button")
            if (n.isClickable || n.isLongClickable) {
                val kids = ArrayList<String>()
                fun gather(c: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 3 || kids.size >= 4) return
                    for (i in 0 until c.childCount) {
                        val k = c.getChild(i) ?: continue
                        val t = (k.text ?: k.contentDescription)?.toString()?.trim()
                        if (!t.isNullOrEmpty() && !k.isPassword) kids += t else gather(k, depth + 1)
                    }
                }
                gather(n, 0)
                if (kids.isNotEmpty()) return kids.joinToString(" · ").take(120)
                n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ')?.let { return it }
            }
            return ""
        }

        fun roleOf(n: AccessibilityNodeInfo): String {
            val c = n.className?.toString().orEmpty()
            return when {
                n.isEditable || c.endsWith("EditText") -> "field"
                c.endsWith("Switch") || c.endsWith("SwitchCompat") || c.endsWith("ToggleButton") -> "switch"
                c.endsWith("CheckBox") -> "checkbox"
                c.endsWith("RadioButton") -> "radio"
                c.endsWith("Button") || c.endsWith("ImageButton") -> "button"
                n.isScrollable -> "list"
                c.endsWith("ImageView") -> "image"
                c.endsWith("SeekBar") -> "slider"
                n.isClickable -> "item"
                else -> "text"
            }
        }

        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (app.isEmpty() && w.type == AccessibilityWindowInfo.TYPE_APPLICATION) app = root.packageName?.toString().orEmpty()
            val wb = Rect().also { w.getBoundsInScreen(it) }
            fun walk(n: AccessibilityNodeInfo, insideAction: Boolean) {
                if (budget-- <= 0 || !n.isVisibleToUser) return
                n.getBoundsInScreen(b)
                if (b.isEmpty || !Rect.intersects(b, r)) return
                val cx = b.centerX(); val cy = b.centerY()
                val hidden = !noHiding && above.any { it.contains(cx, cy) && !(it.top == 0 && it.height() <= statusBar + 2) }
                val actionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable
                if (!hidden) {
                    val label = labelOf(n)
                    // plain text inside a button is already in the button's label
                    if (actionable || (label.isNotEmpty() && !insideAction)) {
                        val e = Element(0, label, roleOf(n), b.left, b.top, b.right, b.bottom,
                            clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable,
                            scrollable = n.isScrollable, focused = n.isFocused,
                            checked = if (n.isCheckable) n.isChecked else null)
                        raw += e to n
                    }
                }
                val inside = insideAction || (n.isClickable && !n.isScrollable)
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, inside) }
            }
            walk(root, false)
            above += wb
            if (raw.size > MAX_ELEMENTS) break
        }
        if (raw.size < 3 && above.size > 1 && !noHiding) return read(noHiding = true)
        // reading order: rows (within 12 dp), then left to right
        val row = (12 * d).toInt().coerceAtLeast(1)
        val sorted = raw.sortedWith(compareBy({ it.first.top / row }, { it.first.left })).take(MAX_ELEMENTS)
        val elements = sorted.mapIndexed { i, (e, _) -> e.copy(id = i + 1) }
        nodes = sorted.mapIndexed { i, (_, n) -> (i + 1) to n }.toMap()
        return Screen(app, r.width(), r.height(), elements).also { screen = it }
    }

    /** A screenshot for Claude when the app's elements say little (maps, games). API 30+. */
    private fun shot(): PlannerRequest.Shot? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var result: PlannerRequest.Shot? = null
        runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(s: AccessibilityService.ScreenshotResult) {
                    Thread {
                        runCatching {
                            val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace) ?: return@runCatching
                            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                            s.hardwareBuffer.close()
                            val scale = min(1f, SHOT_WIDTH / soft.width.toFloat())
                            val small = Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true)
                            val out = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                            result = PlannerRequest.Shot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), small.width, small.height, scale)
                        }
                        latch.countDown()
                    }.start()
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
        }.onFailure { latch.countDown() }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return result
    }

    companion object {
        const val HOLD = "handsfree"
        /** Hands-free turns itself off after this long without a command. */
        const val IDLE_MS = 5 * 60_000L
        const val ASK_MS = 15_000L
        const val SETTLE_MS = 700L
        const val MAX_STEPS = 5
        const val MAX_RESTARTS = 4
        const val MAX_NODES = 1500
        const val MAX_ELEMENTS = 250
        const val SHOT_WIDTH = 720f
    }
}

/** Matching a spoken app name against what's installed. Pure. */
object AppMatch {
    fun best(spoken: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val q = Screen.norm(spoken).removeSuffix(" app").trim()
        if (q.isEmpty()) return null
        var best: Pair<String, String>? = null
        var score = 0.0
        for (a in apps) {
            val l = Screen.norm(a.first)
            val s = when {
                l == q -> 1.0
                l.replace(" ", "") == q.replace(" ", "") -> 0.95
                l.startsWith(q) -> 0.8
                l.contains(q) && q.length >= 3 -> 0.6
                else -> Screen.overlap(q, l) * 0.55
            }
            if (s > score) { score = s; best = a }
        }
        return best.takeIf { score >= 0.5 }
    }
}
