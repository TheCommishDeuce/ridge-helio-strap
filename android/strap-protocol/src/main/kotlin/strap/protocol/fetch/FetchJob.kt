package strap.protocol.fetch

import strap.protocol.Bytes
import strap.protocol.model.Sample
import strap.protocol.parse.ActivityParser
import strap.protocol.parse.FetchType
import strap.protocol.parse.Replies
import strap.protocol.parse.WorkoutParser
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * One fetch type, paged over as many rounds as it takes, on the `0004`/`0005` channel
 * (spec/01 §6). Pure: the driver writes [FetchOutput.writes] to char `0004` in order, feeds
 * `0004` notifications to [onControl] and `0005` notifications to [onData], and reports
 * timeouts and write failures through [fail]. Finished when [FetchOutput.result] is set.
 *
 * Every ack is `03 09` — keep the data on the strap — so any window can be fetched again.
 */
public class FetchJob(
    public val code: Int,
    since: Instant,
    private val zone: ZoneId,
    private val now: () -> Instant = Instant::now,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val probeOnly: Boolean = false,
) {
    private var since: Instant = since
    private var roundStart: Instant = since
    private var rounds = 0
    private var lastCounter = -1
    private var counterGap = false
    private var integrityRetries = 0
    private var expected = -1L
    private var packets = 0
    private val roundStats = mutableListOf<RoundStat>()
    private val round = ByteArrayOutputStream()
    private val allRaw = ByteArrayOutputStream()
    private val samples = mutableListOf<Sample>()
    private var finished: FetchResult? = null

    /** The first write of round one. */
    public fun start(): FetchOutput {
        check(rounds == 0) { "already started" }
        return FetchOutput(listOf(startRound()))
    }

    public fun onControl(p: ByteArray): FetchOutput {
        if (finished != null || p.size < 3 || Bytes.u8(p, 0) != RESPONSE) return FetchOutput.NOTHING
        val status = Bytes.u8(p, 2)
        return when (Bytes.u8(p, 1)) {
            CMD_START -> onStartReply(p)
            CMD_FETCH -> if (status != STATUS_OK) finish(FetchFailure.FetchRejected(status)) else endRound()
            else -> FetchOutput.NOTHING // the device's reply to our ack
        }
    }

    public fun onData(value: ByteArray) {
        if (finished != null || value.isEmpty()) return
        val counter = Bytes.u8(value, 0)
        if (counter != ((lastCounter + 1) and 0xFF)) counterGap = true
        lastCounter = counter
        packets++
        round.write(value, 1, value.size - 1)
    }

    /** A timeout or failed write, named by the driver. Completed rounds survive in the result. */
    public fun fail(failure: FetchFailure): FetchOutput = if (finished != null) FetchOutput.NOTHING else finish(failure)

    private fun onStartReply(p: ByteArray): FetchOutput = when (val reply = Replies.startReply(p)) {
        is Replies.StartReply.Rejected -> finish(FetchFailure.StartRejected(reply.status))
        Replies.StartReply.Malformed -> finish(FetchFailure.BadStartReply)
        is Replies.StartReply.Accepted -> {
            expected = reply.announced
            reply.roundStart?.let { roundStart = it.atZone(zone).toInstant() }
            if (reply.announced == 0L || probeOnly) finish(null, ACK_KEEP) else FetchOutput(listOf(byteArrayOf(CMD_FETCH.toByte())))
        }
    }

    private fun endRound(): FetchOutput {
        val short = round.size().toLong() != announcedBytes(code, expected)
        roundStats += RoundStat(expected, packets, round.size(), counterGap, short)
        if (counterGap || short) {
            if (++integrityRetries > MAX_INTEGRITY_RETRIES) return finish(FetchFailure.MissingPackets, ACK_KEEP)
            return FetchOutput(listOf(ACK_KEEP, startRound())) // same cursor, whole round discarded
        }
        integrityRetries = 0
        val raw = round.toByteArray()
        allRaw.write(raw)
        val decoded = ActivityParser.parse(code, raw, roundStart)
        samples += decoded
        val next = nextSince(raw, decoded) ?: return finish(null, ACK_KEEP)
        if (rounds >= maxRounds) return finish(FetchFailure.RoundLimit, ACK_KEEP)
        since = next
        return FetchOutput(listOf(ACK_KEEP, startRound()))
    }

    /** Where the next round starts, or null when the stream is exhausted. */
    private fun nextSince(raw: ByteArray, decoded: List<Sample>): Instant? {
        val candidate = when {
            code == FetchType.WORKOUTS ->
                WorkoutParser.parseStream(allRaw.toByteArray()).maxOfOrNull { it.start }?.plus(ONE_MINUTE)
            decoded.isNotEmpty() -> decoded.last().at.plus(ONE_MINUTE)
            (code == FetchType.ACTIVITY || code == FetchType.STRESS) && raw.isNotEmpty() -> {
                // 0xFF gap-skip: an all-sentinel round decodes to nothing, so step past it by
                // its own minute count or the pager re-requests the same window forever.
                val recordSize = if (code == FetchType.STRESS) 1 else ActivityParser.activityRecordSize(raw.size)
                since.plus(Duration.ofMinutes((raw.size / recordSize).coerceIn(1, 1440).toLong()))
            }
            else -> null
        } ?: return null
        val stillPast = candidate.isBefore(now().minusSeconds(30))
        return if (stillPast && candidate.isAfter(since)) candidate else null
    }

    private fun startRound(): ByteArray {
        round.reset()
        packets = 0
        lastCounter = -1
        counterGap = false
        rounds++
        val local = since.atZone(zone)
        val offsetMinutes = local.offset.totalSeconds / 60
        return byteArrayOf(CMD_START.toByte(), code.toByte()) + Replies.time8(local.toLocalDateTime(), offsetMinutes)
    }

    private fun finish(failure: FetchFailure?, vararg writes: ByteArray): FetchOutput {
        val result = FetchResult(code, samples.toList(), allRaw.toByteArray(), expected, roundStats.toList(), if (probeOnly) null else failure)
        finished = result
        return FetchOutput(writes.toList(), result)
    }

    public companion object {
        /**
         * What a start reply's count promises, in bytes. Measured on the strap (first real
         * sync, 22 rounds over 12 types, all exact): `0x01` counts 8-byte minute records,
         * every other type counts bytes. A round that falls short lost packets — including
         * a lost FINAL packet, which the counter check alone cannot see.
         */
        internal fun announcedBytes(code: Int, announced: Long): Long =
            if (code == FetchType.ACTIVITY) announced * 8 else announced

        public const val DEFAULT_MAX_ROUNDS: Int = 20
        private const val MAX_INTEGRITY_RETRIES = 2
        private const val RESPONSE = 0x10
        private const val CMD_START = 0x01
        private const val CMD_FETCH = 0x02
        private const val STATUS_OK = 0x01
        private val ACK_KEEP = byteArrayOf(0x03, 0x09)
        private val ONE_MINUTE: Duration = Duration.ofMinutes(1)
    }
}

/** What to write next, and the result once the job is done. */
public class FetchOutput(public val writes: List<ByteArray>, public val result: FetchResult? = null) {
    internal companion object {
        val NOTHING = FetchOutput(emptyList())
    }
}

/**
 * Everything one job collected. [raw] spans every completed round (sleep and workouts are
 * decoded from it). [failure] is null on success; on failure the samples of completed
 * rounds are still here. [announced] is the last start reply's count (unit: see
 * [FetchJob.announcedBytes]), −1 when the strap never answered a start.
 */
public class FetchResult(
    public val code: Int,
    public val samples: List<Sample>,
    public val raw: ByteArray,
    public val announced: Long,
    public val rounds: List<RoundStat>,
    public val failure: FetchFailure?,
)

/** One downloaded round, as diagnostics: what the start reply announced against what arrived. */
public data class RoundStat(val announced: Long, val packets: Int, val bytes: Int, val counterGap: Boolean, val short: Boolean)

public sealed interface FetchFailure {
    public data class StartRejected(val status: Int) : FetchFailure

    public data object BadStartReply : FetchFailure

    public data class FetchRejected(val status: Int) : FetchFailure

    public data object MissingPackets : FetchFailure

    public data object RoundLimit : FetchFailure

    public data object TimedOut : FetchFailure

    public data object WriteFailed : FetchFailure
}
