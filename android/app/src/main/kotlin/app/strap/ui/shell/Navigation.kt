package app.strap.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import app.strap.ui.components.Info
import app.strap.ui.detail.DetailMetric
import java.time.LocalDate

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY("Today", Icons.Outlined.Today, Icons.Filled.Today),
    SLEEP("Sleep", Icons.Outlined.Bedtime, Icons.Filled.Bedtime),
    ACTIVITY("Activity", Icons.AutoMirrored.Outlined.DirectionsRun, Icons.AutoMirrored.Rounded.DirectionsRun),
    JOURNAL("Journal", Icons.Outlined.EditNote, Icons.Filled.EditNote),
    STRAP("Strap", Icons.Outlined.Watch, Icons.Filled.Watch),
}

/** What covers the tabs; the tab under it stays highlighted in the navigation bar. */
sealed interface Pushed {
    data object Recovery : Pushed

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
    var picking by mutableStateOf(false)

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
