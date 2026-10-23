package app.strap.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import app.strap.ui.components.Gauge
import app.strap.ui.components.Note
import app.strap.ui.components.StatusText
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.RidgeType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.strap.ui.components.CardFoot
import app.strap.ui.components.CardHeader
import app.strap.ui.components.CardValue
import app.strap.ui.components.HourAxis
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Span
import app.strap.ui.components.Subtle
import app.strap.ui.components.bar
import app.strap.ui.components.changeNote
import app.strap.ui.components.DayWindow
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import java.time.LocalDate
import kotlin.math.roundToInt

/** Three coloured figures (value over label) and a ring on the right: the top of the day cards (Bevel's stress card). */
@Composable
private fun Figures(items: List<Triple<String, String, Color>>, ring: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items.forEach { (value, label, color) ->
                Column {
                    Text(value, style = RidgeType.tileValue, color = color)
                    Text(label, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ring()
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
 * Stress today, as Bevel's stress card: highest (and when), lowest and average in their colours
 * with the average as a ring; the day's ten-minute slices coloured by the strap's level; and how
 * the day split between relaxed, normal, medium and high. The strap's own 0-100 number; the
 * change against the usual is shown, never judged (D28, [[wearable_stress_validity]]).
 */
@Composable
internal fun StressCard(data: TodayData, onOpen: () -> Unit) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val s = data.stress
    val levels = stressLevels()
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Stress", s?.usual?.let { "usual ${it.roundToInt()}" } ?: usualSuffix(data.day), chevron = true, icon = Icons.Rounded.Psychology, tone = c.stressTone)
        if (s == null) {
            Subtle(data.stressNote ?: "The strap recorded nothing for this yet.")
            return@RidgeCard
        }
        Figures(listOf(
            Triple("${s.max.roundToInt()}", "Highest · ${clockOf(s.maxAt, data.curves.window.zone)}", r.bad),
            Triple("${s.min.roundToInt()}", "Lowest", c.strain),
            Triple("${s.mean.roundToInt()}", "Average", c.stress),
        )) { Gauge("${s.mean.roundToInt()}", null, (s.mean / 100).toFloat(), c.stress, 64.dp, 17.sp, stroke = 9f) }
        DayStrip(data.curves.window, data.curves.sleep) { x, day ->
            val slot = size.width * (SLICE_MS.toFloat() / day)
            for (sl in data.curves.stress) {
                val f = (sl.mean / 100).toFloat().coerceIn(0.06f, 1f)
                bar(levels[stressLevel(sl.mean)].second, x(sl.t) + slot * 0.15f, size.height * (1 - f), slot * 0.7f, size.height)
            }
        }
        val counts = IntArray(4).also { n -> data.curves.stress.forEach { n[stressLevel(it.mean)]++ } }
        ShareBar(levels.mapIndexed { i, (name, color) -> Triple(name, counts[i].toDouble(), color) })
        StatusText(changeNote(s.usual?.let { s.mean - it }, "average vs usual", neutral = true) ?: Note(" ", Color.Transparent))
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
 * Heart rate today, in the same shape: peak (and when), low and resting in their colours with
 * the day's average as a ring; each ten-minute slice as its low-to-high band, coloured by the
 * zone its mean falls in; and the waking day's split between zones.
 */
@Composable
internal fun HeartCard(data: TodayData, onOpen: () -> Unit) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val h = data.heart
    val zones = zoneColors()
    val hrmax = data.hrmax ?: 190
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Heart rate", h?.usual?.let { "usual ${it.roundToInt()} bpm" } ?: usualSuffix(data.day), chevron = true, icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
        if (h == null) {
            Subtle("The strap recorded nothing for this yet.")
            return@RidgeCard
        }
        Figures(listOf(
            Triple("${h.max.roundToInt()}", "Peak · ${clockOf(h.maxAt, data.curves.window.zone)}", c.heart),
            Triple("${h.min.roundToInt()}", "Low", c.strain),
            Triple(data.restingHr.valueOrNull?.roundToInt()?.toString() ?: "—", "Resting", r.good),
        )) { Gauge("${h.mean.roundToInt()}", "avg", (h.mean / hrmax).toFloat(), c.heart, 64.dp, 17.sp, stroke = 9f) }
        val slices = data.curves.hr
        DayStrip(data.curves.window, data.curves.sleep) { x, day ->
            if (slices.isEmpty()) return@DayStrip
            val lo = slices.minOf { it.min } - 5
            val hi = slices.maxOf { it.max } + 5
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val slot = size.width * (SLICE_MS.toFloat() / day)
            for (sl in slices) {
                drawRoundRect(zones[zoneOf(sl.mean, hrmax)].second, Offset(x(sl.t) + slot * 0.15f, y(sl.max)),
                    Size(slot * 0.7f, (y(sl.min) - y(sl.max)).coerceAtLeast(3f)), CornerRadius(slot * 0.35f))
            }
        }
        // Waking slices only: asleep, everyone is at rest.
        val awake = slices.filter { sl -> data.curves.sleep.none { sl.t >= it.start && sl.t < it.end } }
        val counts = IntArray(6).also { n -> awake.forEach { n[zoneOf(it.mean, hrmax)]++ } }
        ShareBar(listOf(Triple(zones[0].first, counts[0].toDouble(), zones[0].second), Triple(zones[1].first, counts[1].toDouble(), zones[1].second),
            Triple("Z2+", (counts[2] + counts[3] + counts[4] + counts[5]).toDouble(), zones[3].second)))
        StatusText(changeNote(h.usual?.let { h.mean - it }, "average vs usual", neutral = true) ?: Note(" ", Color.Transparent))
    }
}

/**
 * Steps in the same shape: the count, where you usually are by now and the distance in their
 * colours with the share of a usual day as a ring; the day adding up as a filled line with a dot
 * where you usually are by now; and how the steps split between morning, afternoon and evening.
 */
@Composable
internal fun StepsCard(data: TodayData, onOpen: () -> Unit, live: Long? = null) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val color = c.steps
    // The strap's live counter beats the last synced count (it is the same instrument, later).
    val liveToday = live?.takeIf { data.day == LocalDate.now() }
    val steps = liveToday?.toDouble() ?: data.steps.valueOrNull
    val running = data.day == LocalDate.now()
    val byNow = data.stepsUsualByNow
    val usualDay = data.steps.usual
    val reference = if (running) byNow else usualDay
    val marker = MaterialTheme.colorScheme.onSurface
    RidgeCard(onClick = onOpen, spacing = 14.dp) {
        CardHeader("Steps", if (liveToday != null) "live" else usualSuffix(data.day), chevron = true, icon = Icons.AutoMirrored.Rounded.DirectionsWalk, tone = c.stepsTone)
        Figures(listOf(
            Triple(steps?.let { "%,d".format(it.roundToInt()) } ?: "—", "Steps", color),
            Triple(reference?.let { "%,d".format(it.roundToInt()) } ?: "—", if (running) "Usual by now" else "Usual day", c.strain),
            Triple(data.distanceM?.let { "%.1f".format(it / 1000) } ?: "—", "km", r.good),
        )) {
            val share = if (steps != null && usualDay != null && usualDay > 0) steps / usualDay else null
            Gauge(share?.let { "${(100 * it).roundToInt()}%" } ?: "—", null, share?.toFloat(), color, 64.dp, 15.sp, stroke = 9f)
        }
        val points = data.curves.stepsSoFar
        DayStrip(data.curves.window, data.curves.sleep) { x, _ ->
            if (points.isEmpty()) return@DayStrip
            val top = listOfNotNull(points.last().v, steps, reference).max().coerceAtLeast(1.0) * 1.08
            fun y(v: Double) = size.height - (v / top).toFloat() * size.height
            val fill = Path().apply {
                moveTo(x(data.curves.window.start), size.height)
                points.forEach { lineTo(x(it.t), y(it.v)) }
                lineTo(x(points.last().t), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.04f))))
            val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.t), y(p.v)) else lineTo(x(p.t), y(p.v)) } }
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (running && byNow != null) drawCircle(marker, 4.dp.toPx(), Offset(x(System.currentTimeMillis()).coerceAtMost(size.width), y(byNow)))
        }
        // Steps added in each part of the day, from the running total.
        val zone = data.curves.window.zone
        fun hourOf(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).hour
        var prev = 0.0
        val parts = DoubleArray(3)
        points.forEach { p -> parts[when (hourOf(p.t - 1)) { in 0..11 -> 0; in 12..17 -> 1; else -> 2 }] += p.v - prev; prev = p.v }
        ShareBar(listOf(Triple("Morning", parts[0], color.copy(alpha = 0.55f)), Triple("Afternoon", parts[1], color), Triple("Evening", parts[2], c.strain)))
        StatusText(changeNote(steps?.let { v -> reference?.let { v - it } }, if (running) "vs usual by now" else "vs usual", magnitude = { "%,d".format(it.roundToInt()) },
            meaningful = (reference ?: 0.0) * 0.05) ?: Note(" ", Color.Transparent))
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
