package app.strap.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.strap.BuildConfig
import app.strap.StrapApp
import java.time.Duration
import java.time.Instant

/**
 * Background sync (D30), ported from the old Healthee app: two periodic jobs, collection
 * (strap → phone) and upload (phone → server), each with its own interval; off by default.
 * Android picks the exact moment; these are the minimum gaps.
 */
data class BackgroundPrefs(
    val enabled: Boolean = false,
    val collectMinutes: Int = 30,
    val uploadMinutes: Int = 60,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /**
     * Sync when the app comes to the front and the last complete sync is older than [OPEN_WINDOW].
     * Off by default in the demo build, whose strap is made up: every open would fail.
     */
    val onOpen: Boolean = !BuildConfig.LOCAL_HTTP,
) {
    companion object {
        val COLLECT_OPTIONS = listOf(15, 30, 60, 180)
        val UPLOAD_OPTIONS = listOf(30, 60, 360)

        /** An open within this of the last complete sync syncs nothing: opening is not a reason to pay a fetch. */
        val OPEN_WINDOW: Duration = Duration.ofMinutes(15)
    }
}

/** The last background run: when, which job, and its result sentence. */
data class BackgroundRun(val at: Instant, val job: String, val result: String)

class Background(private val context: Context) {
    private val prefs = context.getSharedPreferences("background", Context.MODE_PRIVATE)

    fun prefs(): BackgroundPrefs = BackgroundPrefs(
        enabled = prefs.getBoolean("enabled", false),
        collectMinutes = prefs.getInt("collect", 30).takeIf { it in BackgroundPrefs.COLLECT_OPTIONS } ?: 30,
        uploadMinutes = prefs.getInt("upload", 60).takeIf { it in BackgroundPrefs.UPLOAD_OPTIONS } ?: 60,
        wifiOnly = prefs.getBoolean("wifi", false),
        chargingOnly = prefs.getBoolean("charging", false),
        onOpen = prefs.getBoolean("open", !BuildConfig.LOCAL_HTTP),
    )

    /**
     * Saves [p] and re-registers the jobs. Saved disabled first, so a job that fires while the
     * plan is half changed finds itself off and does nothing.
     */
    fun save(p: BackgroundPrefs) {
        write(p.copy(enabled = false))
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(COLLECT)
        work.cancelUniqueWork(UPLOAD)
        if (p.enabled) {
            work.enqueueUniquePeriodicWork(COLLECT, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CollectWorker>(Duration.ofMinutes(p.collectMinutes.toLong()))
                    .setConstraints(Constraints.Builder().setRequiresCharging(p.chargingOnly).build()).build())
            work.enqueueUniquePeriodicWork(UPLOAD, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(Duration.ofMinutes(p.uploadMinutes.toLong()))
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresCharging(p.chargingOnly).build()).build())
        }
        write(p)
    }

    fun lastRun(): BackgroundRun? = prefs.getString("last", null)?.split('|', limit = 3)?.let { (at, job, result) ->
        BackgroundRun(Instant.ofEpochMilli(at.toLong()), job, result)
    }

    internal fun record(job: String, result: String) {
        Log.i("strap", "background $job: $result")
        prefs.edit().putString("last", "${System.currentTimeMillis()}|$job|$result").apply()
    }

    private fun write(p: BackgroundPrefs) = prefs.edit()
        .putBoolean("enabled", p.enabled).putInt("collect", p.collectMinutes).putInt("upload", p.uploadMinutes)
        .putBoolean("wifi", p.wifiOnly).putBoolean("charging", p.chargingOnly).putBoolean("open", p.onOpen)
        .commit()

    private companion object {
        const val COLLECT = "collect"
        const val UPLOAD = "upload"
    }
}

/**
 * One background job. Always reports success: a busy strap or a failed connection waits for the
 * next period rather than piling up WorkManager retries; the outcome is recorded for Settings.
 */
abstract class BackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val app get() = applicationContext as StrapApp
    abstract val job: String

    abstract suspend fun work(): SyncState.Finished?

    override suspend fun doWork(): Result {
        if (!app.background.prefs().enabled) return Result.success()
        val outcome = work()
        app.background.record(job, when {
            outcome == null -> "Skipped: the strap was busy for over 3 minutes."
            else -> outcome.failure ?: "Done."
        })
        return Result.success()
    }
}

class CollectWorker(context: Context, params: WorkerParameters) : BackgroundWorker(context, params) {
    override val job = "Collection"

    override suspend fun work(): SyncState.Finished? {
        if (app.vault.load() == null) return SyncState.Finished("No strap paired yet.")
        if (applicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return SyncState.Finished("Nearby devices permission is off, so the strap can't be reached.")
        }
        return app.syncRunner.collect()
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : BackgroundWorker(context, params) {
    override val job = "Upload"

    override suspend fun work(): SyncState.Finished? = app.syncRunner.upload()
}
