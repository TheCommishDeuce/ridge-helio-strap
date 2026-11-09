package app.strap.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import app.strap.StrapApp
import app.strap.integrations.HealthConnect
import app.strap.ui.theme.RidgeType
import app.strap.ui.theme.StrapTheme
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What Ridge writes to Health Connect, in the words both this page and Health Connect's own "why" screen use. */
private val WRITTEN = listOf(
    "Each day: steps, distance, total and active calories, your basal rate, and your heart rate minute by minute as the strap read it.",
    "Each night: resting heart rate, HRV, blood oxygen and breathing rate, and the sleep itself with its stages. Naps too.",
    "Workouts as Fitness lists them: the sport and its times, your edits and added sessions included, hidden ones left out.",
    "Your VO₂max estimate, when there is one.",
)

/** Turning the Health Connect export on or off (D45). Write-only: Ridge reads nothing from Health Connect. */
@Composable
fun HealthConnectStep(app: StrapApp, onDone: () -> Unit) {
    val hc = app.healthConnect
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val availability = remember { hc.availability() }
    var on by remember { mutableStateOf(hc.enabled) }
    var status by remember { mutableStateOf(hc.status()) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun start() {
        hc.enable()
        on = true
        working = true
        message = "Writing the last ${HealthConnect.BACKFILL_DAYS} days…"
        scope.launch {
            val failure = hc.export()
            status = hc.status()
            message = failure
            working = false
        }
    }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(hc.permissions)) start()
        else message = "Ridge writes only what Health Connect allows, and it needs all of the list to keep your days whole. Nothing was turned on."
    }
    // Permissions taken away in Health Connect's own settings show here as soon as the page opens.
    LaunchedEffect(Unit) {
        if (on && availability == HealthConnect.Availability.READY && hc.missingPermissions().isNotEmpty())
            message = "Health Connect no longer lets Ridge write. Allow it again to carry on."
    }

    Frame(null, Icons.Rounded.MonitorHeart, "Health Connect",
        "Ridge can write your days to Health Connect, where the other apps on this phone that you allow can read them. " +
            "Ridge reads nothing from Health Connect.",
        buttons = {
            when {
                availability != HealthConnect.Availability.READY -> Buttons(null, "Done", onNext = onDone)
                !on -> Buttons(onDone, "Turn on", enabled = !working) {
                    scope.launch { if (hc.missingPermissions().isEmpty()) start() else ask.launch(hc.permissions) }
                }
                else -> Buttons(null, "Done", enabled = !working, onNext = onDone)
            }
        }) {
        when (availability) {
            HealthConnect.Availability.MISSING -> Text("Health Connect is not available on this phone.", style = RidgeType.body, color = scheme.error)
            HealthConnect.Availability.NEEDS_UPDATE -> Text("Health Connect needs installing or updating from the Play Store first.", style = RidgeType.body, color = scheme.error)
            HealthConnect.Availability.READY -> {}
        }
        WRITTEN.forEach { Text("•  $it", style = RidgeType.body) }
        Text("The last ${HealthConnect.BACKFILL_DAYS} days are written when you turn it on, then each day again after every sync, so a late sync or an edit corrects what was written. " +
            "Calories are the day's totals; a workout carries none of its own, so nothing is counted twice.",
            style = RidgeType.body, color = scheme.onSurfaceVariant)
        if (on) {
            val s = status
            Text(when {
                s?.failure != null -> "⚠ ${s.failure}"
                s?.at != null -> "Last written ${DateTimeFormatter.ofPattern("d MMM HH:mm").format(s.at.atZone(ZoneId.systemDefault()))} · ${s.records} records"
                else -> "On. Nothing written yet."
            }, style = RidgeType.body, color = if (s?.failure != null) scheme.error else scheme.primary)
        }
        message?.let { Text(it, style = RidgeType.body, color = if (working) scheme.onSurfaceVariant else scheme.error) }
        if (on && availability == HealthConnect.Availability.READY) {
            if (status?.needsPermission == true || message?.startsWith("Health Connect no longer") == true)
                TextButton(enabled = !working, onClick = { ask.launch(hc.permissions) }) { Text("Allow again") }
            TextButton(enabled = !working, onClick = { hc.disable(); on = false; status = null; message = null }) { Text("Turn off (keep what was written)") }
            TextButton(enabled = !working, onClick = {
                working = true
                message = "Removing…"
                scope.launch {
                    message = hc.erase()
                    on = false; status = null; working = false
                }
            }) { Text("Turn off and remove what Ridge wrote", color = scheme.error) }
        }
    }
}

/** Health Connect's "why does Ridge want this" (its permission screens link here): the same list, and nothing else. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrapTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        Frame(null, Icons.Rounded.MonitorHeart, "Ridge and Health Connect",
                            "Ridge only writes to Health Connect, and only once you turn it on in Ridge's Settings. It reads nothing from it. " +
                                "What it writes stays on this phone, for the apps you allow in Health Connect. Ridge has no account and sends this nowhere.",
                            buttons = {
                                Buttons(null, "Close") { finish() }
                                TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE))) }) { Text("Source code") }
                            }) {
                            WRITTEN.forEach { Text("•  $it", style = RidgeType.body) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val SOURCE = "https://github.com/TheCommishDeuce/ridge-helio-strap"
    }
}
