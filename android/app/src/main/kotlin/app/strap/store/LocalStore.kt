package app.strap.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import strap.protocol.model.Metric
import strap.protocol.sync.SyncResult
import strap.protocol.sync.SyncWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import strap.science.Derived
import strap.science.DerivedStore
import strap.science.DeviceDailyTotal
import strap.science.IllnessFlag
import strap.science.MemoryData
import strap.science.Profile
import strap.science.Sample
import strap.science.SleepSession
import strap.science.Stage
import strap.science.WeightEntry
import strap.science.Workout
import strap.science.Zones
import java.time.Instant
import java.time.LocalDate

/**
 * What the strap delivered, the phone's own derived numbers and the owner's journal: the only
 * live copy (D34). Each raw row carries an outbox flag for the backup to the server. Timestamps
 * are epoch milliseconds, UTC.
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context, "strap.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    /**
     * Version 1 was a pre-release cache, rebuilt on upgrade. From version 2 on the phone's copy is
     * kept (D34: it becomes the only live copy), so every later upgrade only adds; never drop here.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf("samples", "sleep_sessions", "workouts", "daily_totals", "meta", "sync_log").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
            return
        }
        if (oldVersion < 3) DERIVED_SCHEMA.forEach(db::execSQL)
        if (oldVersion < 4) JOURNAL_SCHEMA.forEach(db::execSQL)
        if (oldVersion < 5) RESTORE_SCHEMA.forEach(db::execSQL)
    }

    // ── the phone's own derived numbers (D34 S3) ────────────────────────────

    /** What strap-science writes and reads: one row per (day, metric), and the illness flags. Days are epoch days. */
    val derived: DerivedStore = object : DerivedStore {
        override fun get(day: LocalDate, metric: String): Derived? = rows(
            "SELECT day, metric, value, flags FROM derived WHERE day = ? AND metric = ?", day.toEpochDay().toString(), metric,
        ).firstOrNull()

        override fun range(metric: String, first: LocalDate, last: LocalDate): List<Derived> = rows(
            "SELECT day, metric, value, flags FROM derived WHERE metric = ? AND day >= ? AND day <= ? ORDER BY day",
            metric, first.toEpochDay().toString(), last.toEpochDay().toString(),
        )

        override fun put(row: Derived) {
            writableDatabase.insertWithOnConflict("derived", null, ContentValues().apply {
                put("day", row.day.toEpochDay()); put("metric", row.metric); put("value", row.value); put("flags", row.flags.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun clear(day: LocalDate) {
            val d = arrayOf(day.toEpochDay().toString())
            writableDatabase.delete("derived", "day = ?", d)
            writableDatabase.delete("illness", "day = ?", d)
        }

        override fun illness(day: LocalDate): IllnessFlag? = flags("SELECT * FROM illness WHERE day = ?", day.toEpochDay().toString()).firstOrNull()

        override fun putIllness(flag: IllnessFlag) {
            writableDatabase.insertWithOnConflict("illness", null, ContentValues().apply {
                put("day", flag.date.toEpochDay()); put("severity", flag.severity)
                flag.rrDeltaBpm?.let { put("rr_delta_bpm", it) }; flag.tempDeltaC?.let { put("temp_delta_c", it) }
                put("sustained", if (flag.sustained) 1 else 0); put("note_ids", JSONArray(flag.noteIds).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        override fun clearIllness(day: LocalDate): Boolean = writableDatabase.delete("illness", "day = ?", arrayOf(day.toEpochDay().toString())) > 0

        override fun latestIllness(first: LocalDate, last: LocalDate): IllnessFlag? = flags(
            "SELECT * FROM illness WHERE day >= ? AND day <= ? ORDER BY day DESC LIMIT 1", first.toEpochDay().toString(), last.toEpochDay().toString(),
        ).firstOrNull()

        private fun rows(sql: String, vararg args: String): List<Derived> = readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(Derived(LocalDate.ofEpochDay(c.getLong(0)), c.getString(1), c.getDouble(2), Json.parseToJsonElement(c.getString(3)).jsonObject))
            }
        }

        private fun flags(sql: String, vararg args: String): List<IllnessFlag> = readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val notes = JSONArray(c.getString(c.getColumnIndexOrThrow("note_ids")))
                    add(IllnessFlag(
                        LocalDate.ofEpochDay(c.getLong(c.getColumnIndexOrThrow("day"))), c.getString(c.getColumnIndexOrThrow("severity")),
                        c.floatOrNull("rr_delta_bpm"), c.floatOrNull("temp_delta_c"), c.getInt(c.getColumnIndexOrThrow("sustained")) == 1,
                        List(notes.length()) { notes.getString(it) },
                    ))
                }
            }
        }

        private fun android.database.Cursor.floatOrNull(column: String): Float? =
            getColumnIndexOrThrow(column).let { if (isNull(it)) null else getFloat(it) }
    }

    // ── the journal (D34 S3) ────────────────────────────────────────────────

    data class JournalRow(val id: String, val kind: String, val ts: Instant, val amount: Double, val unit: String, val name: String?, val serverId: String?, val pushed: Boolean, val deleted: Boolean)

    fun journalRows(where: String = "1", vararg args: String): List<JournalRow> = readableDatabase.rawQuery(
        "SELECT id, kind, ts, amount, unit, name, server_id, pushed, deleted FROM journal WHERE $where ORDER BY ts", args,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(JournalRow(c.getString(0), c.getString(1), Instant.ofEpochMilli(c.getLong(2)), c.getDouble(3), c.getString(4),
                if (c.isNull(5)) null else c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getInt(7) == 1, c.getInt(8) == 1))
        }
    }

    fun putJournal(row: JournalRow) {
        writableDatabase.insertWithOnConflict("journal", null, ContentValues().apply {
            put("id", row.id); put("kind", row.kind); put("ts", row.ts.toEpochMilli()); put("amount", row.amount); put("unit", row.unit)
            row.name?.let { put("name", it) }; row.serverId?.let { put("server_id", it) }
            put("pushed", if (row.pushed) 1 else 0); put("deleted", if (row.deleted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeJournal(id: String) = writableDatabase.delete("journal", "id = ?", arrayOf(id))

    /**
     * The earliest instant stored since the derive last caught up, if any: where the next one
     * starts (PhoneScience). Written with the data, so a derive the process never got to still
     * happens.
     */
    fun underivedFrom(): Instant? = meta(readableDatabase, UNDERIVED)?.let { Instant.ofEpochMilli(it.toLong()) }

    /** Forgets the mark the derive started from, unless a save moved it meanwhile. */
    fun clearUnderived(seen: Instant) {
        writableDatabase.delete("meta", "key = ? AND value = ?", arrayOf(UNDERIVED, seen.toEpochMilli().toString()))
    }

    private fun markUnderived(db: SQLiteDatabase, at: Instant?) {
        if (at == null) return
        val held = meta(db, UNDERIVED)?.toLong()
        if (held == null || at.toEpochMilli() < held) setMeta(db, UNDERIVED, at.toEpochMilli().toString())
    }

    /** The newest day the phone holds a derived row for, if any. */
    fun derivedThrough(): LocalDate? = readableDatabase.rawQuery("SELECT MAX(day) FROM derived", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) LocalDate.ofEpochDay(it.getLong(0)) else null }

    /** Where the next sync resumes: newest held timestamp per stream, and the one-shot flags. */
    fun window(): SyncWindow {
        val db = readableDatabase
        val last = mutableMapOf<Metric, Instant>()
        db.rawQuery("SELECT metric, MAX(ts) FROM samples GROUP BY metric", null).use { c ->
            while (c.moveToNext()) {
                val metric = Metric.entries.firstOrNull { it.wireName == c.getString(0) } ?: continue
                last[metric] = Instant.ofEpochMilli(c.getLong(1))
            }
        }
        return SyncWindow(
            lastSampleAt = last,
            lastSleepStart = maxInstant(db, "SELECT MAX(record_ts) FROM sleep_sessions"),
            lastWorkoutStart = maxInstant(db, "SELECT MAX(start) FROM workouts"),
            stressBackfillDone = meta(db, STRESS_BACKFILL) == "1",
            napBackfillDone = meta(db, NAP_BACKFILL) == "1",
        )
    }

    /** Stores one sync's yield in one transaction, with its diagnostics. */
    fun save(result: SyncResult, batteryPercent: Int?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sample = db.compileStatement(
                "INSERT INTO samples(metric, ts, value) VALUES(?,?,?) " +
                    "ON CONFLICT(metric, ts) DO UPDATE SET value = excluded.value, " +
                    "pushed = CASE WHEN samples.value = excluded.value THEN samples.pushed ELSE 0 END",
            )
            for (s in result.samples) {
                if (s.metric == Metric.SLEEP_SESSION) continue // a marker; the session itself is stored below
                sample.bindString(1, s.metric.wireName)
                sample.bindLong(2, s.at.toEpochMilli())
                sample.bindDouble(3, s.value)
                sample.executeInsert()
                sample.clearBindings()
            }
            result.sleepSessions.forEach { s ->
                val v = s.values()
                // A rewritten night can start a minute off its earlier version: drop the earlier
                // version it overlaps rather than keep the night twice (the server does the same).
                val end = s.stages.lastOrNull()?.end?.toEpochMilli()
                if (end != null) {
                    val start = v.getAsLong("start")
                    nights(db, "is_nap = ? AND start <> ? AND start < ?", v.getAsInteger("is_nap").toString(), start.toString(), end.toString())
                        .filter { (it.stagesEnd ?: return@filter false) > start }
                        .forEach { db.delete("sleep_sessions", "start = ?", arrayOf(it.start.toString())) }
                }
                db.insertWithOnConflict("sleep_sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            result.workouts.forEach { db.insertWithOnConflict("workouts", null, it.values(), SQLiteDatabase.CONFLICT_REPLACE) }
            result.dailyTotals?.let {
                db.insertWithOnConflict("daily_totals", null, ContentValues().apply {
                    put("read_at", it.readAt.toEpochMilli()); put("steps", it.steps)
                    put("distance_m", it.distanceM); put("calories", it.calories)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            markUnderived(db, listOfNotNull(
                result.samples.minOfOrNull { it.at },
                result.sleepSessions.minOfOrNull { it.stages.firstOrNull()?.start ?: it.sessionStart },
                result.workouts.minOfOrNull { it.start },
                result.dailyTotals?.readAt,
            ).minOrNull())
            batteryPercent?.let { setMeta(db, BATTERY, "$it@${result.completedAt.toEpochMilli()}") }
            if (result.stressBackfillRan) setMeta(db, STRESS_BACKFILL, "1")
            if (result.napBackfillRan) setMeta(db, NAP_BACKFILL, "1")
            db.insert("sync_log", null, ContentValues().apply {
                put("at", result.completedAt.toEpochMilli())
                put("summary", SyncSummary.of(result, batteryPercent).toString())
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── outbox for the server push ──────────────────────────────────────────

    /** Up to [limit] unsent samples, oldest first, as the server's JSON shape. Keys identify them for [markSamplesPushed]. */
    fun unpushedSamples(limit: Int): List<Pair<Pair<String, Long>, JSONObject>> = readableDatabase.rawQuery(
        "SELECT metric, ts, value FROM samples WHERE pushed = 0 ORDER BY ts LIMIT ?", arrayOf(limit.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add((c.getString(0) to c.getLong(1)) to JSONObject().put("metric", c.getString(0)).put("ts", c.getLong(1)).put("value", c.getDouble(2)))
            }
        }
    }

    fun markSamplesPushed(keys: List<Pair<String, Long>>) = transaction { db ->
        val st = db.compileStatement("UPDATE samples SET pushed = 1 WHERE metric = ? AND ts = ?")
        for ((metric, ts) in keys) {
            st.bindString(1, metric); st.bindLong(2, ts); st.executeUpdateDelete(); st.clearBindings()
        }
    }

    /** Unsent sleep sessions, workouts and counter readings, in the server's JSON shape. */
    fun unpushedRecords(): JSONObject {
        val db = readableDatabase
        val sleep = JSONArray()
        db.rawQuery("SELECT is_nap, avg_hr, score, rem_min, light_min, deep_min, wake_min, stages FROM sleep_sessions WHERE pushed = 0 ORDER BY start", null).use { c ->
            while (c.moveToNext()) {
                val stages = JSONArray(c.getString(7))
                if (stages.length() == 0) continue // no window without stages; nothing for the science to use
                sleep.put(JSONObject()
                    .put("start_ts", stages.getJSONArray(0).getLong(0))
                    .put("end_ts", stages.getJSONArray(stages.length() - 1).getLong(1))
                    .put("kind", if (c.getInt(0) == 1) "nap" else "main")
                    .put("avg_hr", c.getInt(1)).put("score", c.getInt(2))
                    .put("rem_min", c.getInt(3)).put("light_min", c.getInt(4)).put("deep_min", c.getInt(5)).put("wake_min", c.getInt(6))
                    .put("stages", stages))
            }
        }
        val workouts = JSONArray()
        db.rawQuery("SELECT start, sport, duration_s, calories, avg_hr, max_hr, min_hr FROM workouts WHERE pushed = 0 ORDER BY start", null).use { c ->
            while (c.moveToNext()) {
                workouts.put(JSONObject().put("start_ts", c.getLong(0)).put("sport", c.getInt(1)).put("duration_s", c.getInt(2))
                    .put("calories", c.getInt(3)).put("avg_hr", c.getInt(4)).put("max_hr", c.getInt(5)).put("min_hr", c.getInt(6)))
            }
        }
        val totals = JSONArray()
        db.rawQuery("SELECT read_at, steps, distance_m, calories FROM daily_totals WHERE pushed = 0 ORDER BY read_at", null).use { c ->
            while (c.moveToNext()) {
                totals.put(JSONObject().put("read_at", c.getLong(0)).put("steps", c.getLong(1)).put("distance_m", c.getLong(2)).put("calories", c.getLong(3)))
            }
        }
        return JSONObject().put("sleep", sleep).put("workouts", workouts).put("daily_totals", totals)
    }

    // ── the science's view (D34) ────────────────────────────────────────────

    /**
     * Everything stored, as the server receives it from [unpushedSamples] and [unpushedRecords]:
     * metrics under the server's names (unknown ones dropped), missing session and workout
     * fields as 0, sessions without stages left out, and per day only the latest counter
     * reading, filed on the local date it was read. [zones], [profile] and [weights] are the
     * owner's (Owner). Only what lies from [from] (a day start, `Zones.rawFrom`) until [until]
     * is read, cut as `MemoryData.window` cuts; null bounds read everything.
     */
    fun scienceData(zones: Zones, profile: Profile?, weights: List<WeightEntry>, from: Instant? = null, until: Instant? = null): MemoryData {
        val db = readableDatabase
        fun between(column: String, lo: Long?, hi: Long?): Pair<String, Array<String>> =
            listOfNotNull(lo?.let { "$column >= ?" to it }, hi?.let { "$column < ?" to it }).let { bounds ->
                (if (bounds.isEmpty()) "1" else bounds.joinToString(" AND ") { it.first }) to bounds.map { it.second.toString() }.toTypedArray()
            }
        val lo = from?.toEpochMilli()
        val hi = until?.toEpochMilli()
        val (inside, insideArgs) = between("ts", lo, hi)
        // A night shorter than a day: one that started up to a day before the cut reaches into it.
        val (nights, nightArgs) = between("start", lo?.minus(86_400_000L), hi)
        val (started, startedArgs) = between("start", lo, hi)
        val (read, readArgs) = between("read_at", lo, hi)
        val (days, dayArgs) = between("day", from?.let { zones.dateOf(it).toEpochDay() }, until?.let { zones.dateOf(it).toEpochDay() })
        val samples = ArrayList<Sample>()
        db.rawQuery("SELECT metric, ts, value FROM samples WHERE $inside ORDER BY ts", insideArgs).use { c ->
            while (c.moveToNext()) {
                val name = SERVER_METRICS[c.getString(0)] ?: continue
                samples += Sample(Instant.ofEpochMilli(c.getLong(1)), name, c.getDouble(2))
            }
        }
        val sessions = ArrayList<SleepSession>()
        db.rawQuery("SELECT is_nap, avg_hr, score, rem_min, light_min, deep_min, wake_min, stages, start, end_ts, source FROM sleep_sessions WHERE $nights ORDER BY start", nightArgs).use { c ->
            while (c.moveToNext()) {
                val raw = JSONArray(c.getString(7))
                val stages = List(raw.length()) { i -> raw.getJSONArray(i).let { Stage(it.getLong(0), it.getLong(1), it.getInt(2)) } }
                // The strap's own nights are their stages' span; a restored night carries its own end.
                val start = if (!c.isNull(9)) Instant.ofEpochMilli(c.getLong(8)) else stages.firstOrNull()?.let { Instant.ofEpochMilli(it.startMs) } ?: continue
                val end = if (!c.isNull(9)) Instant.ofEpochMilli(c.getLong(9)) else Instant.ofEpochMilli(stages.last().endMs)
                sessions += SleepSession(
                    start, end, if (c.getInt(0) == 1) SleepSession.NAP else SleepSession.MAIN,
                    c.intOrNull(2), c.intOrNull(1), c.intOrNull(3), c.intOrNull(4), c.intOrNull(5), c.intOrNull(6), stages, c.getString(10),
                )
            }
        }
        // The server keeps one session per start; a later one with the same start replaces it.
        val byStart = sessions.associateBy { it.start }.values
        val workouts = ArrayList<Workout>()
        db.rawQuery("SELECT start, sport, duration_s, calories, avg_hr, max_hr, min_hr FROM workouts WHERE $started ORDER BY start", startedArgs).use { c ->
            while (c.moveToNext()) {
                workouts += Workout(Instant.ofEpochMilli(c.getLong(0)), c.getInt(1), c.getInt(2), c.intOrNull(3), null, c.intOrNull(4), c.intOrNull(5), c.intOrNull(6))
            }
        }
        // The server's day counters as restored, then this phone's own readings over them by the
        // server's rule: a strap reading replaces one read no later, or with no read time, or from the cloud.
        val totals = LinkedHashMap<LocalDate, DeviceDailyTotal>()
        db.rawQuery("SELECT day, steps, distance_m, calories, source, read_at, reported_at FROM restored_totals WHERE $days ORDER BY day", dayArgs).use { c ->
            while (c.moveToNext()) {
                val day = LocalDate.ofEpochDay(c.getLong(0))
                totals[day] = DeviceDailyTotal(day, c.intOrNull(1), c.doubleOrNull(2), c.doubleOrNull(3), c.getString(4),
                    Instant.ofEpochMilli(c.getLong(6)), if (c.isNull(5)) null else Instant.ofEpochMilli(c.getLong(5)))
            }
        }
        db.rawQuery("SELECT read_at, steps, distance_m, calories FROM daily_totals WHERE $read ORDER BY read_at", readArgs).use { c ->
            while (c.moveToNext()) {
                val read = Instant.ofEpochMilli(c.getLong(0))
                val day = zones.dateOf(read)
                val held = totals[day]
                if (held != null && held.source != "zepp_cloud" && held.readAt != null && read < held.readAt) continue
                // Stored when the server received it; the phone only knows when it was read.
                totals[day] = DeviceDailyTotal(day, c.getLong(1).toInt(), c.getLong(2).toDouble(), c.getLong(3).toDouble(), "strap_0x16", read, read)
            }
        }
        return MemoryData(zones, samples, byStart, workouts, totals.values, profile, weights)
    }

    private class NightSpan(val start: Long, val endTs: Long?, val stagesEnd: Long?)

    /** The stored nights matching [where]: their start, own end (restored ones) and last stage's end. */
    private fun nights(db: SQLiteDatabase, where: String, vararg args: String): List<NightSpan> =
        db.rawQuery("SELECT start, end_ts, stages FROM sleep_sessions WHERE $where", args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val stages = JSONArray(c.getString(2))
                    val last = if (stages.length() == 0) null else stages.getJSONArray(stages.length() - 1).getLong(1)
                    add(NightSpan(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), last))
                }
            }
        }

    private fun android.database.Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private fun android.database.Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)

    // ── restore from the server (D34 S5) ────────────────────────────────────

    /** True when the strap has never delivered anything here: a restore cannot clash with own data. */
    fun isEmpty(): Boolean = listOf("samples", "sleep_sessions", "workouts", "daily_totals", "restored_totals").all { t ->
        readableDatabase.rawQuery("SELECT 1 FROM $t LIMIT 1", null).use { !it.moveToFirst() }
    }

    /**
     * Restored samples, under this phone's names, already on the server (so never sent back).
     * [replace] false keeps a reading the strap gave this phone itself (a backfill, not a restore).
     */
    fun restoreSamples(rows: List<Triple<String, Long, Double>>, replace: Boolean = true) = transaction { db ->
        val st = db.compileStatement("INSERT OR ${if (replace) "REPLACE" else "IGNORE"} INTO samples(metric, ts, value, pushed) VALUES(?,?,?,1)")
        for ((metric, ts, value) in rows) {
            st.bindString(1, metric); st.bindLong(2, ts); st.bindDouble(3, value); st.executeInsert(); st.clearBindings()
        }
    }

    /** A night from the server; with [replace] false, none that overlaps a night this phone holds. */
    fun restoreSleep(s: JSONObject, replace: Boolean = true) {
        if (!replace) {
            val overlaps = nights(readableDatabase, "start < ?", s.getLong("end_ts").toString())
                .any { (it.endTs ?: it.stagesEnd ?: it.start) > s.getLong("start_ts") }
            if (overlaps) return
        }
        writableDatabase.insertWithOnConflict("sleep_sessions", null, ContentValues().apply {
            put("start", s.getLong("start_ts")); put("record_ts", s.getLong("start_ts")); put("end_ts", s.getLong("end_ts"))
            put("is_nap", if (s.getString("kind") == "nap") 1 else 0)
            for (k in listOf("avg_hr", "score", "rem_min", "light_min", "deep_min", "wake_min")) if (!s.isNull(k)) put(k, s.getInt(k))
            put("stages", (s.optJSONArray("stages") ?: JSONArray()).toString()); put("source", s.getString("source")); put("pushed", 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun restoreWorkout(w: JSONObject, replace: Boolean = true) {
        writableDatabase.insertWithOnConflict("workouts", null, ContentValues().apply {
            put("start", w.getLong("start_ts")); put("sport", w.optInt("sport")); put("duration_s", w.optInt("duration_s"))
            for (k in listOf("calories", "avg_hr", "max_hr", "min_hr")) if (!w.isNull(k)) put(k, w.getInt(k))
            put("pushed", 1)
        }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun restoreTotal(t: JSONObject) {
        writableDatabase.insertWithOnConflict("restored_totals", null, ContentValues().apply {
            put("day", LocalDate.parse(t.getString("day")).toEpochDay()); put("source", t.getString("source")); put("reported_at", t.getLong("reported_at"))
            if (!t.isNull("steps")) put("steps", t.getInt("steps"))
            if (!t.isNull("distance_m")) put("distance_m", t.getDouble("distance_m"))
            if (!t.isNull("calories")) put("calories", t.getDouble("calories"))
            if (!t.isNull("read_at")) put("read_at", t.getLong("read_at"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Forgets every derived row and illness flag: the next derive works the whole history out again. */
    fun clearDerived() = transaction { db ->
        db.delete("derived", null, null)
        db.delete("illness", null, null)
    }

    /** The first instant the store holds data for, if any. */
    fun firstData(): Instant? = readableDatabase.rawQuery(
        "SELECT MIN(t) FROM (SELECT MIN(ts) t FROM samples UNION ALL SELECT MIN(start) FROM sleep_sessions UNION ALL SELECT MIN(start) FROM workouts)", null,
    ).use { if (it.moveToFirst() && !it.isNull(0)) Instant.ofEpochMilli(it.getLong(0)) else null }

    /** Marks everything [unpushedRecords] returned as sent. Only called after the server accepted it. */
    fun markRecordsPushed() = transaction { db ->
        for (table in listOf("sleep_sessions", "workouts", "daily_totals")) db.execSQL("UPDATE $table SET pushed = 1 WHERE pushed = 0")
    }

    fun unpushedCounts(): Map<String, Int> = listOf("samples", "sleep_sessions", "workouts", "daily_totals").associateWith { t ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t WHERE pushed = 0", null).use { it.moveToFirst(); it.getInt(0) }
    }

    /** Runs [block] in one transaction: a re-derive writes hundreds of rows. */
    fun <T> batch(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    fun metaValue(key: String): String? = meta(readableDatabase, key)

    fun setMetaValue(key: String, value: String) = setMeta(writableDatabase, key, value)

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The newest battery reading, from a sync or a strap job. */
    fun battery(): Battery? = meta(readableDatabase, BATTERY)?.split('@')?.let { (pct, at) -> Battery(pct.toInt(), Instant.ofEpochMilli(at.toLong())) }

    /** One counter reading outside a sync (the last live one), queued for the server like any other. */
    fun saveDailyTotals(t: strap.protocol.model.DailyTotals) = transaction { db ->
        db.insertWithOnConflict("daily_totals", null, ContentValues().apply {
            put("read_at", t.readAt.toEpochMilli()); put("steps", t.steps)
            put("distance_m", t.distanceM); put("calories", t.calories)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        markUnderived(db, t.readAt)
    }

    fun saveBattery(battery: Battery) = setMeta(writableDatabase, BATTERY, "${battery.percent}@${battery.at.toEpochMilli()}")

    data class Battery(val percent: Int, val at: Instant)

    /** When the last sync finished, and its failure (null = complete), or null before the first. */
    fun lastSync(): Pair<Instant, String?>? = readableDatabase
        .rawQuery("SELECT at, summary FROM sync_log ORDER BY id DESC LIMIT 1", null)
        .use { c ->
            if (!c.moveToFirst()) return null
            val failure = JSONObject(c.getString(1)).opt("failure")
            Instant.ofEpochMilli(c.getLong(0)) to (failure as? String)
        }

    /** When the last sync that fetched everything finished; a failed one never counts (D30). */
    /** When this store's first sync ran: data from then on reached the server through this phone. */
    fun firstSync(): Instant? = readableDatabase.rawQuery("SELECT MIN(at) FROM sync_log", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) Instant.ofEpochMilli(it.getLong(0)) else null }

    /** The strap's battery as each sync since [since] read it, oldest first. */
    fun batteryHistory(since: Instant): List<Battery> = readableDatabase
        .rawQuery("SELECT at, summary FROM sync_log WHERE at >= ? ORDER BY at", arrayOf(since.toEpochMilli().toString()))
        .use { c ->
            buildList {
                while (c.moveToNext()) {
                    val s = JSONObject(c.getString(1))
                    if (!s.isNull("battery")) add(Battery(s.getInt("battery"), Instant.ofEpochMilli(c.getLong(0))))
                }
            }
        }

    fun lastCompleteSync(): Instant? = readableDatabase
        .rawQuery("SELECT at, summary FROM sync_log ORDER BY id DESC LIMIT 50", null)
        .use { c ->
            while (c.moveToNext()) {
                if (JSONObject(c.getString(1)).isNull("failure")) return Instant.ofEpochMilli(c.getLong(0))
            }
            null
        }

    fun lastSummary(): JSONObject? = readableDatabase
        .rawQuery("SELECT summary FROM sync_log ORDER BY id DESC LIMIT 1", null)
        .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    /**
     * How densely a stream is sampled over the last [hours]: readings and distinct minutes.
     * This is the R1 measurement — what resolution the strap actually delivers.
     */
    fun density(metric: Metric, now: Instant, hours: Int = 24): Density {
        val from = now.minusSeconds(hours * 3600L).toEpochMilli()
        return readableDatabase.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT ts / 60000) FROM samples WHERE metric = ? AND ts >= ?",
            arrayOf(metric.wireName, from.toString()),
        ).use { c -> c.moveToFirst(); Density(metric, hours, c.getInt(0), c.getInt(1)) }
    }

    data class Density(val metric: Metric, val hours: Int, val readings: Int, val minutes: Int) {
        val coverage: Double get() = minutes / (hours * 60.0)
    }

    private fun maxInstant(db: SQLiteDatabase, sql: String): Instant? =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) Instant.ofEpochMilli(c.getLong(0)) else null }

    private fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Keyed by when the sleep STARTED, not by the record's own timestamp: that one is when
     * the strap last wrote the record (seen as late as the same evening), so a record the
     * strap rewrites — e.g. to append a nap — would otherwise store its night twice.
     */
    private fun strap.protocol.model.SleepSession.values() = ContentValues().apply {
        put("start", (stages.firstOrNull()?.start ?: sessionStart).toEpochMilli())
        put("record_ts", sessionStart.toEpochMilli()); put("is_nap", if (isNap) 1 else 0)
        put("sleep_start_min", sleepStartMin); put("sleep_end_min", sleepEndMin)
        put("avg_hr", avgHr); put("score", score)
        put("rem_min", remMin); put("light_min", lightMin); put("deep_min", deepMin); put("wake_min", wakeMin)
        put("stages", JSONArray(stages.map { JSONArray(listOf(it.start.toEpochMilli(), it.end.toEpochMilli(), it.stage.code)) }).toString())
        put("pushed", 0)
    }

    private fun strap.protocol.model.Workout.values() = ContentValues().apply {
        put("start", start.toEpochMilli()); put("sport", sportType); put("duration_s", durationSec)
        put("calories", calories); put("avg_hr", avgHr); put("max_hr", maxHr); put("min_hr", minHr)
        put("pushed", 0)
    }

    companion object {
        private const val STRESS_BACKFILL = "stress_backfill_done"
        private const val NAP_BACKFILL = "nap_backfill_done"
        private const val BATTERY = "battery" // "percent@epochMillis"
        private const val UNDERIVED = "underived_from" // epoch millis

        /** The phone's metric names → the server's (server/src/strap_server/ingest/upsert.py METRIC_NAMES). */
        val SERVER_METRICS = mapOf(
            "hr" to "hr", "hrv" to "hrv", "spo2" to "spo2", "spo2_sleep" to "spo2", "temperature_c" to "skin_temp_c",
            "respiratory_rate" to "respiratory_rate", "stress" to "stress", "steps" to "steps_per_minute",
            "resting_hr" to "resting_hr_device", "max_hr" to "max_hr_device", "manual_hr" to "manual_hr",
            "stress_manual" to "stress_manual", "hrv_sdnn" to "hrv_sdnn", "pai_daily" to "pai_daily_device",
            "pai_total" to "pai_total_device", "odi_events" to "odi_events_device", "odi_score" to "odi_score_device",
            "odi_valid_time" to "odi_valid_time_device", "odi_raw" to "odi_raw_device",
        )
        /**
         * Added in version 5 (D34 S5): what a restore from the server brings that the strap never
         * sent this phone. A night's own end and source (an imported night may have no stages), and
         * the server's day counters as it kept them, read time unknown included.
         */
        val RESTORE_SCHEMA = listOf(
            "ALTER TABLE sleep_sessions ADD COLUMN end_ts INTEGER",
            "ALTER TABLE sleep_sessions ADD COLUMN source TEXT NOT NULL DEFAULT 'strap_ble'",
            "CREATE TABLE restored_totals(day INTEGER PRIMARY KEY, steps INTEGER, distance_m REAL, calories REAL, source TEXT NOT NULL, read_at INTEGER, reported_at INTEGER NOT NULL)",
        )

        /**
         * Added in version 4 (D34 S3): the journal the phone owns, weights included. [server_id] is
         * the server's id once uploaded; a deleted entry waits as [deleted] until the server knows.
         */
        val JOURNAL_SCHEMA = listOf(
            "CREATE TABLE journal(id TEXT PRIMARY KEY, kind TEXT NOT NULL, ts INTEGER NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL, " +
                "name TEXT, server_id TEXT, pushed INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX journal_ts ON journal(ts)",
        )

        /** Added in version 3 (D34 S3). Illness deltas are REAL, as on the server. */
        val DERIVED_SCHEMA = listOf(
            "CREATE TABLE derived(day INTEGER NOT NULL, metric TEXT NOT NULL, value REAL NOT NULL, flags TEXT NOT NULL, PRIMARY KEY(day, metric)) WITHOUT ROWID",
            "CREATE INDEX derived_metric_day ON derived(metric, day)",
            "CREATE TABLE illness(day INTEGER PRIMARY KEY, severity TEXT NOT NULL, rr_delta_bpm REAL, temp_delta_c REAL, sustained INTEGER NOT NULL, note_ids TEXT NOT NULL)",
        )

        private val SCHEMA = listOf(
            "CREATE TABLE samples(metric TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, " +
                "pushed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(metric, ts)) WITHOUT ROWID",
            "CREATE INDEX samples_unpushed ON samples(pushed) WHERE pushed = 0",
            "CREATE TABLE sleep_sessions(start INTEGER PRIMARY KEY, record_ts INTEGER NOT NULL, is_nap INTEGER NOT NULL, sleep_start_min INTEGER, " +
                "sleep_end_min INTEGER, avg_hr INTEGER, score INTEGER, rem_min INTEGER, light_min INTEGER, deep_min INTEGER, " +
                "wake_min INTEGER, stages TEXT NOT NULL, pushed INTEGER NOT NULL DEFAULT 0, " +
                "end_ts INTEGER, source TEXT NOT NULL DEFAULT 'strap_ble')",
            "CREATE TABLE workouts(start INTEGER PRIMARY KEY, sport INTEGER, duration_s INTEGER, calories INTEGER, " +
                "avg_hr INTEGER, max_hr INTEGER, min_hr INTEGER, pushed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE daily_totals(read_at INTEGER PRIMARY KEY, steps INTEGER, distance_m INTEGER, calories INTEGER, " +
                "pushed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sync_log(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, summary TEXT NOT NULL)",
        ) + DERIVED_SCHEMA + JOURNAL_SCHEMA + RESTORE_SCHEMA.drop(2)

    }
}

/** A sync's diagnostics as JSON — what arrived, per fetch type, including announced-vs-received per round. */
object SyncSummary {
    fun of(r: SyncResult, battery: Int?): JSONObject = JSONObject().apply {
        put("failure", r.failure?.toString() ?: JSONObject.NULL)
        put("fetchChannel", r.fetchChannelPresent)
        put("battery", battery ?: JSONObject.NULL)
        put("samples", JSONObject(r.samples.groupingBy { it.metric.wireName }.eachCount()))
        put("sleepSessions", r.sleepSessions.size)
        put("naps", r.sleepSessions.count { it.isNap })
        put("workouts", r.workouts.size)
        put("dailySteps", r.dailyTotals?.steps ?: JSONObject.NULL)
        put("fetches", JSONArray(r.fetches.map { f ->
            JSONObject().apply {
                put("code", "0x%02x".format(f.code)); put("samples", f.samples); put("bytes", f.rawBytes)
                put("failure", f.failure?.toString() ?: JSONObject.NULL)
                put("rounds", JSONArray(f.roundStats.map { "${it.announced}/${it.packets}p/${it.bytes}b${if (it.counterGap) "/gap" else ""}${if (it.short) "/short" else ""}" }))
            }
        }))
    }
}
