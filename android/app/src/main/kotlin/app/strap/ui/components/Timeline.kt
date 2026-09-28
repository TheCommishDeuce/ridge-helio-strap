package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricTone
import app.strap.ui.theme.RidgeType

/** One moment of the day: when, what (icon in its metric's colours), and an optional value. */
data class Moment(
    val at: Long,
    val time: String,
    val icon: ImageVector,
    val tone: MetricTone,
    val title: String,
    val subtitle: String,
    val meta: String? = null,
    val onClick: () -> Unit,
)

/** "Your day": moments joined by a 2 dp connector, inside one card. */
@Composable
fun Timeline(moments: List<Moment>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalRidgeColors.current.card).padding(vertical = 8.dp)) {
        moments.forEachIndexed { i, m -> TimelineRow(m, first = i == 0, last = i == moments.lastIndex) }
    }
}

@Composable
private fun TimelineRow(m: Moment, first: Boolean, last: Boolean) {
    val line = LocalRidgeColors.current.surface3
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp).clickable(onClick = m.onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.time, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Box(
            Modifier.padding(horizontal = 10.dp).width(28.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val top = if (first) size.height / 2 else 0f
                val bottom = if (last) size.height / 2 else size.height
                if (bottom > top) drawLine(line, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
            },
            contentAlignment = Alignment.Center,
        ) { IconCircle(m.icon, m.tone.container, m.tone.onContainer, size = 28.dp, iconSize = 16.dp) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(m.title, style = RidgeType.rowTitle)
            Text(m.subtitle, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        m.meta?.let { Text(it, fontSize = 17.sp, style = RidgeType.body, color = m.tone.accent, modifier = Modifier.padding(start = 8.dp, end = 4.dp)) }
    }
}
