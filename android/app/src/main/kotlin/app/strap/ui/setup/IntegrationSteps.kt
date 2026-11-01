package app.strap.ui.setup

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.strap.StrapApp
import app.strap.api.ApiException
import app.strap.integrations.Dawarich
import app.strap.integrations.OpenGymClient
import app.strap.pairing.DawarichLink
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.launch

/**
 * Connecting openGym (D41): its address and the 8-character code from openGym's Settings →
 * "Pair the mobile app". Pairing redeems the code at once (it lives 5 minutes) and pulls.
 */
@Composable
fun OpenGymStep(app: StrapApp, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val current = remember { app.vault.loadOpenGym() }
    var url by remember { mutableStateOf(current?.baseUrl ?: "") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf(false) }
    Frame(null, Icons.Rounded.FitnessCenter, if (current == null) "Connect openGym" else "openGym",
        "Ridge reads your logged gym sessions and lays the strap's heart rate over them. It never changes anything in openGym.",
        buttons = { Buttons(onDone.takeIf { !paired }, if (paired || (current != null && code.isBlank())) "Done" else "Pair", enabled = !busy && (paired || current != null || code.isNotBlank())) {
            if (paired || (current != null && code.isBlank())) return@Buttons onDone()
            scope.launch {
                busy = true
                result = try {
                    app.vault.saveOpenGym(OpenGymClient.pair(url, code))
                    val failure = app.openGym.pull(force = true)
                    paired = failure == null
                    failure ?: "Paired. ${count(app)}"
                } catch (e: ApiException) {
                    e.message
                }
                busy = false
            }
        } }) {
        OutlinedTextField(url, { url = it; result = null }, label = { Text("openGym address") }, placeholder = { Text("https://gym.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(code, { code = it.uppercase(); result = null }, label = { Text(if (current == null) "Pairing code" else "New pairing code (to pair again)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = RidgeType.paragraph.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            supportingText = { Text("In openGym: Settings → Pair the mobile app. 8 characters, valid for 5 minutes.") })
        result?.let { Text(it, style = RidgeType.body, color = if (paired) scheme.primary else scheme.error) }
        if (current != null) {
            app.openGym.status()?.let { s ->
                Text(s.failure ?: s.at?.let { "Last pulled ${ago(it)}. ${count(app)}" } ?: "Not pulled yet.", style = RidgeType.body,
                    color = if (s.failure != null) scheme.error else scheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        result = app.openGym.pull(force = true) ?: "Pulled. ${count(app)}"
                        busy = false
                    }
                }) {
                    Icon(Icons.Rounded.CloudSync, null, Modifier.size(18.dp))
                    Text(if (busy) "  Pulling…" else "  Pull now")
                }
                TextButton(onClick = { app.openGym.forget(); onDone() }) { Text("Disconnect", color = scheme.error) }
            }
        }
    }
}

/** Connecting Dawarich (D42): its address and an API key from its account settings. Tested before it is saved. */
@Composable
fun DawarichStep(app: StrapApp, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val current = remember { app.vault.loadDawarich() }
    var url by remember { mutableStateOf(current?.baseUrl ?: "") }
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var tilesUrl by remember { mutableStateOf(MapTiles.get(app)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf<DawarichLink?>(null) }
    val keyKept = current != null && key.isBlank()
    Frame(null, Icons.Rounded.Map, if (current == null) "Connect Dawarich" else "Dawarich",
        "Ridge shows the day's walks and rides from your Dawarich with the strap's heart rate. Ridge itself never records your location; the tracks stay in Dawarich.",
        buttons = { Buttons(onDone, "Save", enabled = tested != null || (keyKept && current?.baseUrl == url.trim().trimEnd('/'))) {
            tested?.let { app.vault.saveDawarich(it) }
            MapTiles.set(app, tilesUrl)
            onDone()
        } }) {
        OutlinedTextField(url, { url = it; tested = null; result = null }, label = { Text("Dawarich address") }, placeholder = { Text("https://dawarich.example.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(
            key, { key = it; tested = null; result = null }, label = { Text(if (current == null) "API key" else "API key (leave empty to keep)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), textStyle = RidgeType.paragraph.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (shown) "Hide key" else "Show key") } },
            supportingText = { Text("In Dawarich: Account → API key.") },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !testing, onClick = {
                val link = DawarichLink.parse(url, key.ifBlank { current?.apiKey ?: "" })
                if (link == null) {
                    result = "Needs an https address and the API key."
                    return@OutlinedButton
                }
                scope.launch {
                    testing = true
                    result = try {
                        val n = Dawarich.test(link)
                        tested = link
                        "Connected: $n walks and rides in the last 7 days."
                    } catch (e: ApiException) {
                        e.message
                    }
                    testing = false
                }
            }) {
                Icon(Icons.Rounded.CloudSync, null, Modifier.size(18.dp))
                Text(if (testing) "  Testing…" else "  Test")
            }
            if (current != null) TextButton(onClick = { app.dawarich.forget(); onDone() }) { Text("Disconnect", color = scheme.error) }
        }
        result?.let { Text(it, style = RidgeType.body, color = if (tested != null) scheme.primary else scheme.error) }
        OutlinedTextField(tilesUrl, { tilesUrl = it }, label = { Text("Map tiles") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text("An https tile address with {z}, {x} and {y}. Tiles load only while a map is on screen; point it at your own tile server if you run one.") })
        TextButton(onClick = { tilesUrl = MapTiles.DEFAULT }) { Text("Use the default (OpenStreetMap)") }
    }
}

/** Where the map's image tiles come from (D42): a `{z}/{x}/{y}` address. Not a secret: plain preferences. */
object MapTiles {
    const val DEFAULT = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    fun get(context: Context): String = context.getSharedPreferences("integrations", Context.MODE_PRIVATE).getString("map_tiles", null) ?: DEFAULT

    fun set(context: Context, url: String) {
        context.getSharedPreferences("integrations", Context.MODE_PRIVATE).edit().putString("map_tiles", valid(url) ?: DEFAULT).apply()
    }

    /** An https address naming {z}, {x} and {y}, or null. */
    fun valid(url: String): String? = url.trim().takeIf { u -> u.startsWith("https://") && listOf("{z}", "{x}", "{y}").all { it in u } }

    /** What the map credits. Most tile servers draw OpenStreetMap's data, which asks for this line. */
    const val ATTRIBUTION = "© OpenStreetMap contributors"
}

private fun count(app: StrapApp): String {
    val n = app.store.gymSessions(java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(86_400)).size
    return if (n == 1) "1 session on this phone." else "$n sessions on this phone."
}

private fun ago(at: java.time.Instant): String {
    val m = java.time.Duration.between(at, java.time.Instant.now()).toMinutes()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}
