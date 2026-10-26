package strap.science

import java.time.LocalDate

/** Per-night metrics for one main sleep session, filed on its wake date. */
public fun Science.deriveNight(session: SleepSession) {
    val day = wakeDate(session.end)
    rhr(session.start, session.end)?.let { (rhr, n) -> upsert(day, "rhr_daily", rhr, json("n" to n)) }
    nightVitals(day, session.start, session.end)
    // A night without a complete stage breakdown scores nothing: three of the four dimensions
    // and the sleep debt are functions of the stage minutes, and inventing them means zeros.
    val rem = session.remMin
    val light = session.lightMin
    val deep = session.deepMin
    val wake = session.wakeMin
    if (rem != null && light != null && deep != null && wake != null) sleepScore(session, rem, light, deep, wake, day)
}

/**
 * The daily chain in dependency order: MVPA → activity and calories → VO₂max (needs resting
 * HR) → cardio load (needs resting HR) → sleep debt (needs the night's sleep time) → recovery.
 * Reads the day's night rows and earlier days' rows, never later ones.
 */
public fun Science.deriveDay(day: LocalDate) {
    mvpa(day)
    dailyActivity(day)
    vo2max(day)
    cardioLoad(day)
    sleepDebt(day)
    recovery(day)
}

/**
 * How far back, in days before the first day worked out or shown, any pass or card reads raw
 * data: 30 days for the summary's usual values and breathing baseline (the longest; illness
 * reads 15 nights, Energy 14 days and an 8-day warm-up, the SRI 7 nights), one more for a night
 * that began the evening before, and one for a day a zone change stretched. Baselines read the
 * derived rows, which this does not limit. [MemoryData.window] and the phone's store cut by it.
 */
public const val RAW_LOOKBACK_DAYS: Long = 32

/** The first instant of raw data that working out or showing [day] (and any later day) reads. */
public fun Zones.rawFrom(day: LocalDate): java.time.Instant = start(day.minusDays(RAW_LOOKBACK_DAYS))

/** The days holding data from [since] on (every day when null), oldest first. */
public fun Science.dataDays(since: LocalDate? = null): List<LocalDate> {
    val days = data.dataInstants(since?.let(zones::start)).mapTo(HashSet()) { zones.dateOf(it) }
    days += data.totalDays()
    return days.filter { since == null || it >= since }.sorted()
}

/**
 * Derives every day holding data from [since] on (everything when null), oldest first: each
 * day is cleared, then the nights that wake on it, then the day, then the illness flag. A
 * day's numbers read only that day's and earlier days' rows, so this equals the server's
 * full `rederive` over the same data. Returns the days derived.
 *
 * New data on a day changes the baselines of every later one, so after a sync the caller
 * passes the earliest day the sync touched.
 */
public fun Science.rederive(since: LocalDate? = null): List<LocalDate> {
    val days = dataDays(since)
    val nights = data.mainSessions().groupBy { wakeDate(it.end) }
    for (day in days) {
        store.clear(day)
        nights[day]?.forEach { deriveNight(it) }
        deriveDay(day)
        illnessFlag(day)
    }
    return days
}
