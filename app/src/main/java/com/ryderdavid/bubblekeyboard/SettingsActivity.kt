package com.ryderdavid.bubblekeyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.edit
import com.ryderdavid.bubblekeyboard.voice.ClaudeCleanupRequest
import com.ryderdavid.bubblekeyboard.voice.Secrets

/** Setup, appearance and voice settings. Plain platform widgets, so the app stays tiny. */
class SettingsActivity : Activity() {

    private lateinit var enableButton: Button
    private lateinit var switchButton: Button
    private lateinit var micButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        column.addView(text(getString(R.string.app_name), 28f, bold = true))

        column.addView(header(getString(R.string.setup_header)))
        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        switchButton = Button(this).apply {
            setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }
        column.addView(enableButton, MATCH_PARENT, WRAP_CONTENT)
        column.addView(switchButton, MATCH_PARENT, WRAP_CONTENT)

        column.addView(header(getString(R.string.appearance_header)))
        column.addView(slider(R.string.bubble_size, Prefs.BUBBLE_SIZE, Prefs.DEFAULT_BUBBLE_SIZE, Prefs.BUBBLE_SIZE_RANGE))
        column.addView(slider(R.string.ring_size, Prefs.RING_SIZE, Prefs.DEFAULT_RING_SIZE, Prefs.RING_SIZE_RANGE))
        column.addView(slider(R.string.bubble_opacity, Prefs.BUBBLE_OPACITY, Prefs.DEFAULT_BUBBLE_OPACITY, Prefs.BUBBLE_OPACITY_RANGE, R.string.percent))
        column.addView(text(getString(R.string.bubble_opacity_summary), 14f, secondary = true))
        column.addView(slider(R.string.swell, Prefs.SWELL, Prefs.DEFAULT_SWELL, Prefs.SWELL_RANGE, R.string.percent))
        column.addView(text(getString(R.string.swell_summary), 14f, secondary = true))
        column.addView(slider(R.string.ring_timeout, Prefs.RING_TIMEOUT, Prefs.DEFAULT_RING_TIMEOUT, Prefs.RING_TIMEOUT_RANGE, R.string.seconds))
        column.addView(text(getString(R.string.ring_timeout_summary), 14f, secondary = true))
        column.addView(Switch(this).apply {
            text = getString(R.string.rotate_bloom)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.ROTATE_BLOOM, Prefs.DEFAULT_ROTATE_BLOOM)
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.ROTATE_BLOOM, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.rotate_bloom_summary), 14f, secondary = true))

        column.addView(header(getString(R.string.voice_header)))
        column.addView(text(getString(R.string.voice_summary), 14f, secondary = true))
        micButton = Button(this).apply {
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC) }
        }
        column.addView(micButton, MATCH_PARENT, WRAP_CONTENT)
        column.addView(secretField(R.string.voice_elevenlabs_key, Secrets.ELEVENLABS))
        column.addView(Switch(this).apply {
            text = getString(R.string.voice_hold_to_talk)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.HOLD_TO_TALK, Prefs.DEFAULT_HOLD_TO_TALK)
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.HOLD_TO_TALK, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.voice_hold_to_talk_summary), 14f, secondary = true))
        column.addView(Switch(this).apply {
            text = getString(R.string.voice_tap_to_talk)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.TAP_TO_TALK, Prefs.DEFAULT_TAP_TO_TALK)
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.TAP_TO_TALK, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.voice_tap_to_talk_summary), 14f, secondary = true))
        column.addView(slider(R.string.silence_timeout, Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT, Prefs.SILENCE_TIMEOUT_RANGE, R.string.seconds))
        column.addView(text(getString(R.string.silence_timeout_summary), 14f, secondary = true))
        column.addView(Switch(this).apply {
            text = getString(R.string.voice_live)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE)
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.VOICE_LIVE, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.voice_live_summary), 14f, secondary = true))
        column.addView(text(getString(R.string.voice_elevenlabs_summary), 14f, secondary = true))
        column.addView(Switch(this).apply {
            text = getString(R.string.voice_cleanup)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.CLEANUP, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.voice_cleanup_summary), 14f, secondary = true))
        column.addView(secretField(R.string.voice_anthropic_key, Secrets.ANTHROPIC))
        column.addView(text(getString(R.string.voice_model), 16f).apply { setPadding(0, dp(12), 0, 0) })
        column.addView(EditText(this).apply {
            setText(prefs.getString(Prefs.CLEANUP_MODEL, null) ?: ClaudeCleanupRequest.DEFAULT_MODEL)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setOnFocusChangeListener { _, focused ->
                if (!focused) prefs.edit { putString(Prefs.CLEANUP_MODEL, text.toString().trim().ifBlank { null }) }
            }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.voice_model_summary), 14f, secondary = true))

        column.addView(header(getString(R.string.language_header)))
        column.addView(Switch(this).apply {
            text = getString(R.string.millennial_mode)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.MILLENNIAL, Prefs.DEFAULT_MILLENNIAL)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.MILLENNIAL, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.millennial_mode_summary), 14f, secondary = true))

        column.addView(header(getString(R.string.try_header)))
        column.addView(EditText(this).apply {
            hint = getString(R.string.try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.how_to), 14f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })

        val scroll = ScrollView(this).apply {
            addView(column, MATCH_PARENT, WRAP_CONTENT)
            // targetSdk 35+ draws edge to edge: keep the content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                val bars = if (android.os.Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                        .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
                else intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                v.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
        }
        setContentView(scroll)
        // edge to edge without the gray scrim behind 3-button navigation
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    override fun onResume() {
        super.onResume()
        updateSetupState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSetupState() // the keyboard picker is a dialog, so onResume doesn't run after it
    }

    private fun updateSetupState() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        enableButton.text = getString(if (enabled) R.string.setup_enable_done else R.string.setup_enable)
        enableButton.isEnabled = !enabled
        switchButton.text = getString(if (current) R.string.setup_switch_done else R.string.setup_switch)
        switchButton.isEnabled = enabled && !current
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micButton.text = getString(if (mic) R.string.voice_mic_done else R.string.voice_mic)
        micButton.isEnabled = !mic
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        updateSetupState()
        // after a permanent "don't allow" the dialog no longer shows: send the user to the app's page
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus() // saves the model field
    }

    /**
     * An API key field. The saved key is never shown again: the field reads "Saved" and a new value
     * replaces it; clearing an empty field with Remove deletes it.
     */
    private fun secretField(titleRes: Int, name: String): View {
        val status = TextView(this).apply { textSize = 14f; alpha = 0.7f }
        val title = TextView(this).apply { text = getString(titleRes); textSize = 16f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(status)
        }
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val save = Button(this).apply { text = getString(R.string.voice_key_save) }
        val remove = Button(this).apply { text = getString(R.string.voice_key_remove) }
        fun refresh() {
            val has = Secrets.has(this@SettingsActivity, name)
            status.text = getString(if (has) R.string.voice_key_saved else R.string.voice_key_missing)
            field.hint = getString(if (has) R.string.voice_key_replace_hint else R.string.voice_key_hint)
            remove.isEnabled = has
        }
        save.setOnClickListener {
            val v = field.text.toString().trim()
            if (v.isNotEmpty() && !Secrets.put(this, name, v)) {
                android.widget.Toast.makeText(this, R.string.voice_key_failed, android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            field.setText("")
            refresh()
        }
        remove.setOnClickListener {
            Secrets.put(this, name, null)
            refresh()
        }
        refresh()
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(remove, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(row, MATCH_PARENT, WRAP_CONTENT)
            addView(field, MATCH_PARENT, WRAP_CONTENT)
            addView(buttons, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun slider(titleRes: Int, key: String, default: Int, range: IntRange, formatRes: Int = R.string.size_dp): View {
        val prefs = Prefs.get(this)
        val value = TextView(this).apply { textSize = 14f; alpha = 0.7f }
        val title = TextView(this).apply { text = getString(titleRes); textSize = 16f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(value)
        }
        val start = prefs.getInt(key, default).coerceIn(range)
        value.text = getString(formatRes, start)
        val bar = SeekBar(this).apply {
            max = range.last - range.first
            progress = start - range.first
            contentDescription = getString(titleRes)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = getString(formatRes, range.first + p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    prefs.edit { putInt(key, range.first + s.progress) }
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(row, MATCH_PARENT, WRAP_CONTENT)
            addView(bar, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun header(s: String) = text(s, 14f, bold = true).apply {
        setPadding(0, dp(24), 0, dp(8))
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) setTextColor(tv.data)
    }

    private fun text(s: String, sizeSp: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (secondary) alpha = 0.7f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_MIC = 1
    }
}
