package strap.science

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/test_summary.py, test_series.py and test_travel.py.
class SummaryAndSeriesTest {
    private val input = Golden.read("seed/input.json").jsonObject
    private val golden = Golden.read("seed/expected_daily.json").jsonArray.map { it.jsonObject }
    private val now = Instant.parse("2026-10-21T09:00:00Z")
    private val days = input.getValue("days").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }
    private val last = days.last()
    private fun golden(day: LocalDate, metric: String) =
        golden.first { it.getValue("day").jsonPrimitive.content == day.toString() && it.getValue("metric").jsonPrimitive.content == metric }.getValue("value").jsonPrimitive.double
    private fun seeded(extra: List<Sample> = emptyList()) = Science(Golden.data(input, extra = extra), MemoryDerived()).also { it.rederive() }
    private fun local(day: LocalDate, h: Int, m: Int = 0) = at(day, h, m)
    private fun JsonObject.o(k: String) = getValue(k).jsonObject
    private fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.double

    @Test
    fun `strain is 21 at the personal p95 and concave below, and the illness framing`() {
        assertEquals(21.0, strainFromLoad(100.0, 100.0))
        assertEquals(0.0, strainFromLoad(0.0, 100.0))
        assertEquals(12.5, strainFromLoad(50.0, 100.0))
        assertEquals(21.0, strainFromLoad(300.0, 100.0))
        assertNull(strainFromLoad(50.0, null))
        assertEquals(
            "Possible early signal — consider lighter activity today. Breathing rate +2.4 bpm vs your 14-day baseline; skin temperature +0.35°C — sustained across two nights, the Smarr 2020 / Quer 2021 pattern. Not a diagnosis.",
            illnessFraming(2.4, 0.35, true),
        )
        // A flag raised by skin temperature alone can carry a breathing rate under its baseline.
        assertEquals(
            "Possible early signal — consider lighter activity today. Breathing rate −0.2 bpm vs your 14-day baseline; skin temperature +1.51°C. Not a diagnosis.",
            illnessFraming(-0.2, 1.51, false),
        )
    }

    @Test
    fun `the cards carry the derived numbers`() {
        val out = seeded().daySummary(last, now).toJson()
        assertEquals(roundInt(golden(last, "recovery_score")).toDouble(), out.o("recovery").n("value"))
        assertEquals("evidence_weighted_personal_baseline", out.o("recovery").o("flags").getValue("method").jsonPrimitive.content)
        assertEquals(roundInt(golden(last, "steps_total")).toDouble(), out.o("steps").o("steps").n("value"))
        assertEquals(round(golden(last, "cardio_load"), 1), out.o("strain").n("cardio_load"))
        assertTrue(out.o("strain").n("value") in 0.0..21.0 && out.o("strain").n("value") > 0)
        assertEquals(golden(last, "sleep_health_score_4dim"), out.o("sleep").o("health").n("dimensions"))
        val session = out.o("sleep").getValue("sessions").jsonArray[0].jsonObject
        assertEquals("main" to 85, session.getValue("kind").jsonPrimitive.content to session.getValue("device_score").jsonPrimitive.int)
        assertEquals(480.0, out.o("sleep").n("need_min"))
        assertEquals(roundInt(golden(last, "rhr_daily")).toDouble(), out.o("heart").o("resting").n("value"))
        assertEquals(7, out.o("heart").o("resting").o("baseline").getValue("n").jsonPrimitive.int)
        assertEquals(round(golden(last, "vo2max_estimate"), 1), out.o("vo2max").n("value"))
        assertEquals(JsonNull, out["illness"])
    }

    @Test
    fun `a day without data is withheld by name, never null`() {
        val out = seeded().daySummary(LocalDate.of(2026, 6, 1), now).toJson()
        for (card in listOf(out.o("recovery"), out.o("strain"), out.o("steps").o("steps"), out.o("heart").o("resting"), out.o("heart").o("today"), out.o("vo2max"), out.o("charge"), out.o("pai"), out.o("sleep").o("breathing"))) {
            val w = card.o("withheld")
            assertTrue(w.getValue("reason").jsonPrimitive.content.isNotBlank() && w.getValue("message").jsonPrimitive.content.isNotBlank(), "$card")
        }
        assertEquals(JsonArray(emptyList()), out.o("sleep").getValue("sessions"))
        assertTrue("withheld" in out.o("sleep").o("health"))
    }

    @Test
    fun `usual steps by a clock time scale each day's total by its share so far`() {
        val s = seeded()
        val total = golden(days[0], "steps_total")
        assertEquals(Usual(roundInt(total * 1210 / 3105).toDouble(), 7, LocalTime.of(8, 11)), s.stepsUsualBy(last, LocalTime.of(8, 11)))
        assertEquals(roundInt(total).toDouble(), s.stepsUsualBy(last, LocalTime.NOON)!!.median)
        assertNull(s.stepsUsualBy(days[3], LocalTime.NOON))
    }

    @Test
    fun `each card that is not computed yet says so in its own words`() {
        val out = Science(Golden.data(input), MemoryDerived()).daySummary(LocalDate.of(2026, 6, 1), now).toJson()
        val message = { o: JsonObject -> o.o("withheld").getValue("message").jsonPrimitive.content }
        for (card in listOf(out.o("recovery"), out.o("strain"), out.o("steps").o("steps"), out.o("heart").o("resting"))) {
            assertEquals("Today's number has not been computed yet — sync the strap.", message(card))
        }
        assertTrue("sleep debt" !in out.toString().replace(out.o("sleep").o("debt").toString(), ""), "only the debt card may speak of sleep debt")
    }

    @Test
    fun `the usual mean compares the same hours of other days`() {
        val s = seeded()
        val walk = assertNotNull(s.usualMean(last, "hr", LocalTime.of(9, 0), HrValidity::valid))
        assertEquals(7 to LocalTime.of(9, 0), walk.n to walk.until)
        val out = s.daySummary(last, now).toJson()
        assertEquals(8, out.o("heart").o("today").o("usual").getValue("n").jsonPrimitive.int)
        assertEquals(JsonNull, out.o("heart").o("today").o("usual")["until"])
        assertTrue("usual_by_now" !in out.o("steps").o("steps"))
    }

    @Test
    fun `charge runs over the day`() {
        val out = seeded().daySummary(last, now).toJson()
        assertTrue("readiness" !in out.o("recovery"))
        val c = out.o("charge")
        assertTrue(c.n("low") <= c.n("value") && c.n("value") <= c.n("high") && c.n("high") <= 100 && c.n("low") >= 0)
        assertTrue(c.n("at_wake") >= c.n("value"))
        assertTrue(c.n("recharged") > 0 && c.o("drained").n("activity") > 0)
        assertEquals(json("stress" to 60, "personal" to false).toString(), c.o("threshold").toString())
        assertEquals(local(last, 0).toEpochMilli(), c.getValue("curve").jsonArray[0].jsonArray[0].jsonPrimitive.long)
        assertNotNull(out.o("strain")["typical_load"]?.takeUnless { it is JsonNull })
    }

    @Test
    fun `PAI is the day's latest reading with the week of daily points`() {
        val extra = days.takeLast(3).flatMapIndexed { k, d -> listOf(Sample(local(d, 23, 59), "pai_daily_device", 10.0 + k), Sample(local(d, 23, 59), "pai_total_device", 60.0 + k)) } +
            listOf(Sample(local(last, 14), "pai_daily_device", 4.0), Sample(local(last, 14), "pai_total_device", 55.0))
        val pai = seeded(extra).daySummary(last, now).toJson().o("pai")
        assertEquals(62.0 to 12.0, pai.n("value") to pai.n("today"))
        assertEquals(listOf(10, 11, 12), pai.getValue("week").jsonArray.map { it.jsonObject.getValue("points").jsonPrimitive.int })
    }

    @Test
    fun `breathing disturbances are drops per hour measured, against the usual`() {
        val unmeasured = days[0].minusDays(1)
        val extra = days.flatMapIndexed { k, d -> listOf(Sample(local(d, 0), "odi_events_device", 18.0 + k), Sample(local(d, 0), "odi_valid_time_device", 32400.0)) } +
            Sample(local(unmeasured, 0), "odi_valid_time_device", 0.0)
        val s = seeded(extra)
        val b = s.daySummary(last, now).toJson().o("sleep").o("breathing")
        assertEquals(Triple(25.0, 9.0, round(25 / 9.0, 1)), Triple(b.n("drops"), b.n("hours"), b.n("value")))
        assertEquals(json("median" to 2.3, "n" to 7).toString(), b.o("usual").toString())
        assertEquals("odi_not_measured", s.daySummary(unmeasured, now).toJson().o("sleep").o("breathing").o("withheld").getValue("reason").jsonPrimitive.content)
    }

    // Series: Amsterdam, a day of HR with a 30-minute gap, a two-minute spike and an artefact.
    private val ams = "Europe/Amsterdam"
    private val day = LocalDate.of(2026, 9, 24)
    private val midnight = Instant.parse("2026-09-23T22:00:00Z")
    private val spike = midnight.plusSeconds((14 * 60 + 37) * 60L)
    private fun hrDay(): Science {
        val rows = (0 until 1440).filter { it !in 600 until 630 }.map { m -> midnight.plusSeconds(60L * m) }
            .map { ts -> Sample(ts, "hr", if (ts == spike) 172.0 else if (ts == spike.plusSeconds(60)) 168.0 else if (ts == midnight.plusSeconds(300)) 250.0 else 70.0) } +
            Sample(midnight.minusSeconds(60), "hr", 99.0)
        return Science(MemoryData(Zones(ams), rows), MemoryDerived())
    }

    @Test
    fun `the day view is every minute of the local day, with gaps and no artefacts`() {
        val hr = hrDay().daySeries(day, listOf("hr")).toJson().o("series").getValue("hr").jsonArray
        assertEquals(1440 - 30 - 1, hr.size)
        assertEquals(midnight.toEpochMilli(), hr[0].jsonArray[0].jsonPrimitive.long)
        assertEquals(172.0, hr.maxOf { it.jsonArray[1].jsonPrimitive.double })
    }

    @Test
    fun `a two-minute spike survives every zoom, and buckets count only what was measured`() {
        val s = hrDay()
        for (b in Bucket.entries) {
            val peak = s.bucketSeries("hr", day, day, b).toJson().getValue("buckets").jsonArray.map { it.jsonObject }.maxBy { it.n("max") }
            assertEquals(172.0, peak.n("max"))
            assertEquals(spike.toEpochMilli(), peak.getValue("t_max").jsonPrimitive.long)
            assertTrue(peak.n("mean") < 90)
        }
        val d = s.bucketSeries("hr", day, day, Bucket.DAY).toJson().getValue("buckets").jsonArray.single().jsonObject
        assertEquals(midnight.toEpochMilli() to 1440 - 30 - 1, d.getValue("t").jsonPrimitive.long to d.getValue("n").jsonPrimitive.int)
        val ten = s.bucketSeries("hr", day, day, Bucket.HOUR).toJson().getValue("buckets").jsonArray.map { it.jsonObject }
            .first { it.getValue("t").jsonPrimitive.long == midnight.plusSeconds(36000).toEpochMilli() }
        assertEquals(30, ten.getValue("n").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { s.bucketSeries("hr", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), Bucket.QUARTER).toJson() }
    }

    @Test
    fun `steps buckets carry the sum`() {
        val rows = (0 until 12).map { Sample(midnight.plusSeconds(8 * 3600 + 60L * it), "steps_per_minute", 100.0) }
        val out = Science(MemoryData(Zones(ams), rows), MemoryDerived()).bucketSeries("steps", day, day, Bucket.HOUR).toJson().getValue("buckets").jsonArray
        assertEquals(1, out.size)
        assertEquals(1200.0 to 100.0, out[0].jsonObject.n("sum") to out[0].jsonObject.n("max"))
    }

    @Test
    fun `a zone change recuts the days`() {
        val rows = generateSequence(Instant.parse("2026-10-04T00:00:00Z")) { it.plusSeconds(600) }.takeWhile { it < Instant.parse("2026-10-07T00:00:00Z") }.map { Sample(it, "hr", 70.0) }.toList()
        val before = Science(MemoryData(Zones("Europe/Berlin"), rows), MemoryDerived()).daySeries(LocalDate.of(2026, 10, 6), listOf("hr")).toJson()
        assertEquals("Europe/Berlin" to Instant.parse("2026-10-05T22:00:00Z").toEpochMilli(), before.getValue("timezone").jsonPrimitive.content to before.getValue("start").jsonPrimitive.long)
        val s = Science(MemoryData(Zones("Europe/Berlin", listOf(Instant.parse("2026-10-05T15:00:00Z") to "Asia/Dubai")), rows), MemoryDerived())
        val left = s.daySeries(LocalDate.of(2026, 10, 5), listOf("hr")).toJson()
        assertEquals("Europe/Berlin", left.getValue("timezone").jsonPrimitive.content)
        assertEquals(Instant.parse("2026-10-04T22:00:00Z").toEpochMilli() to Instant.parse("2026-10-05T20:00:00Z").toEpochMilli(), left.getValue("start").jsonPrimitive.long to left.getValue("end").jsonPrimitive.long)
        assertEquals(22 * 6, left.o("series").getValue("hr").jsonArray.size)
        val arrived = s.daySeries(LocalDate.of(2026, 10, 6), listOf("hr")).toJson()
        assertEquals("Asia/Dubai" to Instant.parse("2026-10-05T20:00:00Z").toEpochMilli(), arrived.getValue("timezone").jsonPrimitive.content to arrived.getValue("start").jsonPrimitive.long)
        assertEquals(24 * 6, arrived.o("series").getValue("hr").jsonArray.size)
    }
}
