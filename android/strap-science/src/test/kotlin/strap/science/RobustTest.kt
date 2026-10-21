package strap.science

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Ported from server/tests/derive/test_robust.py.
class RobustTest {
    @Test
    fun `the MAD constant is the normal consistency constant to 4 dp`() {
        assertEquals(1.4826, round(1.4826022185056018, 4))
        assertEquals(Robust.MAD_TO_SD, round(1.4826022185056018, 4))
    }

    @Test
    fun `robust sd scales the MAD and its floor guards a flat history`() {
        assertEquals(14.826, Robust.robustSd(10.0), 1e-9)
        assertEquals(0.0, Robust.robustSd(0.0))
        assertEquals(0.5, Robust.robustSd(0.1, floor = 0.5))
        assertEquals(0.5, Robust.robustSd(0.0, floor = 0.5))
        assertEquals(14.826, Robust.robustSd(10.0, floor = 0.5), 1e-9)
        assertEquals(0.5, Robust.robustSd(0.5 / Robust.MAD_TO_SD, floor = 0.5), 1e-12)
    }

    @Test
    fun `median of odd and even samples`() {
        assertEquals(2.0, Robust.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(7.0, Robust.median(listOf(7.0)))
        assertEquals(30.0, Robust.median(listOf(50.0, 10.0, 30.0, 40.0, 20.0)))
        assertEquals(2.5, Robust.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(390.0, Robust.median(listOf(480.0, 300.0, 450.0, 330.0, 420.0, 360.0)))
    }

    @Test
    fun `the median of an empty sample is an error, not a zero`() {
        assertFailsWith<IllegalArgumentException> { Robust.median(emptyList()) }
    }

    @Test
    fun `median absolute deviation`() {
        assertEquals(1.0, Robust.medianAbsDeviation(listOf(10.0, 1.0, 3.0, 2.0, 4.0)))
        assertEquals(60.0, Robust.medianAbsDeviation(listOf(480.0, 300.0, 450.0, 330.0, 420.0, 360.0)))
    }

    @Test
    fun `percentile_cont interpolates like Postgres`() {
        assertEquals(null, Robust.percentileCont(emptyList(), 0.95))
        assertEquals(7.0, Robust.percentileCont(listOf(7.0), 0.95))
        assertEquals(9.55, Robust.percentileCont((1..10).map { it.toDouble() }, 0.95)!!, 1e-12)
    }

    @Test
    fun `Python rounding is half-even on the exact value`() {
        assertEquals(2L, roundInt(2.5))
        assertEquals(4L, roundInt(3.5))
        assertEquals(2.67, round(2.675, 2)) // 2.675 is 2.67499999… in binary
        assertEquals(0.1, round(0.05, 1)) // 0.05 is 0.05000000000000000277…
    }
}
