package strap.protocol.parse

import strap.protocol.Bytes
import strap.protocol.model.DailyTotals
import java.time.Instant
import java.time.LocalDateTime

/** Small fixed-layout replies and encodings (spec/01 §3, §5, §6). */
public object Replies {
    /** Daily totals on endpoint `0x0016`: `04 01 0c · steps u32 · metres u32 · kcal u32`. */
    public fun dailyTotals(payload: ByteArray, readAt: Instant): DailyTotals? {
        if (payload.size < 15 || Bytes.u8(payload, 0) != 0x04 || Bytes.u8(payload, 1) != 0x01) return null
        return DailyTotals(Bytes.u32(payload, 3), Bytes.u32(payload, 7), Bytes.u32(payload, 11), readAt)
    }

    /** Services list on endpoint `0x0000`: `04 · n u16 · (endpoint u16, encrypted u8) × n`. */
    public fun services(payload: ByteArray): Map<Int, Boolean>? {
        if (payload.size < 3 || Bytes.u8(payload, 0) != 0x04) return null
        val out = linkedMapOf<Int, Boolean>()
        var offset = 3
        repeat(Bytes.u16(payload, 1)) {
            if (offset + 3 > payload.size) return out
            out[Bytes.u16(payload, offset)] = Bytes.u8(payload, offset + 2) != 0
            offset += 3
        }
        return out
    }

    /**
     * The 8-byte local time the fetch start command carries: year u16, month, day, hour,
     * minute, second (always 0), and the UTC offset in 15-minute units `& 0xFF`.
     */
    public fun time8(local: LocalDateTime, utcOffsetMinutes: Int): ByteArray {
        val out = ByteArray(8)
        Bytes.putU16(out, 0, local.year)
        out[2] = local.monthValue.toByte()
        out[3] = local.dayOfMonth.toByte()
        out[4] = local.hour.toByte()
        out[5] = local.minute.toByte()
        out[6] = 0
        out[7] = ((utcOffsetMinutes / 15) and 0xFF).toByte()
        return out
    }

    /** The control reply to a fetch start: `10 01 status · expected u32 · date`. */
    public sealed interface StartReply {
        public data class Accepted(val announced: Long, val roundStart: LocalDateTime?) : StartReply

        public data class Rejected(val status: Int) : StartReply

        public data object Malformed : StartReply
    }

    /** [StartReply.Accepted.roundStart] is null exactly when nothing is to be sent. */
    public fun startReply(p: ByteArray): StartReply {
        if (p.size < 3) return StartReply.Malformed
        val status = Bytes.u8(p, 2)
        if (status != 0x01) return StartReply.Rejected(status)
        if (p.size < 14) return StartReply.Malformed
        val expected = Bytes.u32(p, 3)
        if (expected == 0L) return StartReply.Accepted(0, null)
        val start = runCatching {
            LocalDateTime.of(Bytes.u16(p, 7), Bytes.u8(p, 9), Bytes.u8(p, 10), Bytes.u8(p, 11), Bytes.u8(p, 12), Bytes.u8(p, 13))
        }.getOrNull() ?: return StartReply.Malformed
        return StartReply.Accepted(expected, start)
    }
}
