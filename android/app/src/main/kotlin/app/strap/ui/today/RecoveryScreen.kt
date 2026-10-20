package app.strap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import app.strap.ui.components.BaselineRow
import app.strap.ui.components.BaselineRows
import app.strap.ui.components.CardHeader
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
import app.strap.ui.components.Infos
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionLabel
import app.strap.ui.components.SideStat
import app.strap.ui.components.StatRow
import app.strap.ui.components.Subtle
import app.strap.ui.components.changeNote
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Recovery (pushed from Today): the score with its parts, Charge, and the week. */
@Composable
fun RecoveryContent(data: TodayData, onSelectDay: (LocalDate) -> Unit, onSdnn: () -> Unit) {
    val r = LocalRidgeColors.current
    val recovery = data.recovery
    val value = recovery.valueOrNull
    val zone = value?.let(r::zone) ?: LocalMetricColors.current.recovery
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val hrv = data.hrv.valueOrNull
            val rhr = data.restingHr.valueOrNull
            HeroRow(
                left = { SideStat(hrv?.roundToInt()?.toString() ?: "—", "HRV · ms", changeNote(hrv?.let { v -> data.hrv.usual?.let { v - it } }, "vs usual"), it) },
                right = { SideStat(rhr?.roundToInt()?.toString() ?: "—", "Resting HR", changeNote(rhr?.let { v -> data.restingHr.usual?.let { v - it } }, "vs usual", lowerBetter = true), it) },
            ) {
                HeroGauge(value?.let { "${it.roundToInt()}%" } ?: "—", "ready", value?.let { (it / 100).toFloat() }, zone,
                    value?.let(::zoneName) ?: "Recovery", changeNote(value?.let { v -> data.recoveryWeekBefore(data.day)?.let { v - it } }, "vs week"))
            }
        }
        if (recovery is Reading.Withheld) item { RidgeCard { Subtle(recovery.message) } }
        item { ChargeCard(data) }
        item { SdnnRow(onSdnn) }
        if (data.factors.isNotEmpty()) {
            item { SectionLabel("What went into it", "vs your last 42 days") }
            item {
                RidgeCard(spacing = 0.dp, padding = 16.dp) {
                    BaselineRows(data.factors.map { f ->
                        {
                            BaselineRow(FACTOR_LABELS.getValue(f.key), factorUsual(f), factorValue(f), factorTrackOf(f),
                                chip = "weight ${(f.weight * 100).roundToInt()}%", footer = factorUsual(f) to "part score ${f.sub}")
                        }
                    })
                }
            }
        }
        item { SectionLabel("This week", data.recoveryWeekTo(data.stripEnd)?.let { "average ${it.roundToInt()}%" }) }
        item { WeekBars(data, onSelectDay) }
    }
}

@Composable
private fun ChargeCard(data: TodayData) {
    val c = data.charge
    RidgeCard(spacing = 8.dp) {
        CardHeader("Charge", c?.let { if (data.day == LocalDate.now()) "now" else "end of day" }, info = Infos.charge(c?.threshold, c?.personal == true))
        if (c == null) {
            Subtle(data.chargeNote ?: "Not available.")
            return@RidgeCard
        }
        val color = LocalRidgeColors.current.zone(c.value.toDouble())
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${c.value}", style = RidgeType.cardNumber)
            Subtle(c.atWake?.let { "  of 100 · ${it} when you woke" } ?: "  of 100", Modifier.padding(bottom = 5.dp))
        }
        DayLineChart(c.curve, data.curves.window.start, color, 11 * 60_000L, "", dayEnd = data.curves.window.end,
            zone = data.curves.window.zone, shaded = data.curves.sleep, height = 110.dp)
        StatRow("Recharged", "+${c.recharged.roundToInt()}")
        StatRow("Drained by activity", drained(c.activity))
        StatRow("Drained by stress", drained(c.stress))
        StatRow("Drained by the waking day", drained(c.living))
    }
}

/** "−12", or "0" when it rounds to nothing (never "−0"). */
private fun drained(v: Double): String = v.roundToInt().let { if (it == 0) "0" else "−$it" }

/** The strap's own HRV as SDNN, through the day: opens its chart. */
@Composable
private fun SdnnRow(onOpen: () -> Unit) {
    RidgeCard(onClick = onOpen) {
        CardHeader("HRV through the day", "the strap's SDNN", chevron = true)
    }
}

@Composable
private fun WeekBars(data: TodayData, onSelectDay: (LocalDate) -> Unit) {
    val r = LocalRidgeColors.current
    val locale = LocalLocale.current.platformLocale
    RidgeCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            data.stripDays.forEach { d ->
                val v = data.recoveryByDay[d]
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSelectDay(d) }
                        .alpha(if (d == data.day) 1f else 0.45f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(v?.roundToInt()?.toString() ?: "—", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f, fill = false).padding(vertical = 4.dp)) {
                        if (v != null) Box(
                            Modifier.fillMaxWidth(0.7f).widthIn(max = 28.dp).height((120 * v / 100).dp.coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp)).background(r.zone(v)),
                        )
                    }
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
