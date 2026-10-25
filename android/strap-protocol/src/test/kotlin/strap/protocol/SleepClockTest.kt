package strap.protocol

import strap.protocol.model.Stage
import strap.protocol.parse.SleepParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sleep stage minutes are the strap's wall clock, so a clock change must not move the night. */
class SleepClockTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun record(midnight: Instant, vararg stages: Triple<Int, Int, Int>): ByteArray {
        val r = ByteArray(SleepParser.RECORD_SIZE)
        Bytes.putU32(r, 0, midnight.epochSecond + 9 * 3600)
        Bytes.putU32(r, 4, midnight.epochSecond)
        r[8] = 1
        r[9] = 1
        r[0x54] = stages.size.toByte()
        stages.forEachIndexed { i, (start, end, code) ->
            Bytes.putU16(r, 0x56 + 5 * i, start)
            Bytes.putU16(r, 0x58 + 5 * i, end)
            r[0x5a + 5 * i] = code.toByte()
        }
        return r
    }

    private fun local(text: String, zone: ZoneId = amsterdam) = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `the autumn change keeps the wake time and leaves the repeated hour empty`() {
        // Asleep 23:40 CEST, woke 07:10 CET: the strap counts 1420 → 1870 on its wall clock.
        val midnight = local("2026-10-25T00:00") // 22:00Z, still CEST
        val night = SleepParser.parse(record(midnight, Triple(1420, 1430, 4), Triple(1600, 1630, 4), Triple(1631, 1640, 5), Triple(1860, 1870, 4)), amsterdam).single()
        val s = night.stages
        assertEquals(local("2026-10-24T23:40"), s.first().start)
        assertEquals(local("2026-10-25T07:10"), s.last().end)
        // 02:40 → 03:10 on the strap's clock: split at the change, the repeated hour left out.
        assertEquals(Instant.parse("2026-10-25T00:40:00Z"), s[1].start)
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), s[1].end)
        assertEquals(Instant.parse("2026-10-25T02:00:00Z"), s[2].start)
        assertEquals(Instant.parse("2026-10-25T02:10:00Z"), s[2].end)
        assertEquals(Stage.LIGHT, s[2].stage)
        assertEquals(Instant.parse("2026-10-25T02:11:00Z"), s[3].start)
    }

    @Test
    fun `the spring change keeps the wake time`() {
        val midnight = local("2026-03-29T00:00") // CET
        val s = SleepParser.parse(record(midnight, Triple(1440 + 60, 1440 + 200, 4), Triple(1440 + 200, 1440 + 480, 8)), amsterdam).single().stages
        assertEquals(local("2026-03-29T01:00"), s[0].start)
        assertEquals(local("2026-03-29T03:20"), s[0].end) // 01:00 CET → 03:20 CEST: 80 real minutes
        assertEquals(local("2026-03-29T08:00"), s[1].end)
    }

    @Test
    fun `the first night after a change is counted from the previous wall midnight`() {
        val midnight = local("2026-10-26T00:00") // CET; the day before had 25 hours
        val s = SleepParser.parse(record(midnight, Triple(1420, 1870, 4)), amsterdam).single().stages.single()
        assertEquals(local("2026-10-25T23:40"), s.start)
        assertEquals(local("2026-10-26T07:10"), s.end)
    }

    @Test
    fun `a strap on another zone falls back to its own fixed offset`() {
        // The phone is in Dubai (+04:00), the strap still on +02:00: the record's midnight is not one in Dubai.
        val midnight = Instant.parse("2026-10-06T22:00:00Z")
        val s = SleepParser.parse(record(midnight, Triple(1440, 1500, 8)), ZoneId.of("Asia/Dubai")).single().stages.single()
        assertEquals(midnight, s.start)
        assertEquals(midnight.plusSeconds(3600), s.end)
        // and the same as with no zone at all
        assertEquals(s, SleepParser.parse(record(midnight, Triple(1440, 1500, 8)), ZoneOffset.UTC).single().stages.single())
    }
}
