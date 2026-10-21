package strap.science

import strap.science.Charge.Minute
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_charge.py.
class ChargeTest {
    private fun run(minutes: List<Minute>, threshold: Double = Charge.DEFAULT_THRESHOLD) = Charge.simulate(minutes, 55.0, 185.0, "male", threshold)
    private fun rep(m: Minute, n: Int) = List(n) { m }

    @Test
    fun `a quiet awake minute drains the living rate and no data holds`() {
        val r = run(rep(Minute(hr = 60.0, stress = 40.0, steps = 20.0), 10) + rep(Minute(), 5))
        assertEquals(Charge.START_VALUE - 10 * Charge.LIVING, r.values[9], 1e-9)
        assertEquals(r.values[9], r.values[14])
        assertEquals("rest", r.states.first())
        assertEquals("no_data", r.states.last())
        assertEquals(10 * Charge.LIVING, r.drained.take(10).sumOf { it.getValue("living") }, 1e-9)
    }

    @Test
    fun `sleep refills a share of the room left, so it slows as it fills`() {
        val r = run(rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 450))
        val firstGain = r.values[0] - Charge.START_VALUE
        assertEquals(0.006 * 1.0 * 1.2 * (100 - Charge.START_VALUE), firstGain, 1e-9)
        assertTrue(r.values.last() > 95 && r.values.last() - r.values[r.values.size - 2] < firstGain / 10)
        assertTrue(r.slept.all { it })
    }

    @Test
    fun `a racing heart in sleep and light stages recharge less`() {
        val calm = run(rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 60)).values.last()
        val racing = run(rep(Minute(hr = 70.0, stage = 5, nightRhr = 55.0), 60)).values.last()
        val light = run(rep(Minute(hr = 55.0, stage = 4, nightRhr = 55.0), 60)).values.last()
        assertTrue(calm > light && light > Charge.START_VALUE && calm > racing && racing > Charge.START_VALUE)
    }

    @Test
    fun `exercise drains by its TRIMP and its after-burn is recovery, not stress`() {
        val r = run(rep(Minute(hr = 150.0, stress = 90.0), 30) + rep(Minute(hr = 88.0, stress = 90.0), 10))
        assertEquals(setOf("exercise"), r.states.take(30).toSet())
        assertEquals(setOf("exercise_recovery"), r.states.drop(30).toSet())
        assertTrue(r.drained.sumOf { it["activity"] ?: 0.0 } > 30 * Charge.LIVING)
        assertEquals(0.0, r.drained.sumOf { it["stress"] ?: 0.0 })
    }

    @Test
    fun `stress drains only after three minutes over the threshold`() {
        val r = run(rep(Minute(hr = 65.0, stress = 80.0), 5), threshold = 60.0)
        assertEquals(listOf("rest", "rest", "stress", "stress", "stress"), r.states)
        assertEquals(0.007 * 20, r.drained[2].getValue("stress"), 1e-9)
    }

    @Test
    fun `steps count as activity and a calm still minute relaxes`() {
        val r = run(listOf(Minute(hr = 62.0, stress = 30.0, steps = 90.0), Minute(hr = 58.0, stress = 10.0)))
        assertEquals(listOf("activity", "relax"), r.states)
        assertTrue(r.recharged[1] > 0)
    }

    @Test
    fun `charge stays inside 0 to 100`() {
        assertEquals(0.0, run(rep(Minute(hr = 180.0, stress = 99.0), 600)).values.min())
        assertTrue(run(rep(Minute(hr = 50.0, stage = 5, nightRhr = 55.0, hrvFactor = 1.1), 3000)).values.max() <= 100.0)
    }

    @Test
    fun `the HRV factor is one on a usual night and bounded`() {
        assertEquals(1.0, Charge.hrvFactor(50.0))
        assertEquals(1.0, Charge.hrvFactor(null))
        assertEquals(0.75, Charge.hrvFactor(0.0))
        assertEquals(1.1, Charge.hrvFactor(100.0))
        assertEquals(0.6, Charge.hrvFactor(-100.0))
    }

    @Test
    fun `the stress threshold is personal only with enough calm readings`() {
        val days = (1..3).associate { LocalDate.of(2026, 10, it) to List(25) { 30.0 } }
        assertEquals(50.0 to true, Charge.personalThreshold(days))
        assertEquals(Charge.DEFAULT_THRESHOLD to false, Charge.personalThreshold(mapOf(LocalDate.of(2026, 10, 1) to List(100) { 30.0 })))
        assertEquals(70.0 to true, Charge.personalThreshold(days.mapValues { List(30) { 65.0 } }))
    }
}
