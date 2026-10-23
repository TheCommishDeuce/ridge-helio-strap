package strap.science

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// The day summary (the server's `GET /v1/day/{day}/summary`): every card is a value with its
// baseline, or withheld with a named reason (Model.kt).

private const val BASELINE_DAYS = 30L
private const val BASELINE_MIN_POINTS = 5
private const val ILLNESS_ACTIVE_DAYS = 2L

// One text per reason; a card with its own "not computed yet" passes it (the server merged them
// into one map, so every card said "sleep debt", fixed with D34 S3).
private val MESSAGES = mapOf(
    Freshness.NOT_DERIVED_YET to Freshness.NOT_DERIVED_YET_MESSAGE,
    "no_data" to "The strap recorded nothing for this yet.",
    Freshness.PROFILE_INCOMPLETE to "We need your height, sex and date of birth, plus at least one logged weight, before this estimate can be computed.",
    Vo2max.FEW_RHR_DAYS to "Fewer than 3 nights of resting heart rate in the last week — wear the strap overnight for a few more nights and this comes back.",
    Vo2max.RHR_OUT_OF_RANGE to "Your 7-day resting-HR median is outside the 40-100 bpm range this model was validated on, so any number here would be a guess.",
    Vo2max.RHR_TOO_NOISY to "Your resting heart rate moved too much this week for the estimate to mean anything (7-day spread above 8 bpm). A few steadier nights will restore it.",
    Vo2max.SRPA_NOT_REPORTED to "This estimate needs one thing we cannot read off the strap: how much deliberate aerobic exercise you actually do in a typical week. Step counts cannot tell training apart from getting around, and guessing moves this number by years. Answer the activity-level question in your profile and the estimate returns.",
    Freshness.WEIGHT_STALE to "This estimate runs on your BMI, and the weight behind it is more than 14 days old — old enough that we'd be guessing at your mass, so we won't guess at your fitness either. Log a weight and it returns.",
    Sleep.SRI_WINDOW_TOO_SHORT to "Sleep regularity needs seven consecutive nights of recorded sleep — wear the strap overnight until the week is complete and this comes back.",
    Freshness.DOB_MISSING to "We need your date of birth to set your age-based sleep need before a debt can be tracked.",
    Freshness.NO_NIGHTS_IN_WINDOW to "No sleep has been recorded in the last two weeks, so there is no window to compute a debt over — wear the strap overnight and this comes back.",
    "odi_not_measured" to "The strap did not measure blood oxygen through this night.",
)

internal fun withheld(reason: String, own: Map<String, String> = emptyMap()): Card.Withheld =
    Card.Withheld(reason, own[reason] ?: MESSAGES[reason] ?: reason)

private val VO2MAX_NOT_YET = mapOf(Freshness.NOT_DERIVED_YET to "Today's estimate has not been computed yet — sync the strap.")
private val SRI_NOT_YET = mapOf(Freshness.NOT_DERIVED_YET to "Last night's regularity has not been computed yet — sync the strap.")
private val DEBT_NOT_YET = mapOf(Freshness.NOT_DERIVED_YET to "Today's sleep debt has not been computed yet — sync the strap.")

/** 0–21 from the day's load against the owner's 90-day 95th percentile. */
internal fun strainFromLoad(load: Double, p95: Double?): Double? {
    if (p95 == null || p95 <= 0) return null
    return round(maxOf(0.0, minOf(21.0, 21.0 * Math.pow(load / p95, 0.75))), 1)
}

private fun Science.history(day: LocalDate, metric: String, days: Long): List<Double> =
    store.range(metric, day.minusDays(days), day.minusDays(1)).map { it.value }

private fun Science.baseline(day: LocalDate, metric: String, value: Double): Baseline? {
    val hist = history(day, metric, BASELINE_DAYS)
    if (hist.size < BASELINE_MIN_POINTS) return null
    val med = Robust.median(hist)
    val sd = Robust.medianAbsDeviation(hist) * Robust.MAD_TO_SD
    return Baseline(round(med, 1), hist.size, if (sd > 0) round((value - med) / sd, 2) else null)
}

private fun Science.metricCard(day: LocalDate, metric: String, digits: Int = 0): Card<DailyNumber> {
    val row = store.get(day, metric) ?: return withheld(Freshness.NOT_DERIVED_YET)
    val value = if (digits != 0) round(row.value, digits) else roundInt(row.value).toDouble()
    return Card.Shown(DailyNumber(value, row.flags, baseline(day, metric, row.value)))
}

/** Samples of a read series (the server's `SERIES` filters). */
internal fun Science.filtered(metric: String, start: Instant, end: Instant, keep: (Double) -> Boolean): List<Sample> =
    data.samples(metric, start, end).filter { keep(it.value) }

/** Min, max, mean and the times of the extremes (the first one on a tie). */
private fun rawStats(rows: List<Sample>, usual: () -> Usual?): Card<DayStats> {
    if (rows.isEmpty()) return withheld("no_data")
    val hi = rows.maxOf { it.value }
    val lo = rows.minOf { it.value }
    val max = rows.first { it.value == hi }
    val min = rows.first { it.value == lo }
    return Card.Shown(DayStats(min.value, max.value, round(rows.sumOf { it.value } / rows.size, 1), rows.size, max.ts, min.ts, usual()))
}

/** Each of the 30 days before [day]: its start, and where its comparison window ends. */
private fun Science.baselineDays(day: LocalDate, until: LocalTime?): Pair<List<Instant>, List<Instant>> {
    val first = day.minusDays(BASELINE_DAYS)
    val edges = zones.starts(first, day.minusDays(1))
    val cutoffs = (0 until BASELINE_DAYS.toInt()).map { i ->
        val d = first.plusDays(i.toLong())
        var cut = edges[i + 1]
        if (until != null) cut = minOf(cut, maxOf(edges[i], Zones.wall(d, until, zones.zoneOf(d))))
        cut
    }
    return edges to cutoffs
}

/** The median of each earlier day's mean up to the same clock time (D28). */
internal fun Science.usualMean(day: LocalDate, metric: String, until: LocalTime?, keep: (Double) -> Boolean): Usual? {
    val (edges, cutoffs) = baselineDays(day, until)
    val means = cutoffs.indices.mapNotNull { i ->
        val rows = filtered(metric, edges[i], minOf(cutoffs[i], edges[i + 1]), keep)
        if (rows.isEmpty()) null else rows.sumOf { it.value } / rows.size
    }
    if (means.size < BASELINE_MIN_POINTS) return null
    return Usual(round(Robust.median(means), 1), means.size, until)
}

/** Each earlier day's step count times the share of its per-minute steps taken by [until] (D28). */
internal fun Science.stepsUsualBy(day: LocalDate, until: LocalTime): Usual? {
    val (edges, cutoffs) = baselineDays(day, until)
    val first = day.minusDays(BASELINE_DAYS)
    val shares = HashMap<LocalDate, Double>()
    for (i in cutoffs.indices) {
        val rows = filtered("steps_per_minute", edges[i], edges[i + 1]) { it > 0 && it < 250 }
        val total = rows.sumOf { it.value }
        if (rows.isEmpty() || total == 0.0) continue
        shares[first.plusDays(i.toLong())] = rows.filter { it.ts < cutoffs[i] }.sumOf { it.value } / total
    }
    val usual = store.range("steps_total", first, day.minusDays(1)).mapNotNull { r -> shares[r.day]?.let { r.value * it } }
    if (usual.size < BASELINE_MIN_POINTS) return null
    return Usual(roundInt(Robust.median(usual)).toDouble(), usual.size, until)
}

private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun Science.recoveryCard(day: LocalDate): Card<Recovery> {
    val row = store.get(day, "recovery_score") ?: return withheld(Freshness.NOT_DERIVED_YET)
    val factors = row.flags.obj("factors")
    val weights = row.flags.obj("weights")
    // As Load.kt's recovery writes them, in its order.
    val parts = listOf("hrv", "rhr", "rr", "sleep").mapNotNull { key ->
        val f = factors?.obj(key) ?: return@mapNotNull null
        val weight = weights?.num(key) ?: 0.0
        if (key == "sleep") RecoveryFactor(key, f.int("sub")!!, weight, f.num("tst_min")!!, needMin = f.num("need_min"))
        else RecoveryFactor(key, f.int("sub")!!, weight, f.num("value")!!, f.num("baseline"), f.num("z"))
    }
    return Card.Shown(Recovery(row.value.toInt(), row.flags, parts))
}

private fun Science.strainCard(day: LocalDate): Card<Strain> {
    val row = store.get(day, "cardio_load") ?: return withheld(Freshness.NOT_DERIVED_YET)
    val p95 = Robust.percentileCont(store.range("cardio_load", day.minusDays(90), day).map { it.value }.filter { it > 0 }, 0.95)
    val hist = history(day, "cardio_load", BASELINE_DAYS)
    return Card.Shown(Strain(
        strainFromLoad(row.value, p95?.takeIf { it != 0.0 }), 21.0, round(row.value, 1),
        if (hist.size >= BASELINE_MIN_POINTS) round(Robust.median(hist), 1) else null, row.flags,
        (row.flags["zone_min"] as? JsonArray)?.map { it.jsonPrimitive.intOrNull ?: 0 },
        row.flags.int("hrmax"), row.flags.int("rhr"),
    ))
}

private fun Science.lastDay(day: LocalDate, metric: String): LocalDate? = store.range(metric, LocalDate.MIN, day).lastOrNull()?.day

private fun Science.sleepCard(day: LocalDate): SleepCard {
    val (start, end) = dayBounds(day)
    val nights = data.sleepSessions(start, end).filter { it.end >= start && it.end < end }.map { s ->
        Night(s.start, s.end, s.kind, s.remMin, s.lightMin, s.deepMin, s.wakeMin, s.stages, s.score?.takeIf { it != 0 })
    }
    val score = store.get(day, "sleep_health_score_4dim")
    val debt = store.get(day, "sleep_debt_min")
    val need = store.get(day, "sleep_need_min")
    val sri = store.get(day, "sleep_regularity_index")
    val lastSri = lastDay(day, "sleep_regularity_index")
    return SleepCard(
        nights,
        if (score != null) Card.Shown(score.flags.let { f ->
            SleepHealth(score.value.toInt(), f.int("duration")!!, f.int("efficiency")!!, f.int("timing")!!, f.int("regularity")!!,
                f.num("tst_min")!!, f.num("efficiency_pct")!!, f.getValue("midpoint_local").jsonPrimitive.content, f)
        }) else withheld(Freshness.NOT_DERIVED_YET),
        need?.value,
        if (debt != null) Card.Shown(debt.flags.let { f ->
            SleepDebt(debt.value, f.int("window_nights")!!, f.int("nights")!!, f.num("avg_tst_min")!!, f.int("nights_below")!!, f)
        }) else withheld(Freshness.unavailableReason(day, null) { sleepDebtWithholdReason(day) } ?: Freshness.NOT_DERIVED_YET, DEBT_NOT_YET),
        if (sri != null) Card.Shown(sri.value)
        else withheld(Freshness.unavailableReason(day, lastSri) { sriWithholdReason(day) } ?: Freshness.NOT_DERIVED_YET, SRI_NOT_YET),
        breathingCard(day),
    )
}

/** The strap's nightly breathing summaries stamped in `[start, end)`, by stamp. */
private fun Science.odiNights(start: Instant, end: Instant): List<Map<String, Double>> {
    val nights = sortedMapOf<Instant, MutableMap<String, Double>>()
    for (metric in listOf("odi_events_device", "odi_valid_time_device")) {
        for (s in data.samples(metric, start, end)) nights.getOrPut(s.ts) { HashMap() }[metric] = s.value
    }
    return nights.values.toList()
}

private fun dropsPerHour(night: Map<String, Double>): Double? {
    val seconds = night["odi_valid_time_device"] ?: 0.0
    val events = night["odi_events_device"]
    return if (seconds > 0 && events != null) events * 3600.0 / seconds else null
}

/** Blood-oxygen drops per hour measured, against the usual (spec/02 §2.13). */
private fun Science.breathingCard(day: LocalDate): Card<Breathing> {
    val (start, end) = dayBounds(day)
    val night = odiNights(start, end).firstOrNull() ?: return withheld("no_data")
    val perHour = dropsPerHour(night) ?: return withheld("odi_not_measured")
    val past = odiNights(dayBounds(day.minusDays(BASELINE_DAYS)).first, start).mapNotNull(::dropsPerHour)
    return Card.Shown(Breathing(
        round(perHour, 1), roundInt(night.getValue("odi_events_device")).toInt(), round(night.getValue("odi_valid_time_device") / 3600.0, 1),
        if (past.size >= BASELINE_MIN_POINTS) Usual(round(Robust.median(past), 1), past.size, null) else null,
    ))
}

/** The strap's own PAI: its 7-day total and the week's daily points (spec/02 §2.13). */
private fun Science.paiCard(day: LocalDate): Card<Pai> {
    val first = day.minusDays(6)
    val edges = zones.starts(first, day)
    val byDay = sortedMapOf<LocalDate, MutableMap<String, Double>>()
    for (metric in listOf("pai_daily_device", "pai_total_device")) {
        for (s in data.samples(metric, edges.first(), edges.last())) {
            val d = first.plusDays((bisectRight(edges, s.ts) - 1).toLong())
            byDay.getOrPut(d) { HashMap() }[metric] = s.value // oldest first, so the latest stays
        }
    }
    val today = byDay[day]
    if (today == null || "pai_total_device" !in today) return withheld("no_data")
    return Card.Shown(Pai(
        roundInt(today.getValue("pai_total_device")).toInt(), roundInt(today["pai_daily_device"] ?: 0.0).toInt(),
        byDay.filterValues { "pai_daily_device" in it }.mapValues { (_, v) -> roundInt(v.getValue("pai_daily_device")).toInt() }, 100,
    ))
}

private fun Science.vo2maxCard(day: LocalDate): Card<DailyNumber> {
    val reason = Freshness.unavailableReason(day, lastDay(day, "vo2max_estimate")) { vo2maxWithholdReason(day) }
    if (reason != null) return withheld(reason, VO2MAX_NOT_YET)
    return metricCard(day, "vo2max_estimate", digits = 1)
}

private fun fixed(x: Double, digits: Int): String = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

/** "+0.3" or "−0.2": a limb's change keeps its own sign (a limb under its baseline used to print "+-0.2"). */
private fun signedFixed(x: Double, digits: Int): String = fixed(x, digits).let { if (it.startsWith("-")) "−" + it.drop(1) else "+$it" }

internal fun illnessFraming(rrDelta: Double?, tempDelta: Double?, sustained: Boolean): String {
    val parts = listOfNotNull(
        rrDelta?.let { "breathing rate ${signedFixed(it, 1)} bpm vs your 14-day baseline" },
        tempDelta?.let { "skin temperature ${signedFixed(it, 2)}°C" },
    )
    val joined = parts.joinToString("; ")
    val suffix = if (sustained) " — sustained across two nights, the Smarr 2020 / Quer 2021 pattern" else ""
    return "Possible early signal — consider lighter activity today. ${joined.take(1).uppercase() + joined.drop(1)}$suffix. Not a diagnosis."
}

private fun Science.illnessCard(day: LocalDate): IllnessCard? {
    val f = store.latestIllness(day.minusDays(ILLNESS_ACTIVE_DAYS), day) ?: return null
    val rr = f.rrDeltaBpm?.real()
    val temp = f.tempDeltaC?.real()
    return IllnessCard(f.date, f.severity, f.sustained, rr, temp, f.noteIds, illnessFraming(rr, temp, f.sustained))
}

/** Everything the day screens show for [day], as it stands at [now]. */
public fun Science.daySummary(day: LocalDate, now: Instant): DaySummary {
    val today = zones.dateOf(now)
    val localNow = now.atZone(Zones.id(zones.zoneOf(today)))
    val until = if (day == today) localNow.toLocalTime().truncatedTo(ChronoUnit.MINUTES) else null
    val (start, end) = zones.bounds(day)
    val steps = metricCard(day, "steps_total")
    return DaySummary(
        day, zones.zoneOf(day), start, end, until,
        recoveryCard(day),
        chargeCard(day, now),
        strainCard(day),
        sleepCard(day),
        Activity(
            steps, if (until != null && steps is Card.Shown) stepsUsualBy(day, until) else null,
            metricCard(day, "distance_m_daily"), metricCard(day, "active_calories"), metricCard(day, "total_calories"), metricCard(day, "mvpa_min"),
        ),
        Heart(
            metricCard(day, "rhr_daily"), metricCard(day, "hrv_sleep_avg", digits = 1),
            rawStats(filtered("hr", start, end, HrValidity::valid)) { usualMean(day, "hr", until, HrValidity::valid) },
        ),
        rawStats(filtered("stress", start, end) { true }) { usualMean(day, "stress", until) { true } },
        paiCard(day),
        vo2maxCard(day),
        illnessCard(day),
    )
}
