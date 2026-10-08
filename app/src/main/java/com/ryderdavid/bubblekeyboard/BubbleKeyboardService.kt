package com.ryderdavid.bubblekeyboard

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The input method. Its whole UI is a [BubbleView] that spans the IME window; only the bubble,
 * ring and bloom take touches, and the app behind is never resized.
 */
class BubbleKeyboardService : InputMethodService(), BubbleView.Listener {

    private var bubbleView: BubbleView? = null
    private val padLocation = IntArray(2)

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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            bubbleView?.reloadSettings()
            bubbleView?.reset()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
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
        when (key) {
            BubbleView.Key.UP -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            BubbleView.Key.DOWN -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            BubbleView.Key.LEFT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            BubbleView.Key.RIGHT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            BubbleView.Key.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            BubbleView.Key.SPACE -> currentInputConnection?.commitText(" ", 1)
            // runs the field's action (search, send, next...) when it has one, else a newline
            BubbleView.Key.ENTER -> sendKeyChar('\n')
        }
    }

    override fun onLetter(ch: Char) {
        currentInputConnection?.commitText(ch.toString(), 1)
    }

    override fun onLongPressCenter() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showInputMethodPicker()
    }

    override fun shouldCapitalize(): Boolean {
        val ic = currentInputConnection ?: return false
        val inputType = currentInputEditorInfo?.inputType ?: return false
        return ic.getCursorCapsMode(inputType) != 0
    }
}
