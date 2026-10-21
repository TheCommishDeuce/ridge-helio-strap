package strap.science

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

// Steps, distance, MVPA and energy for one local day (spec/02 §2.1–2.3).

private const val STEPS = "steps_per_minute"
private const val STRIDE_HEIGHT_FRACTION = 0.414

/** Cadence-based MVPA (Tudor-Locke 2018): a vigorous minute counts twice. */
internal fun Science.mvpa(day: LocalDate) {
    val (start, end) = dayBounds(day)
    val rows = data.samples(STEPS, start, end).filter { it.value > 0 && it.value < 250 }
    if (rows.isEmpty()) return
    val byMin = LinkedHashMap<Instant, Double>()
    for (s in rows) {
        val k = s.ts.floorMinute()
        byMin[k] = maxOf(byMin[k] ?: 0.0, s.value)
    }
    var moderate = 0
    var vigorous = 0
    for ((ts, spm) in byMin) {
        if (spm < 100) continue
        val prev = byMin[ts.minusSeconds(60)] ?: 0.0
        if (spm >= 130 && prev >= 110) vigorous += 1 else if (prev >= 80) moderate += 1
    }
    upsert(day, "mvpa_min", moderate + 2 * vigorous, json("moderate" to moderate, "vigorous" to vigorous))
}

internal data class DailyValue(val value: Double, val flags: JsonObject)

/** The strap's own counter wins over the per-minute sum, which stalls (D28). */
internal fun selectSteps(device: DeviceDailyTotal?, perMinuteSum: Double): DailyValue {
    if (device?.steps == null) return DailyValue(perMinuteSum, json("source" to STEPS))
    return DailyValue(
        device.steps.toDouble(),
        json(
            "source" to device.source, "steps_per_minute_sum" to roundInt(perMinuteSum),
            "reported_at" to isoformatUtc(device.reportedAt), "read_at" to device.readAt?.let(::isoformatUtc),
        ),
    )
}

internal fun partialDayCaveats(device: DeviceDailyTotal?, steps: DailyValue, dayEnd: Instant): List<JsonObject> {
    if (device?.steps == null) return emptyList()
    if (device.readAt == null) {
        return listOf(
            counterCaveat(
                Freshness.COUNTER_READ_TIME_UNKNOWN,
                "This is the strap's own step counter, but this reading did not record when it was taken — so we cannot say whether it counts the whole day or only part of it. Readings taken from now on say when they were read.",
                device, steps,
            ),
        )
    }
    if (device.readAt >= dayEnd) return emptyList()
    return listOf(
        counterCaveat(
            Freshness.COUNTER_MID_DAY,
            "This is the strap's own step counter as it stood at ${isoformatUtc(device.readAt)}, while the day was still running — so it counts the day up to then, not the whole of it. It will rise with the next sync.",
            device, steps,
        ),
    )
}

private fun counterCaveat(reason: String, message: String, device: DeviceDailyTotal, steps: DailyValue) = json(
    "reason" to reason, "message" to message, "read_at" to device.readAt?.let(::isoformatUtc),
    "reported_at" to isoformatUtc(device.reportedAt), "source" to steps.flags.getValue("source"),
)

internal fun selectDistance(device: DeviceDailyTotal?, steps: DailyValue, strideM: Double?): DailyValue? {
    if (device?.distanceM != null) return DailyValue(device.distanceM, json("source" to device.source, "method" to "device"))
    if (strideM == null) return null
    return DailyValue(steps.value * strideM, json("source" to steps.flags.getValue("source"), "method" to "stride", "stride_m" to round(strideM, 3)))
}

internal fun Science.dailyActivity(day: LocalDate) {
    val (start, end) = dayBounds(day)
    val device = data.deviceTotal(day)
    val perMinute = data.samples(STEPS, start, end).filter { it.value < 250 }
    val steps = selectSteps(device, perMinute.sumOf { it.value })
    val counted = device?.steps != null || perMinute.isNotEmpty()
    if (counted) {
        upsert(day, "steps_total", steps.value, steps.flags + json("sample_minutes" to perMinute.size, "caveats" to partialDayCaveats(device, steps, end)))
    }
    val prof = loadProfile(day)
    val strideM = prof?.let { STRIDE_HEIGHT_FRACTION * it.heightCm / 100.0 }
    selectDistance(device, steps, if (counted) strideM else null)?.let { upsert(day, "distance_m_daily", it.value, it.flags) }
    if (prof != null && strideM != null) calories(day, prof, start, end, strideM)
}

// --- Energy: MET by state, anchored on Mifflin-St Jeor BMR (never HR-based) -----------------

private const val AWAKE_SEDENTARY_MET = 1.3
private const val AWAKE_ACTIVE_MET = 1.55
private const val NEAT_WINDOW = 7
private const val SLEEP_MET = 0.95
private const val WALK_RUN_SPEED_M_MIN = 134
private const val MIFFLIN_KCAL_PER_KG_DAY = 10.0
private const val INDIVIDUAL_ERROR_PCT = 15.0

/** Total energy outside workouts: every minute of the day at its state's MET × BMR/1440. */
internal fun Science.teeMet(start: Instant, end: Instant, bmr: Double, strideM: Double): Double {
    val bmrMin = bmr / 1440.0
    val sleepWins = sleepWindows(start, end)
    val workoutWins = data.workouts(start, end).map { it.start to it.start.plusSeconds(it.durationS.toLong()) }
    val stepsByMin = perMinute(STEPS, start, end, keep = { it < 250 }, sum = true)
    var total = 0.0
    val base = start.floorMinute()
    for (i in 0 until dayMinutes(start, end)) {
        val m = base.plusSeconds(60L * i)
        if (workoutWins.any { (s, e) -> s <= m && m < e }) continue
        total += minuteMet(m, stepsByMin, strideM) { t -> sleepWins.any { (s, e) -> s <= t && t < e } } * bmrMin
    }
    return total
}

private fun minuteMet(m: Instant, stepsByMin: Map<Instant, Double>, strideM: Double, asleep: (Instant) -> Boolean): Double {
    val st = stepsByMin[m] ?: 0.0
    if (st > 0) {
        val speed = st * strideM
        val vo2 = if (speed >= WALK_RUN_SPEED_M_MIN) 0.2 * speed + 3.5 else 0.1 * speed + 3.5
        return vo2 / 3.5
    }
    if (asleep(m)) return SLEEP_MET
    val near = (-NEAT_WINDOW..NEAT_WINDOW).any { k -> m.plusSeconds(60L * k) in stepsByMin }
    return if (near) AWAKE_ACTIVE_MET else AWAKE_SEDENTARY_MET
}

private fun weightCaveats(prof: ProfileAsOf, day: LocalDate, bmr: Double): List<JsonObject> {
    val asOf = prof.weightAsOf
    if (!Freshness.weightIsStale(asOf, day)) return emptyList()
    val perKgPct = 100.0 * MIFFLIN_KCAL_PER_KG_DAY / bmr
    val ageDays = Freshness.weightAgeDays(asOf, day)
    val message = "These calories rest on the ${pyStr(round(prof.weightKg, 1))} kg you logged on $asOf, $ageDays days from the day they cover — " +
        "we have no nearer weight, so the mass behind them is assumed rather than something you told us. Every kilogram you have changed since " +
        "moves this by about ${roundInt(MIFFLIN_KCAL_PER_KG_DAY)} kcal a day, at most ${pyStr(round(perKgPct, 2))}% of the number, against the " +
        "plus-or-minus ${roundInt(INDIVIDUAL_ERROR_PCT)}% individual error this estimate already carries — which is why we show it rather than " +
        "withhold it. Log a weight and it stops being an assumption."
    return listOf(
        json(
            "reason" to Freshness.WEIGHT_STALE, "message" to message, "as_of_date" to asOf.toString(), "age_days" to ageDays,
            "horizon_days" to Freshness.WEIGHT_MAX_AGE_DAYS, "kcal_per_day_per_kg" to MIFFLIN_KCAL_PER_KG_DAY,
            "max_percent_per_kg" to round(perKgPct, 2),
        ),
    )
}

private fun weightFlags(prof: ProfileAsOf, day: LocalDate, bmr: Double) = json(
    "weight_kg" to prof.weightKg, "weight_as_of" to prof.weightAsOf.toString(),
    "weight_age_days" to Freshness.weightAgeDays(prof.weightAsOf, day), "caveats" to weightCaveats(prof, day, bmr),
)

private fun Science.calories(day: LocalDate, prof: ProfileAsOf, start: Instant, end: Instant, strideM: Double) {
    val years = age(prof.dob, day)
    val bmr = 10 * prof.weightKg + 6.25 * prof.heightCm - 5 * years + (if (prof.sex == "male") 5 else -161)
    var total = teeMet(start, end, bmr, strideM)
    val workouts = data.workouts(start, end)
    val workoutCal = workouts.sumOf { (it.calories ?: 0).toLong() }.toDouble()
    val uncounted = workouts.filter { it.calories == null }
    val uncountedMinutes = roundInt(uncounted.sumOf { it.durationS.toLong() } / 60.0)
    total += workoutCal
    val active = maxOf(0.0, total - bmr)
    val weight = weightFlags(prof, day, bmr)
    val uncountedCaveats = if (uncounted.isEmpty()) emptyList() else listOf(
        json(
            "reason" to "workout_without_device_calories",
            "message" to "${uncounted.size} recorded ${if (uncounted.size == 1) "session" else "sessions"} on this day ($uncountedMinutes min in total) " +
                "came from the strap with no calorie figure. Workout minutes are counted from the strap's own measurement rather than from the " +
                "movement model, so those minutes contributed nothing and this total is lower than the day actually was — by at least the resting " +
                "energy of $uncountedMinutes minutes.",
            "sessions" to uncounted.size, "uncounted_minutes" to uncountedMinutes,
        ),
    )
    val energy = weight + json("caveats" to weightCaveats(prof, day, bmr) + uncountedCaveats)
    val flags = json("bmr" to roundInt(bmr), "workout_cal" to roundInt(workoutCal), "stride_m" to round(strideM, 3), "pal" to round(total / bmr, 2)) + energy
    upsert(day, "total_calories", total, flags)
    upsert(day, "active_calories", active, energy)
    upsert(day, "basal_calories", bmr, weight)
}
