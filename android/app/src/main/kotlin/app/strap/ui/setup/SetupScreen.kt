package app.strap.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.StrapApp
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.pairing.Pairing
import app.strap.pairing.ServerLink
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.components.Subtle
import app.strap.ui.describe
import app.strap.ui.syncPermissions
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val DOCS = "https://github.com/TheCommishDeuce/ridge-helio-strap/blob/main"

/**
 * First run (D27): welcome → pair the strap → connect the server → first sync → Today.
 * Shown whenever the pairing or the server is missing; each step checks its input first.
 */
@Composable
fun SetupFlow(app: StrapApp, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(if (app.vault.load() == null) 0 else 2) }
    when (step) {
        0 -> Welcome { step = 1 }
        1 -> StrapStep(app, title = "Pair your strap") { step = 2 }
        2 -> ServerStep(app, title = "Connect your server") { step = 3 }
        else -> FirstSync(app, onDone)
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val links = LocalUriHandler.current
    Page("Ridge", "Your Helio Strap's data, read straight from the strap and kept on your own server.") {
        Text("You need two things:", fontWeight = FontWeight.SemiBold)
        Text("1. Your strap's auth key. The Zepp app made it when it paired the strap; a small script on your computer reads it once.")
        TextButton(onClick = { links.openUri("$DOCS/tools/keyfetch/README.md") }) { Text("How to get the key") }
        Text("2. A Ridge server with HTTPS: about ten minutes with Docker.")
        TextButton(onClick = { links.openUri("$DOCS/deploy/README.md") }) { Text("How to run the server") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    }
}

/** Pairing: MAC + key (or the keyfetch QR text), then the Bluetooth permission. Also Settings' "Change…". */
@Composable
fun StrapStep(app: StrapApp, title: String, onCancel: (() -> Unit)? = null, onSaved: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var mac by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
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
    Page(title, "Paste what tools/keyfetch printed: the MAC and the key, or the whole QR text into the key field.") {
        OutlinedTextField(mac, { mac = it }, label = { Text("MAC (AA:BB:CC:DD:EE:FF)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it }, label = { Text("Auth key (32 hex) or QR text") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { links.openUri("$DOCS/tools/keyfetch/README.md") }) { Text("How to get the key") }
        Buttons(onCancel) {
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
    }
}

/** The server: address + token, tested with one authenticated call before it is saved. Also Settings' "Change…". */
@Composable
fun ServerStep(app: StrapApp, title: String, onCancel: (() -> Unit)? = null, onSaved: () -> Unit) {
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf<ServerLink?>(null) }
    Page(title, "The HTTPS address of your Ridge server and the token its new-token.sh printed.") {
        OutlinedTextField(url, { url = it; tested = null }, label = { Text("https://ridge.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it; tested = null }, label = { Text("Token (64 characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
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
        }) { Text(if (testing) "Testing…" else "Test") }
        result?.let { Text(it, color = if (tested != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        TextButton(onClick = { links.openUri("$DOCS/deploy/README.md") }) { Text("How to run the server") }
        Buttons(onCancel, nextEnabled = tested != null) {
            tested?.let { app.vault.saveServer(it); onSaved() }
        }
    }
}

@Composable
private fun FirstSync(app: StrapApp, onDone: () -> Unit) {
    val state by app.syncRunner.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state !is SyncState.Running && state !is SyncState.Connecting) SyncService.start(app) }
    val finished = state as? SyncState.Finished
    Page("First sync", "Reads up to 30 days from the strap and uploads them. It can take a few minutes: keep the strap near the phone.") {
        if (finished == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(describe(state))
        } else if (finished.failure == null) {
            Text("All set. Your days are on the server.", fontWeight = FontWeight.SemiBold)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Open Today") }
        } else {
            Text(finished.failure, color = MaterialTheme.colorScheme.error)
            Subtle("Whatever arrived is kept; the next sync continues from there.")
            Button(onClick = { SyncService.start(app) }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            TextButton(onClick = onDone) { Text("Continue to Today") }
        }
    }
}

@Composable
private fun Page(title: String, intro: String, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Subtle(intro)
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun Buttons(onCancel: (() -> Unit)?, nextEnabled: Boolean = true, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onCancel?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("Cancel") } }
        Button(enabled = nextEnabled, onClick = onNext, modifier = Modifier.weight(1f)) { Text(if (onCancel != null) "Save" else "Next") }
    }
}
