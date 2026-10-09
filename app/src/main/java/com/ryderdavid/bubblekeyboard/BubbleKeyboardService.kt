package com.ryderdavid.bubblekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
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
import com.ryderdavid.bubblekeyboard.voice.Secrets
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

    private val dictation: DictationController by lazy {
        DictationController(
            audio = MicRecorder(),
            transcriberFactory = {
                Secrets.get(this, Secrets.ELEVENLABS)?.let { ElevenLabsTranscriber(it, http) }
            },
            cleanerFactory = {
                val prefs = Prefs.get(this)
                val key = Secrets.get(this, Secrets.ANTHROPIC)
                if (!prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP) || key.isNullOrBlank()) null
                else ClaudeCleaner(
                    key,
                    prefs.getString(Prefs.CLEANUP_MODEL, null)?.trim()?.ifBlank { null } ?: ClaudeCleanupRequest.DEFAULT_MODEL,
                    http,
                )
            },
            ui = object : DictationController.Ui {
                override fun onListening() {
                    if (liveTyping) liveTyper.begin()
                    bubbleView?.showListening()
                }
                override fun onLevel(level: Float) { bubbleView?.showLevel(level) }
                override fun onLiveText(text: String) {
                    if (liveTyping) liveTyper.live(text) else bubbleView?.showLiveText(text)
                }
                override fun onCleaning(raw: String) {
                    if (liveTyping) liveTyper.live(raw) // the draft holds the raw words while they're polished
                    bubbleView?.showCleaning(raw)
                }
                override fun onResult(text: String) {
                    if (liveTyping) liveTyper.result(text) else typeDictation(text)
                }
                override fun onIdle() {
                    if (liveTyping) liveTyper.end()
                    bubbleView?.talkEnded()
                    if (bubbleView?.voice != BubbleView.Voice.MESSAGE) bubbleView?.hideVoice()
                }
                override fun onError(message: String) {
                    if (liveTyping) liveTyper.end() // keep what was typed so far
                    bubbleView?.talkEnded()
                    bubbleView?.showMessage(message)
                }
            },
        )
    }

    /** This dictation types into the field as you speak (rather than previewing above the bubble). */
    private var liveTyping = false

    private val liveField = object : LiveField {
        override fun setComposing(text: String) { currentInputConnection?.setComposingText(text, 1) }
        override fun finishComposing() { currentInputConnection?.finishComposingText() }
        override fun commit(text: String) { currentInputConnection?.commitText(text, 1) }
        override fun textBefore(n: Int): CharSequence? = currentInputConnection?.getTextBeforeCursor(n, 0)
        override fun deleteBefore(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
        override fun beginBatch() { currentInputConnection?.beginBatchEdit() }
        override fun endBatch() { currentInputConnection?.endBatchEdit() }
    }

    private val liveTyper: LiveTyper by lazy {
        // the app ended our draft mid-speech: what's typed stays, the next words start a new draft
        LiveTyper({ if (currentInputConnection != null) liveField else null }, onLost = { typed -> dictation.markBoundary(typed) })
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
        if (liveTyping) liveTyper.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
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
        if (key != fieldKey) stopDictationKeepingText()
        fieldKey = key
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            stopDictationKeepingText()
            bubbleView?.reloadSettings()
            bubbleView?.reset()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
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

    // ---------------------------------------------------------------- BubbleView.Listener

    override fun onKey(key: BubbleView.Key, repeat: Boolean) {
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

    override fun onLetter(ch: Char) {
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
        liveTyping = Prefs.get(this).getBoolean(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE) &&
            fieldTakesLiveText(currentInputEditorInfo)
        bubbleView?.voiceInField = liveTyping
        return dictation.start()
    }

    override fun onTalkEnd(cancel: Boolean) {
        if (cancel && liveTyping) liveTyper.cancel()
        dictation.stop(cancel)
    }

    /** Types the dictated text, with a space before it when it would otherwise run into a word. */
    private fun typeDictation(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val sep = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        ic.commitText(sep + text, 1)
    }
}
