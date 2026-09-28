package app.strap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.components.HourAxis
import app.strap.ui.components.Note
import app.strap.ui.components.NoteLine
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Span
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
 * Stress today: average against the usual for the same hours, high (and when) and low, and
 * the day as ten-minute bars. The strap's own 0-100 number, drawn by height and depth of
 * one colour only — no bands, and the change is shown but never judged (D28, [[wearable_stress_validity]]).
 */
@Composable
internal fun StressCard(data: TodayData, onOpen: () -> Unit) {
    val color = LocalMetricColors.current.stress
    val s = data.stress
    DayCard("Stress", color, onOpen) {
        if (s == null) {
            Subtle(data.stressNote ?: "The strap recorded nothing for this yet.")
            return@DayCard
        }
        Figures(
            "${s.mean.roundToInt()}", "average", changeNote(s.usual?.let { s.mean - it }, usualSuffix(data.day), neutral = true),
            listOf("${s.max.roundToInt()}" to "high · ${clockOf(s.maxAt)}", "${s.min.roundToInt()}" to "low"),
        )
        DayStrip(data.curves.dayStart, data.curves.sleep) { x, day ->
            val slot = size.width * (SLICE_MS.toFloat() / day)
            for (sl in data.curves.stress) {
                val f = (sl.mean / 100).toFloat().coerceIn(0f, 1f)
                bar(color.copy(alpha = 0.35f + 0.65f * f), x(sl.t) + slot * 0.15f, size.height * (1 - f), slot * 0.7f, size.height)
            }
        }
    }
}

/**
 * Heart rate today: average against the usual for the same hours, the day's range, resting
 * HR as context, and the day as ten-minute min-max bands under a mean line. A higher day
 * average is mostly more activity, so its change is shown but not judged.
 */
@Composable
internal fun HeartCard(data: TodayData, onOpen: () -> Unit) {
    val color = LocalMetricColors.current.heart
    val h = data.heart
    DayCard("Heart rate", color, onOpen) {
        if (h == null) {
            Subtle("The strap recorded nothing for this yet.")
            return@DayCard
        }
        Figures(
            "${h.mean.roundToInt()}", "bpm average", changeNote(h.usual?.let { h.mean - it }, usualSuffix(data.day), neutral = true),
            listOfNotNull(
                "${h.min.roundToInt()}–${h.max.roundToInt()}" to "range",
                data.restingHr.valueOrNull?.let { "${it.roundToInt()}" to "resting" },
            ),
        )
        val slices = data.curves.hr
        DayStrip(data.curves.dayStart, data.curves.sleep) { x, day ->
            if (slices.isEmpty()) return@DayStrip
            val lo = slices.minOf { it.min } - 5
            val hi = slices.maxOf { it.max } + 5
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val slot = size.width * (SLICE_MS.toFloat() / day)
            val line = Path()
            var prev: Slice? = null
            for (sl in slices) {
                drawRoundRect(color.copy(alpha = 0.22f), Offset(x(sl.t) + slot * 0.1f, y(sl.max)), Size(slot * 0.8f, (y(sl.min) - y(sl.max)).coerceAtLeast(2f)),
                    CornerRadius(slot * 0.4f))
                val mid = x(sl.t) + slot / 2
                // A missing slice is a gap, never bridged.
                if (prev == null || sl.t - prev.t > SLICE_MS) line.moveTo(mid, y(sl.mean)) else line.lineTo(mid, y(sl.mean))
                prev = sl
            }
            drawPath(line, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * Steps as one slim row: the count, a bar against the usual whole day, and a tick where
 * the day usually stands by now (a day still running) — plus distance and active minutes.
 */
@Composable
internal fun StepsRow(data: TodayData, onOpen: () -> Unit) {
    val color = LocalMetricColors.current.steps
    val r = LocalRidgeColors.current
    val steps = data.steps.valueOrNull
    val usualDay = data.steps.usual
    val byNow = data.stepsUsualByNow
    val scale = listOfNotNull(steps, usualDay, byNow).maxOrNull()?.coerceAtLeast(1.0)
    val reference = if (data.day == LocalDate.now()) byNow else usualDay
    val note = changeNote(steps?.let { v -> reference?.let { v - it } }, usualSuffix(data.day), magnitude = { "%,d".format(it.roundToInt()) })
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(r.card).clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = color, modifier = Modifier.size(20.dp))
            Text(steps?.let { "%,d".format(it.roundToInt()) } ?: "—", style = RidgeType.cardTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                modifier = Modifier.padding(start = 8.dp))
            Text(" steps", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(listOfNotNull(data.distanceM?.let { "%.1f km".format(it / 1000) }, data.activeMin?.let { "${it.roundToInt()} min active" }).joinToString(" · "),
                style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val track = r.surface3
        val marker = MaterialTheme.colorScheme.onSurface
        Spacer(
            Modifier.fillMaxWidth().height(10.dp).drawWithCache {
                val radius = CornerRadius(size.height / 2)
                val fill = if (steps != null && scale != null) (steps / scale).toFloat() * size.width else 0f
                val tick = if (data.day == LocalDate.now()) byNow?.let { b -> scale?.let { (b / it).toFloat() * size.width } } else null
                onDrawBehind {
                    drawRoundRect(track, cornerRadius = radius)
                    if (fill > 0f) drawRoundRect(color, size = Size(fill.coerceAtLeast(size.height), size.height), cornerRadius = radius)
                    tick?.let { t ->
                        val w = 2.dp.toPx()
                        drawRect(marker, Offset((t - w / 2).coerceIn(0f, size.width - w), -3.dp.toPx()), Size(w, size.height + 6.dp.toPx()))
                    }
                }
            },
        )
        if (note != null) NoteLine(note)
    }
}

/** "vs usual by now" on a day still running, else "vs usual". */
private fun usualSuffix(day: LocalDate) = if (day == LocalDate.now()) "vs usual by now" else "vs usual"

@Composable
private fun DayCard(title: String, color: Color, onOpen: () -> Unit, body: @Composable () -> Unit) {
    RidgeCard(onClick = onOpen, spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(title, style = RidgeType.cardTitle, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        body()
    }
}

/** The headline number with its label and change, then smaller side figures, Bevel-style. */
@Composable
private fun Figures(value: String, label: String, note: Note?, side: List<Pair<String, String>>) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(value, style = RidgeType.sideValue.copy(fontSize = 34.sp, lineHeight = 38.sp))
            Text(label, style = RidgeType.caption, color = faint)
            NoteLine(note)
        }
        side.forEachIndexed { i, (v, l) ->
            if (i > 0) Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(v, style = RidgeType.sideValue.copy(fontSize = 20.sp, lineHeight = 24.sp))
                Text(l, style = RidgeType.caption, color = faint)
                NoteLine(null)
            }
        }
    }
}

/** A 00-24 strip for a local day: sleep shaded, 6-hourly grid, [draw] on top. */
@Composable
private fun DayStrip(dayStart: Long, sleep: List<Span>, draw: DrawScope.(x: (Long) -> Float, dayMs: Long) -> Unit) {
    val grid = LocalRidgeColors.current.surface3
    val sleepShade = LocalMetricColors.current.sleepTone.container.copy(alpha = 0.55f)
    Column {
        Spacer(
            Modifier.fillMaxWidth().height(56.dp).drawWithCache {
                val dayMs = 24 * 3_600_000L
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
