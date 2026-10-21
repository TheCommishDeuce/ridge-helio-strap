package app.strap.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.StrapApp
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.components.Bar
import app.strap.ui.components.GroupHeader
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.RidgeCard
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import strap.protocol.model.Metric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Mono = RidgeType.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

/** Advanced → Diagnostics: exactly what the strap delivered and how the last sync went. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(app: StrapApp) {
    val state by app.syncRunner.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    // Re-read whenever the sync state changes (a finished sync wrote new rows).
    val summary = remember(state) { app.store.lastSummary() }
    val last = remember(state) { app.store.lastSync() }
    val densities = remember(state) { listOf(Metric.HR, Metric.STRESS, Metric.STEPS).map { app.store.density(it, Instant.now()) } }
    val services by app.syncRunner.services.collectAsStateWithLifecycle()
    val running = state.isRunning
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RidgeCard(radius = 28.dp, padding = 20.dp, spacing = 16.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val failed = (state as? SyncState.Finished)?.failure != null
                IconCircle(when { running -> Icons.Outlined.Sync; failed -> Icons.Outlined.ErrorOutline; else -> Icons.Outlined.CheckCircle },
                    scheme.secondaryContainer, if (failed) scheme.error else scheme.onSecondaryContainer, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(describe(state), style = RidgeType.cardTitle)
                    Text(if (running) "Keep the strap near the phone" else last?.let { "Last sync ${clock(it.first)}" } ?: "Not synced yet",
                        style = RidgeType.body, color = scheme.onSurfaceVariant)
                }
            }
            Bar(syncProgress(state) ?: if (last != null) 1f else 0f, scheme.primary, height = 6.dp)
            FilledTonalButton(enabled = !running, onClick = { SyncService.start(app) }) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Text("  Sync now")
            }
        }
        GroupHeader("Resolution, last 24 h", "Readings · distinct minutes · coverage")
        Grouped(densities) { d, shape ->
            Row(Modifier.fillMaxWidth().clip(shape).background(LocalRidgeColors.current.card).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(d.metric.wireName, style = Mono.copy(fontSize = 14.sp), modifier = Modifier.width(80.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("%,d · %,d min · %.0f %%".format(d.readings, d.minutes, d.coverage * 100), style = Mono.copy(fontSize = 13.sp))
                    Bar(d.coverage.toFloat(), scheme.primary, height = 4.dp)
                }
            }
        }
        GroupHeader("Strap services", "Endpoint · * = encrypted")
        val list = services?.entries?.sortedBy { it.key }
        if (list == null) Text("Sync once to read them.", style = RidgeType.body, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { (k, enc) ->
                Text("%04x%s".format(k, if (enc) "*" else ""), style = Mono.copy(fontSize = 14.sp),
                    modifier = Modifier.border(1.dp, LocalRidgeColors.current.surface4, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        StrapBattery(app)
        CopyHistory(app)
        SettingsProbe(app)
        LiveStepsProbe(app)
        GroupHeader("Last sync")
        CodeBlock(summary?.toString(2) ?: "none yet")
    }
}

@Composable
private fun CodeBlock(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalRidgeColors.current.surface2).horizontalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text, style = Mono)
    }
}

/**
 * The strap's drain per day, from the battery level each sync reads: the falls between readings
 * over the hours they span (a rise is a charge and is skipped). To see what live steps cost (S4).
 */
@Composable
private fun StrapBattery(app: StrapApp) {
    val readings = remember { app.store.batteryHistory(Instant.now().minus(java.time.Duration.ofDays(8))) }
    GroupHeader("Strap battery", "Drain per day, from the level each sync reads; charges are skipped.")
    val zone = ZoneId.systemDefault()
    val lines = readings.zipWithNext().filter { (a, b) -> b.percent <= a.percent }
        .groupBy { (a, _) -> a.at.atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, pairs) ->
            val drop = pairs.sumOf { (a, b) -> a.percent - b.percent }
            val hours = pairs.sumOf { (a, b) -> java.time.Duration.between(a.at, b.at).toMinutes() } / 60.0
            "%s  −%d %% over %.1f h  = %.2f %%/h · %.0f %%/day".format(day, drop, hours, if (hours > 0) drop / hours else 0.0, if (hours > 0) 24 * drop / hours else 0.0)
        }
    CodeBlock(if (lines.isEmpty()) "No battery readings in the last week." else lines.joinToString("\n"))
}

/**
 * The server's raw history, copied again (D34): after a Zepp-cloud backfill reached the server,
 * this phone takes the new days and works its whole history out again.
 */
@Composable
private fun CopyHistory(app: StrapApp) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GroupHeader("Server history", "Copies what the server holds that this phone has not (after a Zepp-cloud backfill), then works every day out again.")
    OutlinedButton(enabled = !busy && app.vault.loadServer() != null, modifier = Modifier.height(48.dp), onClick = {
        scope.launch {
            busy = true
            result = app.restore.backfill(again = true) ?: app.science.update(null) ?: "Copied; every day worked out again."
            busy = false
        }
    }) {
        Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
        Text(if (busy) "  Copying…" else "  Copy the server's history again")
    }
    result?.let { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** S4 (D34): how live the step count can be. Walk around while it listens. */
@Composable
private fun LiveStepsProbe(app: StrapApp) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GroupHeader("Live steps probe", "Three counter reads, then 60 s of the strap's real-time step reports. Walk around while it listens.")
    OutlinedButton(enabled = !busy, modifier = Modifier.height(48.dp), onClick = {
        scope.launch {
            busy = true; lines = emptyList()
            failure = app.syncRunner.probeLiveSteps { line -> lines = lines + line }
            busy = false
        }
    }) {
        Icon(Icons.Outlined.DirectionsWalk, null, Modifier.size(18.dp))
        Text(if (busy) "  Listening…" else "  Probe live steps")
    }
    failure?.let { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.error) }
    if (lines.isNotEmpty()) CodeBlock(lines.joinToString("\n"))
}

/** R1.2: read-only requests to the strap's settings services, raw replies shown as hex. */
@Composable
private fun SettingsProbe(app: StrapApp) {
    val lines by app.syncRunner.probe.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GroupHeader("Settings probe", "Read-only requests to the strap's settings services, raw replies shown as hex.")
    OutlinedButton(enabled = !busy, modifier = Modifier.height(48.dp), onClick = {
        scope.launch { busy = true; failure = app.syncRunner.probeSettings(); busy = false }
    }) {
        Icon(Icons.Outlined.SettingsRemote, null, Modifier.size(18.dp))
        Text(if (busy) "  Reading…" else "  Read strap settings")
    }
    failure?.let { Text(it, style = RidgeType.body, color = MaterialTheme.colorScheme.error) }
    if (lines.isNotEmpty()) CodeBlock(lines.joinToString("\n"))
}

private fun clock(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) t.format(DateTimeFormatter.ofPattern("HH:mm")) else t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
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

internal val SyncState.isRunning: Boolean get() = this !is SyncState.Idle && this !is SyncState.Finished

/** How far a running sync is, 0..1; null when none runs. */
internal fun syncProgress(state: SyncState): Float? = when (state) {
    SyncState.Connecting -> 0.04f
    is SyncState.Running -> 0.08f + 0.8f * (state.progress.step - 1).coerceAtLeast(0) / state.progress.total.coerceAtLeast(1)
    is SyncState.Uploading -> 0.92f
    else -> null
}
