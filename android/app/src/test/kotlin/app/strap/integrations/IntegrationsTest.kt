package app.strap.integrations

import app.strap.Phone
import app.strap.PhoneTest
import app.strap.at
import app.strap.pairing.DawarichLink
import app.strap.pairing.OpenGymLink
import org.json.JSONObject
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** openGym and Dawarich as spec/03 records them. The fixtures are made up, in the shapes the live servers sent. */
class IntegrationsTest : PhoneTest() {
    private val openGymState = """
        {"rev": 12, "state": {"unit": "kg", "_rev": 12, "active": null, "deleted": {"routines": []},
          "customEx": [{"id": "c1", "name": "Sled push"}],
          "workouts": [
            {"id": "w-new", "d": "2026-10-05", "start": 1791190800000, "end": 1791193800000, "name": "Push Day", "routineId": "r1",
             "vol": 4200, "bw": 80, "prs": ["0025"],
             "entries": [
               {"id": "0025", "rid": null, "planned": null, "target": null, "topW": 80, "sets": [
                 {"w": 40, "r": 10, "done": true, "warmup": true, "phase": "warmup", "at": 1791190920000},
                 {"w": 80, "r": 6, "done": true, "at": 1791191100000},
                 {"w": 80, "r": 5, "done": false}
               ]},
               {"id": "c1", "sets": [{"min": 12, "speed": 6.5, "done": true, "at": 1791193500000}]}
             ]},
            {"id": "w-old", "d": "2026-07-21", "start": 1784620800000, "end": 1784624400000, "name": "Pull Day",
             "entries": [{"id": "0027", "sets": [{"w": 60, "r": 8, "done": true, "rir": 2}]}]},
            {"id": "broken", "start": "not a time"},
            {"id": "backwards", "start": 1784624400000, "end": 1784620800000, "entries": []}
          ]}}
    """.trimIndent()

    private val dawarichTracks = """
        {"type": "FeatureCollection", "features": [
          {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[13.40, 52.52], [13.41, 52.53], [13.42, 52.52]]},
           "properties": {"id": 7, "start_at": "2026-10-05T14:01:55+02:00", "end_at": "2026-10-05T14:52:26+02:00", "distance": 5057,
             "duration": 3031, "avg_speed": 6.01, "dominant_mode": "cycling", "dominant_mode_emoji": "🚴",
             "mode_timeline": [{"start_time": 1791201715, "end_time": 1791202440, "emoji": "🚴"}], "color": "#6366F1", "revision": 3}},
          {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[13.40, 52.52], [200, 52.52]]},
           "properties": {"id": 8, "start_at": "2026-10-05T18:00:00+02:00", "end_at": "2026-10-05T18:30:00+02:00", "distance": 2100,
             "duration": 1800, "dominant_mode": "walking"}},
          {"type": "Feature", "geometry": {"type": "LineString", "coordinates": []},
           "properties": {"id": 9, "start_at": "2026-10-05T19:00:00+02:00", "end_at": "2026-10-05T19:20:00+02:00", "dominant_mode": "driving"}},
          {"type": "Feature", "properties": {"id": 10, "start_at": "yesterday"}}
        ]}
    """.trimIndent()

    @Test
    fun `openGym workouts parse with set times, warm-ups and timed sets, and broken ones are skipped`() {
        val (sessions, custom) = OpenGymFormat.parse(JSONObject(openGymState))
        assertEquals(listOf("w-new", "w-old"), sessions.map { it.id })
        assertEquals(mapOf("c1" to "Sled push"), custom)
        val g = sessions[0]
        assertEquals(Instant.ofEpochMilli(1791190800000), g.start)
        assertEquals("kg" to 4200.0, g.unit to g.volume)
        assertTrue(g.timed)
        val bench = g.entries[0]
        assertEquals("0025", bench.exercise)
        assertTrue(bench.sets[0].warmup)
        assertEquals(80.0 to 6, bench.sets[1].weight to bench.sets[1].reps)
        assertFalse(bench.sets[2].done)
        assertNull(bench.sets[2].at)
        assertEquals(Instant.ofEpochMilli(1791190920000) to Instant.ofEpochMilli(1791191100000), bench.span)
        val sled = g.entries[1].sets.single()
        assertEquals(12.0 to 6.5, sled.minutes to sled.speed)
        assertNull(sled.weight)
        // An older openGym recorded no set times: the session is shown whole.
        assertFalse(sessions[1].timed)
        assertEquals(2.0, sessions[1].entries[0].sets[0].rir)
    }

    @Test
    fun `the store keeps a replaced copy, so edits and deletions in openGym follow`() {
        val store = Phone().store
        val (sessions, _) = OpenGymFormat.parse(JSONObject(openGymState))
        store.replaceGym(sessions)
        val back = store.gymSession("w-new")!!
        assertEquals(sessions[0], back) // entries survive the round trip through the phone's JSON
        assertEquals(listOf("w-new"), store.gymSessions(at("2026-10-05", "00:00"), at("2026-10-06", "00:00")).map { it.id })

        store.replaceGym(sessions.drop(1)) // deleted in openGym
        assertNull(store.gymSession("w-new"))
        assertEquals(listOf("w-old"), store.gymSessions(Instant.EPOCH, Instant.now()).map { it.id })
        store.clearGym()
        assertTrue(store.gymSessions(Instant.EPOCH, Instant.now()).isEmpty())
    }

    @Test
    fun `Dawarich tracks parse with their own distance, and bad points and features are dropped`() {
        val tracks = DawarichFormat.tracks(JSONObject(dawarichTracks))
        assertEquals(listOf(7L, 8L, 9L), tracks.map { it.id })
        val ride = tracks[0]
        assertEquals("cycling" to 5057, ride.mode to ride.distanceM)
        assertEquals(Instant.parse("2026-10-05T12:01:55Z"), ride.start)
        assertEquals(3031, ride.durationS)
        assertEquals(LatLon(52.53, 13.41), ride.path[1]) // GeoJSON is [lon, lat]
        assertEquals(1, tracks[1].path.size) // a longitude of 200 is no place
        assertEquals(setOf("cycling", "walking"), tracks.map { it.mode }.filter { it in DawarichFormat.SHOWN }.toSet()) // driving is left
    }

    /** Made-up fixes two minutes apart from 10:00, each step [metres] long; 0.001° of latitude is 111.2 m. */
    private fun fixesOf(vararg metres: Double): List<Fix> {
        val t0 = Instant.parse("2026-10-05T10:00:00Z")
        var lat = 52.30
        val points = org.json.JSONArray()
        points.put(JSONObject().put("latitude", "$lat").put("longitude", "4.90").put("timestamp", t0.epochSecond).put("ssid", "kept out"))
        metres.forEachIndexed { i, m ->
            lat += m / 111_195.0
            points.put(JSONObject().put("latitude", "$lat").put("longitude", "4.90").put("timestamp", t0.epochSecond + 120L * (i + 1)))
        }
        points.put(JSONObject().put("latitude", null as String?).put("longitude", "4.90").put("timestamp", t0.epochSecond + 9999)) // no position: dropped
        return DawarichFormat.fixes(points)
    }

    private fun minute(m: Long) = Instant.parse("2026-10-05T10:00:00Z").plusSeconds(60 * m)

    @Test
    fun `the strap decides how a track was moved - steps walk or run, a recorded workout rides, the rest was carried`() {
        // 4 km/h, then 30 km/h for two steps, then 4 km/h: a walk with a tram in it, as Dawarich's "ride".
        val fixes = fixesOf(133.0, 133.0, 1000.0, 1000.0, 133.0)
        assertEquals(6, fixes.size)
        val walking = (0L..3L).associate { minute(it) to 100.0 } + (8L..10L).associate { minute(it) to 95.0 } // no steps on the tram
        val tram = DawarichFormat.split(fixes, walking, workouts = emptyList())!!
        assertEquals(listOf(Moved.WALKED, Moved.CARRIED, Moved.WALKED), tram.legs.map { it.moved })
        assertEquals(minute(4) to minute(8), tram.carried.single().let { it.start to it.end })
        assertEquals(399.0, tram.ownM.toDouble(), 2.0)
        assertEquals(0, tram.rodeM)
        assertEquals(360, tram.ownS)
        assertEquals(Moved.WALKED, tram.moved)
        tram.legs.zipWithNext().forEach { (a, b) -> assertEquals(a.path.last(), b.path.first()) } // the legs join

        // The same fast stretch inside a workout the strap recorded was riding.
        val ride = DawarichFormat.split(fixes, walking, workouts = listOf(minute(3) to minute(9)))!!
        assertEquals(listOf(Moved.WALKED, Moved.RODE, Moved.WALKED), ride.legs.map { it.moved })
        assertEquals(2000.0, ride.rodeM.toDouble(), 2.0)
        assertEquals(Moved.RODE, ride.moved)

        // Walking-level steps win over a fast step (a GPS jump), and with no steps at all speed decides.
        assertEquals(listOf(Moved.WALKED), DawarichFormat.split(fixes, (0L..10L).associate { minute(it) to 100.0 }, emptyList())!!.legs.map { it.moved })
        assertEquals(listOf(Moved.WALKED, Moved.CARRIED, Moved.WALKED), DawarichFormat.split(fixes, emptyMap(), emptyList())!!.legs.map { it.moved })
        assertNull(DawarichFormat.split(fixes.take(1), walking, emptyList()))

        // A run: 2.5 km in 2 min steps is 12 km/h at 165 steps a minute, between walking at 100 and 4 km/h.
        val run = fixesOf(133.0, 400.0, 400.0, 400.0, 133.0)
        val cadence = (0L..1L).associate { minute(it) to 100.0 } + (2L..7L).associate { minute(it) to 165.0 } + (8L..10L).associate { minute(it) to 100.0 }
        val ran = DawarichFormat.split(run, cadence, emptyList())!!
        assertEquals(listOf(Moved.WALKED, Moved.RAN, Moved.WALKED), ran.legs.map { it.moved })
        assertEquals(1200.0, ran.ranM.toDouble(), 2.0)
        assertEquals(Moved.RAN, ran.moved)
        // Brisk walking at a running cadence but walking speed stays a walk.
        assertEquals(listOf(Moved.WALKED), DawarichFormat.split(fixesOf(133.0, 133.0), (0L..4L).associate { minute(it) to 125.0 }, emptyList())!!.legs.map { it.moved })
        // A run inside a workout the strap recorded is still a run: steps come before workouts.
        assertEquals(Moved.RAN, DawarichFormat.split(run, cadence, listOf(minute(0) to minute(12)))!!.moved)

        // A tram standing at a stop between two fast steps, no steps on the strap: one carried leg, not two.
        val stop = fixesOf(133.0, 1000.0, 20.0, 1000.0, 133.0)
        val stepsAround = (0L..1L).associate { minute(it) to 100.0 } + (8L..10L).associate { minute(it) to 100.0 }
        assertEquals(listOf(Moved.WALKED, Moved.CARRIED, Moved.WALKED), DawarichFormat.split(stop, stepsAround, emptyList())!!.legs.map { it.moved })
        assertEquals(111_195.0, DawarichFormat.metres(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1.0)
    }

    @Test
    fun `links take https only and never print their secret`() {
        val token = "a".repeat(40)
        val gym = OpenGymLink.parse("gym.example.com/", token)!!
        assertEquals("https://gym.example.com", gym.baseUrl)
        assertFalse(token in gym.toString())
        assertNull(OpenGymLink.parse("http://gym.example.com", token))
        assertNull(OpenGymLink.parse("https://gym.example.com", "short"))
        assertNull(OpenGymLink.parse("https://gym.example.com", "has a space in it, so no"))
        val tracks = DawarichLink.parse("https://dawarich.example.com/sub", "k".repeat(64))!!
        assertEquals("https://dawarich.example.com/sub", tracks.baseUrl)
        assertFalse("k".repeat(64) in tracks.toString())
        assertNull(DawarichLink.parse("ftp://dawarich.example.com", "k".repeat(64)))
    }
}
