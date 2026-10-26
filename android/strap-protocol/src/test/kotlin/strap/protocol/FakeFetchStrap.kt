package strap.protocol

import strap.protocol.fetch.FetchJob
import strap.protocol.fetch.FetchResult
import java.time.Duration
import java.time.LocalDateTime

/**
 * A strap's `0004`/`0005` side for ONE fetch type, built from spec/01 §6, not from any
 * implementation. Holds a per-minute byte stream that starts at local time [origin] and
 * serves at most [minutesPerRound] minutes per round.
 */
/** The start reply's count, as the real strap sends it: 8-byte records for 0x01, bytes otherwise. */
fun announce(code: Int, bytes: Int): Long = if (code == 0x01) bytes / 8L else bytes.toLong()

/** An accepted start reply: the announced count for [bytes], and [start] as the round's start. */
fun startReply(code: Int, bytes: Int, start: LocalDateTime): ByteArray =
    "100101".unhex() + ByteArray(4).also { Bytes.putU32(it, 0, announce(code, bytes)) } +
        ByteArray(7).also {
            Bytes.putU16(it, 0, start.year)
            it[2] = start.monthValue.toByte(); it[3] = start.dayOfMonth.toByte()
            it[4] = start.hour.toByte(); it[5] = start.minute.toByte(); it[6] = start.second.toByte()
        }

/** The local `since` of a start command. */
fun sinceOf(w: ByteArray): LocalDateTime = LocalDateTime.of(Bytes.u16(w, 2), w[4].toInt(), w[5].toInt(), w[6].toInt(), w[7].toInt())

/** A strap's answer to `0004` writes for one fetch type. */
interface FetchResponder {
    fun handle(write: ByteArray): List<FakeFetchStrap.Event>
}

/** Serves [blob] (absolute-timestamp records) in the first round, nothing after. */
class BlobFetchStrap(private val blob: ByteArray, private val packetSize: Int = 20) : FetchResponder {
    private var round = 0
    private var pending = ByteArray(0)

    override fun handle(write: ByteArray): List<FakeFetchStrap.Event> = when (write[0].toInt()) {
        0x01 -> {
            round++
            pending = if (round == 1) blob else ByteArray(0)
            listOf(FakeFetchStrap.Event.Control("100101".unhex() + ByteArray(4).also { Bytes.putU32(it, 0, announce(write[1].toInt(), pending.size)) } + "ea070604080000".unhex()))
        }
        0x02 -> pending.toList().chunked(packetSize).mapIndexed { i, c -> FakeFetchStrap.Event.Data(byteArrayOf(i.toByte()) + c.toByteArray()) } +
            FakeFetchStrap.Event.Control("100201".unhex())
        else -> listOf(FakeFetchStrap.Event.Control("100301".unhex()))
    }
}

class FakeFetchStrap(
    private val origin: LocalDateTime,
    private val recordSize: Int,
    private val stream: ByteArray,
    private val minutesPerRound: Int = 5,
    private val packetSize: Int = 20,
) : FetchResponder {
    sealed interface Event {
        class Control(val bytes: ByteArray) : Event

        class Data(val bytes: ByteArray) : Event
    }

    /** Round numbers (1-based) whose second data packet is dropped, to force a counter gap. */
    val dropPacketInRound = mutableSetOf<Int>()
    val startCommands = mutableListOf<LocalDateTime>()
    var acks = 0
    private var pending = ByteArray(0)
    private var round = 0

    override fun handle(write: ByteArray): List<Event> = when (write[0].toInt()) {
        0x01 -> onStart(write)
        0x02 -> onFetch()
        0x03 -> {
            require(write.size == 2 && write[1].toInt() == 0x09) { "must always ack with KEEP" }
            acks++
            listOf(Event.Control("100301".unhex()))
        }
        else -> error("unexpected command ${write.hex()}")
    }

    private fun onStart(w: ByteArray): List<Event> {
        round++
        val since = sinceOf(w)
        startCommands += since
        val fromMinute = maxOf(0L, Duration.between(origin, since).toMinutes()).toInt()
        val available = stream.size / recordSize - fromMinute
        val minutes = available.coerceIn(0, minutesPerRound)
        pending = stream.copyOfRange(fromMinute * recordSize, (fromMinute + minutes) * recordSize)
        return listOf(Event.Control(startReply(w[1].toInt(), pending.size, origin.plusMinutes(fromMinute.toLong()))))
    }

    private fun onFetch(): List<Event> {
        val events = pending.toList().chunked(packetSize).mapIndexedNotNull { i, chunk ->
            if (round in dropPacketInRound && i == 1) null else Event.Data(byteArrayOf(i.toByte()) + chunk.toByteArray())
        }
        return events + Event.Control("100201".unhex())
    }

    /** Drives [job] against this strap until it finishes. */
    fun run(job: FetchJob): FetchResult {
        val writes = ArrayDeque(job.start().writes)
        while (writes.isNotEmpty()) {
            for (event in handle(writes.removeFirst())) {
                val out = when (event) {
                    is Event.Control -> job.onControl(event.bytes)
                    is Event.Data -> { job.onData(event.bytes); null }
                } ?: continue
                writes.addAll(out.writes)
                out.result?.let { result -> writes.forEach { handle(it) }; return result }
            }
        }
        error("job never finished")
    }
}

/**
 * A new strap, first worn at [firstWorn] and read at [end]: a window that starts before it
 * gets [pastBytes] bytes of `0xFF` records and nothing more, as a real one answers (`3/1p/24b`
 * rounds for `0x01`); from there it gets up to [minutesPerRound] minutes of [record]s.
 */
class NewStrapHistory(
    private val firstWorn: LocalDateTime,
    private val end: LocalDateTime,
    private val recordSize: Int,
    private val record: (LocalDateTime) -> ByteArray,
    private val pastBytes: Int = 24,
    private val minutesPerRound: Int = 120,
) : FetchResponder {
    private var pending = ByteArray(0)

    override fun handle(write: ByteArray): List<FakeFetchStrap.Event> = when (write[0].toInt()) {
        0x01 -> {
            val since = sinceOf(write)
            val minutes = if (since < firstWorn) pastBytes / recordSize else Duration.between(since, end).toMinutes().toInt().coerceIn(0, minutesPerRound)
            pending = (0 until minutes).map { since.plusMinutes(it.toLong()) }
                .fold(ByteArray(0)) { acc, t -> acc + if (t < firstWorn) ByteArray(recordSize) { 0xFF.toByte() } else record(t) }
            listOf(FakeFetchStrap.Event.Control(startReply(write[1].toInt(), pending.size, since)))
        }
        0x02 -> pending.toList().chunked(20).mapIndexed { i, c -> FakeFetchStrap.Event.Data(byteArrayOf(i.toByte()) + c.toByteArray()) } +
            FakeFetchStrap.Event.Control("100201".unhex())
        else -> {
            require(write.contentEquals(byteArrayOf(0x03, 0x09))) { "must always ack with KEEP" }
            listOf(FakeFetchStrap.Event.Control("100301".unhex()))
        }
    }
}
