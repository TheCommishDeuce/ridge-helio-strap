package strap.science

import strap.science.SessionOverlay.Action
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionsTest {
    private val t0 = Instant.parse("2026-11-04T17:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private val climb = Workout(at(30), sport = 0, durationS = 600, calories = 80, avgHr = 120, maxHr = 150, minHr = 90)
    private val ride = Workout(at(300), sport = 4, durationS = 3600, calories = 500, avgHr = 130)

    @Test
    fun `no changes keep the strap's workouts and mark their own start`() {
        val out = Sessions.apply(listOf(climb), emptyList())
        assertEquals(listOf(climb.copy(strapStart = climb.start)), out)
    }

    @Test
    fun `an edit stretches the window, takes the sport, and drops figures of the old window`() {
        val edit = SessionOverlay("e1", Action.EDIT, climb.start, at(0), at(120), sport = 70)
        val w = Sessions.apply(listOf(climb, ride), listOf(edit)).first()
        assertEquals(at(0), w.start)
        assertEquals(7200, w.durationS)
        assertEquals(70, w.sport)
        assertEquals(Workout.Origin.EDITED, w.origin)
        assertEquals("e1", w.overlayId)
        assertEquals(climb.start, w.strapStart)
        assertNull(w.calories)
        assertNull(w.avgHr)
    }

    @Test
    fun `an edit of the sport alone keeps the strap's figures`() {
        val edit = SessionOverlay("e1", Action.EDIT, climb.start, climb.start, climb.end, sport = 10)
        val w = Sessions.apply(listOf(climb), listOf(edit)).single()
        assertEquals(10, w.sport)
        assertEquals(80, w.calories)
        assertEquals(120, w.avgHr)
    }

    @Test
    fun `a hidden workout is gone`() {
        val hide = SessionOverlay("h1", Action.HIDE, climb.start, climb.start, climb.end, sport = 0)
        assertEquals(listOf(ride.start), Sessions.apply(listOf(climb, ride), listOf(hide)).map { it.start })
    }

    @Test
    fun `an added session replaces a strap workout inside it, once`() {
        val add = SessionOverlay("a1", Action.ADD, null, at(0), at(120), sport = 70)
        val out = Sessions.apply(listOf(climb, ride), listOf(add))
        assertEquals(listOf(at(0), ride.start), out.map { it.start })
        val s = out.first()
        assertEquals(Workout.Origin.ADDED, s.origin)
        assertEquals(7200, s.durationS)
        assertNull(s.calories)
        assertNull(s.strapStart)
    }

    @Test
    fun `an edit pointing at a workout the phone doesn't hold changes nothing`() {
        val edit = SessionOverlay("e1", Action.EDIT, at(999), at(0), at(10), sport = 1)
        assertEquals(listOf(climb.start), Sessions.apply(listOf(climb), listOf(edit)).map { it.start })
    }
}
