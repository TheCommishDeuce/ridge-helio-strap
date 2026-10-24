package app.strap.ui.today

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
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.strap.ui.components.CardHeader
import app.strap.ui.components.DayWindow
import app.strap.ui.components.HourAxis
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Span
import app.strap.ui.components.StatusText
import app.strap.ui.components.Subtle
import app.strap.ui.components.bar
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The top of the day cards, one shape for heart, stress and steps: the day's figure with its
 * change against the usual under it, and two smaller figures on the right.
 */
@Composable
private fun Headline(value: String, unit: String, change: Note?, side: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = RidgeType.cardValue)
                Subtle("  $unit", Modifier.padding(bottom = 5.dp), RidgeType.caption)
            }
            StatusText(change ?: Note(" ", Color.Transparent))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            side.forEach { (label, v) ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Subtle("$label  ", Modifier.padding(bottom = 2.dp), RidgeType.caption)
                    Text(v, style = RidgeType.footValue)
                }
            }
        }
    }
}

/** One slice's bar: when it starts, its height as a share of the strip (0-1), its colour. */
private class SliceBar(val t: Long, val f: Float, val color: Color)

/** The day as one bar per [SLICE_MS] slice, rising from the floor: the strip all three cards draw. */
@Composable
private fun SliceBars(data: TodayData, bars: List<SliceBar>) {
    DayStrip(data.curves.window, data.curves.sleep) { x, day ->
        val slot = size.width * (SLICE_MS.toFloat() / day)
        for (b in bars) bar(b.color, x(b.t) + slot * 0.15f, size.height * (1 - b.f.coerceIn(0.06f, 1f)), slot * 0.7f, size.height)
    }
}

/** How the day split between levels: one rounded bar of coloured parts, each part's name and share under it. */
@Composable
private fun ShareBar(parts: List<Triple<String, Double, Color>>) {
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.second > 0 }.forEach { (_, v, color) -> Box(Modifier.weight(v.toFloat()).height(8.dp).background(color)) }
        }
        Row(Modifier.fillMaxWidth()) {
            parts.forEach { (label, v, color) ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Text(" $label ${(100 * v / total).roundToInt()}%", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** The strap's stress levels (Zepp's bands): relaxed under 40, normal to 59, medium to 79, high from 80. */
@Composable
private fun stressLevels(): List<Pair<String, Color>> {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    return listOf("Relaxed" to c.strain, "Normal" to r.good, "Medium" to c.stress, "High" to r.bad)
}

private fun stressLevel(v: Double) = when { v < 40 -> 0; v < 60 -> 1; v < 80 -> 2; else -> 3 }

/**
 * Stress today: the average against the usual, the highest (and when) and lowest; each
 * ten-minute slice as a bar coloured by the strap's level; and how the day split between
 * relaxed, normal, medium and high. The strap's own 0-100 number; the change against the usual
 * is shown, never judged (D28, [[wearable_stress_validity]]).
 */
@Composable
internal fun StressCard(data: TodayData, onOpen: () -> Unit) {
    val c = LocalMetricColors.current
    val s = data.stress
    val levels = stressLevels()
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Stress", s?.usual?.let { "usual ${it.roundToInt()}" } ?: usualSuffix(data.day), chevron = true, icon = Icons.Rounded.Psychology, tone = c.stressTone)
        if (s == null) {
            Subtle(data.stressNote ?: "The strap recorded nothing for this yet.")
            return@RidgeCard
        }
        Headline("${s.mean.roundToInt()}", "average", changeNote(s.usual?.let { s.mean - it }, "vs usual", neutral = true),
            listOf("High · ${clockOf(s.maxAt, data.curves.window.zone)}" to "${s.max.roundToInt()}", "Low" to "${s.min.roundToInt()}"))
        SliceBars(data, data.curves.stress.map { SliceBar(it.t, (it.mean / 100).toFloat(), levels[stressLevel(it.mean)].second) })
        val counts = IntArray(4).also { n -> data.curves.stress.forEach { n[stressLevel(it.mean)]++ } }
        ShareBar(levels.mapIndexed { i, (name, color) -> Triple(name, counts[i].toDouble(), color) })
    }
}

/** Heart-rate zone colours, below zone 1 to zone 5 (by percent of max HR: 50, 60, 70, 80, 90). */
@Composable
private fun zoneColors(): List<Pair<String, Color>> {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    return listOf("Rest" to c.heart.copy(alpha = 0.45f), "Z1" to c.strain, "Z2" to r.good, "Z3" to r.warn, "Z4" to c.stress, "Z5" to r.bad)
}

private fun zoneOf(hr: Double, hrmax: Int) = listOf(0.5, 0.6, 0.7, 0.8, 0.9).count { hr / hrmax >= it }

/**
 * Heart rate today, in the same shape: the average against the usual, the peak (and when) and
 * low; each ten-minute slice's mean as a bar coloured by its zone; and the waking day's split
 * between zones.
 */
@Composable
internal fun HeartCard(data: TodayData, onOpen: () -> Unit) {
    val c = LocalMetricColors.current
    val h = data.heart
    val zones = zoneColors()
    val hrmax = data.hrmax ?: 190
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Heart rate", h?.usual?.let { "usual ${it.roundToInt()} bpm" } ?: usualSuffix(data.day), chevron = true, icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
        if (h == null) {
            Subtle("The strap recorded nothing for this yet.")
            return@RidgeCard
        }
        Headline("${h.mean.roundToInt()}", "bpm average", changeNote(h.usual?.let { h.mean - it }, "vs usual", neutral = true),
            listOf("Peak · ${clockOf(h.maxAt, data.curves.window.zone)}" to "${h.max.roundToInt()}", "Low" to "${h.min.roundToInt()}"))
        val slices = data.curves.hr
        // From a little under the day's lowest slice, so the night still shows as low bars.
        val floor = (slices.minOfOrNull { it.mean } ?: 0.0) - 10
        val top = (slices.maxOfOrNull { it.mean } ?: 1.0).coerceAtLeast(floor + 1)
        SliceBars(data, slices.map { SliceBar(it.t, ((it.mean - floor) / (top - floor)).toFloat(), zones[zoneOf(it.mean, hrmax)].second) })
        // Waking slices only: asleep, everyone is at rest.
        val awake = slices.filter { sl -> data.curves.sleep.none { sl.t >= it.start && sl.t < it.end } }
        val counts = IntArray(6).also { n -> awake.forEach { n[zoneOf(it.mean, hrmax)]++ } }
        ShareBar(listOf(Triple(zones[0].first, counts[0].toDouble(), zones[0].second), Triple(zones[1].first, counts[1].toDouble(), zones[1].second),
            Triple("Z2+", (counts[2] + counts[3] + counts[4] + counts[5]).toDouble(), zones[3].second)))
    }
}

/**
 * Steps in the same shape: the count against where you usually are by now (or a usual day),
 * the distance and active minutes; the steps of each ten-minute slice as bars; and how they
 * split between morning, afternoon and evening.
 */
@Composable
internal fun StepsCard(data: TodayData, onOpen: () -> Unit, live: Long? = null) {
    val c = LocalMetricColors.current
    val color = c.steps
    // The strap's live counter beats the last synced count (it is the same instrument, later).
    val liveToday = live?.takeIf { data.day == LocalDate.now() }
    val steps = liveToday?.toDouble() ?: data.steps.valueOrNull
    val running = data.day == LocalDate.now()
    val reference = if (running) data.stepsUsualByNow else data.steps.usual
    val usual = reference?.let { "usual %,d".format(it.roundToInt()) + if (running) " by now" else "" } ?: usualSuffix(data.day)
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Steps", if (liveToday != null) "live · $usual" else usual, chevron = true, icon = Icons.AutoMirrored.Rounded.DirectionsWalk, tone = c.stepsTone)
        Headline(steps?.let { "%,d".format(it.roundToInt()) } ?: "—", "steps",
            changeNote(steps?.let { v -> reference?.let { v - it } }, "vs usual", magnitude = { "%,d".format(it.roundToInt()) }, meaningful = (reference ?: 0.0) * 0.05),
            listOf("Distance" to (data.distanceM?.let { "%.1f km".format(it / 1000) } ?: "—"), "Active" to (data.activeMin?.let { "${it.roundToInt()} min" } ?: "—")))
        val slices = data.curves.steps
        val most = (slices.maxOfOrNull { it.v } ?: 0.0).coerceAtLeast(1.0)
        SliceBars(data, slices.filter { it.v > 0 }.map { SliceBar(it.t, (it.v / most).toFloat(), color) })
        val zone = data.curves.window.zone
        val parts = DoubleArray(3)
        slices.forEach { p -> parts[when (java.time.Instant.ofEpochMilli(p.t).atZone(zone).hour) { in 0..11 -> 0; in 12..17 -> 1; else -> 2 }] += p.v }
        ShareBar(listOf(Triple("Morning", parts[0], color.copy(alpha = 0.55f)), Triple("Afternoon", parts[1], color), Triple("Evening", parts[2], c.strain)))
    }
}

/** "vs usual by now" on a day still running, else "vs usual". */
private fun usualSuffix(day: LocalDate) = if (day == LocalDate.now()) "vs your usual by now" else "vs your usual"

/** A 00-24 strip for a local day (its real length on a travel day): sleep shaded, 6-hourly grid, [draw] on top. */
@Composable
private fun DayStrip(window: DayWindow, sleep: List<Span>, draw: DrawScope.(x: (Long) -> Float, dayMs: Long) -> Unit) {
    val dayStart = window.start
    val grid = LocalRidgeColors.current.surface3
    val sleepShade = LocalMetricColors.current.sleepTone.container.copy(alpha = 0.55f)
    Column {
        Spacer(
            Modifier.fillMaxWidth().height(56.dp).drawWithCache {
                val dayMs = window.end - window.start
                fun x(t: Long) = ((t - dayStart).toFloat() / dayMs) * size.width
                val shades = sleep.map { s -> x(s.start.coerceAtLeast(dayStart)) to x(s.end.coerceAtMost(dayStart + dayMs)) }.filter { (l, r) -> r > l }
                onDrawBehind {
                    for ((l, r) in shades) drawRoundRect(sleepShade, Offset(l, 0f), Size(r - l, size.height), CornerRadius(6.dp.toPx()))
                    for (h in listOf(6, 12, 18)) x(dayStart + h * 3_600_000L).let { drawLine(grid, Offset(it, 0f), Offset(it, size.height), 1.dp.toPx()) }
                    draw(::x, dayMs)
                }
            },
        )
        HourAxis()
    }
}
