package app.strap.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricTone
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
        modifier.fillMaxWidth().cardSurface(RoundedCornerShape(radius))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = body,
    )
}

/** A card's surface: the card colour, and in the light theme a soft shadow under it (Bevel's cards float). */
@Composable
fun Modifier.cardSurface(shape: Shape): Modifier {
    val r = LocalRidgeColors.current
    val light = r.bg.luminance() > 0.5f
    return (if (light) shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.08f)) else this)
        .clip(shape).background(r.card)
}

/** A detail screen's top: the metric's tone fading into the background over the first 320 dp. */
@Composable
fun Modifier.tinted(tone: MetricTone): Modifier {
    val top = tone.container
    val bg = LocalRidgeColors.current.bg
    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), bg), startY = 0f, endY = 320.dp.toPx()))
    }
}

/** A summary in words: an icon chip, a title and a muted paragraph (Bevel's insight cards). */
@Composable
fun InsightCard(icon: ImageVector, tone: MetricTone, title: String, text: String, onClick: (() -> Unit)? = null) {
    RidgeCard(spacing = 6.dp, onClick = onClick) {
        CardHeader(title, chevron = onClick != null, icon = icon, tone = tone)
        Text(text, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A row that opens to show more: icon chip, title over a coloured sub-line, a chevron that
 * turns when [expanded]; [content] below it while open.
 */
@Composable
fun ExpandableRow(icon: ImageVector, tone: MetricTone, title: String, sub: String, subColor: Color, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, tone.container, tone.accent, size = 36.dp, iconSize = 18.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = RidgeType.rowTitle)
                Text(sub, style = RidgeType.body, color = subColor)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, if (expanded) "Show less" else "Show more", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(expanded) { Column(Modifier.padding(start = 48.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

/** A horizontal slider: a track, the good range shaded, a dot where [value] stands between [min] and [max]. */
@Composable
fun RangeSlider(value: Double, min: Double, max: Double, goodLow: Double, goodHigh: Double, color: Color, modifier: Modifier = Modifier) {
    val track = LocalRidgeColors.current.surface3
    val good = LocalRidgeColors.current.good.copy(alpha = 0.35f)
    Spacer(
        modifier.fillMaxWidth().height(12.dp).drawBehind {
            fun x(v: Double) = ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f) * size.width
            val y = size.height / 2
            drawLine(track, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(good, Offset(x(goodLow), y), Offset(x(goodHigh), y), 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, 6.dp.toPx(), Offset(x(value), y))
        },
    )
}

/**
 * A card's head, the same on every card: the metric's icon in its tone (when given), the
 * title, an optional right-hand detail, an info action, and a chevron when the card opens something.
 */
@Composable
fun CardHeader(
    title: String,
    detail: String? = null,
    info: Info? = null,
    chevron: Boolean = false,
    icon: ImageVector? = null,
    tone: MetricTone? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null && tone != null) {
            IconCircle(icon, tone.container, tone.accent, size = 28.dp, iconSize = 16.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = RidgeType.cardTitle, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        info?.let { InfoButton(it, Modifier.padding(start = 4.dp).size(32.dp)) }
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card's headline: the number, its unit, and its status (a change or a verdict) on the right. */
@Composable
fun CardValue(value: String, unit: String? = null, status: Note? = null, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(value, style = RidgeType.cardValue, color = valueColor)
        unit?.let { Subtle("  $it", Modifier.padding(bottom = 5.dp), RidgeType.caption) }
        Spacer(Modifier.weight(1f))
        status?.let { StatusText(it, Modifier.padding(bottom = 5.dp, start = 8.dp)) }
    }
}

/** A status in its colour behind a dot: "● +6 vs usual", "● In range". */
@Composable
fun StatusText(note: Note, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(note.color))
        Text("  ${note.text}", style = RidgeType.change, color = note.color)
    }
}

/** A card's footer: a hairline, then up to four small figures side by side. */
@Composable
fun CardFoot(items: List<Pair<String, String>>) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LocalRidgeColors.current.surface3))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            items.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = RidgeType.footValue)
                }
            }
        }
    }
}

/** "STRAP": marks a figure as the strap's own, not Ridge's. */
@Composable
fun StrapTag() {
    Text("STRAP", style = RidgeType.unit, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(LocalRidgeColors.current.surface3).padding(horizontal = 4.dp, vertical = 1.dp))
}

/** A half-width tile in the card language: label, value and unit, status, an optional small visual. */
@Composable
fun Tile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    status: Note? = null,
    strap: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Where the value stands against its usual, 0 (far below) to 1 (far above): the vertical slider on the right. */
    slider: Float? = null,
    visual: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.cardSurface(RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onClick != null && slider == null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = RidgeType.tileValue)
                unit?.let { Subtle(" $it", Modifier.padding(bottom = 3.dp), RidgeType.caption) }
                if (strap) { Spacer(Modifier.width(8.dp)); Box(Modifier.padding(bottom = 4.dp)) { StrapTag() } }
            }
            StatusText(status ?: Note(" ", Color.Transparent))
            visual?.invoke()
        }
        slider?.let { VerticalSlider(it, status?.color?.takeIf { c -> c != Color.Transparent } ?: MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The vitals' slider (Bevel's): a slim vertical track, a mark where the value sits against its usual. */
@Composable
private fun VerticalSlider(position: Float, color: Color) {
    val track = LocalRidgeColors.current.surface3
    Spacer(
        Modifier.padding(start = 10.dp).width(6.dp).height(52.dp).drawBehind {
            val w = size.width
            drawRoundRect(track, cornerRadius = CornerRadius(w / 2))
            val mark = 14.dp.toPx()
            val y = (1 - position.coerceIn(0.08f, 0.92f)) * size.height - mark / 2
            drawRoundRect(color, Offset(0f, y.coerceIn(0f, size.height - mark)), Size(w, mark), CornerRadius(w / 2))
        },
    )
}

/**
 * Where [value] sits against [usual] for a [VerticalSlider]: the middle at the usual, ±20 % at
 * the ends, or ±[span] in the value's own units when given (skin temperature: ±1 °C).
 */
fun sliderPosition(value: Double?, usual: Double?, span: Double? = null): Float? {
    if (value == null || usual == null || usual == 0.0) return null
    val rel = if (span != null) (value - usual) / (2 * span) else 2.5 * (value - usual) / usual
    return (0.5 + rel).toFloat().coerceIn(0f, 1f)
}

/** Tiles two to a row, 10 dp apart. */
@Composable
fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { it(Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A section heading outside the cards (16/500), with an optional action on the right. */
@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RidgeType.sectionTitle, modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (action != null && onAction != null) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAction).padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * A series' course: the second half of [values] against the first. Within [steady] (relative)
 * it is steady, in green; otherwise its direction, green when that is the better way for the
 * metric ([higherBetter]), yellow when it is the worse way, muted for a metric that is neither
 * (null: load, weight). Under six points it is still building.
 */
@Composable
fun trendNote(values: List<Double>, higherBetter: Boolean?, steady: Double = 0.03, up: String = "Rising", down: String = "Falling", still: String = "Steady"): Note {
    val r = LocalRidgeColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (values.size < 6) return Note("Building", muted)
    val half = values.size / 2
    val a = values.take(half).average()
    val b = values.drop(half).average()
    val change = if (a == 0.0) 0.0 else (b - a) / a
    if (kotlin.math.abs(change) < steady) return Note(still, r.good)
    val rising = change > 0
    val color = when (higherBetter) {
        null -> muted
        rising -> r.good
        else -> r.warn
    }
    return Note(if (rising) up else down, color)
}

/** A section heading outside the cards (16/500, as [SectionTitle]) with an optional right-hand note. */
@Composable
fun SectionLabel(text: String, note: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = RidgeType.sectionTitle, modifier = Modifier.weight(1f))
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

/** A duration as people say it: "46 min" under an hour, "1h 05m" from there. */
fun duration(minutes: Double): String = if (minutes < 59.5) "${minutes.roundToInt()} min" else hm(minutes)

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
