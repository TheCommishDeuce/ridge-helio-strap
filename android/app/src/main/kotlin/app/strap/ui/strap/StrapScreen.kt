package app.strap.ui.strap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strap.store.LocalStore
import app.strap.sync.SyncRunner
import app.strap.ui.components.MetricCard
import app.strap.ui.components.Subtle
import kotlinx.coroutines.launch
import strap.protocol.parse.AlarmWrite
import strap.protocol.parse.Config
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.Alarms
import strap.protocol.parse.StrapAlarm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val MAX_ALARMS = 10 // the strap's capabilities reply (spec/01 "Alarms")
private val SETTING_GROUPS = listOf(Config.Health.GROUP, Config.Workout.GROUP)

/**
 * What the strap itself does: battery, alarms, its health and workout settings. Everything is
 * read from the strap on open (one connection); every change is written straight to the strap
 * and read back. The settings are shown read-only until each has been write-tested (R1).
 */
@Composable
fun StrapScreen(runner: SyncRunner) {
    val battery by runner.battery.collectAsStateWithLifecycle()
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

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { BatteryCard(battery) }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Alarms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Subtle("These alarms live on the strap and vibrate it. Changes are written to the strap straight away.")
            }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val list = alarms
        if (list != null) {
            if (list.isEmpty()) item { Subtle("No alarms on the strap.") }
            items(list, key = { it.slot }) { a ->
                AlarmRow(a, enabled = !busy, onToggle = { apply(Alarms.update(a.copy(enabled = it))) }, onClick = { editing = a to false })
            }
            if (list.size < MAX_ALARMS) item {
                Button(enabled = !busy, onClick = {
                    val slot = (0 until MAX_ALARMS).first { s -> list.none { it.slot == s } }
                    editing = StrapAlarm(slot, true, 7, 0, DayOfWeek.entries.take(5).toSet()) to true
                }) { Text("Add alarm") }
            }
        } else if (!busy) item { TextButton(onClick = { apply(null) }) { Text("Read again") } }
        settings?.let { (health, workout) ->
            item { SettingsCard("Heart rate & sensors", health, healthRows(health)) }
            item { SettingsCard("Workout detection", workout, workoutRows(workout)) }
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
private fun BatteryCard(battery: LocalStore.Battery?) {
    val low = battery != null && battery.percent <= LOW_BATTERY
    MetricCard("Battery", if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, battery?.let { "${it.percent} %" }) {
        Subtle(battery?.let { "Read " + readAt(it) } ?: "Not read yet. It is read on every sync and whenever this screen reaches the strap.")
    }
}

private const val LOW_BATTERY = 15

private fun readAt(b: LocalStore.Battery): String {
    val at = b.at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (at.toLocalDate()) {
        today -> time
        today.minusDays(1) -> "yesterday $time"
        else -> at.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}

@Composable
private fun AlarmRow(a: StrapAlarm, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Text("%02d:%02d".format(a.hour, a.minute), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = color)
                Subtle(daysLabel(a.days))
            }
            Switch(checked = a.enabled, enabled = enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(alarm: StrapAlarm, isNew: Boolean, onDismiss: () -> Unit, onSave: (StrapAlarm) -> Unit, onDelete: () -> Unit) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableStateOf(alarm.days) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New alarm" else "Edit alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimeInput(time)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d -> DayToggle(d, d in days) { days = if (d in days) days - d else days + d } }
                }
                Subtle(daysLabel(days))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alarm.copy(hour = time.hour, minute = time.minute, days = days)) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DayToggle(day: DayOfWeek, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (on) scheme.primary else scheme.surfaceVariant)
            .border(1.dp, if (on) scheme.primary else scheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(day.getDisplayName(TextStyle.NARROW, LocalLocale.current.platformLocale), color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
    }
}

private fun daysLabel(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == DayOfWeek.entries.take(5).toSet() -> "Weekdays"
    days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}
