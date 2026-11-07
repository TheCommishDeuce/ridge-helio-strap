package app.strap.sync

import android.util.Log
import app.strap.api.ApiException
import app.strap.api.BackupServer
import app.strap.store.LocalStore
import app.strap.store.LocalStore.JournalRow
import org.json.JSONArray
import org.json.JSONObject
import strap.science.Profile
import strap.science.SessionOverlay
import strap.science.WeightEntry
import strap.science.Workout
import strap.science.Zones
import strap.science.real
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What the owner tells Ridge rather than the strap: the profile and the journal (weights,
 * caffeine, alcohol, a day's activity status), plus the timezone the days are cut in (D31). The
 * phone owns them (D34 S3): edits land here first and reach the server through [push], its backup.
 *
 * Before D34 the server held them, so [seed] copies the server's once; until then the phone has
 * none. A phone set up without a server starts empty and seeds nothing.
 */
class Owner(private val store: LocalStore, private val server: () -> BackupServer?, private val zoneLog: ZoneLog) {
    val seeded: Boolean get() = store.metaValue(SEEDED) != null

    /** Copies the server's profile, journal and zones once. Null when done (or nothing to copy), else why not. */
    suspend fun seed(): String? {
        if (seeded) return null
        val api = server() ?: return null.also { markSeeded("no server") }
        val copy = try {
            api.profile()
        } catch (e: ApiException) {
            return e.message ?: "profile download failed"
        }
        val today = LocalDate.now()
        val entries = try {
            api.journal(today.minusDays(SEED_DAYS), today.plusDays(1))
        } catch (e: ApiException) {
            return e.message ?: "journal download failed"
        }
        store.batch {
            copy.optJSONObject("profile")?.let { store.setMetaValue(PROFILE, it.toString()); store.setMetaValue(PROFILE_PUSHED, "1") }
            store.setMetaValue(BASE_ZONE, copy.getString("timezone"))
            store.setMetaValue(ZONES, (copy.optJSONArray("zone_changes") ?: JSONArray()).toString())
            copy.copyWeights().forEach { w ->
                val id = "weight:${w.ts.toEpochMilli()}"
                store.putJournal(JournalRow(id, "weight", w.ts, w.kg.real(), "kg", null, id, pushed = true, deleted = false))
            }
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.getString("kind") == "weight") continue // every weight came with the profile copy
                val id = e.getString("id")
                store.putJournal(JournalRow(id, e.getString("kind"), Instant.ofEpochMilli(e.getLong("ts")), e.getDouble("amount"),
                    e.getString("unit"), e.optString("name").takeUnless { e.isNull("name") }, id, pushed = true, deleted = false))
            }
            markSeeded("server")
        }
        log("seeded from the server: ${copy.optJSONArray("weights")?.length() ?: 0} weights, ${entries.length()} journal entries")
        return null
    }

    /** Takes the profile, journal and zones from a backup (Restore): the server already has them all. */
    fun restore(records: JSONObject) {
        records.optJSONObject("profile")?.let { store.setMetaValue(PROFILE, it.toString()); store.setMetaValue(PROFILE_PUSHED, "1") }
        store.setMetaValue(BASE_ZONE, records.getString("timezone"))
        store.setMetaValue(ZONES, (records.optJSONArray("zone_changes") ?: JSONArray()).toString())
        records.copyWeights().forEach { w ->
            val id = "weight:${w.ts.toEpochMilli()}"
            store.putJournal(JournalRow(id, "weight", w.ts, w.kg.real(), "kg", null, id, pushed = true, deleted = false))
        }
        val journal = records.optJSONArray("journal") ?: JSONArray()
        for (i in 0 until journal.length()) {
            val e = journal.getJSONObject(i)
            val id = e.getString("id")
            store.putJournal(JournalRow(id, e.getString("kind"), Instant.ofEpochMilli(e.getLong("ts")), e.getDouble("amount"),
                e.getString("unit"), e.optString("name").takeUnless { e.isNull("name") }, id, pushed = true, deleted = false))
        }
        restoreSessions(records)
        markSeeded("restore")
    }

    private fun markSeeded(from: String) = store.setMetaValue(SEEDED, "$from@${Instant.now().toEpochMilli()}")

    // ── the profile ────────────────────────────────────────────────────────

    fun profile(): Profile? = store.metaValue(PROFILE)?.let { JSONObject().put("profile", JSONObject(it)).copyProfile() }

    fun setProfile(p: Profile) {
        store.setMetaValue(PROFILE, JSONObject()
            .put("height_cm", p.heightCm?.real() ?: JSONObject.NULL).put("sex", p.sex ?: JSONObject.NULL)
            .put("dob", p.dob?.toString() ?: JSONObject.NULL).put("srpa", p.srpa ?: JSONObject.NULL).toString())
        store.setMetaValue(PROFILE_PUSHED, "0")
    }

    // ── the journal ────────────────────────────────────────────────────────

    /** Entries from [from] until [until], newest first. */
    fun journal(from: Instant, until: Instant): List<JournalEntry> =
        store.journalRows("deleted = 0 AND ts >= ? AND ts < ?", from.toEpochMilli().toString(), until.toEpochMilli().toString()).reversed()
            .map { JournalEntry(it.id, it.kind, it.ts, it.amount, it.unit, it.name) }

    fun add(kind: String, amount: Double, name: String?, at: Instant) {
        val unit = UNITS.getValue(kind)
        // Kept as the server keeps it: a weight is REAL there, and its id is its time.
        val id = if (kind == "weight") "weight:${at.toEpochMilli()}" else "local:${UUID.randomUUID()}"
        val value = if (kind == "weight") amount.toFloat().real() else amount
        store.putJournal(JournalRow(id, kind, at, value, unit, name, null, pushed = false, deleted = false))
    }

    fun delete(id: String) {
        val row = store.journalRows("id = ?", id).firstOrNull() ?: return
        if (row.serverId == null) store.removeJournal(id) else store.putJournal(row.copy(deleted = true, pushed = false))
    }

    /** Marks [day] with [status] in place of any it had: one entry at the day's middle; an active day has none. */
    fun setStatus(day: LocalDate, status: DayStatus) {
        val (from, until) = daysIn().bounds(day)
        store.batch {
            store.journalRows("deleted = 0 AND kind = 'status' AND ts >= ? AND ts < ?", from.toEpochMilli().toString(), until.toEpochMilli().toString())
                .forEach { delete(it.id) }
            status.key?.let { add("status", 1.0, it, from.plus(java.time.Duration.between(from, until).dividedBy(2))) }
        }
    }

    /** The marked days from [from] through [to]; a day not in it is active. */
    fun statuses(from: LocalDate, to: LocalDate): Map<LocalDate, DayStatus> {
        val zones = daysIn()
        return store.journalRows("deleted = 0 AND kind = 'status' AND ts >= ? AND ts < ?",
            zones.bounds(from).first.toEpochMilli().toString(), zones.bounds(to).second.toEpochMilli().toString())
            .associate { zones.dateOf(it.ts) to DayStatus.of(it.name) }
    }

    /** The zones the days are cut in; the phone's own before there are any. */
    private fun daysIn(): Zones = zones() ?: Zones(java.time.ZoneId.systemDefault().id)

    fun weights(): List<WeightEntry> = store.journalRows("deleted = 0 AND kind = 'weight'").map { WeightEntry(it.ts, it.amount.toFloat()) }

    // ── the sessions (D43) ─────────────────────────────────────────────────

    /** The changes in force (not deleted) that touch [from] until [until]. */
    fun sessions(from: Instant, until: Instant): List<SessionOverlay> =
        store.sessionRows("deleted = 0 AND start < ? AND end_ts > ?", until.toEpochMilli().toString(), from.toEpochMilli().toString()).map { it.overlay }

    /** Adds a session from [start] to [end]; returns where the derive starts again. */
    fun addSession(sport: Int, start: Instant, end: Instant): Instant {
        putSession(SessionOverlay("local:${UUID.randomUUID()}", SessionOverlay.Action.ADD, null, start, end, sport))
        return start
    }

    /**
     * Sets [w]'s sport and window: an added session is replaced by the new one, a strap workout
     * (as recorded or already edited) gets one edit in place of any it had.
     */
    fun editWorkout(w: Workout, sport: Int, start: Instant, end: Instant): Instant = store.batch {
        w.overlayId?.let(::dropSession)
        if (w.origin == Workout.Origin.ADDED) addSession(sport, start, end)
        else {
            val target = w.strapStart ?: w.start
            putSession(SessionOverlay("local:${UUID.randomUUID()}", SessionOverlay.Action.EDIT, target, start, end, sport))
        }
        minOf(w.start, start)
    }

    /** Hides a strap workout (its edit goes with it) or deletes an added session. */
    fun hideWorkout(w: Workout): Instant = store.batch {
        w.overlayId?.let(::dropSession)
        if (w.origin != Workout.Origin.ADDED) {
            val target = w.strapStart ?: w.start
            val strapEnd = store.strapWorkout(target)?.end ?: w.end
            putSession(SessionOverlay("local:${UUID.randomUUID()}", SessionOverlay.Action.HIDE, target, target, strapEnd, w.sport))
        }
        w.start
    }

    /** Takes back one change: a hidden or edited strap workout shows as recorded again. Where the derive starts, or null when it's gone. */
    fun undoSession(id: String): Instant? {
        val o = store.sessionRows("id = ?", id).firstOrNull()?.overlay ?: return null
        dropSession(id)
        return listOfNotNull(o.start, o.target).min()
    }

    private fun putSession(o: SessionOverlay) = store.putSession(LocalStore.SessionRow(o, null, pushed = false, deleted = false))

    private fun dropSession(id: String) {
        val row = store.sessionRows("id = ?", id).firstOrNull() ?: return
        if (row.serverId == null) store.removeSession(id) else store.putSession(row.copy(deleted = true, pushed = false))
    }

    private fun restoreSessions(records: JSONObject) {
        val a = records.optJSONArray("sessions") ?: return
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            val action = runCatching { SessionOverlay.Action.valueOf(e.getString("action").uppercase()) }.getOrNull() ?: continue
            val id = e.getString("id")
            store.putSession(LocalStore.SessionRow(SessionOverlay(id, action, if (e.isNull("target")) null else Instant.ofEpochMilli(e.getLong("target")),
                Instant.ofEpochMilli(e.getLong("start")), Instant.ofEpochMilli(e.getLong("end")), e.getInt("sport")), id, pushed = true, deleted = false))
        }
    }

    // ── the zones ──────────────────────────────────────────────────────────

    /** The base zone the server started from, every change it recorded, and every change this phone saw. */
    fun zones(): Zones? {
        val base = store.metaValue(BASE_ZONE) ?: return null
        val known = JSONArray(store.metaValue(ZONES) ?: "[]")
        val changes = List(known.length()) { i -> known.getJSONObject(i).let { Instant.ofEpochMilli(it.getLong("since")) to it.getString("timezone") } } +
            zoneLog.all()
        return Zones(base, changes.distinctBy { it.first })
    }

    /** A phone without a server takes its own zone as the base, once. */
    fun ensureBaseZone() {
        if (store.metaValue(BASE_ZONE) == null) store.setMetaValue(BASE_ZONE, java.time.ZoneId.systemDefault().id)
    }

    /** Everything the science reads from here, as one string: a change re-derives the era. */
    fun fingerprint(): String = listOf(store.metaValue(PROFILE), store.metaValue(BASE_ZONE), store.metaValue(ZONES),
        weights().joinToString { "${it.ts.toEpochMilli()}=${it.kg}" }, zoneLog.all().joinToString()).joinToString("|")

    // ── the outbox ─────────────────────────────────────────────────────────

    /** Sends the profile and journal changes to the server. Null on success or without a server, else the failure. */
    suspend fun push(): String? {
        if (!seeded) return null
        val api = server() ?: return null
        try {
            if (store.metaValue(PROFILE_PUSHED) == "0") {
                store.metaValue(PROFILE)?.let { api.putProfile(JSONObject(it)) }
                store.setMetaValue(PROFILE_PUSHED, "1")
            }
            for (row in store.journalRows("pushed = 0")) {
                if (row.deleted) {
                    row.serverId?.let { api.deleteJournal(it) }
                    store.removeJournal(row.id)
                } else {
                    val body = JSONObject().put("kind", row.kind).put("amount", row.amount)
                        .put("ts", row.ts.atOffset(java.time.ZoneOffset.UTC).toString())
                    row.name?.let { body.put("name", it) }
                    val id = api.addJournal(body).getString("id")
                    store.putJournal(row.copy(serverId = id, pushed = true))
                }
            }
            for (row in store.sessionRows("pushed = 0")) {
                if (row.deleted) {
                    row.serverId?.let { api.deleteSession(it) }
                    store.removeSession(row.overlay.id)
                } else {
                    val o = row.overlay
                    fun iso(t: Instant) = t.atOffset(java.time.ZoneOffset.UTC).toString()
                    val body = JSONObject().put("action", o.action.name.lowercase()).put("start", iso(o.start)).put("end", iso(o.end))
                        .put("sport", o.sport).put("target", o.target?.let(::iso) ?: JSONObject.NULL)
                    val id = api.addSession(body).getString("id")
                    store.putSession(row.copy(serverId = id, pushed = true))
                }
            }
        } catch (e: ApiException) {
            return e.message ?: "upload failed"
        }
        return null
    }

    /** How many profile, journal and session changes wait for the server. */
    fun unpushed(): Int = store.journalRows("pushed = 0").size + store.sessionRows("pushed = 0").size +
        if (store.metaValue(PROFILE_PUSHED) == "0") 1 else 0

    private fun log(line: String) = Log.i("strap", "owner: $line")

    companion object {
        private const val SEEDED = "owner_seeded"
        private const val PROFILE = "owner_profile"
        private const val PROFILE_PUSHED = "owner_profile_pushed"
        private const val BASE_ZONE = "owner_base_zone"
        private const val ZONES = "owner_zone_changes"
        private const val SEED_DAYS = 400L
        val UNITS = mapOf("caffeine" to "mg", "alcohol" to "drinks", "weight" to "kg", "status" to "day")
    }
}

/**
 * A day's activity status (Bevel's): active unless marked otherwise. Kept in the journal as a
 * "status" entry named by [key]; the server backs it up like the rest.
 */
enum class DayStatus(val key: String?, val label: String, val sub: String) {
    ACTIVE(null, "Active", "Staying engaged and healthy"),
    SICK("sick", "Sick", "Resting from illness"),
    INJURED("injured", "Injured", "Recovering from an injury"),
    BREAK("break", "On a break", "Taking time off from training"),
    ;

    companion object {
        fun of(key: String?): DayStatus = entries.firstOrNull { it.key == key } ?: ACTIVE
    }
}

/** One journal entry: caffeine (mg), alcohol (drinks), a weight (kg) or a day's status, with an optional name ("espresso", "sick"). */
data class JournalEntry(val id: String, val kind: String, val ts: Instant, val amount: Double, val unit: String, val name: String?)

// The server's shapes (`GET /v1/profile`), read once by [Owner.seed] and for the stored profile.
private fun JSONObject.copyProfile(): Profile? = optJSONObject("profile")?.let { p ->
    Profile(
        if (p.isNull("height_cm")) null else p.getDouble("height_cm").toFloat(), if (p.isNull("sex")) null else p.getString("sex"),
        if (p.isNull("dob")) null else LocalDate.parse(p.getString("dob")), if (p.isNull("srpa")) null else p.getInt("srpa"),
    )
}

private fun JSONObject.copyWeights(): List<WeightEntry> {
    val w = optJSONArray("weights") ?: return emptyList()
    return List(w.length()) { i -> w.getJSONObject(i).let { WeightEntry(Instant.ofEpochMilli(it.getLong("ts")), it.getDouble("kg").toFloat()) } }
}
