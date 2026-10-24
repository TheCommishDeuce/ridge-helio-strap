package app.strap.ui.activity

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.ui.Loading
import app.strap.ui.components.Bar
import app.strap.ui.components.BandGauge
import app.strap.ui.components.CardHeader
import app.strap.ui.components.CardValue
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.DailyBars
import app.strap.ui.components.DailyChartSheet
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.Infos
import app.strap.ui.components.ListRow
import app.strap.ui.components.MiniBars
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.Sparkline
import app.strap.ui.components.Subtle
import app.strap.ui.components.Tile
import app.strap.ui.components.TileGrid
import app.strap.ui.components.trendNote
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.MetricColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.loadRange
import app.strap.sync.DayStatus
import app.strap.ui.components.icon
import app.strap.ui.components.tone
import app.strap.ui.today.workoutLine
import strap.science.Card
import strap.science.DaySummary
import strap.science.Strain
import strap.science.Workout
import strap.science.shown
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private class FitnessData(val summary: DaySummary, val daily: Map<String, Map<LocalDate, Double>>, val workouts: List<Workout>, val statuses: Map<LocalDate, DayStatus>)

/** A chart the Fitness tab can pin: a daily metric, how it is drawn and printed. */
/** [higherBetter]: which way the metric's trend is good (null: neither, as load). */
private class Chart(val label: String, val metric: String, val unit: String, val bars: Boolean, val tone: (MetricColors) -> Color, val format: (Double) -> String,
    val higherBetter: Boolean?)

private val CHARTS: Map<String, Chart> = linkedMapOf(
    "active" to Chart("Active minutes", "mvpa_min", "min", true, { it.recovery }, { "${it.roundToInt()}" }, true),
    "vo2" to Chart("VO₂max", "vo2max_estimate", "ml/kg/min", false, { it.recovery }, { "%.1f".format(it) }, true),
    "rhr" to Chart("Resting HR", "rhr_daily", "bpm", false, { it.heart }, { "${it.roundToInt()}" }, false),
    "hrv" to Chart("HRV", "hrv_sleep_avg", "ms", false, { it.sleep }, { "${it.roundToInt()}" }, true),
    "load" to Chart("Daily cardio load", "cardio_load", "TRIMP", true, { it.strain }, { "${it.roundToInt()}" }, null),
    "steps" to Chart("Steps", "steps_total", "steps", true, { it.steps }, { "%,d".format(it.roundToInt()) }, true),
    "rr" to Chart("Breathing rate", "respiratory_rate_sleep", "rpm", false, { it.sleep }, { "%.1f".format(it) }, false),
    "spo2" to Chart("SpO₂", "spo2_overnight", "%", false, { it.steps }, { "${it.roundToInt()}" }, true),
)

private val DEFAULT_PINS = listOf("active", "vo2", "rhr", "hrv", "load", "steps")

private class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString("fitness_pins", null)?.split(',')?.filter { it in CHARTS } ?: DEFAULT_PINS

    fun save(pins: List<String>) = prefs.edit().putString("fitness_pins", pins.joinToString(",")).apply()
}

/**
 * Fitness (Bevel's layout): the week with what you did each day, the day's note, the
 * training load and the strap's PAI, the week's steps, charts you pin, and the workouts.
 */
@Composable
fun ActivityScreen(api: Reader, refreshKey: Any?, onWorkout: (Workout) -> Unit) {
    var data by remember { mutableStateOf<FitnessData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    var dayEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    val context = LocalContext.current
    val store = remember { PinStore(context) }
    val pins = remember { mutableStateListOf<String>().apply { addAll(store.load()) } }
    var editing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey, dayEpoch) {
        try {
            data = FitnessData(
                api.summary(day),
                CHARTS.values.map { it.metric }.distinct().associateWith { m -> api.daily(m, today.minusDays(41), today).associate { it.day to it.value } },
                api.workouts(today.minusDays(29), today),
                api.statuses(today.minusDays(6), today),
            )
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = ZoneId.systemDefault()
    val byDay = d.workouts.groupBy { it.start.atZone(zone).toLocalDate() }
    val dayWorkouts = byDay[day].orEmpty()
    val load = d.daily.getValue("cardio_load")
    val range = loadRange(load, day)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { WeekStrip(today, day, byDay.keys, d.statuses) { dayEpoch = it.toEpochDay() } }
        item {
            val w = dayWorkouts.firstOrNull()
            val rangeLine = range?.let { " Your 7-day load is ${it.acute.roundToInt()}, ${it.words.lowercase()} (${it.low.roundToInt()}–${it.high.roundToInt()})." } ?: ""
            RidgeCard(spacing = 6.dp) {
                val status = d.statuses[day]?.takeIf { it != DayStatus.ACTIVE }
                CardHeader(if (w != null) "Workout done" else status?.label ?: "Rest day",
                    icon = if (w != null) Icons.AutoMirrored.Rounded.DirectionsRun else status?.icon ?: Icons.AutoMirrored.Rounded.DirectionsWalk,
                    tone = if (w == null && status != null) status.tone() else c.strainTone)
                Text((if (w != null) "A ${w.durationS / 60}-minute workout${w.avgHr?.let { " at an average of $it bpm" } ?: ""}." else "No workout recorded on this day.") + rangeLine,
                    style = RidgeType.body, color = muted)
            }
        }
        item { SectionTitle("Training load") }
        item {
            val pai = d.summary.pai.shown
            TileGrid(listOf(
                { m ->
                    Tile("Cardio load", range?.acute?.roundToInt()?.toString() ?: "—", m, "a day",
                        range?.let { Note(it.words, if (it.words == "In range") r.zoneGreen else if (it.words == "Load spike") r.zoneRed else muted) } ?: Note("needs 28 days", muted)) {
                        range?.let { BandGauge(it.acute, maxOf(it.high * 1.5, it.acute * 1.15), it.low, it.high, c.strain, r.zoneGreen.copy(alpha = 0.45f)) }
                    }
                },
                { m ->
                    Tile("PAI", pai?.value?.toString() ?: "—", m, "7 days", pai?.let { Note(if (it.value >= it.goal) "goal ${it.goal} met" else "goal ${it.goal}", if (it.value >= it.goal) r.zoneGreen else muted) },
                        strap = true) {
                        pai?.let { BandGauge(it.value.toDouble(), maxOf(200.0, it.value * 1.1), it.goal.toDouble(), maxOf(200.0, it.value * 1.1), c.recovery, r.zoneGreen.copy(alpha = 0.45f)) }
                    }
                },
            ))
        }
        item {
            val steps = d.daily.getValue("steps_total")
            val week = (0..6).mapNotNull { steps[day.minusDays(it.toLong())] }
            RidgeCard(spacing = 12.dp, onClick = { open = "steps" }) {
                CardHeader("Steps this week", chevron = true, icon = Icons.AutoMirrored.Rounded.DirectionsWalk, tone = c.stepsTone)
                CardValue("%,d".format(week.sum().roundToInt()), "steps", week.takeIf { it.isNotEmpty() }?.let { Note("%,d a day".format(it.average().roundToInt()), muted) })
                DailyBars(steps, day.minusDays(6), day, c.steps, { "%,d steps".format(it.roundToInt()) }, highlightLast = true, height = 90.dp)
            }
        }
        item { SectionTitle("Pinned charts", "Edit", { editing = true }) }
        item {
            TileGrid(pins.mapNotNull { k -> CHARTS[k]?.let { k to it } }.map { (k, chart) ->
                { m: Modifier ->
                    val series = d.daily.getValue(chart.metric)
                    val last30 = (29 downTo 0).map { series[day.minusDays(it.toLong())] }
                    val now = series[day]
                    Tile(chart.label, now?.let(chart.format) ?: "—", m, chart.unit, trendNote(last30.filterNotNull(), chart.higherBetter), onClick = { open = k }) {
                        if (chart.bars) MiniBars(last30, chart.tone(c)) else Sparkline(last30.filterNotNull(), chart.tone(c))
                    }
                }
            })
        }
        item { SectionTitle(day.format(DateTimeFormatter.ofPattern("EEEE d MMM"))) }
        item {
            if (dayWorkouts.isEmpty()) RidgeCard { Subtle("No workouts recorded by the strap on this day.") }
            else WorkoutRows(dayWorkouts, onWorkout)
        }
    }
    if (editing) PinSheet(pins) { editing = false; store.save(pins) }
    open?.let { k ->
        val chart = CHARTS.getValue(k)
        DailyChartSheet(chart.label, chart.unit, d.daily.getValue(chart.metric), day, chart.tone(c), chart.format) { open = null }
    }
}

/** The week ending today, a day per cell, a run icon on the days with a workout and its own on a marked day. */
@Composable
private fun WeekStrip(today: LocalDate, selected: LocalDate, active: Set<LocalDate>, statuses: Map<LocalDate, DayStatus>, onSelect: (LocalDate) -> Unit) {
    val r = LocalRidgeColors.current
    Row(Modifier.fillMaxWidth()) {
        (6 downTo 0).map { today.minusDays(it.toLong()) }.forEach { d ->
            val on = d == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) r.card else Color.Transparent).clickable { onSelect(d) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(d.format(DateTimeFormatter.ofPattern("EEE")), style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${d.dayOfMonth}", style = RidgeType.label)
                DayMark(statuses[d], d in active, 0.6f, 16.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(pins: MutableList<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Pinned charts", style = RidgeType.sheetTitle, modifier = Modifier.padding(bottom = 8.dp))
            (pins + CHARTS.keys.filter { it !in pins }).forEach { key ->
                val pinned = key in pins
                val i = pins.indexOf(key)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pinned, { on -> if (on) pins += key else if (pins.size > 1) pins -= key })
                    Text(CHARTS.getValue(key).label, style = RidgeType.rowTitle, modifier = Modifier.weight(1f))
                    if (pinned) {
                        IconButton(onClick = { if (i > 0) pins.add(i - 1, pins.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { if (i < pins.lastIndex) pins.add(i + 1, pins.removeAt(i)) }, enabled = i < pins.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}
