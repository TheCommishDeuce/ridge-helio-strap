package strap.protocol

import strap.protocol.parse.Alarms
import strap.protocol.parse.StrapAlarm
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmsTest {
    // Layout as read off a strap on 2026-09-26 (spec/01 "Alarms"); times changed to neutral ones.
    private val reply = "0a03040007001f0000000100000109006000000001000002061e000000000100".unhex()

    @Test
    fun `the real list decodes`() {
        assertEquals(
            listOf(
                StrapAlarm(0, true, 7, 0, setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)),
                StrapAlarm(1, false, 9, 0, setOf(SATURDAY, SUNDAY)),
                StrapAlarm(2, false, 6, 30, emptySet()),
            ),
            Alarms.list(reply),
        )
    }

    @Test
    fun `an edit writes back every byte it did not change`() {
        val first = Alarms.list(reply)!!.first()
        assertEquals("0701" + "04" + "0007001f0000000100", Alarms.update(first).bytes.hex())
        assertEquals("0701" + "00" + "0007001f0000000100", Alarms.update(first.copy(enabled = false)).bytes.hex())
        assertEquals(0x08, Alarms.update(first).ack)
    }

    @Test
    fun `create and delete`() {
        val new = StrapAlarm(3, true, 6, 30, setOf(SUNDAY))
        assertEquals("0301" + "0403061e40" + "0000000100", Alarms.create(new).bytes.hex())
        assertEquals("050103", Alarms.delete(3).bytes.hex())
        assertEquals(0x06, Alarms.delete(3).ack)
    }

    @Test
    fun `not a list reply`() {
        assertNull(Alarms.list("02030a000000".unhex()))
        assertNull(Alarms.list("0a02".unhex() + ByteArray(10)))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
