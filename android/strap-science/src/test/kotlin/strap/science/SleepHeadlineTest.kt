package strap.science

import strap.science.SleepHeadline.Kind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SleepHeadlineTest {
    private val t0 = Instant.parse("2026-10-25T22:00:00Z").toEpochMilli()

    /** Stages laid end to end from 22:00: (minutes, code). */
    private fun night(vararg parts: Pair<Int, Int>, score: Int? = 85, deep: Int? = 90, rem: Int? = 90): Night {
        var t = t0
        val stages = parts.map { (min, code) -> Stage(t, t + min * 60_000L, code).also { t += min * 60_000L } }
        return Night(Instant.ofEpochMilli(t0), Instant.ofEpochMilli(t), SleepSession.MAIN, rem, null, deep, null, stages, score)
    }

    private fun kind(n: Night, asleep: Double) = SleepHeadline.of(n, asleep).kind

    @Test
    fun `awake before falling asleep and after the last sleep is not counted`() {
        val n = night(20 to 7, 200 to 4, 10 to 7, 200 to 5, 15 to 7)
        assertEquals(10 to 1, SleepHeadline.wakefulness(n.stages))
    }

    @Test
    fun `back-to-back awake stages are one wake-up, and the gap stage is ignored`() {
        val n = night(100 to 4, 5 to 7, 5 to 7, 10 to 128, 100 to 5)
        assertEquals(10 to 1, SleepHeadline.wakefulness(n.stages))
    }

    @Test
    fun `duration and continuity win over the score`() {
        val calm = night(400 to 4)
        assertEquals(Kind.SHORT, kind(calm, 330.0))
        val broken = night(200 to 4, 35 to 7, 200 to 5, score = 95)
        assertEquals(Kind.BROKEN, kind(broken, 400.0))
        assertEquals(Kind.SHORT_BROKEN, kind(broken, 330.0))
        val restless = night(*Array(6) { listOf(60 to 4, 2 to 7) }.flatMap { it }.toTypedArray(), 60 to 4)
        assertEquals(Kind.BROKEN, kind(restless, 420.0))
    }

    @Test
    fun `then the strap's score, then the stages`() {
        assertEquals(Kind.GREAT, kind(night(450 to 4, score = 90), 450.0))
        assertEquals(Kind.GOOD, kind(night(450 to 4, score = 80), 450.0))
        assertEquals(Kind.OKAY, kind(night(450 to 4, score = 72), 450.0))
        assertEquals(Kind.LIGHT, kind(night(450 to 4, score = 72, deep = 40, rem = 60), 450.0))
        assertEquals(Kind.OKAY, kind(night(450 to 4, score = null), 450.0))
    }

    @Test
    fun `the strap's score reads in Zepp's bands`() {
        assertEquals(listOf("Excellent", "Good", "Fair", "Poor"), listOf(90, 80, 60, 59).map(SleepHeadline::scoreWord))
    }
}
