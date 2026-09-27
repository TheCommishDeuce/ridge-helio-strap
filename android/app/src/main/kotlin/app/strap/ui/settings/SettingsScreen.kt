package app.strap.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.BuildConfig
import app.strap.StrapApp
import app.strap.ui.components.Subtle
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SOURCE = "https://github.com/TheCommishDeuce/ridge-helio-strap"

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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Strap") {
            SettingRow("Helio Strap", pairing?.mac ?: "Not paired", onChangeStrap)
            Subtle(last?.let { (at, failure) -> "Last sync ${time(at)} · ${failure ?: "complete"}" } ?: "Not synced yet")
        }
        Section("Server") {
            SettingRow(server?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() ?: it } ?: "Not connected",
                if (waiting == 0) "Everything uploaded" else "$waiting waiting to upload", onChangeServer)
        }
        Section("About") {
            SettingRow("Ridge ${BuildConfig.VERSION_NAME}", "Free software, AGPL-3.0. Source code and licences") { links.openUri(SOURCE) }
        }
        Section("Advanced") {
            SettingRow("Diagnostics", "What the strap delivered, sync details, the settings probe", onDiagnostics)
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = body) }
}

@Composable
private fun SettingRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Subtle(detail)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
