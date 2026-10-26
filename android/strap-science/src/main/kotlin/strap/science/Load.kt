package strap.science

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

// VO₂max (Jurca, spec/02 §2.10), cardio load (Banister TRIMP + Edwards zones, §2.4) and the
// morning recovery score (§2.7).

/** Banister's TRIMP, one definition (cardio load and Energy both use it). */
public object Trimp {
    private val WEIGHTS = mapOf("male" to (0.64 to 1.92), "female" to (0.86 to 1.67))

    public fun weights(sex: String?): Pair<Double, Double> = WEIGHTS[sex ?: "male"] ?: WEIGHTS.getValue("male")

    public fun hrReserveFraction(hr: Double, rhr: Double, hrmax: Double): Double = maxOf(0.0, minOf(1.0, (hr - rhr) / (hrmax - rhr)))

    public fun minute(hr: Double, rhr: Double, hrmax: Double, sex: String?): Double {
        val (a, b) = weights(sex)
        val dhr = hrReserveFraction(hr, rhr, hrmax)
        return dhr * a * Math.exp(b * dhr)
    }
}

/** Tanaka-style max HR the old system used: 208 − 0.7 × age. */
internal fun hrMax(years: Int): Double = 208 - 0.7 * years

// --- VO₂max --------------------------------------------------------------------------------

internal object Vo2max {
    const val SEE = 1.45 * 3.5
    const val FEW_RHR_DAYS = "insufficient_rhr_days"
    const val RHR_OUT_OF_RANGE = "rhr_median_outside_validated_range"
    const val RHR_TOO_NOISY = "rhr_7d_mad_above_8_bpm"
    const val SRPA_NOT_REPORTED = "srpa_not_reported"
    private val SRPA_METS = doubleArrayOf(0.0, 0.32, 1.06, 1.76, 3.03)

    fun srpaMets(srpa: Int): Double = SRPA_METS[srpa.coerceIn(0, SRPA_METS.size - 1)]

    fun withholdReason(rhrs: List<Double>): String? = when {
        rhrs.size < 3 -> FEW_RHR_DAYS
        Robust.median(rhrs) !in 40.0..100.0 -> RHR_OUT_OF_RANGE
        Robust.medianAbsDeviation(rhrs) > 8.0 -> RHR_TOO_NOISY
        else -> null
    }

    fun profileReason(prof: ProfileAsOf, day: LocalDate): String? = when {
        prof.srpa == null -> SRPA_NOT_REPORTED
        Freshness.weightIsStale(prof.weightAsOf, day) -> Freshness.WEIGHT_STALE
        else -> null
    }

    fun jurca(age: Int, sex: String, bmi: Double, rhr: Double, srpa: Int): Double {
        val sexTerm = if (sex == "male") 1.0 else 0.0
        val crfMets = 18.07 + 2.77 * sexTerm - 0.1 * age - 0.17 * bmi - 0.03 * rhr + srpaMets(srpa)
        return maxOf(crfMets * 3.5, 20.0)
    }
}

/** The nightly resting HRs of the 7 days ending on [day]. */
internal fun Science.rhrWeek(day: LocalDate): List<Double> = store.range("rhr_daily", day.minusDays(6), day).map { it.value }

internal fun Science.vo2maxWithholdReason(day: LocalDate): String? {
    val prof = loadProfile(day) ?: return Freshness.PROFILE_INCOMPLETE
    return Vo2max.profileReason(prof, day) ?: Vo2max.withholdReason(rhrWeek(day))
}

internal fun Science.vo2max(day: LocalDate) {
    val prof = loadProfile(day) ?: return
    if (Vo2max.profileReason(prof, day) != null) return
    val years = age(prof.dob, day)
    val metres = prof.heightCm / 100
    val bmi = prof.weightKg / (metres * metres)
    val rhrs = rhrWeek(day)
    if (Vo2max.withholdReason(rhrs) != null) return
    val rhrMed = Robust.median(rhrs)
    val srpa = prof.srpa!!
    upsert(
        day, "vo2max_estimate", Vo2max.jurca(years, prof.sex, bmi, rhrMed, srpa),
        json(
            "method" to "jurca_non_exercise", "rhr_med" to round(rhrMed, 1), "srpa" to srpa, "bmi" to round(bmi, 1),
            "age_years" to years, "sex" to prof.sex, "see_ml_kg_min" to Vo2max.SEE,
        ),
    )
}

// --- Cardio load ---------------------------------------------------------------------------

internal val EDWARDS_ZONE_LO = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9)

/**
 * Which minutes are training load (D37, after OpenStrap 2.1): every minute of a workout, whatever
 * its intensity; outside workouts, only a waking minute that is moving and hard enough, so a
 * raised heart rate at a desk adds nothing.
 */
internal object DailyLoad {
    const val EVERYDAY_HRR = 0.35
    /** Sustained walking at about 100 steps a minute sits at HRR 0.24–0.34, under [EVERYDAY_HRR]. */
    const val WALK_HRR = 0.20
    const val WALK_STEPS = 60.0
    /** A minute counts only when this many of the last [RUN_WINDOW] (itself included) pass, so single PPG spikes don't. */
    const val RUN_MINUTES = 3
    const val RUN_WINDOW = 5

    fun everyday(hrr: Double, steps: Double): Boolean = steps > 0 && (hrr >= EVERYDAY_HRR || (steps >= WALK_STEPS && hrr >= WALK_HRR))
}

/** The newest nightly resting HR within 30 days up to [day]. */
internal fun Science.measuredRhr(day: LocalDate): Double? =
    store.range("rhr_daily", day.minusDays(Freshness.RHR_MAX_AGE_DAYS.toLong()), day).lastOrNull()?.value

/** Per-minute mean of valid HR. */
internal fun Science.hrPerMinute(start: Instant, end: Instant): LinkedHashMap<Instant, Double> =
    perMinute("hr", start, end, keep = HrValidity::valid, sum = false)

internal fun Science.cardioLoad(day: LocalDate) {
    val prof = loadProfile(day) ?: return
    val hrmax = hrMax(age(prof.dob, day))
    val rhr = measuredRhr(day) ?: return
    if (hrmax - rhr < 1) return
    val (start, end) = dayBounds(day)
    val sleepWins = sleepWindows(start, end)
    val hrs = hrPerMinute(start, end)
    val steps = perMinute("steps_per_minute", start, end, keep = { it > 0 && it < 250 }, sum = true)
    val workouts = data.workouts(start.minus(java.time.Duration.ofDays(1)), end).map { it.start to it.start.plusSeconds(it.durationS.toLong()) }
    fun inWorkout(m: Instant) = workouts.any { (s, e) -> m < e && m.plusSeconds(60) > s }
    fun asleep(m: Instant) = sleepWins.any { (s, e) -> s <= m && m < e }
    fun everyday(m: Instant): Boolean {
        val hr = hrs[m] ?: return false
        return !asleep(m) && !inWorkout(m) && DailyLoad.everyday(Trimp.hrReserveFraction(hr, rhr, hrmax), steps[m] ?: 0.0)
    }
    var workoutLoad = 0.0
    var everydayLoad = 0.0
    val zones = IntArray(5)
    var nHr = 0
    for ((m, hr) in hrs) {
        if (inWorkout(m)) workoutLoad += Trimp.minute(hr, rhr, hrmax, prof.sex)
        else if (everyday(m) && (0 until DailyLoad.RUN_WINDOW).count { everyday(m.minusSeconds(60L * it)) } >= DailyLoad.RUN_MINUTES) {
            everydayLoad += Trimp.minute(hr, rhr, hrmax, prof.sex)
        }
        // Zone time describes the whole waking day, worn minutes included ([hr_minutes]), not only the load.
        if (asleep(m)) continue
        nHr += 1
        val pct = hr / hrmax
        for (zi in 4 downTo 0) {
            if (pct >= EDWARDS_ZONE_LO[zi]) {
                zones[zi] += 1
                break
            }
        }
    }
    if (nHr == 0) return
    val zoneMin = zones.toList()
    val edwards = zoneMin.withIndex().sumOf { (i, z) -> (i + 1) * z }
    upsert(
        day, "cardio_load", round(workoutLoad + everydayLoad, 1),
        json(
            "method" to "banister", "hrmax" to roundInt(hrmax), "rhr" to roundInt(rhr), "zone_min" to zoneMin, "edwards_tl" to edwards, "hr_minutes" to nHr,
            "workout_load" to round(workoutLoad, 1), "everyday_load" to round(everydayLoad, 1),
        ),
    )
    upsert(day, "hr_zone_minutes", zoneMin.sum().toDouble(), json("zone_min" to zoneMin))
}

// --- Recovery ------------------------------------------------------------------------------

private val RECOVERY_WEIGHTS = mapOf("hrv" to 0.42, "rhr" to 0.28, "sleep" to 0.2, "rr" to 0.1)

/** Median and robust SD (floored at 0.5) of the 42 days before [day]; null under 5 points. */
private fun Science.recoveryBaseline(metric: String, day: LocalDate): Pair<Double, Double>? {
    val vals = store.range(metric, day.minusDays(42), day.minusDays(1)).map { it.value }
    if (vals.size < 5) return null
    return Robust.median(vals) to Robust.robustSd(Robust.medianAbsDeviation(vals), 0.5)
}

private fun Science.personalFactor(day: LocalDate, factors: MutableMap<String, JsonObject>, key: String, metric: String, k: Double, higherBetter: Boolean) {
    val (med, sd) = recoveryBaseline(metric, day) ?: return
    val v = store.get(day, metric)?.value ?: return
    val z = (v - med) / sd
    val sub = if (higherBetter) clamp100(50 + k * z) else clamp100(50 - k * z)
    factors[key] = json("sub" to roundInt(sub), "z" to round(z, 2), "value" to round(v, 1), "baseline" to round(med, 1))
}

private fun Science.sleepFactor(day: LocalDate, factors: MutableMap<String, JsonObject>) {
    val tst = store.get(day, "sleep_health_score_4dim")?.flags?.get("tst_min")?.jsonPrimitive?.doubleOrNull
    val need = store.range("sleep_need_min", LocalDate.MIN, day).lastOrNull()?.value
    if (need == null || need == 0.0) return
    if (tst != null) factors["sleep"] = json("sub" to roundInt(clamp100(100.0 * tst / need)), "tst_min" to roundInt(tst), "need_min" to roundInt(need))
}

internal fun Science.recovery(day: LocalDate) {
    val factors = LinkedHashMap<String, JsonObject>()
    personalFactor(day, factors, "hrv", "hrv_sleep_avg", 20.0, higherBetter = true)
    personalFactor(day, factors, "rhr", "rhr_daily", 20.0, higherBetter = false)
    personalFactor(day, factors, "rr", "respiratory_rate_sleep", 15.0, higherBetter = false)
    sleepFactor(day, factors)
    if ("hrv" !in factors && "rhr" !in factors) return
    val tw = factors.keys.sumOf { RECOVERY_WEIGHTS.getValue(it) }
    val score = factors.entries.sumOf { (k, f) -> RECOVERY_WEIGHTS.getValue(k) * f.getValue("sub").jsonPrimitive.content.toDouble() } / tw
    upsert(
        day, "recovery_score", roundInt(score),
        json(
            "factors" to factors, "weights" to factors.keys.associateWith { RECOVERY_WEIGHTS.getValue(it) },
            "method" to "evidence_weighted_personal_baseline", "note_id" to "recovery_readiness",
        ),
    )
}

/** The HRV part of a day's recovery score, if it had one (Energy reads it). */
internal fun Derived.hrvSub(): Double? = flags["factors"]?.jsonObject?.get("hrv")?.jsonObject?.get("sub")?.jsonPrimitive?.doubleOrNull
