package app.strap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.api.Reader
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.DiagnosticsScreen
import app.strap.ui.Loading
import app.strap.ui.activity.ActivityScreen
import app.strap.ui.components.InfoSheet
import app.strap.ui.components.LocalInfo
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.detail.DetailMetric
import app.strap.ui.detail.MetricDetailScreen
import app.strap.ui.isRunning
import app.strap.ui.journal.JournalScreen
import app.strap.ui.settings.BackgroundScreen
import app.strap.ui.settings.ProfileScreen
import app.strap.ui.settings.SettingsScreen
import app.strap.ui.setup.ServerStep
import app.strap.ui.setup.SetupFlow
import app.strap.ui.setup.StrapStep
import app.strap.ui.shell.BottomBar
import app.strap.ui.shell.DayPicker
import app.strap.ui.shell.FullScreen
import app.strap.ui.shell.LiveSteps
import app.strap.ui.shell.Pushed
import app.strap.ui.shell.Refreshable
import app.strap.ui.shell.SyncOnOpen
import app.strap.ui.shell.SyncSnackbar
import app.strap.ui.shell.Tab
import app.strap.ui.shell.TopBar
import app.strap.ui.shell.rememberShell
import app.strap.ui.shell.rememberToday
import app.strap.ui.sleep.SleepScreen
import app.strap.ui.strap.StrapScreen
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.StrapTheme
import app.strap.ui.today.RecoveryContent
import app.strap.ui.today.TodayContent
import app.strap.ui.today.TodayNav
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StrapApp
        setContent { StrapTheme { AppShell(app) } }
    }
}

@Composable
private fun AppShell(app: StrapApp) {
    // First run (D27, D34): until the strap is paired and a server is set (or declined), only setup shows.
    var setUp by remember { mutableStateOf(app.vault.load() != null && (app.vault.loadServer() != null || app.withoutServer)) }
    if (!setUp) {
        FullScreen { SetupFlow(app) { setUp = true } }
        return
    }
    val shell = rememberShell()
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    val lastSync = remember(sync) { app.store.lastSync()?.first }
    // The phone works every number out itself (D34).
    val api: Reader = remember { app.reader() }
    val version by app.science.version.collectAsState()
    val colors = LocalMetricColors.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSnack: (String, String?, (() -> Unit)?) -> Unit = { text, action, onAction ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, action, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
    val top = shell.top
    BackHandler(enabled = top != null) { shell.pop() }
    SyncSnackbar(app, sync, showSnack)
    SyncOnOpen(app)
    LiveSteps(app, shell)
    val live by app.syncRunner.live.collectAsState()
    val today by rememberToday(api, shell.day, sync, version)

    when (top) {
        Pushed.ChangeStrap -> return FullScreen { StrapStep(app, onCancel = shell::pop) { shell.pop() } }
        Pushed.ChangeServer -> return FullScreen { ServerStep(app, onCancel = shell::pop) { app.background.scheduleBackup(true); shell.pop() } }
        else -> {}
    }

    val nav = TodayNav(
        recovery = { shell.push(Pushed.Recovery) },
        sleep = { shell.select(Tab.SLEEP) },
        activity = { shell.select(Tab.ACTIVITY) },
        journal = { shell.select(Tab.JOURNAL) },
        heart = { shell.push(Pushed.Detail(DetailMetric("hr", "Heart rate", "bpm", colors.heartTone, 3 * 60_000L))) },
        stress = { shell.push(Pushed.Detail(DetailMetric("stress", "Stress", "", colors.stressTone, 11 * 60_000L))) },
        steps = { shell.push(Pushed.Detail(DetailMetric("steps", "Steps", "steps", colors.stepsTone, 60 * 60_000L))) },
        selectDay = { shell.day = it },
    )
    val shown = today.data?.takeIf { it.day == shell.day }

    CompositionLocalProvider(LocalInfo provides { shell.sheet = it }, LocalSnackbar provides showSnack) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                // Above the extended FAB on the tabs that have one.
                val fab = top == null && (shell.tab == Tab.JOURNAL || shell.tab == Tab.STRAP)
                SnackbarHost(snackbar, Modifier.padding(bottom = if (fab) 80.dp else 0.dp)) { data ->
                    Snackbar(data, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(horizontal = 16.dp),
                        actionColor = MaterialTheme.colorScheme.inversePrimary)
                }
            },
            topBar = { TopBar(shell, sync, lastSync) { SyncService.start(app) } },
            bottomBar = { BottomBar(shell) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    top == Pushed.Settings -> SettingsScreen(
                        app,
                        onChangeStrap = { shell.push(Pushed.ChangeStrap) },
                        onChangeServer = { shell.push(Pushed.ChangeServer) },
                        onDiagnostics = { shell.push(Pushed.Diagnostics) },
                        onBackground = { shell.push(Pushed.Background) },
                        onProfile = { shell.push(Pushed.Profile) },
                    )
                    top == Pushed.Profile -> ProfileScreen(app.owner) { scope.launch { app.science.update(null) } }
                    top == Pushed.Diagnostics -> DiagnosticsScreen(app)
                    top == Pushed.Background -> BackgroundScreen(app)
                    top is Pushed.Detail -> MetricDetailScreen(api, top.metric, shell.day)
                    top == Pushed.Recovery -> shown?.let {
                        RecoveryContent(it, { d -> shell.day = d }) {
                            shell.push(Pushed.Detail(DetailMetric("hrv_sdnn", "HRV (SDNN)", "ms", colors.recoveryTone, 8 * 3_600_000L)))
                        }
                    } ?: Loading(today.error)
                    else -> Refreshable(sync.isRunning, onRefresh = { SyncService.start(app) }) {
                        when (shell.tab) {
                            Tab.TODAY -> shown?.let { TodayContent(it, nav, liveSteps = live?.steps) } ?: Loading(today.error)
                            Tab.SLEEP -> SleepScreen(api, (sync is SyncState.Finished) to version)
                            Tab.ACTIVITY -> ActivityScreen(api, (sync is SyncState.Finished) to version)
                            Tab.JOURNAL -> JournalScreen(api, app.owner) { at -> app.science.update(at) }
                            Tab.STRAP -> StrapScreen(app, sync)
                        }
                    }
                }
            }
        }
    }
    shell.sheet?.let { InfoSheet(it) { shell.sheet = null } }
    if (shell.picking) DayPicker(shell.day, onDismiss = { shell.picking = false }) { shell.day = it; shell.picking = false }
}
