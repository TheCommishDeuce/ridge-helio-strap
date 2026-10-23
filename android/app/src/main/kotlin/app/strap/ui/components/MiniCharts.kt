package app.strap.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import kotlin.math.cos
import kotlin.math.sin

/** A tile's sparkline: the values as one line, the last one dotted. Nothing under two values. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, height: Dp = 34.dp) {
    if (values.size < 2) return
    Spacer(
        modifier.fillMaxWidth().height(height).drawWithCache {
            val lo = values.min()
            val hi = values.max()
            fun x(i: Int) = i * size.width / (values.size - 1)
            fun y(v: Double) = size.height - 3.dp.toPx() - ((v - lo) / (hi - lo).coerceAtLeast(1e-9)).toFloat() * (size.height - 6.dp.toPx())
            val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            onDrawBehind {
                drawPath(line, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(color, 3.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
            }
        },
    )
}

/** A tile's bars: one per value, the last one full strength. */
@Composable
fun MiniBars(values: List<Double?>, color: Color, modifier: Modifier = Modifier, height: Dp = 34.dp) {
    val hi = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return
    Spacer(
        modifier.fillMaxWidth().height(height).drawWithCache {
            val slot = size.width / values.size
            onDrawBehind {
                values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val h = (v / hi).toFloat() * size.height
                    drawRoundRect(color.copy(alpha = if (i == values.lastIndex) 1f else 0.5f), Offset(i * slot + slot * 0.15f, size.height - h),
                        Size(slot * 0.7f, h), CornerRadius(minOf(3.dp.toPx(), slot * 0.3f)))
                }
            }
        },
    )
}

/**
 * A half ring from 0 to [max]: the track, the [low]–[high] band where the value is best (faint
 * green), the value as a thin arc with a ring at its end.
 */
@Composable
fun BandGauge(value: Double, max: Double, low: Double, high: Double, color: Color, band: Color, modifier: Modifier = Modifier) {
    val track = LocalRidgeColors.current.surface3
    val knob = LocalRidgeColors.current.card
    Spacer(
        modifier.fillMaxWidth().height(64.dp).drawWithCache {
            val r = minOf(size.width / 2, size.height) - 8.dp.toPx()
            val center = Offset(size.width / 2, size.height - 2.dp.toPx())
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(2 * r, 2 * r)
            fun deg(v: Double) = 180f * (v / max).toFloat().coerceIn(0f, 1f)
            val end = Math.toRadians((180 + deg(value)).toDouble())
            onDrawBehind {
                drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                drawArc(band, 180f + deg(low), deg(high) - deg(low), false, topLeft, arc, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color, 180f, deg(value), false, topLeft, arc, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val k = Offset(center.x + r * cos(end).toFloat(), center.y + r * sin(end).toFloat())
                drawCircle(knob, 5.5.dp.toPx(), k)
                drawCircle(color, 5.5.dp.toPx(), k, style = Stroke(3.dp.toPx()))
            }
        },
    )
}

/**
 * Readings over a span shorter than a day (a night, a workout): each a faint dot, a rolling
 * mean as the line, the span's own clock under it. Gaps over 20 minutes break the line.
 */
@Composable
fun SpanLine(points: List<Point>, start: Long, end: Long, color: Color) {
    val grid = LocalRidgeColors.current.surface3
    Column {
        Spacer(
            Modifier.fillMaxWidth().height(120.dp).drawWithCache {
                val lo = points.minOf { it.v }
                val hi = points.maxOf { it.v }
                val pad = ((hi - lo) * 0.15).coerceAtLeast(1.0)
                fun x(t: Long) = ((t - start).toFloat() / (end - start)) * size.width
                fun y(v: Double) = size.height - ((v - lo + pad) / (hi - lo + 2 * pad)).toFloat() * size.height
                val line = Path()
                points.forEachIndexed { i, p ->
                    val near = points.subList(maxOf(0, i - 3), minOf(points.size, i + 4)).map { it.v }.average()
                    if (i == 0 || p.t - points[i - 1].t > 20 * 60_000L) line.moveTo(x(p.t), y(near)) else line.lineTo(x(p.t), y(near))
                }
                onDrawBehind {
                    for (f in listOf(0.25f, 0.5f, 0.75f)) drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
                    for (p in points) drawCircle(color.copy(alpha = 0.35f), 1.6.dp.toPx(), Offset(x(p.t), y(p.v)))
                    drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            },
        )
        EvenAxis((0..3).map { clockOf(start + (end - start) * it / 3) })
    }
}

