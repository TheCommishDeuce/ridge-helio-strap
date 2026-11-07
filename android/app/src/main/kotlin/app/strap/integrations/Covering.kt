package app.strap.integrations

import strap.science.Workout
import java.time.Instant

/**
 * One activity, one row (D44): a strap workout that overlaps something the owner recorded
 * elsewhere (an openGym session, a Dawarich walk, run or ride) is shown as that activity, which
 * lists the strap's pieces inside it. Only what is shown changes: load, strain and calories
 * still come from the strap's workouts.
 */
object Covering {
    /** Overlapping at all, as D41 already folded openGym sessions. */
    fun overlaps(w: Workout, start: Instant, end: Instant): Boolean = w.start.isBefore(end) && w.end.isAfter(start)

    fun inside(workouts: List<Workout>, start: Instant, end: Instant): List<Workout> = workouts.filter { overlaps(it, start, end) }

    /** The workouts no window covers: the ones still shown as the strap's own. */
    fun uncovered(workouts: List<Workout>, windows: List<Pair<Instant, Instant>>): List<Workout> =
        workouts.filter { w -> windows.none { (a, b) -> overlaps(w, a, b) } }

    fun gymWindows(sessions: List<GymSession>): List<Pair<Instant, Instant>> = sessions.map { it.start to it.end }

    fun trackWindows(tracks: List<Track>): List<Pair<Instant, Instant>> = tracks.map { it.start to it.end }

    /** The tracks holding at least one strap workout: a walk or ride the strap saw as training. */
    fun trained(tracks: List<Track>, workouts: List<Workout>): List<Track> = tracks.filter { t -> inside(workouts, t.start, t.end).isNotEmpty() }
}
