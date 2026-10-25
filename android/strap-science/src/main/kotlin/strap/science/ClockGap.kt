package strap.science

import java.time.Duration
import java.time.Instant

/**
 * Nights across an autumn clock change. The strap keeps sleep on its wall clock and keeps
 * only one pass of the repeated hour (spec/01 §7.1), so the night arrives with that hour
 * missing from its stages and its totals. When the heart rate through the hour stays at the
 * night's sleeping level, the hour is counted as light sleep and the night carries how many
 * minutes were added ([SleepSession.estimatedMin]); otherwise the night stays as recorded.
 */
internal class ClockGapFill(private val raw: RawData) : RawData by raw {
    override fun sleepSessions(from: Instant, until: Instant): List<SleepSession> = raw.sleepSessions(from, until).map(::fill)

    override fun mainSessions(): List<SleepSession> = raw.mainSessions().map(::fill)

    fun fill(s: SleepSession): SleepSession {
        val light = s.lightMin
        if (!s.isMain || light == null || s.estimatedMin > 0) return s
        val stages = s.stages.sortedBy { it.startMs }
        for ((a, b) in stages.zipWithNext()) {
            val from = Instant.ofEpochMilli(a.endMs)
            val until = Instant.ofEpochMilli(b.startMs)
            val change = Zones.id(zones.zoneAt(from)).rules.nextTransition(Instant.ofEpochMilli(a.startMs)) ?: continue
            val hour = change.duration.abs()
            if (!change.isOverlap || change.instant < from || change.instant.plus(hour) > until) continue
            val gapEnd = change.instant.plus(hour)
            if (!asleep(s, change.instant, gapEnd)) return s
            val minutes = hour.toMinutes().toInt()
            val added = Stage(change.instant.toEpochMilli(), gapEnd.toEpochMilli(), LIGHT)
            return s.copy(stages = (stages + added).sortedBy { it.startMs }, lightMin = light + minutes, estimatedMin = minutes)
        }
        return s
    }

    /** Whether the heart rate covers at least half of [from, until) and its median is within [MARGIN] of the rest of the night's. */
    private fun asleep(s: SleepSession, from: Instant, until: Instant): Boolean {
        val gap = hr(from, until)
        if (gap.size < Duration.between(from, until).toMinutes() / 2) return false
        val rest = hr(s.start, from) + hr(until, s.end)
        if (rest.isEmpty()) return false
        return median(gap) <= median(rest) + MARGIN
    }

    private fun hr(from: Instant, until: Instant) = raw.samples("hr", from, until).map { it.value }.filter(HrValidity::valid)

    private fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }

    private companion object {
        const val LIGHT = 4
        const val MARGIN = 10.0
    }
}
