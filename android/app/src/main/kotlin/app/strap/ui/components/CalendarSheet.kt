package app.strap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.sync.DayStatus
import strap.science.DayRings
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Which ring the calendar draws on each day. */
enum class CalendarMetric(val label: String) { STRAIN("Strain"), RECOVERY("Recovery"), SLEEP("Sleep"), STRESS("Stress") }

/** A plain ring with no text: the track and the value from 12 o'clock. */
@Composable
fun MiniRing(fraction: Float?, color: Color, size: Dp, stroke: Dp = 3.5.dp) {
    val track = LocalRidgeColors.current.surface3
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val tl = Offset(w / 2, w / 2)
        val s = Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, tl, s, style = Stroke(w))
        fraction?.takeIf { it > 0f }?.let { drawArc(color, -90f, 360f * it.coerceIn(0f, 1f), false, tl, s, style = Stroke(w, cap = StrokeCap.Round)) }
    }
}

/**
 * The calendar (Bevel's): a month of days, each with its ring for the chosen metric and the icon
 * of a day marked sick, injured or on a break; pick a day to show it. Days after today and days with nothing recorded are greyed and cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSheet(api: Reader, day: LocalDate, initial: CalendarMetric, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(day)) }
    var metric by remember { mutableStateOf(initial) }
    var rings by remember { mutableStateOf<Map<LocalDate, DayRings>>(emptyMap()) }
    var statuses by remember { mutableStateOf<Map<LocalDate, DayStatus>>(emptyMap()) }
    LaunchedEffect(month) {
        val last = minOf(month.atEndOfMonth(), today)
        rings = try {
            if (month.atDay(1) > today) emptyMap() else api.rings(month.atDay(1), last).associateBy { it.date }
        } catch (_: ApiException) {
            emptyMap()
        }
        statuses = try { api.statuses(month.atDay(1), month.atEndOfMonth()) } catch (_: ApiException) { emptyMap() }
    }
    val c = LocalMetricColors.current
    val r = LocalRidgeColors.current
    fun ring(d: DayRings?): Pair<Float?, Color> = when (metric) {
        CalendarMetric.STRAIN -> d?.strain?.let { (it / 21).toFloat() } to c.strain
        CalendarMetric.RECOVERY -> d?.recovery?.let { it / 100f } to (d?.recovery?.let { r.zone(it.toDouble()) } ?: c.recovery)
        CalendarMetric.SLEEP -> d?.sleepScore?.let { it / 100f } to c.sleep
        CalendarMetric.STRESS -> d?.stress?.let { (it / 100).toFloat() } to c.stress
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = r.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectedButtons(CalendarMetric.entries, metric, { it.label }) { metric = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = RidgeType.sectionTitle, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                    Text(it, style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val lead = month.atDay(1).dayOfWeek.value - 1
            val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { d ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (d != null) {
                                val known = rings[d]
                                val enabled = d <= today && known != null
                                val (fraction, color) = ring(known)
                                Column(
                                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (d == day) r.card else Color.Transparent)
                                        .clickable(enabled = enabled) { onPick(d) }.padding(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    MiniRing(if (enabled) fraction else null, color, 30.dp)
                                    Text("${d.dayOfMonth}", style = RidgeType.caption,
                                        color = when {
                                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            d == today -> c.strain
                                            else -> MaterialTheme.colorScheme.onSurface
                                        })
                                    // Every cell keeps the icon's room, so the rows line up.
                                    Box(Modifier.size(12.dp)) { statuses[d]?.let { Icon(it.icon, it.label, tint = it.color(), modifier = Modifier.size(12.dp)) } }
                                }
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onPick(today) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Text("Today", style = RidgeType.label)
            }
        }
    }
}
