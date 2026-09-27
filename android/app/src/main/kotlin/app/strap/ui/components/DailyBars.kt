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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalMetricColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One bar per day over [first, last]; days with no value draw nothing (a gap, never zero).
 * [target] draws a dashed reference line (e.g. sleep need). [format] renders the scrub readout.
 */
@Composable
fun DailyBars(
    values: Map<LocalDate, Double>,
    first: LocalDate,
    last: LocalDate,
    color: Color,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    target: Double? = null,
) {
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    val track = LocalMetricColors.current.track
    var scrub by remember { mutableStateOf<Float?>(null) }
    val selected = scrub?.let { days[(it * days.size).toInt().coerceIn(0, days.lastIndex)] }
    val label = selected?.let { d -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " · " + (values[d]?.let(format) ?: "no data") }
    Column(modifier) {
        ValueLabels(label, null, null)
        Spacer(
            Modifier.fillMaxWidth().height(120.dp).scrub { scrub = it }.drawWithCache {
                val max = maxOf(values.values.maxOrNull() ?: 1.0, target ?: 0.0) * 1.1
                val slot = size.width / days.size
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                onDrawBehind {
                    days.forEachIndexed { i, d ->
                        val v = values[d] ?: return@forEachIndexed
                        val h = (v / max).toFloat() * size.height
                        val alpha = if (selected == null || selected == d) 1f else 0.4f
                        drawRoundRect(color.copy(alpha = alpha), Offset(i * slot + slot * 0.2f, size.height - h), Size(slot * 0.6f, h), CornerRadius(3.dp.toPx()))
                    }
                    target?.let {
                        val y = size.height - (it / max).toFloat() * size.height
                        drawLine(track.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = dash)
                    }
                    drawLine(track, Offset(0f, size.height), Offset(size.width, size.height))
                }
            },
        )
        val step = maxOf(1, days.size / 4)
        EvenAxis(days.filterIndexed { i, _ -> i % step == 0 }.map { it.format(DateTimeFormatter.ofPattern(if (days.size <= 7) "EEE" else "d MMM")) }, slots = days.size <= 7)
    }
}
