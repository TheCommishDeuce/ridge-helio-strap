package app.strap.sync

import android.util.Log
import app.strap.api.ApiException
import app.strap.api.BackupServer
import app.strap.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/**
 * A new phone starts from its server's backup (D34 S5): every sample, night, workout and day
 * counter, the profile, the journal and the zones, then works every number out itself. Only into
 * an empty store, so nothing the strap delivered here can clash; the strap then syncs on from
 * the newest restored reading.
 */
class Restore(private val store: LocalStore, private val server: () -> BackupServer?, private val owner: Owner, private val science: PhoneScience) {
    /** How much the server holds, for the offer: (samples, first night or null). Null when it cannot say. */
    suspend fun offer(): Pair<Long, Instant?>? = try {
        val r = (server() ?: return null).backupRecords()
        r.getLong("samples") to r.optJSONArray("sleep")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.getLong("start_ts")?.let(Instant::ofEpochMilli)
    } catch (_: ApiException) {
        null
    }

    /** Null on success, else why it stopped. Safe to run again: every write replaces. */
    suspend fun run(onProgress: (String) -> Unit): String? {
        copy(merge = false, onProgress)?.let { return it }
        store.setMetaValue(RESTORED, "${Instant.now().toEpochMilli()}")
        onProgress("Working every day out on this phone")
        return science.update(null)?.let { "Restored, but the numbers could not be worked out yet: $it" }
    }

    /**
     * For a phone that grew up beside its server (D34 S6): the raw data the server holds that this
     * phone never had (the old app's history, the Zepp backfill), so it works out its whole
     * history itself instead of keeping the server's numbers. What the strap gave this phone, and
     * the profile and journal it owns, are kept. Once; null on success.
     */
    suspend fun backfill(again: Boolean = false): String? {
        if (server() == null) return null
        if (!again && (store.metaValue(RESTORED) != null || store.metaValue(BACKFILLED) != null)) return null
        copy(merge = true) { Log.i("strap", "backfill: $it") }?.let { return it }
        store.clearDerived()
        store.setMetaValue(BACKFILLED, "${Instant.now().toEpochMilli()}")
        return null
    }

    private suspend fun copy(merge: Boolean, onProgress: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val api = server() ?: return@withContext "No server is connected."
        try {
            onProgress("Reading the profile, journal, nights and workouts")
            val records = api.backupRecords()
            store.batch {
                if (!merge) owner.restore(records)
                records.optJSONArray("sleep")?.let { a -> for (i in 0 until a.length()) store.restoreSleep(a.getJSONObject(i), replace = !merge) }
                records.optJSONArray("workouts")?.let { a -> for (i in 0 until a.length()) store.restoreWorkout(a.getJSONObject(i), replace = !merge) }
                records.optJSONArray("daily_totals")?.let { a -> for (i in 0 until a.length()) store.restoreTotal(a.getJSONObject(i)) }
            }
            val total = records.getLong("samples")
            var done = 0L
            var cursor: JSONObject? = null
            do {
                onProgress("Reading samples: %,d of %,d".format(done, total))
                val page = api.backupSamples(cursor?.getLong("after_ts"), cursor?.getString("after_metric") ?: "")
                val rows = page.getJSONArray("samples")
                store.restoreSamples(List(rows.length()) { i -> rows.getJSONArray(i) }.mapNotNull { r ->
                    PHONE_METRICS[r.getString(1)]?.let { name -> Triple(name, r.getLong(0), r.getDouble(2)) }
                }, replace = !merge)
                done += rows.length()
                cursor = page.optJSONObject("next")
            } while (cursor != null)
            Log.i("strap", "${if (merge) "backfill" else "restore"}: $done samples")
            null
        } catch (e: ApiException) {
            e.message ?: "The server stopped answering."
        }
    }

    companion object {
        const val RESTORED = "restored_at"
        const val BACKFILLED = "backfilled_at"

        /** The server's metric names → this phone's (the inverse of LocalStore.SERVER_METRICS; spo2 stays spo2). */
        val PHONE_METRICS: Map<String, String> = LocalStore.SERVER_METRICS.entries.filter { it.key != "spo2_sleep" }.associate { (phone, server) -> server to phone }
    }
}
