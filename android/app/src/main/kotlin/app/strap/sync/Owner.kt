package app.strap.sync

import android.util.Log
import app.strap.api.ApiException
import app.strap.api.BackupServer
import app.strap.store.LocalStore
import app.strap.store.LocalStore.JournalRow
import org.json.JSONArray
import org.json.JSONObject
import strap.science.Profile
import strap.science.WeightEntry
import strap.science.Zones
import strap.science.real
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What the owner tells Ridge rather than the strap: the profile and the journal (weights,
 * caffeine, alcohol), plus the timezone the days are cut in (D31). The phone owns them (D34 S3):
 * edits land here first and reach the server through [push], its backup.
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

    fun weights(): List<WeightEntry> = store.journalRows("deleted = 0 AND kind = 'weight'").map { WeightEntry(it.ts, it.amount.toFloat()) }

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
        } catch (e: ApiException) {
            return e.message ?: "upload failed"
        }
        return null
    }

    /** How many profile and journal changes wait for the server. */
    fun unpushed(): Int = store.journalRows("pushed = 0").size + if (store.metaValue(PROFILE_PUSHED) == "0") 1 else 0

    private fun log(line: String) = Log.i("strap", "owner: $line")

    companion object {
        private const val SEEDED = "owner_seeded"
        private const val PROFILE = "owner_profile"
        private const val PROFILE_PUSHED = "owner_profile_pushed"
        private const val BASE_ZONE = "owner_base_zone"
        private const val ZONES = "owner_zone_changes"
        private const val SEED_DAYS = 400L
        val UNITS = mapOf("caffeine" to "mg", "alcohol" to "drinks", "weight" to "kg")
    }
}

/** One journal entry: caffeine (mg), alcohol (drinks) or a weight (kg), with an optional name ("espresso"). */
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
