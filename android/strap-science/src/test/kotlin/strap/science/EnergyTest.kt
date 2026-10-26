package strap.science

import strap.science.Energy.Minute
import strap.science.Energy.Workout
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Charge cases ported from server/tests/derive/test_charge.py, revised for D35, and the D35 rules.
class EnergyTest {
    private fun run(minutes: List<Minute>, threshold: Double = Energy.DEFAULT_THRESHOLD) = Energy.simulate(minutes, 55.0, 185.0, "male", threshold)
    private fun rep(m: Minute, n: Int) = List(n) { m }
    private val awake = Minute(hr = 60.0, stress = 40.0, steps = 20.0)

    @Test
    fun `a quiet awake minute drains a share of the value and a gap still open holds`() {
        val r = run(rep(Minute(hr = 60.0, stress = 40.0), 10) + rep(Minute(), 15))
        var expected = Energy.START_VALUE
        repeat(10) { expected -= Energy.REST_DRAIN * expected }
        assertEquals(expected, r.values[9]!!, 1e-9)
        assertEquals(r.values[9], r.values.last())
        assertEquals("rest", r.states.first())
        assertEquals("no_data", r.states.last())
        assertEquals(Energy.START_VALUE - expected, r.drained.take(10).sumOf { it.getValue("living") }, 1e-9)
    }

    @Test
    fun `sleep refills a share of the room left, so it slows as it fills`() {
        val r = run(rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 450))
        val firstGain = r.values[0]!! - Energy.START_VALUE
        assertEquals(0.006 * 1.0 * 1.2 * (100 - Energy.START_VALUE), firstGain, 1e-9)
        assertTrue(r.values.last()!! > 95 && r.values.last()!! - r.values[r.values.size - 2]!! < firstGain / 10)
        assertTrue(r.slept.all { it })
    }

    @Test
    fun `a racing heart in sleep and light stages recharge less`() {
        val calm = run(rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 60)).values.last()!!
        val racing = run(rep(Minute(hr = 70.0, stage = 5, nightRhr = 55.0), 60)).values.last()!!
        val light = run(rep(Minute(hr = 55.0, stage = 4, nightRhr = 55.0), 60)).values.last()!!
        assertTrue(calm > light && light > Energy.START_VALUE && calm > racing && racing > Energy.START_VALUE)
    }

    @Test
    fun `exercise drains by its TRIMP and its after-burn is recovery, not stress`() {
        val r = run(rep(Minute(hr = 150.0, stress = 90.0, steps = 150.0), 30) + rep(Minute(hr = 88.0, stress = 90.0), 10))
        assertEquals(setOf("exercise"), r.states.take(30).toSet())
        assertEquals(setOf("exercise_recovery"), r.states.drop(30).toSet())
        assertTrue(r.drained.sumOf { it["activity"] ?: 0.0 } > 30 * Energy.LIVING)
        assertEquals(0.0, r.drained.sumOf { it["stress"] ?: 0.0 })
    }

    @Test
    fun `a raised heart rate sitting still is not effort, unless it lasts ten minutes`() {
        // 100 bpm is 35 % of reserve here, over the exercise line.
        val short = run(rep(Minute(hr = 100.0, stress = 40.0), Energy.SUSTAINED_MIN - 1))
        assertEquals(setOf("rest"), short.states.toSet())
        assertEquals(0.0, short.drained.sumOf { it["activity"] ?: 0.0 })
        val ride = run(rep(Minute(hr = 100.0, stress = 40.0), Energy.SUSTAINED_MIN))
        assertEquals(setOf("exercise"), ride.states.toSet())
        // Sitting at a raised heart rate can't relax either, however low the stress.
        assertEquals(listOf("rest"), run(listOf(Minute(hr = 90.0, stress = 10.0))).states)
    }

    @Test
    fun `stress drains only after three minutes over the threshold`() {
        val r = run(rep(Minute(hr = 65.0, stress = 80.0), 5), threshold = 60.0)
        assertEquals(listOf("rest", "rest", "stress", "stress", "stress"), r.states)
        assertEquals(0.007 * 20, r.drained[2].getValue("stress"), 1e-9)
    }

    @Test
    fun `steps count as activity and a calm still minute recharges without draining`() {
        val r = run(listOf(Minute(hr = 62.0, stress = 30.0, steps = 90.0), Minute(hr = 58.0, stress = 10.0)))
        assertEquals(listOf("activity", "relax"), r.states)
        assertTrue(r.recharged[1] > 0 && r.drained[1].isEmpty())
    }

    @Test
    fun `a calm day holds rather than climbs`() {
        val r = run(rep(Minute(hr = 58.0, stress = 10.0), 8 * 60))
        assertTrue(r.values.last()!! > Energy.START_VALUE && r.values.last()!! < Energy.START_VALUE + 5)
    }

    @Test
    fun `stress in a workout and the twenty minutes after is not stress`() {
        val during = run(rep(Minute(hr = 70.0, stress = 90.0, workout = Workout.ACTIVE), 5), threshold = 60.0)
        assertEquals(setOf("activity"), during.states.toSet())
        val after = run(rep(Minute(hr = 58.0, stress = 90.0, workout = Workout.AFTER), 5), threshold = 60.0)
        assertEquals(setOf("rest"), after.states.toSet())
        assertEquals(0.0, during.drained.sumOf { it["stress"] ?: 0.0 } + after.drained.sumOf { it["stress"] ?: 0.0 })
        // The run starts over after the window.
        val then = run(rep(Minute(hr = 65.0, stress = 90.0, workout = Workout.AFTER), 5) + rep(Minute(hr = 65.0, stress = 90.0), 3), threshold = 60.0)
        assertEquals(listOf("rest", "rest", "stress"), then.states.takeLast(3))
    }

    @Test
    fun `two hours off the wrist drift down and a five-minute dropout holds`() {
        val r = run(rep(awake, 10) + rep(Minute(), 120) + rep(awake, 5))
        assertEquals(setOf("not_worn"), r.states.subList(10, 130).toSet())
        // Just under 50 after the awake minutes, so the lower drift throughout.
        val before = r.values[9]!!
        assertTrue(before < 50)
        assertEquals(before - 120 * Energy.NOT_WORN_DRIFT_LOW, r.values[129]!!, 1e-6)
        assertEquals(120 * Energy.NOT_WORN_DRIFT_LOW, r.drained.subList(10, 130).sumOf { it.getValue("living") }, 1e-6)
        val dropout = run(rep(awake, 10) + rep(Minute(), 5) + rep(awake, 5))
        assertEquals(setOf("no_data"), dropout.states.subList(10, 15).toSet())
        assertEquals(dropout.values[9], dropout.values[14])
    }

    @Test
    fun `a long day off is a gap until the next sleep, which starts from 50`() {
        val r = run(rep(awake, 10) + rep(Minute(), 400) + rep(awake, 30) + rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 1))
        assertTrue(r.values[10 + Energy.NOT_WORN_LIMIT_MIN - 1] != null)
        assertNull(r.values[10 + Energy.NOT_WORN_LIMIT_MIN])
        assertTrue(r.values.subList(410, 440).all { it == null })
        assertEquals(Energy.RESTART_BEFORE_SLEEP + 0.006 * 1.2 * (100 - Energy.RESTART_BEFORE_SLEEP), r.values.last()!!, 1e-9)
    }

    @Test
    fun `a night off is a gap and the morning restarts from the usual wake`() {
        val sleep = Minute(hr = 55.0, stage = 5, nightRhr = 55.0)
        // Three nights: the first wake carries the starting guess and is not kept; the next two are.
        val night = rep(sleep, 120) + listOf(Minute(hr = 60.0, stress = 40.0, wake = true)) + rep(awake, 59)
        // Nights at 0, 180 and 360 (wakes at 120, 300, 480); the strap off from 540, over the usual sleep, until 900.
        val r = run(night + night + night + rep(Minute(usualSleep = true), 300) + rep(Minute(), 60) + listOf(Minute(hr = 60.0, stress = 40.0)))
        assertTrue((540 until 900).all { r.values[it] == null })
        val back = r.values.lastIndex
        assertEquals(900, back)
        assertTrue(r.estimated[back])
        val restart = Robust.median(listOf(r.values[299]!!, r.values[479]!!))
        assertEquals(restart - Energy.REST_DRAIN * restart, r.values[back]!!, 1e-9)
    }

    @Test
    fun `a night off restarts from 80 without wake values`() {
        val r = run(rep(Minute(hr = 55.0, stage = 5, nightRhr = 55.0), 60) + rep(Minute(usualSleep = true), 120) + listOf(Minute(hr = 60.0, stress = 40.0)))
        assertEquals(Energy.DEFAULT_WAKE * (1 - Energy.REST_DRAIN), r.values.last()!!, 1e-9)
        assertTrue(r.estimated.last())
    }

    @Test
    fun `before any sleep a night off waits for the first one`() {
        val r = run(rep(Minute(usualSleep = true), 120) + rep(awake, 5))
        assertTrue(r.values.takeLast(5).all { it == null })
    }

    @Test
    fun `a high illness flag halves recharge and drains half as much again`() {
        val sleep = Minute(hr = 55.0, stage = 5, nightRhr = 55.0)
        val gain = run(listOf(sleep)).values[0]!! - Energy.START_VALUE
        val flagged = run(listOf(Minute(hr = 55.0, stage = 5, nightRhr = 55.0, alert = true))).values[0]!! - Energy.START_VALUE
        assertEquals(gain * Energy.ALERT_RECHARGE, flagged, 1e-12)
        val rest = Energy.START_VALUE - run(listOf(Minute(hr = 60.0, stress = 40.0))).values[0]!!
        val restFlagged = Energy.START_VALUE - run(listOf(Minute(hr = 60.0, stress = 40.0, alert = true))).values[0]!!
        assertEquals(rest * Energy.ALERT_DRAIN, restFlagged, 1e-12)
    }

    @Test
    fun `energy stays inside 0 to 100`() {
        assertEquals(0.0, run(rep(Minute(hr = 180.0, stress = 99.0, steps = 150.0), 600)).values.minOf { it!! })
        assertTrue(run(rep(Minute(hr = 50.0, stage = 5, nightRhr = 55.0, hrvFactor = 1.1), 3000)).values.maxOf { it!! } <= 100.0)
    }

    @Test
    fun `the HRV factor is one on a usual night and bounded`() {
        assertEquals(1.0, Energy.hrvFactor(50.0))
        assertEquals(1.0, Energy.hrvFactor(null))
        assertEquals(0.75, Energy.hrvFactor(0.0))
        assertEquals(1.1, Energy.hrvFactor(100.0))
        assertEquals(0.6, Energy.hrvFactor(-100.0))
    }

    @Test
    fun `the stress threshold is personal only with enough calm readings`() {
        val days = (1..3).associate { LocalDate.of(2026, 10, it) to List(25) { 30.0 } }
        assertEquals(50.0 to true, Energy.personalThreshold(days))
        assertEquals(Energy.DEFAULT_THRESHOLD to false, Energy.personalThreshold(mapOf(LocalDate.of(2026, 10, 1) to List(100) { 30.0 })))
        assertEquals(70.0 to true, Energy.personalThreshold(days.mapValues { List(30) { 65.0 } }))
    }
}
