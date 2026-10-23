package strap.science

import java.time.Instant
import java.time.LocalDate

// Read-time views the screens draw over many days or one workout, from the same rows and
// functions the day summary uses (so a calendar ring always equals that day's own screen).

/** A day's four rings for the calendar: recovery, strain (0–21), the strap's sleep score, mean stress. */
public data class DayRings(val date: LocalDate, val recovery: Int?, val strain: Double?, val sleepScore: Int?, val stress: Double?)

/** [DayRings] for every day from [from] through [to]. Strain uses each day's own 90-day P95, as [daySummary] does. */
public fun Science.dayRings(from: LocalDate, to: LocalDate): List<DayRings> {
    val loads = store.range("cardio_load", from.minusDays(90), to).associate { it.day to it.value }
    val recovery = store.range("recovery_score", from, to).associate { it.day to it.value.toInt() }
    val out = ArrayList<DayRings>()
    var day = from
    while (day <= to) {
        val d = day
        val strain = loads[d]?.let { load ->
            val window = loads.filterKeys { it >= d.minusDays(90) && it <= d }.values.filter { it > 0 }
            strainFromLoad(load, Robust.percentileCont(window, 0.95)?.takeIf { it != 0.0 })
        }
        val (start, end) = dayBounds(d)
        val score = data.sleepSessions(start, end).lastOrNull { it.isMain && it.end >= start && it.end < end }?.score?.takeIf { it != 0 }
        val stress = data.samples("stress", start, end).map { it.value }.takeIf { it.isNotEmpty() }?.average()
        out += DayRings(d, recovery[d], strain, score, stress?.let { round(it, 1) })
        day = day.plusDays(1)
    }
    return out
}

/**
 * The illness check behind the flag (spec/02 §2.9), for showing when nothing is flagged:
 * each limb's change against its own 14 nights, and how many baseline nights there are of the
 * [needed]. A limb is null until it has its baseline. The flag itself stays in [illnessFlag].
 */
public data class IllnessCheck(
    val rrDelta: Double?,
    val tempDelta: Double?,
    val nights: Int,
    val needed: Int,
    /** The night's mean skin temperature (°C, main sleep, readings over 25 °C) and the median of the 14 nights before (five or more). */
    val tempNight: Double? = null,
    val tempUsual: Double? = null,
)

public fun Science.illnessCheck(day: LocalDate): IllnessCheck {
    val rrNights = store.range("respiratory_rate_sleep", firstNight(day), day).associate { it.day to it.value }
    val tempNights = tempNights(day)
    val first = day.minusDays(Illness.BASELINE_NIGHTS.toLong())
    val nights = minOf(rrNights.keys.count { it >= first && it < day }, tempNights.keys.count { it >= first && it < day })
    val tempBefore = tempNights.filterKeys { it >= first && it < day }.values.toList()
    return IllnessCheck(
        Illness.limbFor(rrNights, day)?.let { round(it.delta, 2) },
        Illness.limbFor(tempNights, day)?.let { round(it.delta, 2) },
        nights, Illness.MIN_BASELINE_NIGHTS,
        tempNights[day]?.let { round(it, 2) },
        tempBefore.takeIf { it.size >= 5 }?.let { round(Robust.median(it), 2) },
    )
}

/**
 * One workout's own load: TRIMP over its minutes with the day's resting HR and max HR (the
 * same function cardio load uses, spec/02 §2.4), minutes below 50 % and in each Edwards zone,
 * its strain against the day's 90-day P95, and its per-minute heart rate.
 */
public data class WorkoutDetail(
    val trimp: Double,
    /** Minutes below 50 % of max HR, then zones 1–5. */
    val zoneMinutes: List<Int>,
    val strain: Double?,
    val hrmax: Int,
    val rhr: Int,
    val hr: List<TimedValue>,
)

/** Null without a profile or a fresh resting HR, as cardio load. */
public fun Science.workoutDetail(start: Instant, durationS: Int): WorkoutDetail? {
    val day = zones.dateOf(start)
    val prof = loadProfile(day) ?: return null
    val hrmax = hrMax(age(prof.dob, day))
    val rhr = measuredRhr(day) ?: return null
    if (hrmax - rhr < 1) return null
    val minutes = hrPerMinute(start, start.plusSeconds(durationS.toLong()))
    if (minutes.isEmpty()) return null
    var trimp = 0.0
    val zones = IntArray(6)
    for ((_, hr) in minutes) {
        trimp += Trimp.minute(hr, rhr, hrmax, prof.sex)
        zones[EDWARDS_ZONE_LO.count { hr / hrmax >= it }] += 1
    }
    val p95 = Robust.percentileCont(store.range("cardio_load", day.minusDays(90), day).map { it.value }.filter { it > 0 }, 0.95)
    return WorkoutDetail(round(trimp, 1), zones.toList(), strainFromLoad(trimp, p95?.takeIf { it != 0.0 }), roundInt(hrmax).toInt(), roundInt(rhr).toInt(),
        minutes.map { (t, v) -> TimedValue(t, v) })
}
