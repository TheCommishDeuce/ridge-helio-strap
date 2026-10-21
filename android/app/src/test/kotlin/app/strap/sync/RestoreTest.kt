package app.strap.sync

import app.strap.FakeServer
import app.strap.Phone
import app.strap.PhoneTest
import app.strap.ZONE
import app.strap.at
import app.strap.day
import app.strap.dayOfSamples
import app.strap.sync
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import strap.science.Derived
import strap.science.Zones
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreTest : PhoneTest() {
    private val zones = Zones(ZONE)

    /** A server holding two days of heart rate and steps (server names), a night, a workout and a day counter. */
    private fun backup() = FakeServer(
        records = JSONObject()
            .put("profile", JSONObject().put("height_cm", 170.0).put("sex", "f").put("dob", "1992-03-04").put("srpa", 2))
            .put("timezone", ZONE).put("zone_changes", JSONArray()).put("weights", JSONArray()).put("journal", JSONArray())
            .put("sleep", JSONArray().put(JSONObject().put("start_ts", at("2026-09-01", "23:00").toEpochMilli()).put("end_ts", at("2026-09-02", "07:00").toEpochMilli())
                .put("kind", "main").put("source", "strap_ble").put("score", 81).put("avg_hr", 52)
                .put("stages", JSONArray().put(JSONArray().put(at("2026-09-01", "23:00").toEpochMilli()).put(at("2026-09-02", "07:00").toEpochMilli()).put(4)))))
            .put("workouts", JSONArray().put(JSONObject().put("start_ts", at("2026-09-02", "18:00").toEpochMilli()).put("sport", 1).put("duration_s", 1200)
                .put("calories", 200).put("avg_hr", 140).put("max_hr", 160).put("min_hr", 100)))
            .put("daily_totals", JSONArray().put(JSONObject().put("day", "2026-09-02").put("steps", 6000).put("distance_m", 4000.0).put("calories", 350.0)
                .put("source", "strap_0x16").put("read_at", at("2026-09-02", "21:00").toEpochMilli()).put("reported_at", at("2026-09-02", "21:00").toEpochMilli()))),
        samples = (dayOfSamples("2026-09-01") + dayOfSamples("2026-09-02")).map { s ->
            Triple(s.at.toEpochMilli(), if (s.metric == Metric.STEPS) "steps_per_minute" else s.metric.wireName, s.value)
        },
        pageSize = 100,
    )

    @Test
    fun `the offer says how much the server holds`() = runTest {
        val phone = Phone(backup())
        assertEquals(576L to at("2026-09-01", "23:00"), phone.restore.offer())
        phone.server!!.down = true
        assertNull(phone.restore.offer())
    }

    @Test
    fun `a restore brings everything, page by page, and works every day out`() = runTest {
        val phone = Phone(backup())
        val progress = mutableListOf<String>()
        assertNull(phone.restore.run { progress += it })
        assertTrue(progress.count { it.startsWith("Reading samples") } >= 6, "576 samples, 100 a page")

        val data = phone.store.scienceData(zones, null, emptyList())
        assertEquals(144, data.samples("steps_per_minute", at("2026-09-01", "00:00"), at("2026-09-02", "00:00")).size)
        assertEquals(81, data.sleepSessions(at("2026-09-01"), at("2026-09-03")).single().score)
        assertEquals(200, data.workouts(at("2026-09-02", "00:00"), at("2026-09-03", "00:00")).single().calories)
        assertEquals(6000, data.deviceTotal(day("2026-09-02"))!!.steps)
        // Already on the server: nothing goes back.
        assertTrue(phone.store.unpushedCounts().values.all { it == 0 })
        assertEquals("f", phone.owner.profile()!!.sex)
        assertNotNull(phone.store.metaValue(Restore.RESTORED))
        assertEquals(day("2026-09-02"), phone.store.derivedThrough())
        assertNotNull(phone.store.derived.get(day("2026-09-02"), "rhr_daily") ?: phone.store.derived.get(day("2026-09-02"), "steps_total"))
    }

    @Test
    fun `a stopped restore says why and can run again`() = runTest {
        val phone = Phone(backup().apply { down = true })
        assertEquals("Could not reach the server.", phone.restore.run {})
        assertNull(phone.store.metaValue(Restore.RESTORED))
        phone.server!!.down = false
        assertNull(phone.restore.run {})
        assertNull(phone.restore.run {})
        assertEquals(144, phone.store.scienceData(zones, null, emptyList()).samples("hr", at("2026-09-01", "00:00"), at("2026-09-02", "00:00")).size)
    }

    @Test
    fun `a backfill keeps the phone's own readings and nights, and forgets the derived rows`() = runTest {
        val server = backup().apply { samples = samples + Triple(at("2026-09-02", "12:00").toEpochMilli(), "hr", 99.0) }
        val phone = Phone(server)
        val noon = at("2026-09-02", "12:00")
        phone.store.save(sync(at("2026-09-02", "22:00"), samples = listOf(Sample(noon, Metric.HR, 64.0))), 70)
        phone.store.restoreSleep(JSONObject().put("start_ts", at("2026-09-01", "23:30").toEpochMilli()).put("end_ts", at("2026-09-02", "06:30").toEpochMilli())
            .put("kind", "main").put("source", "phone_own").put("stages", JSONArray()))
        phone.store.derived.put(Derived(day("2026-09-02"), "rhr_daily", 50.0))

        assertNull(phone.restore.backfill())
        val data = phone.store.scienceData(zones, null, emptyList())
        assertEquals(listOf(64.0), data.samples("hr", noon, noon.plusSeconds(1)).map { it.value })
        assertEquals(listOf("phone_own"), data.sleepSessions(at("2026-09-01"), at("2026-09-03")).map { it.source })
        assertEquals(144, data.samples("hr", at("2026-09-01", "00:00"), at("2026-09-02", "00:00")).size)
        assertNull(phone.store.derivedThrough())
        assertNotNull(phone.store.metaValue(Restore.BACKFILLED))

        // Once: a later call reads nothing.
        server.down = true
        assertNull(phone.restore.backfill())
        assertEquals("Could not reach the server.", phone.restore.backfill(again = true))
    }

    @Test
    fun `without a server there is nothing to backfill or restore`() = runTest {
        val phone = Phone(null)
        assertNull(phone.restore.backfill())
        assertNull(phone.restore.offer())
        assertEquals("No server is connected.", phone.restore.run {})
    }
}
