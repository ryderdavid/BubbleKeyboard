package com.ryderdavid.bubblekeyboard

import android.Manifest
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
import com.ryderdavid.bubblekeyboard.voice.ClaudeCleaner
import com.ryderdavid.bubblekeyboard.voice.ClaudeCleanupRequest
import com.ryderdavid.bubblekeyboard.voice.DictationController
import com.ryderdavid.bubblekeyboard.voice.ElevenLabsTranscriber
import com.ryderdavid.bubblekeyboard.voice.LiveField
import com.ryderdavid.bubblekeyboard.voice.LiveTyper
import com.ryderdavid.bubblekeyboard.voice.MicRecorder
import com.ryderdavid.bubblekeyboard.voice.Millennial
import com.ryderdavid.bubblekeyboard.voice.Secrets
import com.ryderdavid.bubblekeyboard.voice.UsageLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The input method. Its whole UI is a [BubbleView] that spans the IME window; only the bubble,
 * ring and bloom take touches, and the app behind is never resized.
 */
class BubbleKeyboardService : InputMethodService(), BubbleView.Listener {

    private var bubbleView: BubbleView? = null
    private val padLocation = IntArray(2)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // the cleanup call is short; never leave the user waiting long for it
            .callTimeout(8, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private val usage by lazy { UsageLog.of(this) }

    private val dictation: DictationController by lazy {
        DictationController(
            audio = MicRecorder(),
            transcriberFactory = {
                Secrets.get(this, Secrets.ELEVENLABS)?.let { ElevenLabsTranscriber(it, http, onAudio = usage::addAudio) }
            },
            cleanerFactory = {
                val prefs = Prefs.get(this)
                val key = Secrets.get(this, Secrets.ANTHROPIC)
                if (!prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP) || key.isNullOrBlank()) null
                else ClaudeCleaner(
                    key,
                    prefs.getString(Prefs.CLEANUP_MODEL, null)?.trim()?.ifBlank { null } ?: ClaudeCleanupRequest.DEFAULT_MODEL,
                    http,
                    onUsage = usage::addClaude,
                )
            },
            ui = object : DictationController.Ui {
                override fun onListening() {
                    heardWords = 0
                    restorePeriodIfFollowingUp()
                    if (liveTyping) liveTyper.begin()
                    bubbleView?.showListening()
                }
                override fun onLevel(level: Float) { bubbleView?.showLevel(level) }
                override fun onLiveText(text: String) {
                    // only a newly recognized word counts as speech for the silence countdown, never
                    // mere noise; after a key press the count restarts lower, so follow it down
                    val words = text.split(' ', '\n').count { it.isNotBlank() }
                    if (words > heardWords) bubbleView?.heardSpeech()
                    heardWords = words
                    if (liveTyping) liveTyper.live(text) else bubbleView?.showLiveText(text)
                }
                override fun onCleaning(raw: String) {
                    if (liveTyping) liveTyper.live(raw) // the draft holds the raw words while they're polished
                    bubbleView?.showCleaning(raw)
                }
                override fun onResult(text: String) {
                    delivered = true
                    val final = finishMessage(text)
                    if (liveTyping) liveTyper.result(final) else typeDictation(final)
                    rememberStripped(final)
                }
                override fun onIdle() {
                    if (liveTyping) liveTyper.end()
                    if (!delivered) takeBackRestoredPeriod()
                    bubbleView?.talkEnded()
                    if (bubbleView?.voice != BubbleView.Voice.MESSAGE) bubbleView?.hideVoice()
                }
                override fun onError(message: String) {
                    if (liveTyping) liveTyper.end() // keep what was typed so far
                    takeBackRestoredPeriod()
                    bubbleView?.talkEnded()
                    bubbleView?.showMessage(message)
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

    private val millennial get() = Prefs.get(this).getBoolean(Prefs.MILLENNIAL, Prefs.DEFAULT_MILLENNIAL)

    /** The finished text, minus its final period in millennial mode. */
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
    private var heardWords = 0

    /** This dictation types into the field as you speak (rather than previewing above the bubble). */
    private var liveTyping = false

    private val liveField = object : LiveField {
        override fun setComposing(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.setComposingText(text, 1) }
        override fun finishComposing() { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.finishComposingText() }
        override fun commit(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.commitText(text, 1) }
        override fun textBefore(n: Int): CharSequence? = currentInputConnection?.getTextBeforeCursor(n, 0)
        override fun deleteBefore(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
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
        bubbleView?.hasSelection = newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd
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

        /** Shows the target: the character after it highlighted (or before it, at the very end). */
        private fun mark() {
            if (markStart < 0 && keptStart < 0 && selStart != selEnd && selStart >= 0) { keptStart = selStart; keptEnd = selEnd }
            if (target == cursor) { clearMark(); restore(); return }
            val a: Int
            val b: Int
            if (target < length) { a = target; b = target + 1 } else if (target > 0) { a = target - 1; b = target } else { a = 0; b = 0 }
            markStart = a; markEnd = b
            setSel(a, b)
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

    private fun refreshCanPaste() { bubbleView?.canPaste = runCatching { clipHasText() }.getOrDefault(false) }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refreshCanPaste() }

    private fun userTookOver() {
        if (dictation.state != DictationController.State.LISTENING) return
        bubbleView?.talkEnded()
        dictation.stop(cancel = false)
    }

    /** A key typed mid-dictation: what's spoken so far stays as typed, and later words follow the key. */
    private fun keyDuringDictation() {
        if (!liveTyping || !dictation.dictating) return
        liveTyper.interrupt(newSegment = dictation.markBoundary(liveTyper.typedWords()))
    }

    override fun onCreateInputView(): View {
        val view = BubbleView(this, null).apply {
            listener = this@BubbleKeyboardService
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        bubbleView = view
        return view
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        stretchWindowToFullHeight(view)
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
            stopDictationKeepingText()
            strippedTail = null
        }
        fieldKey = key
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshCanPaste()
        bubbleView?.hasSelection = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        if (!restarting) {
            stopDictationKeepingText()
            bubbleView?.reloadSettings()
            bubbleView?.reset()
        }
        getSystemService(ClipboardManager::class.java)?.let { it.removePrimaryClipChangedListener(clipListener); it.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        // keep what's been typed, before the view's reset would throw out a dictation in progress
        stopDictationKeepingText()
        bubbleView?.reset()
        super.onFinishInputView(finishingInput)
    }

    /** The bubble floats over the app; there is never an extract (fullscreen) editor. */
    override fun onEvaluateFullscreenMode() = false

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = bubbleView ?: return
        if (!view.isShown) return
        view.getLocationInWindow(padLocation)
        // content and visible area end at the bottom: the app keeps its full height
        val bottom = padLocation[1] + view.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        view.getTouchableRegion(outInsets.touchableRegion)
        outInsets.touchableRegion.translate(padLocation[0], padLocation[1])
    }

    /**
     * By default the IME window wraps its content at the bottom of the screen. The bubble can sit
     * anywhere, so the window, its input area and our view all fill the screen; touches outside
     * the bubble still reach the app thanks to the touchable region above.
     */
    private fun stretchWindowToFullHeight(view: View) {
        window.window?.let { w ->
            // no gray contrast scrim behind 3-button navigation: BubbleView paints a themed strip
            // there instead, and the buttons get the icon color that reads on it
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !night
            val lp = w.attributes
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                w.attributes = lp
            }
        }
        // the input area's parent is a LinearLayout on most versions, a FrameLayout on some
        val inputArea = window.window?.findViewById<View>(android.R.id.inputArea)
        inputArea?.layoutParams?.let {
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
            when (it) {
                is LinearLayout.LayoutParams -> it.gravity = Gravity.BOTTOM
                is FrameLayout.LayoutParams -> it.gravity = Gravity.BOTTOM
            }
            inputArea.layoutParams = it
        }
        (view.layoutParams)?.let {
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.layoutParams = it
        }
    }

    private companion object {
        /** Cursor moves this soon after our own typing are ours, not the user's. */
        /** How long the joystick waits for the app to report a line move before giving up on it. */
        const val LINE_MOVE_TIMEOUT_MS = 300L
    }

    // ---------------------------------------------------------------- BubbleView.Listener

    override fun onKey(key: BubbleView.Key, repeat: Boolean) {
        ownEdit()
        keyDuringDictation()
        when (key) {
            BubbleView.Key.UP -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            BubbleView.Key.DOWN -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            // at either end of the text a d-pad arrow moves focus to another view, which closes the
            // keyboard mid-repeat; stop at the end instead
            BubbleView.Key.LEFT -> if (canMove(before = true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            BubbleView.Key.RIGHT -> if (canMove(before = false)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            BubbleView.Key.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            BubbleView.Key.SPACE -> currentInputConnection?.commitText(" ", 1)
            // runs the field's action (search, send, next...) when it has one, else a newline
            BubbleView.Key.ENTER -> sendKeyChar('\n')
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
    override fun onEdit(action: BubbleView.EditAction) {
        ownEdit()
        // an aimed-at marker is only a highlight: edits act on the real cursor or selection
        if (stick.active) stick.dropMark()

        keyDuringDictation()
        val ic = currentInputConnection ?: return
        when (action) {
            BubbleView.EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
            BubbleView.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            BubbleView.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            // a backspace with text selected deletes the selection, like any keyboard's
            BubbleView.EditAction.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onLetter(ch: Char) {
        ownEdit()
        keyDuringDictation()
        currentInputConnection?.commitText(ch.toString(), 1)
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            bubbleView?.showMessage(getString(R.string.voice_need_mic))
            return false
        }
        if (dictation.dictating) return false // still finishing the last one
        ownEdit()
        liveTyping = Prefs.get(this).getBoolean(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE) &&
            fieldTakesLiveText(currentInputEditorInfo)
        bubbleView?.voiceInField = liveTyping
        return dictation.start()
    }

    override fun onTalkEnd(cancel: Boolean) {
        if (cancel && liveTyping) liveTyper.cancel()
        if (cancel) takeBackRestoredPeriod()
        dictation.stop(cancel)
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
