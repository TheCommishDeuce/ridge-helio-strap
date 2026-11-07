package app.strap.integrations

import org.junit.Test
import strap.science.Workout
import java.time.Instant
import kotlin.test.assertEquals

/** 17 Oct 2026: one bike ride in Dawarich, two strap workouts in and around it (D44). */
class CoveringTest {
    private fun at(t: String) = Instant.parse("2026-10-17T${t}:00Z")
    private val first = Workout(at("09:01"), 223, 33 * 60)
    private val second = Workout(at("09:47"), 0, 31 * 60) // runs 18 minutes past the ride
    private val evening = Workout(at("20:17"), 0, 6 * 60)
    private val ride = Track(1, at("09:01"), at("10:00"), "cycling", 5700, emptyList())
    private val walk = Track(2, at("06:52"), at("07:04"), "walking", 428, emptyList())

    @Test
    fun `strap workouts overlapping a ride are the ride, the rest stay their own`() {
        val all = listOf(first, second, evening)
        assertEquals(listOf(evening), Covering.uncovered(all, Covering.trackWindows(listOf(ride, walk))))
        assertEquals(listOf(first, second), Covering.inside(all, ride.start, ride.end))
        assertEquals(listOf(ride), Covering.trained(listOf(ride, walk), all))
    }

    @Test
    fun `touching end to start is not overlapping`() {
        val after = Workout(at("10:00"), 0, 600)
        assertEquals(listOf(after), Covering.uncovered(listOf(after), Covering.trackWindows(listOf(ride))))
    }
}
