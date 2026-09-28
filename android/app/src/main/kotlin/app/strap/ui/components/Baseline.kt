package app.strap.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlin.math.abs
import kotlin.math.max

/**
 * Where a value sits against its usual: [usual] (the tick), an optional usual range [low]–[high]
 * (the band), and whether the value counts as inside it (green dot) or outside (yellow).
 */
data class Track(val value: Double, val usual: Double, val low: Double?, val high: Double?, val inside: Boolean)

/**
 * A recovery factor as the server reports it: value, baseline median and z. The usual range
 * is the baseline ± one robust SD, recovered from z; without a usable z only the tick shows.
 */
fun factorTrack(value: Double, baseline: Double, z: Double?): Track {
    val sd = if (z != null && abs(z) >= 0.05) abs(value - baseline) / abs(z) else null
    return Track(value, baseline, sd?.let { baseline - it }, sd?.let { baseline + it }, inside = z == null || abs(z) <= 1.0)
}

/** One baseline row: label, "usual 52", value; the track below; optional footer line. */
@Composable
fun BaselineRow(
    label: String,
    usual: String,
    value: String,
    track: Track?,
    chip: String? = null,
    footer: Pair<String, String>? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = RidgeType.label, modifier = Modifier.weight(1f))
            if (chip != null) {
                Text(chip, style = RidgeType.caption, color = muted,
                    modifier = Modifier.border(1.dp, LocalRidgeColors.current.surface4, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            } else {
                Text(usual, style = RidgeType.caption, color = muted)
            }
            Text(value, style = RidgeType.rowValue, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 58.dp).padding(start = 12.dp))
        }
        track?.let { TrackLine(it) }
        footer?.let { (l, r) ->
            Row { Text(l, style = RidgeType.caption, color = muted, modifier = Modifier.weight(1f)); Text(r, style = RidgeType.caption, color = muted) }
        }
    }
}

/** Rows separated by 1 dp hairlines. */
@Composable
fun BaselineRows(rows: List<@Composable () -> Unit>) {
    rows.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        row()
    }
}

@Composable
private fun TrackLine(t: Track) {
    val r = LocalRidgeColors.current
    val card = r.card
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (t.inside) r.zoneGreen else r.zoneYellow
    Spacer(
        Modifier.fillMaxWidth().height(14.dp).drawWithCache {
            // Centre the usual value; half-width covers the value and the band with room to spare.
            val half = max(max(abs(t.value - t.usual), t.high?.let { it - t.usual } ?: 0.0), abs(t.usual) * 0.02 + 1e-6) * 1.8
            fun x(v: Double) = (((v - t.usual) / half + 1) / 2).toFloat().coerceIn(0.03f, 0.97f) * size.width
            val cy = size.height / 2
            onDrawBehind {
                drawRoundRect(r.surface3, Offset(0f, cy - 1.dp.toPx()), Size(size.width, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                if (t.low != null && t.high != null) {
                    val l = x(t.low)
                    drawRoundRect(r.surface4, Offset(l, cy - 2.dp.toPx()), Size(x(t.high) - l, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                val tx = x(t.usual)
                drawRoundRect(tickColor, Offset(tx - 1.dp.toPx(), cy - 6.dp.toPx()), Size(2.dp.toPx(), 12.dp.toPx()), CornerRadius(1.dp.toPx()))
                val dx = x(t.value)
                drawCircle(card, 9.dp.toPx(), Offset(dx, cy))
                drawCircle(dot, 6.dp.toPx(), Offset(dx, cy))
            }
        },
    )
}
