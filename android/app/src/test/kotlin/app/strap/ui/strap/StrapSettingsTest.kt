package app.strap.ui.strap

import strap.protocol.parse.Config.Health
import strap.protocol.parse.Config.Workout
import strap.protocol.parse.ConfigGroup
import strap.protocol.parse.ConfigValue
import kotlin.test.Test
import kotlin.test.assertEquals

class StrapSettingsTest {
    private val health = ConfigGroup(Health.GROUP, 3, linkedMapOf(
        Health.SPO2_LOW_ALERT to ConfigValue.Choice(85, listOf(0, 80, 85, 90)),
        Health.STRESS to ConfigValue.Flag(true),
        0x16 to ConfigValue.Flag(true), // a key we cannot name
        Health.HR_HIGH_ALERT to ConfigValue.Choice(150, listOf(0, 100, 110, 120, 130, 140, 150)),
        Health.HR_INTERVAL to ConfigValue.Choice(0xfe, listOf(0, 0xfe, 1, 5, 10, 30)),
        Health.SPO2_ALL_DAY to ConfigValue.Flag(false),
    ), complete = true)
    private val workout = ConfigGroup(Workout.GROUP, 1, linkedMapOf(
        Workout.HR_ZONES to ConfigValue.Numbers(listOf(80, 100, 120, 140, 160, 180), 30, 220),
        Workout.DETECTION_CATEGORIES to ConfigValue.Choices(listOf(223), listOf(223)),
        Workout.DETECTION_SENSITIVITY to ConfigValue.Choice(1, listOf(0, 1, 2)),
        0x20 to ConfigValue.Choice(0, emptyList()),
    ), complete = true)

    @Test
    fun `settings are grouped by what they are about, a section with none left out`() {
        assertEquals(
            listOf(
                "Heart rate" to listOf("Readings", "High alert"),
                "Stress" to listOf("Stress monitoring"),
                "Blood oxygen" to listOf("All-day SpO₂", "Low alert, %"),
                "Workouts" to listOf("Detect workouts", "Detection sensitivity"),
            ),
            strapSections(health, workout).map { s -> s.title to s.rows.map { it.label } },
        )
    }

    @Test
    fun `each value gets the control that fits it`() {
        val rows = strapSections(health, workout).flatMap { it.rows }.associate { it.label to (it.control to it.text) }
        assertEquals(Control.LIST to "Continuous", rows["Readings"]) // six values
        assertEquals(Control.LIST to "Above 150 bpm", rows["High alert"])
        assertEquals(Control.BUTTONS to "Below 85 %", rows["Low alert, %"]) // four values
        assertEquals(Control.BUTTONS to "Standard", rows["Detection sensitivity"])
        assertEquals(Control.SWITCH to "On", rows["Stress monitoring"])
        assertEquals(Control.SWITCH to "Off", rows["All-day SpO₂"])
        assertEquals(Control.SWITCH to "On", rows["Detect workouts"]) // a category list, shown as on or off
        assertEquals(null, rows["Heart-rate zones, bpm"]) // the strap's own zones: Ridge uses neither them nor shows them
    }

    @Test
    fun `a button says the value alone, a list says it in words`() {
        assertEquals(listOf("Off", "80", "85", "90"), listOf(0, 80, 85, 90).map { buttonName(0, Health.SPO2_LOW_ALERT, it) })
        assertEquals(listOf("High", "Standard", "Low"), listOf(0, 1, 2).map { buttonName(1, Workout.DETECTION_SENSITIVITY, it) })
        assertEquals(listOf("Off", "Continuous", "Every 1 min", "Every 5 min"), listOf(0, 0xfe, 1, 5).map { choiceName(0, Health.HR_INTERVAL, it) })
        assertEquals(listOf("Off", "Below 45 bpm"), listOf(0, 45).map { choiceName(0, Health.HR_LOW_ALERT, it) })
    }

    @Test
    fun `no groups, no sections`() = assertEquals(emptyList(), strapSections(null, null))
}
