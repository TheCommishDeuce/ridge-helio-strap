package strap.protocol.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import strap.protocol.Gatt
import strap.protocol.auth.Handshake
import strap.protocol.auth.HandshakeEvent
import strap.protocol.auth.HandshakeFailure
import strap.protocol.crypto.B163
import strap.protocol.fetch.FetchFailure
import strap.protocol.fetch.FetchJob
import strap.protocol.fetch.FetchResult
import strap.protocol.model.DailyTotals
import strap.protocol.parse.AlarmWrite
import strap.protocol.parse.Alarms
import strap.protocol.parse.Config
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.Replies
import strap.protocol.parse.StrapAlarm
import strap.protocol.transport.ChunkDecoder
import strap.protocol.transport.ChunkEncoder
import strap.protocol.transport.ChunkFormat
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An authenticated conversation with the strap over an open [StrapLink] (spec/01 §3–§6).
 *
 * [open] runs the handshake; afterwards a dispatcher decrypts chunked frames, ACKs the ones
 * that ask, and records daily totals and the services list. History is pulled one type at a
 * time with [fetch]. [log] receives secret-free lines only.
 */
public class StrapSession private constructor(
    private val link: StrapLink,
    private val zone: ZoneId,
    private val now: () -> Instant,
    private val log: (String) -> Unit,
    sessionKey: ByteArray,
    sequence: Long,
) {
    private val sessionKey = sessionKey.copyOf()
    private val encoder = ChunkEncoder(link.mtu).apply { setEncryption(sessionKey, sequence) }
    private val decoder = ChunkDecoder()
    private val _dailyTotals = MutableStateFlow<DailyTotals?>(null)
    private val _services = MutableStateFlow<Map<Int, Boolean>?>(null)
    private val replies = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 16)
    private var dispatcher: Job? = null

    public val hasFetchChannel: Boolean get() = link.hasFetchChannel
    public val dailyTotals: StateFlow<DailyTotals?> = _dailyTotals.asStateFlow()

    /** Endpoint → encrypted, if the strap announced its services. Diagnostic only. */
    public val services: StateFlow<Map<Int, Boolean>?> = _services.asStateFlow()

    private fun startDispatcher(scope: CoroutineScope) {
        dispatcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.collect { n ->
                if (n.channel != Channel.CHUNKED) return@collect
                val frame = decoder.feed(n.bytes, sessionKey) ?: return@collect
                if (frame.needsAck) {
                    try {
                        link.writeAck(ChunkFormat.ack(frame.handle, frame.count))
                    } catch (e: IOException) {
                        log("ack write failed: ${e.message}") // flow-control courtesy; the frame still stands
                    }
                }
                route(frame.endpoint, frame.payload)
            }
        }
    }

    private fun route(endpoint: Int, payload: ByteArray) {
        log("frame endpoint=0x%04x len=%d".format(endpoint, payload.size))
        when (endpoint) {
            Gatt.ENDPOINT_DAILY_TOTALS -> Replies.dailyTotals(payload, now())?.let { _dailyTotals.value = it }
            Gatt.ENDPOINT_SERVICES -> Replies.services(payload)?.let { _services.value = it }
        }
        replies.tryEmit(endpoint to payload.copyOf())
    }

    /** Asks for the since-midnight counters; the answer lands in [dailyTotals]. */
    public suspend fun requestDailyTotals() {
        for (chunk in encoder.encode(Gatt.ENDPOINT_DAILY_TOTALS, byteArrayOf(0x03))) link.writeChunk(chunk)
    }

    /** Asks which endpoints the strap serves (`[0x03]`, unencrypted); the answer lands in [services]. */
    public suspend fun requestServices() {
        for (chunk in encoder.encode(Gatt.ENDPOINT_SERVICES, byteArrayOf(0x03))) link.writeChunk(chunk)
    }

    /** The services list, once the strap answers [requestServices]; null when it did not in time. */
    public suspend fun awaitServices(timeout: Duration = SERVICES_WAIT): Map<Int, Boolean>? =
        withTimeoutOrNull(timeout) { services.filterNotNull().first() }

    /**
     * Sends [request] on [endpoint] and returns the first reply whose command byte is
     * [replyCommand], or null when none came in time.
     */
    public suspend fun exchange(endpoint: Int, request: ByteArray, replyCommand: Int, encrypt: Boolean = false, timeout: Duration = REPLY_WAIT): ByteArray? =
        withTimeoutOrNull(timeout) {
            coroutineScope {
                // Subscribed before the request goes out, so a fast reply cannot slip past.
                val reply = async(start = CoroutineStart.UNDISPATCHED) {
                    replies.first { (ep, p) -> ep == endpoint && p.isNotEmpty() && (p[0].toInt() and 0xFF) == replyCommand }.second
                }
                for (chunk in encoder.encode(endpoint, request, encrypt)) link.writeChunk(chunk)
                reply.await()
            }
        }

    /** One config group, or null when the strap did not answer or refused (spec/01 "Config"). */
    public suspend fun readConfig(group: Int, encrypt: Boolean = true, timeout: Duration = REPLY_WAIT): ConfigGroup? {
        val reply = exchange(Config.ENDPOINT, Config.readRequest(group), Config.READ_REPLY, encrypt, timeout) ?: return null
        return Config.group(reply).also { g ->
            // Settings bytes, never secrets: kept so a layout we misread can be studied.
            if (g?.complete != true) log("config group 0x%02x not fully decoded: %s".format(group, reply.joinToString("") { "%02x".format(it) }))
        }
    }

    /** The strap's alarms, or null when it did not answer in time. */
    public suspend fun readAlarms(timeout: Duration = REPLY_WAIT): List<StrapAlarm>? =
        exchange(Gatt.ENDPOINT_ALARMS, Alarms.listRequest, Alarms.LIST_REPLY, timeout = timeout)?.let(Alarms::list)

    /** Sends one alarm change; true once the strap confirms it. */
    public suspend fun writeAlarm(write: AlarmWrite, timeout: Duration = REPLY_WAIT): Boolean =
        exchange(Gatt.ENDPOINT_ALARMS, write.bytes, write.ack, timeout = timeout) != null

    /** The counters, or null when the strap did not answer — which is not "zero steps". */
    public suspend fun awaitDailyTotals(timeout: Duration = DAILY_TOTALS_WAIT): DailyTotals? =
        withTimeoutOrNull(timeout) { dailyTotals.filterNotNull().first() }

    /** Pulls one fetch type from [since]. Never throws for protocol failures: see [FetchResult.failure]. */
    public suspend fun fetch(
        code: Int,
        since: Instant,
        maxRounds: Int = FetchJob.DEFAULT_MAX_ROUNDS,
        timeout: Duration = FETCH_TIMEOUT,
    ): FetchResult {
        check(link.hasFetchChannel) { "no activity-fetch channel on this strap" }
        val job = FetchJob(code, since, zone, now, maxRounds)
        val result = withTimeoutOrNull(timeout) { runJob(job) } ?: job.fail(FetchFailure.TimedOut).result!!
        result.failure?.let { log("fetch 0x%02x ended: %s".format(code, it)) }
        return result
    }

    private suspend fun runJob(job: FetchJob): FetchResult = coroutineScope {
        val done = CompletableDeferred<FetchResult>()
        suspend fun send(writes: List<ByteArray>) {
            try {
                for (w in writes) link.writeControl(w)
            } catch (e: IOException) {
                log("control write failed: ${e.message}")
                job.fail(FetchFailure.WriteFailed).result?.let(done::complete)
            }
        }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.collect { n ->
                val out = when (n.channel) {
                    Channel.CONTROL -> job.onControl(n.bytes)
                    Channel.DATA -> null.also { job.onData(n.bytes) }
                    Channel.CHUNKED -> null
                } ?: return@collect
                send(out.writes)
                out.result?.let(done::complete)
            }
        }
        send(job.start().writes)
        done.await().also { collector.cancel() }
    }

    public fun close() {
        dispatcher?.cancel()
    }

    public companion object {
        public val HANDSHAKE_TIMEOUT: Duration = 15.seconds
        public val DAILY_TOTALS_WAIT: Duration = 5.seconds
        public val FETCH_TIMEOUT: Duration = 30.seconds
        public val REPLY_WAIT: Duration = 5.seconds
        public val SERVICES_WAIT: Duration = 5.seconds

        /**
         * Authenticates over [link] and returns a live session whose dispatcher runs in
         * [scope]. Throws [StrapException] on refusal or timeout — two different failures.
         */
        public suspend fun open(
            link: StrapLink,
            authKey: ByteArray,
            scope: CoroutineScope,
            zone: ZoneId = ZoneId.systemDefault(),
            now: () -> Instant = Instant::now,
            log: (String) -> Unit = {},
            keypair: B163.Keypair? = null,
            handshakeTimeout: Duration = HANDSHAKE_TIMEOUT,
        ): StrapSession {
            // ECDH is CPU work: never on the caller's (possibly main) thread.
            val auth = withContext(Dispatchers.Default) {
                authenticate(link, Handshake(authKey, keypair ?: B163.generateKeypair()), handshakeTimeout)
            }
            log("authenticated")
            if (link.hasFetchChannel) link.openFetchChannel()
            return StrapSession(link, zone, now, log, auth.sessionKey, auth.sequence).also { it.startDispatcher(scope) }
        }

        private suspend fun authenticate(link: StrapLink, handshake: Handshake, timeout: Duration): HandshakeEvent.Authenticated =
            coroutineScope {
                val encoder = ChunkEncoder(link.mtu)
                val decoder = ChunkDecoder()
                val outcome = CompletableDeferred<HandshakeEvent>()
                suspend fun send(payload: ByteArray) {
                    try {
                        for (chunk in encoder.encode(Gatt.ENDPOINT_AUTH, payload)) link.writeChunk(chunk)
                    } catch (e: IOException) {
                        outcome.complete(HandshakeEvent.Failed(HandshakeFailure.Malformed("write failed: ${e.message}")))
                    }
                }
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    link.notifications.collect { n ->
                        if (n.channel != Channel.CHUNKED) return@collect
                        val frame = decoder.feed(n.bytes) ?: return@collect
                        if (frame.endpoint != Gatt.ENDPOINT_AUTH) return@collect
                        when (val event = handshake.onPayload(frame.payload)) {
                            is HandshakeEvent.SendProof -> send(event.payload)
                            is HandshakeEvent.Authenticated, is HandshakeEvent.Failed -> outcome.complete(event)
                            HandshakeEvent.Ignored -> Unit
                        }
                    }
                }
                send(handshake.start())
                val result = withTimeoutOrNull(timeout) { outcome.await() }
                collector.cancel()
                when (result) {
                    is HandshakeEvent.Authenticated -> result
                    is HandshakeEvent.Failed -> throw StrapException(StrapFailure.HandshakeRefused(result.failure))
                    else -> throw StrapException(StrapFailure.HandshakeTimedOut(timeout))
                }
            }
    }
}

/** Why a session could not be established. Secret-free by construction. */
public sealed interface StrapFailure {
    public data class HandshakeRefused(val reason: HandshakeFailure) : StrapFailure

    public data class HandshakeTimedOut(val after: Duration) : StrapFailure
}

public class StrapException(public val failure: StrapFailure) : Exception(failure.toString())
