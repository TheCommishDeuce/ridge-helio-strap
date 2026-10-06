package app.strap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.ui.components.BaselineRow
import app.strap.ui.components.BaselineRows
import app.strap.ui.components.GaugeBlock
import app.strap.ui.components.Moment
import app.strap.ui.components.Note
import app.strap.ui.components.NoteLine
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionLabel
import app.strap.ui.components.Subtle
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
import org.json.JSONObject
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
    val selectDay: (LocalDate) -> Unit,
)

/** Today: week strip, three gauges, the recovery card, the day's moments, stress, heart and steps. */
@Composable
fun TodayContent(data: TodayData, nav: TodayNav, modifier: Modifier = Modifier) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val recovery = data.recovery.valueOrNull
    var hiddenIllness by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { WeekStrip(data.stripDays, data.recoveryByDay, data.day, nav.selectDay) }
        data.illness?.takeIf { it != hiddenIllness }?.let { text -> item { IllnessBanner(text) { hiddenIllness = text } } }
        item {
            val strain = data.strain.valueOrNull
            val score = data.night?.deviceScore
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                GaugeBlock(strain?.let { "%.1f".format(it) } ?: "—", "of 21", strain?.let { (it / 21).toFloat() }, c.strain, "Strain", null,
                    96.dp, 24.sp, stroke = 8f, onClick = nav.activity)
                GaugeBlock(recovery?.let { "${it.roundToInt()}%" } ?: "—", "ready", recovery?.let { (it / 100).toFloat() },
                    recovery?.let(r::zone) ?: c.recovery, "Recovery",
                    changeNote(recovery?.let { v -> data.recoveryWeekBefore(data.day)?.let { v - it } }, "vs week"),
                    150.dp, 42.sp, onClick = nav.recovery)
                // The strap's own 0-100 score, named as the strap's (we compute no composite sleep score).
                GaugeBlock(score?.toString() ?: "—", "Amazfit", score?.let { it / 100f }, c.sleep, "Sleep", null,
                    96.dp, 24.sp, stroke = 8f, onClick = nav.sleep)
            }
        }
        item { RecoveryCard(data, nav.recovery) }
        item { SectionLabel("Your day", "sleep, workouts, journal") }
        item {
            val moments = moments(data, nav)
            if (moments.isEmpty()) RidgeCard { Subtle("Nothing recorded for this day yet.") } else Timeline(moments)
        }
        item { SectionLabel("Heart & stress", if (data.day == LocalDate.now()) "vs your usual by now" else "vs your usual day") }
        item { StressCard(data, nav.stress) }
        item { HeartCard(data, nav.heart) }
        item { StepsRow(data, nav.steps) }
    }
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
private fun RecoveryCard(data: TodayData, onOpen: () -> Unit) {
    val recovery = data.recovery
    RidgeCard(onClick = onOpen, spacing = 6.dp) {
        when (recovery) {
            is Reading.Withheld -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recovery", style = RidgeType.cardTitle, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Subtle(recovery.message)
            }
            is Reading.Value -> {
                val zone = LocalRidgeColors.current.zone(recovery.value)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(zone))
                    Text(zoneName(recovery.value), style = RidgeType.cardTitle, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val week = data.recoveryWeekTo(data.day)?.let { " · week average ${it.roundToInt()}%" } ?: ""
                Subtle("${recovery.value.roundToInt()}%$week. Each part against your last 42 days:")
                BaselineRows(data.factors.map { f -> { BaselineRow(FACTOR_LABELS.getValue(f.key), factorUsual(f), factorValue(f), factorTrackOf(f)) } })
            }
        }
    }
}

@Composable
private fun IllnessBanner(text: String, onDismiss: () -> Unit) {
    val tone = LocalMetricColors.current.stressTone
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tone.container).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The server's fixed, reviewed sentence (names the baseline, says "not a diagnosis").
        Text(text, style = RidgeType.body, color = tone.onContainer, modifier = Modifier.weight(1f).padding(top = 4.dp))
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = tone.onContainer) }
    }
}

internal fun journalIcon(e: JSONObject): ImageVector = when {
    e.getString("kind") == "weight" -> Icons.Rounded.MonitorWeight
    e.getString("kind") == "alcohol" -> Icons.Rounded.WineBar
    else -> when (e.optString("name")) {
        "espresso" -> Icons.Rounded.CoffeeMaker
        "tea" -> Icons.Rounded.EmojiFoodBeverage
        else -> Icons.Rounded.Coffee
    }
}

/** "Caffeine · 95 mg" */
internal fun journalHeadline(e: JSONObject): String {
    val amount = e.getDouble("amount").let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
    val unit = e.optString("unit").let { if (it == "drink" || it == "drinks" || it == "standard") "standard" else it }
    return "${e.getString("kind").replaceFirstChar { it.uppercase() }} · $amount $unit"
}

internal fun journalName(e: JSONObject): String? = e.optString("name").takeIf { it.isNotEmpty() && it != "null" }?.replaceFirstChar { it.uppercase() }

@Composable
private fun moments(data: TodayData, nav: TodayNav): List<Moment> {
    val c = LocalMetricColors.current
    val zone = data.curves.window.zone // the day's own clock (D31)
    return buildList {
        data.night?.let { n ->
            val asleep = data.sleepTstMin?.let { hm(it) + " asleep · " } ?: ""
            add(Moment(n.end, clockOf(n.end, zone), Icons.Rounded.Bedtime, c.sleepTone, "Woke up", "$asleep${clockOf(n.start, zone)} – ${clockOf(n.end, zone)}",
                n.deviceScore?.toString(), nav.sleep))
        }
        data.journal.forEach { e ->
            val ts = e.getLong("ts")
            add(Moment(ts, clockOf(ts, zone), journalIcon(e), c.stressTone, journalName(e) ?: e.getString("kind").replaceFirstChar { it.uppercase() },
                journalHeadline(e), null, nav.journal))
        }
        data.stress?.let { s ->
            add(Moment(s.maxAt, clockOf(s.maxAt, zone), Icons.Rounded.Psychology, c.stressTone, "Stress peak", "Highest of the day",
                "${s.max.roundToInt()}", nav.stress))
        }
        data.workouts.forEach { w ->
            val start = w.getLong("start")
            add(Moment(start, clockOf(start, zone), Icons.AutoMirrored.Rounded.DirectionsRun, c.heartTone, "Workout", workoutLine(w), null, nav.activity))
        }
        if (data.day == LocalDate.now()) data.strain.valueOrNull?.let { s ->
            val now = System.currentTimeMillis()
            add(Moment(now, clockOf(now, zone), Icons.Rounded.Bolt, c.strainTone, "Now", "Strain so far today", "%.1f".format(s), nav.activity))
        }
    }.sortedBy { it.at }
}

/** "48 min · avg 138 bpm · 520 kcal" */
internal fun workoutLine(w: JSONObject): String = listOfNotNull(
    "${w.getInt("duration_s") / 60} min",
    if (w.isNull("avg_hr")) null else "avg ${w.getInt("avg_hr")} bpm",
    if (w.isNull("calories")) null else "${w.getInt("calories")} kcal",
).joinToString(" · ")
