package app.strap.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.AutoAwesome
import app.strap.ui.components.InsightCard
import app.strap.ui.components.MiniRing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import app.strap.ui.components.CardFoot
import app.strap.ui.components.CardValue
import app.strap.ui.components.EvenAxis
import app.strap.ui.components.Gauge
import app.strap.ui.components.Point
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.SpanLine
import app.strap.ui.components.Tile
import app.strap.ui.components.TileGrid
import java.time.Duration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.strap.api.Reader
import app.strap.api.ApiException
import app.strap.ui.components.CardHeader
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.DailyBars
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.Hypnogram
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Subtle
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.components.duration
import app.strap.ui.components.hm
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.Loading
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import strap.science.Breathing
import strap.science.Card
import strap.science.Night
import strap.science.SleepCard
import strap.science.SleepDebt
import strap.science.SleepHealth
import strap.science.shown
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private class SleepData(val sleep: SleepCard, val history: Map<LocalDate, Double>, val night: Map<String, List<Point>>)

/** What the night chart can show: series name, label, unit and how to print it. */
private val NIGHT = listOf(
    NightSeries("hr", "Heart rate", "bpm", "%.0f"),
    NightSeries("hrv", "HRV", "ms", "%.0f"),
    NightSeries("respiratory_rate", "Breathing", "rpm", "%.1f"),
    NightSeries("spo2", "SpO₂", "%", "%.0f"),
)

private data class NightSeries(val key: String, val label: String, val unit: String, val format: String)

/** The night's readings of each [NIGHT] series between its start and end (it starts on the day before). */
private suspend fun nightSeries(api: Reader, night: Night?, today: LocalDate): Map<String, List<Point>> {
    night ?: return emptyMap()
    val keys = NIGHT.map { it.key }
    val days = listOf(api.daySeries(today.minusDays(1), keys), api.daySeries(today, keys))
    val from = night.start
    val to = night.end
    return keys.associateWith { k ->
        days.flatMap { it.series[k].orEmpty() }.filter { it.at >= from && it.at <= to }.map { Point(it.at.toEpochMilli(), it.value) }
    }
}

/**
 * Sleep, on the night palette (Bevel's layout, Ridge's numbers): the strap's own score in the
 * big gauge, time in bed and asleep against your need, the four sleep-health dimensions as
 * tiles (never one score), the stages, the night's vitals, breathing disturbances and debt.
 */
@Composable
fun SleepScreen(api: Reader, day: LocalDate, onSelectDay: (LocalDate) -> Unit, refreshKey: Any?) {
    var data by remember { mutableStateOf<SleepData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableIntStateOf(14) }
    var series by rememberSaveable { mutableStateOf(NIGHT.first().key) }
    var scores by remember { mutableStateOf<Map<LocalDate, Int?>>(emptyMap()) }
    val today = LocalDate.now()
    val stripEnd = if (day >= today.minusDays(6)) today else day
    LaunchedEffect(refreshKey, day) {
        try {
            val sleep = api.summary(day).sleep
            val history = api.daily("sleep_health_score_4dim", day.minusDays(29), day)
                .mapNotNull { r -> r.flags["tst_min"]?.jsonPrimitive?.doubleOrNull?.let { r.day to it } }.toMap()
            scores = api.rings(stripEnd.minusDays(6), stripEnd).associate { it.date to it.sleepScore }
            data = SleepData(sleep, history, nightSeries(api, sleep.main, day))
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val s = d.sleep
    val main = s.main
    val naps = s.nights.filter { !it.isMain }
    val health = s.health.shown
    val need = s.needMin
    val tst = health?.tstMin
    val background = Brush.verticalGradient(listOf(r.bg, r.surface2, r.bg))
    LazyColumn(Modifier.fillMaxSize().background(background), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { NightStrip(stripEnd, day, scores, onSelectDay) }
        item {
            val score = main?.deviceScore
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The strap's own 0-100 score, named as the strap's (we compute no composite sleep score).
                Gauge(score?.toString() ?: "—", score?.let { "${scoreWord(it)} · strap score" } ?: "no score from the strap", score?.let { it / 100f }, c.sleep, 184.dp, 46.sp, stroke = 6f)
            }
        }
        item {
            TileGrid(listOf(
                { m -> Tile("Time in bed", main?.let { hm(Duration.between(it.start, it.end).toMillis() / 60_000.0) } ?: "—", m) },
                { m ->
                    Tile("Time asleep", tst?.let { hm(it) } ?: "—", m,
                        status = changeNote(if (tst != null && need != null) tst - need else null, "vs need", magnitude = { "${it.roundToInt()} min" }))
                },
            ))
        }
        main?.let { n ->
            item { InsightCard(Icons.Rounded.AutoAwesome, c.sleepTone, day.format(DateTimeFormatter.ofPattern("d MMMM")) + " sleep summary", nightSummary(n, tst, need, s.debt.shown)) }
        }
        item { HealthTiles(s.health, s.regularity) }
        item {
            RidgeCard(spacing = 12.dp) {
                if (main == null) {
                    CardHeader("Stages", icon = Icons.Rounded.Bedtime, tone = c.sleepTone)
                    Subtle("No night recorded that ended today.")
                } else {
                    val start = main.start.toEpochMilli()
                    val end = main.end.toEpochMilli()
                    CardHeader("Stages", "${clockOf(start)} – ${clockOf(end)}", icon = Icons.Rounded.Bedtime, tone = c.sleepTone)
                    Hypnogram(main.stages.map { Triple(it.startMs, it.endMs, it.code) })
                    CardFoot(listOf("REM" to main.remMin, "Light" to main.lightMin, "Deep" to main.deepMin, "Awake" to main.awakeMin)
                        .map { (k, v) -> k to (v?.let { duration(it.toDouble()) } ?: "—") })
                }
            }
        }
        if (main != null) item { NightCard(d.night, series, main) { series = it } }
        item {
            val b = s.breathing.shown
            val debt = s.debt.shown
            TileGrid(listOf(
                { m ->
                    Tile("Breathing disturbances", b?.let { "%.1f".format(it.value) } ?: "—", m, "/ hour",
                        Note(b?.usual?.let { "usual %.1f".format(it.median) } ?: (s.breathing as? Card.Withheld)?.message?.let { "not measured" } ?: "learning your usual",
                            MaterialTheme.colorScheme.onSurfaceVariant), strap = true)
                },
                { m ->
                    Tile("Sleep debt", debt?.let { hm(it.minutes) } ?: "—", m, debt?.let { "${it.windowNights} nights" },
                        debt?.let { Note("${it.nightsBelow} of ${it.nights} nights below need", MaterialTheme.colorScheme.onSurfaceVariant) })
                },
            ))
        }
        item {
            RidgeCard(spacing = 14.dp) {
                CardHeader("History", "time asleep", icon = Icons.Rounded.CalendarMonth, tone = c.sleepTone)
                ConnectedButtons(listOf(7, 14, 30), days, { "$it nights" }) { days = it }
                DailyBars(d.history, day.minusDays(days - 1L), day, c.sleep, { hm(it) }, target = need,
                    dim = need?.let { n -> { v -> v < n } }, height = 130.dp)
                need?.let { Subtle("- - Your need, ${hm(it)}", style = RidgeType.caption) }
            }
        }
        if (naps.isNotEmpty()) {
            item { GroupHeader("Naps") }
            item {
                Grouped(naps) { n, shape ->
                    ListRow(shape, "${clockOf(n.start.toEpochMilli())} – ${clockOf(n.end.toEpochMilli())}",
                        leading = { IconCircle(Icons.Rounded.Hotel, c.sleepTone.container, c.sleepTone.onContainer) },
                        trailing = { Text(hm(Duration.between(n.start, n.end).toMillis() / 60_000.0), style = RidgeType.label) })
                }
            }
        }
    }
}

/** The night in plain words, from its own numbers. */
private fun nightSummary(night: Night, tst: Double?, need: Double?, debt: SleepDebt?): String = buildList {
    val bed = clockOf(night.start.toEpochMilli())
    val wake = clockOf(night.end.toEpochMilli())
    if (tst != null && need != null) {
        val gap = (tst - need).roundToInt()
        add("You slept ${hm(tst)} between $bed and $wake, ${kotlin.math.abs(gap)} minutes ${if (gap >= 0) "over" else "under"} your need.")
    } else add("You slept between $bed and $wake.")
    val deep = night.deepMin
    val rem = night.remMin
    if (deep != null && rem != null) add("Deep sleep came to ${hm(deep.toDouble())} and REM to ${hm(rem.toDouble())}.")
    debt?.let { add(if (it.minutes > 0) "Over ${it.windowNights} nights you are ${hm(it.minutes)} short." else "You carry no sleep debt over ${it.windowNights} nights.") }
}.joinToString(" ")

/** The strap's score in words, so the big number reads without a scale. */
private fun scoreWord(score: Int) = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 55 -> "Fair"
    else -> "Poor"
}

/** The seven nights ending on [end], each a ring of the strap's score with its date inside; tap to show it. */
@Composable
private fun NightStrip(end: LocalDate, selected: LocalDate, scores: Map<LocalDate, Int?>, onSelect: (LocalDate) -> Unit) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        (6 downTo 0).map { end.minusDays(it.toLong()) }.forEach { d ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (d == selected) r.card else Color.Transparent)
                    .clickable { onSelect(d) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(d.format(DateTimeFormatter.ofPattern("EEE")), style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(contentAlignment = Alignment.Center) {
                    MiniRing(scores[d]?.let { it / 100f }, c.sleep, 34.dp)
                    Text("${d.dayOfMonth}", style = RidgeType.caption)
                }
            }
        }
    }
}

/** The night's heart rate, HRV, breathing or SpO₂, picked with the buttons above the line. */
@Composable
private fun NightCard(night: Map<String, List<Point>>, selected: String, main: Night, onSelect: (String) -> Unit) {
    val c = LocalMetricColors.current
    val pick = NIGHT.first { it.key == selected }
    val points = night[pick.key].orEmpty()
    val color = when (pick.key) { "hr" -> c.heart; "hrv" -> c.recovery; "respiratory_rate" -> c.sleep; else -> c.steps }
    RidgeCard(spacing = 12.dp) {
        CardHeader("Through the night", icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
        ConnectedButtons(NIGHT.map { it.key }, selected, { k -> NIGHT.first { it.key == k }.label }, onSelect = onSelect)
        if (points.isEmpty()) {
            Subtle("The strap recorded no ${pick.label.lowercase()} through this night.")
            return@RidgeCard
        }
        CardValue(pick.format.format(points.map { it.v }.average()), "${pick.unit} average",
            Note("${pick.format.format(points.minOf { it.v })}–${pick.format.format(points.maxOf { it.v })}", MaterialTheme.colorScheme.onSurfaceVariant))
        SpanLine(points, main.start.toEpochMilli(), main.end.toEpochMilli(), color)
    }
}

/** The four dimensions as tiles, each with its own rule — never folded into one "score". */
@Composable
private fun HealthTiles(card: Card<SleepHealth>, regularity: Card<Double>) {
    val r = LocalRidgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Sleep health", trailing = { card.shown?.let { Subtle("${it.dimensions} of 4 met", style = RidgeType.caption) } })
        val health = when (card) {
            is Card.Withheld -> return@Column RidgeCard { Subtle(card.message) }
            is Card.Shown -> card.value
        }
        val midpoint = OffsetDateTime.parse(health.midpointLocal).format(DateTimeFormatter.ofPattern("HH:mm"))
        val rows = listOf(
            Row4("Duration", "7–9 h", hm(health.tstMin), health.duration),
            Row4("Efficiency", "≥ 85%", "${health.efficiencyPct.roundToInt()}%", health.efficiency),
            Row4("Timing", "midpoint 02–04", midpoint, health.timing),
            Row4("Regularity", "SRI ≥ 70", regularity.shown?.let { "%.0f".format(it) } ?: "—", health.regularity),
        )
        TileGrid(rows.map { row ->
            { m: Modifier ->
                val color = if (row.ok == 1) r.zoneGreen else r.zoneYellow
                Column(m.clip(RoundedCornerShape(16.dp)).background(r.card).padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (row.ok == 1) "Met" else "Not met", style = RidgeType.rowTitle)
                    Text("${row.name} · ${row.rule}", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.value, style = RidgeType.label, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp).width(56.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
        })
        if (regularity is Card.Withheld) Subtle(regularity.message, Modifier.padding(horizontal = 4.dp), RidgeType.caption)
    }
}

private data class Row4(val name: String, val rule: String, val value: String, val ok: Int)
