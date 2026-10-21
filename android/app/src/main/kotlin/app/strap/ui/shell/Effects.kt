package app.strap.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.strap.StrapApp
import app.strap.api.ApiException
import app.strap.api.Reader
import app.strap.sync.BackgroundPrefs
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.isRunning
import app.strap.ui.today.TodayData
import app.strap.ui.today.loadToday
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// What the shell does on its own: report a sync it watched, sync on open, hold live steps, load Today.

/** A sync that ran while the app watched ends in a snackbar with its result. */
@Composable
fun SyncSnackbar(app: StrapApp, sync: SyncState, showSnack: (String, String?, (() -> Unit)?) -> Unit) {
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(sync) {
        if (sync.isRunning) watched = true
        val done = sync as? SyncState.Finished ?: return@LaunchedEffect
        if (!watched) return@LaunchedEffect
        watched = false
        if (done.background) return@LaunchedEffect // its result is on Settings → Background sync
        val readings = app.store.lastSummary()?.optJSONObject("samples")?.let { s -> s.keys().asSequence().sumOf { s.optInt(it) } }
        showSnack(done.failure ?: ("Synced" + (readings?.let { " · %,d new readings".format(it) } ?: "")), null, null)
    }
}

/**
 * Sync on open (D30): coming to the front syncs when the last complete sync is older than the
 * window. The stamp is on disk, so a cold start does not reset it; a failed sync never counts,
 * so the next open tries again.
 */
@Composable
fun SyncOnOpen(app: StrapApp) {
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val last = app.store.lastCompleteSync()
        val stale = last == null || Duration.between(last, Instant.now()) > BackgroundPrefs.OPEN_WINDOW
        if (stale && app.background.prefs().onOpen && !app.syncRunner.state.value.isRunning) SyncService.start(app)
    }
}

/**
 * Live steps (S4, D34): one connection held while [shell] shows Today for today, released the
 * moment it does not (another tab, a pushed screen, another day, the app in the background).
 */
@Composable
fun LiveSteps(app: StrapApp, shell: Shell) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val showingToday = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && shell.top == null && shell.tab == Tab.TODAY && shell.day == LocalDate.now()
    DisposableEffect(showingToday) {
        if (showingToday) app.syncRunner.startLive() else app.syncRunner.stopLive()
        onDispose { app.syncRunner.stopLive() }
    }
}

/** Today's data for [day], or why it could not load: shared by Today and Recovery, reloaded after a sync and every derive. */
class TodayLoad(val data: TodayData?, val error: String?)

@Composable
fun rememberToday(api: Reader, day: LocalDate, sync: SyncState, version: Int): State<TodayLoad> {
    val state = remember { mutableStateOf(TodayLoad(null, null)) }
    LaunchedEffect(api, day, sync is SyncState.Finished, version) {
        state.value = try {
            TodayLoad(loadToday(api, day), null)
        } catch (e: ApiException) {
            TodayLoad(state.value.data, e.message)
        }
    }
    return state
}
