package com.ryderdavid.bubblekeyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.ryderdavid.bubblekeyboard.voice.UsageLog
import java.time.LocalDate
import java.time.format.TextStyle as DateStyle
import java.util.Locale

/** One day of usage: what was streamed to ElevenLabs and sent to Claude, and what it cost. */
data class DayUsage(
    val date: LocalDate,
    val audioMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val scribeUsd: Double,
    val claudeUsd: Double,
) { val totalUsd get() = scribeUsd + claudeUsd }

/** Month to date and where the month is heading at the pace of the last [paceDays] days. */
data class MonthOutlook(val toDate: Double, val projected: Double, val daysLeft: Int, val paceDays: Int) {
    companion object {
        fun of(days: List<DayUsage>, today: LocalDate, paceDays: Int = 7): MonthOutlook {
            val month = days.filter { it.date.year == today.year && it.date.month == today.month && !it.date.isAfter(today) }
            val toDate = month.sumOf { it.totalUsd }
            val recent = days.filter { !it.date.isAfter(today) && it.date.isAfter(today.minusDays(paceDays.toLong())) }
            val perDay = recent.sumOf { it.totalUsd } / paceDays
            val left = today.lengthOfMonth() - today.dayOfMonth
            return MonthOutlook(toDate, toDate + perDay * left, left, paceDays)
        }
    }
}

/** The two series' colors (validated for color-blind separation and contrast in both themes). */
private object Series {
    @Composable fun eleven() = if (isSystemInDarkTheme()) Color(0xFF3987E5) else Color(0xFF2A78D6)
    @Composable fun claude() = if (isSystemInDarkTheme()) Color(0xFFD95926) else Color(0xFFEB6834)
}

interface UsageActions {
    fun range(days: Int)
    fun exportCsv()
    fun exportJson()
}

/**
 * Usage and cost: month to date and the month's projection up top, then daily lines (cost per
 * day for ElevenLabs and Claude, the month adding up, and what each one was used for), then
 * export, so the numbers can be kept elsewhere.
 */
@Composable
fun UsageAnalyticsScreen(
    days: List<DayUsage>,
    today: LocalDate,
    rangeDays: Int,
    /** A day picked on the chart (its values shown in a callout), or null. */
    picked: LocalDate?,
    actions: UsageActions,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val outlook = MonthOutlook.of(days, today)
    val shown = days.filter { !it.date.isAfter(today) && it.date.isAfter(today.minusDays(rangeDays.toLong())) }
    val month = today.month.getDisplayName(DateStyle.FULL, Locale.getDefault())
    val inMonth = days.filter { it.date.month == today.month && it.date.year == today.year && !it.date.isAfter(today) }
    val eleven = Series.eleven()
    val claude = Series.claude()
    SettingsPage("Usage & cost", onBack) {
        item {
            Card {
                Text("$month so far", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(UsageLog.formatUsd(outlook.toDate), style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "On pace for ${UsageLog.formatUsd(outlook.projected)} by month’s end · at your last ${outlook.paceDays} days’ rate, ${outlook.daysLeft} days to go",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SplitTile(eleven, "ElevenLabs", UsageLog.formatUsd(inMonth.sumOf { it.scribeUsd }), UsageLog.formatAudio(inMonth.sumOf { it.audioMs }) + " of audio", Modifier.weight(1f))
                    SplitTile(claude, "Claude", UsageLog.formatUsd(inMonth.sumOf { it.claudeUsd }), UsageLog.formatTokens(inMonth.sumOf { it.inputTokens + it.outputTokens }) + " tokens", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Card {
                CardTitle("This month", "Spent so far, and where it’s heading")
                MonthChart(inMonth, today, outlook)
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            val options = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (n, label) ->
                    SegmentedButton(selected = rangeDays == n, onClick = { actions.range(n) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Card {
                CardTitle("Cost per day", null)
                Legend(listOf(eleven to "ElevenLabs", claude to "Claude"))
                Spacer(Modifier.height(8.dp))
                LineChart(
                    shown.map { it.date },
                    listOf(eleven to shown.map { it.scribeUsd }, claude to shown.map { it.claudeUsd }),
                    format = { UsageLog.formatUsd(it) }, labels = listOf("ElevenLabs", "Claude"),
                    picked = picked,
                    callout = { d -> shown.firstOrNull { it.date == d }?.let { u ->
                        listOf("${d.month.getDisplayName(DateStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth}",
                            "ElevenLabs ${UsageLog.formatUsd(u.scribeUsd)}", "Claude ${UsageLog.formatUsd(u.claudeUsd)}",
                            "Total ${UsageLog.formatUsd(u.totalUsd)}")
                    } },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Card {
                CardTitle("Audio sent to ElevenLabs", "Minutes per day")
                LineChart(shown.map { it.date }, listOf(eleven to shown.map { it.audioMs / 60_000.0 }), format = { "${it.toInt()} min" })
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Card {
                CardTitle("Claude tokens", "Per day, in and out")
                LineChart(shown.map { it.date }, listOf(claude to shown.map { (it.inputTokens + it.outputTokens).toDouble() }), format = { UsageLog.formatTokens(it.toLong()) })
            }
            Spacer(Modifier.height(24.dp))
        }
        group(R.string.usage_export_group, listOf(
            {
                SettingRow("Export as spreadsheet (CSV)", "One row per day: audio, tokens and cost for each service",
                    leading = { RowIcon(Icons.Rounded.TableChart, cs.secondaryContainer, cs.onSecondaryContainer) }, onClick = actions::exportCsv)
            },
            {
                SettingRow("Export as JSON", "The same, for scripts and other apps",
                    leading = { RowIcon(Icons.Rounded.DataObject, cs.surfaceContainerHighest, cs.onSurfaceVariant) }, onClick = actions::exportJson)
            },
        ))
        item {
            Footnote("Kept on this phone for 13 months: counts and costs only, never what you said. Costs use list prices (Scribe v2 Realtime \$0.39 an hour; Claude per million tokens) and may differ a little from your bills.")
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(rowColor()).padding(20.dp)) { content() }

@Composable
private fun CardTitle(title: String, sub: String?) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SplitTile(color: Color, name: String, cost: String, use: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(cost, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        Text(use, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Legend(items: List<Pair<Color, String>>) = Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    items.forEach { (c, n) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c))
            Spacer(Modifier.width(6.dp))
            Text(n, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ---------------------------------------------------------------- charts */

private class Ink(val grid: Color, val axis: Color, val muted: Color, val surface: Color, val ink: Color)

@Composable
private fun ink() = MaterialTheme.colorScheme.let { Ink(it.outlineVariant.copy(alpha = 0.6f), it.outlineVariant, it.onSurfaceVariant, rowColor(), it.onSurface) }

/** "Nice" round steps for a y-axis from 0 to about [max]. */
private fun ticks(max: Double): List<Double> {
    if (max <= 0) return listOf(0.0, 1.0)
    val raw = max / 3
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return (0..3).map { it * step }.let { if (it.last() < max) it + 4 * step else it }
}

private val AXIS_W = 52.dp
private val AXIS_H = 22.dp

/** A line per series over days, on one y-axis from zero, with a crosshair callout at [picked]. */
@Composable
private fun LineChart(
    dates: List<LocalDate>,
    series: List<Pair<Color, List<Double>>>,
    format: (Double) -> String,
    labels: List<String>? = null,
    picked: LocalDate? = null,
    callout: (LocalDate) -> List<String>? = { null },
) {
    val tm = rememberTextMeasurer()
    val k = ink()
    val small = MaterialTheme.typography.labelSmall.copy(color = k.muted)
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        if (dates.size < 2) return@Canvas
        val max = series.maxOf { s -> s.second.maxOrNull() ?: 0.0 }
        val t = ticks(max)
        val left = AXIS_W.toPx(); val bottom = size.height - AXIS_H.toPx(); val top = 8.dp.toPx(); val right = size.width - 4.dp.toPx()
        fun x(i: Int) = left + (right - left) * i / (dates.size - 1)
        fun y(v: Double) = bottom - ((bottom - top) * (v / t.last())).toFloat()
        yAxis(tm, t, small, k, left, right, ::y, format)
        xAxis(tm, dates, small, k, ::x, bottom)
        series.forEachIndexed { si, (c, vals) ->
            drawPath(linePath(vals.indices.map { Offset(x(it), y(vals[it])) }), c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val i = picked?.let { dates.indexOf(it) } ?: -1
        if (i >= 0) {
            drawLine(k.axis, Offset(x(i), top), Offset(x(i), bottom), 1.dp.toPx())
            series.forEach { (c, vals) ->
                drawCircle(k.surface, 6.dp.toPx(), Offset(x(i), y(vals[i])))
                drawCircle(c, 4.dp.toPx(), Offset(x(i), y(vals[i])))
            }
            callout(dates[i])?.let { lines -> drawCallout(tm, lines, x(i), top, left, right, k) }
        }
    }
}

/** The month adding up day by day, then a dashed line on to the projected total at month's end. */
@Composable
private fun MonthChart(days: List<DayUsage>, today: LocalDate, outlook: MonthOutlook) {
    val tm = rememberTextMeasurer()
    val k = ink()
    val line = MaterialTheme.colorScheme.primary
    val small = MaterialTheme.typography.labelSmall.copy(color = k.muted)
    val strong = MaterialTheme.typography.labelMedium.copy(color = k.ink, fontWeight = FontWeight.Medium)
    val first = today.withDayOfMonth(1)
    val n = today.lengthOfMonth()
    val dates = (0 until n).map { first.plusDays(it.toLong()) }
    var run = 0.0
    val cum = dates.take(today.dayOfMonth).map { d -> run += days.filter { it.date == d }.sumOf { it.totalUsd }; run }
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val t = ticks(outlook.projected * 1.08)
        val left = AXIS_W.toPx(); val bottom = size.height - AXIS_H.toPx(); val top = 22.dp.toPx(); val right = size.width - 4.dp.toPx()
        fun x(i: Int) = left + (right - left) * i / (n - 1)
        fun y(v: Double) = bottom - ((bottom - top) * (v / t.last())).toFloat()
        yAxis(tm, t, small, k, left, right, ::y) { UsageLog.formatUsd(it) }
        xAxis(tm, dates, small, k, ::x, bottom)
        val pts = cum.indices.map { Offset(x(it), y(cum[it])) }
        drawPath(linePath(pts), line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = Offset(x(n - 1), y(outlook.projected))
        drawLine(line.copy(alpha = 0.7f), pts.last(), end, 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        drawCircle(k.surface, 6.dp.toPx(), pts.last()); drawCircle(line, 4.dp.toPx(), pts.last())
        drawCircle(k.surface, 6.dp.toPx(), end); drawCircle(line, 4.dp.toPx(), end, style = Stroke(2.dp.toPx()))
        // direct labels: today's total, and the projection
        val now = tm.measure("Today ${UsageLog.formatUsd(outlook.toDate)}", strong)
        drawText(now, topLeft = Offset((pts.last().x - now.size.width / 2).coerceIn(left, right - now.size.width), pts.last().y - now.size.height - 8.dp.toPx()))
        val proj = tm.measure("≈ ${UsageLog.formatUsd(outlook.projected)}", strong)
        drawText(proj, topLeft = Offset(right - proj.size.width, end.y - proj.size.height - 8.dp.toPx()))
    }
}

private fun linePath(pts: List<Offset>) = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }

private fun DrawScope.yAxis(tm: TextMeasurer, t: List<Double>, style: TextStyle, k: Ink, left: Float, right: Float, y: (Double) -> Float, format: (Double) -> String) {
    t.forEach { v ->
        val yy = y(v)
        drawLine(if (v == 0.0) k.axis else k.grid, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
        val m = tm.measure(format(v), style)
        drawText(m, topLeft = Offset(left - 8.dp.toPx() - m.size.width, yy - m.size.height / 2))
    }
}

private fun DrawScope.xAxis(tm: TextMeasurer, dates: List<LocalDate>, style: TextStyle, k: Ink, x: (Int) -> Float, bottom: Float) {
    val every = when { dates.size <= 8 -> 1; dates.size <= 31 -> 7; else -> 14 }
    var lastEnd = -1f
    dates.forEachIndexed { i, d ->
        if ((dates.size - 1 - i) % every != 0 && i != 0) return@forEachIndexed
        val label = if (i == 0 || d.dayOfMonth <= every) "${d.month.getDisplayName(DateStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth}" else "${d.dayOfMonth}"
        val m = tm.measure(label, style)
        val lx = (x(i) - m.size.width / 2).coerceIn(x(0) - 4f, x(dates.lastIndex) - m.size.width + 4f)
        if (lx < lastEnd + 6.dp.toPx()) return@forEachIndexed
        drawText(m, topLeft = Offset(lx, bottom + 5.dp.toPx()))
        lastEnd = lx + m.size.width
    }
}

private fun DrawScope.drawCallout(tm: TextMeasurer, lines: List<String>, at: Float, top: Float, left: Float, right: Float, k: Ink) {
    val head = TextStyle(color = k.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = k.muted, fontSize = 12.sp)
    val ms = lines.mapIndexed { i, s -> tm.measure(s, if (i == 0) head else body) }
    val pad = 8.dp.toPx()
    val w = ms.maxOf { it.size.width } + pad * 2
    val h = ms.sumOf { it.size.height } + pad * 2
    val bx = if (at + 10.dp.toPx() + w <= right) at + 10.dp.toPx() else at - 10.dp.toPx() - w
    drawRoundRect(k.ink.copy(alpha = 0.08f), Offset(bx + 1.dp.toPx(), top + 2.dp.toPx()), Size(w, h), CornerRadius(12.dp.toPx()))
    drawRoundRect(k.surface, Offset(bx, top), Size(w, h), CornerRadius(12.dp.toPx()))
    drawRoundRect(k.axis, Offset(bx, top), Size(w, h), CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
    var yy = top + pad
    ms.forEach { m -> drawText(m, topLeft = Offset(bx + pad, yy)); yy += m.size.height }
}

/** Days as CSV, oldest first, for export. */
fun usageCsv(days: List<DayUsage>): String = buildString {
    appendLine("date,audio_seconds,input_tokens,output_tokens,elevenlabs_usd,claude_usd,total_usd")
    days.sortedBy { it.date }.forEach { d ->
        appendLine(String.format(Locale.US, "%s,%d,%d,%d,%.4f,%.4f,%.4f", d.date, d.audioMs / 1000, d.inputTokens, d.outputTokens, d.scribeUsd, d.claudeUsd, d.totalUsd))
    }
}
