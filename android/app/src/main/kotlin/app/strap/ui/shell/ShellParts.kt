package app.strap.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.strap.ui.theme.LocalRidgeColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

// The shell's small parts: the full-screen frame, the sync line, pull to refresh, the day picker and its labels.

/** Setup screens: no top bar, no navigation bar. */
@Composable
internal fun FullScreen(content: @Composable () -> Unit) {
    // A Surface, not a bare Box: it sets the content colour that plain Text reads.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) { content() }
    }
}

/** The 4 dp sync progress line under the top bar; its width follows the sync over 0.6 s. */
@Composable
internal fun SyncBar(progress: Float?) {
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
internal fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
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

internal val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM")

/** Today / Yesterday / the weekday within the last week / a date. */
internal fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day > today.minusDays(7) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> day.format(if (day.year == today.year) SHORT_DATE else DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

internal fun clock(at: Instant): String {
    val t = at.atZone(ZoneId.systemDefault())
    return when (t.toLocalDate()) {
        LocalDate.now() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        LocalDate.now().minusDays(1) -> "yesterday " + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    }
}
