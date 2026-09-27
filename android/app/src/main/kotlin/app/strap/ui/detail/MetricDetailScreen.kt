package app.strap.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.Bucket
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.Point
import app.strap.ui.components.RangeChart
import app.strap.ui.components.clockOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A metric that has a detail view: its API series name, unit, colour and how far readings may be apart. */
data class DetailMetric(val series: String, val title: String, val unit: String, val color: Color, val maxGapMs: Long)

private enum class Range(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30) }

private sealed interface Loaded {
    data class Day(val points: List<Point>, val dayStart: Long) : Loaded

    data class Buckets(val buckets: List<Bucket>, val start: Long, val end: Long, val bucketMs: Long, val axis: List<String>) : Loaded
}

@Composable
fun MetricDetailScreen(api: ApiClient, metric: DetailMetric, day: LocalDate) {
    var range by remember { mutableStateOf(Range.DAY) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(range, day) {
        loaded = null
        error = null
        try {
            loaded = load(api, metric, range, day)
        } catch (e: ApiException) {
            error = e.message
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Range.entries.forEachIndexed { i, r ->
                SegmentedButton(selected = range == r, onClick = { range = r }, shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size)) { Text(r.label) }
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val l = loaded) {
                    null -> if (error != null) Text(error!!) else CircularProgressIndicator()
                    is Loaded.Day -> {
                        Stats(metric, l.points.maxByOrNull { it.v }?.let { it.v to it.t }, l.points.minOfOrNull { it.v }, l.points.map { it.v }.average().takeIf { l.points.isNotEmpty() })
                        DayLineChart(l.points, l.dayStart, metric.color, metric.maxGapMs, metric.unit)
                    }
                    is Loaded.Buckets -> {
                        val peak = l.buckets.maxByOrNull { it.max }
                        Stats(metric, peak?.let { it.max to it.tMax }, l.buckets.minOfOrNull { it.min }, l.buckets.map { it.mean }.average().takeIf { l.buckets.isNotEmpty() })
                        RangeChart(l.buckets, l.start, l.end, l.bucketMs, metric.color, metric.unit, l.axis)
                    }
                }
            }
        }
        Text("Press and drag on the chart to read any point. Bars show each period's lowest to highest reading; the dot is its average.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stats(metric: DetailMetric, peak: Pair<Double, Long>?, low: Double?, mean: Double?) {
    if (peak == null) {
        Text("No ${metric.title.lowercase()} readings in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val day = Instant.ofEpochMilli(peak.second).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM"))
    Text("Peak ${peak.first.roundToInt()} ${metric.unit}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("$day at ${clockOf(peak.second)} · low ${low?.roundToInt()} · average ${mean?.roundToInt()}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Day = that day; Week and Month end on it. */
private suspend fun load(api: ApiClient, metric: DetailMetric, range: Range, today: LocalDate): Loaded {
    if (range == Range.DAY) {
        val json = api.daySeries(today, metric.series)
        val zone = ZoneId.of(json.getString("timezone"))
        val arr = json.getJSONObject("series").getJSONArray(metric.series)
        return Loaded.Day(List(arr.length()) { arr.getJSONArray(it).let { p -> Point(p.getLong(0), p.getDouble(1)) } }, today.atStartOfDay(zone).toInstant().toEpochMilli())
    }
    val from = today.minusDays(range.days - 1L)
    val bucket = if (range == Range.WEEK) "1h" else "1d"
    val json = api.buckets(metric.series, from, today, bucket)
    val zone = ZoneId.of(json.getString("timezone"))
    val arr = json.getJSONArray("buckets")
    val buckets = List(arr.length()) { i -> arr.getJSONObject(i).let { Bucket(it.getLong("t"), it.getDouble("min"), it.getDouble("max"), it.getDouble("mean"), it.getLong("t_max")) } }
    val axis = if (range == Range.WEEK) (0 until 7).map { from.plusDays(it.toLong()).format(DateTimeFormatter.ofPattern("EEE")) }
    else (0..4).map { from.plusDays(it * 29L / 4).format(DateTimeFormatter.ofPattern("d MMM")) }
    return Loaded.Buckets(
        buckets, from.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        if (range == Range.WEEK) 3_600_000L else 86_400_000L, axis,
    )
}
