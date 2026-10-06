package strap.protocol.parse

import strap.protocol.Bytes
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** What the owner set: a wall-clock alarm in [zone]. The strap only knows its own clock. */
public data class ZonedAlarm(val hour: Int, val minute: Int, val days: Set<DayOfWeek>, val zone: ZoneId)

/** The time and repeat days as the strap stores them, on the strap's own clock. */
public data class StrapTime(val hour: Int, val minute: Int, val days: Set<DayOfWeek>)

/**
 * Alarms in a chosen timezone (D30). The strap rings at an hour and minute on its own clock,
 * so an alarm in another zone is written shifted by the difference, and a shift across
 * midnight moves every repeat day with it. The alarm zone's offset is taken at the alarm's
 * next ring, so a DST change between now and then is already counted; the caller re-checks
 * after every connection, which catches the changes after that.
 */
public object AlarmZones {
    private const val DAY_MINUTES = 24 * 60

    public fun toStrap(alarm: ZonedAlarm, strapOffset: ZoneOffset, now: Instant): StrapTime {
        val zoneOffset = nextRing(alarm, now).offset
        val shift = (strapOffset.totalSeconds - zoneOffset.totalSeconds) / 60
        val total = alarm.hour * 60 + alarm.minute + shift
        val dayShift = Math.floorDiv(total, DAY_MINUTES).toLong()
        val minutes = Math.floorMod(total, DAY_MINUTES)
        return StrapTime(minutes / 60, minutes % 60, alarm.days.mapTo(mutableSetOf()) { it.plus(dayShift) })
    }

    /** The alarm a strap value means in [zone]: the inverse of [toStrap], for alarms set elsewhere. */
    public fun fromStrap(time: StrapTime, strapOffset: ZoneOffset, zone: ZoneId, now: Instant): ZonedAlarm {
        val shift = (zone.rules.getOffset(now).totalSeconds - strapOffset.totalSeconds) / 60
        val total = time.hour * 60 + time.minute + shift
        val dayShift = Math.floorDiv(total, DAY_MINUTES).toLong()
        val minutes = Math.floorMod(total, DAY_MINUTES)
        return ZonedAlarm(minutes / 60, minutes % 60, time.days.mapTo(mutableSetOf()) { it.plus(dayShift) }, zone)
    }

    /** When [alarm] next rings in its zone: the next repeat day, or for a one-off the next such clock time. */
    internal fun nextRing(alarm: ZonedAlarm, now: Instant): ZonedDateTime {
        val today = now.atZone(alarm.zone)
        val at = LocalTime.of(alarm.hour, alarm.minute)
        for (d in 0L..7L) {
            val date = today.toLocalDate().plusDays(d)
            val ring = ZonedDateTime.of(date, at, alarm.zone)
            if (ring.isAfter(today) && (alarm.days.isEmpty() || date.dayOfWeek in alarm.days)) return ring
        }
        return today // unreachable: some day in the next eight matches
    }

    /**
     * The strap clock's offset from UTC: its local reading minus the true time, rounded to the
     * nearest quarter hour (every real zone is a multiple of 15 min; a drifted clock is not).
     * Null when the reading is no plausible zone (beyond ±14 h).
     */
    public fun offsetOf(strapLocal: LocalDateTime, utcNow: Instant): ZoneOffset? {
        val seconds = Duration.between(utcNow.atOffset(ZoneOffset.UTC).toLocalDateTime(), strapLocal).seconds
        val quarters = Math.round(seconds / 900.0)
        if (quarters !in -56L..56L) return null
        return ZoneOffset.ofTotalSeconds((quarters * 900).toInt())
    }
}

/** The standard Current Time characteristic `2a2b`: year u16, month, day, hour, minute, second, … */
public object CurrentTime {
    public fun parse(b: ByteArray): LocalDateTime? {
        if (b.size < 7) return null
        return runCatching {
            LocalDateTime.of(Bytes.u16(b, 0), Bytes.u8(b, 2), Bytes.u8(b, 3), Bytes.u8(b, 4), Bytes.u8(b, 5), Bytes.u8(b, 6))
        }.getOrNull()
    }
}
