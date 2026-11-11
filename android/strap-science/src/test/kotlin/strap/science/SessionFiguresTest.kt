package strap.science

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keytel's estimate and the figures a session without the strap's own gets (issue #4, D47). */
class SessionFiguresTest {
    @Test
    fun `Keytel by sex, against hand-worked minutes`() {
        // Male, 140 bpm, 80 kg, 30 y: (-55.0969 + 88.326 + 15.904 + 6.051) / 4.184 = 13.189 kcal a minute.
        assertEquals(13.189, Keytel.kcal(listOf(140.0), 80.0, 30, "male")!!, 0.001)
        // Female, 140 bpm, 60 kg, 30 y: (-20.4022 + 62.608 - 7.578 + 2.22) / 4.184 = 8.807.
        assertEquals(8.807, Keytel.kcal(listOf(140.0), 60.0, 30, "female")!!, 0.001)
        assertEquals(2 * 13.189, Keytel.kcal(listOf(140.0, 140.0), 80.0, 30, "male")!!, 0.002)
    }

    @Test
    fun `a minute the equation puts under zero counts as zero`() {
        assertEquals(0.0, Keytel.kcal(listOf(40.0), 50.0, 20, "male")!!, 0.0)
        assertEquals(13.189, Keytel.kcal(listOf(40.0, 140.0), 80.0, 30, "male")!!, 0.001)
    }

    @Test
    fun `no estimate for a sex the equation has no coefficients for`() {
        assertNull(Keytel.kcal(listOf(140.0), 80.0, 30, "other"))
    }

    private val input = Golden.read("demo/input.json.gz").jsonObject
    private val now = Instant.ofEpochMilli(input.getValue("now").jsonPrimitive.long)
    private val data = Golden.data(input, now = { now })
    private val science = Science(data, MemoryDerived().also { Science(data, it).rederive() })

    @Test
    fun `a session's heart rate is its per-minute stream's, with an estimate`() {
        val today = data.zones.dateOf(now)
        val w = data.workouts(science.zones.bounds(today.minusDays(40)).first, now).first()
        val f = assertNotNull(science.sessionFigures(w.start, w.durationS))
        val minutes = science.heartRate(w.start, w.end).map { it.value }
        assertEquals(minutes.average().roundToInt(), f.avgHr)
        assertEquals(minutes.max().roundToInt(), f.maxHr)
        assertTrue((f.estKcal ?: 0) > 0, "the demo person has a profile and a weight")
    }

    @Test
    fun `no figures for a window without heart rate`() {
        assertNull(science.sessionFigures(now.plusSeconds(86_400), 3600))
    }
}
