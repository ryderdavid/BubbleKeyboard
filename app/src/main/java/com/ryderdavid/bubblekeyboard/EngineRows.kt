package com.ryderdavid.bubblekeyboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.ryderdavid.bubblekeyboard.voice.Parakeet

/** Parakeet's download as Compose state, following the shared downloader. */
@Composable
fun rememberParakeet(): Parakeet.State {
    var s by remember { mutableStateOf(Parakeet.state) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val stop = Parakeet.observe { s = it }
        if (s == Parakeet.State.Idle && Parakeet.ready(context)) s = Parakeet.State.Ready
        onDispose { stop() }
    }
    return s
}

/** "Where speech becomes text": ElevenLabs in the cloud, or Parakeet on the phone (downloaded once). */
@Composable
fun EngineRows(): List<@Composable () -> Unit> {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var engine by remember { mutableStateOf(prefs.getString(Prefs.SPEECH_ENGINE, Prefs.ENGINE_ELEVENLABS) ?: Prefs.ENGINE_ELEVENLABS) }
    val dl = rememberParakeet()
    val cs = MaterialTheme.colorScheme
    fun choose(e: String) { engine = e; prefs.edit { putString(Prefs.SPEECH_ENGINE, e) } }
    val mb = Parakeet.TOTAL / 1_000_000
    return listOf(
        {
            SettingRow(
                stringResource(R.string.engine_eleven), stringResource(R.string.engine_eleven_summary),
                leading = { RowIcon(Icons.Rounded.Cloud, cs.tertiaryContainer, cs.onTertiaryContainer) },
                trailing = { RadioButton(selected = engine == Prefs.ENGINE_ELEVENLABS, onClick = null) },
                onClick = { choose(Prefs.ENGINE_ELEVENLABS) }, role = Role.RadioButton,
            )
        },
        {
            Column {
                val ready = dl == Parakeet.State.Ready
                SettingRow(
                    stringResource(R.string.engine_parakeet),
                    when (dl) {
                        Parakeet.State.Ready -> stringResource(R.string.engine_parakeet_ready)
                        is Parakeet.State.Downloading -> stringResource(R.string.engine_parakeet_downloading, dl.done / 1_000_000, dl.total / 1_000_000)
                        Parakeet.State.Checking -> stringResource(R.string.engine_parakeet_checking)
                        is Parakeet.State.Failed -> dl.why
                        Parakeet.State.Idle -> stringResource(R.string.engine_parakeet_summary, mb)
                    },
                    leading = { RowIcon(Icons.Rounded.Smartphone, cs.primaryContainer, cs.onPrimaryContainer) },
                    trailing = {
                        when (dl) {
                            Parakeet.State.Ready -> RadioButton(selected = engine == Prefs.ENGINE_PARAKEET, onClick = null)
                            is Parakeet.State.Downloading -> TextButton(onClick = { Parakeet.cancel() }) { Text(stringResource(R.string.engine_pause)) }
                            Parakeet.State.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            else -> FilledTonalButton(onClick = { choose(Prefs.ENGINE_PARAKEET); Parakeet.download(context) }) {
                                Text(stringResource(if (dl is Parakeet.State.Failed) R.string.engine_retry else R.string.engine_download))
                            }
                        }
                    },
                    onClick = if (ready) ({ choose(Prefs.ENGINE_PARAKEET) }) else null,
                    role = if (ready) Role.RadioButton else null,
                )
                if (dl is Parakeet.State.Downloading) {
                    LinearProgressIndicator(
                        progress = { (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (ready) TextButton(
                    onClick = { Parakeet.remove(context); choose(Prefs.ENGINE_ELEVENLABS) },
                    modifier = Modifier.padding(start = 60.dp, bottom = 6.dp),
                ) { Text(stringResource(R.string.engine_remove, mb)) }
            }
        },
    )
}

