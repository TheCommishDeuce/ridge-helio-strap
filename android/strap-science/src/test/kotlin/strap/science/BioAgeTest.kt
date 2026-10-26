package strap.science

import strap.science.BioAge.Inputs
import strap.science.BioAge.NightRow
import strap.science.BioAge.Term
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// OpenStrap's worked examples (docs/algorithms/healthspan.md §7), then Ridge's window rules.
class BioAgeTest {
    private fun years(e: BioAge.Estimate, t: Term) = e.part(t)!!.years

    @Test
    fun `A, close to average, reads about 34 to 38`() {
        val e = BioAge.estimate(Inputs(35.0, "male", 58.0, 7000.0, 75.0, 400.0, 0.0, vo2max = 44.0))
        assertEquals(-0.07, years(e, Term.RESTING_HR), 0.005)
        assertEquals(1.22, years(e, Term.STEPS), 0.005)
        assertEquals(-0.93, years(e, Term.VO2MAX), 0.005)
        assertEquals(0.71, years(e, Term.REGULARITY), 0.005)
        assertEquals(0.0, years(e, Term.SHORT_SLEEP))
        assertEquals(0.0, years(e, Term.STRENGTH))
        assertEquals(35.6, e.age, 0.05)
        assertEquals(34 to 38, e.low.roundToInt() to e.high.roundToInt())
        assertEquals(0.61, e.part(Term.STEPS)!!.low, 0.005)
        assertEquals(1.5, e.part(Term.STEPS)!!.high, 1e-9)
    }

    @Test
    fun `B, healthy, reads about 30 to 34`() {
        val e = BioAge.estimate(Inputs(35.0, "male", 55.0, 10000.0, 87.0, 450.0, 40.0, vo2max = 46.4))
        assertEquals(-0.35, years(e, Term.RESTING_HR), 0.005)
        assertEquals(-0.86, years(e, Term.STEPS), 0.005)
        assertEquals(-1.45, years(e, Term.VO2MAX), 0.005)
        assertEquals(-0.49, years(e, Term.REGULARITY), 0.005)
        assertEquals(-2.03, years(e, Term.STRENGTH), 0.005)
        assertEquals(-1.60, e.part(Term.STRENGTH)!!.high, 0.005)
        assertEquals(31.9, e.age, 0.05)
        assertEquals(30 to 34, e.low.roundToInt() to e.high.roundToInt())
        assertTrue(!e.fitnessCapped)
    }

    @Test
    fun `C, unfit, hits the fitness limit and reads about 36 to 40`() {
        val e = BioAge.estimate(Inputs(35.0, "male", 80.0, 4000.0, 75.0, 400.0, 0.0, vo2max = 30.0))
        assertEquals(2.00, years(e, Term.RESTING_HR), 0.005)
        assertEquals(1.5, years(e, Term.STEPS), 1e-9)
        assertEquals(2.0, years(e, Term.VO2MAX), 1e-9)
        assertTrue(e.fitnessCapped)
        assertEquals(38.4, e.age, 0.05)
        assertEquals(36 to 40, e.low.roundToInt() to e.high.roundToInt())
    }

    @Test
    fun `someone at every median reads their own age, and Ridge leaves VO2max out`() {
        val e = BioAge.estimate(Inputs(35.0, "male", 58.75, 7803.0, 80.9, 420.0, 0.0))
        assertEquals(35.0, e.age, 0.01)
        assertEquals(null, e.part(Term.VO2MAX))
    }

    @Test
    fun `steps blend from 55 to 65 and short sleep only ever adds`() {
        fun steps(age: Double, n: Double) = years(BioAge.estimate(Inputs(age, "male", 58.0, n, 80.9, 420.0, 0.0)), Term.STEPS)
        assertEquals(1.22, steps(55.0, 7000.0), 0.01)
        assertEquals(-0.14, steps(60.0, 7000.0), 0.01)
        assertEquals(-1.51, steps(65.0, 7000.0), 0.01)
        assertEquals(-2.0, steps(65.0, 9000.0), 0.01)
        assertEquals(4.0, years(BioAge.estimate(Inputs(35.0, "male", 58.0, 7803.0, 80.9, 300.0, 0.0)), Term.SHORT_SLEEP))
        assertEquals(0.0, years(BioAge.estimate(Inputs(35.0, "male", 58.0, 7803.0, 80.9, 600.0, 0.0)), Term.SHORT_SLEEP))
    }

    @Test
    fun `strength earns credit up to 140 minutes a week and never adds years`() {
        fun strength(m: Double) = years(BioAge.estimate(Inputs(35.0, "male", 58.0, 7803.0, 80.9, 420.0, m)), Term.STRENGTH)
        assertEquals(-0.68, strength(10.0), 0.01)
        assertEquals(-2.03, strength(45.0), 0.01)
        assertEquals(0.0, strength(140.0))
        assertEquals(0.0, strength(300.0))
    }

    @Test
    fun `the resting-HR reference follows age and sex between band midpoints`() {
        assertEquals(68.75, BioAge.rhrReference(35.0, "male"), 1e-9)
        assertEquals(74.0, BioAge.rhrReference(22.0, "female"))
        assertEquals(70.0, BioAge.rhrReference(79.0, "female"))
        assertEquals(39.7, BioAge.vo2Reference(35.0, "male"), 1e-9)
    }

    private val asOf = LocalDate.of(2026, 10, 26)
    private val dob = LocalDate.of(1991, 4, 26)
    private fun days(n: Long) = (1..n).map { asOf.minusDays(it) }

    private fun window(n: Long, steps: Double = 7803.0, strengthPerDay: Double = 0.0) = BioAge.asOf(
        asOf, dob, "male",
        rhr = days(n).associateWith { 58.75 },
        wornSteps = days(n).associateWith { steps },
        nights = days(n).associateWith { NightRow(420.0, 80.9) },
        strengthMin = days(n).associateWith { strengthPerDay },
    )

    @Test
    fun `a full window gives a firm estimate, a month an early one, less nothing`() {
        val full = (window(180) as Card.Shown).value
        assertTrue(!full.early)
        assertEquals(full.age - 2, full.low, 1e-9)
        val early = (window(40) as Card.Shown).value
        assertTrue(early.early)
        assertEquals(early.age + 4, early.high, 1e-9)
        assertEquals("bioage_too_early", (window(20) as Card.Withheld).reason)
    }

    @Test
    fun `strength minutes become a weekly figure, and gaps in the data withhold`() {
        val e = (window(180, strengthPerDay = 40.0 / 7) as Card.Shown).value
        assertEquals(40.0, e.inputs.strengthMinPerWeek, 1e-9)
        val gappy = BioAge.asOf(asOf, dob, "male", days(180).associateWith { 58.0 }, days(180).filterIndexed { i, _ -> i % 3 == 0 }.associateWith { 8000.0 },
            days(180).associateWith { NightRow(420.0, 80.0) }, emptyMap())
        assertEquals("bioage_few_step_days", (gappy as Card.Withheld).reason)
        assertEquals("bioage_needs_profile", (BioAge.asOf(asOf, dob, null, emptyMap(), emptyMap(), emptyMap(), emptyMap()) as Card.Withheld).reason)
    }
}
