package app.strap.ui.strap

import strap.protocol.parse.Config
import strap.protocol.parse.Config.Health
import strap.protocol.parse.Config.Workout
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.ConfigValue

/** How a strap setting is changed on the page, so each kind looks like what it is. */
internal enum class Control {
    /** On or off. */
    SWITCH,

    /** A few values, all on show: one tap picks. */
    BUTTONS,

    /** More values than fit in a row: opens a list. */
    LIST,

    /** Shown as the strap holds it. */
    READ_ONLY,
}

/** One setting the strap reported. [group] is its place in the page's read: 0 health, 1 workout. */
internal data class StrapSetting(
    val group: Int,
    val key: Int,
    val label: String,
    /** What it does, under the label. */
    val hint: String?,
    val value: ConfigValue,
    /** The value in words, for a list row or a read-only one. */
    val text: String,
    val control: Control,
)

internal data class SettingSection(val title: String, val rows: List<StrapSetting>)

private const val HEALTH = 0
private const val WORKOUT = 1

/** Sections in the page's order, each row with the hint under its label. */
private val LAYOUT: List<Pair<String, List<Triple<Int, Int, Pair<String, String?>>>>> = listOf(
    "Heart rate" to listOf(
        Triple(HEALTH, Health.HR_INTERVAL, "Readings" to "How often the strap measures. Ridge's numbers need Continuous."),
        Triple(HEALTH, Health.HR_DURING_ACTIVITY, "More often during activity" to "Measures more often while you move."),
        Triple(HEALTH, Health.HR_HIGH_ALERT, "High alert" to "The strap vibrates when your heart rate at rest stays above this."),
        Triple(HEALTH, Health.HR_LOW_ALERT, "Low alert, bpm" to "The strap vibrates when your heart rate at rest stays below this."),
        Triple(HEALTH, Health.HR_BROADCAST, "Share with other devices" to "Lets a bike computer or gym machine read your heart rate."),
    ),
    "Sleep" to listOf(
        Triple(HEALTH, Health.SLEEP_HIGH_ACCURACY, "High-accuracy sleep" to "Finer sleep stages, for more battery."),
        Triple(HEALTH, Health.SLEEP_BREATHING, "Breathing quality" to "Watches your breathing through the night."),
    ),
    "Stress" to listOf(
        Triple(HEALTH, Health.STRESS, "Stress monitoring" to "Measured through the day."),
        Triple(HEALTH, Health.STRESS_RELAX_REMINDER, "Relaxation reminder" to "The strap vibrates when stress stays high."),
    ),
    "Blood oxygen" to listOf(
        Triple(HEALTH, Health.SPO2_ALL_DAY, "All-day SpO₂" to "Measured through the day, not only asleep."),
        Triple(HEALTH, Health.SPO2_LOW_ALERT, "Low alert, %" to "The strap vibrates when your blood oxygen stays below this."),
    ),
    "Workouts" to listOf(
        Triple(WORKOUT, Workout.DETECTION_CATEGORIES, "Detect workouts" to "The strap recognises a workout by itself."),
        Triple(WORKOUT, Workout.DETECTION_SENSITIVITY, "Detection sensitivity" to "Higher notices more workouts, and more that weren't."),
        Triple(WORKOUT, Workout.DETECTION_ALERT, "Alert when detected" to "The strap vibrates when it recognises a workout."),
    ),
)

/** A choice with at most this many values shows them all as buttons; more open a list. */
internal const val MAX_BUTTONS = 4

/**
 * The strap's settings as the page lays them out: by what they are about, each with the control
 * that fits its value. Only settings the strap reported and we can name; a section with none is left out.
 */
internal fun strapSections(health: ConfigGroup?, workout: ConfigGroup?): List<SettingSection> = LAYOUT.mapNotNull { (title, rows) ->
    SettingSection(title, rows.mapNotNull { (group, key, words) ->
        val v = (if (group == HEALTH) health else workout)?.values?.get(key) ?: return@mapNotNull null
        val (label, hint) = words
        when {
            group == WORKOUT && key == Workout.DETECTION_CATEGORIES ->
                (v as? ConfigValue.Choices)?.let { StrapSetting(group, key, label, hint, v, if (v.values.isNotEmpty()) "On" else "Off", Control.SWITCH) }
            v is ConfigValue.Flag -> StrapSetting(group, key, label, hint, v, if (v.on) "On" else "Off", Control.SWITCH)
            v is ConfigValue.Choice -> StrapSetting(group, key, label, hint, v, choiceName(group, key, v.value), when {
                v.options.isEmpty() -> Control.READ_ONLY
                v.options.size <= MAX_BUTTONS -> Control.BUTTONS
                else -> Control.LIST
            })
            else -> null
        }
    }).takeIf { it.rows.isNotEmpty() }
}

/** A choice's [value] in words, as a list offers it. */
internal fun choiceName(group: Int, key: Int, value: Int): String = when {
    group == WORKOUT -> if (key == Workout.DETECTION_SENSITIVITY) sensitivityName(value) else value.toString()
    key == Health.HR_INTERVAL -> when (val i = Health.hrInterval(value)) {
        Config.HrInterval.Off -> "Off"
        Config.HrInterval.Smart -> "Smart"
        Config.HrInterval.Continuous -> "Continuous"
        is Config.HrInterval.Every -> "Every ${i.minutes} min"
    }
    key == Health.HR_HIGH_ALERT -> if (value == 0) "Off" else "Above $value bpm"
    key == Health.HR_LOW_ALERT -> if (value == 0) "Off" else "Below $value bpm"
    key == Health.SPO2_LOW_ALERT -> if (value == 0) "Off" else "Below $value %"
    else -> value.toString()
}

/** The same value short enough for a button: the row's label carries the unit. */
internal fun buttonName(group: Int, key: Int, value: Int): String = when {
    group == WORKOUT && key == Workout.DETECTION_SENSITIVITY -> sensitivityName(value)
    group == HEALTH && key == Health.HR_INTERVAL -> choiceName(group, key, value).removePrefix("Every ")
    value == 0 -> "Off"
    else -> value.toString()
}

/** The strap's sensitivity byte: 0 high, 1 standard, 2 low (spec/01 "Config"). */
internal fun sensitivityName(v: Int): String = listOf("High", "Standard", "Low").getOrElse(v) { "Unknown ($v)" }
