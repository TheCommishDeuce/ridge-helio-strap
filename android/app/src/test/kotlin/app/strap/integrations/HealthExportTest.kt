package app.strap.integrations

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import strap.science.SleepSession
import strap.science.Stage
import strap.science.TimedValue
import strap.science.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthExportTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val day = LocalDate.of(2026, 11, 9)
    private val start = day.atStartOfDay(zone).toInstant()
    private val bounds = start to day.plusDays(1).atStartOfDay(zone).toInstant()
    private val later = bounds.second.plusSeconds(3600)
    private val values = mapOf(
        "steps_total" to 8123.4, "distance_m_daily" to 6100.0, "total_calories" to 2600.0, "active_calories" to 700.0,
        "basal_calories" to 1900.0, "rhr_daily" to 51.6, "hrv_sleep_avg" to 48.2, "spo2_overnight" to 96.0,
        "respiratory_rate_sleep" to 14.5, "vo2max_estimate" to 44.0,
    )
    private fun at(h: Int, m: Int = 0) = start.plusSeconds(h * 3600L + m * 60L)
    private fun ms(i: Instant) = i.toEpochMilli()
    private val night = SleepSession(start.minusSeconds(3600), at(7), stages = listOf(
        Stage(ms(start.minusSeconds(3600)), ms(at(2)), 4), Stage(ms(at(2)), ms(at(3)), 5),
        Stage(ms(at(2, 30)), ms(at(4)), 8), // overlaps the deep stage before it
        Stage(ms(at(4)), ms(at(5)), 128), // a gap marker
        Stage(ms(at(5)), ms(at(7, 30)), 7), // runs past the session's end
    ))

    private fun build(nights: List<SleepSession> = listOf(night), workouts: List<Workout> = emptyList(), now: Instant = later, v: Map<String, Double> = values) =
        HealthExport.day(day, bounds, zone, v::get, nights, workouts, now)

    @Test
    fun `a whole day writes one record per result, each under an id of its own`() {
        val records = build()
        assertEquals(11, records.size)
        assertEquals(records.size, records.map { it.metadata.clientRecordId }.toSet().size)
        assertTrue(records.all { it.metadata.clientRecordId!!.startsWith("ridge-") })
        val steps = records.filterIsInstance<StepsRecord>().single()
        assertEquals(8123, steps.count)
        assertEquals(bounds, steps.startTime to steps.endTime)
        assertEquals(ZoneOffset.ofHours(1), steps.startZoneOffset)
        assertEquals("ridge-steps-2026-11-09", steps.metadata.clientRecordId)
    }

    @Test
    fun `the same day built later carries a later version, so it replaces the earlier write`() {
        val first = build(now = later).first().metadata.clientRecordVersion
        val second = build(now = later.plusSeconds(60)).first().metadata.clientRecordVersion
        assertTrue(second > first)
    }

    @Test
    fun `today runs until now, never into the future`() {
        val now = at(15, 30)
        val records = build(now = now)
        assertEquals(now, records.filterIsInstance<TotalCaloriesBurnedRecord>().single().endTime)
        assertTrue(build(now = start).isEmpty()) // a day that has not begun
    }

    @Test
    fun `the night's numbers are stamped at waking, or at the day's start without a main sleep`() {
        assertEquals(at(7), build().filterIsInstance<RestingHeartRateRecord>().single().time)
        assertEquals(52, build().filterIsInstance<RestingHeartRateRecord>().single().beatsPerMinute)
        val nap = SleepSession(at(13), at(13, 40), kind = SleepSession.NAP)
        assertEquals(start, build(nights = listOf(nap)).filterIsInstance<HeartRateVariabilityRmssdRecord>().single().time)
    }

    @Test
    fun `sleep stages are in order, inside the night and never overlap, gap markers dropped`() {
        val sleep = build().filterIsInstance<SleepSessionRecord>().single()
        assertNull(sleep.title)
        assertEquals(
            listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM, SleepSessionRecord.STAGE_TYPE_AWAKE),
            sleep.stages.map { it.stage },
        )
        assertEquals(at(3), sleep.stages[2].startTime) // pushed past the deep stage
        assertEquals(at(7), sleep.stages.last().endTime) // cut at the session's end
        sleep.stages.zipWithNext().forEach { (a, b) -> assertTrue(a.endTime <= b.startTime) }
        assertEquals("Nap", build(nights = listOf(SleepSession(at(13), at(13, 40), kind = SleepSession.NAP))).filterIsInstance<SleepSessionRecord>().single().title)
    }

    @Test
    fun `workouts carry the sport, an added session is a manual entry, one still running waits`() {
        val ride = Workout(at(18), sport = 4, durationS = 1800)
        val added = Workout(at(10), sport = 52, durationS = 3600, origin = Workout.Origin.ADDED)
        val records = build(workouts = listOf(ride, added)).filterIsInstance<ExerciseSessionRecord>()
        assertEquals(listOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING), records.map { it.exerciseType })
        assertEquals(listOf("Outdoor cycling", "Strength training"), records.map { it.title })
        assertEquals(listOf(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, Metadata.RECORDING_METHOD_MANUAL_ENTRY), records.map { it.metadata.recordingMethod })
        assertEquals("ridge-workout-${at(18).epochSecond}", records.first().metadata.clientRecordId)
        assertTrue(build(workouts = listOf(ride), now = at(18, 10)).filterIsInstance<ExerciseSessionRecord>().isEmpty())
    }

    @Test
    fun `heart rate goes as one record a day, a sample for each minute the strap read`() {
        val beats = listOf(TimedValue(at(9), 61.4), TimedValue(at(9, 1), 63.0), TimedValue(at(14), 140.6), TimedValue(at(14, 1), 0.0), TimedValue(later, 70.0))
        val hr = HealthExport.day(day, bounds, zone, { null }, emptyList(), emptyList(), later, beats).single() as HeartRateRecord
        assertEquals(listOf(61L, 63L, 141L), hr.samples.map { it.beatsPerMinute }) // the zero and the minute past the day are left out
        assertEquals(at(9) to at(14, 1), hr.startTime to hr.endTime)
        assertEquals("ridge-hr-2026-11-09", hr.metadata.clientRecordId)
        val now = at(14).plusSeconds(20)
        assertEquals(now, (HealthExport.day(day, bounds, zone, { null }, emptyList(), emptyList(), now, beats).single() as HeartRateRecord).endTime)
    }

    @Test
    fun `the strap's own Activity and unlisted sports are an other workout`() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthExport.exerciseType(223))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, HealthExport.exerciseType(9999))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, HealthExport.exerciseType(2))
    }

    @Test
    fun `a result the day does not have, or one out of any sane range, writes nothing`() {
        assertTrue(build(nights = emptyList(), v = emptyMap()).isEmpty())
        assertTrue(build(nights = emptyList(), v = mapOf("steps_total" to 0.0, "spo2_overnight" to 140.0, "rhr_daily" to 0.2)).isEmpty())
    }
}
