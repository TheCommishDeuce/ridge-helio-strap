package app.strap.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.components.CardHeader
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.DailyBars
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
import app.strap.ui.components.Hypnogram
import app.strap.ui.components.IconCircle
import app.strap.ui.components.Infos
import app.strap.ui.components.ListRow
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SideStat
import app.strap.ui.components.StatRow
import app.strap.ui.components.Subtle
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.components.hm
import app.strap.ui.components.stageShades
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.Loading
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
    var days by rememberSaveable { mutableIntStateOf(14) }
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
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val s = d.summary
    val sessions = s.getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val main = sessions.lastOrNull { it.getString("kind") == "main" }
    val naps = sessions.filter { it.getString("kind") == "nap" }
    val health = s.getJSONObject("health")
    val flags = health.optJSONObject("flags")
    val need = if (s.isNull("need_min")) null else s.getDouble("need_min")
    val tst = flags?.optDouble("tst_min")?.takeIf { !it.isNaN() }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val efficiency = flags?.optDouble("efficiency_pct")?.takeIf { !it.isNaN() }
            HeroRow(
                left = { SideStat(main?.takeIf { !it.isNull("device_score") }?.getInt("device_score")?.toString() ?: "—", "Amazfit", null, it) },
                right = {
                    SideStat(efficiency?.let { "${it.roundToInt()}%" } ?: "—", "Efficiency",
                        efficiency?.let { Note("target ≥ 85%", if (it >= 85) r.zoneGreen else r.zoneRed) }, it)
                },
            ) {
                HeroGauge(
                    tst?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).roundToInt().coerceAtMost(59)) } ?: "—",
                    need?.let { "of ${needLabel(it)} need" },
                    if (tst != null && need != null) (tst / need).toFloat() else null,
                    c.sleep, "Asleep",
                    changeNote(if (tst != null && need != null) tst - need else null, "vs need", magnitude = { "${it.roundToInt()} min" }),
                )
            }
        }
        item {
            RidgeCard {
                if (main == null) {
                    CardHeader("Stages")
                    Subtle("No night recorded that ended today.")
                } else {
                    val start = main.getLong("start")
                    val end = main.getLong("end")
                    CardHeader("Stages", "${clockOf(start)} – ${clockOf(end)} · in bed ${hm((end - start) / 60_000.0)}")
                    val st = main.getJSONArray("stages")
                    Hypnogram(List(st.length()) { i -> st.getJSONArray(i).let { Triple(it.getLong(0), it.getLong(1), it.getInt(2)) } })
                    StageTiles(main.getJSONObject("minutes"))
                }
            }
        }
        item { HealthGroup(health, flags, s.getJSONObject("regularity")) }
        item {
            val debt = s.getJSONObject("debt")
            val f = debt.optJSONObject("flags")
            RidgeCard {
                CardHeader("Sleep debt", info = Infos.debt(f?.optInt("window_nights", 14) ?: 14))
                if (debt.has("withheld")) {
                    Subtle(debt.getJSONObject("withheld").getString("message"))
                } else if (f != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(hm(debt.getDouble("minutes")), style = RidgeType.cardNumber)
                        Subtle("  over ${f.getInt("window_nights")} nights", Modifier.padding(bottom = 6.dp))
                    }
                    need?.let { StatRow("Your need", hm(it)) }
                    StatRow("Average asleep", hm(f.getDouble("avg_tst_min")))
                    StatRow("Nights below need", "${f.getInt("nights_below")} of ${f.getInt("nights")}")
                }
            }
        }
        item {
            RidgeCard(spacing = 14.dp) {
                CardHeader("History")
                ConnectedButtons(listOf(7, 14, 30), days, { "$it nights" }) { days = it }
                DailyBars(d.history, today.minusDays(days - 1L), today, c.sleep, { hm(it) }, target = need,
                    dim = need?.let { n -> { v -> v < n } }, height = 130.dp)
                need?.let { Subtle("- - Your need, ${hm(it)}", style = RidgeType.caption) }
            }
        }
        if (naps.isNotEmpty()) {
            item { GroupHeader("Naps") }
            item {
                Grouped(naps) { n, shape ->
                    ListRow(shape, "${clockOf(n.getLong("start"))} – ${clockOf(n.getLong("end"))}",
                        leading = { IconCircle(Icons.Rounded.Hotel, c.sleepTone.container, c.sleepTone.onContainer) },
                        trailing = { Text(hm((n.getLong("end") - n.getLong("start")) / 60_000.0), style = RidgeType.label) })
                }
            }
        }
    }
}

/** 480 → "8h", 450 → "7h 30m". */
private fun needLabel(minutes: Double): String {
    val m = minutes.roundToInt()
    return if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}

@Composable
private fun StageTiles(minutes: JSONObject) {
    val c = LocalMetricColors.current
    val shades = stageShades(c.sleep, c.stress, MaterialTheme.colorScheme.onSurface)
    val parts = listOf(Triple("Deep", "deep", 5), Triple("Light", "light", 4), Triple("REM", "rem", 8), Triple("Awake", "awake", 7))
        .map { (label, key, code) -> Triple(label, if (minutes.isNull(key)) 0.0 else minutes.getDouble(key), shades.getValue(code)) }
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, m, color) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(LocalRidgeColors.current.surface3).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                            Text("  $label", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(hm(m), style = RidgeType.rowValue.copy(fontSize = RidgeType.cardTitle.fontSize * 1.2f), modifier = Modifier.weight(1f))
                            Text("${(100 * m / total).roundToInt()}%", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The four dimensions, each shown with its own rule — never folded into one "score". */
@Composable
private fun HealthGroup(health: JSONObject, flags: JSONObject?, regularity: JSONObject) {
    val r = LocalRidgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader("Sleep health", if (health.has("dimensions")) "${health.getInt("dimensions")} of 4 dimensions met" else null, Infos.health)
        if (flags == null) {
            RidgeCard { Subtle(health.optJSONObject("withheld")?.getString("message") ?: "Not computed yet.") }
            return
        }
        val midpoint = flags.optString("midpoint_local").takeIf { it.isNotEmpty() }?.let { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm")) }
        val rows = listOf(
            Row4("Duration", "7–9 h", hm(flags.getDouble("tst_min")), flags.getInt("duration")),
            Row4("Efficiency", "≥ 85%", "${flags.getDouble("efficiency_pct").roundToInt()}%", flags.getInt("efficiency")),
            Row4("Timing", "Midpoint 02:00–04:00", midpoint ?: "—", flags.getInt("timing")),
            Row4("Regularity", "SRI ≥ 70", if (regularity.has("sri")) "%.0f".format(regularity.getDouble("sri")) else "—", flags.getInt("regularity")),
        )
        Grouped(rows) { row, shape ->
            ListRow(shape, row.name, row.rule,
                leading = {
                    if (row.ok == 1) Icon(Icons.Rounded.CheckCircle, "Met", tint = r.zoneGreen, modifier = Modifier.size(24.dp))
                    else Icon(Icons.Rounded.Cancel, "Not met", tint = r.zoneRed, modifier = Modifier.size(24.dp))
                },
                trailing = { Text(row.value, style = RidgeType.label) })
        }
        if (regularity.has("withheld")) Subtle(regularity.getJSONObject("withheld").getString("message"), Modifier.padding(horizontal = 4.dp), RidgeType.caption)
    }
}

private data class Row4(val name: String, val rule: String, val value: String, val ok: Int)
