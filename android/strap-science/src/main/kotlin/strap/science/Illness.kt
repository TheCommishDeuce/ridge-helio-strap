package strap.science

import java.time.LocalDate

// The illness early-warning flag (spec/02 §2.9): breathing rate and skin temperature against
// the owner's own 14-night baseline. Safety-relevant, so it stays exactly the server's rule.

internal object Illness {
    const val BASELINE_NIGHTS = 14
    const val MIN_BASELINE_NIGHTS = 10
    const val RR_TRIGGER_BPM = 2.0
    const val RR_SUSTAINED_BPM = 1.5
    const val TEMP_TRIGGER_C = 0.5
    const val RR_MAD_MULTIPLE = 1.5
    const val TEMP_MAD_MULTIPLE = 2.0
    const val TEMP_MIN_VALID_C = 25.0

    data class Limb(val delta: Double, val mad: Double)

    fun limbFor(nights: Map<LocalDate, Double>, night: LocalDate): Limb? {
        val value = nights[night] ?: return null
        val first = night.minusDays(BASELINE_NIGHTS.toLong())
        val history = nights.filterKeys { it >= first && it < night }.values.toList()
        if (history.size < MIN_BASELINE_NIGHTS) return null
        return Limb(value - Robust.median(history), Robust.medianAbsDeviation(history))
    }

    fun severity(rr: Limb?, temp: Limb?, sustained: Boolean): String? = when {
        over(rr, RR_TRIGGER_BPM) && over(temp, TEMP_TRIGGER_C) && sustained -> "high"
        robustlyOver(rr, RR_TRIGGER_BPM, RR_MAD_MULTIPLE) || robustlyOver(temp, TEMP_TRIGGER_C, TEMP_MAD_MULTIPLE) -> "moderate"
        else -> null
    }

    private fun over(limb: Limb?, trigger: Double) = limb != null && limb.delta >= trigger

    private fun robustlyOver(limb: Limb?, trigger: Double, madMultiple: Double) =
        over(limb, trigger) && limb!!.delta >= madMultiple * limb.mad

    fun sustained(rrNights: Map<LocalDate, Double>, night: LocalDate): Boolean {
        val prior = limbFor(rrNights, night.minusDays(1))
        return prior != null && prior.delta >= RR_SUSTAINED_BPM
    }
}

private fun firstNight(day: LocalDate): LocalDate = day.minusDays((Illness.BASELINE_NIGHTS + 1).toLong())

/** Mean night skin temperature (> 25 °C) per wake date, main sessions only. */
private fun Science.tempNights(day: LocalDate): Map<LocalDate, Double> {
    val from = zones.bounds(firstNight(day)).first
    val until = zones.bounds(day).second
    val sums = LinkedHashMap<LocalDate, DoubleArray>()
    for (session in data.sleepSessions(from, until)) {
        if (!session.isMain || session.end < from || session.end >= until) continue
        val values = data.samples("skin_temp_c", session.start, session.end).map { it.value }.filter { it > Illness.TEMP_MIN_VALID_C }
        if (values.isEmpty()) continue
        val acc = sums.getOrPut(zones.dateOf(session.end)) { DoubleArray(2) }
        acc[0] += values.sum()
        acc[1] += values.size
    }
    return sums.filterValues { it[1] > 0 }.mapValues { (_, a) -> a[0] / a[1] }
}

/** Flags, re-flags or clears [day]. */
internal fun Science.illnessFlag(day: LocalDate) {
    val rrNights = store.range("respiratory_rate_sleep", firstNight(day), day).associate { it.day to it.value }
    val tempNights = tempNights(day)
    val newest = (rrNights.keys + tempNights.keys).filter { it <= day }.maxOrNull()
    if (Freshness.unavailableReason(day, newest) != null) return
    val rr = Illness.limbFor(rrNights, day)
    val temp = Illness.limbFor(tempNights, day)
    if (rr == null && temp == null) return
    val sustained = Illness.sustained(rrNights, day)
    val severity = Illness.severity(rr, temp, sustained)
    if (severity == null) {
        store.clearIllness(day)
        return
    }
    store.putIllness(
        IllnessFlag(
            day, severity, rr?.let { round(it.delta, 3).toFloat() }, temp?.let { round(it.delta, 3).toFloat() }, sustained,
            listOfNotNull(rr?.let { "respiratory_rate_normal" }, temp?.let { "skin_temp_signals" }),
        ),
    )
}
