package strap.science

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from server/tests/test_zones.py (D31).
class ZonesTest {
    private val berlin = "Europe/Berlin"
    private val dubai = "Asia/Dubai"
    private val newYork = "America/New_York"
    private fun utc(s: String) = Instant.parse("${s}Z")
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test
    fun `no change is the plain single-zone day`() {
        val z = Zones(berlin)
        for (day in listOf(d(3, 29), d(10, 6), d(10, 25))) {
            assertEquals(Zones.wall(day, java.time.LocalTime.MIDNIGHT, berlin) to Zones.wall(day.plusDays(1), java.time.LocalTime.MIDNIGHT, berlin), z.bounds(day))
        }
        assertEquals(Duration.ofHours(23), Duration.between(z.bounds(d(3, 29)).first, z.bounds(d(3, 29)).second))
        assertEquals(Duration.ofHours(25), Duration.between(z.bounds(d(10, 25)).first, z.bounds(d(10, 25)).second))
        assertEquals(d(10, 6), z.dateOf(utc("2026-10-05T22:30:00")))
    }

    @Test
    fun `flying east shortens the day left, west lengthens it`() {
        val east = Zones(berlin, listOf(utc("2026-10-05T15:00:00") to dubai))
        assertEquals(utc("2026-10-04T22:00:00") to utc("2026-10-05T20:00:00"), east.bounds(d(10, 5)))
        assertEquals(utc("2026-10-05T20:00:00") to utc("2026-10-06T20:00:00"), east.bounds(d(10, 6)))
        assertEquals(berlin, east.zoneOf(d(10, 5)))
        assertEquals(dubai, east.zoneOf(d(10, 6)))
        val west = Zones(dubai, listOf(utc("2026-10-05T10:00:00") to berlin))
        assertEquals(utc("2026-10-04T20:00:00") to utc("2026-10-05T22:00:00"), west.bounds(d(10, 5)))
    }

    @Test
    fun `days tile with no gap or overlap`() {
        val z = Zones(berlin, listOf(utc("2026-10-05T15:00:00") to dubai, utc("2026-10-09T06:00:00") to newYork, utc("2026-10-12T23:00:00") to berlin))
        var day = d(10, 1)
        repeat(20) {
            val end = z.bounds(day).second
            assertEquals(z.bounds(day.plusDays(1)).first, end)
            assertTrue(end > z.start(day))
            day = day.plusDays(1)
        }
    }

    @Test
    fun `a skipped midnight starts the day at the change`() {
        val z = Zones(berlin, listOf(utc("2026-10-05T21:00:00") to dubai))
        assertEquals(utc("2026-10-05T21:00:00"), z.start(d(10, 6)))
        assertEquals(d(10, 5), z.dateOf(utc("2026-10-05T20:59:00")))
        assertEquals(d(10, 6), z.dateOf(utc("2026-10-05T21:00:00")))
    }

    @Test
    fun `dateOf agrees with bounds`() {
        val z = Zones(berlin, listOf(utc("2026-10-05T15:00:00") to dubai, utc("2026-10-09T06:00:00") to newYork))
        var t = utc("2026-10-03T00:00:00")
        while (t < utc("2026-10-12T00:00:00")) {
            val (start, end) = z.bounds(z.dateOf(t))
            assertTrue(start <= t && t < end, "$t")
            t = t.plusSeconds(37 * 60)
        }
    }

    @Test
    fun `repeats are not changes, and edges run through the last day`() {
        val z = Zones(berlin, listOf(utc("2026-10-01T00:00:00") to berlin, utc("2026-10-02T00:00:00") to dubai, utc("2026-10-03T00:00:00") to dubai))
        assertEquals(listOf(dubai), z.changes.map { it.second })
        assertEquals((5..7).map { Zones.wall(d(10, it), java.time.LocalTime.MIDNIGHT, dubai) }, Zones(dubai).starts(d(10, 5), d(10, 6)))
    }

    @Test
    fun `a wall time in a gap reads with the offset before it, like zoneinfo fold=0`() {
        // America/Santiago springs forward at 24:00 on 2026-09-05: local midnight does not exist.
        assertEquals(utc("2026-09-06T04:00:00"), Zones.wall(d(9, 6), java.time.LocalTime.MIDNIGHT, "America/Santiago"))
        // Berlin falls back on 2026-10-25: 02:30 happens twice; fold=0 is the first (summer) one.
        assertEquals(utc("2026-10-25T00:30:00"), Zones.wall(d(10, 25), java.time.LocalTime.of(2, 30), berlin))
    }
}
