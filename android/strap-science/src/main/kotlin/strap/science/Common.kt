package strap.science

import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The derive passes over one owner's data: they read [data] and write [store]. Every daily
 * metric anchors on a local day of [RawData.zones]; every range is `[start, next start)`.
 * Ported from the server's `derive/` (itself ported verbatim from healthee@049c9ad) and held
 * to its golden fixture, so the rounding and the order of operations are the server's.
 */
public class Science(public val data: RawData, public val store: DerivedStore) {
    public val zones: Zones get() = data.zones

    internal fun upsert(day: LocalDate, metric: String, value: Double, flags: JsonObject = JsonObject(emptyMap())) {
        store.put(Derived(day, metric, round(value, 4), flags))
    }

    internal fun upsert(day: LocalDate, metric: String, value: Number, flags: JsonObject = JsonObject(emptyMap())): Unit =
        upsert(day, metric, value.toDouble(), flags)

    /** AVG/MIN/MAX of a metric's values within `[lo, hi]` over the window, or null without any. */
    internal fun windowStat(metric: String, start: Instant, end: Instant, lo: Double, hi: Double, stat: Stat = Stat.AVG): Double? {
        val values = data.samples(metric, start, end).map { it.value }.filter { it in lo..hi }
        if (values.isEmpty()) return null
        return when (stat) {
            Stat.AVG -> values.sum() / values.size
            Stat.MIN -> values.min()
            Stat.MAX -> values.max()
        }
    }

    internal enum class Stat { AVG, MIN, MAX }

    /** Daily metrics anchor on the local wake (session end) date. */
    internal fun wakeDate(end: Instant): LocalDate = zones.dateOf(end)

    internal fun dayBounds(day: LocalDate): Pair<Instant, Instant> = zones.bounds(day)

    internal fun dateOfBirth(): LocalDate? = data.profile()?.dob

    /** Profile plus the weight as of [day]; null when height, sex, birth date or any weight is missing. */
    internal fun loadProfile(day: LocalDate): ProfileAsOf? {
        val p = data.profile() ?: return null
        if (p.heightCm == null || p.sex == null || p.dob == null) return null
        val (kg, asOf) = weightAsOf(day) ?: return null
        return ProfileAsOf(p.heightCm.real(), p.sex, p.dob, p.srpa, kg, asOf)
    }

    /** The newest weight logged before the day ends, else the earliest one, with its local log date. */
    internal fun weightAsOf(day: LocalDate): Pair<Double, LocalDate>? {
        val all = data.weights()
        val end = zones.bounds(day).second
        val w = all.lastOrNull { it.ts < end } ?: all.firstOrNull() ?: return null
        return w.kg.real() to zones.dateOf(w.ts)
    }

    /** Main-sleep and nap windows overlapping `[start, end)`. */
    internal fun sleepWindows(start: Instant, end: Instant): List<Pair<Instant, Instant>> =
        data.sleepSessions(start, end).map { it.start to it.end }

    /** Per-minute groups (`date_trunc('minute', ts)`) of a metric's samples. */
    internal fun perMinute(metric: String, start: Instant, end: Instant, keep: (Double) -> Boolean, sum: Boolean): LinkedHashMap<Instant, Double> {
        val groups = LinkedHashMap<Instant, MutableList<Double>>()
        for (s in data.samples(metric, start, end)) if (keep(s.value)) groups.getOrPut(s.ts.floorMinute()) { ArrayList() } += s.value
        return groups.mapValuesTo(LinkedHashMap()) { (_, vs) -> if (sum) vs.sum() else vs.sum() / vs.size }
    }
}

internal data class ProfileAsOf(
    val heightCm: Double,
    val sex: String,
    val dob: LocalDate,
    val srpa: Int?,
    val weightKg: Double,
    val weightAsOf: LocalDate,
)

/** Whole years old on [on]. */
internal fun age(dob: LocalDate, on: LocalDate): Int {
    var years = on.year - dob.year
    if (on.monthValue < dob.monthValue || (on.monthValue == dob.monthValue && on.dayOfMonth < dob.dayOfMonth)) years -= 1
    return years
}

/** Whole minutes in a local day (1440 on an ordinary day, 1380/1500 across DST). */
internal fun dayMinutes(start: Instant, end: Instant): Int = Math.floorDiv(Duration.between(start, end).seconds, 60L).toInt()

internal fun clamp100(x: Double): Double = maxOf(0.0, minOf(100.0, x))

/** An engineering artefact filter, not research (spec/02 §0). */
internal object HrValidity {
    const val MIN: Double = 30.0
    const val MAX: Double = 220.0

    fun valid(bpm: Double): Boolean = bpm in MIN..MAX
}
