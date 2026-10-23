package strap.science

import kotlin.math.ln
import kotlin.math.pow

// Biological age (spec/02 §2.11, the documented exception to "no composites"): chronological
// age plus the years that fitness and sleep move all-cause mortality, read through a Gompertz
// doubling time. A motivational estimate; withheld entirely when either term is missing.

public object BioAge {
    /** Gompertz mortality-rate doubling time, years. */
    private const val DOUBLING_YEARS = 7.7
    private val B = ln(2.0) / DOUBLING_YEARS
    private const val CAP_YEARS = 10.0

    /** FRIEND treadmill VO₂max medians by decade (20s…80s). */
    private val FRIEND_MALE = listOf(46.5, 39.7, 35.3, 29.2, 24.6, 20.6, 17.6)
    private val FRIEND_FEMALE = listOf(36.6, 28.3, 25.7, 22.9, 19.6, 17.2, 15.4)

    /** VO₂max SEE of the non-exercise model (spec/02 §2.10): 1.45 METs. */
    public const val VO2MAX_SEE: Double = 5.1

    /** [age] = [chronological] + [fitnessYears] + [sleepYears]; [reference] is the VO₂max median it was measured against. */
    public data class Estimate(val chronological: Double, val fitnessYears: Double, val sleepYears: Double, val reference: Double, val sleepHours: Double) {
        val age: Double get() = chronological + fitnessYears + sleepYears
    }

    private fun years(hazardRatio: Double) = (ln(hazardRatio) / B).coerceIn(-CAP_YEARS, CAP_YEARS)

    /** The FRIEND median for [age]'s decade; anything but "female" reads as male, as the load does. */
    public fun reference(age: Double, sex: String?): Double {
        val table = if (sex == "female") FRIEND_FEMALE else FRIEND_MALE
        return table[((age / 10).toInt() - 2).coerceIn(0, table.lastIndex)]
    }

    /**
     * [sleepHours] is the measured mean time asleep; it is first lifted by the Lauderdale 2008
     * self-report correction, since the hazard curve (Yin 2017) comes from self-reported sleep.
     */
    public fun estimate(age: Double, sex: String?, vo2max: Double, sleepHours: Double): Estimate {
        val ref = reference(age, sex)
        val fitness = years(0.85.pow((vo2max - ref) / 3.5))
        val reported = sleepHours + (3.2 - 0.4 * sleepHours).coerceIn(0.0, 1.2)
        val sleep = years(if (reported < 7) 1.06.pow(7 - reported) else 1.13.pow(reported - 7))
        return Estimate(age, fitness, sleep, ref, sleepHours)
    }
}
