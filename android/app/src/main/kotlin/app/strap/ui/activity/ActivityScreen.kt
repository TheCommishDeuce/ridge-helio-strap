package app.strap.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.Bar
import app.strap.ui.components.DailyBars
import app.strap.ui.components.MetricCard
import app.strap.ui.components.Segmented
import app.strap.ui.components.StatRow
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private class ActivityData(val summary: JSONObject, val daily: JSONObject, val workouts: JSONArray)

@Composable
fun ActivityScreen(api: ApiClient, refreshKey: Any?) {
    var data by remember { mutableStateOf<ActivityData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(7) }
    val today = LocalDate.now()
    LaunchedEffect(refreshKey) {
        try {
            data = ActivityData(
                api.summary(today),
                api.daily("steps_total,cardio_load,mvpa_min", today.minusDays(29), today).getJSONObject("metrics"),
                api.workouts(today.minusDays(29), today),
            )
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return if (error != null) Text(error!!) else CircularProgressIndicator()
    val c = LocalMetricColors.current
    fun series(metric: String): Map<LocalDate, Double> = d.daily.getJSONArray(metric).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associate { LocalDate.parse(it.getString("day")) to it.getDouble("value") }
    }
    val first = today.minusDays(days - 1L)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Order agreed with the owner: steps, recovery, active minutes, load, strain, workouts, VO2max.
        item { Segmented(listOf(7, 30), days, { if (it == 7) "Week" else "Month" }) { days = it } }
        item {
            MetricCard("Steps", c.steps) {
                DailyBars(series("steps_total"), first, today, c.steps, { "%,d steps".format(it.roundToInt()) })
            }
        }
        item { RecoveryCard(d.summary.getJSONObject("recovery"), c.recovery) }
        item {
            MetricCard("Active minutes", c.recovery) {
                Subtle("Moderate + 2 × vigorous minutes from step cadence (WHO counts a vigorous minute double); guideline 150 a week.")
                DailyBars(series("mvpa_min"), first, today, c.recovery, { "${it.roundToInt()} min" })
            }
        }
        item {
            MetricCard("Load", c.strain) {
                Subtle("Daily cardio load (Banister TRIMP over waking minutes) — the number strain is scaled from.")
                DailyBars(series("cardio_load"), first, today, c.strain, { "%.0f TRIMP".format(it) })
            }
        }
        item { StrainCard(d.summary.getJSONObject("strain"), c.strain, c.heart) }
        item { WorkoutsCard(d.workouts, c.heart) }
        item {
            val v = d.summary.getJSONObject("vo2max")
            MetricCard("VO₂max", c.strain, headline = if (v.has("value")) "%.1f ml/kg/min".format(v.getDouble("value")) else null) {
                Subtle(v.optJSONObject("withheld")?.getString("message") ?: "Estimated from your profile, activity answer and resting heart rate (Jurca 2005).")
            }
        }
    }
}

/** The recovery score always shown WITH its parts — it is an evidence-weighted estimate, not a validated formula. */
@Composable
private fun RecoveryCard(r: JSONObject, accent: Color) {
    MetricCard("Recovery", accent, headline = if (r.has("value")) "${r.getInt("value")}%" else null) {
        if (r.has("withheld")) {
            Subtle(r.getJSONObject("withheld").getString("message"))
            return@MetricCard
        }
        r.optJSONObject("readiness")?.let { Subtle("Now ${it.getInt("value")}% after today's load (${it.getDouble("load").roundToInt()} vs your typical ${it.getDouble("typical").roundToInt()}).") }
        val f = r.getJSONObject("flags")
        val factors = f.getJSONObject("factors")
        val weights = f.getJSONObject("weights")
        val names = mapOf("hrv" to "HRV overnight", "rhr" to "Resting heart rate", "rr" to "Breathing rate", "sleep" to "Sleep vs your need")
        names.forEach { (key, label) ->
            val x = factors.optJSONObject(key) ?: return@forEach
            val detail = if (key == "sleep") "${x.getInt("tst_min")} of ${x.getInt("need_min")} min"
            else "${x.getDouble("value")} vs usual ${x.getDouble("baseline")}"
            StatRow("$label · weight ${(weights.getDouble(key) * 100).roundToInt()}%", "${x.getInt("sub")}")
            Bar(x.getInt("sub") / 100f, accent)
            Subtle(detail)
        }
        Subtle("Parts are compared with your own last 42 days. The weights reflect how strong the evidence is for each marker; no study fits them (recovery_readiness).")
    }
}

@Composable
private fun StrainCard(s: JSONObject, accent: Color, zoneColor: Color) {
    MetricCard("Strain", accent, headline = if (s.has("value") && !s.isNull("value")) "%.1f of 21".format(s.getDouble("value")) else null) {
        if (s.has("withheld")) {
            Subtle(s.getJSONObject("withheld").getString("message"))
            return@MetricCard
        }
        Subtle("Today's load on your own scale: 21 is your hardest day of the last 90 (their 95th percentile).")
        val f = s.getJSONObject("flags")
        val zones = f.getJSONArray("zone_min")
        val bounds = listOf("50–60%", "60–70%", "70–80%", "80–90%", "90%+")
        val max = (0 until zones.length()).maxOf { zones.getInt(it) }.coerceAtLeast(1)
        bounds.forEachIndexed { i, b ->
            StatRow("Zone ${i + 1} · $b of max HR", "${zones.getInt(i)} min")
            Bar(zones.getInt(i) / max.toFloat(), zoneColor.copy(alpha = 0.4f + 0.12f * i))
        }
        Subtle("Max HR ${f.getInt("hrmax")} (Tanaka 208 − 0.7 × age), resting ${f.getInt("rhr")}.")
    }
}

@Composable
private fun WorkoutsCard(workouts: JSONArray, accent: Color) {
    MetricCard("Workouts", accent, headline = "${workouts.length()} in 30 days") {
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM")
        (0 until minOf(workouts.length(), 8)).map { workouts.getJSONObject(it) }.forEach { w ->
            val day = Instant.ofEpochMilli(w.getLong("start")).atZone(ZoneId.systemDefault()).format(fmt)
            val hr = if (w.isNull("avg_hr")) "" else " · avg ${w.getInt("avg_hr")} bpm"
            val kcal = if (w.isNull("calories")) "" else " · ${w.getInt("calories")} kcal"
            StatRow("$day ${clockOf(w.getLong("start"))}", "${w.getInt("duration_s") / 60} min$kcal$hr")
        }
        if (workouts.length() == 0) Subtle("No workouts recorded by the strap in the last 30 days.")
    }
}
