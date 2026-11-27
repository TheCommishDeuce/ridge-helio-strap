package strap.science

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PossibleWorkoutsTest {
    private val t0 = Instant.parse("2026-11-25T08:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private val hrMax = 189.0 // zone 2 from 113, walking effort from 104

    /** [base] all day, [high] from minute [from] until [until]. */
    private fun series(base: Double, high: Double, from: Long, until: Long, minutes: Long = 600) =
        (0 until minutes).associate { at(it) to if (it in from until until) high else base }

    private fun find(hr: Map<Instant, Double>, steps: Map<Instant, Double> = emptyMap(), busy: List<Pair<Instant, Instant>> = emptyList(),
        workouts: List<Pair<Instant, Instant>> = emptyList()) = PossibleWorkouts.find(hr, steps, busy, workouts, hrMax)

    @Test
    fun `a long stretch at zone 2 is offered with its window and heart rate`() {
        val p = find(series(80.0, 120.0, 60, 220)).single()
        assertTrue(p.start in at(55)..at(62) && p.end in at(218)..at(225), "$p")
        assertTrue(p.avgHr in 118..120, "$p")
    }

    @Test
    fun `a hike is steady walking at a raised heart rate, a stroll is not`() {
        val walking = series(0.0, 85.0, 60, 180)
        assertEquals(1, find(series(80.0, 108.0, 60, 180), walking).size)
        assertTrue(find(series(80.0, 108.0, 60, 180)).isEmpty(), "the same heart rate without steps")
        assertTrue(find(series(80.0, 95.0, 60, 180), walking).isEmpty(), "walking at an easy heart rate")
    }

    @Test
    fun `a lunch stop does not cut a hike, single spikes do not make one`() {
        val hr = series(80.0, 120.0, 60, 240).toMutableMap()
        (130L until 155L).forEach { hr[at(it)] = 85.0 } // 25 minutes sitting down
        hr[at(400)] = 160.0
        assertEquals(1, find(hr).size)
    }

    @Test
    fun `too short, or under the floor, is nothing`() {
        assertTrue(find(series(80.0, 120.0, 60, 95)).isEmpty())
        assertTrue(find(series(80.0, 105.0, 60, 300)).isEmpty())
    }

    @Test
    fun `a stretch beside a workout is that workout cut short, not another`() {
        val workout = listOf(at(150) to at(220))
        assertTrue(find(series(80.0, 120.0, 60, 220), busy = workout, workouts = workout).isEmpty())
    }

    @Test
    fun `sleep is not judged`() {
        assertTrue(find(series(80.0, 120.0, 60, 220), busy = listOf(at(0) to at(600))).isEmpty())
    }
}
