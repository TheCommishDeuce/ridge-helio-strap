package strap.science

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_sleep_need_inputs.py, test_sri_freshness.py,
// test_sri_scale.py and (its intent) test_derive_batch_order.py.
class SleepTest {
    private val ist = "Asia/Kolkata"
    private val today = LocalDate.of(2026, 6, 16)

    private fun scoredNights(store: MemoryDerived, count: Int) {
        for (back in 0L until count) store.put(Derived(today.minusDays(back), "sleep_health_score_4dim", 3.0, json("tst_min" to 340)))
    }

    private fun science(profile: Profile?, store: MemoryDerived, weights: List<WeightEntry> = emptyList(), sessions: List<SleepSession> = emptyList()) =
        Science(MemoryData(Zones(ist), sessions = sessions, profile = profile, weights = weights), store)

    @Test
    fun `sleep need and debt need only the date of birth, and the band follows age`() {
        val store = MemoryDerived().also { scoredNights(it, 10) }
        val s = science(Profile(178f, "male", LocalDate.of(1990, 1, 1)), store)
        assertNull(s.sleepDebtWithholdReason(today))
        s.sleepDebt(today)
        assertEquals(480.0, store.get(today, "sleep_need_min")!!.value)

        val old = MemoryDerived().also { scoredNights(it, 10) }
        science(Profile(178f, "male", LocalDate.of(today.year - 70, 1, 1)), old, listOf(WeightEntry(Instant.now(), 80f))).sleepDebt(today)
        assertEquals(450.0, old.get(today, "sleep_need_min")!!.value)
    }

    @Test
    fun `no date of birth withholds and names only the date of birth`() {
        val store = MemoryDerived().also { scoredNights(it, 10) }
        val s = science(null, store)
        assertEquals(Freshness.DOB_MISSING, s.sleepDebtWithholdReason(today))
        s.sleepDebt(today)
        assertNull(store.get(today, "sleep_debt_min"))
    }

    private fun hrvHistory(store: MemoryDerived) {
        for (back in 0L until 9) store.put(Derived(today.minusDays(back), "hrv_sleep_avg", 45.0 + back))
    }

    @Test
    fun `recovery has no sleep factor without a stored need, and uses the owner's own need`() {
        val store = MemoryDerived().also { scoredNights(it, 1); hrvHistory(it) }
        science(null, store).recovery(today)
        val factors = assertNotNull(store.get(today, "recovery_score")).flags.getValue("factors").jsonObject
        assertTrue("sleep" !in factors)

        val withNeed = MemoryDerived().also { scoredNights(it, 1); hrvHistory(it); it.put(Derived(today, "sleep_need_min", 450.0)) }
        science(null, withNeed).recovery(today)
        val sleep = withNeed.get(today, "recovery_score")!!.flags.getValue("factors").jsonObject.getValue("sleep").jsonObject
        assertEquals("450", sleep.getValue("need_min").jsonPrimitive.content)
    }

    private fun sriNights(n: Int): List<SleepSession> = (0L until n).map { k ->
        val wake = today.minusDays(k)
        val start = at(wake.minusDays(1), 23)
        val end = at(wake, 6, 30)
        SleepSession(start, end, remMin = 90, lightMin = 200, deepMin = 90, wakeMin = 20, stages = listOf(Stage(start.toEpochMilli(), end.toEpochMilli(), 4)))
    }

    @Test
    fun `the SRI read gate and write gate agree`() {
        for (n in 0..Sleep.SRI_DAYS) {
            val s = science(null, MemoryDerived(), sessions = sriNights(n))
            val reason = s.sriWithholdReason(today)
            assertEquals(reason == null, s.sri(today) != null, "$n nights")
            // Six nights fill seven days: the first night's evening falls on the window's first day.
            assertEquals(if (n >= 6) null else Sleep.SRI_WINDOW_TOO_SHORT, reason, "$n nights")
        }
    }

    @Test
    fun `an older week is not today's SRI, and a short week names the directive`() {
        val week = science(null, MemoryDerived(), sessions = sriNights(Sleep.SRI_DAYS))
        assertEquals(Freshness.NOT_DERIVED_YET, Freshness.unavailableReason(today, today.minusDays(30)) { week.sriWithholdReason(today) } ?: Freshness.NOT_DERIVED_YET)
        assertNull(Freshness.unavailableReason(today, today) { error("today's row asks nothing") })
        val short = science(null, MemoryDerived(), sessions = sriNights(3))
        assertEquals(Sleep.SRI_WINDOW_TOO_SHORT, Freshness.unavailableReason(today, null) { short.sriWithholdReason(today) })
    }

    private fun sriForShift(shift: Int): Double {
        val last = LocalDate.of(2026, 3, 20)
        val sessions = (0 until Sleep.SRI_DAYS).map { k ->
            val day = last.minusDays((Sleep.SRI_DAYS - 1 - k).toLong())
            val start = at(day, 1).plusSeconds(if (k % 2 == 1) 60L * shift else 0)
            val end = start.plusSeconds(7 * 3600)
            SleepSession(start, end, stages = listOf(Stage(start.toEpochMilli(), end.toEpochMilli(), 4)))
        }
        return assertNotNull(science(null, MemoryDerived(), sessions = sessions).sri(last))
    }

    @Test
    fun `our SRI is linear in the nightly shift and keeps the worked example`() {
        for (shift in listOf(0, 15, 30, 45, 60, 90, 120, 150, 180)) {
            assertEquals(round(100.0 - 200.0 / 1440.0 * 2 * shift, 2), sriForShift(shift), 0.01, "shift $shift")
        }
        assertEquals(66.67, sriForShift(120), 0.01)
        val top = sriForShift(30) - 87.32
        val bottom = sriForShift(90) - 71.65
        assertTrue(top in 3.0..5.0 && bottom in 3.0..5.0 && Math.abs(top - bottom) < 1.5)
        assertEquals(60.0, sriForShift(Math.round((100.0 - 60.0) / (200.0 / 1440.0) / 2).toInt()), 0.5)
    }

    @Test
    fun `a night's rows are in place when its day derives, on the first derive`() {
        val end = at(today, 7)
        val night = SleepSession(end.minusSeconds(7 * 3600), end, remMin = 90, lightMin = 240, deepMin = 60, wakeMin = 30)
        val store = MemoryDerived()
        Science(MemoryData(Zones(ist), sessions = listOf(night), profile = Profile(dob = LocalDate.of(1990, 1, 1))), store).rederive()
        assertEquals("1", store.get(today, "sleep_debt_min")!!.flags.getValue("nights").jsonPrimitive.content)
    }
}
