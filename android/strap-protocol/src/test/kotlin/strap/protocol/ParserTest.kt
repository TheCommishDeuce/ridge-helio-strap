package strap.protocol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import strap.protocol.LegacyVectors.arr
import strap.protocol.LegacyVectors.hex
import strap.protocol.LegacyVectors.num
import strap.protocol.LegacyVectors.str
import strap.protocol.model.Stage
import strap.protocol.parse.ActivityParser
import strap.protocol.parse.Replies
import strap.protocol.parse.SleepParser
import strap.protocol.parse.WorkoutParser
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private val parsers = LegacyVectors.obj("parsers")
    private val roundStart = Instant.ofEpochMilli(parsers.num("roundStartMs"))

    @Test
    fun `every activity type decodes exactly as the old implementation`() {
        for ((name, case) in parsers.getValue("activity").jsonObject) {
            val c = case.jsonObject
            val ours = ActivityParser.parse(c.num("code").toInt(), c.hex("raw"), roundStart)
            val theirs = c.arr("samples").map { it.jsonObject }
            assertEquals(theirs.size, ours.size, "count for $name")
            ours.zip(theirs).forEach { (s, t) ->
                assertEquals(t.num("ms"), s.at.toEpochMilli(), name)
                assertEquals(t.str("metric"), s.metric.wireName, name)
                assertEquals(t.getValue("value").jsonPrimitive.content.toDouble(), s.value, name)
            }
        }
    }

    @Test
    fun `sleep records decode as the old implementation, naps included, bad slot skipped`() {
        val v = parsers.getValue("sleep").jsonObject
        val ours = SleepParser.parse(v.hex("raw"))
        val theirs = v.arr("sessions").map { it.jsonObject }
        assertEquals(theirs.size, ours.size)
        ours.zip(theirs).forEach { (s, t) ->
            assertEquals(t.num("sessionStartMs"), s.sessionStart.toEpochMilli())
            assertEquals(t.num("sleepStartMin").toInt(), s.sleepStartMin)
            assertEquals(t.num("sleepEndMin").toInt(), s.sleepEndMin)
            assertEquals(t.num("avgHr").toInt(), s.avgHr)
            assertEquals(t.num("score").toInt(), s.score)
            assertEquals(listOf("remMin", "lightMin", "deepMin", "wakeMin").map { t.num(it).toInt() }, listOf(s.remMin, s.lightMin, s.deepMin, s.wakeMin))
            assertEquals(t.getValue("isNap").jsonPrimitive.content.toBoolean(), s.isNap)
            val stages = t.arr("stages").map { st -> st.jsonArray.map { it.jsonPrimitive.long } }
            assertEquals(stages, s.stages.map { listOf(it.start.toEpochMilli(), it.end.toEpochMilli(), it.stage.code.toLong()) })
        }
        assertTrue(ours.any { it.isNap } && ours.none { it.stages.any { st -> st.stage.code == 0x80 } })
    }

    @Test
    fun `workout summaries decode as the old implementation`() {
        val v = parsers.getValue("workout").jsonObject
        val ours = WorkoutParser.parseStream(v.hex("raw"))
        val theirs = v.arr("workouts").map { it.jsonObject }
        assertEquals(theirs.size, ours.size)
        ours.zip(theirs).forEach { (w, t) ->
            assertEquals(t.num("startMs"), w.start.toEpochMilli())
            assertEquals(
                listOf("sportType", "durationSec", "calories", "avgHr", "maxHr", "minHr").map { t.num(it).toInt() },
                listOf(w.sportType, w.durationSec, w.calories, w.avgHr, w.maxHr, w.minHr),
            )
        }
    }

    // ── spec/01 tables, independent of the old code ──

    @Test
    fun `sleep stage minutes are offsets from midnight minus 24 hours`() {
        val record = ByteArray(594)
        val midnight = 1_780_012_800L
        Bytes.putU32(record, 0, midnight - 3600)
        Bytes.putU32(record, 4, midnight)
        record[8] = 1
        record[9] = 1
        record[0x54] = 1
        Bytes.putU16(record, 0x56, 1440) // = midnight itself
        Bytes.putU16(record, 0x58, 1500)
        record[0x5a] = 8
        val stage = SleepParser.parse(record).single().stages.single()
        assertEquals(Instant.ofEpochSecond(midnight), stage.start)
        assertEquals(Instant.ofEpochSecond(midnight + 3600), stage.end)
        assertEquals(Stage.REM, stage.stage)
    }

    @Test
    fun `daily totals reply`() {
        val reply = "04010c".unhex() + ByteArray(12).also {
            Bytes.putU32(it, 0, 8123)
            Bytes.putU32(it, 4, 6010)
            Bytes.putU32(it, 8, 402)
        }
        val totals = Replies.dailyTotals(reply, Instant.EPOCH)!!
        assertEquals(listOf(8123L, 6010L, 402L), listOf(totals.steps, totals.distanceM, totals.calories))
        assertNull(Replies.dailyTotals("0401".unhex(), Instant.EPOCH))
    }

    @Test
    fun `time8 is local wall time with the offset in 15-minute units`() {
        val t = LocalDateTime.of(2026, 6, 4, 10, 49, 37)
        assertEquals("ea0706040a310008", Replies.time8(t, utcOffsetMinutes = 120).hex())
        assertEquals(0xEC, Replies.time8(t, utcOffsetMinutes = -300)[7].toInt() and 0xFF) // -20 & 0xFF
    }

    @Test
    fun `start reply`() {
        val ok = "100101".unhex() + ByteArray(4).also { Bytes.putU32(it, 0, 5) } + "ea0706040a3125".unhex()
        assertEquals(Replies.StartReply.Accepted(5, LocalDateTime.of(2026, 6, 4, 10, 49, 37)), Replies.startReply(ok))
        assertEquals(Replies.StartReply.Accepted(0, null), Replies.startReply("100101".unhex() + ByteArray(11)))
        assertIs<Replies.StartReply.Rejected>(Replies.startReply("100102".unhex()))
        assertEquals(Replies.StartReply.Malformed, Replies.startReply("100101".unhex()))
    }

    @Test
    fun `services list`() {
        val reply = "04020016000182000000".unhex().copyOf(9)
        assertEquals(mapOf(0x0016 to false, 0x0082 to false), Replies.services("040200160000820000".unhex()))
        assertTrue(Replies.services(reply)!!.isNotEmpty())
    }
}
