package strap.science

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** A night the autumn clock change cut short gets its hour back only when the heart rate says asleep. */
class ClockGapTest {
    private val zone = "Europe/Amsterdam"
    private val start = Instant.parse("2026-10-24T21:40:00Z") // 23:40 CEST
    private val change = Instant.parse("2026-10-25T01:00:00Z") // 03:00 CEST → 02:00 CET
    private val end = Instant.parse("2026-10-25T06:10:00Z") // 07:10 CET

    // As the parser leaves it: stages up to the change, the repeated hour empty, then on.
    private val night = SleepSession(
        start, end, remMin = 120, lightMin = 250, deepMin = 80, wakeMin = 20,
        stages = listOf(
            Stage(start.toEpochMilli(), change.toEpochMilli(), 4),
            Stage(change.plusSeconds(3600).toEpochMilli(), end.toEpochMilli(), 8),
        ),
    )

    private fun hr(from: Instant, until: Instant, bpm: Double) =
        generateSequence(from) { it.plusSeconds(60) }.takeWhile { it < until }.map { Sample(it, "hr", bpm) }.toList()

    private fun filled(vararg hr: List<Sample>): SleepSession =
        Science(MemoryData(Zones(zone), hr.flatMap { it }, sessions = listOf(night)), MemoryDerived()).data.mainSessions().single()

    @Test
    fun `a sleeping heart rate through the hour counts it as light sleep`() {
        val s = filled(hr(start, change, 52.0), hr(change, change.plusSeconds(3600), 50.0), hr(change.plusSeconds(3600), end, 54.0))
        assertEquals(60, s.estimatedMin)
        assertEquals(310, s.lightMin)
        assertEquals(Stage(change.toEpochMilli(), change.plusSeconds(3600).toEpochMilli(), 4), s.stages[1])
    }

    @Test
    fun `an awake heart rate leaves the night as recorded`() {
        val s = filled(hr(start, change, 52.0), hr(change, change.plusSeconds(3600), 80.0), hr(change.plusSeconds(3600), end, 54.0))
        assertEquals(night, s)
    }

    @Test
    fun `no heart rate leaves the night as recorded`() {
        assertEquals(night, filled(hr(start, change, 52.0)))
    }

    @Test
    fun `an ordinary night is untouched`() {
        val other = night.copy(start = start.plusSeconds(7 * 86400), end = end.plusSeconds(7 * 86400),
            stages = night.stages.map { Stage(it.startMs + 7 * 86_400_000L, it.endMs + 7 * 86_400_000L, it.code) })
        val s = Science(MemoryData(Zones(zone), hr(other.start, other.end, 50.0), sessions = listOf(other)), MemoryDerived()).data.mainSessions().single()
        assertEquals(other, s)
    }
}
