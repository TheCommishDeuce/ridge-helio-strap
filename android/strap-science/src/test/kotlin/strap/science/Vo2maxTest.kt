package strap.science

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_vo2max_withhold.py and test_weight_freshness.py.
class Vo2maxTest {
    private val calm = listOf(50.0, 52.0, 54.0, 56.0, 58.0, 60.0, 62.0)
    private val mad8 = listOf(40.0, 48.0, 56.0, 64.0, 72.0)
    private val mad9 = listOf(40.0, 47.0, 56.0, 65.0, 72.0)

    @Test
    fun `the resting-HR gate`() {
        assertEquals(4.0, Robust.medianAbsDeviation(calm))
        assertEquals(9.0, Robust.medianAbsDeviation(mad9))
        assertNull(Vo2max.withholdReason(calm))
        assertNull(Vo2max.withholdReason(mad8))
        assertEquals(Vo2max.RHR_TOO_NOISY, Vo2max.withholdReason(mad9))
        assertNull(Vo2max.withholdReason(listOf(48.0, 48.0, 56.0, 64.0, 64.0)))
        assertEquals(Vo2max.RHR_TOO_NOISY, Vo2max.withholdReason(listOf(47.9, 47.9, 56.0, 64.1, 64.1)))
        assertEquals(Vo2max.FEW_RHR_DAYS, Vo2max.withholdReason(emptyList()))
        assertEquals(Vo2max.FEW_RHR_DAYS, Vo2max.withholdReason(listOf(56.0, 56.0)))
        assertNull(Vo2max.withholdReason(listOf(56.0, 56.0, 56.0)))
        for (rhr in listOf(39.0, 101.0, 120.0)) assertEquals(Vo2max.RHR_OUT_OF_RANGE, Vo2max.withholdReason(List(5) { rhr }))
        for (rhr in listOf(40.0, 100.0)) assertNull(Vo2max.withholdReason(List(5) { rhr }))
        assertEquals(Vo2max.RHR_TOO_NOISY, Vo2max.withholdReason(listOf(40.0, 44.0, 56.0, 56.0, 68.0, 72.0, 72.0)))
    }

    private val day = LocalDate.of(2026, 3, 8)

    private fun derive(rhrs: List<Double> = calm, srpa: Int? = 0, weightDay: LocalDate? = LocalDate.of(2026, 3, 7)): Pair<Science, MemoryDerived> {
        val store = MemoryDerived()
        rhrs.forEachIndexed { k, v -> store.put(Derived(day.minusDays((rhrs.size - 1 - k).toLong()), "rhr_daily", v)) }
        val weights = listOfNotNull(weightDay?.let { WeightEntry(it.atTime(LocalTime.of(6, 0)).toInstant(ZoneOffset.UTC), 72f) })
        val s = Science(MemoryData(Zones("Asia/Kolkata"), profile = Profile(175f, "male", LocalDate.of(1990, 1, 1), srpa), weights = weights), store)
        s.vo2max(day)
        return s to store
    }

    @Test
    fun `a calm week writes a row, a noisy one none at all`() {
        assertNotNull(derive().second.get(day, "vo2max_estimate"))
        assertNull(derive(mad9).second.get(day, "vo2max_estimate"))
    }

    @Test
    fun `an unanswered activity question withholds rather than assuming a level`() {
        val (s, store) = derive(srpa = null)
        assertNull(store.get(day, "vo2max_estimate"))
        assertEquals(Vo2max.SRPA_NOT_REPORTED, s.vo2maxWithholdReason(day))
    }

    @Test
    fun `the estimate moves with the answer by Jurca's published steps`() {
        val stored = (0..4).map { derive(srpa = it).second.get(day, "vo2max_estimate")!!.value }
        assertEquals(listOf(0.32, 0.74, 0.7, 1.27), (0 until 4).map { round((stored[it + 1] - stored[it]) / 3.5, 2) })
    }

    @Test
    fun `no weight, or a stale one, withholds and is never defaulted`() {
        assertNull(derive(weightDay = null).second.get(day, "vo2max_estimate"))
        val (fresh, freshStore) = derive(weightDay = day.minusDays(14))
        assertNotNull(freshStore.get(day, "vo2max_estimate"))
        assertNull(fresh.vo2maxWithholdReason(day))
        for (back in listOf(15L, 200L)) {
            val (stale, staleStore) = derive(weightDay = day.minusDays(back))
            assertNull(staleStore.get(day, "vo2max_estimate"))
            assertEquals(Freshness.WEIGHT_STALE, stale.vo2maxWithholdReason(day))
            assertEquals(72.0, stale.loadProfile(day)!!.weightKg, "a stale weight still loads for metrics that don't spend it")
        }
    }

    @Test
    fun `the weight horizon is two weeks, inclusive, and runs both ways`() {
        val on = LocalDate.of(2026, 3, 15)
        assertTrue(Freshness.weightIsStale(on.minusDays(15), on))
        for (back in listOf(14L, 1L, 0L)) assertTrue(!Freshness.weightIsStale(on.minusDays(back), on))
        assertEquals(40, Freshness.weightAgeDays(on.plusDays(40), on))
        assertTrue(Freshness.weightIsStale(on.plusDays(40), on))
        val (s, _) = derive(weightDay = day.minusDays(3))
        assertEquals(day.minusDays(3), s.loadProfile(day)!!.weightAsOf)
    }
}
