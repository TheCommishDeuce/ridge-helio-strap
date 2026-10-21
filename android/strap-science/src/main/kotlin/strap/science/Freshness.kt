package strap.science

import java.time.LocalDate

/** Withhold reasons and the age rules every metric shares (spec/02 §0 freshness horizons). */
public object Freshness {
    public const val NOT_DERIVED_YET: String = "not_derived_yet"
    public const val PROFILE_INCOMPLETE: String = "profile_or_weight_missing"
    public const val NO_NIGHTS_IN_WINDOW: String = "no_recorded_nights_in_window"
    public const val DOB_MISSING: String = "date_of_birth_missing"
    public const val COUNTER_MID_DAY: String = "device_counter_read_mid_day"
    public const val COUNTER_READ_TIME_UNKNOWN: String = "device_counter_read_time_unknown"
    public const val NOT_DERIVED_YET_MESSAGE: String = "Today's number has not been computed yet — sync the strap."
    public const val WEIGHT_STALE: String = "logged_weight_stale"
    public const val WEIGHT_MAX_AGE_DAYS: Int = 14
    public const val RHR_MAX_AGE_DAYS: Int = 30

    public fun weightAgeDays(asOf: LocalDate, on: LocalDate): Long = Math.abs(days(asOf, on))

    public fun weightIsStale(asOf: LocalDate, on: LocalDate): Boolean = weightAgeDays(asOf, on) > WEIGHT_MAX_AGE_DAYS

    /** Null when the newest row is today's; else the gate's own reason, or "not derived yet". */
    public fun unavailableReason(today: LocalDate, lastDay: LocalDate?, gate: (() -> String?)? = null): String? {
        if (lastDay == today) return null
        return gate?.invoke() ?: NOT_DERIVED_YET
    }
}
