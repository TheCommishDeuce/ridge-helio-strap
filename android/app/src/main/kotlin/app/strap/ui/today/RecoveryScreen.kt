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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.MonitorHeart
import app.strap.ui.theme.MetricTone
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.ui.components.Bar
import app.strap.ui.components.Gauge
import app.strap.ui.components.InsightCard
import app.strap.ui.components.NoteLine
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.sliderPosition
import app.strap.ui.components.tinted
import strap.science.IllnessCheck
import app.strap.ui.components.hm
import app.strap.ui.components.TileGrid
import app.strap.ui.components.Tile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import app.strap.ui.components.BaselineRow
import app.strap.ui.components.BaselineRows
import app.strap.ui.components.CardFoot
import app.strap.ui.components.CardHeader
import app.strap.ui.components.CardValue
import app.strap.ui.components.Note
import app.strap.ui.components.DayLineChart
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
import app.strap.ui.components.Infos
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SectionLabel
import app.strap.ui.components.SideStat
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
fun RecoveryContent(api: Reader, data: TodayData, onSelectDay: (LocalDate) -> Unit, onSdnn: () -> Unit) {
    val r = LocalRidgeColors.current
    val c = LocalMetricColors.current
    val recovery = data.recovery
    val value = recovery.valueOrNull
    val zone = value?.let(r::zone) ?: c.recovery
    val week = value?.let { v -> data.recoveryWeekBefore(data.day)?.let { v - it } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Gauge(value?.let { "${it.roundToInt()}%" } ?: "—", value?.let(::zoneName) ?: "Recovery", value?.let { (it / 100).toFloat() }, zone, 184.dp, 44.sp, stroke = 6f)
                NoteLine(changeNote(week, "vs week"))
            }
        }
        item {
            val hrv = data.hrv.valueOrNull
            val rhr = data.restingHr.valueOrNull
            TileGrid(listOf(
                { m -> Tile("HRV", hrv?.roundToInt()?.toString() ?: "—", m, "ms", changeNote(hrv?.let { v -> data.hrv.usual?.let { v - it } }, "vs usual", meaningful = 2.0),
                    slider = sliderPosition(hrv, data.hrv.usual)) },
                { m -> Tile("Resting HR", rhr?.roundToInt()?.toString() ?: "—", m, "bpm", changeNote(rhr?.let { v -> data.restingHr.usual?.let { v - it } }, "vs usual", lowerBetter = true, meaningful = 1.0),
                    slider = sliderPosition(rhr, data.restingHr.usual)) },
            ))
        }
        if (recovery is Reading.Withheld) item { RidgeCard { Subtle(recovery.message) } }
        if (value != null) item {
            val title = zoneName(value) + (week?.let { w -> ", " + (changeNote(w, "vs your week")?.text ?: "") } ?: "")
            InsightCard(Icons.Rounded.AutoAwesome, c.recoveryTone, title, recoverySummary(data))
        }
        if (data.factors.isNotEmpty()) item {
            RidgeCard(spacing = 4.dp) {
                CardHeader("What went into it", "vs your last 42 days", icon = Icons.Rounded.Tune, tone = c.recoveryTone)
                data.factors.forEachIndexed { i, f -> PartRow(f, first = i == 0) }
            }
        }
        item { ChargeCard(data) }
        item { IllnessCard(data.illness, data.illnessNight, data.day, data.illnessCheck) }
        item { SectionTitle("This week", trailing = { data.recoveryWeekTo(data.stripEnd)?.let { Subtle("average ${it.roundToInt()}%", style = RidgeType.caption) } }) }
        item { WeekBars(data, onSelectDay) }
        item { SdnnRow(onSdnn) }
    }
}

/** One part of the score: its weight, its 0–100 part score as a bar in its zone, and the value against the usual. */
@Composable
private fun PartRow(f: Factor, first: Boolean) {
    val r = LocalRidgeColors.current
    Column(Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FACTOR_LABELS.getValue(f.key), style = RidgeType.rowTitle)
            Subtle("  · weight ${(f.weight * 100).roundToInt()}%", Modifier.weight(1f), RidgeType.caption)
            Text("${f.sub}", style = RidgeType.footValue)
        }
        Bar(f.sub / 100f, r.zone(f.sub.toDouble()), height = 6.dp)
        val unit = when (f.key) { "hrv" -> " ms"; "rhr" -> " bpm"; "rr" -> " rpm"; else -> "" }
        Subtle("${factorValue(f)}$unit · ${factorUsual(f)}${if (f.key != "sleep" && f.baseline != null) unit else ""}", style = RidgeType.caption)
    }
}

/**
 * The illness check. A flag shows its own sentence and the night that raised it (a flag stays
 * active two days, so it can be older than last night). Under it, always, last night's own
 * check: each limb against its 14 nights (thresholds +2.0 rpm and +0.5 °C, the latter
 * unsourced) — the same numbers as last night's skin-temperature tile — or how many baseline
 * nights it still needs. A prompt to pay attention, never a diagnosis.
 */
@Composable
private fun IllnessCard(flag: String?, night: LocalDate?, day: LocalDate, check: IllnessCheck?) {
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val building = check != null && (check.rrDelta == null || check.tempDelta == null)
    val today = night == null || night == day
    RidgeCard(spacing = 10.dp) {
        CardHeader("Illness check", if (flag != null) flagNight(night, day) else if (building) "${check?.nights} of ${check?.needed} nights" else "last night",
            icon = Icons.Rounded.Thermostat, tone = if (flag != null) c.heartTone else c.recoveryTone)
        if (flag != null) {
            CardValue("Signal", null, if (today) Note("take it easy today", r.bad) else Note("still active", r.warn))
            Text(flag, style = RidgeType.body, color = muted)
        } else if (check != null && !building) {
            CardValue("Clear", null, Note("No illness signal", r.good))
        }
        when {
            check == null -> Subtle("Not computed yet.")
            building -> Subtle("Needs ${check.needed} nights of breathing rate and skin temperature to know your usual.")
            else -> {
                if (flag != null && !today) Subtle("Last night, against the 14 nights before:", style = RidgeType.body)
                CardFoot(listOf(
                    "Breathing" to "${signedFixed(check.rrDelta ?: 0.0, 1)} rpm",
                    "Skin temp" to "${signedFixed(check.tempDelta ?: 0.0, 2)} °C",
                    "Flags at" to "+2.0 / +0.5",
                ))
            }
        }
    }
}

/** The morning in plain words: each part of recovery against your usual. */
private fun recoverySummary(data: TodayData): String {
    fun side(key: String, word: String, higherBetter: Boolean): String? {
        val f = data.factors.firstOrNull { it.key == key } ?: return null
        val b = f.baseline ?: return null
        val rel = (f.value - b) / b
        val how = when {
            kotlin.math.abs(rel) < 0.03 -> "at your usual"
            (rel > 0) == higherBetter -> "better than usual"
            else -> "worse than usual"
        }
        return "$word is $how"
    }
    val parts = listOfNotNull(side("hrv", "HRV", true), side("rhr", "resting heart rate", false), side("rr", "breathing rate", false))
    val sleep = data.factors.firstOrNull { it.key == "sleep" }?.let { f -> f.needMin?.let { "You slept ${hm(f.value)} of your ${hoursLabel(it)} need." } }
    return listOfNotNull(parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }?.plus("."), sleep).joinToString(" ")
}

@Composable
private fun ChargeCard(data: TodayData) {
    val c = data.charge
    val m = LocalMetricColors.current
    RidgeCard(spacing = 12.dp) {
        CardHeader("Charge", c?.let { if (data.day == LocalDate.now()) "now" else "end of day" }, info = Infos.charge(c?.threshold, c?.personal == true),
            icon = Icons.Rounded.BatteryChargingFull, tone = m.chargeTone)
        if (c == null) {
            Subtle(data.chargeNote ?: "Not available.")
            return@RidgeCard
        }
        val color = m.charge
        CardValue("${c.value}", "of 100", c.atWake?.let { Note("$it when you woke", MaterialTheme.colorScheme.onSurfaceVariant) })
        DayLineChart(c.curve, data.curves.window.start, color, 11 * 60_000L, "", dayEnd = data.curves.window.end,
            zone = data.curves.window.zone, shaded = data.curves.sleep, height = 110.dp)
        CardFoot(listOf("Recharged" to "+${c.recharged.roundToInt()}", "Activity" to drained(c.activity), "Stress" to drained(c.stress), "Waking day" to drained(c.living)))
    }
}

/** "−12", or "0" when it rounds to nothing (never "−0"). */
private fun drained(v: Double): String = v.roundToInt().let { if (it == 0) "0" else "−$it" }

/** The strap's own HRV as SDNN, through the day: opens its chart. */
@Composable
private fun SdnnRow(onOpen: () -> Unit) {
    RidgeCard(onClick = onOpen) {
        CardHeader("HRV through the day", "the strap's SDNN", chevron = true, icon = Icons.Rounded.MonitorHeart, tone = LocalMetricColors.current.recoveryTone)
    }
}

@Composable
private fun WeekBars(data: TodayData, onSelectDay: (LocalDate) -> Unit) {
    val r = LocalRidgeColors.current
    val locale = LocalLocale.current.platformLocale
    RidgeCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
