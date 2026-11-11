package app.strap.ui.activity

import org.junit.Test
import kotlin.test.assertEquals

/** The session sheet's graph opens on the hours around the session (issue #4). */
class SessionViewTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private val day = 1_000L * h // any midnight
    private val next = day + 24 * h

    private fun view(start: Long, end: Long) = sessionView(day + start, day + end, day, next).let { (a, b) -> (a - day) to (b - day) }

    @Test
    fun `an hour's session shows 90 minutes either side, out to a 4-hour multiple`() {
        // 18:00–19:00 → 16:30–20:30 → 16:00–21:00 → 8 hours from 16:00.
        assertEquals(16 * h to 24 * h, view(18 * h, 19 * h))
        // 10:15–11:00 → 08:45–12:30 → 08:00–13:00 → 8 hours.
        assertEquals(8 * h to 16 * h, view(10 * h + 15 * m, 11 * h))
    }

    @Test
    fun `a short session gets 4 hours`() {
        // 12:00–12:30 → 10:30–14:00 → 10:00–14:00.
        assertEquals(10 * h to 14 * h, view(12 * h, 12 * h + 30 * m))
    }

    @Test
    fun `the view stays inside the day`() {
        assertEquals(0L to 4 * h, view(30 * m, h))
        // Past midnight: shifted back to end at the day's end.
        assertEquals(16 * h to 24 * h, view(22 * h, 25 * h))
    }

    @Test
    fun `a long session gets the whole day once the padding reaches it`() {
        // 06:00–20:00 → 04:30–21:30 → 04:00–22:00 → 20 hours, kept inside the day.
        assertEquals(4 * h to 24 * h, view(6 * h, 20 * h))
        assertEquals(0L to 24 * h, view(2 * h, 22 * h))
    }
}
