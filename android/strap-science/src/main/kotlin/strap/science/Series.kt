package strap.science

import java.time.Instant
import java.time.LocalDate

// The chart series (the server's `read/series.py`): a day at full per-minute resolution, and
// longer ranges in buckets that keep the minimum and maximum, so a spike survives any zoom (D12).

/** Series name → (stored metric, which values count). */
public val SERIES: Map<String, Pair<String, (Double) -> Boolean>> = mapOf(
    "hr" to ("hr" to HrValidity::valid),
    "stress" to ("stress" to { _ -> true }),
    "steps" to ("steps_per_minute" to { v -> v > 0 && v < 250 }),
    "hrv" to ("hrv" to { _ -> true }),
    "spo2" to ("spo2" to { _ -> true }),
    "skin_temp" to ("skin_temp_c" to { v -> v > 25.0 }),
    "respiratory_rate" to ("respiratory_rate" to { _ -> true }),
    "hrv_sdnn" to ("hrv_sdnn" to { _ -> true }),
)

public enum class Bucket(public val key: String, internal val seconds: Long) {
    QUARTER("15m", 900), HOUR("1h", 3600), DAY("1d", 86400);

    public companion object {
        public fun of(key: String): Bucket = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("bucket must be one of [15m, 1d, 1h]")
    }
}

private fun Science.seriesRows(name: String, start: Instant, end: Instant): List<Sample> {
    val (metric, keep) = SERIES[name] ?: throw IllegalArgumentException("unknown series $name")
    return filtered(metric, start, end, keep)
}

/** Every reading of each named series on [day], and the sleep windows touching it. */
public fun Science.daySeries(day: LocalDate, names: List<String>): DaySeries {
    val (start, end) = zones.bounds(day)
    val series = names.associateWith { name -> seriesRows(name, start, end).map { TimedValue(it.ts, it.value) } }
    val sleep = data.sleepSessions(start, end).filter { it.end > start }.map { SleepSpan(it.start, it.end, it.kind) }
    return DaySeries(day, zones.zoneOf(day), start, end, series, sleep)
}

/** Buckets of [name] from [first] through [last]: min, max, mean, sum, count and the time of the max. */
public fun Science.bucketSeries(name: String, first: LocalDate, last: LocalDate, bucket: Bucket): BucketSeries {
    require(last >= first) { "the range ends before it starts" }
    require((days(first, last) + 1) * 86400 / bucket.seconds <= 5000) { "more than 5000 buckets: use a coarser bucket or a shorter range" }
    val edges = bucketEdges(first, last, bucket)
    val groups = sortedMapOf<Int, MutableList<Sample>>()
    for (s in seriesRows(name, edges.first(), edges.last())) groups.getOrPut(bisectRight(edges, s.ts)) { ArrayList() } += s
    val rows = groups.map { (b, rows) ->
        val hi = rows.maxOf { it.value }
        SeriesBucket(edges[b - 1], rows.minOf { it.value }, hi, round(rows.sumOf { it.value } / rows.size, 2), rows.sumOf { it.value }, rows.size, rows.first { it.value == hi }.ts)
    }
    return BucketSeries(name, bucket, first, last, zones.zoneOf(last), rows)
}

/** Day starts, each day cut into fixed steps from its own start (so a 25-hour day has 25 hours). */
private fun Science.bucketEdges(first: LocalDate, last: LocalDate, bucket: Bucket): List<Instant> {
    val days = zones.starts(first, last)
    if (bucket == Bucket.DAY) return days
    val edges = ArrayList<Instant>()
    for ((start, end) in days.zipWithNext()) {
        var t = start
        while (t < end) {
            edges += t
            t = t.plusSeconds(bucket.seconds)
        }
    }
    edges += days.last()
    return edges
}
