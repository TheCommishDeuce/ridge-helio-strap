package strap.science

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CounterDaysTest {
    // A day in Tenerife (UTC+0), the strap still on Amsterdam time (UTC+1).
    private val start = Instant.parse("2026-11-25T00:00:00Z")
    private val end = Instant.parse("2026-11-26T00:00:00Z")
    private fun r(time: String, steps: Long) = CounterReading(Instant.parse("2026-11-25T${time}:00Z"), steps)

    @Test
    fun `no fall keeps the latest`() {
        assertEquals(r("21:00", 9000), CounterDays.pick(listOf(r("09:00", 1200), r("21:00", 9000), r("15:00", 6000)), start, end))
    }

    @Test
    fun `the strap ahead turns its day late in ours, so the reading before the fall holds it`() {
        val readings = listOf(r("08:00", 900), r("18:00", 11000), r("22:40", 12500), r("23:20", 44), r("23:50", 60))
        assertEquals(r("22:40", 12500), CounterDays.pick(readings, start, end))
    }

    @Test
    fun `the strap behind turns its day early in ours, so the latest holds it`() {
        // Strap on UTC-2: until 02:00 it still counts yesterday.
        val readings = listOf(r("00:30", 14000), r("02:30", 30), r("12:00", 5000), r("20:00", 8000))
        assertEquals(r("20:00", 8000), CounterDays.pick(readings, start, end))
    }

    @Test
    fun `no readings, no count`() {
        assertNull(CounterDays.pick(emptyList(), start, end))
    }
}
