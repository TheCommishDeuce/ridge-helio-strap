package app.strap.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.sync.SyncState
import kotlinx.coroutines.launch
import strap.protocol.model.Metric
import java.time.Instant

import app.strap.StrapApp

/** Advanced → Diagnostics: exactly what the strap delivered and how the last sync went. */
@Composable
fun DiagnosticsScreen(app: StrapApp) {
    val state by app.syncRunner.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(describe(state))
        Diagnostics(app, state)
    }
}

@Composable
private fun Diagnostics(app: StrapApp, state: SyncState) {
    // Re-read whenever the sync state changes (a finished sync wrote new rows).
    val summary = remember(state) { app.store.lastSummary() }
    val densities = remember(state) { listOf(Metric.HR, Metric.STRESS, Metric.STEPS).map { app.store.density(it, Instant.now()) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Resolution, last 24 h (readings · distinct minutes · coverage)", style = MaterialTheme.typography.titleSmall)
            for (d in densities) {
                Text("${d.metric.wireName}: ${d.readings} · ${d.minutes} min · ${"%.0f".format(d.coverage * 100)} %", fontFamily = FontFamily.Monospace)
            }
            val services by app.syncRunner.services.collectAsStateWithLifecycle()
            Text("Strap services (endpoint, * = encrypted)", style = MaterialTheme.typography.titleSmall)
            Text(services?.entries?.sortedBy { it.key }?.joinToString(" ") { "%04x%s".format(it.key, if (it.value) "*" else "") } ?: "sync once to read them",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            SettingsProbe(app)
            Text("Last sync", style = MaterialTheme.typography.titleSmall)
            Text(summary?.toString(2) ?: "none yet", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** R1.2: read-only requests to the strap's settings services, raw replies shown as hex. */
@Composable
private fun SettingsProbe(app: StrapApp) {
    val lines by app.syncRunner.probe.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text("Strap settings probe (reads only)", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(enabled = !busy, onClick = {
        scope.launch { busy = true; failure = app.syncRunner.probeSettings(); busy = false }
    }) { Text(if (busy) "Reading…" else "Read strap settings") }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (lines.isNotEmpty()) Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

internal fun syncPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    }

internal fun describe(state: SyncState): String = when (state) {
    SyncState.Idle -> "Idle."
    SyncState.Connecting -> "Connecting…"
    is SyncState.Running -> "Step ${state.progress.step}/${state.progress.total}: ${state.progress.label}"
    is SyncState.Uploading -> state.detail.replaceFirstChar { it.uppercase() } + "…"
    is SyncState.Finished -> state.failure ?: "Sync complete."
}
