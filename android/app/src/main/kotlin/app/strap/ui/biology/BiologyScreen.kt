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
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Schedule
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import strap.science.BioAge
import strap.science.BioAge.Term
import strap.science.Card
import strap.science.shown
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** A biomarker under the age: a daily series and how to print it. */
private class Marker(val label: String, val unit: String, val values: List<Double>, val tone: (MetricColors) -> Color, val format: String,
    /** Which way the trend is good (null: neither, as weight). */
    val higherBetter: Boolean?,
    /** The daily rows behind [values], when the marker is a daily metric (its chart opens from the row). */
    val daily: Map<LocalDate, Double>? = null)

private class BiologyData(
    val day: LocalDate,
    /** Biological age as of today and each of the twelve weeks before, oldest first. */
    val history: List<Pair<LocalDate, BioAge.Estimate>>,
    val withheld: String?,
    val markers: List<Marker>,
    /** Steps, opened from its habit row (not listed with the other biomarkers). */
    val steps: Marker,
)

/** Weeks of estimates the trend line shows. */
private const val TREND_WEEKS = 12L

private suspend fun load(api: Reader, owner: Owner, today: LocalDate): BiologyData {
    val profile = owner.profile()
    val from = today.minusDays(BioAge.WINDOW_DAYS + 7 * TREND_WEEKS)
    val daily = listOf("vo2max_estimate", "hrv_sleep_avg", "rhr_daily", "respiratory_rate_sleep", "spo2_overnight", "steps_total")
        .associateWith { m -> api.daily(m, from, today).associate { it.day to it.value } }
    // A step day counts when the strap saw 10 h of the waking day.
    val worn = api.daily("cardio_load", from, today)
        .filter { (it.flags["hr_minutes"]?.jsonPrimitive?.intOrNull ?: 0) >= BioAge.WORN_MINUTES }.mapTo(HashSet()) { it.day }
    val wornSteps = daily.getValue("steps_total").filterKeys { it in worn }
    val nights = api.daily("sleep_health_score_4dim", from, today).mapNotNull { r ->
        r.flags["tst_min"]?.jsonPrimitive?.doubleOrNull?.let { r.day to BioAge.NightRow(it, r.flags["sri"]?.jsonPrimitive?.doubleOrNull) }
    }.toMap()
    val zone = ZoneId.systemDefault()
    val strength = api.workouts(from, today).filter { it.sport in BioAge.STRENGTH_SPORTS }
        .groupBy { it.start.atZone(zone).toLocalDate() }.mapValues { (_, ws) -> ws.sumOf { it.durationS } / 60.0 }
    fun asOf(d: LocalDate) = BioAge.asOf(d, profile?.dob, profile?.sex, daily.getValue("rhr_daily"), wornSteps, nights, strength)
    val now = asOf(today)
    val history = (TREND_WEEKS downTo 0).map { today.minusWeeks(it) }.mapNotNull { d -> asOf(d).shown?.let { d to it } }
        .takeIf { now is Card.Shown }.orEmpty()
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
    val steps = Marker("Steps", "steps", series("steps_total"), { it.steps }, "%,.0f", true, rows("steps_total"))
    return BiologyData(today, history, (now as? Card.Withheld)?.message, markers, steps)
}

private fun yearWord(n: Double) = if (n == 1.0) "year" else "years"

/** A habit's pull on the shown age, in words: whole years, halves near zero. */
private fun pull(shown: Double): String {
    val a = abs(shown)
    if (a < 0.25) return "barely moves it"
    val n = if (a < 0.75) 0.5 else a.roundToInt().toDouble()
    val amount = if (n == 0.5) "half a year" else "about ${n.toInt()} ${yearWord(n)}"
    return if (shown > 0) "adds $amount" else "takes off $amount"
}

/** "About 2 years younger than 35", or "about your age". */
private fun versus(e: BioAge.Estimate): String {
    val d = (e.age - e.chronological).roundToInt()
    val age = e.chronological.toInt()
    return when {
        d == 0 -> "About your age, $age"
        d < 0 -> "About ${-d} ${yearWord(-d.toDouble())} younger than $age"
        else -> "About $d ${yearWord(d.toDouble())} older than $age"
    }
}

/**
 * Biology: biological age (spec/02 §2.11, D36) as a range with its weekly trend, the habits
 * that move it with their figures and targets, and the other biomarkers' recent course.
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
        val younger = now.age - now.chronological < 0.5
        val tone = if (younger) r.zoneGreen else r.zoneYellow
        val asOf = d.history.last().first.format(DateTimeFormatter.ofPattern("d MMM"))
        item {
            RidgeCard(spacing = 6.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Subtle("Biological age · as of $asOf" + if (now.early) " · early estimate" else "", style = RidgeType.caption)
                    Text("${now.low.roundToInt()}–${now.high.roundToInt()}", fontSize = 52.sp, lineHeight = 58.sp, style = RidgeType.body)
                    Text(versus(now), style = RidgeType.label, color = tone,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(tone.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 4.dp))
                    Subtle(
                        if (now.early) "From the weeks there are so far; it firms up over six months of wear."
                        else "From your last six months, against the typical person your age and sex.",
                        Modifier.padding(top = 6.dp), RidgeType.caption,
                    )
                }
                if (d.history.size >= 2) {
                    AgeLine(d.history.map { it.second.age }, now.chronological, tone)
                    Row(Modifier.fillMaxWidth()) {
                        Subtle(d.history.first().first.format(DateTimeFormatter.ofPattern("d MMM")), Modifier.weight(1f), RidgeType.caption)
                        Subtle(asOf, style = RidgeType.caption)
                    }
                }
            }
        }
        item { SectionTitle("What moves it") }
        item {
            val i = now.inputs
            fun open(metric: String) = { chart = d.markers.firstOrNull { it.daily != null && it.label == metric } }
            val rows = listOfNotNull(
                now.part(Term.RESTING_HR)?.let { p ->
                    Habit(Icons.Rounded.FavoriteBorder, c.heartTone, "Resting heart rate", p.shown, "Nightly low, median" to "${i.sleepingRhr.roundToInt()} bpm", null,
                        open("Resting HR baseline"))
                },
                now.part(Term.STEPS)?.let { p ->
                    Habit(Icons.Rounded.DirectionsWalk, c.stepsTone, "Steps", p.shown, "A day, on days worn 10 h" to "%,d".format(i.steps.roundToInt()),
                        "Aim for" to "8,000–10,000", { chart = d.steps })
                },
                now.part(Term.REGULARITY)?.let { p ->
                    Habit(Icons.Rounded.Schedule, c.sleepTone, "Sleep regularity", p.shown, "Weekly SRI, average" to "${i.sri.roundToInt()}", null, onSleep)
                },
                now.part(Term.SHORT_SLEEP)?.let { p ->
                    Habit(Icons.Rounded.Bedtime, c.sleepTone, "Time asleep", p.shown, "A night, average" to hm(i.asleepMin), "Aim for" to "at least 6h 30m", onSleep)
                },
                now.part(Term.STRENGTH)?.let { p ->
                    Habit(Icons.Rounded.FitnessCenter, c.strainTone, "Strength training", p.shown, "Strength and core workouts" to "${i.strengthMinPerWeek.roundToInt()} min a week",
                        "Aim for" to "40 min a week", null)
                },
            )
            RidgeCard(spacing = 4.dp) {
                rows.forEachIndexed { k, h ->
                    if (k > 0) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(r.surface3))
                    Biomarker(h, r)
                }
                if (now.fitnessCapped) Subtle("Resting heart rate and steps together move it by 3 years at most.", Modifier.padding(top = 6.dp), RidgeType.caption)
            }
        }
        item {
            RidgeCard {
                Subtle(
                    "Each habit is scored against the typical person your age and sex, by how much it moves the risk of dying in large studies, " +
                        "turned into years and shrunk by 40 %, because those studies didn't allow for each other. Strength only ever takes years off and " +
                        "short sleep only ever adds them. It's an estimate for insight, not a medical measurement, so it is shown as a range in whole years.",
                    style = RidgeType.caption,
                )
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

/** One habit behind the age: what it adds or takes off, the figure it was worked from, and a target where there is one. */
private class Habit(
    val icon: ImageVector, val tone: MetricTone, val title: String, val shown: Double,
    val figure: Pair<String, String>, val target: Pair<String, String>?, val onOpen: (() -> Unit)?,
)

/**
 * A habit as the prototype draws an age biomarker: icon chip, name, the years it moves your age
 * (in colour), a chevron when it opens something; under it, its figure and target.
 */
@Composable
private fun Biomarker(h: Habit, r: RidgeColors) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).let { m -> h.onOpen?.let { m.clickable(onClick = it) } ?: m }.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(h.icon, h.tone.container, h.tone.accent, size = 36.dp, iconSize = 18.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(h.title, style = RidgeType.rowTitle)
                Text(pull(h.shown).replaceFirstChar { it.uppercase() }, style = RidgeType.body,
                    color = when { abs(h.shown) < 0.25 -> MaterialTheme.colorScheme.onSurfaceVariant; h.shown < 0 -> r.good; else -> r.warn })
            }
            if (h.onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOfNotNull(h.figure, h.target).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
                Text(k, style = RidgeType.body, modifier = Modifier.weight(1f))
                Text(v, style = RidgeType.label)
            }
        }
    }
}

/** Biological age over the weeks, the chronological age as a dashed line. */
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
