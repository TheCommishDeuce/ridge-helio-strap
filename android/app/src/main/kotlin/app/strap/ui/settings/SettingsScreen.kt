package app.strap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.BuildConfig
import app.strap.StrapApp
import app.strap.sync.SyncService
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.theme.RidgeType
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SOURCE = "https://github.com/TheCommishDeuce/ridge-helio-strap"

private data class Item(val icon: ImageVector, val title: String, val detail: String, val external: Boolean = false, val onClick: () -> Unit)

/** The gear (D27): what is set up, a way to change it, and the raw diagnostics only under Advanced. */
@Composable
fun SettingsScreen(app: StrapApp, onChangeStrap: () -> Unit, onChangeServer: () -> Unit, onDiagnostics: () -> Unit, onBackground: () -> Unit, onProfile: () -> Unit) {
    val links = LocalUriHandler.current
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    // Re-read when a sync changes state: it may have finished, or uploaded the outbox.
    val pairing = remember(sync) { app.vault.load() }
    val server = remember(sync) { app.vault.loadServer() }
    val last = remember(sync) { app.store.lastSync() }
    var backedUp by remember { mutableIntStateOf(0) }
    val waiting = remember(sync, backedUp) { app.store.unpushedCounts().values.sum() + app.owner.unpushed() }
    val lastBackup = remember(sync, backedUp) { app.syncRunner.lastBackup() }
    var backingUp by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val background = remember { app.background.prefs() }
    val profile = remember(sync) { app.owner.profile() }
    val weights = remember(sync) { app.owner.weights() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("Strap", listOf(
            Item(Icons.Outlined.Watch, "Helio Strap", pairing?.mac ?: "Not paired", onClick = onChangeStrap),
            Item(Icons.Outlined.Sync, last?.let { "Last sync ${time(it.first)}" } ?: "Not synced yet",
                last?.let { it.second ?: "Complete" } ?: "Tap to sync now") { SyncService.start(app) },
            Item(Icons.Outlined.Schedule, "Background sync", backgroundSummary(background), onClick = onBackground),
        ))
        Section("Backup", listOfNotNull(
            Item(if (waiting == 0) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                server?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() ?: it } ?: "No server",
                if (server == null) "Your data lives only on this phone. Add a server to keep a copy." else backupStatus(lastBackup, waiting),
                onClick = onChangeServer),
            server?.let {
                Item(Icons.Outlined.Sync, backingUp ?: "Back up now", "Every night while charging on Wi-Fi; or now") {
                    if (backingUp != null) return@Item
                    backingUp = "Backing up…"
                    scope.launch {
                        backingUp = app.syncRunner.backupNow()?.let { "Backup stopped: $it" }
                        backedUp++
                        if (backingUp == null) backingUp = "Backed up"
                    }
                }
            },
        ))
        Section("You", listOf(
            Item(Icons.Outlined.Person, "Profile", profileSummary(profile, weights), onClick = onProfile),
        ))
        Section("About", listOf(
            Item(Icons.Outlined.Info, "Ridge ${BuildConfig.VERSION_NAME}", "Free software, AGPL-3.0. Source code and licences", external = true) { links.openUri(SOURCE) },
        ))
        Section("Advanced", listOf(
            Item(Icons.Outlined.BugReport, "Diagnostics", "What the strap delivered, sync details, the settings probe", onClick = onDiagnostics),
        ))
    }
}

@Composable
private fun Section(title: String, items: List<Item>) {
    val scheme = MaterialTheme.colorScheme
    Text(title, style = RidgeType.label, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp))
    Grouped(items) { item, shape ->
        ListRow(shape, item.title, item.detail, onClick = item.onClick,
            leading = { IconCircle(item.icon, scheme.secondaryContainer, scheme.onSecondaryContainer) },
            trailing = {
                Icon(if (item.external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
            })
    }
}

/** When the server last had everything, and a warning once that is days ago (D34 S5). */
private fun backupStatus(last: Instant?, waiting: Int): String {
    val age = last?.let { java.time.Duration.between(it, Instant.now()) }
    val waits = if (waiting == 0) "nothing waiting" else "$waiting changes waiting"
    return when {
        last == null -> "Not backed up yet · $waits"
        age!!.toDays() >= STALE_BACKUP_DAYS -> "⚠ Last backup ${age.toDays()} days ago · $waits. Check the server, or charge on Wi-Fi overnight."
        else -> "Backed up ${time(last)} · $waits"
    }
}

private const val STALE_BACKUP_DAYS = 3L

/** The profile in one line, and the latest weight (logged in the Journal). */
private fun profileSummary(p: strap.science.Profile?, weights: List<strap.science.WeightEntry>): String {
    val parts = buildList {
        p?.dob?.let { add("Born ${it.year}") }
        p?.heightCm?.let { add("${it.toInt()} cm") }
        weights.lastOrNull()?.let { add("%.1f kg on %s".format(it.kg, it.ts.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM")))) }
    }
    val missing = listOfNotNull("date of birth".takeIf { p?.dob == null }, "sex".takeIf { p?.sex == null },
        "height".takeIf { p?.heightCm == null }, "activity".takeIf { p?.srpa == null })
    return (parts + listOfNotNull(missing.takeIf { it.isNotEmpty() }?.let { "Missing: " + it.joinToString() })).joinToString(" · ")
        .ifEmpty { "Tell Ridge what the strap cannot measure" }
}

private fun time(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val clock = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> clock
        today.minusDays(1) -> "yesterday $clock"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}
