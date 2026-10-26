package app.strap.ui.widget

import app.strap.StrapApp
import app.strap.sync.SyncState
import strap.science.SleepHeadline
import strap.science.shown
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The bar on the Steps widget fills to this: the bottom of the target Biology shows (D36). */
internal const val STEPS_GOAL = 8000

/** A sync this old shows on every widget, and a tap on it syncs (D39). */
internal val STALE_AFTER: Duration = Duration.ofHours(3)

/** Where the strap stands, shown in a widget's top row when it matters; null when all is well. */
internal sealed interface StrapLine {
    data object NotPaired : StrapLine
    data object Syncing : StrapLine
    data class Stale(val at: Instant?) : StrapLine
}

/**
 * Everything both widgets draw, read once per update from the phone's own numbers (D34), as the
 * screens read them. Energy's [curve] is (ms, value) inside [dayStart]–[dayEnd]; [week] is the
 * last seven days' steps, oldest first.
 */
internal data class WidgetSnapshot(
    val recovery: Int?,
    val energy: Int?,
    val curve: List<Pair<Long, Double>>,
    val dayStart: Long,
    val dayEnd: Long,
    val sleepTitle: String?,
    val asleepMin: Double?,
    val steps: Int?,
    val distanceM: Double?,
    val week: List<Pair<LocalDate, Double?>>,
    val lastSync: Instant?,
    val line: StrapLine?,
)

internal suspend fun loadSnapshot(app: StrapApp, today: LocalDate = LocalDate.now(), now: Instant = Instant.now()): WidgetSnapshot {
    val lastSync = app.store.lastSync()?.first
    val state = app.syncRunner.state.value
    val line = when {
        app.vault.load() == null -> StrapLine.NotPaired
        state is SyncState.Connecting || state is SyncState.Running -> StrapLine.Syncing
        lastSync == null || Duration.between(lastSync, now) > STALE_AFTER -> StrapLine.Stale(lastSync)
        else -> null
    }
    val empty = WidgetSnapshot(null, null, emptyList(), 0, 0, null, null, null, null, emptyList(), lastSync, line)
    if (line == StrapLine.NotPaired) return empty
    val api = app.reader()
    val s = runCatching { api.summary(today) }.getOrNull() ?: return empty
    val night = s.sleep.main
    val asleep = s.sleep.health.shown?.tstMin
        ?: night?.let { n -> listOfNotNull(n.deepMin, n.lightMin, n.remMin).takeIf { it.size == 3 }?.sum()?.toDouble() }
    val steps = runCatching { api.daily("steps_total", today.minusDays(6), today) }.getOrDefault(emptyList()).associate { it.day to it.value }
    val energy = s.energy.shown
    return WidgetSnapshot(
        recovery = s.recovery.shown?.value,
        energy = energy?.value,
        curve = energy?.curve.orEmpty().map { it.at.toEpochMilli() to it.value },
        dayStart = s.start.toEpochMilli(),
        dayEnd = s.end.toEpochMilli(),
        sleepTitle = if (night != null && asleep != null) SleepHeadline.of(night, asleep).kind.title else null,
        asleepMin = asleep,
        steps = s.activity.steps.shown?.value?.toInt(),
        distanceM = s.activity.distanceM.shown?.value,
        week = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to steps[it] },
        lastSync = lastSync,
        line = line,
    )
}
