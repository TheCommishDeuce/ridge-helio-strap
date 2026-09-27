package strap.protocol.sync

import strap.protocol.fetch.FetchFailure
import strap.protocol.fetch.FetchResult
import strap.protocol.model.DailyTotals
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import strap.protocol.model.SleepSession
import strap.protocol.model.Workout
import strap.protocol.parse.FetchType
import strap.protocol.parse.SleepParser
import strap.protocol.parse.WorkoutParser
import strap.protocol.session.StrapSession
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * One full sync, in the proven order (spec/01 §6 "Fetch plan"): daily totals first, then
 * each metric from its watermark, the one-shot stress backfill, sleep, workouts. The first
 * failing fetch ends the sync; everything collected before it is returned with the failure.
 */
public class StrapSync(
    private val session: StrapSession,
    private val now: () -> Instant = Instant::now,
    private val onProgress: (SyncProgress) -> Unit = {},
) {
    private val samples = mutableListOf<Sample>()
    private val sleep = sortedMapOf<Instant, SleepSession>()
    private val workouts = sortedMapOf<Instant, Workout>()
    private val stats = mutableListOf<FetchResult>()
    private var step = 0
    private var stressBackfillRan = false
    private var napBackfillRan = false

    public suspend fun run(window: SyncWindow): SyncResult {
        progress("daily step counter")
        session.requestDailyTotals()
        if (!session.hasFetchChannel) return result(session.awaitDailyTotals(), failure = null)
        val failure = fetchHistory(window)
        return result(session.awaitDailyTotals(), failure)
    }

    private suspend fun fetchHistory(window: SyncWindow): FetchFailure? {
        val t = now()
        for ((code, label, watermark) in SyncPlan.METRICS) {
            progress(label)
            fetchMetric(code, window.lastSampleAt[watermark], t)?.let { return it }
        }
        progress("stress backfill")
        if (!window.stressBackfillDone) {
            fetch(FetchType.STRESS, SyncPlan.stressBackfillSince(t))?.let { return it }
            stressBackfillRan = true
        }
        progress("sleep")
        fetch(FetchType.SLEEP, SyncPlan.sleepSince(window.lastSleepStart, window.napBackfillDone, t))?.let { return it }
        napBackfillRan = !window.napBackfillDone
        progress("workouts")
        return fetch(FetchType.WORKOUTS, SyncPlan.workoutsSince(window.lastWorkoutStart, t), SyncPlan.WORKOUT_ROUNDS, WORKOUT_TIMEOUT)
    }

    /** A metric from its watermark, re-established from 2 days back if a stale feed came back empty. */
    private suspend fun fetchMetric(code: Int, last: Instant?, t: Instant): FetchFailure? {
        val before = samples.size
        fetch(code, SyncPlan.metricSince(last, t))?.let { return it }
        val retry = SyncPlan.staleRetrySince(last, t, gotSamples = samples.size > before) ?: return null
        return fetch(code, retry)
    }

    /** Runs one fetch, keeps whatever it decoded, and returns its failure (null on success). */
    private suspend fun fetch(
        code: Int,
        since: Instant,
        maxRounds: Int = SyncPlan.ROUNDS,
        timeout: kotlin.time.Duration = StrapSession.FETCH_TIMEOUT,
    ): FetchFailure? {
        val r = session.fetch(code, since, maxRounds, timeout)
        stats += r
        when (code) {
            FetchType.SLEEP -> SleepParser.parse(r.raw).forEach { sleep[it.sessionStart] = it }
            FetchType.WORKOUTS -> WorkoutParser.parseStream(r.raw).forEach { workouts[it.start] = it }
            else -> samples += r.samples
        }
        return r.failure
    }

    private fun progress(label: String) = onProgress(SyncProgress(++step, SyncPlan.STEPS, label))

    private fun result(totals: DailyTotals?, failure: FetchFailure?) = SyncResult(
        failure = failure,
        fetchChannelPresent = session.hasFetchChannel,
        samples = samples.toList(),
        sleepSessions = sleep.values.toList(),
        workouts = workouts.values.toList(),
        dailyTotals = totals,
        stressBackfillRan = stressBackfillRan,
        napBackfillRan = napBackfillRan,
        fetches = stats.map { FetchStat(it.code, it.rounds.size, it.rounds, it.samples.size, it.raw.size, it.failure) },
        completedAt = now(),
    )

    private companion object {
        val WORKOUT_TIMEOUT = 150.seconds
    }
}

/** The fetch windows, as pure functions of watermarks and the clock. */
public object SyncPlan {
    /** `(fetch type, progress label, watermark metric)` in fetch order. */
    public val METRICS: List<Triple<Int, String, Metric>> = listOf(
        Triple(FetchType.ACTIVITY, "hr", Metric.HR),
        Triple(FetchType.HRV, "hrv", Metric.HRV),
        Triple(FetchType.SPO2, "spo2", Metric.SPO2),
        Triple(FetchType.SPO2_SLEEP, "spo2_sleep", Metric.SPO2_SLEEP),
        Triple(FetchType.TEMPERATURE, "temperature", Metric.TEMPERATURE_C),
        Triple(FetchType.STRESS, "stress", Metric.STRESS),
        Triple(FetchType.RESPIRATORY_RATE, "sleep_resp", Metric.RESPIRATORY_RATE),
        Triple(FetchType.RESTING_HR, "resting_hr", Metric.RESTING_HR),
        Triple(FetchType.MAX_HR, "max_hr", Metric.MAX_HR),
    )

    /** Totals + each metric + stress backfill + sleep + workouts. */
    public val STEPS: Int = METRICS.size + 4
    public const val ROUNDS: Int = 400
    public const val WORKOUT_ROUNDS: Int = 100
    public val BACKFILL: Duration = Duration.ofDays(30)

    public fun metricSince(last: Instant?, now: Instant): Instant = last?.plusSeconds(60) ?: now.minus(BACKFILL)

    /** A watermark older than 2 days that produced nothing: re-establish the feed from 2 days back. */
    public fun staleRetrySince(last: Instant?, now: Instant, gotSamples: Boolean): Instant? =
        if (!gotSamples && last != null && Duration.between(last, now) > Duration.ofDays(2)) now.minus(Duration.ofDays(2)) else null

    public fun stressBackfillSince(now: Instant): Instant = now.minus(Duration.ofDays(25))

    /** Always re-pulls 2 days (a nap appended later in the day); 14 days once for the nap backfill. */
    public fun sleepSince(lastSleepStart: Instant?, napBackfillDone: Boolean, now: Instant): Instant {
        val last = lastSleepStart ?: now.minus(BACKFILL)
        val floor = now.minus(Duration.ofDays(if (napBackfillDone) 2 else 14))
        return if (last.isAfter(floor)) floor else last
    }

    /** One day of overlap so a same-day bout cannot slip through; 90 days on the first run. */
    public fun workoutsSince(lastWorkoutStart: Instant?, now: Instant): Instant =
        lastWorkoutStart?.minus(Duration.ofDays(1)) ?: now.minus(Duration.ofDays(90))
}

/** What the phone already holds, so the sync knows where to resume. */
public data class SyncWindow(
    val lastSampleAt: Map<Metric, Instant> = emptyMap(),
    val lastSleepStart: Instant? = null,
    val lastWorkoutStart: Instant? = null,
    val stressBackfillDone: Boolean = false,
    val napBackfillDone: Boolean = false,
)

public data class SyncProgress(val step: Int, val total: Int, val label: String)

/** Per-type diagnostics, kept with every sync. */
public data class FetchStat(
    val code: Int,
    val rounds: Int,
    val roundStats: List<strap.protocol.fetch.RoundStat>,
    val samples: Int,
    val rawBytes: Int,
    val failure: FetchFailure?,
)

public data class SyncResult(
    val failure: FetchFailure?,
    val fetchChannelPresent: Boolean,
    val samples: List<Sample>,
    val sleepSessions: List<SleepSession>,
    val workouts: List<Workout>,
    /** Null means the strap did not answer — not zero steps. */
    val dailyTotals: DailyTotals?,
    val stressBackfillRan: Boolean,
    val napBackfillRan: Boolean,
    val fetches: List<FetchStat>,
    val completedAt: Instant,
)
