package app.strap.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.integrations.ExerciseNames
import app.strap.integrations.GymSession
import app.strap.integrations.GymSet
import app.strap.integrations.Moved
import app.strap.integrations.Track
import app.strap.ui.Loading
import app.strap.ui.components.CardFoot
import app.strap.ui.components.CardHeader
import app.strap.ui.components.Gauge
import app.strap.ui.components.IconCircle
import app.strap.ui.components.Point
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Span
import app.strap.ui.components.SpanLine
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.components.duration
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.RidgeType
import strap.science.TimedValue
import strap.science.WorkoutDetail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** A Dawarich track id that means "all of the day's tracks". */
const val ALL_TRACKS: Long = -1

// ── rows on Fitness ─────────────────────────────────────────────────────────

/** The day's openGym sessions (D41), each opening its detail. */
@Composable
internal fun GymRows(sessions: List<GymSession>, onGym: (GymSession) -> Unit) {
    val tone = LocalMetricColors.current.strainTone
    RowsCard(sessions.map { g ->
        RowItem(Icons.Rounded.FitnessCenter, g.name ?: "Gym session",
            "${duration(minutes(g.start, g.end))} · ${g.entries.size} exercises · ${g.entries.sumOf { e -> e.sets.count { it.done } }} sets",
            "${clockOf(g.start.toEpochMilli())} · openGym", tone.container to tone.accent) { onGym(g) }
    })
}

/** The day's walks and rides from Dawarich (D42), each opening its map; a last row shows them all together. */
@Composable
internal fun TrackRows(tracks: List<Track>, all: Boolean = true, onTrack: (Long) -> Unit) {
    val tone = LocalMetricColors.current.stepsTone
    RowsCard(tracks.map { t ->
        RowItem(modeIcon(t), modeName(t), listOfNotNull(t.ownM?.let(::km), duration(t.ownS / 60.0),
            t.split?.carried?.size?.takeIf { it > 0 }?.let { if (it == 1) "1 ride left out" else "$it rides left out" }).joinToString(" · "),
            "${clockOf(t.start.toEpochMilli())} · Dawarich", tone.container to tone.accent) { onTrack(t.id) }
    } + listOfNotNull(tracks.takeIf { all && it.size > 1 }?.let {
        RowItem(Icons.Rounded.Map, "All on one map", "${it.size} tracks", "", tone.container to tone.accent) { onTrack(ALL_TRACKS) }
    }))
}

private class RowItem(val icon: ImageVector, val title: String, val line: String, val right: String, val colors: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, val onClick: () -> Unit)

@Composable
private fun RowsCard(rows: List<RowItem>) {
    RidgeCard(spacing = 0.dp, padding = 8.dp) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = r.onClick).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconCircle(r.icon, r.colors.first, r.colors.second, size = 36.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(r.title, style = RidgeType.rowTitle)
                    Subtle(r.line, style = RidgeType.body)
                }
                Subtle(r.right, style = RidgeType.caption)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── one openGym session ─────────────────────────────────────────────────────

/**
 * One openGym session (D41): the strap's per-minute heart rate through it, with each exercise
 * as a band and each set as a tick when openGym recorded when sets were done; its load and
 * zones; then the exercises with their sets and the heart rate in the minute each was done.
 */
@Composable
fun GymSessionScreen(api: Reader, names: ExerciseNames, id: String) {
    var session by remember { mutableStateOf<GymSession?>(null) }
    var hr by remember { mutableStateOf<List<TimedValue>>(emptyList()) }
    var detail by remember { mutableStateOf<WorkoutDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try {
            val g = api.gymSession(id)
            session = g
            if (g != null) {
                hr = api.heartRate(g.start, g.end)
                detail = api.windowDetail(g.start, g.end)
            }
            error = if (g == null) "This session is no longer in openGym." else null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val g = session ?: return Loading(error)
    val c = LocalMetricColors.current
    val byMinute = hr.associate { it.at.truncatedTo(ChronoUnit.MINUTES) to it.value }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(Icons.Rounded.FitnessCenter, g.name ?: "Gym session", g.start, g.end, "openGym") }
        item {
            RidgeCard(spacing = 12.dp) {
                StrainRow(detail, "this session")
                CardFoot(listOf("Duration" to duration(minutes(g.start, g.end)), "Avg HR" to bpm(hr.map { it.value }.average()), "Max HR" to bpm(hr.maxOfOrNull { it.value })))
                CardFoot(listOf("Sets" to "${g.entries.sumOf { e -> e.sets.count { it.done && !it.warmup } }}",
                    "Volume" to (g.volume?.let { "%,d %s".format(it.roundToInt(), g.unit) } ?: "—"), "Load" to (detail?.let { "+${it.trimp.roundToInt()}" } ?: "—")))
            }
        }
        item {
            RidgeCard(spacing = 10.dp) {
                CardHeader("Heart rate", "per minute", icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
                if (hr.size < 2) Subtle("The strap recorded no heart rate during this session.")
                else {
                    SpanLine(hr.map { Point(it.at.toEpochMilli(), it.value) }, g.start.toEpochMilli(), g.end.toEpochMilli(), c.heart,
                        bands = g.entries.mapNotNull { e -> e.span?.let { (a, b) -> Span(a.toEpochMilli() - 60_000, b.toEpochMilli()) } },
                        ticks = g.entries.flatMap { e -> e.sets.mapNotNull { it.at?.toEpochMilli() } })
                    Subtle(if (g.timed) "Shaded: each exercise, from its first set to its last. Marks: each set, when it was ticked done."
                        else "openGym did not record when these sets were done, so this is the whole session.", style = RidgeType.caption)
                }
            }
        }
        detail?.let { d ->
            item {
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Heart-rate zones", "by % of max HR (${d.hrmax})", icon = Icons.Rounded.MonitorHeart, tone = c.stressTone)
                    ZonesTable(d.zoneMinutes, d.hrmax)
                }
            }
        }
        g.entries.forEach { e ->
            item {
                RidgeCard(spacing = 6.dp) {
                    val during = e.span?.let { (a, b) -> hr.filter { !it.at.isBefore(a.minusSeconds(60)) && !it.at.isAfter(b) }.map { it.value } }.orEmpty()
                    CardHeader(names.of(e.exercise), during.takeIf { it.isNotEmpty() }?.let { "avg ${it.average().roundToInt()} · max ${it.max().roundToInt()} bpm" },
                        icon = Icons.Rounded.FitnessCenter, tone = c.strainTone)
                    e.sets.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Subtle("${i + 1}", Modifier.width(24.dp), RidgeType.body)
                            Text(setText(s, g.unit), style = RidgeType.rowTitle, modifier = Modifier.weight(1f),
                                color = if (s.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Subtle(s.at?.let { at -> byMinute[at.truncatedTo(ChronoUnit.MINUTES)]?.let { "${it.roundToInt()} bpm" } } ?: "",
                                Modifier.width(72.dp), RidgeType.body)
                        }
                    }
                }
            }
        }
    }
}

private fun setText(s: GymSet, unit: String): String {
    val main = when {
        s.minutes != null -> "%s min".format(num(s.minutes)) + (s.speed?.let { " at ${num(it)}" } ?: "")
        s.weight != null && s.weight > 0 -> "${num(s.weight)} $unit × ${s.reps ?: "—"}"
        else -> "${s.reps ?: "—"} reps"
    }
    val extra = listOfNotNull("warm-up".takeIf { s.warmup }, s.rir?.let { "RIR ${num(it)}" }, s.rpe?.let { "RPE ${num(it)}" }, "not done".takeIf { !s.done })
    return (listOf(main) + extra).joinToString(" · ")
}

private fun num(v: Double): String = if (v == v.roundToInt().toDouble()) "${v.roundToInt()}" else "%.1f".format(v)

// ── one Dawarich track, or the whole day's ──────────────────────────────────

/**
 * A walk or ride from Dawarich (D42), or all of [day]'s ([ALL_TRACKS]): the path on a map,
 * Dawarich's distance and time, and the strap's heart rate and zones through it. Ridge
 * derives nothing from the path itself.
 */
@Composable
fun TrackScreen(api: Reader, day: LocalDate, id: Long, onTrack: (Long) -> Unit) {
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var hr by remember { mutableStateOf<List<TimedValue>>(emptyList()) }
    var detail by remember { mutableStateOf<WorkoutDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day, id) {
        try {
            val shown = api.tracks(day).filter { id == ALL_TRACKS || it.id == id }
            tracks = shown
            if (shown.size == 1) {
                hr = api.heartRate(shown[0].start, shown[0].end)
                detail = api.windowDetail(shown[0].start, shown[0].end)
            }
            error = if (shown.isEmpty()) "This track is no longer in Dawarich." else null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val ts = tracks?.takeIf { it.isNotEmpty() } ?: return Loading(error)
    val c = LocalMetricColors.current
    val one = ts.singleOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (one != null) Header(modeIcon(one), modeName(one), one.start, one.end, "Dawarich")
            else Header(Icons.Rounded.Map, "Walks and rides", ts.first().start, ts.last().end, "Dawarich")
        }
        item {
            RidgeCard(spacing = 0.dp, padding = 0.dp) {
                TrackMap(ts.flatMap { t -> t.split?.legs?.map { MapLine(it.path, it.carried) } ?: listOf(MapLine(t.path)) }, c.steps,
                    Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp)))
            }
        }
        item {
            RidgeCard(spacing = 12.dp) {
                if (one != null) StrainRow(detail, "this ${modeName(one).lowercase()}")
                CardFoot(listOf("Distance" to (ts.mapNotNull { it.ownM }.takeIf { it.isNotEmpty() }?.sum()?.let(::km) ?: "—"),
                    "Time" to duration(ts.sumOf { it.ownS } / 60.0),
                    if (one != null) "Avg HR" to bpm(hr.map { it.value }.average()) else "Tracks" to "${ts.size}"))
                Subtle(sourceLine(ts, one != null), style = RidgeType.caption)
            }
        }
        if (one != null) {
            item {
                RidgeCard(spacing = 10.dp) {
                    CardHeader("Heart rate", "per minute", icon = Icons.Rounded.MonitorHeart, tone = c.heartTone)
                    if (hr.size < 2) Subtle("The strap recorded no heart rate during this track.")
                    else {
                        val carried = one.split?.carried.orEmpty()
                        SpanLine(hr.map { Point(it.at.toEpochMilli(), it.value) }, one.start.toEpochMilli(), one.end.toEpochMilli(), c.heart,
                            bands = carried.map { Span(it.start.toEpochMilli(), it.end.toEpochMilli()) })
                        if (carried.isNotEmpty()) Subtle("Shaded: carried (a tram, a train or a car). Strain and zones cover the whole track.", style = RidgeType.caption)
                    }
                }
            }
            detail?.let { d ->
                item {
                    RidgeCard(spacing = 10.dp) {
                        CardHeader("Heart-rate zones", "by % of max HR (${d.hrmax})", icon = Icons.Rounded.MonitorHeart, tone = c.stressTone)
                        ZonesTable(d.zoneMinutes, d.hrmax)
                    }
                }
            }
        } else {
            item { TrackRows(ts, all = false, onTrack = onTrack) }
        }
    }
}

/** Where the figures come from: the parts moved by the owner when the track could be cut, else Dawarich's whole track. */
private fun sourceLine(ts: List<Track>, one: Boolean): String {
    val carried = ts.sumOf { it.split?.carried?.size ?: 0 }
    val hr = if (one) " Heart rate is the strap's." else ""
    return when {
        ts.any { it.split == null } -> "Distance and time are Dawarich's for the whole track.$hr"
        carried == 0 -> "Distance and time from your tracker's points.$hr"
        else -> "Distance and time count only what you walked, ran or rode yourself, from your tracker's points. " +
            "${if (carried == 1) "1 stretch" else "$carried stretches"} faster than walking, with no steps on the strap and no workout it recorded, " +
            "${if (carried == 1) "was" else "were"} a ride on something (dashed on the map) and ${if (carried == 1) "is" else "are"} left out. " +
            "Dawarich counts ${ts.mapNotNull { it.distanceM }.sum().let(::km)} for the whole track.$hr"
    }
}

// ── shared pieces ───────────────────────────────────────────────────────────

@Composable
private fun Header(icon: ImageVector, title: String, start: Instant, end: Instant, source: String) {
    val c = LocalMetricColors.current
    val day = start.atZone(ZoneId.systemDefault()).toLocalDate()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconCircle(icon, c.strain, MaterialTheme.colorScheme.surface, size = 48.dp, iconSize = 24.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, style = RidgeType.topTitle)
        Subtle("${day.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${clockOf(start.toEpochMilli())} – ${clockOf(end.toEpochMilli())} · $source", style = RidgeType.body)
    }
}

/** The strain gauge for a window and its words, as a strap workout shows it. */
@Composable
private fun StrainRow(d: WorkoutDetail?, what: String) {
    val c = LocalMetricColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Gauge(d?.strain?.let { "%.1f".format(it) } ?: "—", null, d?.strain?.let { (it / 21).toFloat() }, c.strain, 64.dp, 17.sp, stroke = 9f)
        Column(Modifier.padding(start = 12.dp)) {
            Text("Activity strain", style = RidgeType.rowTitle)
            Subtle(d?.strain?.let { s -> when { s >= 14 -> "High load from $what"; s >= 9 -> "Moderate load from $what"; else -> "Light load from $what" } }
                ?: "Needs a profile and a resting heart rate", style = RidgeType.body)
        }
    }
}

private fun minutes(a: Instant, b: Instant): Double = java.time.Duration.between(a, b).seconds / 60.0

private fun bpm(v: Double?): String = v?.takeIf { !it.isNaN() }?.let { "${it.roundToInt()} bpm" } ?: "—"

private fun km(m: Int): String = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

/** Walk, run or ride by the strap (D42): running steps are a run, walking steps a walk, a recorded workout without steps a ride. */
private fun modeName(t: Track): String = when (t.moved) {
    Moved.RAN -> "Run"
    Moved.RODE -> "Ride"
    else -> "Walk"
}

private fun modeIcon(t: Track): ImageVector = when (t.moved) {
    Moved.RAN -> Icons.AutoMirrored.Rounded.DirectionsRun
    Moved.RODE -> Icons.AutoMirrored.Rounded.DirectionsBike
    else -> Icons.AutoMirrored.Rounded.DirectionsWalk
}
