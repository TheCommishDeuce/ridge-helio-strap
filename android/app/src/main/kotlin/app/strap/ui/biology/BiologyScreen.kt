package app.strap.ui.biology

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import app.strap.ui.components.DailyChartSheet
import app.strap.ui.components.ExpandableRow
import app.strap.ui.components.RangeSlider
import app.strap.ui.components.tinted
import app.strap.ui.components.trendNote
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import app.strap.ui.theme.MetricTone
import app.strap.ui.theme.RidgeColors
import androidx.compose.ui.unit.sp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.sync.Owner
import app.strap.ui.Loading
import app.strap.ui.components.IconCircle
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.Sparkline
import app.strap.ui.components.Subtle
import app.strap.ui.components.hm
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricColors
import app.strap.ui.theme.RidgeType
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import strap.science.BioAge
import strap.science.Card
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A biomarker under the age: a daily series and how to print it. */
private class Marker(val label: String, val unit: String, val values: List<Double>, val tone: (MetricColors) -> Color, val format: String,
    /** Which way the trend is good (null: neither, as weight). */
    val higherBetter: Boolean?,
    /** The daily rows behind [values], when the marker is a daily metric (its chart opens from the row). */
    val daily: Map<LocalDate, Double>? = null)

private class BiologyData(
    val day: LocalDate,
    /** Biological age per day over the last month, oldest first. */
    val history: List<Pair<LocalDate, BioAge.Estimate>>,
    val vo2max: Double?,
    val rhrWeek: Double?,
    val withheld: String?,
    val markers: List<Marker>,
    val sex: String?,
)

private fun ageOn(dob: LocalDate, day: LocalDate) = ChronoUnit.DAYS.between(dob, day) / 365.2425

private suspend fun load(api: Reader, owner: Owner, today: LocalDate): BiologyData {
    val profile = owner.profile()
    val daily = listOf("vo2max_estimate", "hrv_sleep_avg", "rhr_daily", "respiratory_rate_sleep", "spo2_overnight")
        .associateWith { m -> api.daily(m, today.minusDays(59), today).associate { it.day to it.value } }
    val tst = api.daily("sleep_health_score_4dim", today.minusDays(59), today)
        .mapNotNull { r -> r.flags["tst_min"]?.jsonPrimitive?.doubleOrNull?.let { r.day to it / 60 } }.toMap()
    val vo2 = daily.getValue("vo2max_estimate")
    val dob = profile?.dob
    // Each day's estimate: the VO₂max of that day (or the latest before it) and the 14 nights ending on it (seven or more).
    fun estimate(d: LocalDate): BioAge.Estimate? {
        dob ?: return null
        val v = vo2.filterKeys { it <= d }.maxByOrNull { it.key }?.value ?: return null
        val nights = (0..13).mapNotNull { tst[d.minusDays(it.toLong())] }.takeIf { it.size >= 7 } ?: return null
        return BioAge.estimate(ageOn(dob, d), profile.sex, v, nights.average())
    }
    val history = (29 downTo 0).map { today.minusDays(it.toLong()) }.mapNotNull { d -> estimate(d)?.let { d to it } }
    val withheld = when {
        dob == null -> "Biological age needs your date of birth in Settings → Profile."
        history.isEmpty() -> (api.summary(today).vo2max as? Card.Withheld)?.message ?: "Biological age needs seven nights and a VO₂max first."
        else -> null
    }
    fun series(m: String) = daily.getValue(m).filterKeys { it > today.minusDays(30) }.toSortedMap().values.toList()
    fun rows(m: String) = daily.getValue(m)
    val weights = owner.weights().sortedBy { it.ts }.map { it.kg.toDouble() }
    val markers = listOf(
        Marker("HRV baseline", "ms", series("hrv_sleep_avg"), { it.sleep }, "%.1f", true, rows("hrv_sleep_avg")),
        Marker("Resting HR baseline", "bpm", series("rhr_daily"), { it.heart }, "%.1f", false, rows("rhr_daily")),
        Marker("VO₂max", "ml/kg/min", series("vo2max_estimate"), { it.recovery }, "%.1f", true, rows("vo2max_estimate")),
        Marker("Weight", "kg", weights.takeLast(12), { it.strain }, "%.1f", null),
        Marker("Breathing rate", "rpm", series("respiratory_rate_sleep"), { it.sleep }, "%.1f", false, rows("respiratory_rate_sleep")),
        Marker("SpO₂", "%", series("spo2_overnight"), { it.steps }, "%.0f", true, rows("spo2_overnight")),
    ).filter { it.values.isNotEmpty() }
    val rhrWeek = daily.getValue("rhr_daily").filterKeys { it > today.minusDays(7) }.values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    return BiologyData(today, history, vo2.filterKeys { it <= today }.maxByOrNull { it.key }?.value, rhrWeek, withheld, markers, profile?.sex)
}

private fun yearsWord(x: Double) = "%.1f years %s".format(abs(x), if (x < 0) "younger" else "older")

/**
 * Biology: biological age (spec/02 §2.11) with its trend and range, the two age biomarkers
 * that move it with their inputs, and the other biomarkers' recent course.
 */
@Composable
fun BiologyScreen(api: Reader, owner: Owner, refreshKey: Any?, onSleep: () -> Unit) {
    var data by remember { mutableStateOf<BiologyData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    LaunchedEffect(refreshKey) {
        try {
            data = load(api, owner, today)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var chart by remember { mutableStateOf<Marker?>(null) }
    chart?.let { m -> m.daily?.let { rows -> DailyChartSheet(m.label, m.unit, rows, d.day, m.tone(c), { m.format.format(it) }) { chart = null } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val now = d.history.lastOrNull()?.second
        if (now == null) {
            item { RidgeCard { Subtle(d.withheld ?: "Not computed yet.") } }
            return@LazyColumn
        }
        val younger = now.age < now.chronological
        val tone = if (younger) r.zoneGreen else r.zoneYellow
        item {
            RidgeCard(spacing = 6.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Subtle("Biological age · as of ${d.history.last().first.format(DateTimeFormatter.ofPattern("d MMM"))}", style = RidgeType.caption)
                    Text("%.1f".format(now.age), fontSize = 52.sp, lineHeight = 58.sp, style = RidgeType.body)
                    Text("${yearsWord(now.age - now.chronological)} than ${now.chronological.toInt()}", style = RidgeType.label, color = tone,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(tone.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 4.dp))
                    d.history.firstOrNull { it.first == d.history.last().first.minusDays(7) }?.let { (_, week) ->
                        val change = now.age - week.age
                        Subtle("%s%.1f since last week".format(if (change >= 0) "+" else "−", abs(change)), Modifier.padding(top = 4.dp), RidgeType.caption)
                    }
                }
                AgeLine(d.history.map { it.second.age }, now.chronological, tone)
                Row(Modifier.fillMaxWidth()) {
                    Subtle(d.history.first().first.format(DateTimeFormatter.ofPattern("d MMM")), Modifier.weight(1f), RidgeType.caption)
                    Subtle(d.history.last().first.format(DateTimeFormatter.ofPattern("d MMM")), style = RidgeType.caption)
                }
            }
        }
        item {
            val v = d.vo2max
            RidgeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Confidence", style = RidgeType.rowTitle, modifier = Modifier.weight(1f))
                    if (v != null) {
                        // The VO₂max estimate's own error (±1 SEE) carried through to the age.
                        val low = BioAge.estimate(now.chronological, d.sex, v + BioAge.VO2MAX_SEE, now.sleepHours).age
                        val high = BioAge.estimate(now.chronological, d.sex, v - BioAge.VO2MAX_SEE, now.sleepHours).age
                        Text("%.1f – %.1f".format(low, high), style = RidgeType.label)
                    }
                }
            }
        }
        item { SectionTitle("Age biomarkers") }
        item {
            RidgeCard(spacing = 4.dp) {
                Biomarker(Icons.Rounded.FavoriteBorder, c.recoveryTone, "Fitness", now.fitnessYears, r,
                    listOfNotNull(d.vo2max?.let { "VO₂max" to "%.1f ml/kg/min".format(it) }, d.rhrWeek?.let { "Resting HR, 7-day median" to "%.1f bpm".format(it) })) {
                    chart = d.markers.firstOrNull { it.label == "VO₂max" }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(r.surface3))
                Biomarker(Icons.Rounded.Bedtime, c.sleepTone, "Sleep", now.sleepYears, r, listOf("Time asleep, 14 nights" to hm(now.sleepHours * 60)), onSleep)
            }
        }
        if (d.markers.isNotEmpty()) {
            item { SectionTitle("Other biomarkers") }
            item {
                RidgeCard(spacing = 0.dp) {
                    d.markers.forEachIndexed { i, m ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(r.surface3))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).let { if (m.daily != null) it.clickable { chart = m } else it }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = RidgeType.rowTitle)
                                Row {
                                    val t = trendNote(m.values, m.higherBetter, steady = 0.02, up = "Increasing", down = "Decreasing", still = "Stable")
                                    Text(t.text, style = RidgeType.body, color = t.color)
                                    Subtle(" · ${m.format.format(m.values.last())} ${m.unit}", style = RidgeType.body)
                                }
                            }
                            Sparkline(m.values, m.tone(c), Modifier.width(96.dp), height = 30.dp)
                            if (m.daily != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = muted, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * An age biomarker as the prototype draws it: icon chip, name, the years it moves your age (in
 * colour), a chevron; under it, each input with its value. The whole block opens [onOpen].
 */
@Composable
private fun Biomarker(icon: ImageVector, tone: MetricTone, title: String, years: Double, r: RidgeColors, rows: List<Pair<String, String>>, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, tone.container, tone.accent, size = 36.dp, iconSize = 18.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = RidgeType.rowTitle)
                Text(yearsWord(years), style = RidgeType.body, color = if (years < 0) r.good else r.warn)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
                Text(k, style = RidgeType.body, modifier = Modifier.weight(1f))
                Text(v, style = RidgeType.label)
            }
        }
    }
}

/** Biological age over the month, the chronological age as a dashed line. */
@Composable
private fun AgeLine(ages: List<Double>, chronological: Double, color: Color) {
    val dash = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer(
        Modifier.fillMaxWidth().height(90.dp).padding(top = 8.dp).drawWithCache {
            val lo = minOf(ages.min(), chronological) - 0.6
            val hi = maxOf(ages.max(), chronological) + 0.6
            fun x(i: Int) = if (ages.size == 1) size.width / 2 else i * size.width / (ages.size - 1)
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val line = Path().apply { ages.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            onDrawBehind {
                drawLine(dash, Offset(0f, y(chronological)), Offset(size.width, y(chronological)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(color, 4.dp.toPx(), Offset(x(ages.lastIndex), y(ages.last())))
            }
        },
    )
}
