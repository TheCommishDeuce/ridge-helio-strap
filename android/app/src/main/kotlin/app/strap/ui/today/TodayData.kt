package app.strap.ui.today

import app.strap.api.ApiClient
import app.strap.ui.components.DayWindow
import app.strap.ui.components.Span
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** A number, or the server's reason for not having one. */
sealed interface Reading {
    data class Value(val value: Double, val json: JSONObject) : Reading

    data class Withheld(val message: String) : Reading
}

val Reading.valueOrNull: Double? get() = (this as? Reading.Value)?.value

/** The 30-day median the server compares a daily card with, if it has one. */
val Reading.usual: Double? get() = (this as? Reading.Value)?.json?.optJSONObject("baseline")?.optDouble("median")?.takeIf { !it.isNaN() }

/** Readiness now: recovery reduced by today's load against the typical load. */
data class Readiness(val value: Int, val load: Double, val typical: Double)

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

/** A day's raw stats for one signal (the strap's own values), and its usual up to the same clock time. */
data class DayStats(val min: Double, val max: Double, val mean: Double, val maxAt: Long, val usual: Double?)

/** One slice of a day's per-minute series: its lowest, highest and mean value. */
data class Slice(val t: Long, val min: Double, val max: Double, val mean: Double)

/** The day's per-minute heart rate and stress in [SLICE_MS] slices, and the sleep to shade. */
data class DayCurves(val window: DayWindow, val hr: List<Slice>, val stress: List<Slice>, val sleep: List<Span>) {
    val dayStart: Long get() = window.start
}

const val SLICE_MS = 10 * 60_000L

/** The main night that ended on the day. */
data class Night(val start: Long, val end: Long, val deviceScore: Int?)

/** Everything the Today and Recovery screens draw, parsed once at the data boundary. */
data class TodayData(
    val day: LocalDate,
    val recovery: Reading,
    val readiness: Readiness?,
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
    val heart: DayStats?,
    val stress: DayStats?,
    val stressNote: String?,
    val curves: DayCurves,
    val illness: String?,
    val journal: List<JSONObject>,
    val workouts: List<JSONObject>,
) {
    /** The seven strip days, oldest first. */
    val stripDays: List<LocalDate> get() = (6 downTo 0).map { stripEnd.minusDays(it.toLong()) }

    /** Mean recovery over the seven days before [d] (null under three of them). */
    fun recoveryWeekBefore(d: LocalDate): Double? =
        (1..7).mapNotNull { recoveryByDay[d.minusDays(it.toLong())] }.takeIf { it.size >= 3 }?.average()

    /** Mean recovery over the seven days ending on [d]. */
    fun recoveryWeekTo(d: LocalDate): Double? = (0..6).mapNotNull { recoveryByDay[d.minusDays(it.toLong())] }.takeIf { it.isNotEmpty() }?.average()
}

private fun reading(card: JSONObject?): Reading = when {
    card == null -> Reading.Withheld("Not available.")
    card.has("withheld") -> Reading.Withheld(card.getJSONObject("withheld").getString("message"))
    card.isNull("value") -> Reading.Withheld("Not enough history yet to place this.")
    else -> Reading.Value(card.getDouble("value"), card)
}

private fun stats(o: JSONObject): DayStats? = if (o.has("withheld")) null else
    DayStats(o.getDouble("min"), o.getDouble("max"), o.getDouble("mean"), o.getLong("t_max"), o.optJSONObject("usual")?.num("median"))

/** Per-minute points into fixed slices from local midnight; a slice with no reading is absent. */
private fun slices(points: JSONArray, dayStart: Long): List<Slice> =
    (0 until points.length()).map { points.getJSONArray(it) }.groupBy { (it.getLong(0) - dayStart) / SLICE_MS }.toSortedMap()
        .map { (i, ps) -> ps.map { it.getDouble(1) }.let { v -> Slice(dayStart + i * SLICE_MS, v.min(), v.max(), v.average()) } }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

/** The week strip shows the last seven days, or the week ending on an older picked day. */
fun stripEndFor(day: LocalDate, today: LocalDate = LocalDate.now()): LocalDate = if (day >= today.minusDays(6)) today else day

suspend fun loadToday(api: ApiClient, day: LocalDate): TodayData = coroutineScope {
    val stripEnd = stripEndFor(day)
    val summaryCall = async { api.summary(day) }
    val recoveryCall = async { api.daily("recovery_score", stripEnd.minusDays(13), stripEnd) }
    val journalCall = async { api.journal(day, day) }
    val workoutsCall = async { api.workouts(day, day) }
    val seriesCall = async { api.daySeries(day, "hr,stress") }
    val s = summaryCall.await()
    val sleep = s.getJSONObject("sleep")
    val main = sleep.getJSONArray("sessions").objects().lastOrNull { it.getString("kind") == "main" }
    val health = sleep.getJSONObject("health")
    val recoveryCard = s.getJSONObject("recovery")
    val stress = s.getJSONObject("stress")
    val stepsCard = s.getJSONObject("steps")
    val series = seriesCall.await()
    val window = DayWindow.of(series, day)
    val dayStart = window.start
    val flags = recoveryCard.optJSONObject("flags")
    val factors = flags?.optJSONObject("factors")?.let { f ->
        val weights = flags.optJSONObject("weights")
        listOf("hrv", "rhr", "rr", "sleep").mapNotNull { key ->
            val x = f.optJSONObject(key) ?: return@mapNotNull null
            val weight = weights?.optDouble(key)?.takeIf { !it.isNaN() } ?: 0.0
            if (key == "sleep") Factor(key, x.getDouble("tst_min"), null, null, x.getInt("sub"), weight, x.getDouble("need_min"))
            else Factor(key, x.getDouble("value"), x.num("baseline"), x.num("z"), x.getInt("sub"), weight, null)
        }
    }.orEmpty()
    TodayData(
        day = day,
        recovery = reading(recoveryCard),
        readiness = recoveryCard.optJSONObject("readiness")?.let { Readiness(it.getInt("value"), it.getDouble("load"), it.getDouble("typical")) },
        factors = factors,
        recoveryByDay = recoveryCall.await().getJSONObject("metrics").getJSONArray("recovery_score").objects()
            .associate { LocalDate.parse(it.getString("day")) to it.getDouble("value") },
        stripEnd = stripEnd,
        strain = reading(s.getJSONObject("strain")),
        night = main?.let { Night(it.getLong("start"), it.getLong("end"), if (it.isNull("device_score")) null else it.getInt("device_score")) },
        sleepTstMin = health.optJSONObject("flags")?.num("tst_min"),
        steps = reading(stepsCard.getJSONObject("steps")),
        stepsUsualByNow = stepsCard.getJSONObject("steps").optJSONObject("usual_by_now")?.num("median"),
        distanceM = reading(stepsCard.optJSONObject("distance_m")).valueOrNull,
        activeMin = reading(stepsCard.optJSONObject("mvpa_min")).valueOrNull,
        restingHr = reading(s.getJSONObject("heart").getJSONObject("resting")),
        hrv = reading(s.getJSONObject("heart").optJSONObject("hrv")),
        heart = stats(s.getJSONObject("heart").getJSONObject("today")),
        stress = stats(stress),
        stressNote = stress.optJSONObject("withheld")?.getString("message"),
        curves = series.getJSONObject("series").let { c ->
            DayCurves(window, slices(c.getJSONArray("hr"), dayStart), slices(c.getJSONArray("stress"), dayStart),
                series.optJSONArray("sleep")?.objects().orEmpty().map { Span(it.getLong("start"), it.getLong("end")) })
        },
        illness = s.optJSONObject("illness")?.getString("framing"),
        journal = journalCall.await().objects(),
        workouts = workoutsCall.await().objects(),
    )
}
