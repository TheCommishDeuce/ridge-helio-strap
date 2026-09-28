package app.strap.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A point on a day chart: epoch ms and value. */
data class Point(val t: Long, val v: Double)

/** A shaded span (sleep) on a day chart, epoch ms. */
data class Span(val start: Long, val end: Long)

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

/**
 * Press-and-drag inspection: reports the horizontal fraction under the finger while pressed,
 * null when released. Only HORIZONTAL drags are consumed, so the list still scrolls.
 */
internal fun Modifier.scrub(onScrub: (Float?) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onPress = { o ->
            onScrub(o.x / size.width)
            tryAwaitRelease()
            onScrub(null)
        })
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { o -> onScrub(o.x / size.width) },
            onDragEnd = { onScrub(null) },
            onDragCancel = { onScrub(null) },
            onHorizontalDrag = { change, _ -> onScrub((change.position.x / size.width).coerceIn(0f, 1f)) },
        )
    }

/** A bar with the design's 8/8/3/3 corners (capped to half its width). */
internal fun DrawScope.bar(color: Color, left: Float, top: Float, width: Float, bottom: Float) {
    val h = (bottom - top).coerceAtLeast(2f)
    val rt = minOf(8.dp.toPx(), width / 2, h / 2)
    val rb = minOf(3.dp.toPx(), width / 2, h / 2)
    val path = Path().apply {
        addRoundRect(RoundRect(left, bottom - h, left + width, bottom, CornerRadius(rt), CornerRadius(rt), CornerRadius(rb), CornerRadius(rb)))
    }
    drawPath(path, color)
}

/**
 * A full local day, 00:00 to 24:00, as a line. The line breaks where readings are more
 * than [maxGapMs] apart — a gap is a gap, never bridged. The peak is marked; press and
 * drag to read any minute: into [onScrub] when given, else a readout above the chart.
 */
@Composable
fun DayLineChart(
    points: List<Point>,
    dayStart: Long,
    color: Color,
    maxGapMs: Long,
    unit: String,
    modifier: Modifier = Modifier,
    shaded: List<Span> = emptyList(),
    height: Dp = 140.dp,
    onScrub: ((Point?) -> Unit)? = null,
) {
    val dayEnd = dayStart + 24 * 3_600_000L
    val grid = LocalRidgeColors.current.surface3
    val sleepShade = LocalMetricColors.current.sleepTone.container.copy(alpha = 0.55f)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val selected = scrub?.let { f -> nearest(points, dayStart + ((dayEnd - dayStart) * f).toLong(), maxGapMs) }
    Column(modifier) {
        if (onScrub == null) ValueLabels(selected?.let { "${clockOf(it.t)} · ${it.v.roundToInt()} $unit" }, points.maxOfOrNull { it.v }, points.minOfOrNull { it.v })
        // drawWithCache: the path is built once per size/data change, not on every frame.
        Spacer(
            Modifier.fillMaxWidth().height(height).scrub { f ->
                scrub = f
                onScrub?.invoke(f?.let { nearest(points, dayStart + ((dayEnd - dayStart) * it).toLong(), maxGapMs) })
            }.drawWithCache {
                val lo = (points.minOfOrNull { it.v } ?: 0.0) - 5
                val hi = (points.maxOfOrNull { it.v } ?: 1.0) + 5
                fun x(t: Long) = ((t - dayStart).toFloat() / (dayEnd - dayStart)) * size.width
                fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
                val path = Path()
                var prev: Point? = null
                for (p in points) {
                    if (prev == null || p.t - prev.t > maxGapMs) path.moveTo(x(p.t), y(p.v)) else path.lineTo(x(p.t), y(p.v))
                    prev = p
                }
                val peak = points.maxByOrNull { it.v }?.let { Offset(x(it.t), y(it.v)) }
                val shades = shaded.map { s -> x(s.start.coerceAtLeast(dayStart)) to x(s.end.coerceAtMost(dayEnd)) }.filter { (l, r) -> r > l }
                val gridX = listOf(6, 12, 18).map { x(dayStart + it * 3_600_000L) }
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val sel = selected?.let { Offset(x(it.t), y(it.v)) }
                onDrawBehind {
                    for ((l, r) in shades) drawRoundRect(sleepShade, Offset(l, 0f), Size(r - l, size.height), CornerRadius(6.dp.toPx()))
                    for (gx in gridX) drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), 1.dp.toPx())
                    drawPath(path, color, style = stroke)
                    if (sel == null) peak?.let { drawCircle(color, 6.dp.toPx(), it) }
                    sel?.let {
                        drawLine(color.copy(alpha = 0.5f), Offset(it.x, 0f), Offset(it.x, size.height), 1.dp.toPx())
                        drawCircle(color, 6.dp.toPx(), it)
                    }
                }
            },
        )
        HourAxis()
    }
}

/** The reading nearest to [t], unless the nearest one is further away than a gap. */
private fun nearest(points: List<Point>, t: Long, maxGapMs: Long): Point? {
    if (points.isEmpty()) return null
    var lo = 0
    var hi = points.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].t < t) lo = mid + 1 else hi = mid
    }
    val candidates = listOfNotNull(points.getOrNull(lo - 1), points.getOrNull(lo))
    return candidates.minByOrNull { kotlin.math.abs(it.t - t) }?.takeIf { kotlin.math.abs(it.t - t) <= maxGapMs }
}

/** The scrub readout when pressing, else faint high/low labels. */
@Composable
internal fun ValueLabels(scrubbed: String?, high: Double?, low: Double?) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth().height(20.dp)) {
        if (scrubbed != null) {
            Text(scrubbed, style = RidgeType.label, modifier = Modifier.align(Alignment.CenterStart))
        } else if (high != null && low != null) {
            Row(Modifier.align(Alignment.CenterEnd)) {
                Text("high ${high.roundToInt()}", style = RidgeType.caption, color = faint)
                Spacer(Modifier.width(10.dp))
                Text("low ${low.roundToInt()}", style = RidgeType.caption, color = faint)
            }
        }
    }
}

/** Twenty-four bars for a local day (e.g. steps per hour). Hours with no data draw nothing. */
@Composable
fun DayBars(values: Map<Int, Double>, color: Color, unit: String, modifier: Modifier = Modifier, height: Dp = 90.dp, onScrub: ((Int?) -> Unit)? = null) {
    val grid = LocalRidgeColors.current.surface3
    var scrub by remember { mutableStateOf<Float?>(null) }
    val hour = scrub?.let { (it * 24).toInt().coerceIn(0, 23) }
    Column(modifier) {
        if (onScrub == null) ValueLabels(hour?.let { "%02d:00 · %,d %s".format(it, (values[it] ?: 0.0).roundToInt(), unit) }, values.values.maxOrNull(), null)
        Spacer(
            Modifier.fillMaxWidth().height(height).scrub { f ->
                scrub = f
                onScrub?.invoke(f?.let { (it * 24).toInt().coerceIn(0, 23) })
            }.drawWithCache {
                val max = (values.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                val slot = size.width / 24
                onDrawBehind {
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    for (h in 0 until 24) {
                        val v = values[h] ?: continue
                        val barH = (v / max).toFloat() * size.height
                        val alpha = if (hour == null || hour == h) 1f else 0.4f
                        bar(color.copy(alpha = alpha), h * slot + slot * 0.14f, size.height - barH, slot * 0.72f, size.height)
                    }
                }
            },
        )
        HourAxis()
    }
}

/**
 * A night's stages as a hypnogram in a `surface3` box: awake on top, deep at the bottom.
 * Deep = sleep, REM = sleep mixed with onSurface, Light = sleep at half, Awake = stress.
 */
@Composable
fun Hypnogram(stages: List<Triple<Long, Long, Int>>, modifier: Modifier = Modifier) {
    if (stages.isEmpty()) return
    val start = stages.first().first
    val end = stages.last().second
    val c = LocalMetricColors.current
    // strap stage codes: 7 awake, 8 REM, 4 light, 5 deep
    val rows = listOf(7 to "Awake", 8 to "REM", 4 to "Light", 5 to "Deep")
    val shades = stageShades(c.sleep, c.stress, MaterialTheme.colorScheme.onSurface)
    val box = LocalRidgeColors.current.surface3
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.height(92.dp).padding(end = 8.dp)) {
                rows.forEach { (_, label) ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(label, style = RidgeType.change, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(
                Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(12.dp)).drawWithCache {
                    val pad = 6.dp.toPx()
                    val rowH = (size.height - 2 * pad) / rows.size
                    fun x(t: Long) = pad + ((t - start).toFloat() / (end - start)) * (size.width - 2 * pad)
                    val index = rows.withIndex().associate { (i, r) -> r.first to i }
                    onDrawBehind {
                        drawRect(box)
                        for ((s, e, code) in stages) {
                            val r = index[code] ?: continue
                            drawRoundRect(shades.getValue(code), Offset(x(s), pad + r * rowH + rowH * 0.15f),
                                Size((x(e) - x(s)).coerceAtLeast(3f), rowH * 0.7f), CornerRadius(4.dp.toPx()))
                        }
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(44.dp))
            Box(Modifier.weight(1f)) { EvenAxis((0..3).map { clockOf(start + (end - start) * it / 3) }) }
        }
    }
}

/** Stage colours, shared by the hypnogram and its legend tiles. */
fun stageShades(sleep: Color, awake: Color, onSurface: Color): Map<Int, Color> =
    mapOf(7 to awake, 8 to lerp(sleep, onSurface, 0.25f), 4 to sleep.copy(alpha = 0.5f), 5 to sleep)

/** Hour labels centred under 00/06/12/18/24, clamped inside the chart's edges. */
@Composable
internal fun HourAxis() = EvenAxis(listOf("00", "06", "12", "18", "24"))

/**
 * Labels spread across the width: at the edges and evenly between ([slots] false), or
 * centred in equal slots ([slots] true — one label per day of a week).
 */
@Composable
internal fun EvenAxis(labels: List<String>, slots: Boolean = false) {
    Layout(
        content = { labels.forEach { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p ->
                val centre = when {
                    slots -> (width * (2 * i + 1)) / (2 * labels.size)
                    labels.size == 1 -> width / 2
                    else -> width * i / (labels.size - 1)
                }
                p.placeRelative((centre - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
