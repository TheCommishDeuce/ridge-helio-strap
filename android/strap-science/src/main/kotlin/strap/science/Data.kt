package strap.science

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

// What the science reads and writes. Metric names are the server's (`steps_per_minute`, `spo2`,
// `pai_total_device`, …); the app maps its own names onto them. Fields the server stores as
// REAL are Floats here, so a number goes through the same float32 rounding it did there.

public data class Sample(val ts: Instant, val metric: String, val value: Double)

/** A sleep stage: `[start ms, end ms, code]` (spec/01: 4 light, 5 deep, 7 awake, 8 REM, 128 gap). */
public data class Stage(val startMs: Long, val endMs: Long, val code: Int)

public data class SleepSession(
    val start: Instant,
    val end: Instant,
    val kind: String = MAIN,
    val score: Int? = null,
    val avgHr: Int? = null,
    val remMin: Int? = null,
    val lightMin: Int? = null,
    val deepMin: Int? = null,
    val wakeMin: Int? = null,
    val stages: List<Stage> = emptyList(),
    val source: String = "strap_ble",
) {
    public val isMain: Boolean get() = kind == MAIN

    public companion object {
        public const val MAIN: String = "main"
        public const val NAP: String = "nap"
    }
}

public data class Workout(
    val start: Instant,
    val sport: Int = 0,
    val durationS: Int = 0,
    val calories: Int? = null,
    val distanceM: Float? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val minHr: Int? = null,
)

/** The strap's own counter for a day; [readAt] is when it was read, [reportedAt] when stored. */
public data class DeviceDailyTotal(
    val day: LocalDate,
    val steps: Int?,
    val distanceM: Double?,
    val calories: Double?,
    val source: String,
    val reportedAt: Instant,
    val readAt: Instant?,
)

/** [srpa]: Jurca's self-reported activity category, 0–4, or null when never asked. */
public data class Profile(val heightCm: Float? = null, val sex: String? = null, val dob: LocalDate? = null, val srpa: Int? = null)

public data class WeightEntry(val ts: Instant, val kg: Float)

/** One derived number for a local day, with its flags (spec/02). */
public data class Derived(val day: LocalDate, val metric: String, val value: Double, val flags: JsonObject = JsonObject(emptyMap()))

public data class IllnessFlag(
    val date: LocalDate,
    val severity: String,
    val rrDeltaBpm: Float?,
    val tempDeltaC: Float?,
    val sustained: Boolean,
    val noteIds: List<String>,
)

/** The raw data, read-only. Every range is half-open `[from, until)`, oldest first. */
public interface RawData {
    public val zones: Zones

    public fun samples(metric: String, from: Instant, until: Instant): List<Sample>

    /** Sessions with `end >= from` and `start < until` (callers narrow it further), by start. */
    public fun sleepSessions(from: Instant, until: Instant): List<SleepSession>

    /** Workouts starting in the range. */
    public fun workouts(from: Instant, until: Instant): List<Workout>

    public fun deviceTotal(day: LocalDate): DeviceDailyTotal?

    public fun profile(): Profile?

    /** Every logged weight, oldest first. */
    public fun weights(): List<WeightEntry>

    /**
     * The instants that hold data at or after [from] (all of them when null): each sample's
     * 15-minute floor, every sleep session's start and end, every workout's start. The days
     * they fall in, with [totalDays], are the days a re-derive walks.
     */
    public fun dataInstants(from: Instant?): Collection<Instant>

    /** Every day the strap's own counter was stored for. */
    public fun totalDays(): Collection<LocalDate>

    /** Every main sleep session, by start. */
    public fun mainSessions(): List<SleepSession>
}

/** The derived numbers and illness flags: what the derive passes write and later passes read. */
public interface DerivedStore {
    public fun get(day: LocalDate, metric: String): Derived?

    /** Rows of [metric] from [first] through [last], by day. */
    public fun range(metric: String, first: LocalDate, last: LocalDate): List<Derived>

    public fun put(row: Derived)

    /** Forget every derived row and illness flag of [day] (before it is derived again). */
    public fun clear(day: LocalDate)

    public fun illness(day: LocalDate): IllnessFlag?

    public fun putIllness(flag: IllnessFlag)

    /** Returns whether there was one. */
    public fun clearIllness(day: LocalDate): Boolean

    /** The newest flag dated from [first] through [last]. */
    public fun latestIllness(first: LocalDate, last: LocalDate): IllnessFlag?
}
