package app.strap.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.strap.MainActivity
import app.strap.R
import app.strap.sync.SyncService
import app.strap.ui.shell.Tab
import app.strap.ui.theme.DarkMetrics
import app.strap.ui.theme.DarkRidge
import app.strap.ui.theme.LightMetrics
import app.strap.ui.theme.LightRidge
import app.strap.ui.theme.MetricTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// The widgets' share of Ridge's look (D39): the app's cards, its metric colours, light and dark.

/** The three layouts; the system shows the largest that fits the cell. */
internal val SMALL = DpSize(110.dp, 110.dp)
internal val WIDE = DpSize(250.dp, 110.dp)
internal val LARGE = DpSize(250.dp, 220.dp)

internal object WidgetColors {
    val card = ColorProvider(day = LightRidge.card, night = DarkRidge.card)
    val text = ColorProvider(day = Color(0xFF101213), night = Color(0xFFEDEFF0))
    val muted = ColorProvider(day = Color(0xFF4D5053), night = Color(0xFFA2A5A8))
    val track = ColorProvider(day = LightRidge.surface3, night = DarkRidge.surface3)
    /** Amber is for the strap and sync only. */
    val warn = ColorProvider(day = LightRidge.zoneYellow, night = DarkRidge.zoneYellow)
    val energy = tone { it.energyTone }
    val steps = tone { it.stepsTone }
    val sleep = tone { it.sleepTone }

    private fun tone(of: (app.strap.ui.theme.MetricColors) -> MetricTone) = ColorProvider(day = of(LightMetrics).accent, night = of(DarkMetrics).accent)

    /** Recovery's zone colour: ≥ 67 green, 34–66 yellow, below red. */
    fun recovery(value: Int): ColorProvider = ColorProvider(day = LightRidge.zone(value.toDouble()), night = DarkRidge.zone(value.toDouble()))
}

/** Opens the app on [tab]. */
internal fun openTab(context: Context, tab: Tab): Action =
    actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab.name))

/** One sync, as the app's sync button runs it; a widget tap may start the foreground service. */
internal fun syncNow(context: Context): Action = actionStartService(Intent(context, SyncService::class.java), isForegroundService = true)

@Composable
internal fun WidgetCard(onClick: Action, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(WidgetColors.card).clickable(onClick).padding(16.dp)) { content() }
}

@Composable
internal fun Label(text: String, size: TextUnit = 12.sp, color: ColorProvider = WidgetColors.muted, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = color, fontSize = size), maxLines = 1)
}

@Composable
internal fun Figure(text: String, color: ColorProvider, size: TextUnit) {
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Medium), maxLines = 1)
}

/** A number over its name, the way the app's tiles read. */
@Composable
internal fun Metric(value: String, label: String, color: ColorProvider, size: TextUnit, modifier: GlanceModifier = GlanceModifier) {
    Column(modifier) {
        Figure(value, color, size)
        Label(label)
    }
}

/** "Synced 09:10", "Synced yesterday", "Synced Tue" within a week, then "Synced 13 Oct". */
internal fun syncedWords(at: Instant?, now: Instant = Instant.now()): String {
    at ?: return "Not synced"
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return "Synced " + when (ChronoUnit.DAYS.between(day, today)) {
        0L -> at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        1L -> "yesterday"
        in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEE"))
        else -> day.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

/**
 * The title, and at its right the strap's state when it matters: amber "Synced 09:10" that syncs
 * when tapped, "Syncing", or "Pair your strap". [words] off (the small size) leaves only the icon.
 */
@Composable
internal fun TopRow(context: Context, title: String, line: StrapLine?, words: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, GlanceModifier.defaultWeight(), style = TextStyle(color = WidgetColors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        when (line) {
            null -> {}
            StrapLine.Syncing -> Label(if (words) "Syncing…" else "…")
            StrapLine.NotPaired -> Label(if (words) "Pair your strap" else "Pair", color = WidgetColors.warn, modifier = GlanceModifier.clickable(openTab(context, Tab.STRAP)))
            is StrapLine.Stale -> Row(GlanceModifier.clickable(syncNow(context)).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (words) Label(syncedWords(line.at), color = WidgetColors.warn, modifier = GlanceModifier.padding(end = 6.dp))
                Image(ImageProvider(R.drawable.ic_widget_sync), "Sync now, ${syncedWords(line.at).lowercase()}", GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(WidgetColors.warn))
            }
        }
    }
}

/** A white drawing tinted by [color], so it follows light and dark like the text does. */
@Composable
internal fun Drawing(bitmap: Bitmap, description: String, color: ColorProvider, modifier: GlanceModifier) {
    Image(ImageProvider(bitmap), description, modifier, contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(color))
}

internal fun px(context: Context, dp: Dp): Int = (dp.value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

/** Energy through the day: the line, a faint fill under it, a gap where there is none, the day's full width. */
internal fun energyBitmap(points: List<Pair<Long, Double>>, start: Long, end: Long, w: Int, h: Int, stroke: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    if (points.isEmpty() || end <= start) return bmp
    val pad = stroke
    fun x(t: Long) = ((t - start).toFloat() / (end - start)) * w
    fun y(v: Double) = pad + (1 - (v / 100).toFloat().coerceIn(0f, 1f)) * (h - 2 * pad)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(22, 255, 255, 255); style = Paint.Style.FILL }
    val base = Paint().apply { color = android.graphics.Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    canvas.drawLine(0f, h - 1f, w.toFloat(), h - 1f, base)
    // A gap of over 11 minutes (Energy has no value there) breaks the line, as the app's chart does.
    for (run in points.runs { a, b -> b.first - a.first <= 11 * 60_000L }) {
        val path = Path().apply { run.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
        val area = Path(path).apply { lineTo(x(run.last().first), h.toFloat()); lineTo(x(run.first().first), h.toFloat()); close() }
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
    return bmp
}

/** Seven days' bars against the goal line; today's at full strength, the others fainter. */
internal fun weekBitmap(values: List<Double?>, goal: Double, w: Int, h: Int, gap: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val top = maxOf(goal, values.maxOf { it ?: 0.0 }) * 1.08
    val bar = (w - gap * (values.size - 1)) / values.size
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    values.forEachIndexed { i, v ->
        if (v == null || v <= 0) return@forEachIndexed
        paint.color = if (i == values.lastIndex) android.graphics.Color.WHITE else android.graphics.Color.argb(115, 255, 255, 255)
        val left = i * (bar + gap)
        val height = (v / top).toFloat() * h
        canvas.drawRoundRect(left, h - maxOf(height, bar / 4), left + bar, h.toFloat(), bar / 4, bar / 4, paint)
    }
    val y = h - (goal / top).toFloat() * h
    val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = maxOf(1f, gap / 4); pathEffect = DashPathEffect(floatArrayOf(gap, gap), 0f)
    }
    canvas.drawLine(0f, y, w.toFloat(), y, dash)
    return bmp
}

/** Splits a list into runs where each neighbour pair satisfies [together]. */
private fun <T> List<T>.runs(together: (T, T) -> Boolean): List<List<T>> {
    val out = ArrayList<MutableList<T>>()
    for (item in this) {
        val last = out.lastOrNull()
        if (last != null && together(last.last(), item)) last += item else out += mutableListOf(item)
    }
    return out
}

internal fun hm(minutes: Double): String = "%dh %02dm".format((minutes / 60).toInt(), (minutes % 60).roundToInt().coerceAtMost(59))
