package strap.science

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The calendar's rings, the illness check and a workout's own load, over the demo golden person. */
class RingsTest {
    private val input = Golden.read("demo/input.json.gz").jsonObject
    private val now = Instant.ofEpochMilli(input.getValue("now").jsonPrimitive.long)
    private val data = Golden.data(input, now = { now })
    private val store = MemoryDerived().also { Science(data, it).rederive() }
    private val science = Science(data, store)
    private val today = data.zones.dateOf(now)

    @Test
    fun `each day's rings equal that day's own summary`() {
        val from = today.minusDays(40)
        val rings = science.dayRings(from, today)
        assertEquals(41, rings.size)
        for (r in rings) {
            val s = science.daySummary(r.date, now)
            assertEquals(s.recovery.shown?.value, r.recovery, "recovery ${r.date}")
            assertEquals(s.strain.shown?.value, r.strain, "strain ${r.date}")
            assertEquals(s.sleep.main?.deviceScore, r.sleepScore, "sleep score ${r.date}")
            s.stress.shown?.let { assertEquals(it.mean, r.stress!!, 0.06, "stress ${r.date}") }
        }
        assertTrue(rings.count { it.recovery != null } > 20)
    }

    @Test
    fun `the illness check gives the flag's own deltas on a flagged night`() {
        val flagged = (0..60L).map { today.minusDays(it) }.mapNotNull { d -> store.illness(d) }
        assertTrue(flagged.isNotEmpty(), "the demo has feverish nights")
        for (f in flagged) {
            val check = science.illnessCheck(f.date)
            f.rrDeltaBpm?.let { assertEquals(it.toDouble(), check.rrDelta!!, 0.01) }
            f.tempDeltaC?.let { assertEquals(it.toDouble(), check.tempDelta!!, 0.01) }
            assertTrue(check.nights >= check.needed)
        }
        val now = science.illnessCheck(today)
        assertEquals(10, now.needed)
        // The night's skin temperature less its usual is the limb's delta whenever both exist.
        if (now.tempNight != null && now.tempUsual != null && now.tempDelta != null) assertEquals(now.tempDelta!!, now.tempNight!! - now.tempUsual!!, 0.02)
    }

    @Test
    fun `a workout's load is part of its day's and its minutes all land in a zone`() {
        val workouts = data.workouts(science.zones.bounds(today.minusDays(40)).first, now)
        assertTrue(workouts.isNotEmpty())
        for (w in workouts) {
            val d = assertNotNull(science.workoutDetail(w.start, w.durationS), "detail for ${w.start}")
            val day: LocalDate = science.zones.dateOf(w.start)
            assertTrue(d.trimp <= store.get(day, "cardio_load")!!.value + 0.1, "trimp within the day's load on $day")
            assertEquals(d.hr.size, d.zoneMinutes.sum())
            assertTrue(d.hr.size <= Duration.ofSeconds(w.durationS.toLong()).toMinutes() + 1)
        }
    }
}
