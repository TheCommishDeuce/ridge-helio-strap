package strap.science

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A stretch of raised heart rate the strap opened no workout for (D51). */
public data class PossibleWorkout(val start: Instant, val end: Instant, val avgHr: Int, val maxHr: Int) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

/**
 * The strap's own detection can miss a long, steady effort: a hike can come through as no
 * workout at all. Ridge finds such stretches in the per-minute heart rate and
 * offers them; nothing is counted until the owner adds one as a session (D43).
 */
public object PossibleWorkouts {
    /** Shorter stretches are too easily an errand or a dash for a tram. */
    public const val MIN_MINUTES: Long = 45

    /** A pause this long (lunch on a trail, a viewpoint, a gap in the readings) does not end a stretch. */
    public const val GAP_MINUTES: Long = 40

    /** A stretch this close to a workout is that workout cut short. */
    private const val WORKOUT_MARGIN_MINUTES: Long = 10

    /** Minutes this close are one block of effort; a block under [MIN_BLOCK_MINUTES] is a burst. */
    private const val BLOCK_GAP_MINUTES: Long = 10
    private const val MIN_BLOCK_MINUTES: Long = 15

    /** Each minute is judged by the ten around it, so a single high reading or a few strides are not effort. */
    private const val HALF_WINDOW_MINUTES: Long = 5

    /** Of maximum heart rate: walking at least this hard, for long, is a hike rather than a stroll. */
    public const val WALKING_HR: Double = 0.55

    /** Steps a minute over the ten around it: walking steadily, not standing about. */
    public const val WALKING_CADENCE: Double = 50.0

    /** Of maximum heart rate: zone 2's floor (D48), effort whatever the steps (a ride, a climb). */
    public const val EFFORT_HR: Double = 0.60

    /**
     * The stretches in [hr] (one reading a minute) and [steps] (per minute) that hold, for
     * [MIN_MINUTES] or more of effort (pauses not counted), either heart rate at zone 2 ([EFFORT_HR] of [hrMax]) or steady walking
     * ([WALKING_CADENCE]) at [WALKING_HR] of it. A hike holds both for hours, while a busy day in
     * town reaches the same heart rate only in short bursts of walking (D51). Minutes inside [busy] (sleep, workouts, sessions
     * logged or hidden) are not judged, and a stretch within ten minutes of one of [workouts] is
     * left out: that is a workout the strap cut short, which the owner edits instead.
     */
    public fun find(
        hr: Map<Instant, Double>,
        steps: Map<Instant, Double>,
        busy: List<Pair<Instant, Instant>>,
        workouts: List<Pair<Instant, Instant>>,
        hrMax: Double,
    ): List<PossibleWorkout> {
        val minutes = hr.keys.sorted()
        if (minutes.isEmpty()) return emptyList()
        fun inside(t: Instant, wins: List<Pair<Instant, Instant>>) = wins.any { (s, e) -> s <= t && t < e }
        val half = HALF_WINDOW_MINUTES * 60
        val raised = ArrayList<Instant>()
        var lo = 0
        var hi = 0
        for (t in minutes) {
            while (minutes[lo] < t.minusSeconds(half)) lo++
            while (hi < minutes.size && minutes[hi] < t.plusSeconds(half)) hi++
            val around = (lo until hi).map { hr.getValue(minutes[it]) }.sorted()
            val median = if (around.size % 2 == 1) around[around.size / 2] else (around[around.size / 2 - 1] + around[around.size / 2]) / 2
            val cadence = (-HALF_WINDOW_MINUTES until HALF_WINDOW_MINUTES).sumOf { steps[t.plusSeconds(it * 60)] ?: 0.0 } / (2 * HALF_WINDOW_MINUTES)
            val effort = median >= EFFORT_HR * hrMax || (median >= WALKING_HR * hrMax && cadence >= WALKING_CADENCE)
            if (effort && !inside(t, busy)) raised += t
        }
        // Minutes a few apart make a block; a block too short to be more than a burst is dropped
        // before blocks are joined across a pause, so stray minutes never glue an outing together.
        fun chain(items: List<List<Instant>>, gap: Long): List<MutableList<Instant>> {
            val out = ArrayList<MutableList<Instant>>()
            for (item in items) {
                val last = out.lastOrNull()
                if (last != null && Duration.between(last.last(), item.first()).toMinutes() <= gap + 1) last += item else out += item.toMutableList()
            }
            return out
        }
        val blocks = chain(raised.map { listOf(it) }, BLOCK_GAP_MINUTES)
            .filter { Duration.between(it.first(), it.last()).toMinutes() + 1 >= MIN_BLOCK_MINUTES }
        // Joined, a run keeps which blocks it was made of: its effort is their minutes, not the pauses.
        val runs = ArrayList<MutableList<List<Instant>>>()
        for (b in blocks) {
            val last = runs.lastOrNull()
            if (last != null && Duration.between(last.last().last(), b.first()).toMinutes() <= GAP_MINUTES + 1) last += b else runs += mutableListOf<List<Instant>>(b)
        }
        val margin = Duration.ofMinutes(WORKOUT_MARGIN_MINUTES)
        return runs.mapNotNull { run ->
            val start = run.first().first()
            val end = run.last().last().plusSeconds(60)
            val effort = run.sumOf { Duration.between(it.first(), it.last()).toMinutes() + 1 }
            if (effort < MIN_MINUTES) return@mapNotNull null
            if (workouts.any { (s, e) -> s < end.plus(margin) && e > start.minus(margin) }) return@mapNotNull null
            val values = minutes.filter { it >= start && it < end }.map { hr.getValue(it) }
            PossibleWorkout(start, end, values.average().toInt(), values.max().toInt())
        }
    }
}

/**
 * The day's [PossibleWorkouts], judged against the owner's age-based maximum heart rate; none
 * without a date of birth. [logged] are windows the strap's workouts don't hold: openGym sessions
 * and hidden workouts.
 */
public fun Science.possibleWorkouts(day: LocalDate, logged: List<Pair<Instant, Instant>> = emptyList()): List<PossibleWorkout> {
    val prof = loadProfile(day) ?: return emptyList()
    val (start, end) = dayBounds(day)
    val workouts = data.workouts(start.minus(Duration.ofDays(1)), end).map { it.start to it.start.plusSeconds(it.durationS.toLong()) }
    val steps = perMinute("steps_per_minute", start, end, keep = { it > 0 && it < 250 }, sum = true)
    return PossibleWorkouts.find(hrPerMinute(start, end), steps, sleepWindows(start, end) + workouts + logged, workouts + logged, hrMax(age(prof.dob, day)))
}
