package strap.science

import strap.science.Illness.Limb
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from server/tests/derive/test_illness_rule.py.
class IllnessRuleTest {
    private val night = LocalDate.of(2026, 5, 20)
    private val nights = Illness.BASELINE_NIGHTS
    private fun baseline(n: Int = nights, f: (Int) -> Double) = (1..n).associate { night.minusDays(it.toLong()) to f(it) }
    private val rrBase = baseline { 13.0 + it % 2 }
    private val tempBase = baseline { 33.0 + 0.5 * (it % 2) }
    private fun rr(v: Double) = rrBase + (night to v)
    private fun temp(v: Double) = tempBase + (night to v)
    private fun limb(m: Map<LocalDate, Double>) = Illness.limbFor(m, night)

    @Test
    fun `a limb is the night minus the 14-night median, with that window's MAD`() {
        assertEquals(Limb(2.5, 0.5), limb(rr(16.0)))
        assertEquals(Limb(2.5, 0.5), limb(rr(16.0) + (night.minusDays(nights + 1L) to 99.0)))
    }

    @Test
    fun `a limb needs ten baseline nights and a value of its own`() {
        val short = rrBase.entries.take(Illness.MIN_BASELINE_NIGHTS - 1).associate { it.toPair() }
        assertNull(limb(short + (night to 16.0)))
        val enough = rrBase.entries.take(Illness.MIN_BASELINE_NIGHTS).associate { it.toPair() }
        assertNotNull(limb(enough + (night to 16.0)))
        assertNull(limb(rrBase))
    }

    @Test
    fun `severity`() {
        assertNull(Illness.severity(limb(rr(14.0)), limb(temp(33.0)), sustained = false))
        assertEquals("moderate", Illness.severity(limb(rr(16.0)), limb(temp(33.25)), sustained = false))
        assertNull(Illness.severity(limb(rr(15.0)), null, sustained = false))
    }

    @Test
    fun `the rr trigger is inclusive at exactly two breaths`() {
        val l = limb(baseline { 14.0 } + (night to 16.0))
        assertEquals(Limb(2.0, 0.0), l)
        assertEquals("moderate", Illness.severity(l, null, sustained = false))
    }

    @Test
    fun `a noisy person's ordinary spread does not flag them`() {
        val l = limb(baseline { 11.0 + 6.0 * (it % 2) } + (night to 16.5))
        assertEquals(Limb(2.5, 3.0), l)
        assertNull(Illness.severity(l, null, sustained = false))
    }

    @Test
    fun `a temperature rise under the cut does not flag`() {
        val flat = baseline { 33.0 }
        val l = limb(flat + (night to 33.25))
        assertEquals(Limb(0.25, 0.0), l)
        assertNull(Illness.severity(null, l, sustained = false))
        assertEquals("moderate", Illness.severity(null, limb(flat + (night to 33.5)), sustained = false))
    }

    @Test
    fun `high needs both limbs and a sustained rise, and a missing limb never blocks the other`() {
        val r = limb(rr(16.0))
        val t = limb(temp(34.0))
        assertEquals(Limb(0.75, 0.25), t)
        assertEquals("high", Illness.severity(r, t, sustained = true))
        assertEquals("moderate", Illness.severity(r, t, sustained = false))
        assertEquals("moderate", Illness.severity(r, null, sustained = false))
        assertEquals("moderate", Illness.severity(null, t, sustained = false))
        assertNull(Illness.severity(null, null, sustained = true))
    }

    @Test
    fun `the weak limb is held to a stricter robustness bar`() {
        val flags = limb(baseline { 33.0 + 0.4 * (it % 2) } + (night to 33.75))!!
        assertEquals(0.55, flags.delta, 1e-9)
        assertEquals(0.2, flags.mad, 1e-9)
        assertEquals("moderate", Illness.severity(null, flags, sustained = false))
        val quiet = limb(baseline { 33.0 + 0.8 * (it % 2) } + (night to 33.95))!!
        assertEquals(0.55, quiet.delta, 1e-9)
        assertEquals(0.4, quiet.mad, 1e-9)
        assertNull(Illness.severity(null, quiet, sustained = false))
    }

    @Test
    fun `sustained reads the prior night against its own baseline`() {
        val prior = night.minusDays(1)
        assertTrue(Illness.sustained(baseline(nights + 1) { 13.0 + it % 2 } + (prior to 15.0) + (night to 16.0), night))
        assertFalse(Illness.sustained(rr(16.0), night))
        val short = baseline(Illness.MIN_BASELINE_NIGHTS - 1) { 13.0 + it % 2 }
        assertFalse(Illness.sustained(short + (prior to 20.0) + (night to 16.0), night))
    }
}
