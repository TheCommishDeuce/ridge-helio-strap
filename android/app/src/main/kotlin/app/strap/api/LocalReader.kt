package app.strap.api

import app.strap.integrations.Dawarich
import app.strap.integrations.DawarichFormat
import app.strap.integrations.GymSession
import app.strap.integrations.OpenGymSync
import app.strap.integrations.Track
import app.strap.store.LocalStore
import app.strap.sync.DayStatus
import app.strap.sync.JournalEntry
import app.strap.sync.Owner
import app.strap.sync.PhoneScience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import strap.science.Bucket
import strap.science.BucketSeries
import strap.science.DaySeries
import strap.science.DaySummary
import strap.science.DayRings
import strap.science.Derived
import strap.science.IllnessCheck
import strap.science.Science
import strap.science.Workout
import strap.science.TimedValue
import strap.science.WorkoutDetail
import strap.science.heartRate
import strap.science.bucketSeries
import strap.science.dayRings
import strap.science.illnessCheck
import strap.science.workoutDetail
import strap.science.sessionFigures
import strap.science.daySeries
import strap.science.daySummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The screens' reads worked out on the phone (D34 S3), from its own store and `strap-science`. Needs no network. */
class LocalReader(
    private val science: PhoneScience,
    private val store: LocalStore,
    private val owner: Owner,
    private val openGym: OpenGymSync,
    private val dawarich: Dawarich,
) : Reader {
    override suspend fun summary(day: LocalDate): DaySummary = on(day) { s -> s.daySummary(day, Instant.now()) }

    override suspend fun daySeries(day: LocalDate, series: List<String>): DaySeries = on(day) { s -> s.daySeries(day, series) }

    override suspend fun buckets(series: String, from: LocalDate, to: LocalDate, bucket: Bucket): BucketSeries =
        over(from, to) { s -> s.bucketSeries(series, from, to, bucket) }

    override suspend fun daily(metric: String, from: LocalDate, to: LocalDate): List<Derived> = off { store.derived.range(metric, from, to) }

    override suspend fun workouts(from: LocalDate, to: LocalDate): List<Workout> = over(from, to) { s ->
        s.data.workouts(s.zones.bounds(from).first, s.zones.bounds(to).second).map { w ->
            // A session added or re-timed has none of the strap's figures: the rows show its heart rate
            // and Keytel's estimate instead (issue #4, D47), for display only.
            if (w.avgHr != null) w else s.sessionFigures(w.start, w.durationS)?.let { f ->
                w.copy(avgHr = f.avgHr, maxHr = f.maxHr, estKcal = f.estKcal.takeIf { w.calories == null })
            } ?: w
        }.sortedByDescending { it.start }
    }

    override suspend fun journal(from: LocalDate, to: LocalDate): List<JournalEntry> = over(from, to) { s ->
        owner.journal(s.zones.bounds(from).first, s.zones.bounds(to).second)
    }

    override suspend fun statuses(from: LocalDate, to: LocalDate): Map<LocalDate, DayStatus> = off { owner.statuses(from, to) }

    override suspend fun rings(from: LocalDate, to: LocalDate): List<DayRings> = over(from, to) { s -> s.dayRings(from, to) }

    override suspend fun illnessCheck(day: LocalDate): IllnessCheck = on(day) { s -> s.illnessCheck(day) }

    override suspend fun workoutDetail(workout: Workout): WorkoutDetail? {
        val day = workout.start.atZone(ZoneId.systemDefault()).toLocalDate()
        return on(day) { s -> s.workoutDetail(workout.start, workout.durationS) }
    }

    override suspend fun windowDetail(start: Instant, end: Instant): WorkoutDetail? =
        on(zones().dateOf(start)) { s -> s.workoutDetail(start, java.time.Duration.between(start, end).seconds.toInt()) }

    override suspend fun heartRate(start: Instant, end: Instant): List<TimedValue> = on(zones().dateOf(start)) { s -> s.heartRate(start, end) }

    override suspend fun refreshGym() {
        openGym.pullIfOlder()
    }

    override suspend fun gymSessions(from: LocalDate, to: LocalDate): List<GymSession> =
        over(from, to) { s -> store.gymSessions(s.zones.bounds(from).first, s.zones.bounds(to).second).sortedByDescending { it.start } }

    override suspend fun gymSession(id: String): GymSession? = withContext(Dispatchers.IO) { store.gymSession(id) }

    /** The day's tracks cut by how the owner moved (D42): the strap's steps say walking or running, its workouts riding. */
    override suspend fun tracks(day: LocalDate): List<Track> {
        val (from, until) = zones().bounds(day)
        val raw = dawarich.tracks(from, until)
        if (raw.isEmpty()) return raw
        return on(day) { s ->
            val first = raw.minOf { it.start }.minusSeconds(120)
            val last = raw.maxOf { it.end }.plusSeconds(120)
            val steps = s.data.samples("steps_per_minute", first, last).associate { it.ts.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) to it.value }
            val workouts = s.data.workouts(first.minus(java.time.Duration.ofHours(12)), last).map { it.start to it.start.plusSeconds(it.durationS.toLong()) }
            raw.map { t -> t.copy(split = DawarichFormat.split(t.fixes, steps, workouts)) }
                .filter { (it.split?.ownM ?: Int.MAX_VALUE) >= DawarichFormat.MIN_OWN_M }
        }
    }

    /** The owner's day boundaries (D31); the phone's zone until one is recorded. */
    private fun zones(): strap.science.Zones = owner.zones() ?: strap.science.Zones(ZoneId.systemDefault().id)

    /** Over the science that shows [day]. */
    private suspend fun <T> on(day: LocalDate, block: (Science) -> T): T = science.at(day).let { s -> off { block(s) } }

    /** Over the raw data of [first] through [last]. */
    private suspend fun <T> over(first: LocalDate, last: LocalDate, block: (Science) -> T): T =
        science.over(first, last).let { s -> off { block(s) } }

    /** Off the main thread, once the stored rows are current, and every failure as the screens expect one. */
    private suspend fun <T> off(block: () -> T): T {
        science.ready()
        return withContext(Dispatchers.Default) {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                throw ApiException(e.message ?: "Not available.", e)
            }
        }
    }
}
