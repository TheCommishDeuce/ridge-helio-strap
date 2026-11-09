package app.strap.integrations

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import strap.science.SleepSession
import strap.science.Sports
import strap.science.TimedValue
import strap.science.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * One day as Health Connect gets it (D45): Ridge's own results for the day, the strap's nights
 * with their stages, the workouts as the owner says they were, and the per-minute heart rate (the
 * one raw stream that goes: the owner, 2026-11-09). Pure:
 * the same day always builds the same records under the same ids, so writing a day again
 * replaces what was there.
 */
object HealthExport {
    /** Every record type Ridge writes; the permissions asked for are exactly these. */
    val TYPES: List<KClass<out Record>> = listOf(
        StepsRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class,
        BasalMetabolicRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class, RespiratoryRateRecord::class, Vo2MaxRecord::class,
        SleepSessionRecord::class, ExerciseSessionRecord::class, HeartRateRecord::class,
    )

    /** The types whose records come and go with the owner's edits (a hidden workout, a night the strap re-cut). */
    val SESSION_TYPES: List<KClass<out Record>> = listOf(SleepSessionRecord::class, ExerciseSessionRecord::class)

    private val STRAP = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Amazfit", model = "Helio Strap")

    /**
     * [day]'s records. [bounds] are the day's start and end and [zone] its timezone (D31);
     * [value] reads one of Ridge's results for the day; [nights] are the sleeps that ended in the
     * day, [workouts] those that started in it, [heartRate] the strap's reading for each minute of it. [version] orders rewrites: a later one wins.
     */
    fun day(
        day: LocalDate,
        bounds: Pair<Instant, Instant>,
        zone: ZoneId,
        value: (String) -> Double?,
        nights: List<SleepSession>,
        workouts: List<Workout>,
        now: Instant,
        heartRate: List<TimedValue> = emptyList(),
        version: Long = now.epochSecond,
    ): List<Record> {
        val (start, dayEnd) = bounds
        val end = minOf(dayEnd, now) // Health Connect takes nothing from the future: today runs until now
        if (end <= start) return emptyList()
        fun offset(at: Instant): ZoneOffset = zone.rules.getOffset(at)
        fun meta(id: String) = Metadata.autoRecorded(clientRecordId = "ridge-$id", clientRecordVersion = version, device = STRAP)
        // The night's numbers are stamped at waking; a day with no main sleep, at its start.
        val woke = nights.filter { it.isMain }.maxOfOrNull { it.end }?.takeIf { it > start && it <= end } ?: start
        val records = mutableListOf<Record>()
        value("steps_total")?.roundToLong()?.takeIf { it in 1..1_000_000 }?.let {
            records += StepsRecord(start, offset(start), end, offset(end), it, meta("steps-$day"))
        }
        value("distance_m_daily")?.takeIf { it > 0 && it <= 1_000_000 }?.let {
            records += DistanceRecord(start, offset(start), end, offset(end), Length.meters(it), meta("distance-$day"))
        }
        value("total_calories")?.takeIf { it > 0 }?.let {
            records += TotalCaloriesBurnedRecord(start, offset(start), end, offset(end), Energy.kilocalories(it), meta("total-kcal-$day"))
        }
        value("active_calories")?.takeIf { it > 0 }?.let {
            records += ActiveCaloriesBurnedRecord(start, offset(start), end, offset(end), Energy.kilocalories(it), meta("active-kcal-$day"))
        }
        value("basal_calories")?.takeIf { it > 0 && it <= 10_000 }?.let {
            records += BasalMetabolicRateRecord(start, offset(start), Power.kilocaloriesPerDay(it), meta("bmr-$day"))
        }
        value("rhr_daily")?.roundToLong()?.takeIf { it in 1..300 }?.let {
            records += RestingHeartRateRecord(woke, offset(woke), it, meta("rhr-$day"))
        }
        value("hrv_sleep_avg")?.takeIf { it >= 1 && it <= 200 }?.let {
            records += HeartRateVariabilityRmssdRecord(woke, offset(woke), it, meta("hrv-$day"))
        }
        value("spo2_overnight")?.takeIf { it > 0 && it <= 100 }?.let {
            records += OxygenSaturationRecord(woke, offset(woke), Percentage(it), meta("spo2-$day"))
        }
        value("respiratory_rate_sleep")?.takeIf { it > 0 && it <= 1000 }?.let {
            records += RespiratoryRateRecord(woke, offset(woke), it, meta("rr-$day"))
        }
        value("vo2max_estimate")?.takeIf { it > 0 && it <= 100 }?.let {
            records += Vo2MaxRecord(time = start, zoneOffset = offset(start), metadata = meta("vo2max-$day"), vo2MillilitersPerMinuteKilogram = it, measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER)
        }
        nights.filter { it.end > it.start && it.end <= now }.forEach { n ->
            records += SleepSessionRecord(
                startTime = n.start, startZoneOffset = offset(n.start), endTime = n.end, endZoneOffset = offset(n.end),
                metadata = meta("sleep-${n.start.epochSecond}"), title = if (n.isMain) null else "Nap", stages = stages(n),
            )
        }
        workouts.filter { it.durationS > 0 && it.end <= now }.forEach { w ->
            records += ExerciseSessionRecord(
                startTime = w.start, startZoneOffset = offset(w.start), endTime = w.end, endZoneOffset = offset(w.end),
                metadata = if (w.origin == Workout.Origin.ADDED) Metadata.manualEntry(clientRecordId = "ridge-workout-${w.start.epochSecond}", clientRecordVersion = version, device = STRAP)
                else meta("workout-${w.start.epochSecond}"),
                exerciseType = exerciseType(w.sport), title = Sports.label(w.sport),
            )
        }
        // One record a day holding the minutes the strap read: a gap in wearing is a gap in the samples.
        heartRate.filter { it.at >= start && it.at < end && it.value >= 1 && it.value <= 300 }.sortedBy { it.at }.takeIf { it.isNotEmpty() }?.let { beats ->
            val last = minOf(beats.last().at.plusSeconds(60), maxOf(end, beats.last().at.plusSeconds(1)))
            records += HeartRateRecord(
                startTime = beats.first().at, startZoneOffset = offset(beats.first().at), endTime = last, endZoneOffset = offset(last),
                samples = beats.map { HeartRateRecord.Sample(it.at, it.value.roundToLong()) }, metadata = meta("hr-$day"),
            )
        }
        return records
    }

    /** The strap's stages inside the night, in order and never overlapping (Health Connect refuses those). */
    internal fun stages(n: SleepSession): List<SleepSessionRecord.Stage> {
        var cursor = n.start
        return n.stages.sortedBy { it.startMs }.mapNotNull { s ->
            val type = STAGES[s.code] ?: return@mapNotNull null
            val from = maxOf(Instant.ofEpochMilli(s.startMs), cursor)
            val to = minOf(Instant.ofEpochMilli(s.endMs), n.end)
            if (to <= from) return@mapNotNull null
            cursor = to
            SleepSessionRecord.Stage(from, to, type)
        }
    }

    private val STAGES = mapOf(
        4 to SleepSessionRecord.STAGE_TYPE_LIGHT, 5 to SleepSessionRecord.STAGE_TYPE_DEEP,
        7 to SleepSessionRecord.STAGE_TYPE_AWAKE, 8 to SleepSessionRecord.STAGE_TYPE_REM,
    )

    /** Health Connect's exercise type for the strap's sport code; the strap's own "Activity" and the unlisted are "other workout". */
    internal fun exerciseType(sport: Int): Int = when (sport) {
        2 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        143 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        9 -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        23 -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        49 -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        60 -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        61 -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        76 -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        191 -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        else -> when (Sports.kind(sport)) {
            Sports.Kind.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            Sports.Kind.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            Sports.Kind.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            Sports.Kind.RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            Sports.Kind.SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            Sports.Kind.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            Sports.Kind.CLIMB -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
            Sports.Kind.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }
}
