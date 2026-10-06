package app.strap.sync

import android.content.Context
import strap.protocol.parse.AlarmZones
import strap.protocol.parse.Alarms
import strap.protocol.parse.StrapAlarm
import strap.protocol.parse.StrapTime
import strap.protocol.parse.ZonedAlarm
import strap.protocol.session.StrapSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The strap clock's offset from UTC, and whether it was read off the strap or assumed (D30). */
data class StrapClock(val offset: ZoneOffset, val measured: Boolean)

/**
 * Alarms in a chosen timezone (D30). The strap has no field for a zone, so the phone keeps
 * what the owner set (time, days, zone) per alarm slot, and writes the strap the time that
 * rings then on the strap's own clock. [reconcile] runs on every connection: a DST change or a
 * strap clock set elsewhere moves the strap value, never the owner's alarm.
 */
class AlarmKeeper(context: Context) {
    private val prefs = context.getSharedPreferences("alarm_zones", Context.MODE_PRIVATE)

    fun get(slot: Int): ZonedAlarm? = prefs.getString(slot.toString(), null)?.let(::decode)

    fun put(slot: Int, alarm: ZonedAlarm) = prefs.edit().putString(slot.toString(), encode(alarm)).apply()

    fun remove(slot: Int) = prefs.edit().remove(slot.toString()).apply()

    /** What [a] means to the owner: its stored alarm, or the strap value read in [fallback]. */
    fun intent(a: StrapAlarm, clock: StrapClock, fallback: ZoneId, now: Instant = Instant.now()): ZonedAlarm =
        get(a.slot) ?: AlarmZones.fromStrap(StrapTime(a.hour, a.minute, a.days), clock.offset, fallback, now)

    /** [a] with the strap time that rings at [alarm], on a strap clock at [clock]. */
    fun onStrap(a: StrapAlarm, alarm: ZonedAlarm, clock: StrapClock, now: Instant = Instant.now()): StrapAlarm =
        AlarmZones.toStrap(alarm, clock.offset, now).let { a.copy(hour = it.hour, minute = it.minute, days = it.days) }

    /**
     * Reads the alarms; forgets zones of slots no longer on the strap; rewrites any zoned alarm
     * whose strap time is out of date, then reads again. Null when the strap did not answer.
     */
    suspend fun reconcile(s: StrapSession, clock: StrapClock, log: (String) -> Unit): List<StrapAlarm>? {
        val now = Instant.now()
        val list = s.readAlarms() ?: return null
        val slots = list.map { it.slot.toString() }.toSet()
        prefs.all.keys.filter { it !in slots }.forEach { prefs.edit().remove(it).apply() }
        var wrote = false
        for (a in list) {
            val wanted = get(a.slot)?.let { onStrap(a, it, clock, now) } ?: continue
            if (wanted == a) continue
            log("alarm ${a.slot}: strap %02d:%02d → %02d:%02d (zone ${get(a.slot)?.zone})".format(a.hour, a.minute, wanted.hour, wanted.minute))
            s.writeAlarm(Alarms.update(wanted))
            wrote = true
        }
        return if (wrote) s.readAlarms() else list
    }

    private fun encode(a: ZonedAlarm) = "%d:%d:%d:%s".format(a.hour, a.minute, a.days.fold(0) { m, d -> m or (1 shl (d.value - 1)) }, a.zone.id)

    private fun decode(s: String): ZonedAlarm? = runCatching {
        val (h, m, mask, zone) = s.split(':', limit = 4)
        ZonedAlarm(h.toInt(), m.toInt(), DayOfWeek.entries.filterTo(mutableSetOf()) { mask.toInt() and (1 shl (it.value - 1)) != 0 }, ZoneId.of(zone))
    }.getOrNull()
}
