package app.strap.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import app.strap.StrapApp
import app.strap.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the widgets current (D39). They change only when their numbers can: after every derive
 * (which follows every sync), when a sync starts or ends (the "Syncing" line), at midnight (a new
 * day), and when the last sync turns [STALE_AFTER] old. Never on a system timer.
 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Watches the science and the sync for as long as the process lives; started by the app. */
    fun watch(app: StrapApp) {
        val syncing = app.syncRunner.state.map { it is SyncState.Connecting || it is SyncState.Running }.distinctUntilChanged()
        scope.launch { combine(app.science.version, syncing) { v, s -> v to s }.collect { refresh(app) } }
    }

    suspend fun refresh(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        if (manager.getGlanceIds(TodayWidget::class.java).isEmpty() && manager.getGlanceIds(StepsWidget::class.java).isEmpty()) return
        TodayWidget().updateAll(context)
        StepsWidget().updateAll(context)
        schedule(context)
    }

    /** The next moment a widget would change with no sync: midnight, or the last sync turning stale. */
    private fun schedule(context: Context) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        val stale = (context.applicationContext as StrapApp).store.lastSync()?.first?.plus(STALE_AFTER)?.takeIf { it > now }
        val at = listOfNotNull(midnight, stale).min()
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, WidgetTick::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Inexact: a widget a few minutes late is fine, and it needs no exact-alarm permission.
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, at.toEpochMilli(), WINDOW_MS, intent)
    }

    private const val WINDOW_MS = 5 * 60_000L
}

/** The alarm [WidgetUpdater] sets: refreshes the widgets and sets the next. */
class WidgetTick : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { WidgetUpdater.refresh(context) } finally { pending.finish() }
        }
    }
}
