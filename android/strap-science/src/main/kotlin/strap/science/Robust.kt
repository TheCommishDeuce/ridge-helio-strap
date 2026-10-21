package strap.science

/** Robust statistics, one implementation each (spec/02 §0). */
public object Robust {
    /** `1/Φ⁻¹(0.75)`: MAD × this is the SD of a normal sample. */
    public const val MAD_TO_SD: Double = 1.4826

    /** Textbook median: the mean of the two central values when even. */
    public fun median(values: Collection<Double>): Double {
        require(values.isNotEmpty()) { "median of an empty sample is undefined" }
        val ordered = values.sorted()
        val mid = ordered.size / 2
        return if (ordered.size % 2 == 1) ordered[mid] else 0.5 * (ordered[mid - 1] + ordered[mid])
    }

    /** `median(|x − median(x)|)`, raw units. */
    public fun medianAbsDeviation(values: Collection<Double>): Double {
        val med = median(values)
        return median(values.map { Math.abs(it - med) })
    }

    public fun robustSd(mad: Double, floor: Double = 0.0): Double = maxOf(mad * MAD_TO_SD, floor)

    /** Postgres' `percentile_cont(q)`: linear interpolation between the closest ranks. */
    public fun percentileCont(values: Collection<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val ordered = values.sorted()
        val pos = q * (ordered.size - 1)
        val lo = Math.floor(pos).toInt()
        val hi = Math.ceil(pos).toInt()
        return if (lo == hi) ordered[lo] else ordered[lo] + (pos - lo) * (ordered[hi] - ordered[lo])
    }
}
