package strap.protocol.parse

import strap.protocol.Bytes
import java.time.DayOfWeek

/**
 * One alarm as the strap stores it (spec/01 "Alarms"). [tail] is the entry's last five
 * bytes, meaning unknown: written back exactly as read, so an edit changes nothing else.
 */
public data class StrapAlarm(
    val slot: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val smartWake: Boolean = false,
    val tail: List<Int> = NEW_TAIL,
) {
    public companion object {
        /** What the strap reports on every alarm it holds; used for alarms we create. */
        public val NEW_TAIL: List<Int> = listOf(0, 0, 0, 1, 0)
    }
}

/** A write to the alarm endpoint and the reply command that confirms it. */
public class AlarmWrite(public val bytes: ByteArray, public val ack: Int)

/** Encodings for the alarm endpoint `0x000f` (spec/01 "Alarms"). */
public object Alarms {
    public const val LIST_REPLY: Int = 0x0a
    private const val ENTRY = 10
    private const val FLAG_SMART = 0x01
    private const val FLAG_ENABLED = 0x04

    public val listRequest: ByteArray get() = byteArrayOf(0x09)

    /** `0a · count · count × 10-byte entries`, or null if this is not a list reply. */
    public fun list(payload: ByteArray): List<StrapAlarm>? {
        if (payload.size < 2 || Bytes.u8(payload, 0) != LIST_REPLY) return null
        val n = Bytes.u8(payload, 1)
        if (payload.size < 2 + n * ENTRY) return null
        return List(n) { decode(payload, 2 + it * ENTRY) }
    }

    public fun create(alarm: StrapAlarm): AlarmWrite = AlarmWrite(byteArrayOf(0x03, 0x01) + encode(alarm), 0x04)

    public fun update(alarm: StrapAlarm): AlarmWrite = AlarmWrite(byteArrayOf(0x07, 0x01) + encode(alarm), 0x08)

    public fun delete(slot: Int): AlarmWrite = AlarmWrite(byteArrayOf(0x05, 0x01, slot.toByte()), 0x06)

    private fun decode(b: ByteArray, o: Int): StrapAlarm {
        val flags = Bytes.u8(b, o)
        val repeat = Bytes.u8(b, o + 4)
        return StrapAlarm(
            slot = Bytes.u8(b, o + 1),
            enabled = flags and FLAG_ENABLED != 0,
            hour = Bytes.u8(b, o + 2),
            minute = Bytes.u8(b, o + 3),
            days = DayOfWeek.entries.filterTo(mutableSetOf()) { repeat and (1 shl (it.value - 1)) != 0 },
            smartWake = flags and FLAG_SMART != 0,
            tail = List(5) { Bytes.u8(b, o + 5 + it) },
        )
    }

    internal fun encode(a: StrapAlarm): ByteArray {
        require(a.slot in 0..0xFF && a.hour in 0..23 && a.minute in 0..59 && a.tail.size == 5) { "invalid alarm $a" }
        val flags = (if (a.enabled) FLAG_ENABLED else 0) or (if (a.smartWake) FLAG_SMART else 0)
        val repeat = a.days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
        return byteArrayOf(flags.toByte(), a.slot.toByte(), a.hour.toByte(), a.minute.toByte(), repeat.toByte()) +
            ByteArray(5) { a.tail[it].toByte() }
    }
}
