package app.strap.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.ui.Loading
import app.strap.ui.components.Bar
import app.strap.ui.components.CardFoot
import app.strap.ui.components.CardHeader
import app.strap.ui.components.CardValue
import app.strap.ui.components.Gauge
import app.strap.ui.components.IconCircle
import app.strap.ui.components.InsightCard
import app.strap.ui.components.Note
import app.strap.ui.components.Point
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.SpanLine
import app.strap.ui.components.Subtle
import app.strap.ui.components.Tile
import app.strap.ui.components.TileGrid
import app.strap.ui.components.clockOf
import app.strap.ui.components.duration
import app.strap.ui.components.hm
import app.strap.ui.components.tinted
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.loadRange
import app.strap.sync.DayStatus
import app.strap.ui.components.color
import app.strap.ui.components.icon
import androidx.compose.ui.unit.Dp
import app.strap.ui.today.workoutLine
import app.strap.ui.today.workoutKcal
import androidx.compose.ui.graphics.vector.ImageVector
import strap.science.DaySummary
import strap.science.Sports
import app.strap.integrations.Covering
import app.strap.integrations.GymSession
import app.strap.integrations.Track
import strap.science.Workout
import strap.science.WorkoutDetail
import strap.science.shown
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Zone colours, rest to zone 5: grey, blue, green, yellow, orange, red (Bevel's). */
internal val ZONE_COLORS = listOf(Color(0xFF9AA3A0), Color(0xFF6FB7E8), Color(0xFF3F9B5E), Color(0xFFE1B12C), Color(0xFFE8792B), Color(0xFFD9433E))
private val ZONE_PCT = listOf(0.5, 0.6, 0.7, 0.8, 0.9)

/** "Rest · < 92", "Zone 1 · 92–109" … by percent of max HR. */
private fun zoneRows(hrmax: Int?): List<Pair<String, String>> {
    val names = listOf("Rest") + (1..5).map { "Zone $it" }
    if (hrmax == null || hrmax <= 0) return names.mapIndexed { i, n -> n to if (i == 0) "< 50%" else "${(ZONE_PCT[i - 1] * 100).toInt()}%+" }
    val lows = ZONE_PCT.map { (it * hrmax).roundToInt() }
    return names.mapIndexed { i, n -> n to when (i) { 0 -> "< ${lows[0]}"; 5 -> "${lows[4]}+"; else -> "${lows[i - 1]}–${lows[i] - 1}" } }
}

/** Minutes per zone as one bar of colours, then each zone's name, bpm range, share and minutes. */
@Composable
internal fun ZonesTable(minutes: List<Int>, hrmax: Int?, rest: Boolean = true) {
    val total = minutes.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        minutes.forEachIndexed { i, m -> if (m > 0) Box(Modifier.weight(m.toFloat()).height(10.dp).background(ZONE_COLORS[i])) }
    }
    zoneRows(hrmax).withIndex().reversed().filter { rest || it.index > 0 }.forEach { (i, row) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(ZONE_COLORS[i]))
            Text(row.first, style = RidgeType.rowTitle, modifier = Modifier.padding(start = 10.dp).width(64.dp))
            Subtle(row.second, Modifier.weight(1f), RidgeType.body)
            Subtle("${(100 * minutes[i] / total)}%", Modifier.width(44.dp), RidgeType.body)
            Text(duration(minutes[i].toDouble()), style = RidgeType.label, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
    }
}

/**
 * Strain for a day (Bevel's strain screen): the ring of 21, active time and energy, the day in
 * words, the minutes in each heart-rate zone (moved here from Fitness) and the day's workouts.
 */
@Composable
fun StrainScreen(api: Reader, day: LocalDate, onGym: (GymSession) -> Unit = {}, onTrack: (Long) -> Unit = {}, onWorkout: (Workout) -> Unit) {
    var summary by remember { mutableStateOf<DaySummary?>(null) }
    var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }
    var gym by remember { mutableStateOf<List<GymSession>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day) {
        try {
            summary = api.summary(day)
            workouts = api.workouts(day, day)
            gym = api.gymSessions(day, day).sortedBy { it.start }
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    LaunchedEffect(day) { tracks = try { api.tracks(day) } catch (_: ApiException) { emptyList() } }
    val s = summary ?: return Loading(error)
    val c = LocalMetricColors.current
    val strain = s.strain.shown
    val value = strain?.value
    val active = s.activity.mvpaMin.shown?.value
    val energy = s.activity.totalCalories.shown?.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Gauge(value?.let { "%.1f".format(it) } ?: "—", if (day == LocalDate.now()) "of 21 · so far" else "of 21", value?.let { (it / 21).toFloat() }, c.strain, 184.dp, 44.sp, stroke = 6f)
            }
        }
        item {
            TileGrid(listOf(
                { m -> Tile("Active time", active?.let { duration(it) } ?: "—", m) },
                { m -> Tile("Calories", energy?.let { "%,d".format(it.roundToInt()) } ?: "—", m, "kcal") },
            ))
        }
        item {
            val w = workouts.firstOrNull()
            val title = when { value == null -> "Strain"; value >= 18 -> "Hard day"; value >= 14 -> "Solid day"; else -> "Light day" }
            val text = buildList {
                strain?.let { add((if (day == LocalDate.now()) "Today's" else "The day's") + " cardio load is ${it.cardioLoad.roundToInt()}" + (it.typicalLoad?.let { t -> ", against a typical ${t.roundToInt()}." } ?: ".")) }
                val fromWorkouts = strain?.workoutLoad
                val everyday = strain?.everydayLoad
                when {
                    // Rows from before the split (D37) carry no parts.
                    fromWorkouts == null || everyday == null ->
                        if (w != null) add("Most of it came from a ${w.durationS / 60}-minute workout${w.avgHr?.let { " at an average of $it bpm" } ?: ""}.")
                    w == null && strain.cardioLoad.roundToInt() == 0 ->
                        add("No workout and no brisk movement" + (if (day == LocalDate.now()) " yet." else ".") + " Sitting with a raised heart rate adds nothing.")
                    w == null -> add("No workout: it all comes from walking and other movement. Sitting with a raised heart rate adds nothing.")
                    else -> {
                        val workout = "${fromWorkouts.roundToInt()} from " + if (workouts.size > 1) "${workouts.size} workouts"
                            else "the ${w.durationS / 60}-minute workout${w.avgHr?.let { " at an average of $it bpm" } ?: ""}"
                        add(if (fromWorkouts >= everyday) "Most of it, $workout; ${everyday.roundToInt()} from everyday movement."
                            else "$workout, ${everyday.roundToInt()} from everyday movement.".replaceFirstChar { it.uppercase() })
                    }
                }
            }.joinToString(" ")
            InsightCard(Icons.Rounded.AutoAwesome, c.strainTone, title, text)
        }
        item {
            RidgeCard(spacing = 10.dp) {
                CardHeader("Heart-rate zones", "waking minutes", icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
                val zones = strain?.zoneMinutes
                if (zones == null) Subtle(s.strain.let { (it as? strap.science.Card.Withheld)?.message } ?: "Not computed yet.")
                else {
                    // The day counts minutes from zone 1 up; below 50 % of max HR is not kept.
                    ZonesTable(listOf(0) + zones, strain.hrmax, rest = false)
                }
            }
        }
        // The list shows each activity once (D44); the load above still counts every strap workout.
        val shown = Covering.uncovered(workouts, Covering.gymWindows(gym) + Covering.trackWindows(tracks))
        val trained = Covering.trained(tracks, Covering.uncovered(workouts, Covering.gymWindows(gym)))
        if (shown.isNotEmpty() || gym.isNotEmpty() || trained.isNotEmpty()) item { SectionTitle("Workouts") }
        if (gym.isNotEmpty()) item { GymRows(gym, onGym) }
        if (trained.isNotEmpty()) item { TrackRows(trained, all = false, held = { t -> Covering.inside(workouts, t.start, t.end).size }) { onTrack(it) } }
        if (shown.isNotEmpty()) item { WorkoutRows(shown, onWorkout) }
    }
}

/** Where a workout's record comes from (D43). */
internal fun originOf(w: Workout): String = when (w.origin) {
    Workout.Origin.STRAP -> "Helio Strap"
    Workout.Origin.EDITED -> "Helio Strap, edited by you"
    Workout.Origin.ADDED -> "Added by you"
}

/** A workout's icon, by the kind of sport (issue #4); a run's figure when the sport is unknown. */
internal fun sportIcon(sport: Int): ImageVector = when (Sports.kind(sport)) {
    Sports.Kind.RUN, Sports.Kind.OTHER -> Icons.AutoMirrored.Rounded.DirectionsRun
    Sports.Kind.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Sports.Kind.HIKE -> Icons.Rounded.Hiking
    Sports.Kind.RIDE -> Icons.AutoMirrored.Rounded.DirectionsBike
    Sports.Kind.SWIM -> Icons.Rounded.Pool
    Sports.Kind.STRENGTH -> Icons.Rounded.FitnessCenter
    Sports.Kind.CLIMB -> Icons.Rounded.Landscape
}

/** Workouts as one card of rows; each opens its detail. */
@Composable
internal fun WorkoutRows(workouts: List<Workout>, onWorkout: (Workout) -> Unit) {
    val tone = LocalMetricColors.current.strainTone
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM")
    RidgeCard(spacing = 0.dp, padding = 8.dp) {
        workouts.forEach { w ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onWorkout(w) }.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconCircle(sportIcon(w.sport), tone.container, tone.accent, size = 36.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(Sports.label(w.sport), style = RidgeType.rowTitle)
                    Subtle(workoutLine(w), style = RidgeType.body)
                }
                Subtle("${w.start.atZone(ZoneId.systemDefault()).format(fmt)} · ${clockOf(w.start.toEpochMilli())}", style = RidgeType.caption)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One workout (Bevel's activity details): its strain and figures, heart rate through it with
 * the zones behind, minutes per zone, cardio focus, and what it added to the 7-day load.
 */
@Composable
fun WorkoutScreen(api: Reader, start: Instant, actions: SessionActions? = null, onGym: (GymSession) -> Unit = {}, onTrack: (Long) -> Unit = {}, onGone: () -> Unit = {}) {
    var editing by remember { mutableStateOf(false) }
    // What the owner recorded elsewhere around it (D44): read with the day, Dawarich only on display (D42).
    var gym by remember { mutableStateOf<List<GymSession>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var workout by remember { mutableStateOf<Workout?>(null) }
    var detail by remember { mutableStateOf<WorkoutDetail?>(null) }
    var load by remember { mutableStateOf<Map<LocalDate, Double>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val day = start.atZone(ZoneId.systemDefault()).toLocalDate()
    LaunchedEffect(start) {
        try {
            val w = api.workouts(day, day).firstOrNull { it.start == start }
            workout = w
            detail = w?.let { api.workoutDetail(it) }
            load = api.daily("cardio_load", day.minusDays(27), day).associate { it.day to it.value }
            error = if (w == null) "This workout is no longer on the phone." else null
            if (w != null) {
                gym = api.gymSessions(day, day).filter { Covering.overlaps(w, it.start, it.end) }
                tracks = try { api.tracks(day).filter { Covering.overlaps(w, it.start, it.end) } } catch (_: ApiException) { emptyList() }
            }
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val w = workout ?: return Loading(error)
    if (editing && actions != null) SessionSheet(SessionEdit.Edit(w), onDismiss = { editing = false }, heartRate = actions.heartRate) { sport, from, to ->
        editing = false
        actions.edit(w, sport, from, to)
        onGone()
    }
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val d = detail
    val end = w.start.plusSeconds(w.durationS.toLong())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IconCircle(sportIcon(w.sport), c.strain, MaterialTheme.colorScheme.surface, size = 48.dp, iconSize = 24.dp)
                Spacer(Modifier.height(6.dp))
                Text(Sports.label(w.sport), style = RidgeType.topTitle)
                Subtle("${day.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${clockOf(w.start.toEpochMilli())} – ${clockOf(end.toEpochMilli())} · ${originOf(w)}", style = RidgeType.body)
            }
        }
        if (actions != null) item {
            // Edits change what Ridge shows and counts, never the strap's record (D43); each leaves this page.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                when (w.origin) {
                    Workout.Origin.ADDED -> OutlinedButton(onClick = { actions.hide(w); onGone() }) { Text("Delete") }
                    else -> OutlinedButton(onClick = { actions.hide(w); onGone() }) { Text("Hide") }
                }
                if (w.origin == Workout.Origin.EDITED) w.overlayId?.let { id ->
                    TextButton(onClick = { actions.undo(id); onGone() }) { Text("Undo edit") }
                }
            }
        }
        if (gym.isNotEmpty() || tracks.isNotEmpty()) {
            item { Subtle("Part of what you recorded elsewhere, and shown as it in your lists. Its load, strain and calories still count from this workout.", style = RidgeType.body) }
            if (gym.isNotEmpty()) item { GymRows(gym, onGym) }
            if (tracks.isNotEmpty()) item { TrackRows(tracks, all = false) { onTrack(it) } }
        }
        item {
            RidgeCard(spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gauge(d?.strain?.let { "%.1f".format(it) } ?: "—", null, d?.strain?.let { (it / 21).toFloat() }, c.strain, 64.dp, 17.sp, stroke = 9f)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Activity strain", style = RidgeType.rowTitle)
                        Subtle(d?.strain?.let { s -> when { s >= 14 -> "High load from this workout"; s >= 9 -> "Moderate load from this workout"; else -> "Light load from this workout" } }
                            ?: "Needs a profile and a resting heart rate", style = RidgeType.body)
                    }
                }
                // A session without the strap's summary (added or re-timed, D43) takes its figures from the per-minute stream.
                val avgHr = w.avgHr ?: d?.hr?.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.roundToInt()
                val maxHr = w.maxHr ?: d?.hr?.maxOfOrNull { it.value }?.roundToInt()
                CardFoot(listOf("Duration" to duration(w.durationS / 60.0), "Avg HR" to (avgHr?.let { "$it bpm" } ?: "—"), "Max HR" to (maxHr?.let { "$it bpm" } ?: "—")))
                CardFoot(listOf("Calories" to (workoutKcal(w) ?: "—"), "Distance" to (w.distanceM?.let { "%.1f km".format(it / 1000) } ?: "—"),
                    "Load" to (d?.let { "+${it.trimp.roundToInt()}" } ?: "—")))
                if (w.calories == null && w.estKcal != null) {
                    Subtle("Calories estimated from heart rate. The day's calories count these minutes from your movement.", style = RidgeType.caption)
                }
            }
        }
        if (d != null && d.hr.isNotEmpty()) {
            item {
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Heart rate", "avg ${d.hr.map { it.value }.average().roundToInt()} bpm", icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
                    SpanLine(d.hr.map { Point(it.at.toEpochMilli(), it.value) }, w.start.toEpochMilli(), end.toEpochMilli(), c.heart)
                }
            }
            item {
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Heart-rate zones", "by % of max HR (${d.hrmax})", icon = Icons.Rounded.MonitorHeart, tone = c.stressTone)
                    ZonesTable(d.zoneMinutes, d.hrmax)
                }
            }
            item {
                val z = d.zoneMinutes
                val total = z.sum().coerceAtLeast(1)
                val focus = listOf("Anaerobic" to z[5], "High aerobic" to z[3] + z[4], "Low aerobic" to z[0] + z[1] + z[2])
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Cardio focus", icon = Icons.Rounded.Speed, tone = c.strainTone)
                    focus.forEach { (name, m) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = RidgeType.rowTitle, modifier = Modifier.weight(1f))
                            Text("${100 * m / total}%", style = RidgeType.label)
                        }
                        Bar(m / total.toFloat(), c.strain, height = 6.dp)
                    }
                }
            }
            item {
                val range = loadRange(load, day)
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Load", icon = Icons.Rounded.Speed, tone = c.strainTone)
                    CardValue("+${d.trimp.roundToInt()}", "cardio load", range?.let { Note(it.words, if (it.words == "In range") r.good else if (it.words == "Load spike") r.bad else MaterialTheme.colorScheme.onSurfaceVariant) })
                    range?.let { lr ->
                        CardFoot(listOf("7-day load" to "${(lr.acute - d.trimp / 7).roundToInt()} → ${lr.acute.roundToInt()}", "Range" to "${lr.low.roundToInt()}–${lr.high.roundToInt()}",
                            "Of the day" to (load[day]?.takeIf { it > 0 }?.let { "${(100 * d.trimp / it).roundToInt()}%" } ?: "—")))
                    }
                }
            }
        }
    }
}

/** What a day shows in Fitness (the prototype's): its status when marked, else a run on a workout day, else a faded walk. */
@Composable
internal fun DayMark(status: DayStatus?, workout: Boolean, faded: Float, size: Dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        status != null && status != DayStatus.ACTIVE -> Icon(status.icon, status.label, tint = status.color(), modifier = Modifier.size(size))
        workout -> Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null, tint = LocalMetricColors.current.strain, modifier = Modifier.size(size))
        else -> Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = muted.copy(alpha = faded), modifier = Modifier.size(size))
    }
}

/** The training calendar: a month with what you did each day, its total, and its workouts (each opens its detail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingCalendarSheet(api: Reader, onDismiss: () -> Unit, onDay: (LocalDate) -> Unit, onGym: (GymSession) -> Unit = {}, onWorkout: (Workout) -> Unit) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var all by remember { mutableStateOf<List<Workout>>(emptyList()) }
    var gym by remember { mutableStateOf<List<GymSession>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<LocalDate, DayStatus>>(emptyMap()) }
    LaunchedEffect(month) {
        all = try { api.workouts(month.atDay(1), minOf(month.atEndOfMonth(), today)) } catch (_: ApiException) { emptyList() }
        gym = try { api.gymSessions(month.atDay(1), minOf(month.atEndOfMonth(), today)) } catch (_: ApiException) { emptyList() }
        statuses = try { api.statuses(month.atDay(1), month.atEndOfMonth()) } catch (_: ApiException) { emptyMap() }
    }
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val zone = ZoneId.systemDefault()
    // A month of Dawarich tracks is not fetched (D42): here only openGym sessions take in the strap's workouts (D44).
    val workouts = Covering.uncovered(all, Covering.gymWindows(gym))
    val active = workouts.map { it.start.atZone(zone).toLocalDate() }.toSet() + gym.map { it.start.atZone(zone).toLocalDate() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = r.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        LazyColumn(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = RidgeType.sectionTitle, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                            Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        }
                    }
                    val lead = month.atDay(1).dayOfWeek.value - 1
                    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { d ->
                                // A past day opens on Fitness: its workouts, gym sessions, walks and rides.
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).let { m -> if (d != null && d <= today) m.clickable { onDay(d) } else m }
                                    .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (d != null) {
                                        DayMark(statuses[d], d in active, if (d <= today) 0.45f else 0.15f, 20.dp)
                                        Text("${d.dayOfMonth}", style = RidgeType.caption, color = if (d == today) c.strain else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            val count = workouts.size + gym.size
            val minutes = workouts.sumOf { it.durationS } / 60.0 + gym.sumOf { java.time.Duration.between(it.start, it.end).seconds } / 60.0
            item { Subtle("$count ${if (count == 1) "workout" else "workouts"} · ${hm(minutes)}", style = RidgeType.body) }
            if (gym.isNotEmpty()) item { GymRows(gym, onGym) }
            if (workouts.isNotEmpty()) item { WorkoutRows(workouts, onWorkout) }
        }
    }
}
