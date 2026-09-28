package app.strap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.Centered
import app.strap.ui.DiagnosticsScreen
import app.strap.ui.Loading
import app.strap.ui.activity.ActivityScreen
import app.strap.ui.components.Info
import app.strap.ui.components.InfoSheet
import app.strap.ui.components.Infos
import app.strap.ui.components.LocalInfo
import app.strap.ui.components.LocalSnackbar
import app.strap.ui.describe
import app.strap.ui.detail.DetailMetric
import app.strap.ui.detail.MetricDetailScreen
import app.strap.ui.isRunning
import app.strap.ui.journal.JournalScreen
import app.strap.ui.settings.SettingsScreen
import app.strap.ui.setup.ServerStep
import app.strap.ui.setup.SetupFlow
import app.strap.ui.setup.StrapStep
import app.strap.ui.sleep.SleepScreen
import app.strap.ui.strap.StrapScreen
import app.strap.ui.syncProgress
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import app.strap.ui.theme.StrapTheme
import app.strap.ui.today.RecoveryContent
import app.strap.ui.today.TodayContent
import app.strap.ui.today.TodayData
import app.strap.ui.today.TodayNav
import app.strap.ui.today.loadToday
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StrapApp
        setContent { StrapTheme { AppShell(app) } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY("Today", Icons.Outlined.Today, Icons.Filled.Today),
    SLEEP("Sleep", Icons.Outlined.Bedtime, Icons.Filled.Bedtime),
    ACTIVITY("Activity", Icons.AutoMirrored.Outlined.DirectionsRun, Icons.AutoMirrored.Rounded.DirectionsRun),
    JOURNAL("Journal", Icons.Outlined.EditNote, Icons.Filled.EditNote),
    STRAP("Strap", Icons.Outlined.Watch, Icons.Filled.Watch),
}

/** What covers the tabs; the tab under it stays highlighted in the navigation bar. */
private sealed interface Pushed {
    data object Recovery : Pushed

    data class Detail(val metric: DetailMetric) : Pushed

    data object Settings : Pushed

    data object Diagnostics : Pushed

    /** Setup in edit mode, full screen: no top bar or navigation bar. */
    data object ChangeStrap : Pushed

    data object ChangeServer : Pushed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(app: StrapApp) {
    // First run (D27): until both the strap and the server are set, only the setup flow shows.
    var setUp by remember { mutableStateOf(app.vault.load() != null && app.vault.loadServer() != null) }
    if (!setUp) {
        FullScreen { SetupFlow(app) { setUp = true } }
        return
    }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val stack = remember { mutableStateListOf<Pushed>() }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var serverVersion by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<Info?>(null) }
    var picking by remember { mutableStateOf(false) }
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    val lastSync = remember(sync) { app.store.lastSync()?.first }
    val api = remember(serverVersion) { app.vault.loadServer()?.let(::ApiClient) }
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
    val top = stack.lastOrNull()
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    // A sync that ran while the app watched ends in a snackbar with its result.
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(sync) {
        if (sync.isRunning) watched = true
        val done = sync as? SyncState.Finished ?: return@LaunchedEffect
        if (!watched) return@LaunchedEffect
        watched = false
        val readings = app.store.lastSummary()?.optJSONObject("samples")?.let { s -> s.keys().asSequence().sumOf { s.optInt(it) } }
        showSnack(done.failure ?: ("Synced" + (readings?.let { " · %,d new readings".format(it) } ?: "")), null, null)
    }

    // Today's data is shared by Today and Recovery; it reloads when a sync finishes.
    var today by remember { mutableStateOf<TodayData?>(null) }
    var todayError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api, day, sync is SyncState.Finished) {
        if (api == null) return@LaunchedEffect
        try {
            today = loadToday(api, day)
            todayError = null
        } catch (e: ApiException) {
            todayError = e.message
        }
    }

    when (top) {
        Pushed.ChangeStrap -> return FullScreen { StrapStep(app, onCancel = { stack.removeAt(stack.lastIndex) }) { stack.removeAt(stack.lastIndex) } }
        Pushed.ChangeServer -> return FullScreen {
            ServerStep(app, onCancel = { stack.removeAt(stack.lastIndex) }) { serverVersion++; stack.removeAt(stack.lastIndex) }
        }
        else -> {}
    }

    fun push(p: Pushed) { stack.add(p) }
    val nav = TodayNav(
        recovery = { push(Pushed.Recovery) },
        sleep = { tab = Tab.SLEEP; stack.clear() },
        activity = { tab = Tab.ACTIVITY; stack.clear() },
        journal = { tab = Tab.JOURNAL; stack.clear() },
        heart = { push(Pushed.Detail(DetailMetric("hr", "Heart rate", "bpm", colors.heartTone, 3 * 60_000L))) },
        stress = { push(Pushed.Detail(DetailMetric("stress", "Stress", "", colors.stressTone, 11 * 60_000L))) },
        steps = { push(Pushed.Detail(DetailMetric("steps", "Steps", "steps", colors.stepsTone, 60 * 60_000L))) },
        selectDay = { day = it },
    )
    val running = sync.isRunning
    val synced = lastSync?.let { "synced " + clock(it) }
    val subtitle = when {
        running -> describe(sync)
        top == Pushed.Recovery || top is Pushed.Detail -> if (top is Pushed.Detail && day == LocalDate.now()) null else day.format(SHORT_DATE)
        top != null -> null
        tab == Tab.TODAY -> day.format(SHORT_DATE) + (synced?.let { " · $it" } ?: "")
        tab == Tab.STRAP -> "Paired" + (synced?.let { " · $it" } ?: "")
        else -> synced?.replaceFirstChar { it.uppercase() } ?: "Not synced yet"
    }
    val title = when (top) {
        Pushed.Recovery -> "Recovery"
        is Pushed.Detail -> top.metric.title
        Pushed.Settings -> "Settings"
        Pushed.Diagnostics -> "Diagnostics"
        else -> when (tab) {
            Tab.TODAY -> dayTitle(day)
            Tab.STRAP -> "Helio Strap"
            else -> tab.label
        }
    }

    CompositionLocalProvider(LocalInfo provides { sheet = it }, LocalSnackbar provides showSnack) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                // Above the extended FAB on the tabs that have one.
                val fab = top == null && (tab == Tab.JOURNAL || tab == Tab.STRAP)
                SnackbarHost(snackbar, Modifier.padding(bottom = if (fab) 80.dp else 0.dp)) { data ->
                    Snackbar(data, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(horizontal = 16.dp),
                        actionColor = MaterialTheme.colorScheme.inversePrimary)
                }
            },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, style = RidgeType.topTitle)
                                subtitle?.let {
                                    Text(it, style = RidgeType.topSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable(enabled = !running && top == null) { SyncService.start(app) })
                                }
                            }
                        },
                        navigationIcon = {
                            if (top != null) IconButton(onClick = { stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        },
                        actions = {
                            val muted = MaterialTheme.colorScheme.onSurfaceVariant
                            when {
                                top == Pushed.Recovery -> IconButton(onClick = { sheet = Infos.recovery }) { Icon(Icons.Outlined.Info, "About recovery", tint = muted) }
                                top is Pushed.Detail -> IconButton(onClick = { sheet = if (top.metric.isSteps) Infos.detailSteps else Infos.detail }) {
                                    Icon(Icons.Outlined.Info, "Reading the chart", tint = muted)
                                }
                                top == null -> {
                                    if (tab == Tab.TODAY) IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.CalendarMonth, "Pick a day", tint = muted) }
                                    if (tab == Tab.JOURNAL) IconButton(onClick = { sheet = Infos.journal }) { Icon(Icons.Outlined.Info, "About the journal", tint = muted) }
                                    IconButton(onClick = { push(Pushed.Settings) }) { Icon(Icons.Outlined.Settings, "Settings", tint = muted) }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
                    )
                    SyncBar(syncProgress(sync))
                }
            },
            bottomBar = {
                val outline = MaterialTheme.colorScheme.outlineVariant
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.drawBehind { drawLine(outline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
                ) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                if (t == Tab.TODAY && tab == Tab.TODAY && stack.isEmpty()) day = LocalDate.now()
                                tab = t
                                stack.clear()
                            },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label, style = RidgeType.label.copy(fontSize = RidgeType.caption.fontSize)) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    top == Pushed.Settings -> SettingsScreen(
                        app,
                        onChangeStrap = { push(Pushed.ChangeStrap) },
                        onChangeServer = { push(Pushed.ChangeServer) },
                        onDiagnostics = { push(Pushed.Diagnostics) },
                    )
                    top == Pushed.Diagnostics -> DiagnosticsScreen(app)
                    api == null -> Centered("Add your server in Settings (the gear) first.")
                    top is Pushed.Detail -> MetricDetailScreen(api, top.metric, day)
                    top == Pushed.Recovery -> today?.takeIf { it.day == day }?.let { RecoveryContent(it) { d -> day = d } } ?: Loading(todayError)
                    else -> Refreshable(running, onRefresh = { SyncService.start(app) }) {
                        when (tab) {
                            Tab.TODAY -> today?.takeIf { it.day == day }?.let { TodayContent(it, nav) } ?: Loading(todayError)
                            Tab.SLEEP -> SleepScreen(api, sync is SyncState.Finished)
                            Tab.ACTIVITY -> ActivityScreen(api, sync is SyncState.Finished)
                            Tab.JOURNAL -> JournalScreen(api)
                            Tab.STRAP -> StrapScreen(app, sync)
                        }
                    }
                }
            }
        }
    }
    sheet?.let { InfoSheet(it) { sheet = null } }
    if (picking) DayPicker(day, onDismiss = { picking = false }) { day = it; picking = false }
}

/** Setup screens: no top bar, no navigation bar. */
@Composable
private fun FullScreen(content: @Composable () -> Unit) {
    // A Surface, not a bare Box: it sets the content colour that plain Text reads.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) { content() }
    }
}

/** The 4 dp sync progress line under the top bar; its width follows the sync over 0.6 s. */
@Composable
private fun SyncBar(progress: Float?) {
    val width by animateFloatAsState(progress ?: 0f, tween(600), label = "sync")
    Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
        if (progress != null) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(LocalRidgeColors.current.surface3))
            Box(Modifier.fillMaxWidth(width).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/**
 * Pull to refresh on the tabs: past 64 dp a release starts a sync. The indicator is a 48 dp
 * circle whose arrow turns with the pull and flips at the threshold; while a sync runs it
 * rests 12 dp from the top with a spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize().pullToRefresh(refreshing, state, threshold = 64.dp, onRefresh = onRefresh)) {
        content()
        val pulled = state.distanceFraction * 64f // dp
        if (refreshing || pulled > 1f) {
            val y = if (refreshing) 12f else min(pulled, 110f) - 36f
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = y.dp).size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .background(LocalRidgeColors.current.surface3),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Rounded.ArrowDownward, "Pull to sync",
                        Modifier.rotate(if (pulled >= 64f) 180f else pulled * 2.4f).size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = min(1f, pulled / 40f)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(day: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    // The picker speaks UTC-midnight millis, which map 1:1 onto epoch days.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = day.toEpochDay() * DAY_MS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.toEpochDay() * DAY_MS
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) } ?: onDismiss() }) { Text("Show") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        colors = DatePickerDefaults.colors(containerColor = LocalRidgeColors.current.surface3),
    ) { DatePicker(state, colors = DatePickerDefaults.colors(containerColor = LocalRidgeColors.current.surface3)) }
}

private const val DAY_MS = 86_400_000L
private val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM")

/** Today / Yesterday / the weekday within the last week / a date. */
private fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day > today.minusDays(7) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> day.format(if (day.year == today.year) SHORT_DATE else DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

private fun clock(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    return when (t.toLocalDate()) {
        LocalDate.now() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        LocalDate.now().minusDays(1) -> "yesterday " + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}
