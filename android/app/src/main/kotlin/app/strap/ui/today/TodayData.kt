package app.strap.ui.today

import app.strap.api.Reader
import app.strap.sync.JournalEntry
import app.strap.ui.components.DayWindow
import app.strap.ui.components.Point
import app.strap.ui.components.Span
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import strap.science.Breathing
import strap.science.Card
import strap.science.DailyNumber
import strap.science.Derived
import strap.science.IllnessCheck
import strap.science.TimedValue
import strap.science.Workout
import strap.science.shown
import java.time.LocalDate

/** A number, or the reason for not having one. */
sealed interface Reading {
    /** [usual] is the 30-day median the card compares with, if there is enough history. */
    data class Value(val value: Double, val usual: Double? = null) : Reading

    data class Withheld(val message: String) : Reading
}

val Reading.valueOrNull: Double? get() = (this as? Reading.Value)?.value

val Reading.usual: Double? get() = (this as? Reading.Value)?.usual

/**
 * Charge (D33): the body battery now, or at the day's end for a past day. Sleep refilled
 * [recharged]; [activity], [stress] and everyday [living] drained it. [threshold] is the
 * stress level that starts to drain it, [personal] when learned from your calm readings.
 */
data class Charge(
    val value: Int,
    val at: Long,
    val atWake: Int?,
    val high: Int,
    val low: Int,
    val recharged: Double,
    val activity: Double,
    val stress: Double,
    val living: Double,
    val threshold: Int,
    val personal: Boolean,
    val curve: List<Point>,
)

/**
 * One part of the recovery score. [key] hrv / rhr / rr carry value, baseline and z; sleep
 * carries asleep and need minutes instead.
 */
data class Factor(
    val key: String,
    val value: Double,
    val baseline: Double?,
    val z: Double?,
    val sub: Int,
    val weight: Double,
    val needMin: Double?,
)

/** 7-day mean cardio load against the 28-day mean (spec/02 §2.5), and the 0.8–1.3× range around it. */
class LoadRange(val acute: Double, val low: Double, val high: Double) {
    val words get() = when {
        acute < low -> "Below range"
        acute > high -> "Load spike"
        else -> "In range"
    }
}

internal fun loadRange(load: Map<LocalDate, Double>, day: LocalDate): LoadRange? {
    val rows = load.filterKeys { it <= day && it > day.minusDays(28) }
    if (rows.size < 28) return null
    val chronic = rows.values.average().takeIf { it > 0 } ?: return null
    val acute = rows.filterKeys { it > day.minusDays(7) }.values.average()
    return LoadRange(acute, 0.8 * chronic, 1.3 * chronic)
}

/** A day's raw stats for one signal (the strap's own values), and its usual up to the same clock time. */
data class DayStats(val min: Double, val max: Double, val mean: Double, val maxAt: Long, val usual: Double?)

/** One slice of a day's per-minute series: its lowest, highest and mean value. */
data class Slice(val t: Long, val min: Double, val max: Double, val mean: Double)

/** The day's per-minute heart rate and stress in [SLICE_MS] slices, steps summed up slice by slice, and the sleep to shade. */
data class DayCurves(val window: DayWindow, val hr: List<Slice>, val stress: List<Slice>, val stepsSoFar: List<Point>, val sleep: List<Span>) {
    val dayStart: Long get() = window.start
}

const val SLICE_MS = 10 * 60_000L

/** The main night that ended on the day. */
data class Night(val start: Long, val end: Long, val deviceScore: Int?)

/** Everything the Today and Recovery screens draw, parsed once at the data boundary. */
data class TodayData(
    val day: LocalDate,
    val recovery: Reading,
    val charge: Charge?,
    /** Why there is no [charge], when there is none. */
    val chargeNote: String?,
    val factors: List<Factor>,
    /** recovery_score for the two weeks ending on [stripEnd] — the week strip and "vs week". */
    val recoveryByDay: Map<LocalDate, Double>,
    val stripEnd: LocalDate,
    val strain: Reading,
    val night: Night?,
    val sleepTstMin: Double?,
    val steps: Reading,
    /** Steps usually walked by this time of day; only for a day still running. */
    val stepsUsualByNow: Double?,
    val distanceM: Double?,
    val activeMin: Double?,
    val restingHr: Reading,
    val hrv: Reading,
    /** The night's mean SpO₂ against the median of the 30 nights before. */
    val spo2: Reading,
    /** The strap's blood-oxygen drops an hour through the night (its own figure). */
    val breathing: Card<Breathing>,
    /** The night's mean skin temperature against the 14 nights before. */
    val skinTemp: Reading,
    /** The max HR the day's zones are cut by (Tanaka, from the profile); null without one. */
    val hrmax: Int?,
    val heart: DayStats?,
    val stress: DayStats?,
    val stressNote: String?,
    val curves: DayCurves,
    val illness: String?,
    /** The night the active [illness] flag was raised on (a flag stays active two days); null without one. */
    val illnessNight: LocalDate?,
    /** Last night's illness check: each limb against the 14 nights before; null if it could not be read. */
    val illnessCheck: IllnessCheck?,
    /** The 7-day load against its range (null under 28 days of load). */
    val load: LoadRange?,
    val journal: List<JournalEntry>,
    val workouts: List<Workout>,
) {
    /** The seven strip days, oldest first. */
    val stripDays: List<LocalDate> get() = (6 downTo 0).map { stripEnd.minusDays(it.toLong()) }

    /** Mean recovery over the seven days before [d] (null under three of them). */
    fun recoveryWeekBefore(d: LocalDate): Double? =
        (1..7).mapNotNull { recoveryByDay[d.minusDays(it.toLong())] }.takeIf { it.size >= 3 }?.average()

    /** Mean recovery over the seven days ending on [d]. */
    fun recoveryWeekTo(d: LocalDate): Double? = (0..6).mapNotNull { recoveryByDay[d.minusDays(it.toLong())] }.takeIf { it.isNotEmpty() }?.average()
}

private fun reading(card: Card<DailyNumber>): Reading = when (card) {
    is Card.Withheld -> Reading.Withheld(card.message)
    is Card.Shown -> Reading.Value(card.value.value, card.value.baseline?.median)
}

private fun stats(card: Card<strap.science.DayStats>): DayStats? = card.shown?.let { DayStats(it.min, it.max, it.mean, it.maxAt.toEpochMilli(), it.usual?.median) }

/** Per-minute points into fixed slices from local midnight; a slice with no reading is absent. */
private fun slices(points: List<TimedValue>, dayStart: Long): List<Slice> =
    points.groupBy { (it.at.toEpochMilli() - dayStart) / SLICE_MS }.toSortedMap()
        .map { (i, ps) -> ps.map { it.value }.let { v -> Slice(dayStart + i * SLICE_MS, v.min(), v.max(), v.average()) } }

/** Steps from local midnight, summed up at the end of each [SLICE_MS] slice that has any. */
private fun stepsSoFar(points: List<TimedValue>, dayStart: Long): List<Point> {
    var total = 0.0
    return points.groupBy { (it.at.toEpochMilli() - dayStart) / SLICE_MS }.toSortedMap()
        .map { (i, ps) -> total += ps.sumOf { it.value }; Point(dayStart + (i + 1) * SLICE_MS, total) }
}

/** The day's value of a nightly metric against the median of the nights before it (five or more). */
private fun nightly(rows: List<Derived>, day: LocalDate): Reading {
    val today = rows.firstOrNull { it.day == day } ?: return Reading.Withheld("The strap recorded nothing for this night.")
    val before = rows.filter { it.day < day }.map { it.value }.sorted()
    val usual = before.takeIf { it.size >= 5 }?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    return Reading.Value(today.value, usual)
}

private fun charge(c: strap.science.ChargeCard) = Charge(
    c.value, c.at.toEpochMilli(), c.atWake, c.high, c.low, c.recharged, c.activity, c.stress, c.living, c.threshold, c.personal,
    c.curve.map { Point(it.at.toEpochMilli(), it.value) },
)

/** The week strip shows the last seven days, or the week ending on an older picked day. */
fun stripEndFor(day: LocalDate, today: LocalDate = LocalDate.now()): LocalDate = if (day >= today.minusDays(6)) today else day

suspend fun loadToday(api: Reader, day: LocalDate): TodayData = coroutineScope {
    val stripEnd = stripEndFor(day)
    val summaryCall = async { api.summary(day) }
    val recoveryCall = async { api.daily("recovery_score", stripEnd.minusDays(13), stripEnd) }
    val journalCall = async { api.journal(day, day) }
    val workoutsCall = async { api.workouts(day, day) }
    val seriesCall = async { api.daySeries(day, listOf("hr", "stress", "steps")) }
    val spo2Call = async { api.daily("spo2_overnight", day.minusDays(30), day) }
    val loadCall = async { api.daily("cardio_load", day.minusDays(27), day) }
    val checkCall = async { api.illnessCheck(day) }
    val s = summaryCall.await()
    val check = checkCall.await()
    val series = seriesCall.await()
    val window = DayWindow.of(series.start, series.end, series.timezone)
    val recovery = s.recovery.shown
    TodayData(
        day = day,
        recovery = when (val r = s.recovery) {
            is Card.Withheld -> Reading.Withheld(r.message)
            is Card.Shown -> Reading.Value(r.value.value.toDouble())
        },
        charge = s.charge.shown?.let(::charge),
        chargeNote = (s.charge as? Card.Withheld)?.message,
        factors = recovery?.factors.orEmpty().map { f ->
            Factor(f.key, f.value, f.baseline, f.z, f.sub, f.weight, f.needMin)
        },
        recoveryByDay = recoveryCall.await().associate { it.day to it.value },
        stripEnd = stripEnd,
        strain = when (val st = s.strain) {
            is Card.Withheld -> Reading.Withheld(st.message)
            is Card.Shown -> st.value.value?.let { Reading.Value(it) } ?: Reading.Withheld("Not enough history yet to place this.")
        },
        night = s.sleep.main?.let { Night(it.start.toEpochMilli(), it.end.toEpochMilli(), it.deviceScore) },
        sleepTstMin = s.sleep.health.shown?.tstMin,
        steps = reading(s.activity.steps),
        stepsUsualByNow = s.activity.usualByNow?.median,
        distanceM = reading(s.activity.distanceM).valueOrNull,
        activeMin = reading(s.activity.mvpaMin).valueOrNull,
        restingHr = reading(s.heart.resting),
        hrv = reading(s.heart.hrv),
        spo2 = nightly(spo2Call.await(), day),
        breathing = s.sleep.breathing,
        hrmax = s.strain.shown?.hrmax?.takeIf { it > 0 },
        skinTemp = check.let { ch -> ch.tempNight?.let { Reading.Value(it, ch.tempUsual) } ?: Reading.Withheld("The strap recorded no skin temperature this night.") },
        heart = stats(s.heart.today),
        stress = stats(s.stress),
        stressNote = (s.stress as? Card.Withheld)?.message,
        curves = DayCurves(
            window, slices(series.series["hr"].orEmpty(), window.start), slices(series.series["stress"].orEmpty(), window.start),
            stepsSoFar(series.series["steps"].orEmpty(), window.start),
            series.sleep.map { Span(it.start.toEpochMilli(), it.end.toEpochMilli()) },
        ),
        illness = s.illness?.framing,
        illnessNight = s.illness?.date,
        illnessCheck = check,
        load = loadRange(loadCall.await().associate { it.day to it.value }, day),
        journal = journalCall.await(),
        workouts = workoutsCall.await(),
    )
}
