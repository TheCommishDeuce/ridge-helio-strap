package strap.protocol

import strap.protocol.parse.AlarmZones
import strap.protocol.parse.CurrentTime
import strap.protocol.parse.StrapTime
import strap.protocol.parse.ZonedAlarm
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmZonesTest {
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val summer = Instant.parse("2026-07-01T12:00:00Z") // Berlin +2, New York −4
    private val cest = ZoneOffset.ofHours(2)

    @Test
    fun `an alarm in the strap's own zone is written unchanged`() {
        assertEquals(StrapTime(7, 0, weekdays), AlarmZones.toStrap(ZonedAlarm(7, 0, weekdays, berlin), cest, summer))
    }

    @Test
    fun `a later zone shifts forward, across midnight onto the next days`() {
        // 07:00 New York = 13:00 Berlin; 20:00 New York = 02:00 Berlin the next day.
        assertEquals(StrapTime(13, 0, weekdays), AlarmZones.toStrap(ZonedAlarm(7, 0, weekdays, newYork), cest, summer))
        assertEquals(
            StrapTime(2, 0, setOf(TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY)),
            AlarmZones.toStrap(ZonedAlarm(20, 0, weekdays, newYork), cest, summer),
        )
    }

    @Test
    fun `an earlier zone shifts back onto the previous days`() {
        // 07:00 Tokyo (+9) = 00:00 Berlin; 06:00 Tokyo = 23:00 Berlin the day before.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(StrapTime(0, 0, weekdays), AlarmZones.toStrap(ZonedAlarm(7, 0, weekdays, tokyo), cest, summer))
        assertEquals(
            StrapTime(23, 0, setOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY)),
            AlarmZones.toStrap(ZonedAlarm(6, 0, weekdays, tokyo), cest, summer),
        )
    }

    @Test
    fun `a half-hour zone and a one-off alarm`() {
        // 07:00 Kolkata (+5:30) = 03:30 Berlin; no days stays no days.
        assertEquals(StrapTime(3, 30, emptySet()), AlarmZones.toStrap(ZonedAlarm(7, 0, emptySet(), ZoneId.of("Asia/Kolkata")), cest, summer))
    }

    @Test
    fun `the alarm zone's offset is the one at its next ring, across a DST change`() {
        // Saturday 2026-10-24 12:00Z: Berlin is still +2, but the Monday ring falls after the
        // switch on Sunday 25 October, so 07:00 Berlin (+1) is 08:00 on a strap clock left at +2.
        val beforeSwitch = Instant.parse("2026-10-24T12:00:00Z")
        assertEquals(StrapTime(8, 0, weekdays), AlarmZones.toStrap(ZonedAlarm(7, 0, weekdays, berlin), cest, beforeSwitch))
        // A strap clock that follows the switch (+1) needs no shift.
        assertEquals(StrapTime(7, 0, weekdays), AlarmZones.toStrap(ZonedAlarm(7, 0, weekdays, berlin), ZoneOffset.ofHours(1), beforeSwitch))
    }

    @Test
    fun `fromStrap undoes toStrap`() {
        val alarm = ZonedAlarm(20, 15, weekdays, newYork)
        assertEquals(alarm, AlarmZones.fromStrap(AlarmZones.toStrap(alarm, cest, summer), cest, newYork, summer))
    }

    @Test
    fun `the strap clock's offset is rounded to a quarter hour`() {
        val now = Instant.parse("2026-07-01T12:00:00Z")
        assertEquals(cest, AlarmZones.offsetOf(LocalDateTime.parse("2026-07-01T14:00:41"), now)) // 41 s drift
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), AlarmZones.offsetOf(LocalDateTime.parse("2026-07-01T17:29:30"), now))
        assertEquals(ZoneOffset.ofHours(-4), AlarmZones.offsetOf(LocalDateTime.parse("2026-07-01T08:00:00"), now))
        assertNull(AlarmZones.offsetOf(LocalDateTime.parse("2000-01-01T00:00:00"), now))
    }

    @Test
    fun `current time characteristic`() {
        // 2026-07-01 14:00:41, then day of week, fractions, adjust reason (ignored).
        assertEquals(LocalDateTime.parse("2026-07-01T14:00:41"), CurrentTime.parse("ea0707010e0029030000".unhex()))
        assertNull(CurrentTime.parse("ea0707".unhex()))
        assertNull(CurrentTime.parse("ea070d010e0029".unhex())) // month 13
    }
}
