package com.ryderdavid.bubblekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.ryderdavid.bubblekeyboard.voice.ClaudeCleanupRequest
import com.ryderdavid.bubblekeyboard.voice.Pricing
import com.ryderdavid.bubblekeyboard.voice.UsageLog
import com.ryderdavid.bubblekeyboard.voice.Secrets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The app's settings: a home page of categories, each opening its own short page. */
class SettingsActivity : ComponentActivity() {

    /** Whether the keyboard is turned on and chosen, and the microphone allowed; re-read whenever we come back. */
    data class Setup(val enabled: Boolean = false, val current: Boolean = false, val mic: Boolean = false, val gboard: Boolean = false, val gboardBubble: Boolean = false)

    private var setup by mutableStateOf(Setup())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        refreshSetup()
        val start = intent.getStringExtra(EXTRA_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        setContent { BubbleTheme { SettingsApp(setup, ::refreshSetup, start) } }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup() // the keyboard picker is a dialog, so onResume doesn't run after it
    }

    private fun refreshSetup() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val gboard = Prefs.gboardImeId(this) != null
        setup = Setup(enabled, current, mic, gboard, GboardBubbleService.isEnabled(this))
    }

    companion object {
        /** Opens on a page other than home (a [Page] name). */
        const val EXTRA_PAGE = "page"
    }
}

enum class Page { HOME, LOOK, VOICE, LANGUAGE, HELP, TRY }

@Composable
fun SettingsApp(setup: SettingsActivity.Setup, refresh: () -> Unit, start: Page = Page.HOME) {
    var page by rememberSaveable { mutableStateOf(start) }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.HOME
            (slideInHorizontally(tween(300)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(200)))
        },
        label = "page",
    ) { p ->
        val back = { page = Page.HOME }
        when (p) {
            Page.HOME -> HomePage(setup) { page = it }
            Page.LOOK -> LookPage(setup, back)
            Page.VOICE -> VoicePage(setup, refresh, back)
            Page.LANGUAGE -> LanguagePage(back)
            Page.HELP -> HelpPage(back)
            Page.TRY -> TryPage(back)
        }
    }
}

/* ---------------------------------------------------------------- prefs */

@Composable
private fun rememberPrefs() = LocalContext.current.let { remember { Prefs.get(it) } }

@Composable
private fun intPref(key: String, default: Int, range: IntRange): Pair<Int, (Int) -> Unit> {
    val prefs = rememberPrefs()
    var v by remember { mutableIntStateOf(prefs.getInt(key, default).coerceIn(range)) }
    return v to { n: Int -> v = n; prefs.edit { putInt(key, n) } }
}

@Composable
private fun boolPref(key: String, default: Boolean): Pair<Boolean, (Boolean) -> Unit> {
    val prefs = rememberPrefs()
    var v by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    return v to { b: Boolean -> v = b; prefs.edit { putBoolean(key, b) } }
}

/* ---------------------------------------------------------------- home */

@Composable
private fun HomePage(setup: SettingsActivity.Setup, open: (Page) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val voiceSummary = when {
        !setup.mic -> stringResource(R.string.home_voice_need_mic)
        !Secrets.has(context, Secrets.ELEVENLABS) -> stringResource(R.string.home_voice_need_key)
        else -> stringResource(R.string.home_voice_ready)
    }
    SettingsPage(stringResource(R.string.app_name), onBack = null) {
        item {
            if (!setup.enabled || !setup.current || !setup.gboard || (!setup.gboardBubble && android.os.Build.VERSION.SDK_INT >= 30)) SetupCard(setup) else HeroCard(onTry = { open(Page.TRY) })
            Spacer(Modifier.height(24.dp))
        }
        group(rows = listOf(
            { NavRow(stringResource(R.string.page_look), stringResource(R.string.page_look_summary), Icons.Rounded.Palette, cs.primaryContainer, cs.onPrimaryContainer) { open(Page.LOOK) } },
            { NavRow(stringResource(R.string.page_voice), voiceSummary, Icons.Rounded.Mic, cs.tertiaryContainer, cs.onTertiaryContainer) { open(Page.VOICE) } },
            { NavRow(stringResource(R.string.page_language), stringResource(R.string.page_language_summary), Icons.Rounded.TextFields, cs.primaryContainer, cs.onPrimaryContainer) { open(Page.LANGUAGE) } },
        ))
        group(rows = listOf(
            { NavRow(stringResource(R.string.page_help), stringResource(R.string.page_help_summary), Icons.Rounded.Lightbulb, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.HELP) } },
            { NavRow(stringResource(R.string.page_try), stringResource(R.string.page_try_summary), Icons.Rounded.Keyboard, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.TRY) } },
        ))
        item {
            Text(
                stringResource(R.string.version, BuildConfigCompat.versionName(context)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shown until the keyboard is turned on and chosen: two steps, each with its button. */
@Composable
private fun SetupCard(setup: SettingsActivity.Setup) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)
            SetupStep(1, stringResource(R.string.setup_enable), setup.enabled, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            SetupStep(2, stringResource(R.string.setup_switch), setup.current, stringResource(R.string.setup_switch_button), enabled = setup.enabled) {
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
            }
            SetupStep(3, stringResource(R.string.setup_gboard), setup.gboard, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) SetupStep(4, stringResource(R.string.setup_way_back), setup.gboardBubble, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, done: Boolean, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).then(Modifier.padding(0.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
            else Surface(shape = CircleShape, color = cs.primary, contentColor = cs.onPrimary, modifier = Modifier.size(26.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!done) Button(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

/** Once set up: the bar itself and a way to try it. */
@Composable
private fun HeroCard(onTry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = rowColor()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hero_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hero_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            val layout = rememberPrefs().getString(Prefs.BAR_LAYOUT, Prefs.DEFAULT_BAR_LAYOUT) ?: Prefs.DEFAULT_BAR_LAYOUT
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> DockView(ctx, null).apply { showAsPreview(layout) } },
                    update = { it.showAsPreview(layout) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilledTonalButton(onClick = onTry) { Text(stringResource(R.string.page_try)) }
        }
    }
}

/* ---------------------------------------------------------------- look */

@Composable
private fun LookPage(setup: SettingsActivity.Setup, onBack: () -> Unit) {
    val prefs = rememberPrefs()
    var barLayout by remember { mutableStateOf(prefs.getString(Prefs.BAR_LAYOUT, Prefs.DEFAULT_BAR_LAYOUT) ?: Prefs.DEFAULT_BAR_LAYOUT) }
    val setBarLayout = { v: String -> barLayout = v; prefs.edit { putString(Prefs.BAR_LAYOUT, v) } }
    var gboardReturn by remember { mutableStateOf(prefs.getString(Prefs.GBOARD_RETURN, Prefs.DEFAULT_GBOARD_RETURN) ?: Prefs.DEFAULT_GBOARD_RETURN) }
    val setGboardReturn = { v: String -> gboardReturn = v; prefs.edit { putString(Prefs.GBOARD_RETURN, v) } }
    val context = LocalContext.current
    SettingsPage(stringResource(R.string.page_look), onBack) {
        group(R.string.bar_layout, listOf(
            {
                BarLayoutOption(stringResource(R.string.bar_layout_pills), stringResource(R.string.bar_layout_pills_summary),
                    Prefs.BAR_LAYOUT_PILLS, barLayout != Prefs.BAR_LAYOUT_EQUAL) { setBarLayout(Prefs.BAR_LAYOUT_PILLS) }
            },
            {
                BarLayoutOption(stringResource(R.string.bar_layout_equal), stringResource(R.string.bar_layout_equal_summary),
                    Prefs.BAR_LAYOUT_EQUAL, barLayout == Prefs.BAR_LAYOUT_EQUAL) { setBarLayout(Prefs.BAR_LAYOUT_EQUAL) }
            },
        ))
        // typing happens in Gboard: whether it's ready, and the button that brings Voicebar back
        group(R.string.gboard_group, listOf(
            {
                SettingRow(
                    stringResource(R.string.gboard_row),
                    stringResource(if (setup.gboard) R.string.gboard_ready else R.string.gboard_missing),
                    onClick = { context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            },
            {
                val old = android.os.Build.VERSION.SDK_INT < 30
                SettingRow(
                    stringResource(R.string.gboard_bubble_row),
                    stringResource(when { old -> R.string.gboard_bubble_old_android; setup.gboardBubble -> R.string.gboard_bubble_on; else -> R.string.gboard_bubble_off }),
                    enabled = !old,
                    onClick = { context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            },
        ))
        group(R.string.gboard_return_group, listOf(
            { StyleRow(stringResource(R.string.gboard_return_bar), stringResource(R.string.gboard_return_bar_summary), gboardReturn == Prefs.GBOARD_RETURN_BAR) { setGboardReturn(Prefs.GBOARD_RETURN_BAR) } },
            { StyleRow(stringResource(R.string.gboard_return_mic_key), stringResource(R.string.gboard_return_mic_key_summary), gboardReturn == Prefs.GBOARD_RETURN_MIC_KEY) { setGboardReturn(Prefs.GBOARD_RETURN_MIC_KEY) } },
            { StyleRow(stringResource(R.string.gboard_return_bubble), stringResource(R.string.gboard_return_bubble_summary), gboardReturn == Prefs.GBOARD_RETURN_BUBBLE) { setGboardReturn(Prefs.GBOARD_RETURN_BUBBLE) } },
        ))
    }
}

@Composable
private fun BarLayoutOption(title: String, summary: String, layout: String, selected: Boolean, onSelect: () -> Unit) {
    Column {
        StyleRow(title, summary, selected, onSelect)
        // the real bar, drawn by the keyboard's own view, at rest in this layout
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> DockView(ctx, null).apply { showAsPreview(layout) } },
                update = { it.showAsPreview(layout) },
                modifier = Modifier.fillMaxWidth(), // the view measures itself to the bar's height
            )
        }
    }
}

@Composable
private fun StyleRow(title: String, summary: String, selected: Boolean, onSelect: () -> Unit) =
    SettingRow(
        title, summary,
        leading = { androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect) },
        onClick = onSelect,
        role = androidx.compose.ui.semantics.Role.RadioButton,
    )

/** The folded bubble and the open ring side by side, at their real sizes. */
/* ---------------------------------------------------------------- voice */

private val usageWindows = listOf(R.string.usage_day to 24, R.string.usage_week to 24 * 7, R.string.usage_month to 24 * 30)

private fun fmtRate(v: Double): String = if (v >= 1 && v == Math.floor(v)) "%.0f".format(java.util.Locale.US, v) else "%.2f".format(java.util.Locale.US, v)

@Composable
private fun usageSummary(t: UsageLog.Totals): String {
    if (t.audioMs == 0L && t.inputTokens + t.outputTokens == 0L) return stringResource(R.string.usage_none)
    val parts = mutableListOf(
        stringResource(R.string.usage_scribe, UsageLog.formatUsd(t.scribeUsd), UsageLog.formatAudio(t.audioMs)),
        stringResource(R.string.usage_claude, UsageLog.formatUsd(t.claudeUsd), UsageLog.formatTokens(t.inputTokens + t.outputTokens)),
    )
    if (t.unpricedTokens > 0) parts += stringResource(R.string.usage_unpriced, UsageLog.formatTokens(t.unpricedTokens))
    return parts.joinToString(" · ")
}

@Composable
private fun VoicePage(setup: SettingsActivity.Setup, refresh: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val prefs = rememberPrefs()
    val cs = MaterialTheme.colorScheme
    val (live, setLive) = boolPref(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE)
    val (autoListen, setAutoListen) = boolPref(Prefs.AUTO_LISTEN, Prefs.DEFAULT_AUTO_LISTEN)
    val (cleanup, setCleanup) = boolPref(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)
    val (silence, setSilence) = intPref(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT, Prefs.SILENCE_TIMEOUT_RANGE)
    var keysVersion by remember { mutableIntStateOf(0) } // re-reads the keys' saved state after a dialog
    var editing by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(prefs.getString(Prefs.CLEANUP_MODEL, null) ?: ClaudeCleanupRequest.DEFAULT_MODEL) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        // after a permanent "don't allow" the dialog no longer shows: send the user to the app's page
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    @Suppress("UNUSED_EXPRESSION") keysVersion
    var usageVersion by remember { mutableIntStateOf(0) }
    val usage = remember(usageVersion) { UsageLog.of(context) }
    val hasEleven = Secrets.has(context, Secrets.ELEVENLABS)
    val hasClaude = Secrets.has(context, Secrets.ANTHROPIC)

    SettingsPage(stringResource(R.string.page_voice), onBack) {
        group(rows = listOf({
            SettingRow(
                stringResource(R.string.voice_mic_title),
                stringResource(if (setup.mic) R.string.voice_mic_done else R.string.voice_mic_needed),
                leading = { RowIcon(Icons.Rounded.Mic, cs.tertiaryContainer, cs.onTertiaryContainer) },
                trailing = {
                    if (setup.mic) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                    else FilledTonalButton(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.voice_mic_allow)) }
                },
            )
        }))
        group(R.string.group_speech, listOf(
            {
                SettingRow(
                    stringResource(R.string.voice_elevenlabs_key),
                    stringResource(if (hasEleven) R.string.voice_key_saved_summary else R.string.voice_elevenlabs_summary),
                    leading = { RowIcon(Icons.Rounded.Key, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = { KeyBadge(hasEleven) },
                    onClick = { editing = Secrets.ELEVENLABS },
                )
            },
            { SwitchRow(stringResource(R.string.auto_listen), stringResource(R.string.auto_listen_summary), autoListen, onChange = setAutoListen) },
            { SwitchRow(stringResource(R.string.voice_live), stringResource(R.string.voice_live_summary), live, onChange = setLive) },
            { SliderRow(stringResource(R.string.silence_timeout), stringResource(R.string.silence_timeout_summary), silence, Prefs.SILENCE_TIMEOUT_RANGE, { "$it s" }, onCommit = setSilence) },
        ))
        group(R.string.group_cleanup, listOf(
            { SwitchRow(stringResource(R.string.voice_cleanup), stringResource(R.string.voice_cleanup_summary), cleanup, onChange = setCleanup) },
            {
                SettingRow(
                    stringResource(R.string.voice_anthropic_key),
                    stringResource(if (hasClaude) R.string.voice_key_saved_summary else R.string.voice_anthropic_summary),
                    enabled = cleanup,
                    leading = { RowIcon(Icons.Rounded.Key, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = { KeyBadge(hasClaude) },
                    onClick = { editing = Secrets.ANTHROPIC },
                )
            },
            {
                SettingRow(
                    stringResource(R.string.voice_model), model, enabled = cleanup,
                    leading = { RowIcon(Icons.Rounded.AutoAwesome, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    onClick = { editingModel = true },
                )
            },
        ))
        group(R.string.group_usage, usageWindows.map<Pair<Int, Int>, @Composable () -> Unit> { (label, hours) ->
            {
                val t = usage.last(hours)
                SettingRow(
                    stringResource(label),
                    usageSummary(t),
                    leading = { RowIcon(Icons.Rounded.Payments, cs.secondaryContainer, cs.onSecondaryContainer) },
                    trailing = { Text(UsageLog.formatUsd(t.totalUsd), style = MaterialTheme.typography.titleMedium, color = cs.onSurface) },
                )
            }
        } + listOf<@Composable () -> Unit>({
            SettingRow(
                stringResource(R.string.usage_clear),
                null,
                leading = { RowIcon(Icons.Rounded.DeleteSweep, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                onClick = { usage.clear(); usageVersion++ },
            )
        }))
        item {
            Footnote(stringResource(
                R.string.usage_footnote,
                Pricing.SCRIBE_REALTIME_PER_HOUR,
                model,
                Pricing.claude(model)?.let { "$" + fmtRate(it.input) + " in / $" + fmtRate(it.output) + " out per million tokens" }
                    ?: stringResource(R.string.usage_unknown_price),
            ))
        }
        item { Footnote(stringResource(R.string.voice_summary)) }
    }

    editing?.let { name ->
        KeyDialog(
            title = stringResource(if (name == Secrets.ELEVENLABS) R.string.voice_elevenlabs_key else R.string.voice_anthropic_key),
            help = stringResource(if (name == Secrets.ELEVENLABS) R.string.voice_elevenlabs_help else R.string.voice_anthropic_help),
            has = Secrets.has(context, name),
            onSave = { v ->
                if (!Secrets.put(context, name, v)) Toast.makeText(context, R.string.voice_key_failed, Toast.LENGTH_LONG).show()
                editing = null; keysVersion++
            },
            onRemove = { Secrets.put(context, name, null); editing = null; keysVersion++ },
            onDismiss = { editing = null },
        )
    }
    if (editingModel) {
        ModelDialog(
            current = model,
            onSave = { v ->
                prefs.edit { putString(Prefs.CLEANUP_MODEL, v.trim().ifBlank { null }) }
                model = v.trim().ifBlank { ClaudeCleanupRequest.DEFAULT_MODEL }
                editingModel = false
            },
            onDismiss = { editingModel = false },
        )
    }
}

@Composable
private fun KeyBadge(saved: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = if (saved) cs.secondaryContainer else cs.errorContainer) {
        Text(
            stringResource(if (saved) R.string.voice_key_saved else R.string.voice_key_missing),
            style = MaterialTheme.typography.labelMedium,
            color = if (saved) cs.onSecondaryContainer else cs.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Paste a key: it's stored encrypted and never shown again; a saved one can be replaced or removed. */
@Composable
private fun KeyDialog(title: String, help: String, has: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(help, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(if (has) R.string.voice_key_replace_hint else R.string.voice_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.voice_key_save)) }
        },
        dismissButton = {
            Row {
                if (has) TextButton(onClick = onRemove) { Text(stringResource(R.string.voice_key_remove), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ModelDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        title = { Text(stringResource(R.string.voice_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.voice_model_summary), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.voice_key_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.voice_model_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/* ---------------------------------------------------------------- language */

@Composable
private fun LanguagePage(onBack: () -> Unit) {
    val (millennial, setMillennial) = boolPref(Prefs.MILLENNIAL, Prefs.DEFAULT_MILLENNIAL)
    SettingsPage(stringResource(R.string.page_language), onBack) {
        group(rows = listOf(
            { SwitchRow(stringResource(R.string.millennial_mode), stringResource(R.string.millennial_mode_summary), millennial, onChange = setMillennial) },
        ))
    }
}

/* ---------------------------------------------------------------- help */

@Composable
private fun HelpPage(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val entries = listOf(
        Triple(Icons.Rounded.GraphicEq, R.string.help_talk_title, R.string.help_talk),
        Triple(Icons.Rounded.Keyboard, R.string.help_type_title, R.string.help_type),
        Triple(Icons.Rounded.TouchApp, R.string.help_cursor_title, R.string.help_cursor),
        Triple(Icons.AutoMirrored.Rounded.Backspace, R.string.help_edit_title, R.string.help_edit),
        Triple(Icons.Rounded.Apps, R.string.help_menu_title, R.string.help_menu),
    )
    SettingsPage(stringResource(R.string.page_help), onBack) {
        group(rows = entries.map { (icon, title, body) ->
            @Composable {
                SettingRow(stringResource(title), stringResource(body), leading = { RowIcon(icon, cs.secondaryContainer, cs.onSecondaryContainer) })
            }
        })
    }
}

/* ---------------------------------------------------------------- try */

@Composable
private fun TryPage(onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SettingsPage(stringResource(R.string.page_try), onBack) {
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.try_hint)) },
                minLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.try_tip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
        }
    }
}

/** The app's version name, for the footer. */
private object BuildConfigCompat {
    fun versionName(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
}
