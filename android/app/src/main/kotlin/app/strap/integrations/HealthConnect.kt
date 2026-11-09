package app.strap.integrations

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import app.strap.store.LocalStore
import app.strap.sync.Owner
import app.strap.sync.PhoneScience
import strap.science.heartRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes Ridge's days to Health Connect (D45), for the other apps on the phone that read it.
 * Write-only: Ridge asks for no read permission and takes nothing from Health Connect. Off until
 * the owner turns it on in Settings; then every day the science works out again is written again.
 */
class HealthConnect(private val context: Context, private val store: LocalStore, private val science: PhoneScience, private val owner: Owner) {
    private val lock = Mutex()

    enum class Availability { READY, NEEDS_UPDATE, MISSING }

    val permissions: Set<String> = HealthExport.TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.READY
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
        else -> Availability.MISSING
    }

    val enabled: Boolean get() = store.metaValue(ENABLED) == "1"

    /** The permissions the owner has not granted; empty when Ridge may write everything it writes. */
    suspend fun missingPermissions(): Set<String> =
        if (availability() != Availability.READY) permissions else permissions - client().permissionController.getGrantedPermissions()

    /** Turns the export on: the last [BACKFILL_DAYS] days go first, then every day as it changes. */
    fun enable(today: LocalDate = LocalDate.now()) {
        store.setMetaValue(ENABLED, "1")
        touched(today.minusDays(BACKFILL_DAYS))
    }

    /** Stops writing. What is in Health Connect stays; [erase] removes it. */
    fun disable() {
        store.setMetaValue(ENABLED, "")
        store.setMetaValue(PENDING, "")
    }

    /** Days from [from] on changed (null: only today moved on) and are owed to Health Connect. */
    fun touched(from: LocalDate?) {
        if (!enabled) return
        val day = (from ?: LocalDate.now()).toEpochDay()
        val held = store.metaValue(PENDING)?.toLongOrNull()
        if (held == null || day < held) store.setMetaValue(PENDING, day.toString())
    }

    /** Writes every owed day through today. Returns the failure, or null; never throws. */
    suspend fun export(now: Instant = Instant.now()): String? = lock.withLock {
        if (!enabled) return null
        try {
            if (availability() != Availability.READY) return fail("Health Connect is not available on this phone.")
            val zones = owner.zones() ?: return null
            val today = zones.dateOf(now)
            // Never further back than the backfill: an edit to an old day is not worth rewriting months.
            val first = maxOf(store.metaValue(PENDING)?.toLongOrNull()?.let(LocalDate::ofEpochDay) ?: today, today.minusDays(BACKFILL_DAYS))
            val client = client()
            val missing = permissions - client.permissionController.getGrantedPermissions()
            if (missing.isNotEmpty()) return fail("Health Connect no longer lets Ridge write. Open this page and allow it again.", NEEDS_PERMISSION)
            val s = science.over(first.minusDays(1), today)
            var written = 0
            var day = first
            while (!day.isAfter(today)) {
                val bounds = zones.bounds(day)
                val records = HealthExport.day(
                    day, bounds, ZoneId.of(zones.zoneOf(day)), { metric -> store.derived.get(day, metric)?.value },
                    s.data.sleepSessions(bounds.first, bounds.second).filter { it.end >= bounds.first && it.end < bounds.second },
                    s.data.workouts(bounds.first, bounds.second), now, s.heartRate(bounds.first, bounds.second),
                )
                // A workout hidden or a night re-cut since the last write: what Ridge wrote for it goes.
                val ids = HealthExport.SESSION_TYPES.associateWith { type -> records.filter { type.isInstance(it) }.mapNotNull { it.metadata.clientRecordId } }
                val key = "$SESSIONS$${day.toEpochDay()}"
                val before = store.metaValue(key).orEmpty().split('|').map { it.split(',').filter(String::isNotEmpty) }
                HealthExport.SESSION_TYPES.forEachIndexed { i, type ->
                    val gone = before.getOrElse(i) { emptyList() } - ids.getValue(type).toSet()
                    if (gone.isNotEmpty()) client.deleteRecords(type, recordIdsList = emptyList(), clientRecordIdsList = gone)
                }
                if (records.isNotEmpty()) client.insertRecords(records)
                store.setMetaValue(key, HealthExport.SESSION_TYPES.joinToString("|") { ids.getValue(it).joinToString(",") })
                written += records.size
                day = day.plusDays(1)
            }
            store.setMetaValue(PENDING, "")
            store.setMetaValue(STATUS, "ok@${now.toEpochMilli()}@$written")
            Log.i("strap", "health connect: $written records for $first..$today")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Health Connect answers with SecurityException, IOException, RemoteException or IllegalStateException; none may fail a sync.
            fail(e.message ?: e::class.simpleName ?: "Health Connect refused the write.")
        }
    }

    /** Removes everything Ridge wrote (Health Connect only lets an app delete its own records) and turns the export off. */
    suspend fun erase(): String? = lock.withLock {
        try {
            disable()
            if (availability() != Availability.READY) return null
            val client = client()
            val all = androidx.health.connect.client.time.TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(86_400))
            HealthExport.TYPES.forEach { type: kotlin.reflect.KClass<out Record> -> client.deleteRecords(type, all) }
            store.setMetaValue(STATUS, "")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Health Connect refused."
        }
    }

    /** What Settings shows: when it last wrote and how much, or why it could not. */
    fun status(): Status? {
        if (!enabled) return null
        val parts = store.metaValue(STATUS)?.takeIf { it.isNotEmpty() }?.split('@', limit = 3) ?: return Status(null, null, 0, false)
        val at = parts.getOrNull(1)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        val ok = parts[0] == "ok"
        return Status(at, parts.getOrNull(2).takeIf { !ok }, if (ok) parts.getOrNull(2)?.toIntOrNull() ?: 0 else 0, parts[0] == NEEDS_PERMISSION)
    }

    data class Status(val at: Instant?, val failure: String?, val records: Int, val needsPermission: Boolean)

    private fun client() = HealthConnectClient.getOrCreate(context)

    private fun fail(message: String, kind: String = "failed"): String {
        store.setMetaValue(STATUS, "$kind@${Instant.now().toEpochMilli()}@$message")
        Log.i("strap", "health connect: $message")
        return message
    }

    companion object {
        /** How far back turning it on reaches, and the furthest any rewrite goes. */
        const val BACKFILL_DAYS = 30L
        private const val ENABLED = "hc_enabled"
        private const val PENDING = "hc_pending_from" // epoch day
        private const val STATUS = "hc_status" // "ok@ms@records", "failed@ms@message", "permission@ms@message"
        private const val SESSIONS = "hc_sessions" // + "$epochDay" → "sleep ids|workout ids"
        private const val NEEDS_PERMISSION = "permission"
    }
}
