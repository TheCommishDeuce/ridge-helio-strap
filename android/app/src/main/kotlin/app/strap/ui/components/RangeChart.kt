package app.strap.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalMetricColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One server bucket: start, min, max, mean, and when the max happened (epoch ms). */
data class Bucket(val t: Long, val min: Double, val max: Double, val mean: Double, val tMax: Long)

/**
 * Buckets over [start, end) as min–max bars with the mean marked — the zoomed-out view that
 * cannot hide a peak, because the bar's top IS the peak. Empty buckets draw nothing.
 */
@Composable
fun RangeChart(
    buckets: List<Bucket>,
    start: Long,
    end: Long,
    bucketMs: Long,
    color: Color,
    unit: String,
    axis: List<String>,
    modifier: Modifier = Modifier,
) {
    val track = LocalMetricColors.current.track
    var scrub by remember { mutableStateOf<Float?>(null) }
    val selected = scrub?.let { f -> buckets.minByOrNull { kotlin.math.abs((it.t + bucketMs / 2) - (start + (end - start) * f)) } }
    val label = selected?.let { b ->
        val when_ = Instant.ofEpochMilli(b.t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (bucketMs >= 86_400_000L) "EEE d MMM" else "EEE HH:mm"))
        "$when_ · ${b.min.roundToInt()}–${b.max.roundToInt()} $unit, avg ${b.mean.roundToInt()} · peak at ${clockOf(b.tMax)}"
    }
    Column(modifier) {
        ValueLabels(label, buckets.maxOfOrNull { it.max }, buckets.minOfOrNull { it.min })
        Spacer(
            Modifier.fillMaxWidth().height(160.dp).scrub { scrub = it }.drawWithCache {
                val lo = (buckets.minOfOrNull { it.min } ?: 0.0) - 3
                val hi = (buckets.maxOfOrNull { it.max } ?: 1.0) + 3
                fun x(t: Long) = ((t - start).toFloat() / (end - start)) * size.width
                fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
                val slot = x(start + bucketMs) - x(start)
                val barW = (slot * 0.6f).coerceIn(1.5f, 14.dp.toPx())
                onDrawBehind {
                    drawLine(track, Offset(0f, size.height), Offset(size.width, size.height))
                    for (b in buckets) {
                        val cx = x(b.t) + slot / 2
                        val alpha = if (selected == null || selected == b) 1f else 0.35f
                        drawRoundRect(color.copy(alpha = 0.55f * alpha), Offset(cx - barW / 2, y(b.max)),
                            Size(barW, (y(b.min) - y(b.max)).coerceAtLeast(2f)), CornerRadius(barW / 2))
                        drawCircle(color.copy(alpha = alpha), (barW / 2).coerceAtLeast(2f), Offset(cx, y(b.mean)))
                    }
                }
            },
        )
        EvenAxis(axis, slots = bucketMs < 86_400_000L)
    }
}
