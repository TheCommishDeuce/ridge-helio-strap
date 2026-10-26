package app.strap.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.strap.ble.AndroidStrapLink
import app.strap.pairing.KeyVault
import app.strap.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strap.protocol.fetch.FetchFailure
import strap.protocol.model.DailyTotals
import strap.protocol.parse.AlarmZones
import strap.protocol.parse.Config
import strap.protocol.parse.CurrentTime
import strap.protocol.parse.Replies
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

    /**
     * S4 probe, how live steps can be (D34): the cost of a counter read on an open connection,
     * then 60 s of the step service's real-time reporting (`05 01` on, `05 00` off; Gadgetbridge's
     * names, untried on this strap), every frame logged raw. Real-time is a subscription, not a
     * setting, and is switched off again before disconnecting.
     */
    suspend fun probeLiveSteps(onLine: (String) -> Unit): String? {
        val asked = System.nanoTime()
        fun ms() = (System.nanoTime() - asked) / 1_000_000
        val lines = ArrayList<String>()
        fun add(line: String) {
            val stamped = "%6d ms  %s".format(ms(), line)
            lines += stamped
            log("steps probe $stamped")
            onLine(stamped)
        }
        val job = strapJob { s ->
            add("connected and authenticated")
            repeat(3) {
                val t = ms()
                val reply = s.exchange(DAILY_TOTALS, byteArrayOf(0x03), 0x04)
                add("read 03 → ${reply?.hex() ?: "no reply"} (${ms() - t} ms)${reply?.let { r -> Replies.dailyTotals(r, Instant.now())?.let { "  steps=${it.steps}" } } ?: ""}")
            }
            coroutineScope {
                val watcher = launch { s.frames(DAILY_TOTALS).collect { add("frame ${it.hex()}") } }
                val ack = s.exchange(DAILY_TOTALS, byteArrayOf(0x05, 0x01), 0x06)
                add("real-time on 05 01 → ${ack?.hex() ?: "no ack"}; walk now, listening 60 s")
                delay(60_000)
                val off = s.exchange(DAILY_TOTALS, byteArrayOf(0x05, 0x00), 0x06)
                add("real-time off 05 00 → ${off?.hex() ?: "no ack"}")
                delay(1_000)
                watcher.cancel()
            }
            val last = s.exchange(DAILY_TOTALS, byteArrayOf(0x03), 0x04)
            add("read 03 → ${last?.hex() ?: "no reply"}")
        }
        add(job.failure?.let { "ended: $it" } ?: "disconnected")
        runCatching { java.io.File(context.getExternalFilesDir(null), "steps-probe.txt").writeText(lines.joinToString("\n")) }
        return job.failure
    }

    private val _finding = MutableStateFlow(false)

    /** The strap is buzzing for Find strap. */
    val finding: StateFlow<Boolean> = _finding.asStateFlow()
    @Volatile private var stopFinding: CompletableDeferred<Unit>? = null

    /**
     * Find strap (D40; spec/01 §5): `03` on Find device (`0x001A`) buzzes the strap until `06`,
     * after which the strap sends `07`. Tapping the strap does not stop it (OpenStrap's hardware
     * check, fw 3.25.0.3), so this always sends `06`: on [stopFind], after [FIND_LIMIT], or when
     * anything goes wrong, unless the strap already said it stopped. Null when it buzzed and
     * stopped, else why not.
     */
    suspend fun findStrap(): String? {
        val stop = CompletableDeferred<Unit>().also { stopFinding = it }
        val job = strapJob { s ->
            s.requestServices()
            val services = s.awaitServices() ?: return@strapJob "The strap didn't say what it can do. Try again."
            val encrypt = services[FIND_DEVICE] ?: return@strapJob "This strap can't be found from the phone."
            coroutineScope {
                val stopped = async(start = CoroutineStart.UNDISPATCHED) { s.frames(FIND_DEVICE).first { it.isNotEmpty() && it[0] == 0x07.toByte() } }
                try {
                    val ack = s.exchange(FIND_DEVICE, byteArrayOf(0x03), 0x04, encrypt)
                    log("find strap 03 → ${ack?.hex() ?: "no reply"}")
                    // A lost acknowledgement doesn't mean it isn't buzzing (seen out of range), so it still waits and stops.
                    _finding.value = true
                    withTimeoutOrNull(FIND_LIMIT) { select<Unit> { stop.onAwait {}; stopped.onAwait {} } }
                    if (ack == null) "The strap didn't answer; it may not have buzzed." else null
                } finally {
                    _finding.value = false
                    if (!stopped.isCompleted) withContext(NonCancellable) {
                        val end = s.exchange(FIND_DEVICE, byteArrayOf(0x06), 0x07, encrypt)
                        log("find strap 06 → ${end?.hex() ?: "no reply"}")
                    }
                    stopped.cancel()
                }
            }
        }
        stopFinding = null
        return job.failure ?: job.value
    }

    /** Stops a running [findStrap]. */
    fun stopFind() {
        stopFinding?.complete(Unit)
    }

    /**
     * Strap → phone → server. Null when the strap is already busy, else how it ended. Live steps
     * step aside for it and come back after.
     */
    suspend fun run(): SyncState.Finished? {
        liveJob?.cancelAndJoin()
        try {
            // The server is a backup now (D34 S5): a sync stays on the phone; the nightly job uploads.
            return exclusive { listOfNotNull(syncOnce()).also { derive() } }
        } finally {
            if (liveWanted) startLive()
        }
    }

    /** Brings the phone's own numbers up to date (D34 S3) from what the sync stored; never fails a sync. */
    private suspend fun derive() {
        app().science.catchUp()?.let { log("science: $it") }
    }

    private fun app() = context.applicationContext as app.strap.StrapApp

    // ── live steps (S4, D34) ────────────────────────────────────────────────

    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var liveWanted = false
    @Volatile private var liveJob: Job? = null
    private val _live = MutableStateFlow<DailyTotals?>(null)

    /** The strap's counters as they change, while Today is on screen; null when not live. */
    val live: StateFlow<DailyTotals?> = _live.asStateFlow()

    /**
     * Holds one connection with the step service's real-time reporting on (spec/01 §5), for as
     * long as Today is on screen: the only connection Ridge keeps open (D34 loosens D30 for it).
     * Waits for a running sync, never interrupts a strap job; a dropped link retries.
     */
    fun startLive() {
        if (vault.load() == null) return
        liveWanted = true
        if (liveJob?.isActive == true) return
        liveJob = liveScope.launch {
            while (liveWanted) {
                lock.lock()
                try {
                    if (!liveWanted) break
                    onStrap { session, _ -> liveSession(session) }.second?.let { log("live steps: $it") }
                } finally {
                    lock.unlock()
                    _live.value = null
                }
                if (liveWanted) delay(LIVE_RETRY)
            }
        }
    }

    /** Today left the screen: real-time off, disconnect, keep the last reading. */
    fun stopLive() {
        liveWanted = false
        liveJob?.cancel()
    }

    private suspend fun liveSession(s: StrapSession) {
        var last: DailyTotals? = null
        try {
            coroutineScope {
                launch {
                    s.frames(DAILY_TOTALS).collect { frame ->
                        Replies.realtimeSteps(frame, Instant.now())?.let { last = it; _live.value = it }
                    }
                }
                s.exchange(DAILY_TOTALS, byteArrayOf(0x03), 0x04)?.let(::readingOf)?.let { last = it; _live.value = it }
                if (s.exchange(DAILY_TOTALS, Replies.realtimeStepsOn, Replies.REALTIME_STEPS_ACK) == null) log("live steps: no acknowledgement")
                awaitCancellation()
            }
        } finally {
            withContext(NonCancellable) {
                // The strap ends the subscription when the link closes anyway; this just asks first.
                withTimeoutOrNull(LIVE_OFF_WAIT) { s.exchange(DAILY_TOTALS, Replies.realtimeStepsOff, Replies.REALTIME_STEPS_ACK) }
                last?.let {
                    store.saveDailyTotals(it)
                    app().science.catchUp()?.let { why -> log("science: $why") }
                }
            }
        }
    }

    private fun readingOf(reply: ByteArray) = Replies.dailyTotals(reply, Instant.now())

    /**
     * The background jobs (D30): strap → phone, and phone → server. Collection holds the strap's
     * lock, an upload only the upload lock (it never touches the strap, so live steps do not hold
     * it up). Each WAITS for a running one (up to [BACKGROUND_WAIT]) rather than skipping: the two
     * jobs often fire together, and a skipped job would wait a whole period.
     */
    suspend fun collect(): SyncState.Finished? = exclusive(background = true) { listOfNotNull(syncOnce()).also { derive() } }

    suspend fun upload(): SyncState.Finished? {
        if (withTimeoutOrNull(BACKGROUND_WAIT) { uploadLock.lock() } == null) return null
        try {
            return backup()
        } finally {
            uploadLock.unlock()
        }
    }

    /** One upload at a time, whoever starts it. */
    private val uploadLock = Mutex()

    /** "Back up now": the backup, waited for; null on success, else why it stopped. */
    suspend fun backupNow(): String? = uploadLock.withLock { backup().failure }

    /**
     * Sends everything not yet on the server, showing its progress, and always ends in a finished
     * state, so the bar never stays on "Uploading" (a background result: no message for it).
     */
    private suspend fun backup(): SyncState.Finished {
        try {
            return SyncState.Finished(withContext(Dispatchers.IO) { pushAll().joinToString(" ").ifEmpty { null } }, background = true)
                .also { _state.value = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = SyncState.Finished("The backup was interrupted; the next one continues from there.", background = true)
            throw e
        }
    }

    /** The strap's data, then the owner's profile and journal (D34 S3); a full success is the backup's time. */
    private suspend fun pushAll(): List<String> = listOfNotNull(
        pushOnce(),
        app().owner.push()?.let { "Profile and journal upload stopped: $it (it resumes with the next backup)" },
    ).also { if (it.isEmpty() && vault.loadServer() != null) store.setMetaValue(BACKUP_AT, Instant.now().toEpochMilli().toString()) }

    /** When everything last reached the server, or null if it never did (or no server is set). */
    fun lastBackup(): Instant? = store.metaValue(BACKUP_AT)?.toLong()?.let(Instant::ofEpochMilli)

    private suspend fun exclusive(background: Boolean = false, work: suspend () -> List<String>): SyncState.Finished? {
        val locked = if (background) withTimeoutOrNull(BACKGROUND_WAIT) { lock.lock() } != null else lock.tryLock()
        if (!locked) return null
        try {
            // Off the main thread: the store writes thousands of rows per sync.
            return SyncState.Finished(withContext(Dispatchers.IO) { work().joinToString(" ").ifEmpty { null } }, background)
                .also { _state.value = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped from outside (WorkManager ends a job, the service goes): say so, never stay "Connecting…".
            _state.value = SyncState.Finished("The sync was interrupted; the next one continues from there.", background = true)
            throw e
        } catch (e: RuntimeException) {
            log("sync failed: $e")
            _state.value = SyncState.Finished("The sync failed: ${e.message ?: e::class.simpleName}", background)
            return null
        } finally {
            lock.unlock()
        }
    }

    /**
     * One short strap job outside a sync (alarms): connect, authenticate, [block], disconnect.
     * While a sync, live steps or another job holds the strap it waits up to [wait] for it (a
     * read the page makes on opening), else is refused at once (a change the owner just made).
     */
    suspend fun <T> strapJob(wait: Duration = Duration.ZERO, block: suspend (StrapSession) -> T): StrapJob<T> {
        val locked = if (wait > Duration.ZERO) withTimeoutOrNull(wait) { lock.lock() } != null else lock.tryLock()
        if (!locked) return StrapJob(null, "The strap is busy with a sync. Try again in a moment.")
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
            when (val f = result.failure) {
                null -> null
                FetchFailure.RoundLimit -> "More history than one sync fetches: what arrived was saved, and the next sync continues from there."
                else -> "Sync stopped early: $f (what arrived was saved)"
            }
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
        return outcome.failure?.let { "Upload stopped: $it (it resumes with the next backup)" }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "strap"
        const val BATTERY = 0x0029
        const val DAILY_TOTALS = 0x0016
        const val BACKUP_AT = "backup_at"
        val LIVE_RETRY = 15.seconds
        val LIVE_OFF_WAIT = 1.seconds
        const val DEVICE_INFO = 0x0043
        const val FIND_DEVICE = 0x001A
        /** As long as Zepp's find dialog waits before it closes. */
        val FIND_LIMIT = 60.seconds
        val BACKGROUND_WAIT = 3.minutes
    }
}
