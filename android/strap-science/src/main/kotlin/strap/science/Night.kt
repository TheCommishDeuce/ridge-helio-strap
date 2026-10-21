package strap.science

import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// Per-night metrics (spec/02 §1) and the sleep-need chain (§2.6). Every one is filed on the
// session's local wake date.

internal object Sleep {
    const val DURATION_MIN_H = 7.0
    const val DURATION_MAX_H = 9.0
    const val EFFICIENCY_MIN = 0.85
    const val TIMING_FROM = 2
    const val TIMING_TO = 4
    const val SRI_GOOD = 70.0
    const val SRI_DAYS = 7
    const val NEED_18_64 = 480
    const val NEED_65P = 450
    const val DEBT_WINDOW = 14
    const val RECOVERY_CREDIT = 0.5
    const val AWAKE = 7
    const val SRI_WINDOW_TOO_SHORT = "sri_window_under_7_nights"
}

/** Lowest 5-minute mean HR (buckets with ≥ 3 valid readings) in the window, and the readings used. */
internal fun Science.rhr(start: Instant, end: Instant): Pair<Double, Int>? {
    val buckets = LinkedHashMap<Long, MutableList<Double>>()
    for (s in data.samples("hr", start, end)) {
        if (HrValidity.valid(s.value)) buckets.getOrPut(Math.floorDiv(s.ts.epochSecond, 300L)) { ArrayList() } += s.value
    }
    val kept = buckets.values.filter { it.size >= 3 }
    if (kept.isEmpty()) return null
    return kept.minOf { it.sum() / it.size } to kept.sumOf { it.size }
}

internal fun Science.nightVitals(day: LocalDate, start: Instant, end: Instant) {
    windowStat("hrv", start, end, 5.0, 200.0)?.let { upsert(day, "hrv_sleep_avg", it) }
    windowStat("spo2", start, end, 70.0, 100.0)?.let { spo2 ->
        upsert(day, "spo2_overnight", spo2)
        windowStat("spo2", start, end, 70.0, 100.0, Science.Stat.MIN)?.let { upsert(day, "spo2_overnight_min", it) }
    }
    windowStat("respiratory_rate", start, end, 4.0, 40.0)?.let { upsert(day, "respiratory_rate_sleep", it) }
}

internal fun sleepEfficiency(tst: Int, wake: Int): Double {
    val total = tst + wake
    return if (total > 0) minOf(1.0, tst.toDouble() / total) else 0.0
}

/** Local minute-of-day sets of time asleep, per day of the 7-night window ending on [nightDate]. */
internal fun Science.sriGrid(nightDate: LocalDate): Map<Int, Set<Int>> {
    val first = nightDate.minusDays((Sleep.SRI_DAYS - 1).toLong())
    val from = zones.start(first)
    val until = zones.bounds(nightDate).second
    val grid = HashMap<Int, MutableSet<Int>>()
    for (session in data.sleepSessions(from, until)) {
        if (!session.isMain) continue
        for (st in session.stages) {
            if (st.code == Sleep.AWAKE) continue
            val end = Instant.ofEpochMilli(st.endMs)
            var minute = Instant.ofEpochMilli(st.startMs)
            while (minute < end) {
                val local = minute.atZone(Zones.id(zones.zoneAt(minute)))
                val index = days(first, local.toLocalDate()).toInt()
                if (index in 0 until Sleep.SRI_DAYS) grid.getOrPut(index) { HashSet() } += local.hour * 60 + local.minute
                minute = minute.plusSeconds(60)
            }
        }
    }
    return grid
}

/** Phillips 2017 over the 7 nights ending on [nightDate]; null without all seven. */
internal fun Science.sri(nightDate: LocalDate): Double? {
    val grid = sriGrid(nightDate)
    if (grid.size < Sleep.SRI_DAYS) return null
    var matches = 0
    for (j in 0 until Sleep.SRI_DAYS - 1) {
        val a = grid.getValue(j)
        val b = grid.getValue(j + 1)
        val symmetric = a.count { it !in b } + b.count { it !in a }
        matches += 1440 - symmetric
    }
    return round(-100.0 + 200.0 / (1440 * (Sleep.SRI_DAYS - 1)) * matches, 2)
}

internal fun Science.sriWithholdReason(day: LocalDate): String? =
    if (sriGrid(day).size < Sleep.SRI_DAYS) Sleep.SRI_WINDOW_TOO_SHORT else null

/** The 4-dimension sleep-health score: duration, efficiency, timing, regularity (never one validated score). */
internal fun Science.sleepScore(session: SleepSession, rem: Int, light: Int, deep: Int, wake: Int, nightDate: LocalDate) {
    val tst = light + deep + rem
    val tib = maxOf(1L, Duration.between(session.start, session.end).seconds / 60).toInt()
    val pDur = if (tst / 60.0 in Sleep.DURATION_MIN_H..Sleep.DURATION_MAX_H) 1 else 0
    val eff = sleepEfficiency(tst, wake)
    val pEff = if (tst > 0 && eff >= Sleep.EFFICIENCY_MIN) 1 else 0
    val midpoint = session.start.plus(Duration.between(session.start, session.end).dividedBy(2))
    val mid = midpoint.atZone(Zones.id(zones.zoneAt(midpoint)))
    val pTim = if (mid.hour >= Sleep.TIMING_FROM && mid.hour < Sleep.TIMING_TO) 1 else 0
    val sri = sri(nightDate)
    val pReg = if (sri != null && sri >= Sleep.SRI_GOOD) 1 else 0
    val score = pDur + pEff + pTim + pReg
    val flags = json(
        "duration" to pDur, "efficiency" to pEff, "timing" to pTim, "regularity" to pReg,
        "tst_min" to tst, "tib_min" to tib, "efficiency_pct" to round(eff * 100, 1),
        "midpoint_local" to isoformat(mid), "midpoint_hr" to mid.hour, "sri" to sri,
        "session_source" to session.source,
    )
    upsert(nightDate, "sleep_health_score_4dim", score, flags)
    upsert(nightDate, "sleep_dim_duration", pDur, flags)
    upsert(nightDate, "sleep_dim_efficiency", pEff, flags)
    upsert(nightDate, "sleep_dim_timing", pTim, flags)
    upsert(nightDate, "sleep_dim_regularity", pReg, flags)
    if (sri != null) upsert(nightDate, "sleep_regularity_index", sri, flags)
}

/** Total sleep times of the scored nights in the 14 days ending on [day], oldest first. */
internal fun Science.tstWindow(day: LocalDate): List<Double> =
    store.range("sleep_health_score_4dim", day.minusDays((Sleep.DEBT_WINDOW - 1).toLong()), day)
        .mapNotNull { it.flags["tst_min"]?.jsonPrimitive?.doubleOrNull }

internal fun Science.sleepDebtWithholdReason(day: LocalDate): String? = when {
    dateOfBirth() == null -> Freshness.DOB_MISSING
    tstWindow(day).isEmpty() -> Freshness.NO_NIGHTS_IN_WINDOW
    else -> null
}

/** NSF 2015 need by age; debt = shortfall − ½ surplus over the last 14 recorded nights. */
internal fun Science.sleepDebt(day: LocalDate) {
    val dob = dateOfBirth() ?: return
    val years = age(dob, day)
    val need = if (years >= 65) Sleep.NEED_65P else Sleep.NEED_18_64
    val tsts = tstWindow(day)
    if (tsts.isEmpty()) return
    val shortfall = tsts.sumOf { maxOf(0.0, need - it) }
    val surplus = tsts.sumOf { maxOf(0.0, it - need) }
    val avgTst = tsts.sum() / tsts.size
    val debt = maxOf(0.0, shortfall - Sleep.RECOVERY_CREDIT * surplus)
    val avgDeficit = maxOf(0.0, need - avgTst)
    upsert(day, "sleep_need_min", need.toDouble(), json("basis" to "NSF2015", "age" to years))
    upsert(
        day, "sleep_debt_min", round(debt, 0),
        json(
            "window_nights" to Sleep.DEBT_WINDOW, "nights" to tsts.size, "avg_tst_min" to roundInt(avgTst),
            "avg_deficit_min" to roundInt(avgDeficit), "nights_below" to tsts.count { it < need },
            "recovery_credit" to Sleep.RECOVERY_CREDIT,
        ),
    )
}
