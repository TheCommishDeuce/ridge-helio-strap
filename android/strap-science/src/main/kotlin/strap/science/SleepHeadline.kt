package strap.science

// The night's headline (spec/02 §1.3, D38): what shaped it, in a few words, after OpenStrap 2.1.
// Duration and continuity first, because they are the night's most dependable numbers; then the
// strap's own score; stages last, because consumer staging agrees with the lab least.

public object SleepHeadline {
    /** The bottom of Zepp's duration bands. */
    internal const val SHORT_MIN = 360.0
    /** Where Zepp's continuity starts losing points. */
    internal const val BROKEN_WASO_MIN = 30
    /** About one an hour, set high because wake detection is the least reliable part of staging. */
    internal const val BROKEN_WAKEUPS = 6
    internal const val GREAT_SCORE = 90
    internal const val GOOD_SCORE = 80
    /** Adults usually spend 40–50 % of sleep in deep and REM. */
    internal const val LIGHT_SHARE = 0.25

    public enum class Kind(public val title: String) {
        SHORT_BROKEN("Short, broken night"),
        SHORT("Short night"),
        BROKEN("Broken sleep"),
        GREAT("Great night"),
        GOOD("Good night"),
        LIGHT("Light sleep"),
        OKAY("Okay night"),
    }

    /** The headline, with what it was read from: [wasoMin] awake after falling asleep, [wakeups] times. */
    public data class Headline(val kind: Kind, val asleepMin: Double, val wasoMin: Int, val wakeups: Int, val deepRemShare: Double?)

    /** Minutes awake between falling asleep and the last sleep, and how many separate times. */
    internal fun wakefulness(stages: List<Stage>): Pair<Int, Int> {
        val sorted = stages.filter { it.code != Energy.GAP_STAGE }.sortedBy { it.startMs }
        val asleep = sorted.filter { it.code != Sleep.AWAKE }
        if (asleep.isEmpty()) return 0 to 0
        val onset = asleep.first().startMs
        val last = asleep.last().endMs
        var waso = 0L
        var wakeups = 0
        var prevAwake = false
        for (st in sorted) {
            val awake = st.code == Sleep.AWAKE && st.startMs >= onset && st.endMs <= last
            if (awake) {
                waso += st.endMs - st.startMs
                if (!prevAwake) wakeups++
            }
            prevAwake = awake
        }
        return (waso / 60_000).toInt() to wakeups
    }

    public fun of(night: Night, asleepMin: Double): Headline {
        val (waso, wakeups) = wakefulness(night.stages)
        val deepRem = if (night.deepMin != null && night.remMin != null && asleepMin > 0) (night.deepMin + night.remMin) / asleepMin else null
        val short = asleepMin < SHORT_MIN
        val broken = waso >= BROKEN_WASO_MIN || wakeups >= BROKEN_WAKEUPS
        val score = night.deviceScore
        val kind = when {
            short && broken -> Kind.SHORT_BROKEN
            short -> Kind.SHORT
            broken -> Kind.BROKEN
            score != null && score >= GREAT_SCORE -> Kind.GREAT
            score != null && score >= GOOD_SCORE -> Kind.GOOD
            deepRem != null && deepRem < LIGHT_SHARE -> Kind.LIGHT
            else -> Kind.OKAY
        }
        return Headline(kind, asleepMin, waso, wakeups, deepRem)
    }

    /** The strap's 0–100 score in Zepp's own bands, the scale it was made on. */
    public fun scoreWord(score: Int): String = when {
        score >= GREAT_SCORE -> "Excellent"
        score >= GOOD_SCORE -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }
}
