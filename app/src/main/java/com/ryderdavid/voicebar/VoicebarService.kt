package com.ryderdavid.voicebar

import android.Manifest
import com.ryderdavid.voicebar.voice.ScreenContext
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ryderdavid.voicebar.voice.ChatApps
import com.ryderdavid.voicebar.voice.ClaudeCleaner
import com.ryderdavid.voicebar.voice.CorrectionWatch
import com.ryderdavid.voicebar.voice.Corrections
import com.ryderdavid.voicebar.voice.Dictionary
import com.ryderdavid.voicebar.voice.LearnerRequest
import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import com.ryderdavid.voicebar.voice.DictationController
import com.ryderdavid.voicebar.voice.ElevenLabsTranscriber
import com.ryderdavid.voicebar.voice.Transcriber
import com.ryderdavid.voicebar.voice.LiveField
import com.ryderdavid.voicebar.voice.LiveTyper
import com.ryderdavid.voicebar.voice.MicRecorder
import com.ryderdavid.voicebar.voice.Infill
import com.ryderdavid.voicebar.voice.Millennial
import com.ryderdavid.voicebar.voice.Secrets
import com.ryderdavid.voicebar.voice.VoiceCommands
import com.ryderdavid.voicebar.voice.WakeWord
import com.ryderdavid.voicebar.voice.UsageLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The input method: Voicebar's one-row voice bar ([DockView]), docked like any keyboard.
 * Typing is handed to Gboard (ABC); the bar is for dictation, the cursor pad and editing.
 */
class VoicebarService : InputMethodService(), Bar.Listener {

    /** The voice bar. */
    private var bar: KeyboardSurface? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // the cleanup call is short; never leave the user waiting long for it
            .callTimeout(8, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private val usage by lazy { UsageLog.of(this) }

    private fun pickTranscriber(): Transcriber? = run {
                // on the phone when Parakeet is chosen and downloaded (the screen is still read, for the cleanup)
                if (com.ryderdavid.voicebar.voice.Parakeet.inUse(this)) { readScreen(); com.ryderdavid.voicebar.voice.ParakeetTranscriber(this) }
                else Secrets.get(this, Secrets.ELEVENLABS)?.let {
                    val useTerms = Prefs.get(this).getBoolean(Prefs.DICT_KEYTERMS, Prefs.DEFAULT_DICT_KEYTERMS)
                    val terms = if (useTerms) Dictionary.of(this).keyterms() else emptyList()
                    // the screen is read for the cleanup only: given to the recognizer (as names to listen
                    // for or as words "just said") it made it hear things that were never said and keep
                    // rewriting its live text. The recognizer gets the dictionary and, at most, the last
                    // sentence you typed in this box.
                    readScreen()
                    val lead = aroundCursor?.before?.let { ScreenContext.leadIn(it) }
                    ElevenLabsTranscriber(it, http, onAudio = usage::addAudio, keyterms = terms,
                        context = if (lead == null) null else ({ ElevenLabsTranscriber.Context(terms, lead) }))
                }
    }

    private val dictation: DictationController by lazy {
        DictationController(
            audio = MicRecorder(),
            transcriberFactory = { pickTranscriber()?.let { com.ryderdavid.voicebar.voice.VoiceFilterTranscriber.wrap(this, it) } },
            cleanerFactory = {
                val polish = forcePolish
                forcePolish = false
                newCleaner(polish, forField = true)
            },
            ui = object : DictationController.Ui {
                override fun onListening() {
                    dismissedWords = 0
                    // a new dictation starts empty: the last one's words must never be typed again
                    lastLiveText = ""
                    restorePeriodIfFollowingUp()
                    if (liveTyping) liveTyper.begin()
                    bar?.showListening()
                }
                override fun onLevel(level: Float) {
                    bar?.showLevel(level)
                    if (!guard.auto) { if (manualLevels.size < 4000) manualLevels += level }
                    // talked at the phone: what was heard so far goes into the field now
                    else if (guard.level(level, SystemClock.uptimeMillis())) {
                        AppLog.log("guard", "engaged (talking at the phone): " + AppLog.excerpt(lastLiveText))
                        if (liveTyping && lastLiveText.isNotBlank()) liveTyper.live(livePending(lastLiveText)?.before ?: lastLiveText)
                    }
                }
                // only a newly recognized word counts as speech for the silence countdown, never mere
                // noise (the controller counts raw words, so a pause cleanup changing the text doesn't count)
                override fun onHeard() { bar?.heardSpeech() }
                override fun onLiveText(text: String) {
                    // "voice bar …": what follows goes in the pill, not the field
                    lastLiveText = text
                    val pending = livePending(text)
                    pendingCommand = pending
                    val shown = pending?.before ?: text
                    if (liveTyping && guard.engaged) liveTyper.live(shown) else bar?.showLiveText(shown)
                    bar?.showCommand(pending?.said, pending?.command != null)
                    watchCommands(text)
                }
                override fun onCleaning(raw: String) {
                    if (liveTyping) liveTyper.live(raw) // the draft holds the raw words while they're polished
                    bar?.showCleaning(raw)
                }
                override fun onHeardAll(raw: String) { heardAll = raw }
                override fun onResult(text: String) {
                    delivered = true
                    // every dictation goes in the history, as heard and as cleaned, wherever it ends up
                    val app = currentInputEditorInfo?.packageName?.let { pkg ->
                        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                    }.orEmpty()
                    DictationHistory.of(this@VoicebarService).add(heardAll ?: text, Dictionary.of(this@VoicebarService).applySure(text).trim(), app)
                    heardAll = null
                    bar?.dictations = DictationHistory.of(this@VoicebarService).all()
                    AppLog.log("result", "heard " + AppLog.excerpt(heardAll ?: text) + " → " + AppLog.excerpt(text) +
                        " divert=$divertToClipboard toClipboard=$toClipboard field=${hasTextField()} hold=${guard.hold(endedBySilence)} live=$liveTyping")
                    if (divertToClipboard) {
                        // the safeguard heard the room, not a message: nothing goes into the message box
                        divertToClipboard = false
                        sendAfterTalk = false
                        val words = Dictionary.of(this@VoicebarService).applySure(text).trim()
                        if (words.isNotEmpty() && copyDictation(words)) bar?.showMessage(getString(R.string.safeguard_diverted))
                        return
                    }
                    if (toClipboard || !hasTextField()) {
                        // nowhere to type it: the words go on the clipboard to paste wherever they're wanted
                        val words = Dictionary.of(this@VoicebarService).applySure(text).trim()
                        sendAfterTalk = false
                        if (words.isNotEmpty() && copyDictation(words)) bar?.showMessage(getString(R.string.dictation_copied))
                        return
                    }
                    // fitted to its spot when it goes into the middle of text: capitals, the closing mark, leftover words
                    val fit = Infill.fit(Dictionary.of(this@VoicebarService).applySure(text), aroundCursor, fieldCleaner?.seam ?: Infill.Seam())
                    if (fit.notes.isNotEmpty()) AppLog.log("infill", fit.notes.joinToString("; "))
                    // millennial mode is for the end of a message, not a fix in the middle of one
                    val final = if (aroundCursor?.inside == true) fit.text else finishMessage(fit.text)
                    if (guard.hold(endedBySilence)) {
                        // auto-started in a chat or email app and it ended by itself: it waits for a tap
                        sendAfterTalk = false
                        if (liveTyping) liveTyper.cancel()
                        heldShown = true
                        bar?.showHeld(getString(R.string.held_tap_to_add, final)) {
                            heldShown = false
                            typeDictation(final)
                            removeLeftovers(final, fit)
                            if (aroundCursor?.inside == true) spaceAfterInsert(final)
                        }
                        return
                    }
                    // watch for hand corrections to it (never in password fields)
                    if (learnOn && fieldTakesLiveText(currentInputEditorInfo))
                        CorrectionWatch.start(final, currentInputEditorInfo?.packageName, fieldKey)
                    lastDelivered = final
                    lastDeliveredAt = SystemClock.uptimeMillis()
                    if (liveTyping) liveTyper.result(final) else typeDictation(final)
                    removeLeftovers(final, fit)
                    if (aroundCursor?.inside == true) spaceAfterInsert(final)
                    rememberStripped(final)
                }
                override fun onNotCleaned(why: String) {
                    CleanupProblem.save(this@VoicebarService, why)
                    bar?.showMessage(getString(R.string.not_cleaned, why))
                }
                override fun onIdle() {
                    (bar as? View)?.post { updateWake() }
                    if (!guard.auto && manualLevels.isNotEmpty()) {
                        val p = Prefs.get(this@VoicebarService)
                        val old = p.getFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.DEFAULT_TYPICAL)
                        p.edit().putFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.learnTypical(old, manualLevels)).apply()
                        manualLevels.clear()
                    }
                    if (sendAfterTalk) {
                        sendAfterTalk = false
                        // after the text is in and the app has seen it (its Send may enable only then)
                        (bar as? View)?.postDelayed({ send() }, SEND_DELAY_MS)
                    }
                    if (hideAfterTalk) {
                        hideAfterTalk = false
                        (bar as? View)?.postDelayed({ requestHideSelf(0) }, SEND_DELAY_MS)
                    }
                    if (gboardAfterTalk) {
                        gboardAfterTalk = false
                        // after the text is in: let the field settle, then hand over
                        (bar as? View)?.post { onSwitchToGboard() }
                    }
                    if (liveTyping) liveTyper.end()
                    if (!delivered) takeBackRestoredPeriod()
                    bar?.talkEnded()
                    if (bar?.voice != Bar.Voice.MESSAGE) bar?.hideVoice()
                }
                override fun onError(message: String) {
                    AppLog.log("error", message)
                    hideAfterTalk = false
                    (bar as? View)?.post { updateWake() }
                    gboardAfterTalk = false
                    sendAfterTalk = false
                    if (liveTyping) liveTyper.end() // keep what was typed so far
                    takeBackRestoredPeriod()
                    bar?.talkEnded()
                    bar?.showMessage(message)
                }
            },
        )
    }

    // ---------------------------------------------------------------- millennial mode

    /** The end of the last message we typed without its final period, to recognize a follow-up. */
    private var strippedTail: String? = null
    /** This dictation put the previous message's period back (taken back if nothing gets typed). */
    private var restoredPeriod = false
    /** This dictation typed its result. */
    private var delivered = false

    /** Millennial mode, for this field: everywhere, or where it's checked (search boxes, chats). */
    private val millennial get() = Prefs.millennial(this).let { m ->
        m.everywhere || (m.chats && ChatApps.isChat(currentInputEditorInfo)) || (m.search && ChatApps.isSearch(currentInputEditorInfo))
    }

    // ---------------------------------------------------------------- learning from hand corrections

    private val learnOn get() = Prefs.get(this).getBoolean(Prefs.LEARN, Prefs.DEFAULT_LEARN)

    /** A "Learned …" note waiting for the bar to show again (the correction was made in Gboard). */
    private var learnedNote: Pair<String, () -> Unit>? = null

    /** The current field's text, through the keyboard if it's active, else what accessibility sees. */
    /** The screen's text for the dictation under way, being read in the background. */
    private var screenNow: java.util.concurrent.Future<ScreenContext.Snapshot?>? = null

    /**
     * Starts reading the screen for a dictation that's starting: the app's text through the Gboard
     * companion (accessibility) when it's on, otherwise just the text box. Null when it's off.
     */
    private fun readScreen(): java.util.concurrent.Future<ScreenContext.Snapshot?>? {
        screenNow = null
        if (!Prefs.get(this).getBoolean(Prefs.SCREEN_CONTEXT, Prefs.DEFAULT_SCREEN_CONTEXT)) return null
        val companion = GboardButtonService.running
        // without the companion, the field is all there is (read here: the connection is ours)
        val field = if (companion == null) readField()?.trim() else null
        val task = java.util.concurrent.FutureTask {
            companion?.screenText() ?: field?.takeIf { it.isNotEmpty() }?.let { ScreenContext.build(listOf(ScreenContext.Piece(it, 0, 0, field = true))) }
        }
        Thread(task, "voicebar-screen").apply { isDaemon = true }.start()
        return task.also { screenNow = it }
    }

    private fun readField(): String? =
        runCatching { currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 5000 }, 0)?.text?.toString() }.getOrNull()
            ?: GboardButtonService.running?.focusedText()

    /** Compares the field with what was typed; corrections go to Claude to judge, in the background. */
    private fun checkCorrections(p: CorrectionWatch.Pending, field: String): Boolean {
        val changes = Corrections.find(p.text, field).take(3)
        if (changes.isEmpty()) return false
        val key = Secrets.get(this, Secrets.ANTHROPIC)?.takeIf { it.isNotBlank() } ?: return true
        // learning from fixes is Claude's job (Haiku), whichever model does cleanup
        val model = ClaudeCleanupRequest.DEFAULT_MODEL
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            for (c in changes) {
                val r = LearnerRequest.judge(c, key, model, http, usage::addClaude) ?: continue
                if (r.verdict == LearnerRequest.Verdict.NONE) continue
                main.post { learned(r) }
            }
        }, "voicebar-learn").start()
        return true
    }

    private fun learned(r: LearnerRequest.Result) {
        val dict = Dictionary.of(this)
        val before = dict.find(r.term)
        val heard = if (r.verdict == LearnerRequest.Verdict.VOCABULARY) null else r.heard
        dict.add(r.term, heard, vocabulary = r.verdict != LearnerRequest.Verdict.REPLACEMENT)
        val note = getString(R.string.learned_note, r.term)
        val undo = { dict.restore(r.term, before) }
        if (inputShown && !dictation.dictating) bar?.showNote(note, undo) else learnedNote = note to undo
    }

    private fun showLearnedNote() {
        val (note, undo) = learnedNote ?: return
        learnedNote = null
        bar?.showNote(note, undo)
    }

    // ---------------------------------------------------------------- "voice bar, listen" while idle

    /** The bar is up (between onStartInputView and onFinishInputView). */
    private var inputShown = false

    private val wakeListener = object : WakeWord.Listener {
        override fun onWake() { if (!dictation.dictating) bar?.showCommand("", false) }
        override fun onListen() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            bar?.startVoice()
        }
        override fun onHide() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            requestHideSelf(0)
        }
        override fun onWakeGone() { if (!dictation.dictating) bar?.showCommand(null) }
        override fun onHandsFree() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            // the screen is yours to command: put the keyboard away
            if (com.ryderdavid.voicebar.voice.HandsFreeStart.request()) requestHideSelf(0)
            else bar?.showMessage(getString(R.string.hf_needs_service))
        }
    }

    /** Watch for "voice bar" while the bar is up and nobody's dictating. */
    private fun updateWake() {
        val want = inputShown && !dictation.dictating &&
            Prefs.get(this).getBoolean(Prefs.WAKE_WORD, Prefs.DEFAULT_WAKE_WORD) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        WakeWord.want(this, WAKE_OWNER, if (want) wakeListener else null)
    }

    override fun onCreate() {
        super.onCreate()
        CorrectionWatch.onCheck = { p, field -> learnOn && checkCorrections(p, field) }
    }

    override fun onDestroy() {
        CorrectionWatch.onCheck = null
        WakeWord.want(this, WAKE_OWNER, null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- spoken commands

    private val commandHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The live text when it last ended with a command. */
    private var commandText = ""
    private val commandRunnable = Runnable { runSpokenCommand() }

    /** "Voice bar" heard near the end of the live text, and what's followed it. */
    private var pendingCommand: VoiceCommands.Pending? = null

    /** The live text as last heard (with any "voice bar …" still in it). */
    private var lastLiveText = ""
    /**
     * "Voice bar" waved off with a swipe up: how many words it was from "voice" to the end then.
     * That one stays plain text as it grows; a later "voice bar" (fewer words from the end) counts.
     */
    private var dismissedWords = 0

    private fun livePending(text: String): VoiceCommands.Pending? {
        if (!commandsOn || dictation.state != DictationController.State.LISTENING) return null
        val p = VoiceCommands.pending(text) ?: run { dismissedWords = 0; return null }
        if (dismissedWords > 0) { if (p.words >= dismissedWords) return null else dismissedWords = 0 }
        return p
    }

    override fun onDismissCommand() {
        if (!dictation.dictating) {
            // at rest: just settle back down
            WakeWord.dismiss()
            bar?.showCommand(null)
            return
        }
        val p = pendingCommand ?: return
        dismissedWords = p.words
        pendingCommand = null
        commandHandler.removeCallbacks(commandRunnable)
        // the words go back where they were said
        if (liveTyping) liveTyper.live(lastLiveText) else bar?.showLiveText(lastLiveText)
        bar?.showCommand(null)
    }

    /** Finishing some other way with "voice bar …" half said: those words don't get typed. */
    private fun dropPendingCommand() {
        val p = pendingCommand ?: return
        pendingCommand = null
        dictation.cutLast(p.words)
    }

    private val commandsOn get() = Prefs.get(this).getBoolean(Prefs.VOICE_COMMANDS, Prefs.DEFAULT_VOICE_COMMANDS)

    /**
     * New live text: when it ends with "voice bar" and a command, wait a moment; if nothing more is
     * said (the text is unchanged), the command runs and its words come out of the text.
     */
    private fun watchCommands(text: String) {
        commandHandler.removeCallbacks(commandRunnable)
        if (dictation.state != DictationController.State.LISTENING || !commandsOn) return
        commandText = text
        if (commandAt(text) != null) commandHandler.postDelayed(commandRunnable, COMMAND_HOLD_MS)
    }

    /** The command [text] ends with, unless it's the "voice bar" that was waved off. */
    private fun commandAt(text: String): VoiceCommands.Heard? {
        val h = VoiceCommands.atEnd(text) ?: return null
        return if (dismissedWords > 0 && h.words >= dismissedWords) null else h
    }

    private fun runSpokenCommand() {
        if (dictation.state != DictationController.State.LISTENING) return
        val heard = commandAt(commandText) ?: return
        (bar as? View)?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        if (!dictation.cutLast(heard.words)) return
        tipUsed("voicebar")
        when (heard.command) {
            VoiceCommands.Command.CLEANUP -> {
                // polish what's been said so far and keep listening
                forcePolish = true
                dictation.pause()
                return
            }
            VoiceCommands.Command.CANCEL -> {
                bar?.talkEnded()
                onTalkEnd(cancel = true)
                return
            }
            VoiceCommands.Command.SEND -> sendAfterTalk = true
            VoiceCommands.Command.KEYBOARD -> gboardAfterTalk = true
            // what's been said is typed (polished), then the keyboard goes away
            VoiceCommands.Command.HIDE -> hideAfterTalk = true
            VoiceCommands.Command.DONE -> Unit
        }
        bar?.talkEnded()
        forcePolish = true // like tapping the pill: always polished
        dictation.stop(cancel = false)
    }

    /** The finished text, minus its final period when millennial mode applies here. */
    private fun finishMessage(text: String): String = if (millennial) Millennial.strip(text).first else text

    private fun rememberStripped(final: String) {
        strippedTail = if (millennial && final.isNotEmpty() && final.last().isLetterOrDigit()) Millennial.tail(final) else null
    }

    /**
     * Dictating more right after a message we left without its period: it was the end of a
     * sentence after all, so the period goes back before the new words.
     */
    private fun restorePeriodIfFollowingUp() {
        delivered = false
        restoredPeriod = false
        val tail = strippedTail ?: return
        strippedTail = null
        val ic = currentInputConnection ?: return
        if (!millennial || !Millennial.needsPeriodBack(tail, ic.getTextBeforeCursor(tail.length, 0))) return
        ic.commitText(".", 1)
        restoredPeriod = true
    }

    /** The follow-up was thrown away or heard nothing: the message ends without its period again. */
    private fun takeBackRestoredPeriod() {
        if (!restoredPeriod) return
        restoredPeriod = false
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(1, 0)?.toString() == ".") {
            ic.deleteSurroundingText(1, 0)
            strippedTail = ic.getTextBeforeCursor(Millennial.TAIL_LENGTH, 0)?.toString()
        }
    }

    /** Words recognized so far in this dictation, so the silence countdown resets only on new ones. */

    /** This dictation types into the field as you speak (rather than previewing above the bubble). */
    private var liveTyping = false

    private val liveField = object : LiveField {
        override fun setComposing(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.setComposingText(text, 1) }
        override fun finishComposing() { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.finishComposingText() }
        override fun commit(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.commitText(text, 1) }
        override fun textBefore(n: Int): CharSequence? = currentInputConnection?.getTextBeforeCursor(n, 0)
        override fun deleteBefore(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
        override fun textAfter(n: Int): CharSequence? = currentInputConnection?.getTextAfterCursor(n, 0)
        override fun deleteAfter(n: Int) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.deleteSurroundingText(0, n) }
        override fun setSelection(start: Int, end: Int) { currentInputConnection?.setSelection(start, end) }
        override fun beginBatch() { currentInputConnection?.beginBatchEdit() }
        override fun endBatch() { currentInputConnection?.endBatchEdit() }
    }

    private val liveTyper: LiveTyper by lazy {
        // the app ended our draft mid-speech: what's typed stays, the next words start a new draft
        // the user took over the field mid-dictation (moved the cursor, selected text): stop listening
        // and finish in place, so the dictation never fights them for the cursor
        LiveTyper({ if (currentInputConnection != null) liveField else null }, onUserEdit = { userTookOver() })
    }

    /**
     * Live typing needs a real text editor: not a password (spoken passwords shouldn't appear as
     * you say them) and not a raw-key field like a terminal, which can't hold a draft.
     */
    private fun fieldTakesLiveText(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type == InputType.TYPE_NULL) return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return false
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false
        return true
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (stick.active && stick.onSelection(newSelStart, newSelEnd)) return // the joystick's own marker
        bar?.hasSelection = newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd
        if (liveTyping) liveTyper.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    /** The field's selection as last reported. */
    private var selStart = -1
    private var selEnd = -1

    /**
     * The cursor joystick's side of things. An IME can't draw inside the app's text, so the
     * target marker is shown as a one-character selection at the target (the field highlights
     * it); a tap collapses it to a cursor there. Left and right move the target by characters
     * directly; up and down ask the app to move a cursor by a line (so wrapped lines work) and
     * read where it landed. Selecting keeps the anchor at the cursor and moves the other end.
     */
    private inner class Stick {
        var active = false
        /** Where the blinking cursor is (or the selection's fixed end), and where the stick is aimed. */
        var cursor = 0
        var target = 0
        /** The one-character highlight standing in for the marker, while it's up. */
        var markStart = -1
        var markEnd = -1
        var selecting = false
        var anchor = 0
        /** A real selection that was up when aiming began: it comes back if the aim isn't taken. */
        private var keptStart = -1
        private var keptEnd = -1

        /** Puts back what aiming covered up: the selection that was there, or the cursor. */
        private fun restore() {
            if (keptStart >= 0) setSel(keptStart, keptEnd) else setSel(cursor, cursor)
            keptStart = -1; keptEnd = -1
        }
        /** A line move is out with the app; the next collapsed cursor report is where it landed. */
        var awaitingLine = false
        private var awaitingSince = 0L
        /** Where the line move started from: reports of the cursor still there aren't its answer. */
        private var lineFrom = -1
        private var length = 0

        /** A line move that never answered (the first or last line): give up on it. */
        private fun lineTimedOut(): Boolean {
            if (awaitingLine && SystemClock.uptimeMillis() - awaitingSince > LINE_MOVE_TIMEOUT_MS) {
                awaitingLine = false
                if (!selecting) mark()
            }
            return !awaitingLine
        }

        fun start() {
            active = true
            selecting = false
            awaitingLine = false
            cursor = if (selStart >= 0) selEnd else 0
            target = cursor
            clearMark()
            refreshLength()
        }

        fun end() {
            if (!active) return
            // aimed but never tapped: the cursor (or selection) stays where it was
            if (markStart >= 0 || awaitingLine) restore()
            active = false
            clearMark()
        }

        private fun refreshLength() {
            val et = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            length = if (et?.text != null) et.startOffset + et.text.length else Int.MAX_VALUE / 2
        }

        private fun clearMark() { markStart = -1; markEnd = -1 }

        private fun setSel(a: Int, b: Int) {
            lastOwnEditAt = SystemClock.uptimeMillis()
            currentInputConnection?.setSelection(a, b)
        }

        /** Moves the blinking cursor to the target, between characters, as it's aimed. */
        private fun mark() {
            keptStart = -1; keptEnd = -1
            clearMark()
            cursor = target
            setSel(target, target)
        }

        fun step(cols: Int, lines: Int) {
            if (selecting) { stepSelecting(cols, lines); return }
            if (!lineTimedOut()) return
            if (lines != 0) {
                // at the very start or end an arrow would move focus out of the field instead
                if ((lines < 0 && target == 0) || (lines > 0 && target >= length)) return
                if (markStart < 0 && keptStart < 0 && selStart != selEnd && selStart >= 0) { keptStart = selStart; keptEnd = selEnd }
                awaitingLine = true
                awaitingSince = SystemClock.uptimeMillis()
                lineFrom = target
                // let the app move a real cursor a line, so wrapping is respected
                clearMark()
                setSel(target, target)
                repeat(kotlin.math.abs(lines)) { sendDownUpKeyEvents(if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
                return
            }
            target = (target + cols).coerceIn(0, length)
            mark()
        }

        private fun stepSelecting(cols: Int, lines: Int) {
            if (!lineTimedOut()) return
            if (lines != 0) {
                if ((lines < 0 && target == 0) || (lines > 0 && target >= length)) return
                awaitingLine = true
                awaitingSince = SystemClock.uptimeMillis()
                lineFrom = target
                val code = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                val ic = currentInputConnection ?: return
                lastOwnEditAt = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                repeat(kotlin.math.abs(lines)) {
                    val now = SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
                }
                return
            }
            target = (target + cols).coerceIn(0, length)
            setSel(anchor, target)
        }

        /** Takes the marker down, putting the cursor back where it was. */
        fun dropMark() {
            if (markStart < 0) return
            clearMark()
            target = cursor
            restore()
        }

        /** The knob was held still: select from the cursor. */
        fun select() {
            keptStart = -1; keptEnd = -1
            selecting = true
            awaitingLine = false
            anchor = cursor
            target = cursor
            clearMark()
            setSel(cursor, cursor)
        }

        /** A tap: the cursor jumps to the target. */
        fun tap() {
            keptStart = -1; keptEnd = -1
            awaitingLine = false
            selecting = false
            cursor = target
            clearMark()
            setSel(cursor, cursor)
        }

        /**
         * A selection report while the joystick is up. True when it's our own marker (so the
         * keyboard shouldn't treat it as the user's selection).
         */
        /**
         * The end of a reported selection that the stick is moving. Apps report selections
         * normalized (start <= end), so a selection grown leftward of the anchor comes back with the
         * moving end first: taking "end" there snapped the target back to the anchor every step, and
         * a leftward drag just rattled one or two characters from it.
         */
        private fun movingEnd(start: Int, end: Int): Int = stickMovingEnd(anchor, target, start, end)

        fun onSelection(start: Int, end: Int): Boolean {
            if (awaitingLine) {
                if (selecting) {
                    val moved = movingEnd(start, end)
                    if (moved == lineFrom) return false
                    awaitingLine = false
                    target = moved
                    return false // a real selection, grown a line
                }
                if (start != end || start == lineFrom) return true // not the line move's answer yet
                awaitingLine = false
                target = start
                mark()
                return true
            }
            if (start == markStart && end == markEnd && markStart >= 0) return true
            if (!selecting && start == end && markStart < 0) {
                // the cursor moved some other way (an edit button, the app): follow it
                cursor = start
                target = start
            }
            if (selecting) target = movingEnd(start, end)
            refreshLength()
            return false
        }
    }
    private val stick = Stick()

    override fun onStickStart() {
        stick.start()
    }

    override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) = stick.step(cols, lines)
    override fun onStickTap() = stick.tap()
    override fun onStickSelect() = stick.select()
    override fun onStickEnd() = stick.end()

    /** When we last typed or edited, so our own cursor moves aren't mistaken for the user's taps. */
    private var lastOwnEditAt = 0L

    private fun ownEdit() {
        lastOwnEditAt = SystemClock.uptimeMillis()
    }

    /** Whether the clipboard holds something pasteable as text, from its description only. */
    private fun clipHasText(): Boolean {
        val clip = getSystemService(ClipboardManager::class.java) ?: return false
        if (!clip.hasPrimaryClip()) return false
        val desc = clip.primaryClipDescription ?: return true
        return desc.mimeTypeCount == 0 || (0 until desc.mimeTypeCount).any {
            desc.getMimeType(it).startsWith("text/") || desc.getMimeType(it) == android.content.ClipDescription.MIMETYPE_TEXT_URILIST
        }
    }

    private fun refreshCanPaste() {
        val can = runCatching { clipHasText() }.getOrDefault(false)
        bar?.canPaste = can
        bar?.clipPreview = if (can) runCatching { clipStart() }.getOrNull() else null
        bar?.clips = ClipHistory.of(this).all()
        bar?.dictations = DictationHistory.of(this).all()
    }

    /** Something new on the clipboard (while Voicebar is the keyboard, or Voicebar's own copy): into the history. */
    private fun rememberClip() {
        runCatching {
            val text = ClipHistory.textOf(this, getSystemService(ClipboardManager::class.java)?.primaryClip) ?: return
            ClipHistory.of(this).add(text)
        }
    }

    /** The dictation just finished, as heard (before cleanup). */
    private var heardAll: String? = null

    override fun onDictation(action: Bar.DictationAction, time: Long) {
        AppLog.log("menu", "dictation $action")
        val h = DictationHistory.of(this)
        val e = h.all().firstOrNull { it.time == time } ?: return
        when (action) {
            Bar.DictationAction.PASTE_TYPED, Bar.DictationAction.PASTE_HEARD -> {
                val t = if (action == Bar.DictationAction.PASTE_HEARD) e.heard else e.typed.ifBlank { e.heard }
                ownEdit()
                keyDuringDictation()
                typeDictation(t)
            }
            Bar.DictationAction.DELETE -> h.remove(time)
            Bar.DictationAction.RECLEAN -> reclean(e)
        }
        bar?.dictations = h.all()
    }

    override fun onRepeatDictation() {
        val last = DictationHistory.of(this).all().firstOrNull()
        AppLog.log("menu", "last dictation key: " + (last?.let { AppLog.excerpt(it.typed.ifBlank { it.heard }) } ?: "none yet"))
        last?.let { onDictation(Bar.DictationAction.PASTE_TYPED, it.time) }
    }

    override fun onClip(action: Bar.ClipAction, text: String) {
        AppLog.log("menu", "clip $action")
        val h = ClipHistory.of(this)
        when (action) {
            Bar.ClipAction.PASTE -> {
                ownEdit()
                keyDuringDictation()
                currentInputConnection?.commitText(text, 1)
            }
            Bar.ClipAction.PIN -> h.setPinned(text, true)
            Bar.ClipAction.UNPIN -> h.setPinned(text, false)
            Bar.ClipAction.DELETE -> h.remove(text)
        }
        bar?.clips = h.all()
    }

    /** The first few dozen characters on the clipboard, for the paste button while dictating. */
    private fun clipStart(): String? {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.ifBlank { null }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { rememberClip(); refreshCanPaste() }

    private fun userTookOver() {
        if (dictation.state != DictationController.State.LISTENING) return
        bar?.talkEnded()
        dictation.stop(cancel = false)
    }

    /** A key typed mid-dictation: what's spoken so far stays as typed, and later words follow the key. */
    private fun keyDuringDictation() {
        if (dictation.dictating) guard.touch()
        if (!liveTyping || !dictation.dictating) return
        liveTyper.interrupt(newSegment = dictation.markBoundary(liveTyper.typedWords()))
    }

    // ---------------------------------------------------------------- first-run tips

    /** What the tips bubble shows now, if anything. */
    private var tipShowing: Tips.Showing? = null
    /** The tour already came up since the keyboard opened. */
    private var tipShownThisOpen = false

    /** When the keyboard opens: the tour, from where it was left, if it isn't finished (and tips are on). */
    private fun maybeTip() {
        if (!inputShown || tipShowing != null || tipShownThisOpen || dictation.dictating) return
        val step = Tips.resume(this) ?: return
        tipShownThisOpen = true
        showTip(Tips.Showing.Step(step))
    }

    private fun showTip(s: Tips.Showing?) {
        tipShowing = s
        val dock = bar as? DockView ?: return
        val of = Tips.Id.entries.size
        dock.tip = when (s) {
            null -> null
            is Tips.Showing.Step -> DockView.Tip(s.id.anchor, getString(s.id.title), getString(s.id.body), s.index + 1, of)
            is Tips.Showing.Ask -> DockView.Tip(Tips.Id.entries[s.index].anchor, getString(R.string.tip_ask_title), getString(R.string.tip_ask), s.index + 1, of, ask = true)
        }
    }

    /** Puts the bubble away for now (the tour resumes there next time). */
    private fun hideTip() = showTip(null)

    /** The bar reports a feature used: starting to talk puts the tour away for now. */
    private fun tipUsed(key: String) {
        if (key == "voice" && tipShowing != null) hideTip()
    }

    private fun tipAnswered(a: Tips.Answer) {
        val now = tipShowing ?: return
        val (next, resume) = Tips.answer(now, a)
        Tips.save(this, resume)
        showTip(next)
    }

    override fun onCreateInputView(): View {
        val view = DockView(this, null).apply {
            listener = this@VoicebarService
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            onUsed = { key -> tipUsed(key) }
            onTipAnswer = { a -> tipAnswered(a) }
        }
        bar = view as KeyboardSurface
        return view
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        dockWindow(view)
    }

    /** Ends any dictation now, leaving whatever it typed (the field is going away or changing). */
    private fun stopDictationKeepingText() {
        if (liveTyping) liveTyper.end()
        dictation.abort()
    }

    private var fieldKey: String? = null

    /**
     * Some apps (browsers especially) move between fields by restarting input on the same view,
     * without finishing the input view: a dictation still finishing must not land in the new field.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val key = attribute?.let { "${it.packageName}/${it.fieldId}/${it.fieldName}" }
        if (key != fieldKey) {
            // left the field: one last look at what was typed there (as accessibility last saw it)
            if (CorrectionWatch.pending != null) CorrectionWatch.finish(null)
            stopDictationKeepingText()
            strippedTail = null
        }
        fieldKey = key
        // switched here from Gboard's Voicebar button: make sure the bar actually shows. With a
        // physical keyboard attached, Android switches keyboards but doesn't re-show the new one,
        // which would leave the field with no keyboard at all
        if (SystemClock.uptimeMillis() < GboardReturn.voiceUntil && android.os.Build.VERSION.SDK_INT >= 28) {
            window?.window?.decorView?.post { if (!isInputViewShown) runCatching { requestShowSelf(0) } }
        }
    }

    /**
     * Show the bar even with a physical keyboard attached (Android hides on-screen keyboards then,
     * unless "Show on-screen keyboard" is on): it's one row, and voice is still a tap away.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshCanPaste()
        bar?.hasSelection = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        if (!restarting) {
            stopDictationKeepingText()
            bar?.reloadSettings()
            bar?.reset()
        }
        getSystemService(ClipboardManager::class.java)?.let { it.removePrimaryClipChangedListener(clipListener); it.addPrimaryClipChangedListener(clipListener) }
        // just switched back from Gboard with the return button, or "listen as soon as a text box
        // opens" is on (and this is a new field, not a password): go straight to listening
        val fromGboard = SystemClock.uptimeMillis() < GboardReturn.voiceUntil
        // …but not with a physical keyboard attached: you're about to type, so the bar just opens
        val autoListen = !restarting && Prefs.get(this).getBoolean(Prefs.AUTO_LISTEN, Prefs.DEFAULT_AUTO_LISTEN) &&
            fieldTakesLiveText(info) && !HardKeyboard.attached(resources.configuration)
        inputShown = true
        AppLog.log("field", "opened ${info?.packageName} type=0x${Integer.toHexString(info?.inputType ?: 0)} restarting=$restarting auto=$autoListen fromGboard=$fromGboard")
        // Parakeet takes a moment to load: start now, so it's ready when you talk
        com.ryderdavid.voicebar.voice.Parakeet.Engine.warm(this)
        // back in the field (from Gboard, say): see whether what we typed was corrected
        if (CorrectionWatch.pending?.field == fieldKey) CorrectionWatch.check(readField())
        (bar as? android.view.View)?.post { showLearnedNote() }
        if (fromGboard || autoListen) {
            GboardReturn.voiceUntil = 0L
            // the field opening started it, not a tap: the auto-listen safeguards apply
            val auto = autoListen && !fromGboard
            (bar as? android.view.View)?.post { autoStartPending = auto; bar?.startVoice(); autoStartPending = false }
        }
        // after any auto-start has had its turn (dictating, it stays off)
        (bar as? android.view.View)?.post { updateWake() }
        if (!restarting) {
            tipShownThisOpen = false
            hideTip()
            (bar as? android.view.View)?.postDelayed({ maybeTip() }, 450)
            (bar as? android.view.View)?.postDelayed({ if (inputShown && !dictation.dictating) warnIfWayBackSwitchedOff() }, 700)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputShown = false
        // held words were for this message box: leaving it throws them away
        if (heldShown) { heldShown = false; bar?.hideVoice() }
        hideTip()
        WakeWord.want(this, WAKE_OWNER, null)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        // keep what's been typed, before the view's reset would throw out a dictation in progress
        stopDictationKeepingText()
        bar?.reset()
        super.onFinishInputView(finishingInput)
    }

    /**
     * A floating hint above the bar (DockView.floatRoom) grows the window, but the app stays
     * where it was and touches there go to the app: content and visible insets start at the
     * bar's surface, and only the bar is touchable.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = bar as? DockView ?: return
        val room = v.floatRoom()
        if (room <= 0 || !v.isShown) return
        outInsets.contentTopInsets += room
        outInsets.visibleTopInsets += room
        val h = window.window?.decorView?.height ?: return
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, outInsets.contentTopInsets, v.width, h)
        // a tip's bubble, or the own key's column of choices, takes taps; the rest of the room stays the app's
        (v.pickerRect() ?: v.tipRect())?.let { r ->
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            outInsets.touchableRegion.op(android.graphics.Rect(r.left.toInt() + loc[0], r.top.toInt() + loc[1], r.right.toInt() + loc[0], r.bottom.toInt() + loc[1]),
                android.graphics.Region.Op.UNION)
        }
    }

    /** Never an extract (fullscreen) editor: the bar sits under the app. */
    override fun onEvaluateFullscreenMode() = false

    /**
     * The docked bar is an ordinary bottom keyboard: the window wraps it, and it draws its own
     * surface under the navigation area. On Android 15+ the system's ⌄/switcher band is hidden so
     * the bar sits right on the gesture area (back still closes the keyboard; switching lives in ⋮).
     */
    private fun dockWindow(view: View) {
        window.window?.let { w ->
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !night
            val lp = w.attributes
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                w.attributes = lp
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runCatching { w.insetsController?.hide(android.view.WindowInsets.Type.captionBar()) }
            }
        }
        val inputArea = window.window?.findViewById<View>(android.R.id.inputArea)
        inputArea?.layoutParams?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            inputArea.layoutParams = it
        }
        view.requestApplyInsets()
    }

    private companion object {
        /** How long recognition and cleanup wait for the screen's text before going without it. */
        const val SCREEN_WAIT_MS = 400L
        /** Cursor moves this soon after our own typing are ours, not the user's. */
        /** How long the joystick waits for the app to report a line move before giving up on it. */
        const val LINE_MOVE_TIMEOUT_MS = 300L
    }

    // ---------------------------------------------------------------- Bar.Listener

    override fun onKey(key: Bar.Key, repeat: Boolean) {
        ownEdit()
        // ↵ while dictating is a line break, never the field's Send/Search action
        val lineBreak = key == Bar.Key.ENTER && dictation.dictating
        keyDuringDictation()
        if (lineBreak) { currentInputConnection?.commitText("\n", 1); return }
        when (key) {
            Bar.Key.UP -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            Bar.Key.DOWN -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            // at either end of the text a d-pad arrow moves focus to another view, which closes the
            // keyboard mid-repeat; stop at the end instead
            Bar.Key.LEFT -> if (canMove(before = true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            Bar.Key.RIGHT -> if (canMove(before = false)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            Bar.Key.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            Bar.Key.SPACE -> currentInputConnection?.commitText(" ", 1)
            // Ctrl+Z: the undo every text field, web page and Compose field understands
            Bar.Key.UNDO -> currentInputConnection?.let { ic ->
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            }
            // runs the field's action (search, send, next...) when it has one, else a newline
            Bar.Key.ENTER -> sendKeyChar('\n')
        }
    }

    /** Whether the cursor has room to move left ([before]) or right, or a selection to collapse. */
    private fun canMove(before: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return true
        val next = if (before) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        return next == null || next.isNotEmpty() // null: the app won't say, so let the key through
    }

    /** Cut, copy and paste are done by the app itself, so the keyboard never reads the clipboard. */
    override fun onEdit(action: Bar.EditAction) {
        ownEdit()
        // an aimed-at marker is only a highlight: edits act on the real cursor or selection
        if (stick.active) stick.dropMark()

        keyDuringDictation()
        val ic = currentInputConnection ?: return
        when (action) {
            Bar.EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
            Bar.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            Bar.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            Bar.EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            // a backspace with text selected deletes the selection, like any keyboard's
            Bar.EditAction.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ABC: hands the field to Gboard; the way-back button over it (GboardButtonService) brings us back. */
    /** When the "way back is off" warning was shown: ABC again soon after switches anyway. */
    private var wayBackWarnedAt = 0L
    /** ABC was tapped mid-dictation: switch to Gboard once the dictation has been typed. */
    private var gboardAfterTalk = false

    /** "Voice bar, close" mid-dictation: put the keyboard away once the text is in. */
    private var hideAfterTalk = false

    /** A long press on the voice pill: send once the dictation has been typed. */
    private var sendAfterTalk = false

    override fun onSendAfterTalk() {
        dropPendingCommand()
        guard.touch()
        if (dictation.dictating) sendAfterTalk = true else (bar as? View)?.postDelayed({ send() }, SEND_DELAY_MS)
    }

    /**
     * Sends the message: the field's own Send (or Go/Search) action when it offers one to
     * keyboards; otherwise the app's Send button, pressed through the accessibility service.
     */
    private fun send() {
        // what's about to be sent, in case Send can't be found: it's kept and copied, never lost
        // (the last dictation only if it was just now: never an old message brought back)
        messageBeforeSend = readField()?.takeIf { it.isNotBlank() }
            ?: lastDelivered?.takeIf { SystemClock.uptimeMillis() - lastDeliveredAt < 15_000L }
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (ic != null && !noEnterAction && action in SEND_ACTIONS && ic.performEditorAction(action)) { AppLog.log("send", "editor action $action"); messageBeforeSend = null; return }
        pressSend(SEND_TRIES)
    }

    /**
     * The app's own Send button. Many apps only show or enable it once they've caught up with the
     * new text (a mic button turning into Send), which can take longer than the moment we wait
     * after typing: so look again a few times before saying it isn't there.
     */
    private fun pressSend(triesLeft: Int) {
        if (GboardButtonService.running?.clickSend() == true) { AppLog.log("send", "pressed the app's Send"); messageBeforeSend = null; lastDelivered = null; return }
        if (triesLeft > 1 && GboardButtonService.running != null) {
            (bar as? View)?.postDelayed({ pressSend(triesLeft - 1) }, SEND_RETRY_MS) ?: sendFailed()
            return
        }
        sendFailed()
    }

    /** The message box's text when Send was asked for (or the last dictation's), for [sendFailed]. */
    private var messageBeforeSend: String? = null
    /** The last dictation typed into a field, and when. */
    private var lastDelivered: String? = null
    private var lastDeliveredAt = 0L

    /**
     * Send isn't there: whatever was in the message stays in the box (put back if it went
     * missing) and goes on the clipboard too, so it's never lost.
     */
    private fun sendFailed() {
        AppLog.log("send", "Send not found; service=${GboardButtonService.running != null} kept=" + AppLog.excerpt(messageBeforeSend))
        val text = messageBeforeSend?.takeIf { it.isNotBlank() }
        messageBeforeSend = null
        var copied = false
        if (text != null) {
            val now = readField().orEmpty()
            if (!now.contains(text.trim())) typeDictation(text)
            copied = copyDictation(text)
        }
        // the Gboard button's service is what presses an app's own Send: off, say so, with a tap to fix it
        if (GboardButtonService.running == null) { bar?.showMessage(getString(R.string.send_needs_service)) { openAccessibility() }; return }
        bar?.showMessage(getString(if (copied) R.string.send_not_found_kept else R.string.send_not_found))
    }

    private fun openAccessibility() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Android sometimes switches the Gboard button's service off by itself (after the app's
     * process crashes or is killed a few times in a row, or a battery saver steps in). When it was
     * on before and isn't now, say so on the bar, at most once a day, with a tap to fix it.
     */
    private fun warnIfWayBackSwitchedOff() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.WAY_BACK_WAS_ON, false) || GboardButtonService.running != null) return
        val now = System.currentTimeMillis()
        if (now - p.getLong(Prefs.WAY_BACK_WARNED_AT, 0) < 24 * 3600_000L) return
        p.edit().putLong(Prefs.WAY_BACK_WARNED_AT, now).apply()
        val msg = if (GboardButtonService.isEnabled(this)) R.string.way_back_stopped else R.string.way_back_switched_off
        bar?.showMessage(getString(msg)) { openAccessibility() }
    }

    override fun onTypeAfterTalk() {
        dropPendingCommand()
        guard.touch()
        if (dictation.dictating) gboardAfterTalk = true else onSwitchToGboard()
    }

    override fun onSwitchToGboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val id = Prefs.gboardImeId(this)
        if (id == null) { bar?.showMessage(getString(R.string.gboard_not_enabled)); return }
        // without the way-back service there'd be no button on Gboard to come back with: say so first
        val now = SystemClock.uptimeMillis()
        if (android.os.Build.VERSION.SDK_INT >= 30 && !GboardButtonService.isEnabled(this) && now - wayBackWarnedAt > WAY_BACK_WARNING_MS) {
            wayBackWarnedAt = now
            bar?.showMessage(getString(R.string.way_back_off)) {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        wayBackWarnedAt = 0L
        val ok = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) switchInputMethod(id)
            else @Suppress("DEPRECATION") window.window?.attributes?.token?.let { imm.setInputMethod(it, id) }
        }.isSuccess
        if (!ok) bar?.showMessage(getString(R.string.gboard_not_enabled))
    }

    override fun onSwitchKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showInputMethodPicker()
    }

    override fun shouldCapitalize(): Boolean {
        val ic = currentInputConnection ?: return false
        val inputType = currentInputEditorInfo?.inputType ?: return false
        return ic.getCursorCapsMode(inputType) != 0
    }

    override fun onTalkStart(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bar?.showMessage(getString(R.string.voice_need_mic))
            return false
        }
        if (dictation.dictating) return false // still finishing the last one
        // dictating by hand: hands-free mode lets go of the microphone
        if (com.ryderdavid.voicebar.voice.HandsFreeStart.active) com.ryderdavid.voicebar.voice.HandsFreeStart.stop()
        // a new dictation: the last one's corrections, if any, are in by now
        if (CorrectionWatch.pending?.field == fieldKey) CorrectionWatch.check(readField())
        // the wake-word watcher lets go of the mic for the real dictation
        WakeWord.want(this, WAKE_OWNER, null)
        bar?.showCommand(null)
        refreshCanPaste() // the paste button beside the voice pill shows what's on the clipboard
        forcePolish = false
        ownEdit()
        toClipboard = !hasTextField()
        lastLiveText = ""
        val info = currentInputEditorInfo
        val chatOrEmail = com.ryderdavid.voicebar.voice.ChatApps.isChat(info) ||
            info?.packageName in com.ryderdavid.voicebar.voice.AutoListenGuard.EMAIL_PACKAGES
        guard.start(autoStartPending, chatOrEmail, Prefs.get(this).getFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.DEFAULT_TYPICAL),
            careful = Prefs.get(this).getBoolean(Prefs.CHAT_CARE, Prefs.DEFAULT_CHAT_CARE))
        manualLevels.clear()
        // an auto-started dictation in a chat or email app previews in the pill: nothing reaches the
        // message box until it's clearly yours (see onResult)
        liveTyping = !toClipboard && !guard.careful && Prefs.get(this).getBoolean(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE) &&
            fieldTakesLiveText(currentInputEditorInfo)
        AppLog.log("dictation", "start auto=${guard.auto} chatOrEmail=$chatOrEmail live=$liveTyping toClipboard=$toClipboard in ${info?.packageName}")
        (bar as? View)?.let { v ->
            v.removeCallbacks(guardWindow); v.removeCallbacks(guardCap)
            if (guard.auto) v.postDelayed(guardWindow, com.ryderdavid.voicebar.voice.AutoListenGuard.START_WINDOW_MS)
            if (guard.careful) v.postDelayed(guardCap, com.ryderdavid.voicebar.voice.AutoListenGuard.CAP_MS)
            v.removeCallbacks(decisionTick)
            divertToClipboard = false
            talkId++
            lastCheckedText = ""
            if (guard.chat && safeguardCheckOn()) v.postDelayed(decisionTick, com.ryderdavid.voicebar.voice.Decisions.EVERY_MS)
        }
        bar?.voiceInField = liveTyping
        aroundCursor = readAround()
        fieldCleaner = null
        return dictation.start()
    }

    /** This dictation began with no text field to type into: it goes on the clipboard instead. */
    private var toClipboard = false

    /** The next dictation is being started by a field opening (auto-listen), not a tap. */
    private var autoStartPending = false
    private val guard = com.ryderdavid.voicebar.voice.AutoListenGuard()
    /** Levels of a dictation started by hand, to learn how loud the user talks at the phone. */
    private val manualLevels = ArrayList<Float>()
    /** The guard is stopping this dictation itself (not the user's ✕). */
    private var guardStopping = false
    /** Nobody talked at the phone in the start window: stop, quietly, with nothing typed. */
    private val guardWindow = Runnable {
        if (!dictation.dictating || !guard.expired()) return@Runnable
        AppLog.log("guard", "start window passed with nobody talking at the phone: stopped")
        guardStopping = true
        bar?.reset()
        guardStopping = false
        bar?.hideVoice()
    }
    /** An untouched auto-started dictation in a chat or email app has run a minute: end it (its text is held). */
    /** Which dictation this is, so a late safeguard answer can't act on the next one. */
    private var talkId = 0
    /** The safeguard judged this dictation to be the room, not a message: it goes to the clipboard. */
    private var divertToClipboard = false
    private var lastCheckedText = ""

    private fun safeguardCheckOn() = Secrets.has(this, Secrets.OPENAI) &&
        Prefs.get(this).getBoolean(Prefs.SAFEGUARD_CHECK, Prefs.DEFAULT_SAFEGUARD_CHECK)

    /**
     * Every 10 s of an auto-started dictation in a chat or email app (once it's someone talking at
     * the phone, and the user hasn't taken it over): OpenAI Decisions judges whether it's still a
     * message for this conversation. If it's the room, the dictation ends and goes to the clipboard.
     */
    private val decisionTick: Runnable = object : Runnable {
        override fun run() {
            if (!dictation.dictating || !guard.chat || guard.touched || divertToClipboard) return
            (bar as? View)?.postDelayed(this, com.ryderdavid.voicebar.voice.Decisions.EVERY_MS)
            if (!guard.engaged) return
            val text = (livePending(lastLiveText)?.before ?: lastLiveText).trim()
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            if (words < com.ryderdavid.voicebar.voice.Decisions.MIN_WORDS || text == lastCheckedText) return
            lastCheckedText = text
            val key = Secrets.get(this@VoicebarService, Secrets.OPENAI) ?: return
            val pkg = currentInputEditorInfo?.packageName
            val app = pkg?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }.orEmpty()
            val conversation = runCatching { screenNow?.takeIf { it.isDone }?.get()?.text }.getOrNull() ?: aroundCursor?.before.orEmpty()
            val id = talkId
            Thread({
                val p = com.ryderdavid.voicebar.voice.Decisions.ask(http, key, com.ryderdavid.voicebar.voice.Decisions.input(app, conversation, text), onUsage = usage::addClaude)
                (bar as? View)?.post {
                    val room = p != null && p < com.ryderdavid.voicebar.voice.Decisions.ROOM_BELOW
                    val act = room && id == talkId && dictation.dictating && !guard.touched
                    com.ryderdavid.voicebar.voice.SafeguardLog.add(this@VoicebarService,
                        com.ryderdavid.voicebar.voice.SafeguardLog.Entry(System.currentTimeMillis(), app, p, words, act, text.take(60)))
                    AppLog.log("safeguard", "verdict p=$p words=$words act=$act")
                    if (act) { divertToClipboard = true; bar?.finishVoice() }
                }
            }, "voicebar-safeguard").start()
        }
    }

    /** Held words are showing in the pill: they belong to this field and go when it does. */
    private var heldShown = false
    private val guardCap = Runnable { if (dictation.dictating && guard.capped()) bar?.finishVoice() }

    /** A text field is focused and connected (not a raw-key view or nothing at all). */
    private fun hasTextField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return currentInputConnection != null && info.inputType != InputType.TYPE_NULL
    }

    /** Puts dictated [text] on the clipboard; false if the system wouldn't take it. */
    private fun copyDictation(text: String): Boolean = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.dictation_clip_label), text))
        // into the history too, so it never pushes out what was there before (and even if the listener's off)
        ClipHistory.of(this).add(text)
        bar?.clips = ClipHistory.of(this).all()
        true
    }.getOrDefault(false)

    /** ✓ on the docked bar: always polish, whatever the cleanup setting says. */
    private var forcePolish = false

    /**
     * Claude Haiku or GPT-6 Luna: the one chosen, or whichever has a key; null when cleanup is off
     * (unless [polish] forces it) or there's no key. [forField]: it's for the field being typed in, so
     * it gets the screen's text and the text around the cursor; a retry from the ⋮ menu gets neither.
     */
    private fun newCleaner(polish: Boolean, forField: Boolean): com.ryderdavid.voicebar.voice.Cleaner? {
        val prefs = Prefs.get(this)
        val choice = CleanupModels.resolve(this)
        AppLog.log("cleanup", "model ${choice?.model ?: "none (no key)"} cleanupOn=${prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)} forced=$polish")
        if ((!polish && !prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)) || choice == null) return null
        val screen = { if (!forField) "" else runCatching { screenNow?.get(SCREEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()?.prompt ?: "" }
        val around = { if (forField) aroundCursor else null }
        val cleaner = if (choice.provider == CleanupModels.Provider.OPENAI) com.ryderdavid.voicebar.voice.OpenAICleaner(
            // a little more room than Claude gets: a slow first reply shouldn't lose the cleanup
            choice.key, choice.model, http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build(),
            dictionary = { Dictionary.of(this).promptSection() },
            screen = screen,
            children = { com.ryderdavid.voicebar.voice.ChildVoices.enabled(this) },
            around = around,
            onUsage = usage::addClaude,
        )
        else ClaudeCleaner(
            choice.key, choice.model, http,
            dictionary = { Dictionary.of(this).promptSection() },
            screen = screen,
            children = { com.ryderdavid.voicebar.voice.ChildVoices.enabled(this) },
            around = around,
            onUsage = usage::addClaude,
        )
        if (forField) fieldCleaner = cleaner
        return cleaner
    }

    /**
     * A dictation tapped in the ⋮ menu: what was said goes to the cleanup model again, and the new
     * cleanup goes on the clipboard (and becomes that dictation's typed text). For a bad cleanup.
     */
    private fun reclean(e: DictationHistory.Entry) {
        val b = bar ?: return
        if (b.recleaning != null) return
        val raw = e.heard.ifBlank { e.typed }
        val cleaner = newCleaner(polish = true, forField = false)
            ?: return b.showMessage(getString(R.string.dictation_reclean_no_key))
        b.recleaning = e.time
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val t0 = System.currentTimeMillis()
            AppLog.log("cleanup", "again from the menu, start with ${cleaner.service}: " + AppLog.excerpt(raw))
            val attempt = runCatching { cleaner.clean(raw) }
            val result = attempt.getOrNull()?.takeIf { it.isNotBlank() }
            val why = if (result != null) null
                else attempt.exceptionOrNull()?.let { com.ryderdavid.voicebar.voice.cleanupFailure(it, cleaner.service) } ?: cleaner.problem ?: "no reply from ${cleaner.service}"
            AppLog.log("cleanup", "again from the menu, " + (if (why == null) "ok" else "failed ($why)") + " in ${System.currentTimeMillis() - t0} ms" +
                (result?.let { ": " + AppLog.excerpt(it) } ?: ""))
            main.post {
                val bar = bar
                bar?.recleaning = null
                if (result == null) { bar?.showMessage(getString(R.string.dictation_reclean_failed, why)); return@post }
                if (!copyDictation(result)) { bar?.showMessage(getString(R.string.dictation_reclean_failed, "the clipboard wouldn't take it")); return@post }
                DictationHistory.of(this).retype(e.time, result)
                bar?.dictations = DictationHistory.of(this).all()
                bar?.showRecleaned(e.time)
            }
        }, "voicebar-reclean").start()
    }
    /** This dictation ended by itself (silence or the cap), not by the user. */
    private var endedBySilence = false

    override fun onTalkFinish(polish: Boolean) {
        dropPendingCommand()
        // ✓ or the pill polishes: the user ended it, so it's theirs; the silence timeout and the cap don't
        if (polish) guard.touch()
        endedBySilence = !polish
        forcePolish = polish
        onTalkEnd(false)
    }

    override fun onDeleteAll() {
        val ic = currentInputConnection ?: return
        ownEdit()
        keyDuringDictation()
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(100_000, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(100_000, 0)?.length ?: 0
        val sel = ic.getSelectedText(0)?.length ?: 0
        if (sel > 0) ic.commitText("", 1)
        if (before > 0 || after > 0) ic.deleteSurroundingText(before, after)
        ic.endBatchEdit()
    }

    /** A pause in hands-free dictation: polish what's been said so far, keep listening. */
    override fun onQuiet() = dictation.pause()

    override fun onTalkEnd(cancel: Boolean) {
        if (cancel && !guardStopping) guard.touch()
        (bar as? View)?.let { it.removeCallbacks(guardWindow); it.removeCallbacks(guardCap); it.removeCallbacks(decisionTick) }
        if (cancel && liveTyping) liveTyper.cancel()
        if (cancel) takeBackRestoredPeriod()
        dictation.stop(cancel)
    }

    /**
     * The field's text either side of the cursor as this dictation began (never a password), so the
     * cleanup fits the new words into that spot: capitals, punctuation, the right sound-alike.
     */
    @Volatile private var aroundCursor: com.ryderdavid.voicebar.voice.Around? = null

    private fun readAround(): com.ryderdavid.voicebar.voice.Around? {
        if (!fieldTakesLiveText(currentInputEditorInfo)) return null
        val ic = currentInputConnection ?: return null
        return runCatching {
            com.ryderdavid.voicebar.voice.Around.of(
                ic.getTextBeforeCursor(com.ryderdavid.voicebar.voice.Around.BEFORE_CHARS, 0),
                ic.getTextAfterCursor(com.ryderdavid.voicebar.voice.Around.AFTER_CHARS, 0),
                ic.getSelectedText(0))
        }.getOrNull()
    }

    /**
     * The cleaner this dictation's text came from (the last one made for the field), to read the
     * leftover words its reply named; null without cleanup.
     */
    @Volatile private var fieldCleaner: com.ryderdavid.voicebar.voice.Cleaner? = null

    /** Takes out the leftover words [fit] found at either side of [typed], now right before the cursor. */
    private fun removeLeftovers(typed: String, fit: Infill.Fit) {
        if (fit.removeBefore.isEmpty() && fit.removeAfter.isEmpty()) return
        ownEdit()
        val done = Infill.removeLeftovers(liveField, typed, fit)
        AppLog.log("infill", if (done) "took leftovers out of the field" else "leftovers weren't where expected: left alone")
    }

    /** Dictated into the middle of text: a space between the new words and a word right after them. */
    private fun spaceAfterInsert(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty() || text.last().isWhitespace()) return
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull() ?: return
        if (next.isLetterOrDigit()) { ownEdit(); ic.commitText(" ", 0) } // the cursor stays before the space
    }

    /** Types the dictated text, with a space before it when it would otherwise run into a word. */
    private fun typeDictation(text: String) {
        ownEdit()
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val sep = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        ic.commitText(sep + text, 1)
    }
}

/** The end of a normalized selection report [start]..[end] that isn't [anchor] (see Stick.movingEnd). */
internal fun stickMovingEnd(anchor: Int, target: Int, start: Int, end: Int): Int = when (anchor) {
    start -> end
    end -> start
    else -> if (kotlin.math.abs(start - target) < kotlin.math.abs(end - target)) start else end
}

/** How long after the way-back warning a second ABC tap switches anyway. */
private const val WAY_BACK_WARNING_MS = 6_000L

/** The keyboard's claim on the wake-word watcher (the Gboard button has its own). */
private const val WAKE_OWNER = "bar"

/** How long a spoken command must stay the last thing said before it runs. */
private const val COMMAND_HOLD_MS = 700L

/** Looking for the app's Send button: this many times, this far apart (about 1.5 s in all). */
private const val SEND_TRIES = 8
private const val SEND_RETRY_MS = 200L

/** After the dictation lands, a moment for the app to see the text before Send is pressed. */
private const val SEND_DELAY_MS = 200L
/** Field actions that mean "send this": the field's own Send, Go or Search. */
private val SEND_ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH)
