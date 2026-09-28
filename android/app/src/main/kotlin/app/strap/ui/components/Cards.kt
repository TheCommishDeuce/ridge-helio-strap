package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlin.math.roundToInt

/** The card every screen uses: `card` colour, 20 dp corners, 16 dp padding. */
@Composable
fun RidgeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    radius: Dp = 20.dp,
    padding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(LocalRidgeColors.current.card)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = body,
    )
}

/** A card's title row: title (16/500), optional right-hand detail, optional info action. */
@Composable
fun CardHeader(title: String, detail: String? = null, info: Info? = null, chevron: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RidgeType.cardTitle, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        info?.let { InfoButton(it, Modifier.padding(start = 4.dp).size(32.dp)) }
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "YOUR DAY"-style section label (12/500, uppercase, tracked) with optional right-hand note. */
@Composable
fun SectionLabel(text: String, note: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = RidgeType.section, modifier = Modifier.weight(1f))
        note?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A list group's heading outside the cards: title, optional sub-line, optional info action. */
@Composable
fun GroupHeader(title: String, sub: String? = null, info: Info? = null, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = RidgeType.cardTitle)
                trailing?.let { Text("  $it", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            sub?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        info?.let { InfoButton(it) }
    }
}

@Composable
fun InfoButton(info: Info, modifier: Modifier = Modifier) {
    val show = LocalInfo.current
    IconButton(onClick = { show(info) }, modifier = modifier) {
        Icon(Icons.Outlined.Info, "About ${info.title.lowercase()}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Subtle(text: String, modifier: Modifier = Modifier, style: TextStyle = RidgeType.body) {
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A labelled row: left label, right value. */
@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = RidgeType.label, color = valueColor)
    }
}

fun hm(minutes: Double): String = "%dh %02dm".format((minutes / 60).toInt(), (minutes % 60).roundToInt().coerceAtMost(59))

/** A plain proportion bar on a `surface3` track. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(LocalRidgeColors.current.surface3)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** A 40 dp tonal circle holding an icon (list leading slot). */
@Composable
fun IconCircle(icon: ImageVector, container: Color, tint: Color, size: Dp = 40.dp, iconSize: Dp = 22.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Grouped list corners: 20 dp on the group's outer edges, 4 dp between items. */
fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) 20.dp else 4.dp
    val bottom = if (index == count - 1) 20.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Items of one group, 2 dp apart; [item] gets its index and the shape to use. */
@Composable
fun <T> Grouped(items: List<T>, item: @Composable (T, Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, t -> item(t, groupShape(i, items.size)) }
    }
}

/** One grouped-list item: optional leading, headline over supporting, optional trailing. */
@Composable
fun ListRow(
    shape: Shape,
    headline: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    headlineStyle: TextStyle = RidgeType.rowTitle,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(LocalRidgeColors.current.card)
            .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(headline, style = headlineStyle)
            supporting?.let { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke(this)
    }
}
