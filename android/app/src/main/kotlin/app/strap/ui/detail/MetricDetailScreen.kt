package app.strap.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.Bucket
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.DailyBars
import app.strap.ui.components.DayBars
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.Note
import app.strap.ui.components.NoteLine
import app.strap.ui.components.Point
import app.strap.ui.components.RangeChart
import app.strap.ui.components.Span
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricTone
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A metric that has a detail view: its API series name, unit, colours and how far readings may be apart. */
data class DetailMetric(val series: String, val title: String, val unit: String, val tone: MetricTone, val maxGapMs: Long) {
    val isSteps: Boolean get() = series == "steps"
}

private enum class Range(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30) }

private sealed interface Loaded {
    val summary: JSONObject

    data class Day(val points: List<Point>, val sleep: List<Span>, val dayStart: Long, override val summary: JSONObject) : Loaded

    data class Hours(val hours: Map<Int, Double>, override val summary: JSONObject) : Loaded

    data class Buckets(val buckets: List<Bucket>, val start: Long, val end: Long, val bucketMs: Long, val axis: List<String>, override val summary: JSONObject) : Loaded

    data class Days(val days: Map<LocalDate, Double>, val from: LocalDate, override val summary: JSONObject) : Loaded
}

/** The headline: what it is, the number, its unit, and one supporting line. */
private data class Headline(val label: String, val value: String, val line: String)

private data class Stat(val label: String, val value: String, val note: Note? = null)

@Composable
fun MetricDetailScreen(api: ApiClient, metric: DetailMetric, day: LocalDate) {
    var range by rememberSaveable { mutableStateOf(Range.DAY) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrubbed by remember { mutableStateOf<Headline?>(null) }
    LaunchedEffect(range, day) {
        loaded = null
        error = null
        try {
            loaded = load(api, metric, range, day)
        } catch (e: ApiException) {
            error = e.message
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConnectedButtons(Range.entries, range, { it.label }) { range = it }
        val l = loaded
        if (l == null) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant) else CircularProgressIndicator()
            }
            return@Column
        }
        val (headline, stats) = describe(metric, l, day)
        HeadlineBlock(scrubbed ?: headline, metric.unit)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(LocalRidgeColors.current.card).padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            val fmtDay = DateTimeFormatter.ofPattern("EEE d MMM")
            when (l) {
                is Loaded.Day -> DayLineChart(l.points, l.dayStart, metric.tone.accent, metric.maxGapMs, metric.unit, shaded = l.sleep, height = 200.dp,
                    onScrub = { p -> scrubbed = p?.let { Headline("At ${clockOf(it.t)}", "${it.v.roundToInt()}", "") } })
                is Loaded.Hours -> DayBars(l.hours, metric.tone.accent, metric.unit, height = 200.dp,
                    onScrub = { h -> scrubbed = h?.let { Headline("%02d:00 – %02d:00".format(it, it + 1), "%,d".format((l.hours[it] ?: 0.0).roundToInt()), "") } })
                is Loaded.Buckets -> RangeChart(l.buckets, l.start, l.end, l.bucketMs, metric.tone.accent, metric.tone.container, metric.unit, l.axis, height = 200.dp,
                    onScrub = { b ->
                        scrubbed = b?.let {
                            val at = Instant.ofEpochMilli(it.t).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern(if (l.bucketMs >= 86_400_000L) "EEE d MMM" else "EEE HH:mm"))
                            Headline(at, "${it.min.roundToInt()}–${it.max.roundToInt()}", "average ${it.mean.roundToInt()} · peak at ${clockOf(it.tMax)}")
                        }
                    })
                is Loaded.Days -> DailyBars(l.days, l.from, day, metric.tone.accent, { "%,d".format(it.roundToInt()) }, height = 200.dp,
                    onScrub = { d -> scrubbed = d?.let { Headline(it.format(fmtDay), l.days[it]?.let { v -> "%,d".format(v.roundToInt()) } ?: "—", "") } })
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.forEach { s ->
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(LocalRidgeColors.current.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(s.label, style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.value, style = RidgeType.rowValue)
                    if (s.note != null) NoteLine(s.note)
                }
            }
        }
    }
}

@Composable
private fun HeadlineBlock(h: Headline, unit: String) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(h.label, style = RidgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(h.value, style = RidgeType.headline)
            if (unit.isNotEmpty()) Text(" $unit", style = RidgeType.rowValue.copy(fontWeight = RidgeType.label.fontWeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(h.line.ifEmpty { " " }, style = RidgeType.body.copy(fontSize = RidgeType.cardTitle.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The headline and three stat tiles for what was loaded. */
@Composable
private fun describe(metric: DetailMetric, l: Loaded, day: LocalDate): Pair<Headline, List<Stat>> {
    val today = day == LocalDate.now()
    val dayWord = if (today) "Today" else day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    val none = Headline("No readings", "—", "No ${metric.title.lowercase()} readings in this period.")
    val steps = l.summary.optJSONObject("steps")
    fun card(o: JSONObject?, key: String) = o?.optJSONObject(key)?.takeIf { it.has("value") && !it.isNull("value") }
    return when (l) {
        is Loaded.Day -> {
            val peak = l.points.maxByOrNull { it.v }
            val resting = card(l.summary.optJSONObject("heart"), "resting")
            val third = if (metric.series == "hr") {
                val v = resting?.getDouble("value")
                val usual = resting?.optJSONObject("baseline")?.optDouble("median")?.takeIf { !it.isNaN() }
                Stat("Resting", v?.roundToInt()?.toString() ?: "—", changeNote(v?.let { a -> usual?.let { a - it } }, "vs usual", lowerBetter = true))
            } else {
                Stat("Peak at", peak?.let { clockOf(it.t) } ?: "—")
            }
            (peak?.let { Headline("Peak", "${it.v.roundToInt()}", "$dayWord at ${clockOf(it.t)}") } ?: none) to listOf(
                Stat("Low", l.points.minOfOrNull { it.v }?.roundToInt()?.toString() ?: "—"),
                Stat("Average", l.points.takeIf { it.isNotEmpty() }?.map { it.v }?.average()?.roundToInt()?.toString() ?: "—"),
                third,
            )
        }
        is Loaded.Hours -> {
            val total = l.hours.values.sum()
            val busiest = l.hours.maxByOrNull { it.value }?.key
            Headline(if (today) "Total today" else "Total", "%,d".format(total.roundToInt()),
                busiest?.let { "Busiest hour %02d:00 – %02d:00".format(it, it + 1) } ?: "") to listOf(
                Stat("Distance", card(steps, "distance_m")?.let { "%.1f km".format(it.getDouble("value") / 1000) } ?: "—"),
                Stat("Active kcal", card(steps, "active_calories")?.getDouble("value")?.roundToInt()?.toString() ?: "—"),
                Stat("Hours with steps", l.hours.count { it.value > 0 }.toString()),
            )
        }
        is Loaded.Buckets -> {
            val peak = l.buckets.maxByOrNull { it.max }
            val at = peak?.let { Instant.ofEpochMilli(it.tMax).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM")) }
            (peak?.let { Headline("Peak", "${it.max.roundToInt()}", "$at at ${clockOf(it.tMax)}") } ?: none) to listOf(
                Stat("Low", l.buckets.minOfOrNull { it.min }?.roundToInt()?.toString() ?: "—"),
                Stat("Average", l.buckets.takeIf { it.isNotEmpty() }?.map { it.mean }?.average()?.roundToInt()?.toString() ?: "—"),
                Stat("High", peak?.max?.roundToInt()?.toString() ?: "—"),
            )
        }
        is Loaded.Days -> {
            val total = l.days.values.sum()
            val avg = l.days.values.takeIf { it.isNotEmpty() }?.average()
            val best = l.days.maxByOrNull { it.value }
            Headline("Total", "%,d".format(total.roundToInt()), avg?.let { "%,d a day on average".format(it.roundToInt()) } ?: "") to listOf(
                Stat("Average", avg?.let { "%,d".format(it.roundToInt()) } ?: "—"),
                Stat("Best day", best?.let { "%,d".format(it.value.roundToInt()) } ?: "—"),
                Stat("Days", l.days.size.toString()),
            )
        }
    }
}

/** Day = that day; Week and Month end on it. */
private suspend fun load(api: ApiClient, metric: DetailMetric, range: Range, day: LocalDate): Loaded = coroutineScope {
    val summaryCall = async { api.summary(day) }
    val from = day.minusDays(range.days - 1L)
    when {
        range == Range.DAY && metric.isSteps -> {
            val json = api.buckets("steps", day, day, "1h")
            val zone = ZoneId.of(json.getString("timezone"))
            val arr = json.getJSONArray("buckets")
            Loaded.Hours((0 until arr.length()).associate { i -> arr.getJSONObject(i).let { Instant.ofEpochMilli(it.getLong("t")).atZone(zone).hour to it.getDouble("sum") } },
                summaryCall.await())
        }
        range == Range.DAY -> {
            val json = api.daySeries(day, metric.series)
            val zone = ZoneId.of(json.getString("timezone"))
            val arr = json.getJSONObject("series").getJSONArray(metric.series)
            val sleep = json.optJSONArray("sleep")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Span(o.getLong("start"), o.getLong("end")) } } }.orEmpty()
            Loaded.Day(List(arr.length()) { arr.getJSONArray(it).let { p -> Point(p.getLong(0), p.getDouble(1)) } }, sleep,
                day.atStartOfDay(zone).toInstant().toEpochMilli(), summaryCall.await())
        }
        metric.isSteps -> {
            val rows = api.daily("steps_total", from, day).getJSONObject("metrics").getJSONArray("steps_total")
            Loaded.Days((0 until rows.length()).map { rows.getJSONObject(it) }.associate { LocalDate.parse(it.getString("day")) to it.getDouble("value") },
                from, summaryCall.await())
        }
        else -> {
            val bucket = if (range == Range.WEEK) "1h" else "1d"
            val json = api.buckets(metric.series, from, day, bucket)
            val zone = ZoneId.of(json.getString("timezone"))
            val arr = json.getJSONArray("buckets")
            val buckets = List(arr.length()) { i -> arr.getJSONObject(i).let { Bucket(it.getLong("t"), it.getDouble("min"), it.getDouble("max"), it.getDouble("mean"), it.getLong("t_max")) } }
            val axis = if (range == Range.WEEK) (0 until 7).map { from.plusDays(it.toLong()).format(DateTimeFormatter.ofPattern("EEE")) }
            else (0..4).map { from.plusDays(it * 29L / 4).format(DateTimeFormatter.ofPattern("d MMM")) }
            Loaded.Buckets(
                buckets, from.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                if (range == Range.WEEK) 3_600_000L else 86_400_000L, axis, summaryCall.await(),
            )
        }
    }
}
