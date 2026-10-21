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
import app.strap.api.Reader
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import strap.science.Breathing
import strap.science.Card
import strap.science.Night
import strap.science.SleepCard
import strap.science.SleepHealth
import strap.science.shown
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private class SleepData(val sleep: SleepCard, val history: Map<LocalDate, Double>)

@Composable
fun SleepScreen(api: Reader, refreshKey: Any?) {
    var data by remember { mutableStateOf<SleepData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableIntStateOf(14) }
    val today = LocalDate.now()
    LaunchedEffect(refreshKey) {
        try {
            val sleep = api.summary(today).sleep
            val history = api.daily("sleep_health_score_4dim", today.minusDays(29), today)
                .mapNotNull { r -> r.flags["tst_min"]?.jsonPrimitive?.doubleOrNull?.let { r.day to it } }.toMap()
            data = SleepData(sleep, history)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val d = data ?: return Loading(error)
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    val s = d.sleep
    val main = s.main
    val naps = s.nights.filter { !it.isMain }
    val health = s.health.shown
    val need = s.needMin
    val tst = health?.tstMin
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val efficiency = health?.efficiencyPct
            HeroRow(
                left = { SideStat(main?.deviceScore?.toString() ?: "—", "Amazfit", null, it) },
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
                    val start = main.start.toEpochMilli()
                    val end = main.end.toEpochMilli()
                    CardHeader("Stages", "${clockOf(start)} – ${clockOf(end)} · in bed ${hm((end - start) / 60_000.0)}")
                    Hypnogram(main.stages.map { Triple(it.startMs, it.endMs, it.code) })
                    StageTiles(main)
                }
            }
        }
        item { HealthGroup(s.health, s.regularity) }
        item { BreathingCard(s.breathing) }
        item {
            val debt = s.debt
            RidgeCard {
                CardHeader("Sleep debt", info = Infos.debt(debt.shown?.windowNights ?: 14))
                when (debt) {
                    is Card.Withheld -> Subtle(debt.message)
                    is Card.Shown -> {
                        val f = debt.value
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(hm(f.minutes), style = RidgeType.cardNumber)
                            Subtle("  over ${f.windowNights} nights", Modifier.padding(bottom = 6.dp))
                        }
                        need?.let { StatRow("Your need", hm(it)) }
                        StatRow("Average asleep", hm(f.avgTstMin))
                        StatRow("Nights below need", "${f.nightsBelow} of ${f.nights}")
                    }
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
                    ListRow(shape, "${clockOf(n.start.toEpochMilli())} – ${clockOf(n.end.toEpochMilli())}",
                        leading = { IconCircle(Icons.Rounded.Hotel, c.sleepTone.container, c.sleepTone.onContainer) },
                        trailing = { Text(hm(java.time.Duration.between(n.start, n.end).toMillis() / 60_000.0), style = RidgeType.label) })
                }
            }
        }
    }
}

/** The strap's sleep-apnea summary: blood-oxygen drops per hour measured, against your usual. No severity words. */
@Composable
private fun BreathingCard(card: Card<Breathing>) {
    RidgeCard {
        CardHeader("Breathing disturbances", "the strap's", info = Infos.breathing)
        val b = when (card) {
            is Card.Withheld -> return@RidgeCard Subtle(card.message)
            is Card.Shown -> card.value
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(b.value), style = RidgeType.cardNumber)
            Subtle("  an hour", Modifier.padding(bottom = 6.dp))
        }
        Subtle("${b.drops} blood-oxygen drops in ${"%.1f".format(b.hours)} h measured")
        StatRow("Your usual", b.usual?.let { "%.1f an hour".format(it.median) } ?: "learning")
    }
}

/** 480 → "8h", 450 → "7h 30m". */
private fun needLabel(minutes: Double): String {
    val m = minutes.roundToInt()
    return if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}

@Composable
private fun StageTiles(night: Night) {
    val c = LocalMetricColors.current
    val shades = stageShades(c.sleep, c.stress, MaterialTheme.colorScheme.onSurface)
    val parts = listOf(Triple("Deep", night.deepMin, 5), Triple("Light", night.lightMin, 4), Triple("REM", night.remMin, 8), Triple("Awake", night.awakeMin, 7))
        .map { (label, minutes, code) -> Triple(label, (minutes ?: 0).toDouble(), shades.getValue(code)) }
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
private fun HealthGroup(card: Card<SleepHealth>, regularity: Card<Double>) {
    val r = LocalRidgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader("Sleep health", card.shown?.let { "${it.dimensions} of 4 dimensions met" }, Infos.health)
        val health = when (card) {
            is Card.Withheld -> return@Column RidgeCard { Subtle(card.message) }
            is Card.Shown -> card.value
        }
        val midpoint = OffsetDateTime.parse(health.midpointLocal).format(DateTimeFormatter.ofPattern("HH:mm"))
        val rows = listOf(
            Row4("Duration", "7–9 h", hm(health.tstMin), health.duration),
            Row4("Efficiency", "≥ 85%", "${health.efficiencyPct.roundToInt()}%", health.efficiency),
            Row4("Timing", "Midpoint 02:00–04:00", midpoint, health.timing),
            Row4("Regularity", "SRI ≥ 70", regularity.shown?.let { "%.0f".format(it) } ?: "—", health.regularity),
        )
        Grouped(rows) { row, shape ->
            ListRow(shape, row.name, row.rule,
                leading = {
                    if (row.ok == 1) Icon(Icons.Rounded.CheckCircle, "Met", tint = r.zoneGreen, modifier = Modifier.size(24.dp))
                    else Icon(Icons.Rounded.Cancel, "Not met", tint = r.zoneRed, modifier = Modifier.size(24.dp))
                },
                trailing = { Text(row.value, style = RidgeType.label) })
        }
        if (regularity is Card.Withheld) Subtle(regularity.message, Modifier.padding(horizontal = 4.dp), RidgeType.caption)
    }
}

private data class Row4(val name: String, val rule: String, val value: String, val ok: Int)
