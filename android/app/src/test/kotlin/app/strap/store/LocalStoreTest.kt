package app.strap.store

import android.database.sqlite.SQLiteDatabase
import app.strap.Phone
import app.strap.PhoneTest
import app.strap.ZONE
import app.strap.at
import app.strap.day
import app.strap.night
import app.strap.sync
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import strap.protocol.model.DailyTotals
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import strap.protocol.model.Workout
import strap.science.Derived
import strap.science.IllnessFlag
import strap.science.SleepSession
import strap.science.Zones
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalStoreTest : PhoneTest() {
    private val zones = Zones(ZONE)

    @Test
    fun `the science sees samples under the server's names, unknown metrics dropped`() {
        val store = Phone().store
        val t = at("2026-09-01", "08:00")
        store.save(sync(t, samples = listOf(
            Sample(t, Metric.HR, 61.0), Sample(t, Metric.STEPS, 40.0), Sample(t, Metric.TEMPERATURE_C, 33.5),
            Sample(t, Metric.SLEEP_SESSION, 1.0), // a marker, never stored
        )), batteryPercent = 80)
        store.restoreSamples(listOf(Triple("hr", t.plusSeconds(60).toEpochMilli(), 62.0), Triple("no_such_metric", t.toEpochMilli(), 1.0)))

        val data = store.scienceData(zones, null, emptyList())
        val until = t.plusSeconds(3600)
        assertEquals(listOf(61.0, 62.0), data.samples("hr", t, until).map { it.value })
        assertEquals(listOf(40.0), data.samples("steps_per_minute", t, until).map { it.value })
        assertEquals(listOf(33.5), data.samples("skin_temp_c", t, until).map { it.value })
        assertTrue(data.samples("steps", t, until).isEmpty())
    }

    @Test
    fun `a strap night spans its stages, a restored night keeps its own end and source`() {
        val store = Phone().store
        val bed = at("2026-09-01", "23:00")
        val wake = at("2026-09-02", "07:00")
        store.save(sync(wake, sleep = listOf(night(bed, wake))), null)
        val napStart = at("2026-09-02", "14:00")
        store.restoreSleep(JSONObject().put("start_ts", napStart.toEpochMilli()).put("end_ts", napStart.plusSeconds(1800).toEpochMilli())
            .put("kind", "nap").put("source", "zepp_cloud").put("stages", JSONArray()).put("score", JSONObject.NULL))

        val sessions = store.scienceData(zones, null, emptyList()).sleepSessions(bed.minusSeconds(3600), wake.plusSeconds(86_400))
        assertEquals(2, sessions.size)
        val (main, nap) = sessions
        assertEquals(bed to wake, main.start to main.end)
        assertEquals(SleepSession.MAIN, main.kind)
        assertEquals(80, main.score)
        assertEquals(napStart.plusSeconds(1800), nap.end)
        assertEquals(SleepSession.NAP to "zepp_cloud", nap.kind to nap.source)
        assertNull(nap.score)
    }

    @Test
    fun `a rewritten night replaces the version it overlaps`() {
        val store = Phone().store
        val wake = at("2026-09-02", "07:00")
        store.save(sync(wake, sleep = listOf(night(at("2026-09-01", "23:00"), wake))), null)
        store.save(sync(wake, sleep = listOf(night(at("2026-09-01", "23:01"), wake))), null)
        val sessions = store.scienceData(zones, null, emptyList()).sleepSessions(at("2026-09-01"), at("2026-09-03"))
        assertEquals(listOf(at("2026-09-01", "23:01")), sessions.map { it.start })
    }

    @Test
    fun `a backfilled night that overlaps the phone's own is left out`() {
        val store = Phone().store
        val wake = at("2026-09-02", "07:00")
        store.save(sync(wake, sleep = listOf(night(at("2026-09-01", "23:00"), wake))), null)
        store.restoreSleep(JSONObject().put("start_ts", at("2026-09-01", "22:30").toEpochMilli()).put("end_ts", wake.toEpochMilli())
            .put("kind", "main").put("source", "strap_ble").put("stages", JSONArray()), replace = false)
        val sessions = store.scienceData(zones, null, emptyList()).sleepSessions(at("2026-09-01"), at("2026-09-03"))
        assertEquals(listOf(at("2026-09-01", "23:00")), sessions.map { it.start })
    }

    @Test
    fun `workouts and the day counters by the server's rule`() {
        val store = Phone().store
        val run = at("2026-09-01", "18:00")
        store.save(sync(run.plusSeconds(3600), workouts = listOf(Workout(run, 1, 1800, 300, 150, 170, 90))), null)
        // The server's counters for two days; the phone read later on the first, earlier on the second.
        store.restoreTotal(JSONObject().put("day", "2026-09-01").put("steps", 5000).put("distance_m", 3500.0).put("calories", JSONObject.NULL)
            .put("source", "strap_0x16").put("read_at", at("2026-09-01", "20:00").toEpochMilli()).put("reported_at", at("2026-09-01", "20:00").toEpochMilli()))
        store.restoreTotal(JSONObject().put("day", "2026-09-02").put("steps", 9000).put("distance_m", JSONObject.NULL).put("calories", JSONObject.NULL)
            .put("source", "strap_0x16").put("read_at", at("2026-09-02", "22:00").toEpochMilli()).put("reported_at", at("2026-09-02", "22:00").toEpochMilli()))
        store.save(sync(at("2026-09-01", "23:00"), totals = DailyTotals(7000, 5000, 400, at("2026-09-01", "23:00"))), null)
        store.save(sync(at("2026-09-02", "21:00"), totals = DailyTotals(8000, 6000, 450, at("2026-09-02", "21:00"))), null)

        val data = store.scienceData(zones, null, emptyList())
        val w = data.workouts(at("2026-09-01", "00:00"), at("2026-09-02", "00:00")).single()
        assertEquals(listOf(run.toEpochMilli(), 1, 1800, 300, 150, 170, 90).map { it.toLong() },
            listOf(w.start.toEpochMilli(), w.sport, w.durationS, w.calories, w.avgHr, w.maxHr, w.minHr).map { (it as Number).toLong() })
        assertEquals(7000 to "strap_0x16", data.deviceTotal(day("2026-09-01"))!!.let { it.steps to it.source })
        assertEquals(9000, data.deviceTotal(day("2026-09-02"))!!.steps)
    }

    @Test
    fun `derived rows and illness flags round-trip`() {
        val store = Phone().store
        val flags = buildJsonObject { put("partial", JsonPrimitive(true)) }
        store.derived.put(Derived(day("2026-09-01"), "rhr_daily", 52.0))
        store.derived.put(Derived(day("2026-09-02"), "rhr_daily", 54.5, flags))
        store.derived.put(Derived(day("2026-09-02"), "steps_total", 8000.0))
        assertEquals(listOf(52.0, 54.5), store.derived.range("rhr_daily", day("2026-09-01"), day("2026-09-02")).map { it.value })
        assertEquals(flags, store.derived.get(day("2026-09-02"), "rhr_daily")!!.flags)
        assertEquals(day("2026-09-02"), store.derivedThrough())

        store.derived.clear(day("2026-09-02"))
        assertNull(store.derived.get(day("2026-09-02"), "steps_total"))
        assertEquals(day("2026-09-01"), store.derivedThrough())

        val flag = IllnessFlag(day("2026-09-03"), "moderate", 2.5f, null, sustained = true, noteIds = listOf("a", "b"))
        store.derived.putIllness(flag)
        assertEquals(flag, store.derived.illness(day("2026-09-03")))
        assertEquals(flag, store.derived.latestIllness(day("2026-09-01"), day("2026-09-05")))
        assertTrue(store.derived.clearIllness(day("2026-09-03")))
        assertNull(store.derived.illness(day("2026-09-03")))
    }

    @Test
    fun `first data and first sync`() {
        val store = Phone().store
        assertNull(store.firstData())
        assertNull(store.firstSync())
        store.save(sync(at("2026-09-05"), samples = listOf(Sample(at("2026-09-04"), Metric.HR, 60.0))), null)
        store.restoreSleep(JSONObject().put("start_ts", at("2026-09-02", "23:00").toEpochMilli()).put("end_ts", at("2026-09-03", "07:00").toEpochMilli())
            .put("kind", "main").put("source", "strap_ble").put("stages", JSONArray()))
        store.save(sync(at("2026-09-06")), null)
        assertEquals(at("2026-09-02", "23:00"), store.firstData())
        assertEquals(at("2026-09-05"), store.firstSync())
    }

    @Test
    fun `an upgrade from version 2 keeps the phone's data and adds the later tables`() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath("strap.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE samples(metric TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, pushed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(metric, ts)) WITHOUT ROWID",
                "CREATE TABLE sleep_sessions(start INTEGER PRIMARY KEY, record_ts INTEGER NOT NULL, is_nap INTEGER NOT NULL, sleep_start_min INTEGER, " +
                    "sleep_end_min INTEGER, avg_hr INTEGER, score INTEGER, rem_min INTEGER, light_min INTEGER, deep_min INTEGER, " +
                    "wake_min INTEGER, stages TEXT NOT NULL, pushed INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE workouts(start INTEGER PRIMARY KEY, sport INTEGER, duration_s INTEGER, calories INTEGER, avg_hr INTEGER, max_hr INTEGER, min_hr INTEGER, pushed INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE daily_totals(read_at INTEGER PRIMARY KEY, steps INTEGER, distance_m INTEGER, calories INTEGER, pushed INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE sync_log(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, summary TEXT NOT NULL)",
                "INSERT INTO samples(metric, ts, value) VALUES('hr', ${at("2026-09-01").toEpochMilli()}, 58)",
                "INSERT INTO sleep_sessions(start, record_ts, is_nap, stages) VALUES(${at("2026-09-01", "23:00").toEpochMilli()}, 0, 0, " +
                    "'[[${at("2026-09-01", "23:00").toEpochMilli()},${at("2026-09-02", "07:00").toEpochMilli()},4]]')",
                "INSERT INTO meta(key, value) VALUES('kept', 'yes')",
                "PRAGMA user_version = 2",
            ).forEach(db::execSQL)
        }

        val store = LocalStore(context)
        assertEquals("yes", store.metaValue("kept"))
        val data = store.scienceData(zones, null, emptyList())
        assertEquals(listOf(58.0), data.samples("hr", at("2026-09-01", "00:00"), at("2026-09-02", "00:00")).map { it.value })
        val night = data.sleepSessions(at("2026-09-01"), at("2026-09-03")).single()
        assertEquals("strap_ble" to at("2026-09-02", "07:00"), night.source to night.end)
        // The tables of versions 3–5 exist and work.
        store.derived.put(Derived(day("2026-09-02"), "rhr_daily", 50.0))
        store.putJournal(LocalStore.JournalRow("j", "caffeine", at("2026-09-02"), 80.0, "mg", null, null, pushed = false, deleted = false))
        store.restoreTotal(JSONObject().put("day", "2026-09-02").put("steps", 1).put("distance_m", JSONObject.NULL).put("calories", JSONObject.NULL)
            .put("source", "strap_0x16").put("read_at", JSONObject.NULL).put("reported_at", 0))
        assertEquals(1, store.journalRows().size)
        assertEquals(50.0, store.derived.get(day("2026-09-02"), "rhr_daily")!!.value)
        store.close()
    }
}
