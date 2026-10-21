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
 * Background work. Collection (strap → phone, D30) is the owner's choice: off by default, with
 * its own interval. The backup (phone → server, D34 S5) runs whenever a server is set, once a day
 * while charging on Wi-Fi: the server is cold storage, so one upload a night is enough. Android
 * picks the exact moment; these are the minimum gaps.
 */
data class BackgroundPrefs(
    val enabled: Boolean = false,
    val collectMinutes: Int = 30,
    val chargingOnly: Boolean = false,
    /**
     * Sync when the app comes to the front and the last complete sync is older than [OPEN_WINDOW].
     * Off by default in the demo build, whose strap is made up: every open would fail.
     */
    val onOpen: Boolean = !BuildConfig.LOCAL_HTTP,
) {
    companion object {
        val COLLECT_OPTIONS = listOf(15, 30, 60, 180)

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
        if (p.enabled) {
            work.enqueueUniquePeriodicWork(COLLECT, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CollectWorker>(Duration.ofMinutes(p.collectMinutes.toLong()))
                    .setConstraints(Constraints.Builder().setRequiresCharging(p.chargingOnly).build()).build())
        }
        write(p)
    }

    /**
     * The nightly backup, registered while a server is set. KEEP: registering again (every app
     * start) leaves the running schedule alone. The hourly upload job before D34 is cancelled.
     */
    fun scheduleBackup(server: Boolean) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(OLD_UPLOAD)
        if (!server) {
            work.cancelUniqueWork(BACKUP)
            return
        }
        work.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(Duration.ofDays(1))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()).build())
    }

    fun lastRun(): BackgroundRun? = prefs.getString("last", null)?.split('|', limit = 3)?.let { (at, job, result) ->
        BackgroundRun(Instant.ofEpochMilli(at.toLong()), job, result)
    }

    internal fun record(job: String, result: String) {
        Log.i("strap", "background $job: $result")
        prefs.edit().putString("last", "${System.currentTimeMillis()}|$job|$result").apply()
    }

    private fun write(p: BackgroundPrefs) = prefs.edit()
        .putBoolean("enabled", p.enabled).putInt("collect", p.collectMinutes)
        .putBoolean("charging", p.chargingOnly).putBoolean("open", p.onOpen)
        .commit()

    private companion object {
        const val COLLECT = "collect"
        const val OLD_UPLOAD = "upload"
        const val BACKUP = "backup"
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

/** The nightly backup: on whenever a server is set, whatever the background-sync switch says. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StrapApp
        val outcome = app.syncRunner.upload()
        app.background.record("Backup", outcome?.failure ?: if (outcome == null) "Skipped: another upload was running." else "Done.")
        return Result.success()
    }
}
