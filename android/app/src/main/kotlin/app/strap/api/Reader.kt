package app.strap.api

import app.strap.sync.DayStatus
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
import app.strap.integrations.GymSession
import app.strap.integrations.Track
import strap.science.TimedValue
import java.time.Instant
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

    /** The days marked sick, injured or on a break, [from] through [to]; a day not in it is active. */
    suspend fun statuses(from: LocalDate, to: LocalDate): Map<LocalDate, DayStatus>

    /** Each day's recovery, strain, the strap's sleep score and mean stress, [from] through [to] (the calendar). */
    suspend fun rings(from: LocalDate, to: LocalDate): List<DayRings>

    /** The illness check behind the flag on [day]: each limb's change and the baseline nights there are. */
    suspend fun illnessCheck(day: LocalDate): IllnessCheck

    /** One workout's own load, zones and heart rate; null without a profile or resting HR. */
    suspend fun workoutDetail(workout: Workout): WorkoutDetail?

    /** The same for any window (an openGym session, a Dawarich track); null without a profile or resting HR. */
    suspend fun windowDetail(start: Instant, end: Instant): WorkoutDetail?

    /** The strap's per-minute heart rate over [start, end), with or without a profile. */
    suspend fun heartRate(start: Instant, end: Instant): List<TimedValue>

    /** Asks openGym for changes if it has not been asked in the last minutes (D41); a failure shows in Settings. */
    suspend fun refreshGym()

    /** The openGym sessions that started from [from] through [to], newest first (D41); empty when not connected. */
    suspend fun gymSessions(from: LocalDate, to: LocalDate): List<GymSession>

    suspend fun gymSession(id: String): GymSession?

    /** The day's walks and rides from Dawarich, oldest first (D42); empty when not connected. Throws [ApiException] when it cannot be reached. */
    suspend fun tracks(day: LocalDate): List<Track>
}
