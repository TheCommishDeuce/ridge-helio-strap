package app.strap.ui.today

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strap.sync.Owner
import app.strap.ui.components.GaugeBlock
import app.strap.ui.components.IconCircle
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.components.Moment
import app.strap.ui.components.SectionTitle
import app.strap.ui.components.Subtle
import app.strap.ui.components.clockOf
import app.strap.ui.journal.QUICK
import app.strap.ui.components.cardSurface
import app.strap.ui.journal.JournalLogger
import app.strap.ui.journal.QuickLogRow
import app.strap.ui.journal.WeightDialog
import app.strap.ui.journal.journalTone
import app.strap.ui.journal.journalIcon
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** One ring the timeline can pin: its name, and its value and fill for a day (null = nothing to show). */
private class Pin(val label: String, val read: (TodayData) -> Pair<String, Float?>, val color: @Composable () -> Color, val open: (TodayNav) -> () -> Unit)

private val PINS: Map<String, Pin> = linkedMapOf(
    "strain" to Pin("Strain", { d -> d.strain.valueOrNull.let { (it?.let { "%.1f".format(it) } ?: "—") to it?.let { v -> (v / 21).toFloat() } } },
        { LocalMetricColors.current.strain }, { it.strain }),
    "recovery" to Pin("Recovery", { d -> d.recovery.valueOrNull.let { (it?.let { "${it.roundToInt()}%" } ?: "—") to it?.let { v -> (v / 100).toFloat() } } },
        { LocalMetricColors.current.recovery }, { it.recovery }),
    "sleep" to Pin("Sleep", { d -> d.night?.deviceScore.let { (it?.toString() ?: "—") to it?.let { v -> v / 100f } } },
        { LocalMetricColors.current.sleep }, { it.sleep }),
    "stress" to Pin("Stress", { d -> d.stress?.mean.let { (it?.roundToInt()?.toString() ?: "—") to it?.let { v -> (v / 100).toFloat() } } },
        { LocalMetricColors.current.stress }, { it.stress }),
    "steps" to Pin("Steps", { d ->
        val v = d.steps.valueOrNull
        (v?.let { if (it >= 1000) "%.1fk".format(it / 1000) else "${it.roundToInt()}" } ?: "—") to v?.let { s -> d.steps.usual?.let { u -> (s / u).toFloat() } }
    }, { LocalMetricColors.current.steps }, { it.steps }),
    "active" to Pin("Active", { d -> d.activeMin.let { (it?.let { "${it.roundToInt()}m" } ?: "—") to it?.let { v -> (v / 30).toFloat() } } },
        { LocalMetricColors.current.recovery }, { it.activity }),
    "heart" to Pin("Resting HR", { d -> d.restingHr.valueOrNull.let { (it?.roundToInt()?.toString() ?: "—") to it?.let { v -> (v / 100).toFloat() } } },
        { LocalMetricColors.current.heart }, { it.heart }),
)

private val DEFAULT_PINS = listOf("strain", "recovery", "sleep", "stress", "steps")

/** The pinned rings, kept on the phone in the order chosen. */
private class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString("timeline_pins", null)?.split(',')?.filter { it in PINS } ?: DEFAULT_PINS

    fun save(pins: List<String>) = prefs.edit().putString("timeline_pins", pins.joinToString(",")).apply()
}

/**
 * The whole day, hour by hour: the pinned rings at the top, then each hour with what happened
 * in it (waking, journal, stress peak, workouts, now). An empty hour's + logs a drink at that hour.
 */
@Composable
fun TimelineScreen(data: TodayData, nav: TodayNav, logger: JournalLogger) {
    val context = LocalContext.current
    val store = remember { PinStore(context) }
    val pins = remember { mutableStateListOf<String>().apply { addAll(store.load()) } }
    var editing by remember { mutableStateOf(false) }
    var logAt by remember { mutableStateOf<Int?>(null) }
    var weighAt by remember { mutableStateOf<Instant?>(null) }
    val zone = data.curves.window.zone
    val moments = moments(data, nav).sortedBy { it.at }
    // Today logs at the moment; a past day at its noon; an hour's + at that hour.
    fun now(): Instant = if (data.day == LocalDate.now()) Instant.now() else data.day.atTime(12, 0).atZone(zone).toInstant()
    fun hourAt(h: Int): Instant = data.day.atTime(h, 0).atZone(zone).toInstant()
    val byHour = moments.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).hour }
    val running = data.day == LocalDate.now()
    val lastHour = if (running) java.time.LocalTime.now(zone).hour else 23
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item { SectionTitle("Pinned", "Edit pins", { editing = true }) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pins.mapNotNull { PINS[it] }.forEach { p ->
                    val (value, fraction) = p.read(data)
                    GaugeBlock(value, null, fraction, p.color(), p.label, null, 64.dp, 15.sp, stroke = 9f, onClick = p.open(nav))
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().cardSurface(RoundedCornerShape(20.dp)).padding(14.dp)) {
                QuickLogRow(onPick = { q -> logger.add(q.kind, q.value, q.name, now(), q.logged) }, onWeight = { weighAt = now() })
            }
        }
        item { SectionTitle("Hour by hour") }
        items((0..lastHour).toList(), key = { it }) { h ->
            val here = byHour[h].orEmpty()
            HourRow(h, here, onAdd = { logAt = h })
        }
    }
    if (editing) PinSheet(pins, onDismiss = { editing = false; store.save(pins) })
    logAt?.let { hour ->
        LogSheet(hour, onDismiss = { logAt = null }, onWeight = { logAt = null; weighAt = hourAt(hour) }) { q ->
            logAt = null
            logger.add(q.kind, q.value, q.name, hourAt(hour), q.logged)
        }
    }
    weighAt?.let { at -> WeightDialog(onDismiss = { weighAt = null }) { kg -> weighAt = null; logger.add("weight", kg, null, at, "Weight saved") } }
}

@Composable
private fun HourRow(hour: Int, moments: List<Moment>, onAdd: () -> Unit) {
    val line = LocalRidgeColors.current.surface3
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 30.dp)) {
        Text("%02d:00".format(hour), style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(top = 6.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(line))
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (moments.isEmpty()) {
                Icon(Icons.Rounded.Add, "Log at %02d:00".format(hour), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).clickable(onClick = onAdd).padding(2.dp))
            }
            moments.forEach { m -> MomentCard(m) }
        }
    }
}

@Composable
private fun MomentCard(m: Moment) {
    Row(
        Modifier.fillMaxWidth().cardSurface(RoundedCornerShape(16.dp)).clickable(onClick = m.onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(m.icon, m.tone.container, m.tone.accent, size = 34.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(m.title, style = RidgeType.rowTitle)
            Text("${m.time} · ${m.subtitle}", style = RidgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        m.meta?.let { Text(it, fontSize = 17.sp, style = RidgeType.body, color = m.tone.accent, modifier = Modifier.padding(horizontal = 6.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(pins: MutableList<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Edit pins", style = RidgeType.sheetTitle, modifier = Modifier.padding(bottom = 8.dp))
            val order = pins + PINS.keys.filter { it !in pins }
            order.forEach { key ->
                val pinned = key in pins
                val i = pins.indexOf(key)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pinned, { on -> if (on) pins += key else if (pins.size > 1) pins -= key })
                    Text(PINS.getValue(key).label, style = RidgeType.rowTitle, modifier = Modifier.weight(1f))
                    if (pinned) {
                        IconButton(onClick = { if (i > 0) pins.add(i - 1, pins.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { if (i < pins.lastIndex) pins.add(i + 1, pins.removeAt(i)) }, enabled = i < pins.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(hour: Int, onDismiss: () -> Unit, onWeight: () -> Unit, onPick: (app.strap.ui.journal.Quick) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalRidgeColors.current.surface1, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Log at %02d:00".format(hour), style = RidgeType.sheetTitle)
            QUICK.forEach { q ->
                val tone = journalTone(q.kind)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(q) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(journalIcon(q.kind, q.name), tone.container, tone.accent, size = 36.dp, iconSize = 18.dp)
                    Text(q.label, style = RidgeType.rowTitle, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Subtle(q.amount, style = RidgeType.body)
                }
            }
            val wt = journalTone("weight")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onWeight).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(journalIcon("weight", null), wt.container, wt.accent, size = 36.dp, iconSize = 18.dp)
                Text("Weight", style = RidgeType.rowTitle, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Subtle("kg", style = RidgeType.body)
            }
        }
    }
}
