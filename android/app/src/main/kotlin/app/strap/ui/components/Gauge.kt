package app.strap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlin.math.abs
import kotlin.math.roundToInt

/** A coloured one-liner under a number: a change ("+8 vs week") or a target note. */
data class Note(val text: String, val color: Color)

/**
 * "+8 vs week", "−2 vs usual", "±0 vs usual" (the prototype's signs): green when the change is
 * good or too small to matter (under [meaningful]), yellow when it goes the worse way — worth a
 * look, never an alarm — and muted for a change that is neither ([neutral]: stress, heart rate
 * through the day). [lowerBetter] for resting HR, breathing, skin temperature.
 */
@Composable
fun changeNote(
    delta: Double?,
    suffix: String,
    digits: Int = 0,
    lowerBetter: Boolean = false,
    neutral: Boolean = false,
    magnitude: ((Double) -> String)? = null,
    /** Below this size a change is "about usual". */
    meaningful: Double = 0.5 / Math.pow(10.0, digits.toDouble()),
): Note? {
    if (delta == null || delta.isNaN()) return null
    val r = LocalRidgeColors.current
    val small = abs(delta) < meaningful
    val good = if (lowerBetter) delta < 0 else delta > 0
    val color = when {
        neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        small || good -> r.good
        else -> r.warn
    }
    val size = magnitude?.invoke(abs(delta)) ?: if (digits == 0) "${abs(delta).roundToInt()}" else "%.${digits}f".format(abs(delta))
    val sign = if (size.trim('0', '.', ',').isEmpty()) "±" else if (delta < 0) "−" else "+"
    return Note("$sign$size $suffix", color)
}

/**
 * The ring: a full circle of track, the value running clockwise from 12 o'clock with round
 * ends (Bevel's rings). [fraction] null = withheld — an empty track and "—", never a zero.
 */
@Composable
fun Gauge(value: String, unit: String?, fraction: Float?, color: Color, size: Dp, valueSize: TextUnit, stroke: Float = 7f) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fraction) { progress.animateTo((fraction ?: 0f).coerceIn(0f, 1f), tween(700)) }
    val track = LocalRidgeColors.current.surface3
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val scale = this.size.minDimension / 100f
            val r = 42f * scale
            val w = stroke * scale
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(2 * r, 2 * r)
            val style = Stroke(w, cap = StrokeCap.Round)
            val sweep = if (fraction == null) 0f else 360f * progress.value
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(w))
            if (sweep > 0.5f) drawArc(color, -90f, sweep, false, topLeft, arc, style = style)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = valueSize, lineHeight = valueSize, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = RidgeType.body.fontWeight))
            unit?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
    }
}

/** Ring + label (13/500, muted) + change line; the whole block is the tap target. */
@Composable
fun GaugeBlock(
    value: String,
    unit: String?,
    fraction: Float?,
    color: Color,
    label: String,
    note: Note?,
    size: Dp,
    valueSize: TextUnit,
    stroke: Float = 7f,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Gauge(value, unit, fraction, color, size, valueSize, stroke)
        Text(label, style = RidgeType.ringLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NoteLine(note)
    }
}

@Composable
fun NoteLine(note: Note?, modifier: Modifier = Modifier) {
    // Always takes its line, so gauges with and without a change stay aligned.
    Text(note?.text ?: " ", style = RidgeType.change, color = note?.color ?: Color.Unspecified, modifier = modifier, textAlign = TextAlign.Center)
}

/** A hero's side figure: value 24/400, label 11/500 uppercase, then its note. */
@Composable
fun SideStat(value: String, label: String, note: Note?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = RidgeType.sideValue)
        Text(label.uppercase(), style = RidgeType.change.copy(letterSpacing = 0.8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        NoteLine(note)
    }
}

/** The hero row (Sleep, Activity, Recovery, Strap): side stat · 168 dp gauge · side stat. */
@Composable
fun HeroRow(
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
    gauge: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        left(Modifier.weight(1f).padding(bottom = 36.dp))
        gauge()
        right(Modifier.weight(1f).padding(bottom = 36.dp))
    }
}

/** The standard hero gauge: 168 dp, 38 sp value. */
@Composable
fun HeroGauge(value: String, unit: String?, fraction: Float?, color: Color, label: String, note: Note?, onClick: (() -> Unit)? = null) =
    GaugeBlock(value, unit, fraction, color, label, note, 168.dp, 38.sp, onClick = onClick)
