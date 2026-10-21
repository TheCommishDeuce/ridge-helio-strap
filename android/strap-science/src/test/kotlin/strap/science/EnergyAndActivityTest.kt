package strap.science

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_energy_dst.py, test_mvpa_met_equivalent.py and
// test_unworn_day.py.
class EnergyAndActivityTest {
    private val ny = "America/New_York"
    private val ist = "Asia/Kolkata"
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun minutes(zone: String, day: LocalDate) = Zones(zone).bounds(day).let { (s, e) -> dayMinutes(s, e) }

    @Test
    fun `a day's minutes follow the real local day`() {
        for ((zone, day, expected) in listOf(
            Triple(ny, d(2026, 3, 8), 1380), Triple(ny, d(2026, 11, 1), 1500), Triple(ny, d(2026, 6, 15), 1440), Triple(ist, d(2026, 3, 8), 1440),
            Triple("UTC", d(2026, 3, 8), 1440), Triple("America/Santiago", d(2026, 4, 4), 1500), Triple("Asia/Beirut", d(2026, 10, 24), 1500),
            Triple("America/Nuuk", d(2026, 3, 28), 1380), Triple("America/Santiago", d(2026, 9, 6), 1380),
        )) assertEquals(expected, minutes(zone, day), "$zone $day")
    }

    @Test
    fun `spring forward at midnight starts the day at its first real instant`() {
        val start = Zones("America/Santiago").start(d(2026, 9, 6)).atZone(ZoneId.of("America/Santiago"))
        assertEquals(1 to d(2026, 9, 6), start.hour to start.toLocalDate())
    }

    @Test
    fun `consecutive days tile the timeline in every zone`() {
        for (zone in ZoneId.getAvailableZoneIds().sorted()) {
            val z = Zones(zone)
            var prevEnd: java.time.Instant? = null
            for (k in 0L until 730) {
                val (start, end) = z.bounds(d(2026, 1, 1).plusDays(k))
                if (prevEnd != null) assertEquals(prevEnd, start, "$zone +$k")
                assertTrue(dayMinutes(start, end) in 1320..1560, "$zone +$k")
                prevEnd = end
            }
        }
    }

    @Test
    fun `the walk covers midnight to 23·59 and stops`() {
        for ((zone, day) in listOf(ny to d(2026, 3, 8), ny to d(2026, 11, 1), ist to d(2026, 6, 1), "America/Santiago" to d(2026, 4, 4), "Asia/Beirut" to d(2026, 10, 24))) {
            val (start, end) = Zones(zone).bounds(day)
            val n = dayMinutes(start, end).toLong()
            val id = ZoneId.of(zone)
            val first = start.atZone(id)
            val last = start.plus(Duration.ofMinutes(n - 1)).atZone(id)
            assertEquals(0 to 0, first.hour to first.minute)
            assertEquals(Triple(23, 59, day), Triple(last.hour, last.minute, last.toLocalDate()))
            assertEquals(day.plusDays(1), start.plus(Duration.ofMinutes(n)).atZone(id).toLocalDate())
        }
    }

    // The energy walk: BMR 1700, stride 0.7 m, 100 steps a minute = 3 METs.
    private val bmr = 1700.0
    private fun kcal(metMinutes: Double) = metMinutes * bmr / 1440.0
    private fun tee(zone: String, day: LocalDate, steps: List<java.time.Instant>): Double {
        val (s, e) = Zones(zone).bounds(day)
        return Science(MemoryData(Zones(zone), steps.map { Sample(it, "steps_per_minute", 100.0) }), MemoryDerived()).teeMet(s, e, bmr, 0.7)
    }

    @Test
    fun `a fall-back day counts its last local hour, a spring-forward day stops at its midnight`() {
        val fallBack = d(2026, 11, 1)
        val gain = 3.0 - 1.3 + 14 * (1.55 - 1.3)
        assertEquals(kcal(gain), tee(ny, fallBack, listOf(at(fallBack, 23, 30, ny))) - tee(ny, fallBack, emptyList()), 1e-9)
        assertEquals(kcal(1500 * 1.3), tee(ny, fallBack, emptyList()), 1e-9)
        val spring = d(2026, 3, 8)
        assertEquals(kcal(1380 * 1.3), tee(ny, spring, listOf(at(spring.plusDays(1), 0, 30, ny))), 1e-9)
    }

    @Test
    fun `an ordinary day's calories are the hand-derived 1440-minute total`() {
        val day = d(2026, 6, 15)
        val total = 10 * 3.0 + 90 * 1.55 + (1440 - 10 - 90) * 1.3
        for (zone in listOf(ist, ny)) {
            val steps = listOf(7, 8, 12, 19, 23).flatMap { h -> listOf(0, 5).map { m -> at(day, h, m, zone) } }
            assertEquals(kcal(total), tee(zone, day, steps), 1e-9)
        }
    }

    // MVPA: a vigorous minute counts as two moderate ones.
    private val today = d(2026, 6, 16)
    private fun mvpa(vararg runs: Pair<Int, List<Double>>): Derived? {
        val samples = runs.flatMap { (startMinute, spm) -> spm.mapIndexed { i, v -> Sample(at(today, 9).plusSeconds(60L * (startMinute + i)), "steps_per_minute", v) } }
        val store = MemoryDerived()
        Science(MemoryData(Zones(ist), samples), store).mvpa(today)
        return store.get(today, "mvpa_min")
    }

    @Test
    fun `MVPA sums to its MET-equivalent total`() {
        val row = assertNotNull(mvpa(0 to listOf(85.0) + List(9) { 105.0 }, 30 to listOf(115.0) + List(4) { 140.0 }))
        assertEquals(17.0, row.value)
        assertEquals(json("moderate" to 9, "vigorous" to 4).toString(), row.flags.toString())
        val vigorous = assertNotNull(mvpa(0 to listOf(115.0) + List(3) { 140.0 }))
        assertEquals(6.0, vigorous.value)
        assertEquals(12.0, assertNotNull(mvpa(0 to listOf(85.0) + List(12) { 105.0 })).value)
    }

    // An unworn day: nothing counted is no row, never a zero.
    private val day = d(2026, 6, 14)
    private fun activity(samples: List<Sample> = emptyList(), totals: List<DeviceDailyTotal> = emptyList(), profile: Profile? = null): MemoryDerived {
        val store = MemoryDerived()
        Science(MemoryData(Zones(ist), samples, totals = totals, profile = profile), store).dailyActivity(day)
        return store
    }

    @Test
    fun `a day no instrument counted gets no step row and no stride distance`() {
        assertNull(activity().get(day, "steps_total"))
        assertNull(activity(profile = Profile(178f, "male", d(1990, 1, 1))).get(day, "distance_m_daily"))
    }

    @Test
    fun `a day the stream spoke for is written with its count`() {
        val row = assertNotNull(activity((0 until 3).map { Sample(at(day, 9).plusSeconds(60L * it), "steps_per_minute", 40.0) }).get(day, "steps_total"))
        assertEquals(120.0, row.value)
        assertEquals("steps_per_minute", row.flags.getValue("source").jsonPrimitive.content)
        assertEquals("3", row.flags.getValue("sample_minutes").jsonPrimitive.content)
    }

    @Test
    fun `a strap that reports zero steps has measured zero steps`() {
        val readAt = at(day.plusDays(1), 3)
        val row = assertNotNull(activity(totals = listOf(DeviceDailyTotal(day, 0, null, null, "strap_0x16", readAt, readAt))).get(day, "steps_total"))
        assertEquals(0.0, row.value)
        assertEquals("strap_0x16", row.flags.getValue("source").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), row.flags.getValue("caveats"))
    }
}
