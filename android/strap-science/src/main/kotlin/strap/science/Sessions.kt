package strap.science

import java.time.Instant
import java.time.LocalDate

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
     * [walks] are the starts of the strap workouts that were short, easy walks ([ShortWalks], D46):
     * left out like a hidden one, unless the owner's edit says it was a workout.
     */
    public fun apply(strap: List<Workout>, overlays: List<SessionOverlay>, walks: Set<Instant> = emptySet()): List<Workout> {
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
                else -> if (w.start in walks) null else w.copy(strapStart = w.start)
            }
        }.filter { w -> added.none { a -> w.start < a.end && w.end > a.start } }
        val sessions = added.map { a ->
            Workout(a.start, a.sport, seconds(a.start, a.end), origin = Workout.Origin.ADDED, overlayId = a.id)
        }
        return (changed + sessions).sortedBy { it.start }
    }

    private fun seconds(start: Instant, end: Instant): Int = ((end.epochSecond - start.epochSecond).coerceAtLeast(0)).toInt()
}

/**
 * A strap "workout" that was a short, easy walk (D46): the strap's detection is sensitive enough to
 * file a few minutes to the shop as one. Told by length, heart rate and steps, because the strap
 * gives these no sport of their own (they come as [Sports.ACTIVITY], like a climb does). A short
 * stretch without steps is left alone: it is as likely a piece of a session the strap cut up.
 */
public object ShortWalks {
    /** The walks the strap files as workouts are a few minutes long; a longer one may be meant. */
    public const val MAX_S: Int = 600

    /** Steps a minute over the whole window: a stop-and-go walk passes, effort without steps does not. */
    public const val MIN_CADENCE: Double = 30.0

    /** Of maximum heart rate, where Edwards zone 3 begins: an average under it is an easy effort. */
    public const val EASY_HR: Double = 0.7

    /** The average heart rate a walk stays under for [profile] on [day]; null without a date of birth. */
    public fun easyHr(profile: Profile?, day: LocalDate): Double? = profile?.dob?.let { EASY_HR * hrMax(age(it, day)) }

    /**
     * Whether [w], as the strap recorded it, with [steps] counted inside its window, was one.
     * Without the strap's average heart rate or [easyHr] nothing says it was easy, so it stays a workout.
     */
    public fun isOne(w: Workout, steps: Double, easyHr: Double?): Boolean {
        val avg = w.avgHr?.takeIf { it > 0 } ?: return false
        if (easyHr == null || w.durationS <= 0 || w.durationS >= MAX_S) return false
        return avg < easyHr && steps / (w.durationS / 60.0) >= MIN_CADENCE
    }
}
