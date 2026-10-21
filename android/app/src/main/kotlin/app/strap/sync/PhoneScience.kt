package app.strap.sync

import android.util.Log
import app.strap.api.BackupServer
import app.strap.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import strap.science.MemoryData
import strap.science.Science
import strap.science.Zones
import strap.science.rawFrom
import strap.science.rederive
import java.time.Instant
import java.time.LocalDate

/**
 * The phone's own derived numbers (D34 S3), kept in [LocalStore.derived]. After each sync the
 * days from the earliest one it touched are worked out again; a changed profile or weight
 * re-derives the whole era. Each run reads only the raw data those days need
 * ([strap.science.RAW_LOOKBACK_DAYS] before the first), and so do the screens ([at]).
 *
 * The phone's era starts [ERA_MARGIN_DAYS] after its first sync; before it the server holds data
 * the phone never had (the old app, the Zepp backfill), so those days' rows are copied from the
 * server once and serve as history.
 */
class PhoneScience(private val store: LocalStore, private val server: () -> BackupServer?, private val owner: Owner) {
    /** Set by the app: copies the server's raw history once (Restore.backfill). */
    var backfill: (suspend () -> String?)? = null
    private val lock = Mutex()

    /** The science the last update left: its raw data from [Window.from] on, to the present. */
    private class Window(val from: Instant, val science: Science)

    @Volatile private var recent: Window? = null

    /** One older window, for a screen showing days before [recent] reaches: ((its start, last day), science). */
    @Volatile private var older: Pair<Pair<Instant, LocalDate>, Science>? = null
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Moves on after every update: the screens reload on it. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** The science that shows [day]: its summary and charts, the baselines over earlier days included. */
    suspend fun at(day: LocalDate): Science = window(day) { it.rawFrom(day) }

    /** The science over the raw data of [first] through [last] only (a chart's range, its workouts). */
    suspend fun over(first: LocalDate, last: LocalDate): Science = window(last) { it.start(first) }

    /** Waits for the first catch-up, so the stored rows are current. */
    suspend fun ready() {
        if (recent == null) catchUp()
    }

    /** A science over the raw data from [need] until two days after [last]: the last update's when it reaches back far enough. */
    private suspend fun window(last: LocalDate, need: (Zones) -> Instant): Science {
        ready()
        val zones = owner.zones() ?: return Science(MemoryData(Zones(java.time.ZoneId.systemDefault().id)), store.derived)
        val from = need(zones)
        recent?.takeIf { it.from <= from }?.let { return it.science }
        older?.takeIf { it.first == from to last }?.let { return it.second }
        return withContext(Dispatchers.Default) {
            Science(store.scienceData(zones, owner.profile(), owner.weights(), from, zones.start(last.plusDays(2))), store.derived)
        }.also { older = (from to last) to it }
    }

    /**
     * The first day the phone works out itself: the first day it holds data for. Only a phone that
     * grew up beside its server and has not copied the server's history yet (it holds only what its
     * own syncs fetched) starts two days after its first sync.
     */
    fun era(zones: Zones): LocalDate? {
        val complete = store.metaValue(Restore.RESTORED) != null || store.metaValue(Restore.BACKFILLED) != null || server() == null
        return if (complete) store.firstData()?.let(zones::dateOf)
        else store.firstSync()?.let { zones.dateOf(it).plusDays(ERA_MARGIN_DAYS) }
    }

    /**
     * Brings the stored rows up to date after new data from [touched] on (null: the whole era),
     * and whatever the store holds that is not derived yet. Returns null when done, else why not
     * (no profile copy yet, no sync yet).
     */
    suspend fun update(touched: Instant?): String? = derive(whole = touched == null, touched)

    /** Works out only what the store holds that is not derived yet (a sync's data); nothing when there is none. */
    suspend fun catchUp(): String? = derive(whole = false, touched = null)

    private suspend fun derive(whole: Boolean, touched: Instant?): String? = lock.withLock {
        withContext(Dispatchers.Default) {
            owner.seed()?.let { return@withContext "the owner's data is not copied yet: $it" }
            if (server() == null) owner.ensureBaseZone()
            val zones = owner.zones() ?: return@withContext "no timezone yet"
            // A phone that grew up beside its server copies the server's raw history once.
            backfill?.invoke()?.let { log("history not copied yet: $it") }
            val era = era(zones) ?: return@withContext "no data yet"
            // The profile, the weights and the zones reach every day: any change re-derives the era.
            val inputs = owner.fingerprint().hashCode().toString()
            val pending = store.underivedFrom()
            val from = when {
                whole || store.metaValue(INPUTS) != inputs || store.derivedThrough() == null -> era
                else -> listOfNotNull(touched, pending).minOrNull()?.let { maxOf(era, zones.dateOf(it)) }
            }
            if (from == null && recent != null) return@withContext null
            val started = System.nanoTime()
            // From the window today's screens need too, so the derive's data serves them after.
            val window = zones.rawFrom(minOf(from ?: LocalDate.MAX, zones.dateOf(Instant.now())))
            val science = Science(store.scienceData(zones, owner.profile(), owner.weights(), window), store.derived)
            val days = if (from == null) emptyList() else store.batch { science.rederive(since = from) }
            pending?.let(store::clearUnderived)
            recent = Window(window, science)
            older = null
            _version.value++
            if (from != null) {
                store.setMetaValue(INPUTS, inputs)
                store.setMetaValue(DERIVED_AT, Instant.now().toEpochMilli().toString())
            }
            log("derived ${days.size} days from ${from ?: "nothing new"} in ${(System.nanoTime() - started) / 1_000_000} ms")
            null
        }
    }

    private fun log(line: String) = Log.i("strap", "science: $line")

    companion object {
        /** The old app synced until the day after this phone's first sync (P7). */
        const val ERA_MARGIN_DAYS = 2L
        private const val INPUTS = "science_inputs"
        private const val DERIVED_AT = "science_derived_at"

        /** Every metric the derive passes write (spec/02). */
        val METRICS = listOf(
            "rhr_daily", "hrv_sleep_avg", "spo2_overnight", "spo2_overnight_min", "respiratory_rate_sleep",
            "sleep_health_score_4dim", "sleep_dim_duration", "sleep_dim_efficiency", "sleep_dim_timing", "sleep_dim_regularity",
            "sleep_regularity_index", "sleep_need_min", "sleep_debt_min", "mvpa_min", "steps_total", "distance_m_daily",
            "total_calories", "active_calories", "basal_calories", "vo2max_estimate", "cardio_load", "hr_zone_minutes", "recovery_score",
        )
    }
}
