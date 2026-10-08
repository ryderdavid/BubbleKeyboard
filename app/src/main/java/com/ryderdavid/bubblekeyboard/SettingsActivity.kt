package com.ryderdavid.bubblekeyboard

import android.app.Activity
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

/** Setup and the three settings. Plain platform widgets, so the app stays tiny. */
class SettingsActivity : Activity() {

    private lateinit var enableButton: Button
    private lateinit var switchButton: Button

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
        column.addView(slider(R.string.swell, Prefs.SWELL, Prefs.DEFAULT_SWELL, Prefs.SWELL_RANGE, R.string.percent))
        column.addView(text(getString(R.string.swell_summary), 14f, secondary = true))
        column.addView(Switch(this).apply {
            text = getString(R.string.rotate_bloom)
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.ROTATE_BLOOM, Prefs.DEFAULT_ROTATE_BLOOM)
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(Prefs.ROTATE_BLOOM, checked) } }
        }, MATCH_PARENT, WRAP_CONTENT)
        column.addView(text(getString(R.string.rotate_bloom_summary), 14f, secondary = true))

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
}
