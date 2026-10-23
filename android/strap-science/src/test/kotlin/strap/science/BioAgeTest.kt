package strap.science

import kotlin.test.Test
import kotlin.test.assertEquals

class BioAgeTest {
    @Test
    fun `fitness above the decade's median and a short-ish night move the age both ways`() {
        val e = BioAge.estimate(35.0, "male", 45.5, 7.3)
        assertEquals(39.7, e.reference)
        assertEquals(-2.992, e.fitnessYears, 0.001)
        assertEquals(0.787, e.sleepYears, 0.001)
        assertEquals(32.796, e.age, 0.001)
    }

    @Test
    fun `the self-report correction lifts a six-hour night to six point eight`() {
        assertEquals(0.129, BioAge.estimate(35.0, "male", 39.7, 6.0).sleepYears, 0.001)
        assertEquals(0.0, BioAge.estimate(35.0, "male", 39.7, 6.0).fitnessYears, 1e-9)
    }

    @Test
    fun `the reference follows decade and sex, and each term is capped at ten years`() {
        assertEquals(35.3, BioAge.reference(45.0, null))
        assertEquals(15.4, BioAge.reference(95.0, "female"))
        assertEquals(46.5, BioAge.reference(18.0, "male"))
        assertEquals(10.0, BioAge.estimate(40.0, "male", 5.0, 8.0).fitnessYears)
    }
}
