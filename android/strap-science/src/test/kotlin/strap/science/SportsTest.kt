package strap.science

import kotlin.test.Test
import kotlin.test.assertEquals

class SportsTest {
    @Test
    fun `a listed code is named, an unlisted one shows its number, none is a plain workout`() {
        assertEquals("Strength training", Sports.label(52))
        assertEquals("Climbing", Sports.label(10))
        assertEquals("Workout (code 37)", Sports.label(37))
        assertEquals("Workout", Sports.label(Sports.UNKNOWN))
    }

    @Test
    fun `kinds pick the icon`() {
        assertEquals(Sports.Kind.RIDE, Sports.kind(4))
        assertEquals(Sports.Kind.CLIMB, Sports.kind(70))
        assertEquals(Sports.Kind.OTHER, Sports.kind(Sports.UNKNOWN))
    }

    @Test
    fun `biological age's strength codes are the table's strength and core`() {
        assertEquals(setOf("Strength training", "Core training"), BioAge.STRENGTH_SPORTS.map(Sports::label).toSet())
    }
}
