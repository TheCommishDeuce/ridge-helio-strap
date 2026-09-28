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
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
fun SettingsScreen(app: StrapApp, onChangeStrap: () -> Unit, onChangeServer: () -> Unit, onDiagnostics: () -> Unit) {
    val links = LocalUriHandler.current
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    // Re-read when a sync changes state: it may have finished, or uploaded the outbox.
    val pairing = remember(sync) { app.vault.load() }
    val server = remember(sync) { app.vault.loadServer() }
    val last = remember(sync) { app.store.lastSync() }
    val waiting = remember(sync) { app.store.unpushedCounts().values.sum() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("Strap", listOf(
            Item(Icons.Outlined.Watch, "Helio Strap", pairing?.mac ?: "Not paired", onClick = onChangeStrap),
            Item(Icons.Outlined.Sync, last?.let { "Last sync ${time(it.first)}" } ?: "Not synced yet",
                last?.let { it.second ?: "Complete" } ?: "Tap to sync now") { SyncService.start(app) },
        ))
        Section("Server", listOf(
            Item(if (waiting == 0) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                server?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() ?: it } ?: "Not connected",
                if (waiting == 0) "Everything uploaded" else "$waiting waiting to upload", onClick = onChangeServer),
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
