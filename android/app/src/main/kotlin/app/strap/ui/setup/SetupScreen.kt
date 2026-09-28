package app.strap.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.StrapApp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.pairing.Pairing
import app.strap.pairing.ServerLink
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.components.Grouped
import app.strap.ui.components.IconCircle
import app.strap.ui.components.ListRow
import app.strap.ui.describe
import app.strap.ui.syncPermissions
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val DOCS = "https://github.com/TheCommishDeuce/ridge-helio-strap/blob/main"
private const val KEY_DOCS = "$DOCS/tools/keyfetch/README.md"
private const val SERVER_DOCS = "$DOCS/deploy/README.md"

/**
 * First run (D27): welcome → pair the strap → connect the server → first sync → Today.
 * Shown whenever the pairing or the server is missing; each step checks its input first.
 */
@Composable
fun SetupFlow(app: StrapApp, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(if (app.vault.load() == null) 0 else 2) }
    when (step) {
        0 -> Welcome { step = 1 }
        1 -> StrapStep(app) { step = 2 }
        2 -> ServerStep(app) { step = 3 }
        else -> FirstSync(app, onDone)
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val links = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    Frame(null, Icons.Rounded.Watch, "Ridge", "Your Helio Strap's data, read straight from the strap and kept on your own server.",
        buttons = { Primary("Start", onClick = onStart) }) {
        Text("You need two things:", style = RidgeType.cardTitle)
        Grouped(
            listOf(
                Triple(Icons.Rounded.Key, "Your strap's auth key", "The Zepp app made it when it paired the strap; a small script on your computer reads it once.") to KEY_DOCS,
                Triple(Icons.Rounded.Dns, "A Ridge server with HTTPS", "About ten minutes with Docker.") to SERVER_DOCS,
            ),
        ) { (item, link), shape ->
            ListRow(shape, item.second, item.third, onClick = { links.openUri(link) },
                leading = { IconCircle(item.first, scheme.secondaryContainer, scheme.onSecondaryContainer) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open the guide", tint = scheme.onSurfaceVariant) })
        }
    }
}

/** Pairing: MAC + key (or the keyfetch QR text), then the Bluetooth permission. Also Settings' "Change…" ([onCancel] set). */
@Composable
fun StrapStep(app: StrapApp, onCancel: (() -> Unit)? = null, onSaved: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var mac by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pairing?>(null) }
    // Only Nearby devices (BLUETOOTH_CONNECT) is required; a refused notification permission
    // just means no "Syncing" notification.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val pairing = pending ?: return@rememberLauncherForActivityResult
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            app.vault.save(pairing)
            onSaved()
        } else {
            error = "Ridge needs the Nearby devices permission to reach the strap. Allow it and press Next again."
        }
    }
    val mono = RidgeType.paragraph.copy(fontFamily = FontFamily.Monospace)
    Frame(if (onCancel == null) 1 else null, Icons.Rounded.Bluetooth, if (onCancel == null) "Pair your strap" else "Change strap",
        "Paste what tools/keyfetch printed: the MAC and the key, or the whole QR text into the key field.",
        buttons = {
            Buttons(onCancel, if (onCancel != null) "Save" else "Next") {
                val parsed = Pairing.parse(mac, key)
                when {
                    parsed == null -> error = "That is not a MAC plus a 32-character hex key."
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED -> {
                        app.vault.save(parsed)
                        onSaved()
                    }
                    else -> {
                        pending = parsed
                        permissions.launch(syncPermissions())
                    }
                }
            }
        }) {
        OutlinedTextField(mac, { mac = it; error = null }, label = { Text("MAC address") }, placeholder = { Text("AA:BB:CC:DD:EE:FF", style = mono) },
            textStyle = mono, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null)
        OutlinedTextField(
            key, { key = it; error = null }, label = { Text("Auth key or QR text") }, placeholder = { Text("32 hex characters", style = mono) },
            textStyle = mono, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (shown) "Hide key" else "Show key")
                }
            },
            supportingText = error?.let { { Text(it) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim() ?: return@launch
                    error = null
                    if (text.startsWith("{")) { key = text; return@launch }
                    MAC_IN.find(text)?.let { mac = it.value.uppercase() }
                    key = KEY_IN.find(text.replace(MAC_IN, ""))?.value ?: if (MAC_IN.containsMatchIn(text)) key else text
                }
            }) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                Text("  Paste")
            }
            DocsLink("How to get the key") { links.openUri(KEY_DOCS) }
        }
    }
}

private val MAC_IN = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
private val KEY_IN = Regex("(?<![0-9A-Fa-f])[0-9A-Fa-f]{32}(?![0-9A-Fa-f])")

/** The server: address + token, tested with one authenticated call before it is saved. Also Settings' "Change…". */
@Composable
fun ServerStep(app: StrapApp, onCancel: (() -> Unit)? = null, onSaved: () -> Unit) {
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf<ServerLink?>(null) }
    Frame(if (onCancel == null) 2 else null, Icons.Rounded.Dns, if (onCancel == null) "Connect your server" else "Change server",
        "The HTTPS address of your Ridge server and the token its new-token.sh printed.",
        buttons = { Buttons(onCancel, if (onCancel != null) "Save" else "Next", enabled = tested != null) { tested?.let { app.vault.saveServer(it); onSaved() } } }) {
        OutlinedTextField(url, { url = it; tested = null; result = null }, label = { Text("Server address") }, placeholder = { Text("https://ridge.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(
            token, { token = it; tested = null; result = null }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = RidgeType.paragraph.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (shown) "Hide token" else "Show token")
                }
            },
            supportingText = { Text("${token.trim().length}/64", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !testing, onClick = {
                val link = ServerLink.parse(url, token)
                if (link == null) {
                    result = "Needs an https address and the 64-character token."
                    return@OutlinedButton
                }
                scope.launch {
                    testing = true
                    result = try {
                        ApiClient(link).summary(LocalDate.now())
                        tested = link
                        "Connected: the server accepted the token."
                    } catch (e: ApiException) {
                        e.message
                    }
                    testing = false
                }
            }) {
                Icon(Icons.Rounded.CloudSync, null, Modifier.size(18.dp))
                Text(if (testing) "  Testing…" else "  Test")
            }
            DocsLink("How to run the server") { links.openUri(SERVER_DOCS) }
        }
        result?.let { Text(it, style = RidgeType.body, color = if (tested != null) scheme.primary else scheme.error) }
    }
}

@Composable
private fun FirstSync(app: StrapApp, onDone: () -> Unit) {
    val state by app.syncRunner.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state !is SyncState.Running && state !is SyncState.Connecting) SyncService.start(app) }
    val finished = state as? SyncState.Finished
    Frame(3, Icons.Rounded.Sync, "First sync", "Reads up to 30 days from the strap and uploads them. It can take a few minutes: keep the strap near the phone.",
        buttons = {
            when {
                finished == null -> {}
                finished.failure == null -> Primary("Open Today", onClick = onDone)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Primary("Try again") { SyncService.start(app) }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continue to Today") }
                }
            }
        }) {
        if (finished == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), strokeCap = StrokeCap.Round,
                trackColor = LocalRidgeColors.current.surface3)
            Text(describe(state), style = RidgeType.paragraph)
        } else if (finished.failure == null) {
            Text("All set. Your days are on the server.", style = RidgeType.cardTitle)
        } else {
            Text(finished.failure, style = RidgeType.paragraph, color = MaterialTheme.colorScheme.error)
            Text("Whatever arrived is kept; the next sync continues from there.", style = RidgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The setup page: a three-part step indicator ([step] 1–3; null hides it — Welcome and
 * edit mode), a 64 dp icon tile, the title and intro, the body, and buttons pinned below.
 */
@Composable
private fun Frame(step: Int?, icon: ImageVector, title: String, intro: String, buttons: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val r = LocalRidgeColors.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            if (step != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { i ->
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) scheme.primary else r.surface3))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Text(title, style = RidgeType.setupTitle)
            Text(intro, style = RidgeType.paragraph.copy(fontSize = RidgeType.cardTitle.fontSize, lineHeight = RidgeType.cardTitle.lineHeight * 1.1f),
                color = scheme.onSurfaceVariant)
            body()
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), content = buttons)
    }
}

@Composable
private fun Primary(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val r = LocalRidgeColors.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = r.surface4.copy(alpha = 0.6f)),
    ) { Text(text, style = RidgeType.cardTitle) }
}

@Composable
private fun Buttons(onCancel: (() -> Unit)?, label: String, enabled: Boolean = true, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onCancel?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f).height(56.dp)) { Text("Cancel", style = RidgeType.cardTitle) } }
        Primary(label, enabled, Modifier.weight(1f), onNext)
    }
}

@Composable
private fun DocsLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, style = RidgeType.label)
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 6.dp).size(18.dp))
    }
}
