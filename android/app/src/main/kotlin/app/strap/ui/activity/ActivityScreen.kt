package app.strap.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.ui.Loading
import app.strap.ui.components.Bar
import app.strap.ui.components.CardHeader
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.DailyBars
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
import app.strap.ui.components.IconCircle
import app.strap.ui.components.InfoButton
import app.strap.ui.components.Infos
import app.strap.ui.components.ListRow
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SideStat
import app.strap.ui.components.Subtle
import app.strap.ui.components.changeNote
import app.strap.ui.components.clockOf
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.today.workoutLine
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
    var days by rememberSaveable { mutableIntStateOf(7) }
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
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    fun series(metric: String): Map<LocalDate, Double> = d.daily.getJSONArray(metric).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associate { LocalDate.parse(it.getString("day")) to it.getDouble("value") }
    }
    val first = today.minusDays(days - 1L)
    val strain = d.summary.getJSONObject("strain")
    val readiness = d.summary.getJSONObject("recovery").optJSONObject("readiness")
    val mvpa = series("mvpa_min")
    val weekActive = (0..6).mapNotNull { mvpa[today.minusDays(it.toLong())] }.takeIf { it.isNotEmpty() }?.sum()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val load = if (strain.has("cardio_load")) strain.getDouble("cardio_load") else null
            val value = if (strain.has("value") && !strain.isNull("value")) strain.getDouble("value") else null
            HeroRow(
                left = {
                    SideStat(load?.roundToInt()?.toString() ?: "—", "Load · TRIMP",
                        changeNote(load?.let { l -> readiness?.optDouble("typical")?.takeIf { !it.isNaN() }?.let { l - it } }, "vs typical", neutral = true), it)
                },
                right = {
                    SideStat(weekActive?.roundToInt()?.toString() ?: "—", "Active min",
                        Note("goal 150 / week", if ((weekActive ?: 0.0) >= 150) r.zoneGreen else MaterialTheme.colorScheme.onSurfaceVariant), it)
                },
            ) {
                HeroGauge(value?.let { "%.1f".format(it) } ?: "—", "of 21", value?.let { (it / 21).toFloat() }, c.strain, "Strain", null)
            }
        }
        item { ConnectedButtons(listOf(7, 30), days, { if (it == 7) "Week" else "Month" }) { days = it } }
        item {
            val steps = series("steps_total")
            val inRange = steps.filterKeys { it >= first }
            RidgeCard {
                CardHeader("Steps")
                Text("%,d".format(inRange.values.sum().roundToInt()), style = RidgeType.bigNumber)
                Subtle(
                    (if (days == 7) "this week" else "last 30 days") +
                        (inRange.values.takeIf { it.isNotEmpty() }?.average()?.let { " · %,d a day on average".format(it.roundToInt()) } ?: ""),
                )
                DailyBars(steps, first, today, c.steps, { "%,d steps".format(it.roundToInt()) }, highlightLast = true, height = 130.dp)
            }
        }
        item {
            val inRange = mvpa.filterKeys { it >= first }.values
            RidgeCard {
                CardHeader("Active minutes", info = Infos.active)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${inRange.sum().roundToInt()}", style = RidgeType.cardNumber)
                    Subtle(if (days == 7) "  of 150 min this week" else "  min in 30 days", Modifier.padding(bottom = 5.dp))
                }
                DailyBars(mvpa, first, today, c.recovery, { "${it.roundToInt()} min" }, highlightLast = true, height = 90.dp)
            }
        }
        item {
            val load = series("cardio_load")
            RidgeCard {
                CardHeader("Load", info = Infos.load)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(load[today]?.roundToInt()?.toString() ?: "—", style = RidgeType.cardNumber)
                    Subtle("  TRIMP today", Modifier.padding(bottom = 5.dp))
                }
                DailyBars(load, first, today, c.strain, { "%.0f TRIMP".format(it) }, highlightLast = true, height = 90.dp)
            }
        }
        item { ZonesCard(strain) }
        item { GroupHeader("Workouts", trailing = "${d.workouts.length()} in 30 days") }
        item { Workouts(d.workouts) }
        item {
            val v = d.summary.getJSONObject("vo2max")
            RidgeCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Rounded.Air, c.strainTone.container, c.strainTone.onContainer)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("VO₂max", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (v.has("value")) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("%.1f".format(v.getDouble("value")), style = RidgeType.rowValue)
                                Subtle(" ml/kg/min", Modifier.padding(bottom = 2.dp), RidgeType.caption)
                            }
                        } else {
                            Subtle(v.optJSONObject("withheld")?.getString("message") ?: "—")
                        }
                    }
                    InfoButton(Infos.vo2)
                }
            }
        }
    }
}

@Composable
private fun ZonesCard(s: JSONObject) {
    val c = LocalMetricColors.current
    val f = s.optJSONObject("flags")
    RidgeCard {
        CardHeader("Heart-rate zones today", info = Infos.strain(f?.optInt("hrmax")?.takeIf { it > 0 }, f?.optInt("rhr")?.takeIf { it > 0 }))
        val zones = f?.optJSONArray("zone_min")
        if (s.has("withheld") || zones == null) {
            Subtle(s.optJSONObject("withheld")?.getString("message") ?: "Not computed yet.")
            return@RidgeCard
        }
        val bounds = listOf("50–60%", "60–70%", "70–80%", "80–90%", "90%+")
        val max = (0 until zones.length()).maxOf { zones.getInt(it) }.coerceAtLeast(1)
        bounds.forEachIndexed { i, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(64.dp)) {
                    Text("Zone ${i + 1}", style = RidgeType.caption.copy(fontWeight = RidgeType.label.fontWeight))
                    Text(b, style = RidgeType.change, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Bar(zones.getInt(i) / max.toFloat(), c.heart.copy(alpha = 0.45f + 0.137f * i), Modifier.weight(1f), height = 8.dp)
                Text("${zones.getInt(i)} min", style = RidgeType.label, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
            }
        }
    }
}

@Composable
private fun Workouts(workouts: JSONArray) {
    val tone = LocalMetricColors.current.heartTone
    if (workouts.length() == 0) {
        RidgeCard { Subtle("No workouts recorded by the strap in the last 30 days.") }
        return
    }
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM")
    Grouped((0 until minOf(workouts.length(), 8)).map { workouts.getJSONObject(it) }) { w, shape ->
        val day = Instant.ofEpochMilli(w.getLong("start")).atZone(ZoneId.systemDefault()).format(fmt)
        ListRow(shape, "$day · ${clockOf(w.getLong("start"))}", workoutLine(w),
            leading = { IconCircle(Icons.AutoMirrored.Rounded.DirectionsRun, tone.container, tone.onContainer) })
    }
}
