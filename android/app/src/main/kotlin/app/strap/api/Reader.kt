package app.strap.api

import app.strap.sync.JournalEntry
import strap.science.Bucket
import strap.science.BucketSeries
import strap.science.DaySeries
import strap.science.DaySummary
import strap.science.Derived
import strap.science.Workout
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
}
