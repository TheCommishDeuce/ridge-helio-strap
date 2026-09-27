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
        val since = LocalDateTime.of(Bytes.u16(w, 2), w[4].toInt(), w[5].toInt(), w[6].toInt(), w[7].toInt())
        startCommands += since
        val fromMinute = maxOf(0L, Duration.between(origin, since).toMinutes()).toInt()
        val available = stream.size / recordSize - fromMinute
        val minutes = available.coerceIn(0, minutesPerRound)
        pending = stream.copyOfRange(fromMinute * recordSize, (fromMinute + minutes) * recordSize)
        val start = origin.plusMinutes(fromMinute.toLong())
        val reply = "100101".unhex() + ByteArray(4).also { Bytes.putU32(it, 0, announce(w[1].toInt(), pending.size)) } +
            ByteArray(7).also {
                Bytes.putU16(it, 0, start.year)
                it[2] = start.monthValue.toByte(); it[3] = start.dayOfMonth.toByte()
                it[4] = start.hour.toByte(); it[5] = start.minute.toByte(); it[6] = start.second.toByte()
            }
        return listOf(Event.Control(reply))
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
