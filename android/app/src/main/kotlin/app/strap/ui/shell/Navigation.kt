package app.strap.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.strap.ui.components.CalendarMetric
import app.strap.ui.components.Info
import app.strap.ui.detail.DetailMetric
import java.time.LocalDate

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY("Today", Icons.Outlined.Today, Icons.Filled.Today),
    SLEEP("Sleep", Icons.Outlined.Bedtime, Icons.Filled.Bedtime),
    ACTIVITY("Fitness", Icons.AutoMirrored.Outlined.DirectionsRun, Icons.AutoMirrored.Rounded.DirectionsRun),
    BIOLOGY("Biology", Icons.Outlined.Biotech, Icons.Filled.Biotech),
    STRAP("Strap", Icons.Outlined.Watch, Icons.Filled.Watch),
}

/** What covers the tabs; the tab under it stays highlighted in the navigation bar. */
sealed interface Pushed {
    data object Recovery : Pushed

    /** The day hour by hour, with pinned rings. */
    data object Timeline : Pushed

    /** Caffeine, alcohol and weight, opened from Today's top bar. */
    data object Journal : Pushed

    /** The day's strain: its ring, energy and heart-rate zones. */
    data object Strain : Pushed

    /** One workout, by its start (epoch ms). */
    data class Workout(val startMs: Long) : Pushed

    data class Detail(val metric: DetailMetric) : Pushed

    data object Settings : Pushed

    data object Diagnostics : Pushed

    data object Background : Pushed

    data object Profile : Pushed

    /** Setup in edit mode, full screen: no top bar or navigation bar. */
    data object ChangeStrap : Pushed

    data object ChangeServer : Pushed
}

/** Where the app is: the tab, the screens pushed over it, the day shown, and the open sheet or day picker. */
class Shell {
    var tab by mutableStateOf(Tab.TODAY)
    val stack = mutableStateListOf<Pushed>()
    var day by mutableStateOf(LocalDate.now())
    var sheet by mutableStateOf<Info?>(null)
    /** The open calendar sheet and the ring it starts on; null when closed. */
    var calendar by mutableStateOf<CalendarMetric?>(null)

    /** Bumped on every journal entry made outside the journal, so the day's screens reload. */
    var journalEdits by mutableIntStateOf(0)

    /** The Fitness tab's training calendar is open. */
    var trainingCalendar by mutableStateOf(false)

    val top: Pushed? get() = stack.lastOrNull()

    fun push(p: Pushed) {
        stack.add(p)
    }

    fun pop() {
        stack.removeAt(stack.lastIndex)
    }

    /** Shows [t] with nothing over it; tapping Today again on Today goes back to today. */
    fun select(t: Tab) {
        if (t == Tab.TODAY && tab == Tab.TODAY && stack.isEmpty()) day = LocalDate.now()
        tab = t
        stack.clear()
    }
}

@Composable
fun rememberShell(): Shell = remember { Shell() }
