package app.strap.ui.today

import app.strap.api.ApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

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
    val restingHr: Reading,
    val hrv: Reading,
    val stressMean: Double?,
    val stressMax: Double?,
    val stressMaxAt: Long?,
    val stressNote: String?,
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
    val s = summaryCall.await()
    val sleep = s.getJSONObject("sleep")
    val main = sleep.getJSONArray("sessions").objects().lastOrNull { it.getString("kind") == "main" }
    val health = sleep.getJSONObject("health")
    val recoveryCard = s.getJSONObject("recovery")
    val stress = s.getJSONObject("stress")
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
        steps = reading(s.getJSONObject("steps").getJSONObject("steps")),
        restingHr = reading(s.getJSONObject("heart").getJSONObject("resting")),
        hrv = reading(s.getJSONObject("heart").optJSONObject("hrv")),
        stressMean = stress.num("mean"),
        stressMax = stress.num("max"),
        stressMaxAt = if (stress.has("t_max")) stress.getLong("t_max") else null,
        stressNote = stress.optJSONObject("withheld")?.getString("message"),
        illness = s.optJSONObject("illness")?.getString("framing"),
        journal = journalCall.await().objects(),
        workouts = workoutsCall.await().objects(),
    )
}
