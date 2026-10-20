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
    HRV_SDNN("hrv_sdnn"),

    /** The strap's own PAI (`0x0D`): the day's points and the rolling 7-day total. */
    PAI_DAILY("pai_daily"),
    PAI_TOTAL("pai_total"),

    /**
     * The strap's nightly sleep-apnea summary (`0x4A`), stamped at the local midnight that
     * starts the wake day. Only [ODI_EVENTS] (desaturation count) has a known meaning; the
     * other two are kept as sent until checked on the strap.
     */
    ODI_EVENTS("odi_events"),
    ODI_SCORE("odi_score"),
    ODI_VALID_TIME("odi_valid_time"),
    ODI_RAW("odi_raw"),

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
