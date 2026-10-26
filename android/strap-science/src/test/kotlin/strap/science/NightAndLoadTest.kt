package strap.science

import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KOLKATA = "Asia/Kolkata"

internal fun at(day: LocalDate, h: Int, m: Int = 0, zone: String = KOLKATA): Instant = Zones.wall(day, LocalTime.of(h, m), zone)

// Ported from server/tests/derive/test_cardio_load_rhr.py, test_partial_stage_breakdown.py and
// test_session_source.py. (test_fragmented_night.py checks a server log line; the phone has none.)
class NightAndLoadTest {
    private val day = LocalDate.of(2026, 6, 16)
    private val profile = Profile(175f, "male", LocalDate.of(1994, 1, 1))
    private val weight = listOf(WeightEntry(at(day, 7), 72f))
    private fun wakingHr(minutes: Int, bpm: Double) = (0 until minutes).map { Sample(at(day, 10).plusSeconds(60L * it), "hr", bpm) }

    private fun steps(minutes: Int, perMinute: Double, from: Int = 0) =
        (from until from + minutes).map { Sample(at(day, 10).plusSeconds(60L * it), "steps_per_minute", perMinute) }

    private fun cardio(rhrDay: LocalDate?, samples: List<Sample> = wakingHr(60, 120.0), workouts: List<Workout> = emptyList()): Pair<Science, MemoryDerived> {
        val store = MemoryDerived()
        rhrDay?.let { store.put(Derived(it, "rhr_daily", 52.0)) }
        val science = Science(MemoryData(Zones(KOLKATA), samples, workouts = workouts, profile = profile, weights = weight), store)
        science.cardioLoad(day)
        return science to store
    }

    @Test
    fun `no measured resting HR withholds the whole cardio load`() {
        val (_, store) = cardio(null)
        assertNull(store.get(day, "cardio_load"))
        assertNull(store.get(day, "hr_zone_minutes"))
    }

    @Test
    fun `a year-old resting HR is not today's, and the 30-day horizon is inclusive`() {
        assertNull(cardio(day.minusDays(365)).second.get(day, "cardio_load"))
        assertEquals(52.0, cardio(day.minusDays(30)).first.measuredRhr(day))
        assertNull(cardio(day.minusDays(31)).first.measuredRhr(day))
    }

    @Test
    fun `a fresh measured resting HR produces a row stamped with it`() {
        val row = assertNotNull(cardio(day, wakingHr(60, 120.0) + steps(60, 100.0)).second.get(day, "cardio_load"))
        assertTrue(row.value > 0)
        assertEquals(52, row.flags.getValue("rhr").jsonPrimitive.content.toInt())
    }

    private fun load(samples: List<Sample>, workouts: List<Workout> = emptyList()): Triple<Double, Double, Double> {
        val row = assertNotNull(cardio(day, samples, workouts).second.get(day, "cardio_load"))
        fun f(k: String) = row.flags.getValue(k).jsonPrimitive.content.toDouble()
        return Triple(row.value, f("workout_load"), f("everyday_load"))
    }

    @Test
    fun `a raised heart rate sitting still is no load, though the zones still see it`() {
        val (_, store) = cardio(day)
        assertEquals(0.0, store.get(day, "cardio_load")!!.value)
        assertEquals(60, store.get(day, "cardio_load")!!.flags.getValue("hr_minutes").jsonPrimitive.content.toInt())
        assertEquals(60.0, store.get(day, "hr_zone_minutes")!!.value)
    }

    @Test
    fun `moving minutes count, and a brisk walk counts at a lower reserve`() {
        // 120 bpm is 0.49 of reserve (rhr 52, max 186); 95 bpm is 0.32, under 0.35 but over the walk's 0.20.
        val run = load(wakingHr(60, 120.0) + steps(60, 30.0))
        assertTrue(run.first > 0 && run.second == 0.0 && run.third == run.first)
        assertEquals(0.0, load(wakingHr(60, 95.0) + steps(60, 30.0)).first)
        assertTrue(load(wakingHr(60, 95.0) + steps(60, 100.0)).first > 0)
    }

    @Test
    fun `every workout minute counts, steps or not, and only once`() {
        val ride = Workout(at(day, 10), sport = 9, durationS = 30 * 60)
        val (total, workout, everyday) = load(wakingHr(60, 120.0), listOf(ride))
        assertEquals(total, workout)
        assertEquals(0.0, everyday)
        // Moving throughout: the workout's minutes aren't counted again as everyday load, and the first two
        // minutes after it don't yet have three of five behind them, so 28 everyday minutes at the same rate.
        val both = load(wakingHr(60, 120.0) + steps(60, 100.0), listOf(ride))
        assertEquals(workout, both.second)
        assertEquals(workout * 28 / 30, both.third, 0.11)
        assertEquals(both.second + both.third, both.first, 0.11)
    }

    @Test
    fun `a moving minute needs company, so single spikes add nothing`() {
        // Two hard moving minutes in a calm hour: under 3 of 5.
        val spikes = wakingHr(60, 60.0).map { if (it.ts == at(day, 10, 20) || it.ts == at(day, 10, 40)) Sample(it.ts, "hr", 150.0) else it }
        assertEquals(0.0, load(spikes + steps(60, 30.0)).first)
    }

    private fun night(rem: Int?, light: Int?, deep: Int?, wake: Int?, source: String = "strap_ble"): MemoryDerived {
        val end = at(day, 7)
        val session = SleepSession(end.minusSeconds(7 * 3600), end, remMin = rem, lightMin = light, deepMin = deep, wakeMin = wake, source = source)
        val store = MemoryDerived()
        Science(MemoryData(Zones(KOLKATA), sessions = listOf(session)), store).deriveNight(session)
        return store
    }

    @Test
    fun `a night missing any part of its stage breakdown scores nothing`() {
        for (minutes in listOf(listOf(90, 210, 60, null), listOf(null, 210, 60, 60), listOf(null, null, null, null))) {
            assertNull(night(minutes[0], minutes[1], minutes[2], minutes[3]).get(day, "sleep_health_score_4dim"), "$minutes")
        }
    }

    @Test
    fun `a complete breakdown scores, and a measured zero is a measurement`() {
        val complete = assertNotNull(night(90, 210, 60, 60).get(day, "sleep_health_score_4dim"))
        assertEquals("360", complete.flags.getValue("tst_min").jsonPrimitive.content)
        assertEquals(85.7, complete.flags.getValue("efficiency_pct").jsonPrimitive.content.toDouble(), 0.1)
        val zeroDeep = assertNotNull(night(90, 210, 0, 60).get(day, "sleep_health_score_4dim"))
        assertEquals("300", zeroDeep.flags.getValue("tst_min").jsonPrimitive.content)
    }

    @Test
    fun `each night serves its own source`() {
        assertEquals("strap_ble", night(90, 240, 120, 30).get(day, "sleep_health_score_4dim")!!.flags.getValue("session_source").jsonPrimitive.content)
        assertEquals("zepp_cloud", night(90, 240, 120, 30, "zepp_cloud").get(day, "sleep_health_score_4dim")!!.flags.getValue("session_source").jsonPrimitive.content)
    }
}
