package app.strap.ui.today

import app.strap.api.ApiClient
import app.strap.ui.components.Point
import app.strap.ui.components.Span
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A number, or the server's reason for not having one. */
sealed interface Reading {
    data class Value(val value: Double, val json: JSONObject) : Reading

    data class Withheld(val message: String) : Reading
}

/** Everything the Today screen draws, parsed once at the data boundary. */
data class TodayData(
    val day: LocalDate,
    val dayStartMs: Long,
    val recovery: Reading,
    val readiness: Int?,
    val strain: Reading,
    val sleepTstMin: Double?,
    val sleepDeviceScore: Int?,
    val sleepDebt: Reading,
    val sleepDims: Int?,
    val hypnogram: List<Triple<Long, Long, Int>>,
    val steps: Reading,
    val distanceM: Reading,
    val activeKcal: Reading,
    val restingHr: Reading,
    val vo2max: Reading,
    val stressNote: String?,
    val illness: JSONObject?,
    val hr: List<Point>,
    val stress: List<Point>,
    val sleepSpans: List<Span>,
    val stepsByHour: Map<Int, Double>,
)

private fun reading(card: JSONObject?): Reading = when {
    card == null -> Reading.Withheld("Not available.")
    card.has("withheld") -> Reading.Withheld(card.getJSONObject("withheld").getString("message"))
    card.isNull("value") -> Reading.Withheld("Not enough history yet to place this.")
    else -> Reading.Value(card.getDouble("value"), card)
}

private fun points(array: JSONArray?): List<Point> =
    if (array == null) emptyList() else List(array.length()) { i -> array.getJSONArray(i).let { Point(it.getLong(0), it.getDouble(1)) } }

suspend fun loadToday(api: ApiClient, day: LocalDate): TodayData = coroutineScope {
    val summaryCall = async { api.summary(day) }
    val seriesCall = async { api.daySeries(day, "hr,stress") }
    val stepsCall = async { api.buckets("steps", day, day, "1h") }
    val s = summaryCall.await()
    val series = seriesCall.await()
    val zone = ZoneId.of(series.getString("timezone"))
    val sleep = s.getJSONObject("sleep")
    val main = sleep.getJSONArray("sessions").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.lastOrNull { it.getString("kind") == "main" } }
    val health = sleep.getJSONObject("health")
    val stepsByHour = stepsCall.await().getJSONArray("buckets").let { arr ->
        (0 until arr.length()).associate { i ->
            arr.getJSONObject(i).let { Instant.ofEpochMilli(it.getLong("t")).atZone(zone).hour to it.getDouble("sum") }
        }
    }
    TodayData(
        day = day,
        dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        recovery = reading(s.getJSONObject("recovery")),
        readiness = s.getJSONObject("recovery").optJSONObject("readiness")?.getInt("value"),
        strain = reading(s.getJSONObject("strain")),
        sleepTstMin = health.optJSONObject("flags")?.optDouble("tst_min"),
        sleepDeviceScore = main?.takeIf { !it.isNull("device_score") }?.getInt("device_score"),
        sleepDebt = sleep.getJSONObject("debt").let { if (it.has("withheld")) reading(it) else Reading.Value(it.getDouble("minutes"), it) },
        sleepDims = if (health.has("dimensions")) health.getInt("dimensions") else null,
        hypnogram = main?.getJSONArray("stages")?.let { st -> List(st.length()) { i -> st.getJSONArray(i).let { Triple(it.getLong(0), it.getLong(1), it.getInt(2)) } } } ?: emptyList(),
        steps = reading(s.getJSONObject("steps").getJSONObject("steps")),
        distanceM = reading(s.getJSONObject("steps").getJSONObject("distance_m")),
        activeKcal = reading(s.getJSONObject("steps").getJSONObject("active_calories")),
        restingHr = reading(s.getJSONObject("heart").getJSONObject("resting")),
        vo2max = reading(s.getJSONObject("vo2max")),
        stressNote = s.getJSONObject("stress").optJSONObject("withheld")?.getString("message"),
        illness = s.optJSONObject("illness"),
        hr = points(series.getJSONObject("series").optJSONArray("hr")),
        stress = points(series.getJSONObject("series").optJSONArray("stress")),
        sleepSpans = series.getJSONArray("sleep").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Span(o.getLong("start"), o.getLong("end")) } } },
        stepsByHour = stepsByHour,
    )
}
