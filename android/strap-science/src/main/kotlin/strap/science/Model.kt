package strap.science

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// What the day screens read (spec/02 §6): the day summary, the day's series and the bucketed
// ranges, as types. The server's read API answered the same in JSON; the golden tests still
// compare that JSON, built from these in the test sources.

/** A card's content, or why there is none: a named reason and the text to show. */
public sealed interface Card<out T> {
    public data class Shown<out T>(val value: T) : Card<T>

    public data class Withheld(val reason: String, val message: String) : Card<Nothing>
}

/** The content, if shown. */
public val <T> Card<T>.shown: T? get() = (this as? Card.Shown<T>)?.value

/** The 30 days before, for a daily number: their median, how many, and how far off it the day is. */
public data class Baseline(val median: Double, val n: Int, val z: Double?)

/** A stored daily number (rounded for display), its flags, and its [baseline] once there is enough history. */
public data class DailyNumber(val value: Double, val flags: JsonObject, val baseline: Baseline?)

/** The usual value of earlier days (D28), up to [until] on each when the day is still running. */
public data class Usual(val median: Double, val n: Int, val until: LocalTime?)

/** A day's raw readings of one signal: the extremes (the first on a tie), the mean, and the usual. */
public data class DayStats(
    val min: Double,
    val max: Double,
    val mean: Double,
    val n: Int,
    val maxAt: Instant,
    val minAt: Instant,
    val usual: Usual?,
)

/** One part of the recovery score: [key] hrv, rhr and rr carry value, baseline and z; sleep carries the minutes asleep and needed. */
public data class RecoveryFactor(
    val key: String,
    val sub: Int,
    val weight: Double,
    val value: Double,
    val baseline: Double? = null,
    val z: Double? = null,
    val needMin: Double? = null,
)

public data class Recovery(val value: Int, val flags: JsonObject, val factors: List<RecoveryFactor>)

/**
 * The day's strain on 0–[max] from its cardio load against the owner's 90 days ([value] null
 * until there is a history to place it), the minutes in each heart-rate zone, and the
 * [hrmax] and [rhr] the zones were cut by.
 */
public data class Strain(
    val value: Double?,
    val max: Double,
    val cardioLoad: Double,
    val typicalLoad: Double?,
    val flags: JsonObject,
    val zoneMinutes: List<Int>?,
    val hrmax: Int?,
    val rhr: Int?,
)

/**
 * Charge (D33) now, or at a past day's end ([at]): sleep [recharged] it; [activity], [stress]
 * and everyday [living] drained it. [threshold] is the stress that starts to drain it,
 * [personal] when learned from the owner's calm readings. [curve] is every ten minutes.
 */
public data class ChargeCard(
    val value: Int,
    val at: Instant,
    val atWake: Int?,
    val high: Int,
    val low: Int,
    val recharged: Double,
    val activity: Double,
    val stress: Double,
    val living: Double,
    val threshold: Int,
    val personal: Boolean,
    val curve: List<TimedValue>,
)

public data class TimedValue(val at: Instant, val value: Double)

/** A night or nap that ended on the day, with the strap's own stage minutes and score. */
public data class Night(
    val start: Instant,
    val end: Instant,
    val kind: String,
    val remMin: Int?,
    val lightMin: Int?,
    val deepMin: Int?,
    val awakeMin: Int?,
    val stages: List<Stage>,
    val deviceScore: Int?,
    /** Of [lightMin], the minutes Ridge added for an hour a clock change took (0 for most nights). */
    val estimatedMin: Int = 0,
) {
    val isMain: Boolean get() = kind == SleepSession.MAIN
}

/** The four sleep-health dimensions (spec/02 §2.4) of the night, each met (1) or not (0). */
public data class SleepHealth(
    val dimensions: Int,
    val duration: Int,
    val efficiency: Int,
    val timing: Int,
    val regularity: Int,
    val tstMin: Double,
    val efficiencyPct: Double,
    /** The night's midpoint on the local clock, as the server wrote it (ISO with offset). */
    val midpointLocal: String,
    val flags: JsonObject,
)

/** Sleep debt over the last [windowNights] nights. */
public data class SleepDebt(
    val minutes: Double,
    val windowNights: Int,
    val nights: Int,
    val avgTstMin: Double,
    val nightsBelow: Int,
    val flags: JsonObject,
)

/** Blood-oxygen drops per hour the strap measured through the night, and the usual. */
public data class Breathing(val value: Double, val drops: Int, val hours: Double, val usual: Usual?)

public data class SleepCard(
    val nights: List<Night>,
    val health: Card<SleepHealth>,
    val needMin: Double?,
    val debt: Card<SleepDebt>,
    /** The sleep regularity index. */
    val regularity: Card<Double>,
    val breathing: Card<Breathing>,
) {
    /** The main night, the last one if several. */
    val main: Night? get() = nights.lastOrNull { it.isMain }
}

public data class Activity(
    val steps: Card<DailyNumber>,
    /** Steps usually taken by this time of day; only asked for a day still running. */
    val usualByNow: Usual?,
    val distanceM: Card<DailyNumber>,
    val activeCalories: Card<DailyNumber>,
    val totalCalories: Card<DailyNumber>,
    val mvpaMin: Card<DailyNumber>,
)

public data class Heart(val resting: Card<DailyNumber>, val hrv: Card<DailyNumber>, val today: Card<DayStats>)

/** The strap's own PAI: the 7-day [value] against [goal], [today]'s points and the week's. */
public data class Pai(val value: Int, val today: Int, val week: Map<LocalDate, Int>, val goal: Int)

public data class IllnessCard(
    val date: LocalDate,
    val severity: String,
    val sustained: Boolean,
    val rrDeltaBpm: Double?,
    val tempDeltaC: Double?,
    val noteIds: List<String>,
    /** The sentence to show. */
    val framing: String,
)

/**
 * Everything the day screens show for [date], whose local day runs [start]–[end] in
 * [timezone]. [until] is the clock time the usual values compare to while the day runs (null
 * for a past day).
 */
public data class DaySummary(
    val date: LocalDate,
    val timezone: String,
    val start: Instant,
    val end: Instant,
    val until: LocalTime?,
    val recovery: Card<Recovery>,
    val charge: Card<ChargeCard>,
    val strain: Card<Strain>,
    val sleep: SleepCard,
    val activity: Activity,
    val heart: Heart,
    val stress: Card<DayStats>,
    val pai: Card<Pai>,
    val vo2max: Card<DailyNumber>,
    val illness: IllnessCard?,
)

public data class SleepSpan(val start: Instant, val end: Instant, val kind: String)

/** Every reading of each named series on [date], and the sleep touching the day. */
public data class DaySeries(
    val date: LocalDate,
    val timezone: String,
    val start: Instant,
    val end: Instant,
    val series: Map<String, List<TimedValue>>,
    val sleep: List<SleepSpan>,
)

/** One bucket from [start]: its readings' extremes, mean, sum and count, and when the max fell. */
public data class SeriesBucket(val start: Instant, val min: Double, val max: Double, val mean: Double, val sum: Double, val n: Int, val maxAt: Instant)

public data class BucketSeries(
    val metric: String,
    val bucket: Bucket,
    val from: LocalDate,
    val to: LocalDate,
    val timezone: String,
    val buckets: List<SeriesBucket>,
)
