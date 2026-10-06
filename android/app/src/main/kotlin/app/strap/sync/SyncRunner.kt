package app.strap.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.strap.ble.AndroidStrapLink
import app.strap.pairing.KeyVault
import app.strap.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import strap.protocol.parse.AlarmZones
import strap.protocol.parse.Config
import strap.protocol.parse.CurrentTime
import strap.protocol.auth.HandshakeFailure
import strap.protocol.session.StrapException
import strap.protocol.session.StrapFailure
import strap.protocol.session.StrapSession
import strap.protocol.sync.StrapSync
import strap.protocol.sync.SyncProgress
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** What the sync is doing, for the screen. */
sealed interface SyncState {
    data object Idle : SyncState

    data object Connecting : SyncState

    data class Running(val progress: SyncProgress) : SyncState

    data class Uploading(val detail: String) : SyncState

    /**
     * [failure] null = everything fetched and uploaded; otherwise named, secret-free reasons.
     * [background]: a background job's run, which the screen shows no result message for.
     */
    data class Finished(val failure: String?, val background: Boolean = false) : SyncState
}

/** A strap job's result: [value] on success, else null and a secret-free [failure] sentence. */
data class StrapJob<T>(val value: T?, val failure: String?)

/**
 * Runs one sync end to end: connect → authenticate → fetch → store → disconnect. The ONE
 * owner of the strap connection: a second request while one runs is refused, never queued
 * behind a half-open link (the old app's lease bugs B6–B8).
 */
class SyncRunner(private val context: Context, private val store: LocalStore, private val vault: KeyVault, private val alarms: AlarmKeeper) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val _services = MutableStateFlow<Map<Int, Boolean>?>(null)

    /** Endpoint → encrypted, as the strap announced them on the last sync (diagnostic). */
    val services: StateFlow<Map<Int, Boolean>?> = _services.asStateFlow()
    private val _battery = MutableStateFlow(store.battery())

    /** The newest battery reading and when it was taken; every sync and strap job refreshes it. */
    val battery: StateFlow<LocalStore.Battery?> = _battery.asStateFlow()

    private val _clock = MutableStateFlow<StrapClock?>(null)

    /** The strap clock's offset, measured on every connection before any strap job runs (D30). */
    val clock: StateFlow<StrapClock?> = _clock.asStateFlow()

    private val _probe = MutableStateFlow<List<String>>(emptyList())

    /** The last settings probe: each read request and the strap's raw reply, as hex. */
    val probe: StateFlow<List<String>> = _probe.asStateFlow()

    /**
     * Read-only probe of the strap's settings services (R1.2, spec/01 "Services"): config
     * capabilities and every group it announces, battery details, device info. Sends read
     * requests only; nothing on the strap changes.
     */
    suspend fun probeSettings(): String? {
        val job = strapJob { s ->
            s.requestServices()
            val services = s.awaitServices() ?: return@strapJob listOf("no services list")
            buildList {
                suspend fun ask(endpoint: Int, request: ByteArray, reply: Int): ByteArray? {
                    if (endpoint !in services) return null.also { add("%04x not served".format(endpoint)) }
                    val answer = s.exchange(endpoint, request, reply, encrypt = services[endpoint] == true)
                    add("%04x %s → %s".format(endpoint, request.hex(), answer?.hex() ?: "no reply"))
                    return answer
                }
                val groups = ask(Config.ENDPOINT, Config.capabilitiesRequest, Config.CAPABILITIES_REPLY)?.let(Config::groups).orEmpty()
                for (g in groups) {
                    val decoded = ask(Config.ENDPOINT, Config.readRequest(g), Config.READ_REPLY)?.let(Config::group) ?: continue
                    add("  group %02x v%d%s: %s".format(decoded.group, decoded.version, if (decoded.complete) "" else " (stopped early)",
                        decoded.values.entries.joinToString(" ") { (k, v) -> "%02x=%s".format(k, v) }))
                }
                ask(BATTERY, byteArrayOf(0x03), 0x04)
                ask(DEVICE_INFO, byteArrayOf(0x01), 0x02)
            }
        }
        job.value?.let { lines -> lines.forEach { log("probe $it") }; _probe.value = lines }
        return job.failure
    }

    /** Strap → phone → server. Null when the strap is already busy, else how it ended. */
    suspend fun run(): SyncState.Finished? = exclusive { listOfNotNull(syncOnce(), pushOnce()) }

    /**
     * The background jobs (D30): strap → phone, and phone → server. Each holds the lock so it
     * never interleaves another strap or upload run, and WAITS for a running one (up to
     * [BACKGROUND_WAIT]) rather than skipping: the two jobs often fire together, and a skipped
     * job would wait a whole period.
     */
    suspend fun collect(): SyncState.Finished? = exclusive(background = true) { listOfNotNull(syncOnce()) }

    suspend fun upload(): SyncState.Finished? = exclusive(background = true) { listOfNotNull(pushOnce()) }

    private suspend fun exclusive(background: Boolean = false, work: suspend () -> List<String>): SyncState.Finished? {
        val locked = if (background) withTimeoutOrNull(BACKGROUND_WAIT) { lock.lock() } != null else lock.tryLock()
        if (!locked) return null
        try {
            // Off the main thread: the store writes thousands of rows per sync.
            return SyncState.Finished(withContext(Dispatchers.IO) { work().joinToString(" ").ifEmpty { null } }, background)
                .also { _state.value = it }
        } finally {
            lock.unlock()
        }
    }

    /**
     * One short strap job outside a sync (alarms): connect, authenticate, [block], disconnect.
     * Refused, not queued, while a sync or another job holds the strap.
     */
    suspend fun <T> strapJob(block: suspend (StrapSession) -> T): StrapJob<T> {
        if (!lock.tryLock()) return StrapJob(null, "The strap is busy with a sync. Try again in a moment.")
        try {
            val (value, failure) = withContext(Dispatchers.IO) {
                onStrap { session, link -> block(session).also { link.batteryPercent()?.let { saveBattery(it) } } }
            }
            return StrapJob(value, failure)
        } finally {
            lock.unlock()
        }
    }

    /** The failure sentence, or null on a complete sync. */
    private suspend fun syncOnce(): String? {
        _state.value = SyncState.Connecting
        val (failure, connectFailure) = onStrap { session, link ->
            session.requestServices()
            val started = System.nanoTime()
            val result = StrapSync(session) { _state.value = SyncState.Running(it) }.run(store.window())
            val battery = link.batteryPercent()
            store.save(result, battery)
            battery?.let { _battery.value = LocalStore.Battery(it, result.completedAt) }
            session.services.value?.let { _services.value = it }
            // Keeps zoned alarms right across DST and clock changes; only after a complete
            // fetch, so a struggling link is not asked for more.
            if (result.failure == null) _clock.value?.let { alarms.reconcile(session, it, ::log) }
            log("sync done in %.1f s: %d samples, %d sleep, %d workouts, failure=%s".format(
                (System.nanoTime() - started) / 1e9, result.samples.size, result.sleepSessions.size, result.workouts.size, result.failure))
            result.failure?.let { "Sync stopped early: $it (what arrived was saved)" }
        }
        return connectFailure ?: failure
    }

    /**
     * The strap clock's offset: its Current Time reading against the phone's true time. A strap
     * without that reading is assumed to run on the phone's zone, as the history fetch does.
     */
    private suspend fun measureClock(link: AndroidStrapLink): StrapClock {
        val now = Instant.now()
        val raw = link.currentTime()
        val offset = raw?.let(CurrentTime::parse)?.let { AlarmZones.offsetOf(it, now) }
        log("strap clock: ${raw?.hex() ?: "not read"} → ${offset ?: "assumed phone zone"}")
        return offset?.let { StrapClock(it, measured = true) } ?: StrapClock(ZoneId.systemDefault().rules.getOffset(now), measured = false)
    }

    private fun saveBattery(percent: Int) {
        val reading = LocalStore.Battery(percent, Instant.now())
        store.saveBattery(reading)
        _battery.value = reading
    }

    /** Runs [block] on an authenticated session: its value, or null and the failure sentence. */
    private suspend fun <T> onStrap(block: suspend (StrapSession, AndroidStrapLink) -> T): Pair<T?, String?> {
        val pairing = vault.load() ?: return null to "No strap paired yet."
        val link = try {
            AndroidStrapLink.connect(context, pairing.mac, ::log)
        } catch (e: IOException) {
            return null to "Could not reach the strap: ${e.message}".also(::log)
        }
        return try {
            coroutineScope {
                val session = StrapSession.open(link, pairing.authKey, this, log = ::log)
                _clock.value = measureClock(link)
                try {
                    block(session, link) to null
                } finally {
                    session.close()
                }
            }
        } catch (e: StrapException) {
            val f = e.failure
            null to (
                if (f is StrapFailure.HandshakeRefused && f.reason == HandshakeFailure.WrongAuthKey) {
                    "The strap rejected the key: it was paired again, so its key changed. Get the new key with tools/keyfetch and pair again."
                } else {
                    "The strap refused the connection: $f"
                }
            ).also(::log)
        } catch (e: IOException) {
            null to "The connection dropped: ${e.message}".also(::log)
        } finally {
            link.close()
        }
    }

    /** Uploads whatever is unsent, even after a failed strap sync. Null on success or when no server is set. */
    private fun pushOnce(): String? {
        val link = vault.loadServer() ?: return null
        // Android 17 lets the owner withhold network access, and a withheld INTERNET permission
        // surfaces as a baffling "Unable to resolve host". Name it instead.
        if (context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            return "Upload skipped: network access is off for this app (App info → Permissions → Network)."
        }
        val outcome = PushClient(store, (context.applicationContext as app.strap.StrapApp).zones, ::log).push(link) { _state.value = SyncState.Uploading(it) }
        log("push: ${outcome.samples} samples, ${outcome.records} records, failure=${outcome.failure}")
        return outcome.failure?.let { "Upload stopped: $it (it resumes next sync)" }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "strap"
        const val BATTERY = 0x0029
        const val DEVICE_INFO = 0x0043
        val BACKGROUND_WAIT = 3.minutes
    }
}
