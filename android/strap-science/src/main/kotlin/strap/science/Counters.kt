package strap.science

import java.time.Instant

/** One reading of the strap's since-midnight counters (spec/01 §5): when it was read, and the steps. */
public data class CounterReading(val readAt: Instant, val steps: Long)

/**
 * Which counter reading holds the owner's day (D50). The strap's counter starts again at the
 * strap's own midnight, which is the owner's only while the strap's clock is on the owner's
 * zone. Abroad, with the strap still on home time, it started again at 23:00 and the reading
 * taken after that (a few dozen steps) became the day's count.
 */
public object CounterDays {
    /**
     * The reading that holds the day [start]..[end], from [readings] all read inside it. Between
     * two of the strap's midnights the counter only rises, so a fall means the strap's day turned
     * inside the owner's. A fall in the second half of the day (the strap ahead: the owner went
     * west) leaves the reading before it as the day's; one in the first half (the strap behind:
     * the owner went east) leaves the latest. With no fall, the latest. Null for no readings.
     */
    public fun pick(readings: List<CounterReading>, start: Instant, end: Instant): CounterReading? {
        val sorted = readings.sortedBy { it.readAt }
        val fall = (1 until sorted.size).firstOrNull { sorted[it].steps < sorted[it - 1].steps }
            ?: return sorted.lastOrNull()
        val middle = start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2)
        return if (sorted[fall].readAt >= middle) sorted[fall - 1] else sorted.last()
    }
}
