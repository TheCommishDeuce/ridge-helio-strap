package app.strap.sync

import org.junit.Test
import strap.protocol.model.Workout
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DetectedWorkoutsTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")
    private fun w(start: String, minutes: Int, subtype: Int?, sport: Int = 10) =
        Workout(Instant.parse(start), sport, minutes * 60, 300, 120, 150, 80, subtype)

    @Test
    fun `only a new workout the strap recognised by itself, ended within a day`() {
        val detected = w("2026-11-07T13:00:00Z", 75, 1)
        val started = w("2026-11-07T10:00:00Z", 30, 0)
        val unknownKind = w("2026-11-07T09:00:00Z", 30, null)
        val old = w("2026-11-06T12:00:00Z", 60, 1) // ended 26 h ago
        val known = w("2026-11-07T08:00:00Z", 40, 1)
        val notices = DetectedWorkouts.toNotify(listOf(detected, started, unknownKind, old, known, detected), setOf(known.start), now)
        assertEquals(listOf(detected), notices)
    }

    @Test
    fun `none for a workout inside a session already logged in openGym`() {
        val inGym = w("2026-11-07T13:10:00Z", 20, 1)
        val after = w("2026-11-07T14:30:00Z", 15, 1)
        val gym = Instant.parse("2026-11-07T13:00:00Z") to Instant.parse("2026-11-07T13:41:00Z")
        assertEquals(listOf(after), DetectedWorkouts.toNotify(listOf(inGym, after), emptySet(), now, listOf(gym)))
    }

    @Test
    fun `the notice names the sport, length and start`() {
        assertEquals("Workout · 1 h 15 min from 14:00. Tap to check it, edit it or hide it.",
            DetectedWorkouts.text(w("2026-11-07T13:00:00Z", 75, 1, sport = 0), ZoneOffset.ofHours(1)))
        assertEquals("Workout (code 9999) · 12 min from 14:00. Tap to check it, edit it or hide it.",
            DetectedWorkouts.text(w("2026-11-07T13:00:00Z", 12, 1, sport = 9999), ZoneOffset.ofHours(1)))
    }
}
