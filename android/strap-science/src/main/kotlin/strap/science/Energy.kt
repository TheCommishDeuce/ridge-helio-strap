package strap.science

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Energy 0–100, minute by minute (spec/02 §2.12, D35; Charge until 0.6): sleep fills it; movement,
 * stress over a personal threshold and the waking day drain it. Read-time: computed when shown,
 * never stored. After OpenStrap's Energy (2.1). No study validates a body-battery formula; every
 * constant is a design choice.
 */
public object Energy {
    internal const val START_VALUE = 50.0
    /** A week of wake values for the restart after a night off, plus the night that washes out the start. */
    internal val WARMUP: Duration = Duration.ofDays(8)
    /** A past day runs on this far, only to see whether a gap at its end closed. */
    internal val LOOKAHEAD: Duration = Duration.ofHours(14)
    /** The floor under every minute doing something: movement, stress, a workout, its recovery. */
    internal const val LIVING = 0.04
    /** A still, awake minute drains this share of the current value: 0.04 at 80, 0.025 at 50. */
    internal const val REST_DRAIN = 0.0005
    /** A calm, still minute regains this share of the room left, and drains nothing: 85 → 86 over 8 h. */
    internal const val RELAX_GAIN = 0.00015
    internal const val SLEEP_RATE = 0.006
    internal val STAGE_WEIGHT = mapOf(5 to 1.0, 4 to 0.8, 8 to 0.65, 7 to 0.1)
    internal const val GAP_STAGE = 128
    internal const val EXERCISE_HRR = 0.3
    internal const val ACTIVITY_HRR = 0.2
    internal const val ACTIVITY_STEPS = 60
    /** At or over [EXERCISE_HRR] this many minutes in a row is effort without steps (an unrecorded ride). */
    internal const val SUSTAINED_MIN = 10
    /** Inside a workout or its after-window, a minute under this reserve is back at sitting level. */
    internal const val WORKOUT_REST_HRR = 0.1
    /** Stress readings from a workout's start to this long after its end reflect the exercise, not stress. */
    internal val AFTER_WORKOUT: Duration = Duration.ofMinutes(20)
    internal const val TRIMP_DRAIN = 0.32
    internal const val AFTERBURN_DECAY = 0.966
    internal const val AFTERBURN_MIN = 5.0
    internal const val STRESS_RUN = 3
    internal const val STRESS_DRAIN = 0.007
    internal const val STRESS_MAX_AGE_MIN = 10L
    internal const val RELAX_STRESS = 25
    internal const val RELAX_STEPS = 10
    /** No heart rate (and no sleep) this long, closed by later data, is the strap off; shorter holds. */
    internal const val NOT_WORN_GAP_MIN = 10
    /** Zepp's default no-wear drift per minute, at or over 50 and under it (about −2.9 and −2.1 an hour). */
    internal const val NOT_WORN_DRIFT_HIGH = 0.0482
    internal const val NOT_WORN_DRIFT_LOW = 0.0349
    /** Not-worn minutes since the last wake after which there is no value (Zepp's 6.4 h). */
    internal const val NOT_WORN_LIMIT_MIN = 384
    /** Share of a usual sleep a not-worn stretch must cover to be the strap off overnight. */
    internal const val USUAL_SLEEP_COVER = 0.5
    internal const val USUAL_SLEEP_NIGHTS = 14
    internal const val USUAL_SLEEP_LOOKBACK_DAYS = 30L
    /** Without any main sleep: midnight to 06:00, as minutes from the noon before. */
    internal const val USUAL_SLEEP_START_DEFAULT = 720L
    internal const val USUAL_SLEEP_END_DEFAULT = 1080L
    internal const val WAKE_HISTORY = 7
    internal const val DEFAULT_WAKE = 80.0
    /** The value a sleep starts from after a long daytime stretch off. */
    internal const val RESTART_BEFORE_SLEEP = 50.0
    /** On a night with a high illness flag, until the next main sleep: recharge × 0.5, drain × 1.5. */
    internal const val ALERT_RECHARGE = 0.5
    internal const val ALERT_DRAIN = 1.5
    internal const val DEFAULT_THRESHOLD = 60.0
    internal const val THRESHOLD_DAYS = 14L
    internal const val THRESHOLD_MIN_DAYS = 3
    internal const val THRESHOLD_MIN_READINGS = 60
    internal const val CURVE_STEP_MIN = 10L

    internal val MESSAGES = mapOf(
        "energy_needs_profile" to "Energy needs your date of birth and a measured resting heart rate.",
        "energy_no_sleep" to "Energy starts after the strap has recorded a sleep.",
        "energy_no_data" to "The strap recorded nothing this day.",
        "energy_not_worn" to "The strap was off too long this day to tell.",
    )

    internal enum class Workout { ACTIVE, AFTER }

    /**
     * One minute of input. [wake]: a main sleep ended at this minute. [usualSleep]: inside the
     * usual sleep window. [alert]: a high illness flag is in force.
     */
    internal class Minute(
        val hr: Double? = null,
        val stress: Double? = null,
        val steps: Double = 0.0,
        val stage: Int? = null,
        val nightRhr: Double? = null,
        val hrvFactor: Double = 1.0,
        val workout: Workout? = null,
        val wake: Boolean = false,
        val usualSleep: Boolean = false,
        val alert: Boolean = false,
    )

    /** [values] is null where there is no value (the strap off too long); [estimated] marks a restart after a night off. */
    internal class Run {
        val values = ArrayList<Double?>()
        val states = ArrayList<String>()
        val recharged = ArrayList<Double>()
        val drained = ArrayList<Map<String, Double>>()
        val slept = ArrayList<Boolean>()
        val estimated = ArrayList<Boolean>()
    }

    internal fun hrvFactor(hrvSub: Double?): Double = if (hrvSub == null) 1.0 else maxOf(0.6, minOf(1.1, 0.75 + 0.25 * hrvSub / 50.0))

    /** Median of calm awake stress readings + 10, kept in 50–70; Zepp's "medium" (60) without enough. */
    internal fun personalThreshold(calmByDay: Map<LocalDate, List<Double>>): Pair<Double, Boolean> {
        val readings = calmByDay.values.flatten()
        if (calmByDay.values.count { it.isNotEmpty() } < THRESHOLD_MIN_DAYS || readings.size < THRESHOLD_MIN_READINGS) return DEFAULT_THRESHOLD to false
        return maxOf(50.0, minOf(70.0, Robust.median(readings) + 10.0)) to true
    }

    /** Minutes without heart rate or sleep in a gap of [NOT_WORN_GAP_MIN] or more that later data closes. */
    internal fun notWorn(minutes: List<Minute>): BooleanArray {
        val out = BooleanArray(minutes.size)
        var i = 0
        while (i < minutes.size) {
            if (minutes[i].hr != null || minutes[i].stage != null) { i++; continue }
            var j = i
            while (j < minutes.size && minutes[j].hr == null && minutes[j].stage == null) j++
            if (j < minutes.size && j - i >= NOT_WORN_GAP_MIN) for (k in i until j) out[k] = true
            i = j
        }
        return out
    }

    /** Minutes of a not-worn stretch that covers at least [USUAL_SLEEP_COVER] of a usual sleep window. */
    internal fun offOvernight(minutes: List<Minute>, off: BooleanArray): BooleanArray {
        val usual = ArrayList<IntRange>()
        var i = 0
        while (i < minutes.size) {
            if (!minutes[i].usualSleep) { i++; continue }
            var j = i
            while (j < minutes.size && minutes[j].usualSleep) j++
            usual += i until j
            i = j
        }
        val out = BooleanArray(minutes.size)
        i = 0
        while (i < minutes.size) {
            if (!off[i]) { i++; continue }
            var j = i
            while (j < minutes.size && off[j]) j++
            val covers = usual.any { u -> (minOf(j, u.last + 1) - maxOf(i, u.first)) >= USUAL_SLEEP_COVER * (u.last + 1 - u.first) }
            if (covers) for (k in i until j) out[k] = true
            i = j
        }
        return out
    }

    /** Minutes in a run of [SUSTAINED_MIN]+ awake minutes outside workouts at or over [EXERCISE_HRR]. */
    internal fun sustainedEffort(minutes: List<Minute>, rhr: Double, hrmax: Double): BooleanArray {
        fun hard(m: Minute) = m.stage == null && m.workout == null && m.hr != null && Trimp.hrReserveFraction(m.hr, rhr, hrmax) >= EXERCISE_HRR
        val out = BooleanArray(minutes.size)
        var i = 0
        while (i < minutes.size) {
            if (!hard(minutes[i])) { i++; continue }
            var j = i
            while (j < minutes.size && hard(minutes[j])) j++
            if (j - i >= SUSTAINED_MIN) for (k in i until j) out[k] = true
            i = j
        }
        return out
    }

    internal fun simulate(minutes: List<Minute>, rhr: Double, hrmax: Double, sex: String?, threshold: Double): Run {
        val run = Run()
        val off = notWorn(minutes)
        val overnight = offOvernight(minutes, off)
        val sustained = sustainedEffort(minutes, rhr, hrmax)
        var energy = START_VALUE
        var afterburn = 0.0
        var stressRun = 0
        var prevHr: Double? = null
        var slept = false
        val wakes = ArrayList<Double>()
        var wakesSeen = 0
        var notWornRun = 0
        var invalid = false
        var invalidOvernight = false

        fun record(value: Double?, state: String, gain: Double = 0.0, drain: Map<String, Double> = emptyMap(), estimated: Boolean = false) {
            run.values += value
            run.states += state
            run.recharged += gain
            run.drained += drain
            run.slept += slept
            run.estimated += estimated
        }

        for ((i, m) in minutes.withIndex()) {
            // A wake value, all but the first (which still carries the starting guess); the not-worn count restarts.
            if (m.wake) {
                if (!invalid) { if (wakesSeen > 0) wakes += energy; wakesSeen++ }
                notWornRun = 0
            }
            if (off[i]) {
                notWornRun++
                stressRun = 0
                if (!invalid && (overnight[i] || notWornRun > NOT_WORN_LIMIT_MIN)) {
                    invalid = true
                    invalidOvernight = overnight[i]
                }
                if (invalid) {
                    record(null, "not_worn")
                } else {
                    val before = energy
                    energy = maxOf(0.0, energy - if (energy >= 50) NOT_WORN_DRIFT_HIGH else NOT_WORN_DRIFT_LOW)
                    record(energy, "not_worn", drain = if (before > energy) mapOf("living" to before - energy) else emptyMap())
                }
                continue
            }
            var estimated = false
            if (invalid) {
                if (m.stage != null) {
                    energy = RESTART_BEFORE_SLEEP
                } else if (m.hr != null && invalidOvernight && slept) {
                    // Back on after a night off: restart from the usual wake.
                    energy = wakes.takeLast(WAKE_HISTORY).takeIf { it.isNotEmpty() }?.let(Robust::median) ?: DEFAULT_WAKE
                    estimated = true
                } else {
                    // A short dropout inside the stretch, or worn again after a long one in the day: still no value.
                    stressRun = 0
                    record(null, if (m.hr == null) "no_data" else "not_worn")
                    continue
                }
                invalid = false
                notWornRun = 0
                afterburn = 0.0
            }

            // A reading in a workout's window doesn't count, and breaks the run.
            stressRun = if (m.stage == null && m.workout == null && m.stress != null && m.stress >= threshold) stressRun + 1 else 0
            var drain: Map<String, Double> = emptyMap()
            var gain = 0.0
            var exercising = false
            val state: String
            if (m.stage != null) {
                slept = true
                val relax = if (m.hr != null && m.nightRhr != null) maxOf(0.5, minOf(1.2, 1.2 - 0.04 * (m.hr - m.nightRhr))) else 1.0
                gain = SLEEP_RATE * (STAGE_WEIGHT[m.stage] ?: 0.8) * relax * m.hrvFactor * (100.0 - energy)
                state = "sleep"
            } else if (m.hr == null) {
                // A dropout under [NOT_WORN_GAP_MIN], or data not synced yet: the value holds.
                state = "no_data"
            } else {
                val hrr = Trimp.hrReserveFraction(m.hr, rhr, hrmax)
                val load = Trimp.minute(m.hr, rhr, hrmax, sex)
                // Heart rate is effort outside workouts only while moving, or in a sustained run.
                val effort = m.workout != null || m.steps > 0 || sustained[i]
                val ph = prevHr
                if (hrr >= EXERCISE_HRR && effort) {
                    state = "exercise"
                    exercising = true
                    afterburn += load
                    drain = linkedMapOf("living" to LIVING, "activity" to TRIMP_DRAIN * load)
                } else if (afterburn > AFTERBURN_MIN && ph != null && m.hr <= ph + 2) {
                    state = "exercise_recovery"
                    drain = linkedMapOf("living" to LIVING)
                } else if ((hrr >= ACTIVITY_HRR && effort) || m.steps >= ACTIVITY_STEPS) {
                    state = "activity"
                    drain = linkedMapOf("living" to LIVING, "activity" to TRIMP_DRAIN * load)
                } else if (m.workout == Workout.ACTIVE && hrr >= WORKOUT_REST_HRR) {
                    state = "activity"
                    drain = linkedMapOf("living" to LIVING, "activity" to TRIMP_DRAIN * load)
                } else if (m.workout == Workout.AFTER && hrr >= WORKOUT_REST_HRR) {
                    state = "exercise_recovery"
                    drain = linkedMapOf("living" to LIVING)
                } else if (m.workout != null) {
                    state = "rest"
                    drain = linkedMapOf("living" to LIVING)
                } else if (stressRun >= STRESS_RUN) {
                    state = "stress"
                    drain = linkedMapOf("living" to LIVING, "stress" to STRESS_DRAIN * (m.stress!! - threshold))
                } else if (m.stress != null && m.stress < RELAX_STRESS && m.steps < RELAX_STEPS && hrr < ACTIVITY_HRR) {
                    state = "relax"
                    gain = RELAX_GAIN * (100.0 - energy)
                } else {
                    state = "rest"
                    drain = linkedMapOf("living" to REST_DRAIN * energy)
                }
            }
            if (m.alert) {
                gain *= ALERT_RECHARGE
                drain = drain.mapValues { (_, v) -> v * ALERT_DRAIN }
            }
            if (!exercising) afterburn *= AFTERBURN_DECAY
            if (m.hr != null) prevHr = m.hr
            val before = energy
            var totalDrain = 0.0
            for (v in drain.values) totalDrain += v
            energy = maxOf(0.0, minOf(100.0, energy + gain - totalDrain))
            val delta = energy - before
            record(
                energy, state, maxOf(delta, 0.0),
                if (delta < 0 && totalDrain > 0) drain.mapValues { (_, v) -> -delta * v / totalDrain } else emptyMap(),
                estimated,
            )
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
            if (st.code == Energy.GAP_STAGE) continue
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

/** Each workout's active span and the [Energy.AFTER_WORKOUT] after it, as (start, end, phase). */
private fun Science.workoutSpans(start: Instant, end: Instant): List<Triple<Instant, Instant, Energy.Workout>> =
    data.workouts(start.minus(Duration.ofDays(1)), end).flatMap { w ->
        val stop = w.start.plusSeconds(w.durationS.toLong())
        listOf(Triple(w.start, stop, Energy.Workout.ACTIVE), Triple(stop, stop.plus(Energy.AFTER_WORKOUT), Energy.Workout.AFTER))
    }

/** The phase of [m] in [spans]: active wins over the after-window of an earlier workout. */
private fun phaseAt(spans: List<Triple<Instant, Instant, Energy.Workout>>, m: Instant): Energy.Workout? {
    val next = m.plusSeconds(60)
    var found: Energy.Workout? = null
    for ((s, e, phase) in spans) if (m < e && next > s) { if (phase == Energy.Workout.ACTIVE) return phase; found = phase }
    return found
}

/** Stress readings of the 14 days before [end] taken calm: awake, still, HR reserve under 20 %, outside workouts. */
private fun Science.calmReadings(end: Instant, rhr: Double, hrmax: Double): Map<LocalDate, List<Double>> {
    val start = end.minus(Duration.ofDays(Energy.THRESHOLD_DAYS))
    val hr = hrPerMinute(start, end)
    val steps = perMinute("steps_per_minute", start, end, keep = { true }, sum = true)
    val stress = perMinute("stress", start, end, keep = { true }, sum = false)
    val asleep = asleepMinutes(start, end)
    val spans = workoutSpans(start, end)
    val out = LinkedHashMap<LocalDate, MutableList<Double>>()
    for ((m, v) in stress) {
        val h = hr[m]
        if (m in asleep || h == null || (steps[m] ?: 0.0) >= Energy.RELAX_STEPS || Trimp.hrReserveFraction(h, rhr, hrmax) >= Energy.ACTIVITY_HRR) continue
        if (phaseAt(spans, m) != null) continue
        out.getOrPut(zones.dateOf(m)) { ArrayList() } += v
    }
    return out
}

/**
 * The usual main sleep around each day from [first] to [last], as `[start, end)`: the median start
 * and end of the last [Energy.USUAL_SLEEP_NIGHTS] main sleeps before [before], told as minutes from
 * the noon before the wake day (midnight to 06:00 without any).
 */
private fun Science.usualSleep(before: Instant, first: LocalDate, last: LocalDate): List<Pair<Instant, Instant>> {
    fun noonBefore(day: LocalDate) = zones.start(day.minusDays(1)).plus(Duration.ofHours(12))
    val nights = data.sleepSessions(before.minus(Duration.ofDays(Energy.USUAL_SLEEP_LOOKBACK_DAYS)), before)
        .filter { it.isMain && it.end <= before }.sortedBy { it.end }.takeLast(Energy.USUAL_SLEEP_NIGHTS)
    val offsets = nights.map { s -> noonBefore(zones.dateOf(s.end)).let { n -> Duration.between(n, s.start).toMinutes() to Duration.between(n, s.end).toMinutes() } }
    val from = if (offsets.isEmpty()) Energy.USUAL_SLEEP_START_DEFAULT else Robust.median(offsets.map { it.first.toDouble() }).toLong()
    val to = if (offsets.isEmpty()) Energy.USUAL_SLEEP_END_DEFAULT else Robust.median(offsets.map { it.second.toDouble() }).toLong()
    val out = ArrayList<Pair<Instant, Instant>>()
    var d = first
    while (d <= last) {
        val n = noonBefore(d)
        out += n.plus(Duration.ofMinutes(from)) to n.plus(Duration.ofMinutes(to))
        d = d.plusDays(1)
    }
    return out
}

/** The Energy card for [day] as it stands at [now]. */
public fun Science.energyCard(day: LocalDate, now: Instant): Card<EnergyCard> {
    val (dayStart, dayEnd) = zones.bounds(day)
    val end = minOf(dayEnd, now).floorMinute()
    if (end <= dayStart) return Energy.withheld("energy_no_data")
    val dob = dateOfBirth()
    val rhr = measuredRhr(day)
    if (dob == null || rhr == null) return Energy.withheld("energy_needs_profile")
    val hrmax = hrMax(age(dob, day))
    if (hrmax - rhr < 1) return Energy.withheld("energy_needs_profile")
    val sex = data.profile()?.sex
    val start = dayStart.minus(Energy.WARMUP)
    val simEnd = minOf(dayEnd.plus(Energy.LOOKAHEAD), now).floorMinute()
    val hr = hrPerMinute(start, simEnd)
    val steps = perMinute("steps_per_minute", start, simEnd, keep = { it > 0 && it < 250 }, sum = true)
    val spans = workoutSpans(start, simEnd)
    val stressPoints = perMinute("stress", start.minusSeconds(60 * Energy.STRESS_MAX_AGE_MIN), simEnd, keep = { true }, sum = false).entries
        .filter { phaseAt(spans, it.key) == null }.sortedBy { it.key }
    val asleep = asleepMinutes(start, simEnd)
    val firstNight = zones.dateOf(start)
    val nightRhr = store.range("rhr_daily", firstNight, day).associate { it.day to it.value }
    val hrvSub = store.range("recovery_score", firstNight, day).mapNotNull { r -> r.hrvSub()?.let { r.day to it } }.toMap()
    val (threshold, personal) = Energy.personalThreshold(calmReadings(dayStart, rhr, hrmax))
    val mains = data.sleepSessions(start, simEnd).filter { it.isMain }.sortedBy { it.start }
    val wakes = mains.mapTo(HashSet()) { it.end.floorMinute() }
    // A high illness flag runs from its night's start to the next main sleep's start.
    val alerts = mains.mapIndexedNotNull { k, s ->
        if (store.illness(zones.dateOf(s.end))?.severity != "high") null else s.start to (mains.getOrNull(k + 1)?.start ?: simEnd)
    }
    val usual = usualSleep(dayStart, firstNight, zones.dateOf(simEnd))

    val minutes = ArrayList<Energy.Minute>()
    val stamps = ArrayList<Instant>()
    var si = 0
    var lastStress: Map.Entry<Instant, Double>? = null
    var m = start
    while (m < simEnd) {
        while (si < stressPoints.size && stressPoints[si].key <= m) lastStress = stressPoints[si++]
        val ls = lastStress
        val stress = if (ls != null && Duration.between(ls.key, m) <= Duration.ofMinutes(Energy.STRESS_MAX_AGE_MIN)) ls.value else null
        val sleep = asleep[m]
        val wake = sleep?.second?.let(zones::dateOf)
        minutes += Energy.Minute(
            hr[m], stress, steps[m] ?: 0.0, sleep?.first,
            wake?.let { nightRhr[it] }, if (wake != null) Energy.hrvFactor(hrvSub[wake]) else 1.0,
            workout = phaseAt(spans, m),
            wake = m in wakes,
            usualSleep = usual.any { (a, b) -> m >= a && m < b },
            alert = alerts.any { (a, b) -> m >= a && m < b },
        )
        stamps += m
        m = m.plusSeconds(60)
    }
    val run = Energy.simulate(minutes, rhr, hrmax, sex, threshold)
    val first = stamps.indexOfFirst { it >= dayStart }
    val count = stamps.indexOfFirst { it >= end }.let { if (it < 0) stamps.size else it }
    if (first < 0 || first >= count) return Energy.withheld("energy_no_data")
    val window = first until count
    val measured = window.filter { run.values[it] != null && run.states[it] != "no_data" }
    if (measured.isEmpty()) return Energy.withheld(if (window.any { run.states[it] == "not_worn" }) "energy_not_worn" else "energy_no_data")
    val last = measured.last()
    if (!run.slept[last]) return Energy.withheld("energy_no_sleep")
    val inDay = measured.filter { run.slept[it] }
    // Off overnight: the day restarted from the usual wake, which stands in for the wake value.
    val restart = window.firstOrNull { run.estimated[it] }
    val wakeI = restart ?: wakeIndex(run.states, first, last)
    val drained = linkedMapOf("activity" to 0.0, "stress" to 0.0, "living" to 0.0)
    for (i in first..last) for ((k, v) in run.drained[i]) drained[k] = drained.getValue(k) + v
    val curve = (first..last).filter { i ->
        run.values[i] != null && (Duration.between(dayStart, stamps[i]).let { it.seconds % (60 * Energy.CURVE_STEP_MIN) == 0L && it.nano == 0 } || i == last)
    }.map { i -> TimedValue(stamps[i], round(run.values[i]!!, 1)) }
    var recharged = 0.0
    for (i in first..last) recharged += run.recharged[i]
    return Card.Shown(EnergyCard(
        value = roundInt(run.values[last]!!).toInt(),
        at = stamps[last].plusSeconds(60),
        atWake = wakeI?.let { run.values[it] }?.let { roundInt(it).toInt() },
        high = roundInt(inDay.maxOf { run.values[it]!! }).toInt(),
        low = roundInt(inDay.minOf { run.values[it]!! }).toInt(),
        recharged = round(recharged, 1),
        activity = round(drained.getValue("activity"), 1),
        stress = round(drained.getValue("stress"), 1),
        living = round(drained.getValue("living"), 1),
        threshold = roundInt(threshold).toInt(),
        personal = personal,
        estimated = restart != null,
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
