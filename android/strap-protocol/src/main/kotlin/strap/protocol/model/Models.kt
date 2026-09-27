package strap.protocol.model

import java.time.Instant

/** One decoded reading. [metric] names the stream; units are the metric's own. */
public data class Sample(val at: Instant, val metric: Metric, val value: Double)

/** Every per-sample stream the strap delivers (spec/01 §7). [wireName] is the storage name. */
public enum class Metric(public val wireName: String) {
    HR("hr"),
    STEPS("steps"),
    STRESS("stress"),
    TEMPERATURE_C("temperature_c"),
    MANUAL_HR("manual_hr"),
    STRESS_MANUAL("stress_manual"),
    RESTING_HR("resting_hr"),
    MAX_HR("max_hr"),
    HRV("hrv"),
    RESPIRATORY_RATE("respiratory_rate"),
    SPO2("spo2"),
    SPO2_SLEEP("spo2_sleep"),

    /** A marker per sleep record in a `0x48` round; the sessions come from `SleepParser`. */
    SLEEP_SESSION("sleep_session"),
}

/** Hypnogram stage codes as the strap writes them. */
public enum class Stage(public val code: Int) {
    LIGHT(4),
    DEEP(5),
    AWAKE(7),
    REM(8),
    ;

    public companion object {
        public fun of(code: Int): Stage? = entries.firstOrNull { it.code == code }
    }
}

public data class SleepStage(val start: Instant, val end: Instant, val stage: Stage)

/**
 * One night (or nap) as the strap recorded it. Minutes fields are the device's own, from
 * (midnight − 24 h); a nap's stage minutes are summed from its stages.
 */
public data class SleepSession(
    val sessionStart: Instant,
    val sleepStartMin: Int,
    val sleepEndMin: Int,
    val avgHr: Int,
    val score: Int,
    val stages: List<SleepStage>,
    val remMin: Int,
    val lightMin: Int,
    val deepMin: Int,
    val wakeMin: Int,
    val isNap: Boolean,
)

/** One workout summary (fetch `0x05`). */
public data class Workout(
    val start: Instant,
    val sportType: Int,
    val durationSec: Int,
    val calories: Int,
    val avgHr: Int,
    val maxHr: Int,
    val minHr: Int,
)

/** The strap's live since-midnight counters — the authoritative step total (spec/01 §5). */
public data class DailyTotals(val steps: Long, val distanceM: Long, val calories: Long, val readAt: Instant)
