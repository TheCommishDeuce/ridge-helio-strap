package app.strap.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.pairing.Pairing
import app.strap.pairing.ServerLink
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import kotlinx.coroutines.launch
import strap.protocol.model.Metric
import java.time.Instant

import app.strap.StrapApp

/** Diagnostics: pair, sync, server, and exactly what the strap delivered. Behind the gear. */
@Composable
fun DiagnosticsScreen(app: StrapApp) {
    var pairing by remember { mutableStateOf(app.vault.load()) }
    val state by app.syncRunner.state.collectAsStateWithLifecycle()
    // Only BLUETOOTH_CONNECT is required; the notification permission exists from Android 13
    // and a refusal there must not block a sync.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.BLUETOOTH_CONNECT] == true) SyncService.start(app)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val current = pairing
        if (current == null) {
            PairingForm { app.vault.save(it); pairing = it }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Paired: ${current.mac}")
                    OutlinedButton(onClick = { app.vault.forget(); pairing = null }) { Text("Forget") }
                }
            }
            Button(
                enabled = state is SyncState.Idle || state is SyncState.Finished,
                onClick = { permissions.launch(syncPermissions()) },
            ) { Text("Sync now") }
        }
        ServerCard(app, state)
        Text(describe(state))
        Diagnostics(app, state)
    }
}

@Composable
private fun PairingForm(onSave: (Pairing) -> Unit) {
    var mac by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste the MAC and key from tools/keyfetch, or the QR text into the key field.")
            OutlinedTextField(mac, { mac = it }, label = { Text("MAC (AA:BB:CC:DD:EE:FF)") }, singleLine = true)
            OutlinedTextField(
                key, { key = it }, label = { Text("Auth key (32 hex)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val parsed = Pairing.parse(mac, key)
                if (parsed == null) error = "That is not a MAC plus a 32-character hex key." else onSave(parsed)
            }) { Text("Save") }
        }
    }
}

@Composable
private fun ServerCard(app: StrapApp, state: SyncState) {
    var link by remember { mutableStateOf(app.vault.loadServer()) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val outbox = remember(state, link) { app.store.unpushedCounts() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = link
            if (current == null) {
                Text("Server (optional): uploads after every sync.")
                OutlinedTextField(url, { url = it }, label = { Text("https://your-host") }, singleLine = true)
                OutlinedTextField(
                    token, { token = it }, label = { Text("Token from the server setup") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val parsed = ServerLink.parse(url, token)
                    if (parsed == null) error = "Needs an https address and the 64-character token." else { app.vault.saveServer(parsed); link = parsed }
                }) { Text("Save server") }
            } else {
                Text("Server: ${current.baseUrl}")
                Text("Not yet uploaded: " + outbox.entries.joinToString(" · ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { app.vault.forgetServer(); link = null }) { Text("Forget server") }
            }
        }
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

private fun syncPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    }

private fun describe(state: SyncState): String = when (state) {
    SyncState.Idle -> "Idle."
    SyncState.Connecting -> "Connecting…"
    is SyncState.Running -> "Step ${state.progress.step}/${state.progress.total}: ${state.progress.label}"
    is SyncState.Uploading -> state.detail.replaceFirstChar { it.uppercase() } + "…"
    is SyncState.Finished -> state.failure ?: "Sync complete."
}
