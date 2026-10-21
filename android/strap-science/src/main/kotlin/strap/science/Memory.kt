package strap.science

import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** [RawData] held in memory: for tests, and for a window loaded once from the phone's store. */
public class MemoryData(
    override val zones: Zones,
    samples: Collection<Sample> = emptyList(),
    sessions: Collection<SleepSession> = emptyList(),
    workouts: Collection<Workout> = emptyList(),
    totals: Collection<DeviceDailyTotal> = emptyList(),
    private val profile: Profile? = null,
    weights: Collection<WeightEntry> = emptyList(),
) : RawData {
    // One value per (metric, ts), as the server's primary key keeps it; the last one wins.
    private val byMetric = HashMap<String, TreeMap<Instant, Double>>().apply {
        for (s in samples) getOrPut(s.metric) { TreeMap() }[s.ts] = s.value
    }
    private val sessions = sessions.sortedBy { it.start }
    private val workouts = workouts.sortedBy { it.start }
    private val totals = totals.associateBy { it.day }
    private val weights = weights.sortedBy { it.ts }

    override fun samples(metric: String, from: Instant, until: Instant): List<Sample> {
        if (until <= from) return emptyList()
        return byMetric[metric]?.subMap(from, true, until, false)?.map { (ts, v) -> Sample(ts, metric, v) } ?: emptyList()
    }

    override fun sleepSessions(from: Instant, until: Instant): List<SleepSession> = sessions.filter { it.end >= from && it.start < until }

    override fun workouts(from: Instant, until: Instant): List<Workout> = workouts.filter { it.start >= from && it.start < until }

    override fun deviceTotal(day: LocalDate): DeviceDailyTotal? = totals[day]

    override fun profile(): Profile? = profile

    override fun weights(): List<WeightEntry> = weights

    override fun dataInstants(from: Instant?): Collection<Instant> {
        val out = HashSet<Instant>()
        for (series in byMetric.values) for (ts in series.keys) out += Instant.ofEpochSecond(Math.floorDiv(ts.epochSecond, 900L) * 900)
        for (s in sessions) {
            out += s.start
            out += s.end
        }
        for (w in workouts) out += w.start
        return if (from == null) out else out.filter { it >= from }
    }

    override fun totalDays(): Collection<LocalDate> = totals.keys

    override fun mainSessions(): List<SleepSession> = sessions.filter { it.isMain }

    /**
     * The part of this data from [from] (a day start, [Zones.rawFrom]) until [until] (null: no
     * end): samples and workouts that start inside it, nights that start at most a day before
     * [from] (a night is shorter than a day) and before [until], day counters of the days inside
     * it; the profile and every weight. The phone's store loads the same cut.
     */
    public fun window(from: Instant, until: Instant? = null): MemoryData {
        val first = zones.dateOf(from)
        val last = until?.let(zones::dateOf)
        fun inside(t: Instant) = t >= from && (until == null || t < until)
        return MemoryData(
            zones,
            byMetric.flatMap { (metric, series) -> series.filterKeys(::inside).map { (ts, v) -> Sample(ts, metric, v) } },
            sessions.filter { it.start >= from.minus(java.time.Duration.ofDays(1)) && (until == null || it.start < until) },
            workouts.filter { inside(it.start) },
            totals.values.filter { it.day >= first && (last == null || it.day < last) },
            profile, weights,
        )
    }
}

/** [DerivedStore] held in memory. */
public class MemoryDerived : DerivedStore {
    private val rows = TreeMap<LocalDate, LinkedHashMap<String, Derived>>()
    private val flags = TreeMap<LocalDate, IllnessFlag>()

    override fun get(day: LocalDate, metric: String): Derived? = rows[day]?.get(metric)

    override fun range(metric: String, first: LocalDate, last: LocalDate): List<Derived> =
        if (last < first) emptyList() else rows.subMap(first, true, last, true).values.mapNotNull { it[metric] }

    override fun put(row: Derived) {
        rows.getOrPut(row.day) { LinkedHashMap() }[row.metric] = row
    }

    override fun clear(day: LocalDate) {
        rows.remove(day)
        flags.remove(day)
    }

    override fun illness(day: LocalDate): IllnessFlag? = flags[day]

    override fun putIllness(flag: IllnessFlag) {
        flags[flag.date] = flag
    }

    override fun clearIllness(day: LocalDate): Boolean = flags.remove(day) != null

    override fun latestIllness(first: LocalDate, last: LocalDate): IllnessFlag? =
        if (last < first) null else flags.subMap(first, true, last, true).lastEntry()?.value

    /** Every row, by day then metric. */
    public fun all(): List<Derived> = rows.values.flatMap { day -> day.values.sortedBy { it.metric } }

    public fun allIllness(): Collection<IllnessFlag> = flags.values
}
