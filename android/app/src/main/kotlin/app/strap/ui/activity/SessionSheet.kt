package app.strap.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.Point
import app.strap.ui.components.Span
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import strap.science.SessionOverlay
import strap.science.Sports
import strap.science.TimedValue
import strap.science.Workout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * What the Fitness screens may change about workouts (D43). The activity wires it to the owner's
 * store and the derive; nothing here is written to the strap.
 */
class SessionActions(
    val add: (sport: Int, start: Instant, end: Instant) -> Unit,
    val edit: (w: Workout, sport: Int, start: Instant, end: Instant) -> Unit,
    val hide: (w: Workout) -> Unit,
    /** Takes back one change by its id: a hidden or edited strap workout shows as recorded again. */
    val undo: (id: String) -> Unit,
    /** The strap workouts hidden on a day, to show them again. */
    val hidden: suspend (LocalDate) -> List<SessionOverlay>,
    /** The strap workouts on a day that Ridge left out as short walks (D46), and keeping one as a workout after all. */
    val walks: suspend (LocalDate) -> List<Workout>,
    val keep: (w: Workout) -> Unit,
    /** The per-minute heart rate over a window, for the sheet's graph (issue #4). */
    val heartRate: suspend (start: Instant, end: Instant) -> List<TimedValue> = { _, _ -> emptyList() },
)

/** Where the sheet is: adding a session on [day], or editing [workout]. */
sealed interface SessionEdit {
    data class Add(val day: LocalDate) : SessionEdit
    data class Edit(val workout: Workout) : SessionEdit
}

/**
 * Adds a session or edits a workout: its sport, and when it started and ended. An end at or
 * before the start is taken as the next day (a session over midnight).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(edit: SessionEdit, onDismiss: () -> Unit, heartRate: (suspend (Instant, Instant) -> List<TimedValue>)? = null,
    onSave: (sport: Int, start: Instant, end: Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val w = (edit as? SessionEdit.Edit)?.workout
    val day = w?.start?.atZone(zone)?.toLocalDate() ?: (edit as SessionEdit.Add).day
    var sport by remember { mutableStateOf(w?.sport ?: Sports.UNKNOWN) }
    val shownFrom = remember { w?.start?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: defaultStart(day) }
    val shownTo = remember { w?.end?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: shownFrom.plusHours(1) }
    var from by remember { mutableStateOf(shownFrom) }
    var to by remember { mutableStateOf(shownTo) }
    var picking by remember { mutableStateOf<String?>(null) } // "sport", "from", "to"
    // A time left as it was keeps the workout's own, seconds included: a sport-only edit is then the
    // strap's exact window, and keeps its calories and heart-rate summary (Sessions.apply).
    val start = if (w != null && from == shownFrom) w.start else day.atTime(from).atZone(zone).toInstant()
    val end = if (w != null && from == shownFrom && to == shownTo) w.end
        else (if (to > from) day else day.plusDays(1)).atTime(to).atZone(zone).toInstant()
    val minutes = Duration.between(start, end).toMinutes()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The day's heart rate, to find the session by eye (issue #4): scrub, then set the start or end there.
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    var hr by remember { mutableStateOf<List<Point>>(emptyList()) }
    LaunchedEffect(day) {
        hr = try { heartRate?.invoke(dayStart, dayEnd).orEmpty().map { Point(it.at.toEpochMilli(), it.value) } } catch (_: Exception) { emptyList() }
    }
    var scrubbed by remember { mutableStateOf<Point?>(null) }
    // Around the session by default, so a minute is wide enough to hit; the whole day to look further.
    var wholeDay by remember { mutableStateOf(false) }
    val (viewStart, viewEnd) = if (wholeDay) dayStart.toEpochMilli() to dayEnd.toEpochMilli()
        else sessionView(start.toEpochMilli(), end.toEpochMilli(), dayStart.toEpochMilli(), dayEnd.toEpochMilli())
    fun setStart(t: LocalTime) {
        // Moving the start keeps the length, as a calendar does.
        val length = Duration.between(start, end)
        from = t
        to = t.plus(length)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (w == null) "Add a session" else "Edit workout", style = RidgeType.sheetTitle)
            Subtle(
                if (w == null) "Heart rate, strain and zones come from the strap's minutes in this window."
                else if (w.origin == Workout.Origin.ADDED) "A session you added."
                else "The strap's record stays as it was; Ridge shows your version.",
                style = RidgeType.body,
            )
            if (hr.isNotEmpty()) {
                val tone = LocalMetricColors.current.strainTone
                Spacer(Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Subtle(scrubbed?.let { "${clockOf(it.t)} · ${it.v.roundToInt()} bpm" } ?: "Press and drag to find the start and end.",
                        Modifier.weight(1f), style = RidgeType.caption)
                    TextButton(onClick = { wholeDay = !wholeDay }) { Text(if (wholeDay) "Zoom in" else "Whole day") }
                }
                DayLineChart(hr.filter { it.t in viewStart..viewEnd }, viewStart, LocalMetricColors.current.heart, 3 * 60_000L, "bpm",
                    dayEnd = viewEnd, shaded = listOf(Span(start.toEpochMilli(), end.toEpochMilli())), height = 110.dp,
                    shadeColor = tone.container.copy(alpha = 0.7f), spanAxis = !wholeDay) { p -> if (p != null) scrubbed = p }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val at = scrubbed?.let { Instant.ofEpochMilli(it.t).atZone(zone).toLocalTime().withSecond(0).withNano(0) }
                    OutlinedButton(onClick = { at?.let { setStart(it) } }, enabled = at != null, modifier = Modifier.weight(1f)) { Text("Set start") }
                    OutlinedButton(onClick = { at?.let { to = it } }, enabled = at != null, modifier = Modifier.weight(1f)) { Text("Set end") }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            PickRow("Sport", if (sport == Sports.UNKNOWN) "Not set" else Sports.label(sport)) { picking = "sport" }
            PickRow("Started", "%02d:%02d".format(from.hour, from.minute)) { picking = "from" }
            PickRow("Ended", "%02d:%02d".format(to.hour, to.minute) + if (to <= from) " next day" else "") { picking = "to" }
            Subtle(if (minutes in 1..24 * 60) "${minutes / 60} h ${minutes % 60} min" else "A session lasts up to a day.", style = RidgeType.caption,
                modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = muted) }
                Button(onClick = { onSave(sport, start, end) }, enabled = minutes in 1..24 * 60) { Text("Save") }
            }
        }
    }
    when (picking) {
        "sport" -> SportDialog(sport, onDismiss = { picking = null }) { sport = it; picking = null }
        "from" -> TimeDialog("Started", from, onDismiss = { picking = null }) { setStart(it); picking = null }
        "to" -> TimeDialog("Ended", to, onDismiss = { picking = null }) { to = it; picking = null }
    }
}

private const val HOUR_MS = 3_600_000L

/**
 * The part of the day the sheet's graph shows around a session from [start] to [end] (epoch ms):
 * 90 minutes either side, out to whole hours, made a multiple of 4 hours so the quarter labels are
 * round times, and kept inside [dayStart]..[dayEnd]. The whole day when that is no smaller.
 */
internal fun sessionView(start: Long, end: Long, dayStart: Long, dayEnd: Long): Pair<Long, Long> {
    val pad = 90 * 60_000L
    val lo = dayStart + Math.floorDiv(start - pad - dayStart, HOUR_MS) * HOUR_MS
    val hi = dayStart + Math.floorDiv(end + pad - dayStart + HOUR_MS - 1, HOUR_MS) * HOUR_MS
    val span = Math.floorDiv(hi - lo + 4 * HOUR_MS - 1, 4 * HOUR_MS) * 4 * HOUR_MS
    if (span >= dayEnd - dayStart) return dayStart to dayEnd
    val first = lo.coerceAtMost(dayEnd - span).coerceAtLeast(dayStart)
    return first to first + span
}

/** The day's start for a new session: now's hour today (an hour back), else 18:00. */
private fun defaultStart(day: LocalDate): LocalTime =
    if (day == LocalDate.now()) LocalTime.now().minusHours(1).withMinute(0).withSecond(0).withNano(0) else LocalTime.of(18, 0)

@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = RidgeType.rowTitle, modifier = Modifier.weight(1f))
        Text(value, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SportDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    val sports = remember { Sports.all() }
    val shown = sports.filter { it.second.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sport") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        Text(name, style = RidgeType.rowTitle, color = if (code == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 12.dp))
                    }
                    if (query.isBlank()) item {
                        Text("Not listed", style = RidgeType.rowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(Sports.UNKNOWN) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, time: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
