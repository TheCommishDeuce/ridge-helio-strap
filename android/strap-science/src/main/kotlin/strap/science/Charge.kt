package strap.science

import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Charge 0–100, minute by minute (spec/02 §2.12, D33): sleep fills it; activity, stress over a
 * personal threshold and the waking day drain it. Read-time: computed when shown, never stored.
 * No study validates a body-battery formula; every constant is a design choice.
 */
public object Charge {
    internal const val START_VALUE = 50.0
    internal val WARMUP: Duration = Duration.ofHours(48)
    internal const val LIVING = 0.04
    internal const val SLEEP_RATE = 0.006
    internal val STAGE_WEIGHT = mapOf(5 to 1.0, 4 to 0.8, 8 to 0.65, 7 to 0.1)
    internal const val GAP_STAGE = 128
    internal const val EXERCISE_HRR = 0.3
    internal const val ACTIVITY_HRR = 0.2
    internal const val ACTIVITY_STEPS = 60
    internal const val TRIMP_DRAIN = 0.32
    internal const val AFTERBURN_DECAY = 0.966
    internal const val AFTERBURN_MIN = 5.0
    internal const val STRESS_RUN = 3
    internal const val STRESS_DRAIN = 0.007
    internal const val STRESS_MAX_AGE_MIN = 10L
    internal const val RELAX_STRESS = 25
    internal const val RELAX_STEPS = 10
    internal const val RELAX_GAIN = 0.25 * SLEEP_RATE * 0.8
    internal const val DEFAULT_THRESHOLD = 60.0
    internal const val THRESHOLD_DAYS = 14L
    internal const val THRESHOLD_MIN_DAYS = 3
    internal const val THRESHOLD_MIN_READINGS = 60
    internal const val CURVE_STEP_MIN = 10L

    internal val MESSAGES = mapOf(
        "charge_needs_profile" to "Charge needs your date of birth and a measured resting heart rate.",
        "charge_no_sleep" to "Charge starts after the strap has recorded a sleep.",
        "charge_no_data" to "The strap recorded nothing this day.",
    )

    internal class Minute(
        val hr: Double? = null,
        val stress: Double? = null,
        val steps: Double = 0.0,
        val stage: Int? = null,
        val nightRhr: Double? = null,
        val hrvFactor: Double = 1.0,
    )

    internal class Run {
        val values = ArrayList<Double>()
        val states = ArrayList<String>()
        val recharged = ArrayList<Double>()
        val drained = ArrayList<Map<String, Double>>()
        val slept = ArrayList<Boolean>()
    }

    internal fun hrvFactor(hrvSub: Double?): Double = if (hrvSub == null) 1.0 else maxOf(0.6, minOf(1.1, 0.75 + 0.25 * hrvSub / 50.0))

    /** Median of calm awake stress readings + 10, kept in 50–70; Zepp's "medium" (60) without enough. */
    internal fun personalThreshold(calmByDay: Map<LocalDate, List<Double>>): Pair<Double, Boolean> {
        val readings = calmByDay.values.flatten()
        if (calmByDay.values.count { it.isNotEmpty() } < THRESHOLD_MIN_DAYS || readings.size < THRESHOLD_MIN_READINGS) return DEFAULT_THRESHOLD to false
        return maxOf(50.0, minOf(70.0, Robust.median(readings) + 10.0)) to true
    }

    internal fun simulate(minutes: List<Minute>, rhr: Double, hrmax: Double, sex: String?, threshold: Double): Run {
        val run = Run()
        var charge = START_VALUE
        var afterburn = 0.0
        var stressRun = 0
        var prevHr: Double? = null
        var slept = false
        for (m in minutes) {
            var drain: Map<String, Double> = emptyMap()
            var gain = 0.0
            var exercising = false
            val state: String
            if (m.stage != null) {
                slept = true
                stressRun = 0
                val relax = if (m.hr != null && m.nightRhr != null) maxOf(0.5, minOf(1.2, 1.2 - 0.04 * (m.hr - m.nightRhr))) else 1.0
                gain = SLEEP_RATE * (STAGE_WEIGHT[m.stage] ?: 0.8) * relax * m.hrvFactor * (100.0 - charge)
                state = "sleep"
            } else if (m.hr == null) {
                stressRun = 0
                state = "no_data"
            } else {
                val hrr = Trimp.hrReserveFraction(m.hr, rhr, hrmax)
                val load = Trimp.minute(m.hr, rhr, hrmax, sex)
                val stressed = m.stress != null && m.stress >= threshold
                stressRun = if (stressed) stressRun + 1 else 0
                val ph = prevHr
                if (hrr >= EXERCISE_HRR) {
                    state = "exercise"
                    exercising = true
                    afterburn += load
                    drain = linkedMapOf("living" to LIVING, "activity" to TRIMP_DRAIN * load)
                } else if (afterburn > AFTERBURN_MIN && ph != null && m.hr <= ph + 2) {
                    state = "exercise_recovery"
                    drain = linkedMapOf("living" to LIVING)
                } else if (hrr >= ACTIVITY_HRR || m.steps >= ACTIVITY_STEPS) {
                    state = "activity"
                    drain = linkedMapOf("living" to LIVING, "activity" to TRIMP_DRAIN * load)
                } else if (stressRun >= STRESS_RUN) {
                    state = "stress"
                    drain = linkedMapOf("living" to LIVING, "stress" to STRESS_DRAIN * (m.stress!! - threshold))
                } else if (m.stress != null && m.stress < RELAX_STRESS && m.steps < RELAX_STEPS) {
                    state = "relax"
                    drain = linkedMapOf("living" to LIVING)
                    gain = RELAX_GAIN * (100.0 - charge)
                } else {
                    state = "rest"
                    drain = linkedMapOf("living" to LIVING)
                }
            }
            if (!exercising) afterburn *= AFTERBURN_DECAY
            if (m.hr != null) prevHr = m.hr
            val before = charge
            var totalDrain = 0.0
            for (v in drain.values) totalDrain += v
            charge = maxOf(0.0, minOf(100.0, charge + gain - totalDrain))
            val delta = charge - before
            run.values += charge
            run.states += state
            run.recharged += maxOf(delta, 0.0)
            run.drained += if (delta < 0 && totalDrain > 0) drain.mapValues { (_, v) -> -delta * v / totalDrain } else emptyMap()
            run.slept += slept
        }
        return run
    }

    internal fun withheld(reason: String): Card.Withheld = Card.Withheld(reason, MESSAGES.getValue(reason))
}

/** Stage minutes from sessions overlapping `(start, end)`: minute → (stage, the session's end). */
private fun Science.asleepMinutes(start: Instant, end: Instant): Map<Instant, Pair<Int, Instant>> {
    val out = HashMap<Instant, Pair<Int, Instant>>()
    for (session in data.sleepSessions(start, end)) {
        if (session.end <= start) continue
        for (st in session.stages) {
            if (st.code == Charge.GAP_STAGE) continue
            val e = Instant.ofEpochMilli(st.endMs)
            var m = Instant.ofEpochMilli(st.startMs).floorMinute()
            while (m < e) {
                out[m] = st.code to session.end
                m = m.plusSeconds(60)
            }
        }
    }
    return out
}

/** Stress readings of the 14 days before [end] taken calm: awake, still, HR reserve under 20 %. */
private fun Science.calmReadings(end: Instant, rhr: Double, hrmax: Double): Map<LocalDate, List<Double>> {
    val start = end.minus(Duration.ofDays(Charge.THRESHOLD_DAYS))
    val hr = hrPerMinute(start, end)
    val steps = perMinute("steps_per_minute", start, end, keep = { true }, sum = true)
    val stress = perMinute("stress", start, end, keep = { true }, sum = false)
    val asleep = asleepMinutes(start, end)
    val out = LinkedHashMap<LocalDate, MutableList<Double>>()
    for ((m, v) in stress) {
        val h = hr[m]
        if (m in asleep || h == null || (steps[m] ?: 0.0) >= Charge.RELAX_STEPS || Trimp.hrReserveFraction(h, rhr, hrmax) >= Charge.ACTIVITY_HRR) continue
        out.getOrPut(zones.dateOf(m)) { ArrayList() } += v
    }
    return out
}

/** The Charge card for [day] as it stands at [now]. */
public fun Science.chargeCard(day: LocalDate, now: Instant): Card<ChargeCard> {
    val (dayStart, dayEnd) = zones.bounds(day)
    val end = minOf(dayEnd, now).floorMinute()
    if (end <= dayStart) return Charge.withheld("charge_no_data")
    val dob = dateOfBirth()
    val rhr = measuredRhr(day)
    if (dob == null || rhr == null) return Charge.withheld("charge_needs_profile")
    val hrmax = hrMax(age(dob, day))
    if (hrmax - rhr < 1) return Charge.withheld("charge_needs_profile")
    val sex = data.profile()?.sex
    val start = dayStart.minus(Charge.WARMUP)
    val hr = hrPerMinute(start, end)
    val steps = perMinute("steps_per_minute", start, end, keep = { it > 0 && it < 250 }, sum = true)
    val stressPoints = perMinute("stress", start.minusSeconds(60 * Charge.STRESS_MAX_AGE_MIN), end, keep = { true }, sum = false).entries.sortedBy { it.key }
    val asleep = asleepMinutes(start, end)
    val firstNight = zones.dateOf(start)
    val nightRhr = store.range("rhr_daily", firstNight, day).associate { it.day to it.value }
    val hrvSub = store.range("recovery_score", firstNight, day).mapNotNull { r -> r.hrvSub()?.let { r.day to it } }.toMap()
    val (threshold, personal) = Charge.personalThreshold(calmReadings(dayStart, rhr, hrmax))

    val minutes = ArrayList<Charge.Minute>()
    val stamps = ArrayList<Instant>()
    var si = 0
    var lastStress: Map.Entry<Instant, Double>? = null
    var m = start
    while (m < end) {
        while (si < stressPoints.size && stressPoints[si].key <= m) lastStress = stressPoints[si++]
        val ls = lastStress
        val stress = if (ls != null && Duration.between(ls.key, m) <= Duration.ofMinutes(Charge.STRESS_MAX_AGE_MIN)) ls.value else null
        val sleep = asleep[m]
        val wake = sleep?.second?.let(zones::dateOf)
        minutes += Charge.Minute(
            hr[m], stress, steps[m] ?: 0.0, sleep?.first,
            wake?.let { nightRhr[it] }, if (wake != null) Charge.hrvFactor(hrvSub[wake]) else 1.0,
        )
        stamps += m
        m = m.plusSeconds(60)
    }
    val run = Charge.simulate(minutes, rhr, hrmax, sex, threshold)
    val first = stamps.indexOfFirst { it >= dayStart }
    val measured = (first until stamps.size).filter { run.states[it] != "no_data" }
    if (measured.isEmpty()) return Charge.withheld("charge_no_data")
    val last = measured.last()
    if (!run.slept[last]) return Charge.withheld("charge_no_sleep")
    val inDay = measured.filter { run.slept[it] }
    val wakeI = wakeIndex(run.states, first, last)
    val drained = linkedMapOf("activity" to 0.0, "stress" to 0.0, "living" to 0.0)
    for (i in first..last) for ((k, v) in run.drained[i]) drained[k] = drained.getValue(k) + v
    val curve = (first..last).filter { i -> Duration.between(dayStart, stamps[i]).let { it.seconds % (60 * Charge.CURVE_STEP_MIN) == 0L && it.nano == 0 } || i == last }
        .map { i -> TimedValue(stamps[i], round(run.values[i], 1)) }
    var recharged = 0.0
    for (i in first..last) recharged += run.recharged[i]
    return Card.Shown(ChargeCard(
        value = roundInt(run.values[last]).toInt(),
        at = stamps[last].plusSeconds(60),
        atWake = wakeI?.let { roundInt(run.values[it]).toInt() },
        high = roundInt(inDay.maxOf { run.values[it] }).toInt(),
        low = roundInt(inDay.minOf { run.values[it] }).toInt(),
        recharged = round(recharged, 1),
        activity = round(drained.getValue("activity"), 1),
        stress = round(drained.getValue("stress"), 1),
        living = round(drained.getValue("living"), 1),
        threshold = roundInt(threshold).toInt(),
        personal = personal,
        curve = curve,
    ))
}

/** The end of the day's longest sleep run (a run may start the minute before the day). */
private fun wakeIndex(states: List<String>, first: Int, last: Int): Int? {
    var best: Pair<Int, Int>? = null
    var runStart: Int? = null
    for (i in maxOf(first - 1, 0)..last) {
        if (states[i] == "sleep") {
            if (runStart == null) runStart = i
        } else if (runStart != null) {
            if (i - 1 >= first && (best == null || i - runStart > best.first)) best = (i - runStart) to (i - 1)
            runStart = null
        }
    }
    return best?.second
}
