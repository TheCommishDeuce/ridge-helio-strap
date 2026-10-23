package app.strap.api

import app.strap.sync.JournalEntry
import strap.science.Bucket
import strap.science.BucketSeries
import strap.science.DaySeries
import strap.science.DaySummary
import strap.science.DayRings
import strap.science.Derived
import strap.science.IllnessCheck
import strap.science.Workout
import strap.science.WorkoutDetail
import java.time.LocalDate

/**
 * What the screens read, worked out on the phone ([LocalReader], D34). A read that cannot be
 * answered (a range too long for its buckets) throws [ApiException].
 */
interface Reader {
    suspend fun summary(day: LocalDate): DaySummary

    suspend fun daySeries(day: LocalDate, series: List<String>): DaySeries

    suspend fun buckets(series: String, from: LocalDate, to: LocalDate, bucket: Bucket): BucketSeries

    /** A derived metric's rows from [from] through [to], oldest first. */
    suspend fun daily(metric: String, from: LocalDate, to: LocalDate): List<Derived>

    /** The workouts that started from [from] through [to], newest first. */
    suspend fun workouts(from: LocalDate, to: LocalDate): List<Workout>

    /** The journal from [from] through [to], newest first. */
    suspend fun journal(from: LocalDate, to: LocalDate): List<JournalEntry>

    /** Each day's recovery, strain, the strap's sleep score and mean stress, [from] through [to] (the calendar). */
    suspend fun rings(from: LocalDate, to: LocalDate): List<DayRings>

    /** The illness check behind the flag on [day]: each limb's change and the baseline nights there are. */
    suspend fun illnessCheck(day: LocalDate): IllnessCheck

    /** One workout's own load, zones and heart rate; null without a profile or resting HR. */
    suspend fun workoutDetail(workout: Workout): WorkoutDetail?
}
