package app.strap.ui.sleep

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
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.DailyBars
import app.strap.ui.components.Hypnogram
import app.strap.ui.components.MetricCard
import app.strap.ui.components.Segmented
import app.strap.ui.components.StatRow
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.components.hm
import app.strap.ui.theme.LocalMetricColors
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private class SleepData(val summary: JSONObject, val history: Map<LocalDate, Double>)

@Composable
fun SleepScreen(api: ApiClient, refreshKey: Any?) {
    var data by remember { mutableStateOf<SleepData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(14) }
    val today = LocalDate.now()
    LaunchedEffect(refreshKey) {
        try {
            val summary = api.summary(today).getJSONObject("sleep")
            val rows = api.daily("sleep_health_score_4dim", today.minusDays(29), today).getJSONObject("metrics").getJSONArray("sleep_health_score_4dim")
            val history = (0 until rows.length()).map { rows.getJSONObject(it) }
                .associate { LocalDate.parse(it.getString("day")) to it.getJSONObject("flags").optDouble("tst_min") }
                .filterValues { !it.isNaN() }
            data = SleepData(summary, history)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return if (error != null) Text(error!!) else CircularProgressIndicator()
    val c = LocalMetricColors.current
    val s = d.summary
    val sessions = s.getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val main = sessions.lastOrNull { it.getString("kind") == "main" }
    val naps = sessions.filter { it.getString("kind") == "nap" }
    val health = s.getJSONObject("health")
    val flags = health.optJSONObject("flags")
    val need = if (s.isNull("need_min")) null else s.getDouble("need_min")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MetricCard("Last night", c.sleep, headline = flags?.optDouble("tst_min")?.takeIf { !it.isNaN() }?.let { hm(it) + " asleep" }) {
                if (main == null) {
                    Subtle("No night recorded that ended today.")
                } else {
                    val st = main.getJSONArray("stages")
                    Hypnogram(List(st.length()) { i -> st.getJSONArray(i).let { Triple(it.getLong(0), it.getLong(1), it.getInt(2)) } }, c.sleep)
                    Subtle("${clockOf(main.getLong("start"))} – ${clockOf(main.getLong("end"))}")
                    if (!main.isNull("device_score")) StatRow("Amazfit score · device estimate", "${main.getInt("device_score")}")
                    StageBreakdown(main.getJSONObject("minutes"), c.sleep, c.stress)
                }
            }
        }
        item { HealthCard(health, flags, s.getJSONObject("regularity"), c.sleep, c.recovery, c.heart) }
        item {
            val debt = s.getJSONObject("debt")
            MetricCard("Sleep debt", c.sleep, headline = if (debt.has("minutes")) hm(debt.getDouble("minutes")) else null) {
                if (debt.has("withheld")) {
                    Subtle(debt.getJSONObject("withheld").getString("message"))
                } else {
                    val f = debt.getJSONObject("flags")
                    Subtle("Over the last ${f.getInt("window_nights")} nights: shortfall against your need, with extra sleep repaying it at half value.")
                    need?.let { StatRow("Your need (NSF 2015, by age)", hm(it)) }
                    StatRow("Average asleep", hm(f.getDouble("avg_tst_min")))
                    StatRow("Nights below need", "${f.getInt("nights_below")} of ${f.getInt("nights")}")
                }
            }
        }
        item {
            MetricCard("History", c.sleep) {
                Segmented(listOf(7, 14, 30), days, { "$it nights" }) { days = it }
                DailyBars(d.history, today.minusDays(days - 1L), today, c.sleep, { hm(it) }, target = need)
                need?.let { Subtle("Dashed line: your need, ${hm(it)}") }
            }
        }
        if (naps.isNotEmpty()) item {
            MetricCard("Naps", c.sleep) {
                naps.forEach { n -> StatRow("${clockOf(n.getLong("start"))} – ${clockOf(n.getLong("end"))}", hm((n.getLong("end") - n.getLong("start")) / 60_000.0)) }
            }
        }
        item { Subtle("Stages come from the strap's own staging; wrist sleep staging agrees with lab sleep studies only moderately (wearable_sleep_stage_validity).") }
    }
}

@Composable
private fun StageBreakdown(minutes: JSONObject, sleep: Color, awake: Color) {
    // Same shades as the hypnogram, so the legend reads the chart.
    val parts = listOf(Triple("Deep", "deep", sleep), Triple("Light", "light", sleep.copy(alpha = 0.45f)),
        Triple("REM", "rem", sleep.copy(alpha = 0.7f)), Triple("Awake", "awake", awake))
        .map { (label, key, col) -> Triple(label, if (minutes.isNull(key)) 0.0 else minutes.getDouble(key), col) }
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
    parts.forEach { (label, m, col) -> StatRow(label, "${hm(m)} · ${(100 * m / total).roundToInt()}%", "■", col) }
}

/** The four dimensions, each shown with its own rule — never folded into one "score". */
@Composable
private fun HealthCard(health: JSONObject, flags: JSONObject?, regularity: JSONObject, accent: Color, pass: Color, fail: Color) {
    MetricCard("Sleep health", accent, headline = if (health.has("dimensions")) "${health.getInt("dimensions")} of 4 dimensions met" else null) {
        if (flags == null) {
            Subtle(health.optJSONObject("withheld")?.getString("message") ?: "Not computed yet.")
            return@MetricCard
        }
        fun mark(ok: Int) = if (ok == 1) "✓" to pass else "✕" to fail
        val midpoint = flags.optString("midpoint_local").takeIf { it.isNotEmpty() }?.let { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm")) }
        val rows = listOf(
            Triple("Duration 7–9 h (NSF 2015)", hm(flags.getDouble("tst_min")), flags.getInt("duration")),
            Triple("Efficiency ≥ 85% (AASM)", "${flags.getDouble("efficiency_pct")}%", flags.getInt("efficiency")),
            Triple("Midpoint 02:00–04:00 (Buysse 2014)", midpoint ?: "—", flags.getInt("timing")),
            Triple("Regularity SRI ≥ 70 (Phillips 2017)", if (regularity.has("sri")) "%.0f".format(regularity.getDouble("sri")) else "—", flags.getInt("regularity")),
        )
        rows.forEach { (label, value, ok) -> mark(ok).let { (m, col) -> StatRow(label, value, m, col) } }
        if (regularity.has("withheld")) Subtle(regularity.getJSONObject("withheld").getString("message"))
        Subtle("No single sleep score is validated, so each dimension stands on its own (no_validated_sleep_score).")
    }
}
