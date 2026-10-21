package strap.science

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_device_totals.py: the strap's counter and the per-minute
// sum are two instruments, chosen between and never blended.
class DeviceTotalsTest {
    private val day = LocalDate.of(2026, 6, 16)
    private val reportedAt = Instant.parse("2026-06-16T21:30:00Z")
    private val strap = "strap_0x16"
    private fun device(steps: Int? = 9264, distanceM: Double? = null, source: String = strap, reported: Instant = reportedAt, readAt: Instant? = reportedAt) =
        DeviceDailyTotal(day, steps, distanceM, 451.0, source, reported, readAt)
    private fun JsonObject.str(k: String) = get(k)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `the counter beats the per-minute sum, which is the fallback`() {
        assertEquals(9264.0, selectSteps(device(), 6100.0).value)
        assertEquals(strap, selectSteps(device(), 6100.0).flags.str("source"))
        assertEquals(6100.0, selectSteps(null, 6100.0).value)
        assertEquals("steps_per_minute", selectSteps(null, 6100.0).flags.str("source"))
        val noSteps = selectSteps(device(steps = null, distanceM = 5081.0), 6100.0)
        assertEquals(6100.0, noSteps.value)
        assertEquals("steps_per_minute", noSteps.flags.str("source"))
        assertEquals("strap_0x99", selectSteps(device(source = "strap_0x99"), 1.0).flags.str("source"))
    }

    @Test
    fun `the losing instrument and both instants stay visible`() {
        val s = selectSteps(device(), 6100.4)
        assertEquals("6100", s.flags.str("steps_per_minute_sum"))
        assertEquals("2026-06-16T21:30:00+00:00", s.flags.str("reported_at"))
        val read = Instant.parse("2026-06-16T09:00:00Z")
        assertEquals("2026-06-16T09:00:00+00:00", selectSteps(device(readAt = read), 6100.0).flags.str("read_at"))
        assertNull(selectSteps(device(readAt = null), 6100.0).flags.str("read_at"))
    }

    private val dayEnd = Instant.parse("2026-06-16T18:30:00Z")
    private val readMidDay = Instant.parse("2026-06-16T03:30:00Z")
    private val pushedAfterMidnight = Instant.parse("2026-06-17T02:00:00Z")
    private fun caveats(d: DeviceDailyTotal) = partialDayCaveats(d, selectSteps(d, 6100.0), dayEnd)

    @Test
    fun `a counter read before the day closed says so, whenever it was pushed`() {
        for (reported in listOf(readMidDay, pushedAfterMidnight)) {
            val c = caveats(device(readAt = readMidDay, reported = reported))
            assertEquals(listOf(Freshness.COUNTER_MID_DAY), c.map { it.str("reason") })
            assertEquals("2026-06-16T03:30:00+00:00", c[0].str("read_at"))
            assertTrue("2026-06-16T03:30:00+00:00" in c[0].str("message")!!)
        }
        assertEquals(emptyList(), caveats(device(readAt = Instant.parse("2026-06-16T19:00:00Z"), reported = pushedAfterMidnight)))
    }

    @Test
    fun `an unknown read instant gets its own caveat, the per-minute tier none`() {
        val c = caveats(device(readAt = null, reported = pushedAfterMidnight))
        assertEquals(listOf(Freshness.COUNTER_READ_TIME_UNKNOWN), c.map { it.str("reason") })
        assertNull(c[0].str("read_at"))
        assertTrue("when it was taken" in c[0].str("message")!!)
        assertEquals(emptyList(), partialDayCaveats(null, selectSteps(null, 6100.0), dayEnd))
        assertEquals(emptyList(), caveats(device(steps = null, readAt = null)))
    }

    @Test
    fun `the two instruments are never blended`() {
        for ((counter, perMinute) in listOf(9264 to 6100.0, 1000 to 12000.0, 0 to 0.0, 5 to 5.0)) {
            assertEquals(counter.toDouble(), selectSteps(device(steps = counter), perMinute).value)
        }
    }

    @Test
    fun `distance is the strap's metres, else stride times whichever steps won, else nothing`() {
        val stride = 0.7245
        val withMetres = device(distanceM = 5081.0)
        val d = selectDistance(withMetres, selectSteps(withMetres, 6100.0), stride)!!
        assertEquals(5081.0, d.value)
        assertEquals(strap to "device", d.flags.str("source") to d.flags.str("method"))
        val noProfile = device(steps = null, distanceM = 5081.0)
        assertEquals(5081.0, selectDistance(noProfile, selectSteps(noProfile, 0.0), null)!!.value)
        val s = selectDistance(device(), selectSteps(device(), 6100.0), stride)!!
        assertEquals(9264 * stride, s.value)
        assertEquals(strap to "stride", s.flags.str("source") to s.flags.str("method"))
        assertEquals("0.725", s.flags.str("stride_m"))
        val fallback = selectDistance(null, selectSteps(null, 6100.0), stride)!!
        assertEquals(6100.0 * stride, fallback.value)
        assertEquals("steps_per_minute", fallback.flags.str("source"))
        assertNull(selectDistance(null, selectSteps(null, 6100.0), null))
    }
}
