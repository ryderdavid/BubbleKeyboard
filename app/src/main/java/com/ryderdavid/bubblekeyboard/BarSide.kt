package com.ryderdavid.bubblekeyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * "On wide screens": where the bar's buttons sit on a tablet or an unfolded phone. A small
 * picture of a wide bar shows the choice; spread out is how it's always been.
 */
@Composable
fun BarSideRow() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var side by remember { mutableStateOf(prefs.getString(Prefs.BAR_SIDE, Prefs.DEFAULT_BAR_SIDE) ?: Prefs.DEFAULT_BAR_SIDE) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.bar_side_title), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.bar_side_summary), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        val track = cs.surfaceContainerHighest
        val key = cs.secondaryContainer
        val voice = cs.primary
        // a wide bar, about tablet proportions, with its buttons where they'd be
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val pad = 5.dp.toPx()
            val inner = size.width - 2 * pad
            val spanW = if (side == Prefs.BAR_SIDE_FULL) inner else inner * 0.42f
            val x0 = pad + when (side) {
                Prefs.BAR_SIDE_LEFT -> 0f
                Prefs.BAR_SIDE_RIGHT -> inner - spanW
                Prefs.BAR_SIDE_CENTER -> (inner - spanW) / 2
                else -> 0f
            }
            val h = size.height - 2 * pad
            val gap = 3.dp.toPx()
            val dot = h
            // ⋮ · Cursor ABC Voice · ⌫
            val pw = (spanW - 2 * dot - 4 * gap) / 3
            val kr = CornerRadius(h / 2)
            var x = x0
            drawRoundRect(key, Offset(x, pad), Size(dot, h), kr); x += dot + gap
            drawRoundRect(key, Offset(x, pad), Size(pw, h), kr); x += pw + gap
            drawRoundRect(key, Offset(x, pad), Size(pw, h), kr); x += pw + gap
            drawRoundRect(voice, Offset(x, pad), Size(pw, h), kr); x += pw + gap
            drawRoundRect(key, Offset(x, pad), Size(dot, h), kr)
        }
        Spacer(Modifier.height(14.dp))
        val options = listOf(
            Prefs.BAR_SIDE_LEFT to R.string.bar_side_left,
            Prefs.BAR_SIDE_CENTER to R.string.bar_side_center,
            Prefs.BAR_SIDE_RIGHT to R.string.bar_side_right,
            Prefs.BAR_SIDE_FULL to R.string.bar_side_full,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = side == v,
                    onClick = { side = v; prefs.edit { putString(Prefs.BAR_SIDE, v) } },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
}
