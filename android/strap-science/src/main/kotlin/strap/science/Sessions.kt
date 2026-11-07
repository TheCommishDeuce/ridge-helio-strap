package strap.science

import java.time.Instant

/**
 * One change the owner made to the workout list (issue #4, D43): a session added by hand, a strap
 * workout edited (its sport or window) or hidden. Kept beside the strap's records, never written to
 * the strap. For a hide, [start]..[end] is the hidden workout's window.
 */
public data class SessionOverlay(
    val id: String,
    val action: Action,
    /** The strap workout's own start, for an edit or a hide; null for an added session. */
    val target: Instant?,
    val start: Instant,
    val end: Instant,
    val sport: Int,
) {
    public enum class Action { ADD, EDIT, HIDE }
}

public object Sessions {
    /**
     * The workouts as the owner says they were: [strap] with each edit applied and each hidden one
     * left out, then the added sessions. A strap workout that overlaps an added session is that
     * session (D41's openGym rule), so it is not counted twice. An edit that moves the window drops
     * the strap's calories and heart-rate summary, which describe the old window; the minutes then
     * count as any other (calories from movement, heart rate from the per-minute stream).
     */
    public fun apply(strap: List<Workout>, overlays: List<SessionOverlay>): List<Workout> {
        if (overlays.isEmpty()) return strap.map { it.copy(strapStart = it.start) }
        val onTarget = overlays.filter { it.target != null }.associateBy { it.target }
        val added = overlays.filter { it.action == SessionOverlay.Action.ADD && it.end > it.start }
        val changed = strap.mapNotNull { w ->
            val o = onTarget[w.start]
            when (o?.action) {
                SessionOverlay.Action.HIDE -> null
                SessionOverlay.Action.EDIT -> {
                    val sameWindow = o.start == w.start && o.end == w.end
                    w.copy(
                        start = o.start, sport = o.sport, durationS = seconds(o.start, o.end),
                        calories = w.calories.takeIf { sameWindow }, avgHr = w.avgHr.takeIf { sameWindow },
                        maxHr = w.maxHr.takeIf { sameWindow }, minHr = w.minHr.takeIf { sameWindow },
                        origin = Workout.Origin.EDITED, overlayId = o.id, strapStart = w.start,
                    )
                }
                else -> w.copy(strapStart = w.start)
            }
        }.filter { w -> added.none { a -> w.start < a.end && w.end > a.start } }
        val sessions = added.map { a ->
            Workout(a.start, a.sport, seconds(a.start, a.end), origin = Workout.Origin.ADDED, overlayId = a.id)
        }
        return (changed + sessions).sortedBy { it.start }
    }

    private fun seconds(start: Instant, end: Instant): Int = ((end.epochSecond - start.epochSecond).coerceAtLeast(0)).toInt()
}
