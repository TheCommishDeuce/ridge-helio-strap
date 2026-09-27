package strap.protocol.parse

import strap.protocol.Bytes
import strap.protocol.model.SleepSession
import strap.protocol.model.SleepStage
import strap.protocol.model.Stage
import java.time.Instant

/**
 * Decoder for the 594-byte sleep record (fetch `0x48`, spec/01 §7.1). Each record holds
 * the main night and, in a second block, any daytime naps.
 *
 * All stage minutes are offsets from (midnight − 24 h). A slot failing header validation is
 * SKIPPED, not a stop: valid sessions follow empty ones.
 */
public object SleepParser {
    public const val RECORD_SIZE: Int = 594
    private const val MIN_EPOCH = 1_600_000_000L
    private const val MAX_EPOCH = 2_000_000_000L
    private const val NIGHT_STAGES = 0x56
    private const val NIGHT_STAGES_MAX = 51
    private const val NAP_DESCRIPTORS = 0x18
    private const val NAP_DESCRIPTORS_END = 0x54
    private const val NAP_STAGES = 0x155
    private const val NAP_STAGES_MAX = 49

    public fun parse(data: ByteArray): List<SleepSession> {
        val out = mutableListOf<SleepSession>()
        for (slot in 0 until data.size / RECORD_SIZE) {
            val record = data.copyOfRange(slot * RECORD_SIZE, (slot + 1) * RECORD_SIZE)
            if (!valid(record)) continue
            val base = Bytes.u32(record, 0x04) - 24 * 3600
            out += night(record, base)
            out += naps(record, base)
        }
        return out
    }

    private fun valid(r: ByteArray): Boolean {
        val session = Bytes.u32(r, 0x00)
        val midnight = Bytes.u32(r, 0x04)
        return session in MIN_EPOCH..MAX_EPOCH && midnight in MIN_EPOCH..MAX_EPOCH &&
            Bytes.u8(r, 0x08) == 1 && Bytes.u8(r, 0x09) == 1
    }

    private fun night(r: ByteArray, base: Long): SleepSession {
        val count = minOf(Bytes.u8(r, 0x54), NIGHT_STAGES_MAX)
        val stages = mutableListOf<SleepStage>()
        for (i in 0 until count) {
            val (start, end, code) = triple(r, NIGHT_STAGES + 5 * i)
            if (start == 0 && end == 0) break
            val stage = Stage.of(code) ?: continue // 0x80 gap markers are not stages
            stages += SleepStage(minute(base, start), minute(base, end), stage)
        }
        return SleepSession(
            sessionStart = Instant.ofEpochSecond(Bytes.u32(r, 0x00)),
            sleepStartMin = Bytes.u16(r, 0x0a),
            sleepEndMin = Bytes.u16(r, 0x0c),
            avgHr = Bytes.u8(r, 0x15),
            score = Bytes.u8(r, 0x16),
            stages = stages,
            remMin = Bytes.u16(r, 0x24a),
            lightMin = Bytes.u16(r, 0x24c),
            deepMin = Bytes.u16(r, 0x24e),
            wakeMin = Bytes.u16(r, 0x250),
            isNap = false,
        )
    }

    private fun naps(r: ByteArray, base: Long): List<SleepSession> {
        val windows = mutableListOf<Pair<Int, Int>>()
        var p = NAP_DESCRIPTORS
        while (p + 6 <= NAP_DESCRIPTORS_END) {
            val start = Bytes.u16(r, p)
            val end = Bytes.u16(r, p + 2)
            val duration = Bytes.u16(r, p + 4)
            if (start == 0 && duration == 0) break
            if (duration > 0 && end > start) windows += start to end
            p += 6
        }
        if (windows.isEmpty()) return emptyList()
        val segments = (0 until NAP_STAGES_MAX)
            .map { triple(r, NAP_STAGES + 5 * it) }
            .filter { (start, end, code) -> !(start == 0 && end == 0) && Stage.of(code) != null }
        return windows.mapNotNull { (napStart, napEnd) ->
            val stages = segments
                .filter { (start) -> start in napStart..napEnd }
                .map { (start, end, code) -> SleepStage(minute(base, start), minute(base, end), Stage.of(code)!!) }
            if (stages.isEmpty()) null else napSession(base, napStart, napEnd, stages)
        }
    }

    private fun napSession(base: Long, start: Int, end: Int, stages: List<SleepStage>): SleepSession {
        fun minutesOf(stage: Stage) = stages.filter { it.stage == stage }
            .sumOf { ((it.end.epochSecond - it.start.epochSecond) / 60).toInt() }
        return SleepSession(
            sessionStart = minute(base, start),
            sleepStartMin = start,
            sleepEndMin = end,
            avgHr = 0,
            score = 0,
            stages = stages,
            remMin = minutesOf(Stage.REM),
            lightMin = minutesOf(Stage.LIGHT),
            deepMin = minutesOf(Stage.DEEP),
            wakeMin = minutesOf(Stage.AWAKE),
            isNap = true,
        )
    }

    private fun triple(r: ByteArray, offset: Int) = Triple(Bytes.u16(r, offset), Bytes.u16(r, offset + 2), Bytes.u8(r, offset + 4))

    private fun minute(base: Long, minutes: Int): Instant = Instant.ofEpochSecond(base + minutes * 60L)
}
