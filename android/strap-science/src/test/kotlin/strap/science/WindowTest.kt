package strap.science

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone loads only [RAW_LOOKBACK_DAYS] of raw data before the days it works out or shows.
 * Over the demo person's six weeks (a trip, a DST change, feverish nights), a window must give
 * exactly what the whole history gives.
 */
class WindowTest {
    private val input = Golden.read("demo/input.json.gz").jsonObject
    private val now = Instant.ofEpochMilli(input.getValue("now").jsonPrimitive.long)
    private val data = Golden.data(input, now = { now })
    private val full = MemoryDerived().also { Science(data, it).rederive() }
    private val days = full.all().map { it.day }.distinct().sorted()

    private fun MemoryDerived.copy() = MemoryDerived().also { c -> all().forEach(c::put); allIllness().forEach(c::putIllness) }

    @Test
    fun `a re-derive from any day over its window equals the full re-derive`() {
        assertTrue(days.size > 40)
        for (since in days.filterIndexed { i, _ -> i % 3 == 0 } + days.last()) {
            val store = full.copy()
            // A stale row on every day from [since] on: the re-derive must replace each one.
            days.filter { it >= since }.forEach { store.put(Derived(it, "stale", 1.0)) }
            Science(data.window(data.zones.rawFrom(since)), store).rederive(since)
            assertEquals(full.all(), store.all(), "rows differ re-deriving from $since")
            assertEquals(full.allIllness().toList(), store.allIllness().toList(), "illness flags differ re-deriving from $since")
        }
    }

    @Test
    fun `every day's summary and charts read from its window equal the full history's`() {
        val whole = Science(data, full)
        for (day in days) {
            val window = Science(data.window(data.zones.rawFrom(day), data.zones.start(day.plusDays(2))), full)
            assertEquals(whole.daySummary(day, now), window.daySummary(day, now), "summary of $day")
            val names = SERIES.keys.toList()
            assertEquals(whole.daySeries(day, names), window.daySeries(day, names), "series of $day")
            for (name in names) {
                assertEquals(whole.bucketSeries(name, day.minusDays(29), day, Bucket.DAY), window.bucketSeries(name, day.minusDays(29), day, Bucket.DAY), "$name month to $day")
                assertEquals(whole.bucketSeries(name, day.minusDays(6), day, Bucket.HOUR), window.bucketSeries(name, day.minusDays(6), day, Bucket.HOUR), "$name week to $day")
            }
        }
    }

    @Test
    fun `a window keeps the night that began the evening before it`() {
        val night = data.mainSessions().first { data.zones.dateOf(it.start) != data.zones.dateOf(it.end) }
        val from = data.zones.start(data.zones.dateOf(night.end))
        assertEquals(listOf(night), data.window(from).mainSessions().filter { it.start == night.start })
        // Its samples before the cut are gone; the night itself stays.
        assertTrue(data.window(from).samples("hr", night.start, from).isEmpty())
    }
}
