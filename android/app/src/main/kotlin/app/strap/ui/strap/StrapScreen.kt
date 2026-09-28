package app.strap.ui.strap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.StrapApp
import app.strap.store.LocalStore
import app.strap.sync.SyncState
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.HeroGauge
import app.strap.ui.components.HeroRow
import app.strap.ui.components.Infos
import app.strap.ui.components.ListRow
import app.strap.ui.components.Note
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.SideStat
import app.strap.ui.components.Subtle
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import strap.protocol.parse.AlarmWrite
import strap.protocol.parse.Alarms
import strap.protocol.parse.Config
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.StrapAlarm
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val MAX_ALARMS = 10 // the strap's capabilities reply (spec/01 "Alarms")
private val SETTING_GROUPS = listOf(Config.Health.GROUP, Config.Workout.GROUP)
internal const val LOW_BATTERY = 15

/**
 * What the strap itself does: battery, alarms, its health and workout settings. Everything is
 * read from the strap on open (one connection); every change is written straight to the strap
 * and read back. The settings are shown read-only until each has been write-tested (R1).
 */
@Composable
fun StrapScreen(app: StrapApp, sync: SyncState) {
    val runner = app.syncRunner
    val battery by runner.battery.collectAsStateWithLifecycle()
    val last = remember(sync) { app.store.lastSync() }
    var alarms by remember { mutableStateOf<List<StrapAlarm>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf<List<ConfigGroup?>?>(null) } // health, workout
    var editing by remember { mutableStateOf<Pair<StrapAlarm, Boolean>?>(null) } // alarm, isNew
    val scope = rememberCoroutineScope()

    fun apply(write: AlarmWrite?) {
        scope.launch {
            busy = true
            message = null
            val job = runner.strapJob { s ->
                // Settings on the opening read only; an alarm change re-reads the alarms alone.
                val read = if (write == null) SETTING_GROUPS.map { s.readConfig(it) } else null
                Triple(write?.let { s.writeAlarm(it) } ?: true, s.readAlarms(), read)
            }
            val (confirmed, list, read) = job.value ?: Triple(null, null, null)
            read?.let { settings = it }
            list?.let { alarms = it.sortedWith(compareBy({ a -> a.hour }, { a -> a.minute })) }
            message = when {
                job.failure != null -> job.failure
                list == null -> "The strap did not send its alarms. Try again."
                confirmed == false -> "The strap did not confirm the change. The list shows what it holds now."
                else -> null
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { apply(null) }

    val list = alarms
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Hero(battery, list, last) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp))) }
            item { GroupHeader("Alarms", info = Infos.alarms) }
            message?.let { item { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) } }
            if (list != null) {
                if (list.isEmpty()) item { RidgeCard { Subtle("No alarms on the strap.") } }
                else item {
                    Grouped(list) { a, shape ->
                        val color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        ListRow(shape, "%02d:%02d".format(a.hour, a.minute), daysLabel(a.days), onClick = { editing = a to false }, enabled = !busy,
                            headlineStyle = RidgeType.cardNumber.copy(fontSize = 34.sp, lineHeight = 40.sp, color = color),
                            trailing = { Switch(checked = a.enabled, enabled = !busy, onCheckedChange = { apply(Alarms.update(a.copy(enabled = it))) }) })
                    }
                }
            } else if (!busy) item { TextButton(onClick = { apply(null) }) { Text("Read again") } }
            settings?.let { (health, workout) ->
                item { SettingsGroup("Heart rate & sensors", health, healthRows(health)) }
                item { SettingsGroup("Workout detection", workout, workoutRows(workout)) }
            }
        }
        AnimatedVisibility(list != null && list.size < MAX_ALARMS, Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (busy || list == null) return@ExtendedFloatingActionButton
                    val slot = (0 until MAX_ALARMS).first { s -> list.none { it.slot == s } }
                    editing = StrapAlarm(slot, true, 7, 0, DayOfWeek.entries.take(5).toSet()) to true
                },
                icon = { Icon(Icons.Rounded.AlarmAdd, null) },
                text = { Text("Add alarm", style = RidgeType.cardTitle) },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.height(64.dp),
            )
        }
    }

    editing?.let { (alarm, isNew) ->
        AlarmEditor(
            alarm, isNew,
            onDismiss = { editing = null },
            onSave = { editing = null; apply(if (isNew) Alarms.create(it) else Alarms.update(it)) },
            onDelete = { editing = null; apply(Alarms.delete(alarm.slot)) },
        )
    }
}

@Composable
private fun Hero(battery: LocalStore.Battery?, alarms: List<StrapAlarm>?, last: Pair<Instant, String?>?) {
    val r = LocalRidgeColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val low = battery != null && battery.percent <= LOW_BATTERY
    HeroRow(
        left = { SideStat(alarms?.count { it.enabled }?.toString() ?: "—", "Alarms on", alarms?.let { Note("of ${it.size} on strap", muted) }, it) },
        right = {
            SideStat(last?.first?.let(::readAt) ?: "—", "Last sync",
                last?.let { (_, failure) -> if (failure == null) Note("complete", r.zoneGreen) else Note("stopped early", r.zoneRed) }, it)
        },
    ) {
        HeroGauge(battery?.let { "${it.percent}%" } ?: "—", "charge", battery?.let { it.percent / 100f },
            if (low) MaterialTheme.colorScheme.error else r.zoneGreen, "Battery",
            Note(battery?.let { "read " + readAt(it.at) } ?: "not read yet", muted))
    }
}

private fun readAt(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> time
        today.minusDays(1) -> "yesterday $time"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}

/** A strap settings group, read-only: one row per setting the strap reported that we can name. */
@Composable
private fun SettingsGroup(title: String, group: ConfigGroup?, rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader(title, "Read from the strap", Infos.strapSettings)
        when {
            group == null -> RidgeCard { Subtle("The strap did not send these settings.") }
            rows.isEmpty() -> RidgeCard { Subtle("The strap reported none of the settings we know.") }
            else -> Grouped(rows) { (label, value), shape ->
                ListRow(shape, label, headlineStyle = RidgeType.body.copy(fontSize = 15.sp),
                    trailing = { Text(value, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        if (group != null && !group.complete) Subtle("Some settings could not be read.", Modifier.padding(horizontal = 4.dp), RidgeType.caption)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(alarm: StrapAlarm, isNew: Boolean, onDismiss: () -> Unit, onSave: (StrapAlarm) -> Unit, onDelete: () -> Unit) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableStateOf(alarm.days) }
    val scheme = MaterialTheme.colorScheme
    // Wider than the platform's dialog default: seven 36 dp day toggles need the room.
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 400.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(LocalRidgeColors.current.surface3).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (isNew) "New alarm" else "Edit alarm", style = RidgeType.label, color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            TimeInput(
                time,
                colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = scheme.primaryContainer,
                    timeSelectorUnselectedContainerColor = LocalRidgeColors.current.surface4,
                    timeSelectorSelectedContentColor = scheme.onSurface,
                    timeSelectorUnselectedContentColor = scheme.onSurface,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d -> DayToggle(d, d in days) { days = if (d in days) days - d else days + d } }
            }
            Text(daysLabel(days), style = RidgeType.body, color = scheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = scheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days)) }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val locale = LocalLocale.current.platformLocale
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(if (on) scheme.primary else LocalRidgeColors.current.surface3)
            .border(1.dp, if (on) scheme.primary else scheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { selected = on; contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
        contentAlignment = Alignment.Center,
    ) {
        Text(day.getDisplayName(TextStyle.NARROW, locale), color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, style = RidgeType.label)
    }
}

private fun daysLabel(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == DayOfWeek.entries.take(5).toSet() -> "Weekdays"
    days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}
