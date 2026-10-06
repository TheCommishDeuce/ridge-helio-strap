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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import android.util.Log
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
import strap.protocol.parse.Alarms
import strap.protocol.parse.Config
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.StrapAlarm
import strap.protocol.parse.ZonedAlarm
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
    val clock by runner.clock.collectAsStateWithLifecycle()
    val phoneZone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    fun apply(change: AlarmChange?) {
        scope.launch {
            busy = true
            message = null
            val job = runner.strapJob { s ->
                val strapClock = runner.clock.value ?: return@strapJob Triple(false, null, null) // measured before every job
                // Settings on the opening read only; an alarm change re-reads the alarms alone.
                val read = if (change == null) SETTING_GROUPS.map { s.readConfig(it) } else null
                val confirmed = when (change) {
                    null -> true
                    is AlarmChange.Save -> {
                        app.alarms.put(change.alarm.slot, change.intent)
                        val written = app.alarms.onStrap(change.alarm, change.intent, strapClock)
                        s.writeAlarm(if (change.isNew) Alarms.create(written) else Alarms.update(written))
                    }
                    is AlarmChange.Toggle -> s.writeAlarm(Alarms.update(change.alarm.copy(enabled = change.on)))
                    is AlarmChange.Delete -> s.writeAlarm(Alarms.delete(change.slot)).also { app.alarms.remove(change.slot) }
                }
                Triple(confirmed, app.alarms.reconcile(s, strapClock) { Log.i("strap", it) }, read)
            }
            val (confirmed, list, read) = job.value ?: Triple(null, null, null)
            read?.let { settings = it }
            list?.let { alarms = it }
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

    // Each alarm as the owner set it: its own zone's time, sorted by that.
    val shown = remember(alarms, clock) {
        alarms?.map { a -> a to clock?.let { app.alarms.intent(a, it, phoneZone) } }
            ?.sortedWith(compareBy({ (a, z) -> z?.hour ?: a.hour }, { (a, z) -> z?.minute ?: a.minute }))
    }
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
                    Grouped(shown.orEmpty()) { (a, z), shape ->
                        val color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        ListRow(shape, "%02d:%02d".format(z?.hour ?: a.hour, z?.minute ?: a.minute), alarmDetail(a, z, phoneZone),
                            onClick = { editing = a to false }, enabled = !busy && clock != null,
                            headlineStyle = RidgeType.cardNumber.copy(fontSize = 34.sp, lineHeight = 40.sp, color = color),
                            trailing = { Switch(checked = a.enabled, enabled = !busy, onCheckedChange = { apply(AlarmChange.Toggle(a, it)) }) })
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
                    if (busy || list == null || clock == null) return@ExtendedFloatingActionButton
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
        val start = if (isNew) ZonedAlarm(alarm.hour, alarm.minute, alarm.days, phoneZone) else clock?.let { app.alarms.intent(alarm, it, phoneZone) }
        if (start == null) return@let
        AlarmEditor(
            start, isNew,
            recentZones = (listOf(phoneZone) + shown.orEmpty().mapNotNull { it.second?.zone }).distinct(),
            onDismiss = { editing = null },
            onSave = { editing = null; apply(AlarmChange.Save(alarm, it, isNew)) },
            onDelete = { editing = null; apply(AlarmChange.Delete(alarm.slot)) },
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
private fun AlarmEditor(
    alarm: ZonedAlarm,
    isNew: Boolean,
    recentZones: List<ZoneId>,
    onDismiss: () -> Unit,
    onSave: (ZonedAlarm) -> Unit,
    onDelete: () -> Unit,
) {
    val time = rememberTimePickerState(alarm.hour, alarm.minute, is24Hour = true)
    var days by remember { mutableStateOf(alarm.days) }
    var zone by remember { mutableStateOf(alarm.zone) }
    var pickingZone by remember { mutableStateOf(false) }
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalRidgeColors.current.surface4)
                    .clickable { pickingZone = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Time zone", style = RidgeType.caption, color = scheme.onSurfaceVariant)
                    Text(zoneLabel(zone), style = RidgeType.body)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = scheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onSave(ZonedAlarm(time.hour, time.minute, days, zone)) }) { Text("Save") }
            }
        }
    }
    if (pickingZone) ZonePicker(recentZones, onDismiss = { pickingZone = false }) { zone = it; pickingZone = false }
}

/** Every region zone, searchable by city or region; the phone's and the alarms' zones first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZonePicker(recent: List<ZoneId>, onDismiss: () -> Unit, onPick: (ZoneId) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map(ZoneId::of).sortedBy { cityOf(it) }
    }
    val shown = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) recent + all.filter { it !in recent }
        else all.filter { it.id.replace('_', ' ').contains(q, ignoreCase = true) }
    }
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 48.dp).widthIn(max = 400.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp)).background(LocalRidgeColors.current.surface3).padding(vertical = 16.dp),
        ) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search city or region") }, singleLine = true)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(shown, key = { it.id }) { z ->
                    Text(zoneLabel(z), style = RidgeType.body,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(z) }.padding(horizontal = 24.dp, vertical = 14.dp))
                }
            }
        }
    }
}

/** One change the owner makes to the alarms; written inside one strap job. */
private sealed interface AlarmChange {
    data class Save(val alarm: StrapAlarm, val intent: ZonedAlarm, val isNew: Boolean) : AlarmChange

    data class Toggle(val alarm: StrapAlarm, val on: Boolean) : AlarmChange

    data class Delete(val slot: Int) : AlarmChange
}

/** Days, and for an alarm in another zone: which, and the strap time it rings at. */
private fun alarmDetail(a: StrapAlarm, z: ZonedAlarm?, phone: ZoneId): String {
    if (z == null) return daysLabel(a.days)
    val parts = mutableListOf(daysLabel(z.days))
    if (z.zone.rules.getOffset(Instant.now()) != phone.rules.getOffset(Instant.now())) parts += cityOf(z.zone)
    if (z.hour != a.hour || z.minute != a.minute) parts += "strap %02d:%02d".format(a.hour, a.minute)
    return parts.joinToString(" · ")
}

private fun cityOf(zone: ZoneId): String = zone.id.substringAfterLast('/').replace('_', ' ')

private fun zoneLabel(zone: ZoneId): String {
    val offset = zone.rules.getOffset(Instant.now())
    val utc = if (offset.totalSeconds == 0) "UTC" else "UTC" + offset.id.replace("-", "−").removeSuffix(":00")
    return "${cityOf(zone)} · $utc"
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
