package strap.science

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The owner's timezone over time, and what a "day" is under it (D31).
 *
 * **A day starts at local midnight wherever the owner is at that moment.** Its end is the next
 * day's start, so days tile the timeline with no gap and no overlap whatever the travel: flying
 * east shortens the day being left, flying west lengthens it. When a change jumps past a
 * midnight, the day starts at the change itself. With no change recorded this is the plain
 * single-zone day.
 *
 * Local wall-clock times resolve as Python's `zoneinfo` does with `fold=0`: a time in a gap is
 * read with the offset before it, an ambiguous one takes the earlier offset.
 */
public class Zones(
    public val base: String,
    changes: List<Pair<Instant, String>> = emptyList(),
    private val now: () -> Instant = Instant::now,
) {
    /** (instant, zone) sorted; consecutive repeats of one zone are not changes. */
    public val changes: List<Pair<Instant, String>>
    private val at: List<Instant>
    private val starts = HashMap<LocalDate, Instant>()

    init {
        val merged = ArrayList<Pair<Instant, String>>()
        for ((instant, name) in changes.sortedBy { it.first }) {
            if (name != (merged.lastOrNull()?.second ?: base)) merged += instant to name
        }
        this.changes = merged
        at = merged.map { it.first }
    }

    /** The zone in force at [instant]. */
    public fun zoneAt(instant: Instant): String {
        val i = bisectRight(at, instant)
        return if (i == 0) base else changes[i - 1].second
    }

    /** The zone the day began in: the one its times are told in. */
    public fun zoneOf(day: LocalDate): String = zoneAt(start(day))

    /** When [day] begins. */
    public fun start(day: LocalDate): Instant = starts.getOrPut(day) {
        val periods = listOf<Pair<Instant?, String>>(null to base) + changes
        val found = periods.indices.mapNotNull { i ->
            val (since, name) = periods[i]
            val midnight = wall(day, LocalTime.MIDNIGHT, name)
            val until = periods.getOrNull(i + 1)?.first
            midnight.takeIf { (since == null || it >= since) && (until == null || it < until) }
        }
        found.minOrNull() ?: changes.withIndex().firstOrNull { (k, change) ->
            // The midnight was jumped over: the day starts at the change that crossed it.
            local(change.first, change.second) >= day && local(change.first, periods[k].second) < day
        }?.value?.first ?: wall(day, LocalTime.MIDNIGHT, zoneAt(now()))
    }

    /** The day as a half-open range `[start, next start)`. */
    public fun bounds(day: LocalDate): Pair<Instant, Instant> {
        val start = start(day)
        return start to maxOf(start, start(day.plusDays(1)))
    }

    /** The day [ts] falls in. */
    public fun dateOf(ts: Instant): LocalDate {
        var d = local(ts, zoneAt(ts))
        if (changes.isEmpty()) return d
        while (ts < start(d)) d = d.minusDays(1)
        while (ts >= start(d.plusDays(1))) d = d.plusDays(1)
        return d
    }

    /** Every day's start from [first] through the end of [last] (days + 1 edges). */
    public fun starts(first: LocalDate, last: LocalDate): List<Instant> =
        (0..days(first, last) + 1).map { start(first.plusDays(it)) }

    public companion object {
        private val ids = java.util.concurrent.ConcurrentHashMap<String, ZoneId>()

        public fun id(name: String): ZoneId = ids.getOrPut(name) { ZoneId.of(name) }

        /** A local wall-clock time as an instant, resolved like `zoneinfo` with fold=0. */
        public fun wall(day: LocalDate, time: LocalTime, zone: String): Instant = day.atTime(time).atZone(id(zone)).toInstant()

        internal fun local(ts: Instant, zone: String): LocalDate = ts.atZone(id(zone)).toLocalDate()
    }
}

/** How many of the sorted [xs] are at or before [x] (Python's `bisect_right`). */
internal fun <T : Comparable<T>> bisectRight(xs: List<T>, x: T): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (x < xs[mid]) hi = mid else lo = mid + 1
    }
    return lo
}
