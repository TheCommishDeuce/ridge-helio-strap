package app.strap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.StrapApp
import app.strap.sync.BackgroundPrefs
import app.strap.ui.components.ConnectedButtons
import app.strap.ui.components.Grouped
import app.strap.ui.components.ListRow
import app.strap.ui.components.RidgeCard
import app.strap.ui.components.Subtle
import app.strap.ui.theme.RidgeType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Background sync (D30): the old Healthee screen, two jobs and their limits, plus sync on open. */
@Composable
fun BackgroundScreen(app: StrapApp) {
    var prefs by remember { mutableStateOf(app.background.prefs()) }
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    val last = remember(sync) { app.background.lastRun() } // a background run also moves the sync state
    fun change(p: BackgroundPrefs) {
        prefs = p
        app.background.save(p)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("When the app opens")
        Toggle("Sync when opened", "Only if the last complete sync is over 15 minutes old", prefs.onOpen) { change(prefs.copy(onOpen = it)) }

        Label("In the background")
        Toggle("Sync in the background", "Off by default", prefs.enabled) { change(prefs.copy(enabled = it)) }
        if (prefs.enabled) {
            Interval("Collect from the strap", BackgroundPrefs.COLLECT_OPTIONS, prefs.collectMinutes) { change(prefs.copy(collectMinutes = it)) }
            Interval("Upload to the server", BackgroundPrefs.UPLOAD_OPTIONS, prefs.uploadMinutes) { change(prefs.copy(uploadMinutes = it)) }
            Grouped(listOf(
                Triple("Upload on Wi-Fi only", "Unmetered networks", prefs.wifiOnly),
                Triple("Only while charging", "Both jobs", prefs.chargingOnly),
            )) { (title, detail, on), shape ->
                ListRow(shape, title, detail, trailing = {
                    Switch(on, onCheckedChange = {
                        change(if (title.startsWith("Upload")) prefs.copy(wifiOnly = it) else prefs.copy(chargingOnly = it))
                    })
                })
            }
            Subtle("Android decides the exact timing: these are the shortest gaps, and it may wait longer while the phone is idle.",
                Modifier.padding(horizontal = 4.dp), RidgeType.caption)
        }

        Label("Last background run")
        RidgeCard {
            if (last == null) Subtle("None yet.")
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${last.job} · ${ranAt(last.at)}", style = RidgeType.body)
                Subtle(last.result)
            }
        }
    }
}

internal fun backgroundSummary(p: BackgroundPrefs): String = when {
    p.enabled -> "Collect ${every(p.collectMinutes)}, upload ${every(p.uploadMinutes)}"
    p.onOpen -> "When the app opens"
    else -> "Off"
}

private fun every(minutes: Int) = if (minutes < 60) "every $minutes min" else if (minutes == 60) "hourly" else "every ${minutes / 60} h"

@Composable
private fun Label(text: String) =
    Text(text, style = RidgeType.label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp))

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Grouped(listOf(Unit)) { _, shape -> ListRow(shape, title, detail, trailing = { Switch(on, onCheckedChange = onChange) }) }

@Composable
private fun Interval(title: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    RidgeCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = RidgeType.rowTitle)
            ConnectedButtons(options, selected, { if (it < 60) "$it min" else "${it / 60} h" }, onSelect = onSelect)
        }
    }
}

private fun ranAt(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    val clock = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        LocalDate.now() -> clock
        LocalDate.now().minusDays(1) -> "yesterday $clock"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}
