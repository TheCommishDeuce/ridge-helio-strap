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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import app.strap.ui.theme.LocalRidgeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One bar per day over [first, last]; days with no value draw nothing (a gap, never zero).
 * [target] draws a dashed reference line (e.g. sleep need); [dim] fades bars that miss it.
 * [highlightLast] draws the latest day at full strength and the rest at half. [format]
 * renders the scrub readout, or [onScrub] receives the day instead.
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
    dim: ((Double) -> Boolean)? = null,
    highlightLast: Boolean = false,
    height: Dp = 120.dp,
    onScrub: ((LocalDate?) -> Unit)? = null,
) {
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    val grid = LocalRidgeColors.current.surface3
    val dash = MaterialTheme.colorScheme.onSurfaceVariant
    var scrub by remember { mutableStateOf<Float?>(null) }
    fun dayAt(f: Float) = days[(f * days.size).toInt().coerceIn(0, days.lastIndex)]
    val selected = scrub?.let(::dayAt)
    val label = selected?.let { d -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " · " + (values[d]?.let(format) ?: "no data") }
    Column(modifier) {
        if (onScrub == null) ValueLabels(label, null, null)
        Spacer(
            Modifier.fillMaxWidth().height(height).scrub { f -> scrub = f; onScrub?.invoke(f?.let(::dayAt)) }.drawWithCache {
                val max = maxOf(values.values.maxOrNull() ?: 1.0, target ?: 0.0) * 1.08
                val slot = size.width / days.size
                val w = minOf(slot * 0.78f, 28.dp.toPx())
                val effect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                onDrawBehind {
                    days.forEachIndexed { i, d ->
                        val v = values[d] ?: return@forEachIndexed
                        val h = (v / max).toFloat() * size.height
                        val alpha = when {
                            selected != null -> if (selected == d) 1f else 0.4f
                            highlightLast -> if (i == days.lastIndex) 1f else 0.5f
                            dim?.invoke(v) == true -> 0.55f
                            else -> 1f
                        }
                        bar(color.copy(alpha = alpha), i * slot + (slot - w) / 2, size.height - h, w, size.height)
                    }
                    target?.let {
                        val y = size.height - (it / max).toFloat() * size.height
                        drawLine(dash, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = effect)
                    }
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
            },
        )
        if (days.size <= 7) {
            EvenAxis(days.map { it.format(DateTimeFormatter.ofPattern("EEE")) }, slots = true)
        } else {
            val step = maxOf(1, (days.size - 1) / 3)
            EvenAxis(days.filterIndexed { i, _ -> i % step == 0 }.map { it.format(DateTimeFormatter.ofPattern("d MMM")) })
        }
    }
}
