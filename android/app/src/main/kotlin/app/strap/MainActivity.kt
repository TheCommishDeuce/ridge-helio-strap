package app.strap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strap.api.ApiClient
import app.strap.api.ApiException
import app.strap.sync.SyncService
import app.strap.sync.SyncState
import app.strap.ui.DiagnosticsScreen
import app.strap.ui.activity.ActivityScreen
import app.strap.ui.journal.JournalScreen
import app.strap.ui.sleep.SleepScreen
import app.strap.ui.strap.StrapScreen
import app.strap.ui.detail.DetailMetric
import app.strap.ui.detail.MetricDetailScreen
import app.strap.ui.theme.LocalMetricColors
import app.strap.ui.theme.StrapTheme
import app.strap.ui.today.TodayNav
import app.strap.ui.today.TodayContent
import app.strap.ui.today.TodayData
import app.strap.ui.today.loadToday
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StrapApp
        setContent { StrapTheme { AppShell(app) } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Outlined.Today),
    SLEEP("Sleep", Icons.Outlined.Bedtime),
    ACTIVITY("Activity", Icons.Outlined.DirectionsRun),
    JOURNAL("Journal", Icons.Outlined.EditNote),
    STRAP("Strap", Icons.Outlined.Watch),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(app: StrapApp) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var diagnostics by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DetailMetric?>(null) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    val sync by app.syncRunner.state.collectAsStateWithLifecycle()
    val colors = LocalMetricColors.current
    BackHandler(enabled = diagnostics || detail != null) {
        when {
            diagnostics -> diagnostics = false
            else -> detail = null
        }
    }
    val nav = TodayNav(
        sleep = { tab = Tab.SLEEP },
        activity = { tab = Tab.ACTIVITY },
        heart = { detail = DetailMetric("hr", "Heart rate", "bpm", colors.heart, 3 * 60_000L) },
        stress = { detail = DetailMetric("stress", "Stress", "", colors.stress, 11 * 60_000L) },
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    when {
                        diagnostics -> Text("Diagnostics")
                        detail != null -> Text(detail!!.title + if (day == LocalDate.now()) "" else " · " + dayLabel(day))
                        tab == Tab.TODAY -> DaySwitcher(day) { day = it }
                        else -> Text(tab.label)
                    }
                },
                navigationIcon = {
                    val busy = sync !is SyncState.Idle && sync !is SyncState.Finished
                    IconButton(enabled = !busy, onClick = { SyncService.start(app) }) {
                        if (busy) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Sync, "Sync now")
                    }
                },
                actions = { IconButton(onClick = { diagnostics = !diagnostics }) { Icon(Icons.Outlined.Settings, "Diagnostics") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!diagnostics) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t && detail == null, onClick = { if (t == Tab.TODAY && tab == Tab.TODAY && detail == null) day = LocalDate.now(); tab = t; detail = null }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                diagnostics -> DiagnosticsScreen(app)
                detail != null -> app.vault.loadServer()?.let { MetricDetailScreen(ApiClient(it), detail!!, day) } ?: Centered("Add your server first.")
                tab == Tab.TODAY -> TodayTab(app, sync, nav, day)
                tab == Tab.STRAP -> StrapScreen(app.syncRunner)
                else -> {
                    val api = remember(sync) { app.vault.loadServer()?.let(::ApiClient) }
                    val refresh = sync is SyncState.Finished
                    when {
                        api == null -> Centered("Add your server under the gear (Diagnostics) first.")
                        tab == Tab.SLEEP -> SleepScreen(api, refresh)
                        tab == Tab.ACTIVITY -> ActivityScreen(api, refresh)
                        else -> JournalScreen(api)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayTab(app: StrapApp, sync: SyncState, nav: TodayNav, day: LocalDate) {
    var data by remember { mutableStateOf<TodayData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val link = remember(reload) { app.vault.loadServer() }
    // Reload when a sync finishes: it just uploaded new data and the server re-derived it.
    LaunchedEffect(link, sync is SyncState.Finished, reload, day) {
        if (link == null) return@LaunchedEffect
        try {
            data = loadToday(ApiClient(link), day)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }
    val current = data
    when {
        link == null -> Centered("Add your server under the gear (Diagnostics) to see your day.")
        current != null && current.day == day -> TodayContent(current, nav)
        error != null -> Centered(error!!)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy"))
    }
}

/** ‹ Today › in the title: arrows step a day (never past today), the label opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySwitcher(day: LocalDate, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDay(day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day") }
        Text(dayLabel(day), Modifier.clickable { picking = true }.padding(horizontal = 4.dp))
        IconButton(enabled = day < today, onClick = { onDay(day.plusDays(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day") }
    }
    if (picking) {
        // The picker speaks UTC-midnight millis, which map 1:1 onto epoch days.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.toEpochDay() * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.toEpochDay() * DAY_MS
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { onDay(LocalDate.ofEpochDay(it / DAY_MS)) }; picking = false }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

private const val DAY_MS = 86_400_000L

@Composable
private fun Centered(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
