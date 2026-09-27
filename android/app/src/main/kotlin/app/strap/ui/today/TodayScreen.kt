package app.strap.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strap.ui.components.DayBars
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.Hypnogram
import app.strap.ui.components.MetricCard
import app.strap.ui.components.MetricRing
import app.strap.ui.components.Subtle
import app.strap.ui.components.hm
import app.strap.ui.theme.LocalMetricColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)


/** Where Today's rings and cards lead. */
class TodayNav(val sleep: () -> Unit, val activity: () -> Unit, val heart: () -> Unit, val stress: () -> Unit)

/** Today: three rings (recovery · strain · sleep), then the day's curves and cards. */
@Composable
fun TodayContent(data: TodayData, nav: TodayNav, modifier: Modifier = Modifier) {
    val c = LocalMetricColors.current
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Rings(data, nav) }
        data.illness?.let { item { IllnessBanner(it.getString("framing")) } }
        item {
            MetricCard("Heart rate", c.heart, onClick = nav.heart, headline = (data.restingHr as? Reading.Value)?.let { "${it.value.roundToInt()} bpm resting" }) {
                data.hr.maxByOrNull { it.v }?.let { Subtle("Peak ${it.v.roundToInt()} bpm at ${clock(it.t)} · low ${data.hr.minOf { p -> p.v }.roundToInt()}") }
                if (data.hr.isEmpty()) Subtle("No heart-rate readings for this day yet.")
                else DayLineChart(data.hr, data.dayStartMs, c.heart, maxGapMs = 3 * 60_000L, unit = "bpm", shaded = data.sleepSpans)
            }
        }
        item {
            MetricCard("Stress", c.stress, onClick = nav.stress, headline = data.stress.takeIf { it.isNotEmpty() }?.let { s -> "${s.map { it.v }.average().roundToInt()} average" }) {
                data.stress.maxByOrNull { it.v }?.let { Subtle("Highest ${it.v.roundToInt()} at ${clock(it.t)} · the strap's own 0–100 index") }
                if (data.stress.isEmpty()) Subtle(data.stressNote ?: "No stress readings for this day yet.")
                else DayLineChart(data.stress, data.dayStartMs, c.stress, maxGapMs = 11 * 60_000L, unit = "", shaded = data.sleepSpans)
            }
        }
        item {
            MetricCard("Steps", c.steps, onClick = nav.activity, headline = (data.steps as? Reading.Value)?.let { "%,d".format(it.value.roundToInt()) }) {
                (data.steps as? Reading.Withheld)?.let { Subtle(it.message) }
                val extra = listOfNotNull(
                    (data.distanceM as? Reading.Value)?.let { "%.1f km".format(it.value / 1000) },
                    (data.activeKcal as? Reading.Value)?.let { "${it.value.roundToInt()} active kcal" },
                )
                if (extra.isNotEmpty()) Subtle(extra.joinToString(" · "))
                if (data.stepsByHour.isNotEmpty()) DayBars(data.stepsByHour, c.steps, unit = "steps")
            }
        }
        item {
            MetricCard(if (data.day == LocalDate.now()) "Last night" else "Night before", c.sleep, onClick = nav.sleep, headline = data.sleepTstMin?.let { hm(it) + " asleep" }) {
                if (data.hypnogram.isEmpty()) Subtle("No night recorded for this day.")
                else {
                    Hypnogram(data.hypnogram, c.sleep)
                    Subtle("${clock(data.hypnogram.first().first)} – ${clock(data.hypnogram.last().second)}")
                }
                val debt = data.sleepDebt
                Subtle(
                    listOfNotNull(
                        data.sleepDims?.let { "$it of 4 sleep-health dimensions met" },
                        when (debt) { is Reading.Value -> "debt ${hm(debt.value)} over 14 nights"; is Reading.Withheld -> debt.message },
                    ).joinToString(" · "),
                )
            }
        }
        item {
            MetricCard("VO₂max", c.strain, onClick = null, headline = (data.vo2max as? Reading.Value)?.let { "%.1f ml/kg/min".format(it.value) }) {
                when (val v = data.vo2max) {
                    is Reading.Value -> Subtle("Estimated from your profile and resting heart rate (Jurca model)")
                    is Reading.Withheld -> Subtle(v.message)
                }
            }
        }
    }
}

@Composable
private fun Rings(data: TodayData, nav: TodayNav) {
    val c = LocalMetricColors.current
    val recovery = data.recovery as? Reading.Value
    val strain = data.strain as? Reading.Value
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        // "now X%" is recovery reduced by today's strain so far (live readiness).
        MetricRing("Recovery", recovery?.let { "${it.value.roundToInt()}%" } ?: "—", recovery?.let { (it.value / 100).toFloat() }, c.recovery,
            caption = data.readiness?.let { "now $it%" }, onClick = nav.activity)
        MetricRing("Strain", strain?.let { "%.1f".format(it.value) } ?: "—", strain?.let { (it.value / 21).toFloat() }, c.strain,
            caption = "of 21", onClick = nav.activity)
        // The strap's own 0-100 score, named as the strap's (we compute no composite sleep score).
        MetricRing("Sleep", data.sleepDeviceScore?.toString() ?: "—", data.sleepDeviceScore?.let { it / 100f }, c.sleep,
            caption = "Amazfit score", onClick = nav.sleep)
    }
}

@Composable
private fun IllnessBanner(framing: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = LocalMetricColors.current.stress.copy(alpha = 0.18f))) {
        // The server's fixed, reviewed sentence (names the baseline, says "not a diagnosis").
        Text(framing, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
