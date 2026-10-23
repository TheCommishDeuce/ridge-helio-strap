package app.strap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.CoffeeMaker
import androidx.compose.material.icons.rounded.EmojiFoodBeverage
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.strap.ui.components.sliderPosition
import app.strap.ui.journal.JournalLogger
import app.strap.ui.journal.QuickLogRow
import app.strap.ui.journal.WeightDialog
import java.time.Instant
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.components.GaugeBlock
import app.strap.ui.components.Moment
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.Subtle
import app.strap.ui.components.Tile
import app.strap.ui.components.TileGrid
import app.strap.ui.components.Timeline
import app.strap.ui.components.Track
import app.strap.ui.components.WeekStrip
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.components.factorTrack
import app.strap.ui.components.hm
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.sync.JournalEntry
import app.strap.ui.journal.journalIcon
import app.strap.ui.journal.journalTone
import strap.science.IllnessCheck
import strap.science.Workout
import strap.science.shown
import java.time.LocalDate
import kotlin.math.roundToInt

/** Where Today's gauges, cards, moments and tiles lead. */
class TodayNav(
    val recovery: () -> Unit,
    val sleep: () -> Unit,
    val activity: () -> Unit,
    val journal: () -> Unit,
    val heart: () -> Unit,
    val stress: () -> Unit,
    val steps: () -> Unit,
    val timeline: () -> Unit,
    val strain: () -> Unit,
    val selectDay: (LocalDate) -> Unit,
)

/**
 * Today: week strip; the three gauges in one card with the recovery zone under them; last
 * night against your usual; heart, stress and steps against the usual for the same hours;
 * and the day's moments, which open the full-day timeline.
 */
@Composable
/** [liveSteps]: the strap's count as it changes, while Today shows today (S4); null otherwise. */
fun TodayContent(data: TodayData, nav: TodayNav, logger: JournalLogger, modifier: Modifier = Modifier, liveSteps: Long? = null) {
    var hiddenIllness by rememberSaveable { mutableStateOf<String?>(null) }
    var weighing by remember { mutableStateOf(false) }
    // Today logs at the moment; a past day at its noon.
    fun at(): Instant = if (data.day == LocalDate.now()) Instant.now() else data.day.atTime(12, 0).atZone(data.curves.window.zone).toInstant()
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { WeekStrip(data.stripDays, data.recoveryByDay, data.day, nav.selectDay) }
        data.illness?.takeIf { it != hiddenIllness }?.let { text -> item { IllnessBanner(text, data.illnessNight, data.day, data.illnessCheck) { hiddenIllness = text } } }
        item { GaugesCard(data, nav) }
        item { SectionTitle("Last night", trailing = { Subtle("vs your usual", style = RidgeType.caption) }) }
        item { LastNight(data, nav) }
        item { SectionTitle("Heart, stress & steps") }
        item { HeartCard(data, nav.heart) }
        item { StressCard(data, nav.stress) }
        item { StepsCard(data, nav.steps, liveSteps) }
        item { SectionTitle("Your day", "Full day", nav.timeline) }
        item {
            val moments = moments(data, nav)
            val quick: @Composable () -> Unit = {
                QuickLogRow(onPick = { q -> logger.add(q.kind, q.value, q.name, at(), q.logged) }, onWeight = { weighing = true })
            }
            if (moments.isEmpty()) RidgeCard { Subtle("Nothing recorded for this day yet."); quick() } else Timeline(moments, quick)
        }
    }
    if (weighing) WeightDialog(onDismiss = { weighing = false }) { kg -> weighing = false; logger.add("weight", kg, null, at(), "Weight saved") }
}

/** Strain, recovery and the strap's sleep score in one card, the recovery zone and its week underneath. */
@Composable
private fun GaugesCard(data: TodayData, nav: TodayNav) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val recovery = data.recovery.valueOrNull
    RidgeCard(spacing = 4.dp) {
        val strain = data.strain.valueOrNull
        val score = data.night?.deviceScore
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            GaugeBlock(strain?.let { "%.1f".format(it) } ?: "—", "of 21", strain?.let { (it / 21).toFloat() }, c.strain, "Strain", null,
                88.dp, 22.sp, stroke = 8f, onClick = nav.strain)
            GaugeBlock(recovery?.let { "${it.roundToInt()}%" } ?: "—", "ready", recovery?.let { (it / 100).toFloat() },
                recovery?.let(r::zone) ?: c.recovery, "Recovery",
                changeNote(recovery?.let { v -> data.recoveryWeekBefore(data.day)?.let { v - it } }, "vs week"),
                132.dp, 36.sp, onClick = nav.recovery)
            // The strap's own 0-100 score, named as the strap's (we compute no composite sleep score).
            GaugeBlock(score?.toString() ?: "—", "Amazfit", score?.let { it / 100f }, c.sleep, "Sleep", null,
                88.dp, 22.sp, stroke = 8f, onClick = nav.sleep)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(r.surface3))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = nav.recovery).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val rec = data.recovery) {
                is Reading.Withheld -> Subtle(rec.message, Modifier.weight(1f))
                is Reading.Value -> {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(r.zone(rec.value)))
                    Text(zoneName(rec.value) + (data.load?.let { " · load ${it.words.lowercase()}" } ?: ""), style = RidgeType.rowTitle,
                        modifier = Modifier.weight(1f).padding(start = 10.dp))
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        daySentence(data)?.let { Subtle(it, Modifier.padding(top = 2.dp)) }
    }
}

/** The day so far in one sentence, from its own numbers. */
private fun daySentence(data: TodayData): String? {
    val parts = listOfNotNull(
        data.sleepTstMin?.let { t -> "You slept ${hm(t)}" + (data.night?.deviceScore?.let { " and the strap scored the night $it" } ?: "") },
        data.recovery.valueOrNull?.let { "recovery is ${it.roundToInt()}%" },
        data.strain.valueOrNull?.let { "strain ${if (data.day == LocalDate.now()) "has reached" else "finished at"} ${"%.1f".format(it)}" },
    )
    val week = data.recoveryWeekTo(data.day)?.let { "Your week averages ${it.roundToInt()}% recovery." }
    val load = data.load?.let { "Your 7-day load is ${it.acute.roundToInt()}, ${it.words.lowercase()} (${it.low.roundToInt()}–${it.high.roundToInt()})." }
    return listOfNotNull(parts.takeIf { it.isNotEmpty() }?.joinToString("; ")?.plus("."), week, load).joinToString(" ").takeIf { it.isNotEmpty() }
}

/** Last night's vitals as tiles, each against your usual; the parts of recovery open its screen. */
@Composable
private fun LastNight(data: TodayData, nav: TodayNav) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val f = data.factors.associateBy { it.key }
    val rr = f["rr"]
    val sleep = f["sleep"]
    val tst = data.sleepTstMin
    val need = sleep?.needMin
    fun missing(r: Reading) = Note(if (r is Reading.Withheld) "not yet" else " ", muted)
    TileGrid(listOf(
        { m ->
            val v = data.hrv.valueOrNull
            Tile("HRV", v?.roundToInt()?.toString() ?: "—", m, "ms",
                v?.let { changeNote(data.hrv.usual?.let { u -> it - u }, "vs usual", meaningful = 2.0) } ?: missing(data.hrv), onClick = nav.recovery,
                slider = sliderPosition(v, data.hrv.usual))
        },
        { m ->
            val v = data.restingHr.valueOrNull
            Tile("Resting HR", v?.roundToInt()?.toString() ?: "—", m, "bpm",
                v?.let { changeNote(data.restingHr.usual?.let { u -> it - u }, "vs usual", lowerBetter = true, meaningful = 1.0) } ?: missing(data.restingHr),
                onClick = nav.recovery, slider = sliderPosition(v, data.restingHr.usual))
        },
        { m ->
            Tile("Breathing rate", rr?.let { "%.1f".format(it.value) } ?: "—", m, "rpm",
                rr?.let { changeNote(it.baseline?.let { b -> it.value - b }, "vs usual", digits = 1, lowerBetter = true, meaningful = 0.3) }, onClick = nav.recovery,
                slider = sliderPosition(rr?.value, rr?.baseline))
        },
        { m ->
            val v = data.spo2.valueOrNull
            Tile("SpO₂", v?.roundToInt()?.toString() ?: "—", m, "%",
                v?.let { changeNote(data.spo2.usual?.let { u -> it - u }, "vs usual", meaningful = 1.0) } ?: missing(data.spo2),
                slider = sliderPosition(v, data.spo2.usual))
        },
        { m ->
            Tile("Asleep", tst?.let { hm(it) } ?: "—", m,
                status = changeNote(if (tst != null && need != null) tst - need else null, "vs need", magnitude = { "${it.roundToInt()} min" }, meaningful = 10.0),
                onClick = nav.sleep, slider = sliderPosition(tst, need))
        },
        { m ->
            val v = data.skinTemp.valueOrNull
            Tile("Skin temp", v?.let { "%.1f".format(it) } ?: "—", m, "°C",
                v?.let { changeNote(data.skinTemp.usual?.let { u -> it - u }, "vs usual", digits = 1, lowerBetter = true, meaningful = 0.3) } ?: missing(data.skinTemp),
                onClick = nav.recovery, slider = sliderPosition(v, data.skinTemp.usual, span = 1.0))
        },
    ))
}

/** Recovery zone name. */
internal fun zoneName(recovery: Double) = when {
    recovery >= 67 -> "High recovery"
    recovery >= 34 -> "Medium recovery"
    else -> "Low recovery"
}

internal val FACTOR_LABELS = mapOf("hrv" to "HRV overnight", "rhr" to "Resting heart rate", "rr" to "Breathing rate", "sleep" to "Asleep")

internal fun factorValue(f: Factor): String = when (f.key) {
    "sleep" -> hm(f.value)
    "rr" -> "%.1f".format(f.value)
    else -> "${f.value.roundToInt()}"
}

internal fun factorUsual(f: Factor): String = when {
    f.key == "sleep" -> "need " + (f.needMin?.let { hoursLabel(it) } ?: "—")
    f.baseline == null -> "usual —"
    f.key == "rr" -> "usual %.1f".format(f.baseline)
    else -> "usual ${f.baseline.roundToInt()}"
}

internal fun factorTrackOf(f: Factor): Track? = when {
    f.key == "sleep" -> f.needMin?.let { Track(f.value, it, null, null, inside = f.value >= it) }
    f.baseline != null -> factorTrack(f.value, f.baseline, f.z)
    else -> null
}

/** 480 → "8h", 450 → "7h 30m". */
internal fun hoursLabel(minutes: Double): String {
    val m = minutes.roundToInt()
    return if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}

@Composable
private fun IllnessBanner(text: String, night: LocalDate?, day: LocalDate, check: IllnessCheck?, onDismiss: () -> Unit) {
    val tone = LocalMetricColors.current.stressTone
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tone.container).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Which night raised it: a flag stays active for two days after its night.
            Text(flagNight(night, day), style = RidgeType.label, color = tone.onContainer)
            // The fixed, reviewed sentence (names the baseline, says "not a diagnosis").
            Text(text, style = RidgeType.body, color = tone.onContainer)
            lastNightCheck(night, day, check)?.let { Text(it, style = RidgeType.body, color = tone.onContainer.copy(alpha = 0.8f)) }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = tone.onContainer) }
    }
}

/** "From last night" or "From the night to Wed 21 Oct". */
internal fun flagNight(night: LocalDate?, day: LocalDate): String =
    if (night == null || night == day) "From last night" else "From the night to " + night.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))

/** "+0.7" / "−0.2" / "±0.0" with [digits] decimals. */
internal fun signedFixed(x: Double, digits: Int): String {
    val size = "%.${digits}f".format(kotlin.math.abs(x))
    return (if (size.trim('0', '.', ',').isEmpty()) "±" else if (x < 0) "−" else "+") + size
}

/** When the flag is from an earlier night: what last night's check found, against the same 14-night rule. */
internal fun lastNightCheck(night: LocalDate?, day: LocalDate, check: IllnessCheck?): String? {
    if (night == null || night == day || check == null) return null
    val parts = listOfNotNull(
        check.rrDelta?.let { "breathing rate ${signedFixed(it, 1)} bpm" },
        check.tempDelta?.let { "skin temperature ${signedFixed(it, 2)}°C" },
    )
    if (parts.isEmpty()) return null
    return "Last night: ${parts.joinToString(", ")} against the 14 nights before; no new signal."
}

/** "Caffeine · 95 mg" */
internal fun journalHeadline(e: JournalEntry): String {
    val amount = e.amount.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
    val unit = e.unit.let { if (it == "drink" || it == "drinks" || it == "standard") "standard" else it }
    return "${e.kind.replaceFirstChar { it.uppercase() }} · $amount $unit"
}

internal fun journalName(e: JournalEntry): String? = e.name?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }

@Composable
internal fun moments(data: TodayData, nav: TodayNav): List<Moment> {
    val c = LocalMetricColors.current
    val zone = data.curves.window.zone // the day's own clock (D31)
    return buildList {
        data.night?.let { n ->
            val asleep = data.sleepTstMin?.let { hm(it) + " asleep · " } ?: ""
            add(Moment(n.end, clockOf(n.end, zone), Icons.Rounded.Bedtime, c.sleepTone, "Woke up", "$asleep${clockOf(n.start, zone)} – ${clockOf(n.end, zone)}",
                n.deviceScore?.toString(), nav.sleep))
        }
        data.journal.forEach { e ->
            val ts = e.ts.toEpochMilli()
            add(Moment(ts, clockOf(ts, zone), journalIcon(e.kind, e.name), journalTone(e.kind), journalName(e) ?: e.kind.replaceFirstChar { it.uppercase() },
                journalHeadline(e), null, nav.journal))
        }
        data.stress?.let { s ->
            add(Moment(s.maxAt, clockOf(s.maxAt, zone), Icons.Rounded.Psychology, c.stressTone, "Stress peak", "Highest of the day",
                "${s.max.roundToInt()}", nav.stress))
        }
        data.workouts.forEach { w ->
            val start = w.start.toEpochMilli()
            add(Moment(start, clockOf(start, zone), Icons.AutoMirrored.Rounded.DirectionsRun, c.heartTone, "Workout", workoutLine(w), null, nav.activity))
        }
        if (data.day == LocalDate.now()) data.strain.valueOrNull?.let { s ->
            val now = System.currentTimeMillis()
            add(Moment(now, clockOf(now, zone), Icons.Rounded.Bolt, c.strainTone, "Now", "Strain so far today", "%.1f".format(s), nav.activity))
        }
    }.sortedBy { it.at }
}

/** "48 min · avg 138 bpm · 520 kcal" */
internal fun workoutLine(w: Workout): String = listOfNotNull(
    "${w.durationS / 60} min",
    w.avgHr?.let { "avg $it bpm" },
    w.calories?.let { "$it kcal" },
).joinToString(" · ")
