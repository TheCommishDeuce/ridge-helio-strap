package strap.science

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.ln

// Biological age (spec/02 §2.11, D36; the documented exception to "no composites"): how old your
// habits look. Chronological age plus the years resting heart rate, steps, sleep regularity,
// short sleep and strength training move all-cause mortality, each scored against the population
// median for your age and sex, after OpenStrap's healthspan age (MIT/Apache-2.0). An estimate for
// personal insight, shown as a range in whole years.

public object BioAge {
    /** PhenoAge's Gompertz age slope: ln(hazard ratio) / G = years. */
    internal const val G = 0.09165
    /** Shrinkage: the terms' hazard ratios weren't adjusted for each other. */
    internal const val S = 0.6
    internal const val CAP_YEARS = 5.0
    internal const val FITNESS_CAP_YEARS = 5.0
    internal const val MIN_BAND_YEARS = 2.0
    internal const val EARLY_BAND_YEARS = 4.0
    public const val WINDOW_DAYS: Long = 180
    public const val EARLY_DAYS: Long = 30
    /** A step day counts with this many waking minutes of heart rate (10 h worn). */
    public const val WORN_MINUTES: Int = 600
    /** Strap sport types for strength training and core training. */
    public val STRENGTH_SPORTS: Set<Int> = setOf(52, 50)
    /** Sleeping resting HR → seated: office HR runs about 10 bpm over the night's (Böhm 2020). */
    internal const val SEATED_OFFSET_BPM = 10.0

    // Resting HR: RR 1.09 (1.07–1.12) per 10 bpm from 45 (Zhang 2016); NHANES seated P50.
    private val RHR_SLOPE = ln(1.09) / 10 / G
    private val RHR_SLOPE_BAND = ln(1.07) / 10 / G to ln(1.12) / 10 / G
    private const val RHR_FLOOR = 45.0
    private const val RHR_LIMIT = 4.0
    private val RHR_REF_MALE = listOf(30.0 to 69.0, 50.0 to 68.0, 70.0 to 67.0)
    private val RHR_REF_FEMALE = listOf(30.0 to 74.0, 50.0 to 71.0, 70.0 to 70.0)

    // Steps: Paluch 2022 age-specific curves (HR vs 3,500 steps, read from Figure S2c).
    private val STEPS_YOUNG = listOf(3000.0 to 1.12, 4000.0 to 0.89, 5000.0 to 0.71, 6000.0 to 0.56, 7000.0 to 0.46, 8000.0 to 0.40, 9000.0 to 0.38, 10000.0 to 0.38)
    private val STEPS_OLD = listOf(
        3000.0 to 1.12, 4000.0 to 0.89, 5000.0 to 0.71, 6000.0 to 0.61, 7000.0 to 0.56, 8000.0 to 0.54,
        9000.0 to 0.52, 10000.0 to 0.50, 12000.0 to 0.47, 14000.0 to 0.44, 16000.0 to 0.41,
    )
    private const val STEPS_MEDIAN_YOUNG = 7803.0
    private const val STEPS_MEDIAN_OLD = 5649.0
    private const val STEPS_LIMIT_YOUNG = 1.5
    private const val STEPS_LIMIT_OLD = 2.0

    // Sleep regularity: Windred 2024 SRI quintiles (duration-adjusted), re-based to the middle one.
    private val SRI_CURVE = listOf(68.2 to 2.71, 75.1 to 0.68, 80.9 to 0.0, 85.2 to -0.43, 89.4 to -0.57)
    private const val SRI_LOW = -0.57
    private const val SRI_HIGH = 2.71

    // Short sleep only: Yuan 2024 device-measured sleep, re-based to 6–6.9 h; penalty only.
    private val SLEEP_CURVE = listOf(270.0 to 9.17, 330.0 to 2.12, 390.0 to 0.0)
    private const val SLEEP_LIMIT = 4.0

    // Strength: Momma 2022 dose–response (Figure 4), RR with its CI; credit only, 0 from 140 min.
    private val STRENGTH_RR = listOf(
        0.0 to 1.0, 10.0 to 0.94, 20.0 to 0.89, 30.0 to 0.85, 40.0 to 0.83, 50.0 to 0.83, 60.0 to 0.84, 70.0 to 0.845,
        80.0 to 0.85, 90.0 to 0.865, 100.0 to 0.89, 110.0 to 0.92, 120.0 to 0.95, 130.0 to 0.97, 140.0 to 1.0,
    )
    private val STRENGTH_CI_LOW = listOf(0.0 to 1.0) + listOf(0.926, 0.868, 0.820, 0.794, 0.799, 0.805, 0.810, 0.816, 0.829, 0.856, 0.879, 0.902, 0.926, 0.950)
        .mapIndexed { i, v -> 10.0 * (i + 1) to v }
    private val STRENGTH_CI_HIGH = listOf(0.0 to 1.0) + listOf(0.953, 0.914, 0.883, 0.864, 0.870, 0.875, 0.881, 0.886, 0.900, 0.927, 0.958, 0.994, 1.028, 1.062)
        .mapIndexed { i, v -> 10.0 * (i + 1) to v }
    private val STRENGTH_LOW = ln(0.83) / G
    private const val STRENGTH_TOP_MIN = 140.0

    // VO₂max (optional): RR 0.87 (0.84–0.90) per MET (Kodama 2009), half weight; FRIEND P50.
    private const val VO2_WEIGHT = 0.5
    private const val VO2_LIMIT = 2.0
    private val FRIEND_MALE = listOf(46.5, 39.7, 35.3, 29.2, 24.6, 20.6, 17.6)
    private val FRIEND_FEMALE = listOf(36.6, 28.3, 25.7, 22.9, 19.6, 17.2, 15.4)

    public enum class Term { RESTING_HR, STEPS, VO2MAX, REGULARITY, SHORT_SLEEP, STRENGTH }

    /**
     * What the estimate is worked from: [sleepingRhr] bpm (the median nightly low), [steps] a day,
     * [sri] the mean weekly SRI, [asleepMin] a main night, [strengthMinPerWeek], and an optional
     * exercise-based [vo2max] (none on Ridge: the strap's own estimate isn't decoded).
     */
    public data class Inputs(
        val age: Double,
        val sex: String,
        val sleepingRhr: Double,
        val steps: Double,
        val sri: Double,
        val asleepMin: Double,
        val strengthMinPerWeek: Double,
        val vo2max: Double? = null,
    )

    /** One term in years before shrinkage, with its band; [shown] is what it adds to the shown age. */
    public data class Part(val term: Term, val years: Double, val low: Double, val high: Double) {
        val shown: Double get() = S * years
    }

    /**
     * [age] = [chronological] + the shrunk, capped sum of [parts]; [low]–[high] is the range to show
     * (at least ±2 years, ±4 while [early]). [fitnessCapped]: resting HR, steps and VO₂max hit their
     * shared limit.
     */
    public data class Estimate(
        val chronological: Double,
        val age: Double,
        val low: Double,
        val high: Double,
        val parts: List<Part>,
        val fitnessCapped: Boolean,
        val early: Boolean,
        val inputs: Inputs,
    ) {
        public fun part(term: Term): Part? = parts.firstOrNull { it.term == term }
    }

    /** Joined linearly between the points, flat outside them. */
    private fun interp(points: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        val i = points.indexOfLast { it.first <= x }
        val (x0, y0) = points[i]
        val (x1, y1) = points[i + 1]
        return y0 + (x - x0) / (x1 - x0) * (y1 - y0)
    }

    private fun lnCurve(points: List<Pair<Double, Double>>) = points.map { (x, hr) -> x to ln(hr) / G }

    private fun ordered(a: Double, b: Double, lo: Double, hi: Double) = minOf(a, b).coerceIn(lo, hi) to maxOf(a, b).coerceIn(lo, hi)

    /** ±50 % of a term after its limits, clamped to them: the band where no contrast CI exists. */
    private fun halfBand(c: Double, lo: Double, hi: Double) = ordered(0.5 * c, 1.5 * c, lo, hi)

    internal fun rhrReference(age: Double, sex: String): Double = interp(if (sex == "female") RHR_REF_FEMALE else RHR_REF_MALE, age)

    internal fun vo2Reference(age: Double, sex: String): Double {
        val table = if (sex == "female") FRIEND_FEMALE else FRIEND_MALE
        return interp(table.mapIndexed { i, v -> 25.0 + 10 * i to v }, age)
    }

    private fun restingHr(i: Inputs): Part {
        val x = maxOf(i.sleepingRhr + SEATED_OFFSET_BPM, RHR_FLOOR) - rhrReference(i.age, i.sex)
        val c = (RHR_SLOPE * x).coerceIn(-RHR_LIMIT, RHR_LIMIT)
        val (lo, hi) = ordered(RHR_SLOPE_BAND.first * x, RHR_SLOPE_BAND.second * x, -RHR_LIMIT, RHR_LIMIT)
        return Part(Term.RESTING_HR, c, lo, hi)
    }

    private fun steps(i: Inputs): Part {
        val young = lnCurve(STEPS_YOUNG)
        val old = lnCurve(STEPS_OLD)
        val cYoung = (interp(young, i.steps) - interp(young, STEPS_MEDIAN_YOUNG)).coerceIn(-STEPS_LIMIT_YOUNG, STEPS_LIMIT_YOUNG)
        val cOld = (interp(old, i.steps) - interp(old, STEPS_MEDIAN_OLD)).coerceIn(-STEPS_LIMIT_OLD, STEPS_LIMIT_OLD)
        // Blended from 55 to 65, so the term doesn't jump on a 60th birthday.
        val w = ((i.age - 55) / 10).coerceIn(0.0, 1.0)
        val c = (1 - w) * cYoung + w * cOld
        val limit = (1 - w) * STEPS_LIMIT_YOUNG + w * STEPS_LIMIT_OLD
        val (lo, hi) = halfBand(c, -limit, limit)
        return Part(Term.STEPS, c, lo, hi)
    }

    private fun vo2max(i: Inputs): Part? {
        val v = i.vo2max ?: return null
        val mets = (v - vo2Reference(i.age, i.sex)) / 3.5
        fun years(rr: Double) = (VO2_WEIGHT * ln(rr) / G * mets).coerceIn(-VO2_LIMIT, VO2_LIMIT)
        val (lo, hi) = ordered(years(0.84), years(0.90), -VO2_LIMIT, VO2_LIMIT)
        return Part(Term.VO2MAX, years(0.87), lo, hi)
    }

    private fun regularity(i: Inputs): Part {
        val c = interp(SRI_CURVE, i.sri)
        val (lo, hi) = halfBand(c, SRI_LOW, SRI_HIGH)
        return Part(Term.REGULARITY, c, lo, hi)
    }

    private fun shortSleep(i: Inputs): Part {
        val c = interp(SLEEP_CURVE, i.asleepMin).coerceIn(0.0, SLEEP_LIMIT)
        val (lo, hi) = halfBand(c, 0.0, SLEEP_LIMIT)
        return Part(Term.SHORT_SLEEP, c, lo, hi)
    }

    private fun strength(i: Inputs): Part {
        val m = i.strengthMinPerWeek
        if (m <= 0 || m >= STRENGTH_TOP_MIN) return Part(Term.STRENGTH, 0.0, 0.0, 0.0)
        fun years(table: List<Pair<Double, Double>>) = ln(minOf(1.0, interp(table, m))) / G
        val c = interp(lnCurve(STRENGTH_RR), m).coerceIn(STRENGTH_LOW, 0.0)
        val (lo, hi) = ordered(years(STRENGTH_CI_LOW), years(STRENGTH_CI_HIGH), STRENGTH_LOW, 0.0)
        return Part(Term.STRENGTH, c, lo, hi)
    }

    public fun estimate(i: Inputs, early: Boolean = false): Estimate {
        val fitness = listOfNotNull(restingHr(i), steps(i), vo2max(i))
        val rest = listOf(regularity(i), shortSleep(i), strength(i))
        val fitSum = fitness.sumOf { it.years }
        val fit = fitSum.coerceIn(-FITNESS_CAP_YEARS, FITNESS_CAP_YEARS)
        val fitLow = fitness.sumOf { it.low }.coerceIn(-FITNESS_CAP_YEARS, FITNESS_CAP_YEARS)
        val fitHigh = fitness.sumOf { it.high }.coerceIn(-FITNESS_CAP_YEARS, FITNESS_CAP_YEARS)
        fun shown(sum: Double) = (S * sum).coerceIn(-CAP_YEARS, CAP_YEARS)
        val age = i.age + shown(fit + rest.sumOf { it.years })
        val band = if (early) EARLY_BAND_YEARS else MIN_BAND_YEARS
        val low = minOf(i.age + shown(fitLow + rest.sumOf { it.low }), age - band)
        val high = maxOf(i.age + shown(fitHigh + rest.sumOf { it.high }), age + band)
        return Estimate(i.age, age, low, high, fitness + rest, fit != fitSum, early, i)
    }

    internal val MESSAGES: Map<String, String> = mapOf(
        "bioage_needs_profile" to "Biological age needs your date of birth and sex in Settings → Profile.",
        "bioage_age_range" to "Biological age is worked out for ages 20 to 79.",
        "bioage_too_early" to "Biological age needs a month of wearing the strap, day and night.",
        "bioage_few_nights" to "Biological age needs more nights with a resting heart rate: wear the strap to sleep.",
        "bioage_few_step_days" to "Biological age needs more days worn for 10 hours or more.",
        "bioage_few_weeks" to "Biological age needs more weeks with a sleep-regularity score.",
        "bioage_few_sleeps" to "Biological age needs more recorded nights of sleep.",
    )

    private fun withheld(reason: String) = Card.Withheld(reason, MESSAGES.getValue(reason))

    /** A night's numbers for the estimate: minutes asleep and the SRI over the week it ends, when there was one. */
    public data class NightRow(val asleepMin: Double, val sri: Double?)

    /**
     * The estimate as of [asOf], from the [WINDOW_DAYS] complete days before it: nightly resting HR
     * ([rhr]), the day's steps where the strap was worn 10 h awake ([wornSteps]), each main night
     * ([nights]) and strength minutes per day ([strengthMin]). With under [WINDOW_DAYS] of data but at
     * least [EARLY_DAYS] it is "early": the rules scale to the days there are and the range widens.
     */
    public fun asOf(
        asOf: LocalDate,
        dob: LocalDate?,
        sex: String?,
        rhr: Map<LocalDate, Double>,
        wornSteps: Map<LocalDate, Double>,
        nights: Map<LocalDate, NightRow>,
        strengthMin: Map<LocalDate, Double>,
    ): Card<Estimate> {
        if (dob == null || (sex != "male" && sex != "female")) return withheld("bioage_needs_profile")
        val last = asOf.minusDays(1)
        val first = asOf.minusDays(WINDOW_DAYS)
        val age = java.time.temporal.ChronoUnit.DAYS.between(dob, asOf) / 365.2425
        if (age < 20 || age >= 80) return withheld("bioage_age_range")
        fun <V> inWindow(m: Map<LocalDate, V>) = m.filterKeys { it in first..last }
        val r = inWindow(rhr)
        val s = inWindow(wornSteps)
        val n = inWindow(nights)
        val since = (r.keys + s.keys + n.keys).minOrNull() ?: return withheld("bioage_too_early")
        val span = java.time.temporal.ChronoUnit.DAYS.between(since, asOf)
        if (span < EARLY_DAYS) return withheld("bioage_too_early")
        // Half the window's days for each daily input, 8 weekly SRIs in 180 days; scaled while early.
        fun need(full: Int) = ceil(full * span.toDouble() / WINDOW_DAYS).toInt()
        // One SRI a week: the latest in each 7-day block counted back from the window's end.
        val weekly = n.entries.filter { it.value.sri != null }.groupBy { java.time.temporal.ChronoUnit.DAYS.between(it.key, last) / 7 }
            .values.map { week -> week.maxBy { it.key }.value.sri!! }
        when {
            r.size < need(90) -> return withheld("bioage_few_nights")
            s.size < need(90) -> return withheld("bioage_few_step_days")
            weekly.size < need(8) -> return withheld("bioage_few_weeks")
            n.size < need(90) -> return withheld("bioage_few_sleeps")
        }
        val weeks = span / 7.0
        val inputs = Inputs(
            age, sex, Robust.median(r.values.toList()), s.values.average(), weekly.average(), n.values.map { it.asleepMin }.average(),
            inWindow(strengthMin).values.sum() / weeks,
        )
        return Card.Shown(estimate(inputs, early = span < WINDOW_DAYS))
    }
}
