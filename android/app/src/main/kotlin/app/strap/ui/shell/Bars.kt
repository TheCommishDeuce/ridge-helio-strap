package app.strap.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.strap.sync.SyncState
import app.strap.ui.components.Infos
import app.strap.ui.describe
import app.strap.ui.isRunning
import app.strap.ui.syncProgress
import app.strap.ui.theme.RidgeType
import java.time.Instant
import java.time.LocalDate

/** The top bar's title: the pushed screen's, else the tab's (on Today, the day's). */
private fun Shell.title(): String = when (val top = top) {
    Pushed.Recovery -> "Recovery"
    is Pushed.Detail -> top.metric.title
    Pushed.Settings -> "Settings"
    Pushed.Diagnostics -> "Diagnostics"
    Pushed.Background -> "Background sync"
    Pushed.Profile -> "Profile"
    else -> when (tab) {
        Tab.TODAY -> dayTitle(day)
        Tab.STRAP -> "Helio Strap"
        else -> tab.label
    }
}

/** Under the title: a running sync, else the day shown and when the strap last synced. */
private fun Shell.subtitle(sync: SyncState, lastSync: Instant?): String? {
    val top = top
    val synced = lastSync?.let { "synced " + clock(it) }
    return when {
        sync.isRunning -> describe(sync)
        top == Pushed.Recovery || top is Pushed.Detail -> if (top is Pushed.Detail && day == LocalDate.now()) null else day.format(SHORT_DATE)
        top != null -> null
        tab == Tab.TODAY -> day.format(SHORT_DATE) + (synced?.let { " · $it" } ?: "")
        tab == Tab.STRAP -> "Paired" + (synced?.let { " · $it" } ?: "")
        else -> synced?.replaceFirstChar { it.uppercase() } ?: "Not synced yet"
    }
}

/** The title, a back arrow over a pushed screen, the screen's actions, and the sync line; tapping the subtitle syncs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(shell: Shell, sync: SyncState, lastSync: Instant?, onSync: () -> Unit) {
    val top = shell.top
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(shell.title(), style = RidgeType.topTitle)
                    shell.subtitle(sync, lastSync)?.let {
                        Text(it, style = RidgeType.topSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = !sync.isRunning && top == null, onClick = onSync))
                    }
                }
            },
            navigationIcon = {
                if (top != null) IconButton(onClick = shell::pop) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            },
            actions = {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                when {
                    top == Pushed.Recovery -> IconButton(onClick = { shell.sheet = Infos.recovery }) { Icon(Icons.Outlined.Info, "About recovery", tint = muted) }
                    top is Pushed.Detail -> IconButton(onClick = { shell.sheet = if (top.metric.isSteps) Infos.detailSteps else Infos.detail }) {
                        Icon(Icons.Outlined.Info, "Reading the chart", tint = muted)
                    }
                    top == null -> {
                        if (shell.tab == Tab.TODAY) IconButton(onClick = { shell.picking = true }) { Icon(Icons.Outlined.CalendarMonth, "Pick a day", tint = muted) }
                        if (shell.tab == Tab.JOURNAL) IconButton(onClick = { shell.sheet = Infos.journal }) { Icon(Icons.Outlined.Info, "About the journal", tint = muted) }
                        IconButton(onClick = { shell.push(Pushed.Settings) }) { Icon(Icons.Outlined.Settings, "Settings", tint = muted) }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
        )
        SyncBar(syncProgress(sync))
    }
}

@Composable
fun BottomBar(shell: Shell) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.drawBehind { drawLine(outline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = shell.tab == t,
                onClick = { shell.select(t) },
                icon = { Icon(if (shell.tab == t) t.selectedIcon else t.icon, null) },
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
}
