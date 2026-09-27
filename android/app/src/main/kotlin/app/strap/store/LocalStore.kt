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
import java.time.Instant

/**
 * The phone's copy of what the strap delivered, plus an outbox flag per row for the push
 * to the server (P2). The server is canonical; this is a cache, a watermark source and an
 * outbox. Timestamps are epoch milliseconds, UTC.
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context, "strap.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    /** Pre-release: the phone copy is a cache that one sync refills, so an upgrade rebuilds it. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("samples", "sleep_sessions", "workouts", "daily_totals", "meta", "sync_log").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

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
            result.sleepSessions.forEach { db.insertWithOnConflict("sleep_sessions", null, it.values(), SQLiteDatabase.CONFLICT_REPLACE) }
            result.workouts.forEach { db.insertWithOnConflict("workouts", null, it.values(), SQLiteDatabase.CONFLICT_REPLACE) }
            result.dailyTotals?.let {
                db.insertWithOnConflict("daily_totals", null, ContentValues().apply {
                    put("read_at", it.readAt.toEpochMilli()); put("steps", it.steps)
                    put("distance_m", it.distanceM); put("calories", it.calories)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
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

    /** Marks everything [unpushedRecords] returned as sent. Only called after the server accepted it. */
    fun markRecordsPushed() = transaction { db ->
        for (table in listOf("sleep_sessions", "workouts", "daily_totals")) db.execSQL("UPDATE $table SET pushed = 1 WHERE pushed = 0")
    }

    fun unpushedCounts(): Map<String, Int> = listOf("samples", "sleep_sessions", "workouts", "daily_totals").associateWith { t ->
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $t WHERE pushed = 0", null).use { it.moveToFirst(); it.getInt(0) }
    }

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

    fun saveBattery(battery: Battery) = setMeta(writableDatabase, BATTERY, "${battery.percent}@${battery.at.toEpochMilli()}")

    data class Battery(val percent: Int, val at: Instant)

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

    private companion object {
        const val STRESS_BACKFILL = "stress_backfill_done"
        const val NAP_BACKFILL = "nap_backfill_done"
        const val BATTERY = "battery" // "percent@epochMillis"
        val SCHEMA = listOf(
            "CREATE TABLE samples(metric TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, " +
                "pushed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(metric, ts)) WITHOUT ROWID",
            "CREATE INDEX samples_unpushed ON samples(pushed) WHERE pushed = 0",
            "CREATE TABLE sleep_sessions(start INTEGER PRIMARY KEY, record_ts INTEGER NOT NULL, is_nap INTEGER NOT NULL, sleep_start_min INTEGER, " +
                "sleep_end_min INTEGER, avg_hr INTEGER, score INTEGER, rem_min INTEGER, light_min INTEGER, deep_min INTEGER, " +
                "wake_min INTEGER, stages TEXT NOT NULL, pushed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE workouts(start INTEGER PRIMARY KEY, sport INTEGER, duration_s INTEGER, calories INTEGER, " +
                "avg_hr INTEGER, max_hr INTEGER, min_hr INTEGER, pushed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE daily_totals(read_at INTEGER PRIMARY KEY, steps INTEGER, distance_m INTEGER, calories INTEGER, " +
                "pushed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sync_log(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, summary TEXT NOT NULL)",
        )
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
