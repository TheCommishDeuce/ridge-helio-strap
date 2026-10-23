package app.strap.api

import app.strap.store.LocalStore
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
import strap.science.WorkoutDetail
import strap.science.bucketSeries
import strap.science.dayRings
import strap.science.illnessCheck
import strap.science.workoutDetail
import strap.science.daySeries
import strap.science.daySummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The screens' reads worked out on the phone (D34 S3), from its own store and `strap-science`. Needs no network. */
class LocalReader(private val science: PhoneScience, private val store: LocalStore, private val owner: Owner) : Reader {
    override suspend fun summary(day: LocalDate): DaySummary = on(day) { s -> s.daySummary(day, Instant.now()) }

    override suspend fun daySeries(day: LocalDate, series: List<String>): DaySeries = on(day) { s -> s.daySeries(day, series) }

    override suspend fun buckets(series: String, from: LocalDate, to: LocalDate, bucket: Bucket): BucketSeries =
        over(from, to) { s -> s.bucketSeries(series, from, to, bucket) }

    override suspend fun daily(metric: String, from: LocalDate, to: LocalDate): List<Derived> = off { store.derived.range(metric, from, to) }

    override suspend fun workouts(from: LocalDate, to: LocalDate): List<Workout> = over(from, to) { s ->
        s.data.workouts(s.zones.bounds(from).first, s.zones.bounds(to).second).sortedByDescending { it.start }
    }

    override suspend fun journal(from: LocalDate, to: LocalDate): List<JournalEntry> = over(from, to) { s ->
        owner.journal(s.zones.bounds(from).first, s.zones.bounds(to).second)
    }

    override suspend fun rings(from: LocalDate, to: LocalDate): List<DayRings> = over(from, to) { s -> s.dayRings(from, to) }

    override suspend fun illnessCheck(day: LocalDate): IllnessCheck = on(day) { s -> s.illnessCheck(day) }

    override suspend fun workoutDetail(workout: Workout): WorkoutDetail? {
        val day = workout.start.atZone(ZoneId.systemDefault()).toLocalDate()
        return on(day) { s -> s.workoutDetail(workout.start, workout.durationS) }
    }

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
