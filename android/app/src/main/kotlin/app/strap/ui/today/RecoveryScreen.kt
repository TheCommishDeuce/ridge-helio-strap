package app.strap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
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

/** Recovery (pushed from Today): the score with its parts, readiness now, and the week. */
@Composable
fun RecoveryContent(data: TodayData, onSelectDay: (LocalDate) -> Unit) {
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
        if (value != null) item { ReadinessCard(value, data, zone) }
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
private fun ReadinessCard(recovery: Double, data: TodayData, zone: androidx.compose.ui.graphics.Color) {
    val readiness = data.readiness
    val now = readiness?.value?.toDouble() ?: recovery
    RidgeCard(spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Readiness now", style = RidgeType.cardTitle, modifier = Modifier.weight(1f))
            Text("${now.roundToInt()}%", style = RidgeType.rowValue.copy(fontSize = RidgeType.cardNumber.fontSize * 0.78f))
        }
        Subtle(
            when {
                readiness != null -> "Recovery reduced by today's load so far: ${readiness.load.roundToInt()} vs your typical ${readiness.typical.roundToInt()}."
                data.day == LocalDate.now() -> "Not reduced yet: today's load is not known."
                else -> "Final value for that day."
            },
        )
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val onSurface = MaterialTheme.colorScheme.onSurface
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).padding(top = 0.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(LocalRidgeColors.current.surface3))
            Box(Modifier.fillMaxWidth((now / 100).toFloat().coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(zone))
            Box(Modifier.offset(x = maxWidth * (recovery / 100).toFloat().coerceIn(0f, 1f) - 1.dp).size(2.dp, 16.dp).background(onSurface))
        }
        Row {
            Text("0", style = RidgeType.caption, color = muted)
            Text("recovery ${recovery.roundToInt()}", style = RidgeType.caption, color = muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("100", style = RidgeType.caption, color = muted)
        }
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
