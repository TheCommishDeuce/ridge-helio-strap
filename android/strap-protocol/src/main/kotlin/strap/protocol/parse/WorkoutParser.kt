package strap.protocol.parse

import strap.protocol.model.Workout
import java.time.Instant

/**
 * Workout summaries (fetch `0x05`, spec/01 §7.2): protobuf with no `.proto`, so a lenient
 * hand-rolled reader walks past unknown fields instead of failing closed.
 *
 * A stream holds several summaries; each begins with field 1 = `"2."…` (`0a 03 32 2e`)
 * and is preceded by a 2-byte record header, so record n ends at `start[n+1] − 2`.
 */
public object WorkoutParser {
    private val MARKER = byteArrayOf(0x0a, 0x03, 0x32, 0x2e)
    private const val MIN_START_EPOCH = 1_000_000_000L

    public fun parseStream(data: ByteArray): List<Workout> {
        val starts = (0..data.size - MARKER.size).filter { i -> MARKER.indices.all { data[i + it] == MARKER[it] } }
        return starts.mapIndexedNotNull { n, begin ->
            val end = if (n + 1 < starts.size) starts[n + 1] - 2 else data.size
            parseOne(data.copyOfRange(begin, maxOf(begin, end)))
        }
    }

    private fun parseOne(blob: ByteArray): Workout? {
        val top = Proto.message(blob)
        val meta = Proto.sub(top, 2)
        val start = Proto.int(meta, 1) ?: return null
        if (start < MIN_START_EPOCH) return null
        val hr = Proto.sub(top, 19)
        return Workout(
            start = Instant.ofEpochSecond(start),
            sportType = Proto.int(meta, 3)?.toInt() ?: 0,
            durationSec = Proto.int(Proto.sub(top, 7), 1)?.toInt() ?: 0,
            calories = Proto.int(Proto.sub(top, 16), 1)?.toInt() ?: 0,
            avgHr = Proto.int(hr, 1)?.toInt() ?: 0,
            maxHr = Proto.int(hr, 2)?.toInt() ?: 0,
            minHr = Proto.int(hr, 3)?.toInt() ?: 0,
        )
    }
}

/** Minimal protobuf reader: varints and length-delimited fields kept, fixed32/64 skipped. */
internal object Proto {
    sealed interface Value {
        data class VarInt(val value: Long) : Value

        class Bytes(val value: ByteArray) : Value
    }

    fun message(data: ByteArray): Map<Int, List<Value>> {
        val fields = mutableMapOf<Int, MutableList<Value>>()
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = varint(data, i)
            if (afterTag <= i) break
            i = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> varint(data, i).let { (v, next) -> fields.getOrPut(field) { mutableListOf() } += Value.VarInt(v); i = next }
                2 -> {
                    val (length, next) = varint(data, i)
                    if (next + length > data.size) return fields
                    fields.getOrPut(field) { mutableListOf() } += Value.Bytes(data.copyOfRange(next, next + length.toInt()))
                    i = next + length.toInt()
                }
                5 -> if (i + 4 > data.size) return fields else i += 4
                1 -> if (i + 8 > data.size) return fields else i += 8
                else -> return fields
            }
        }
        return fields
    }

    fun sub(fields: Map<Int, List<Value>>, field: Int): Map<Int, List<Value>> =
        fields[field]?.firstNotNullOfOrNull { it as? Value.Bytes }?.let { message(it.value) } ?: emptyMap()

    fun int(fields: Map<Int, List<Value>>, field: Int): Long? =
        fields[field]?.firstNotNullOfOrNull { it as? Value.VarInt }?.value

    private fun varint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
        }
        return value to i
    }
}
