package app.strap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.LocalDate
import java.time.format.TextStyle

/** Seven days, each a small recovery ring around its date; tapping selects the day. */
@Composable
fun WeekStrip(days: List<LocalDate>, recovery: Map<LocalDate, Double>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d -> DayCell(d, recovery[d], d == selected) { onSelect(d) } }
    }
}

@Composable
private fun DayCell(day: LocalDate, recovery: Double?, selected: Boolean, onClick: () -> Unit) {
    val r = LocalRidgeColors.current
    val arc = LocalMetricColors.current.recovery
    val bg by animateColorAsState(if (selected) r.surface3 else Color.Transparent, tween(200))
    val locale = LocalLocale.current.platformLocale
    val letter = day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
    Column(
        Modifier.width(48.dp).clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 8.dp)
            .semantics {
                this.selected = selected
                contentDescription = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " ${day.dayOfMonth}" +
                    (recovery?.let { ", recovery ${it.toInt()}%" } ?: "")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(letter, style = RidgeType.change, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(34.dp)) {
                // r 14.5, stroke 3 in a 34-unit box
                val s = size.minDimension / 34f
                val rad = 14.5f * s
                val tl = Offset(center.x - rad, center.y - rad)
                val sz = Size(2 * rad, 2 * rad)
                drawArc(r.surface4, 0f, 360f, false, tl, sz, style = Stroke(3f * s))
                recovery?.let { drawArc(arc, -90f, 360f * (it / 100).toFloat().coerceIn(0f, 1f), false, tl, sz, style = Stroke(3f * s, cap = StrokeCap.Round)) }
            }
            Text("${day.dayOfMonth}", fontSize = 13.sp, style = RidgeType.body)
        }
    }
}
