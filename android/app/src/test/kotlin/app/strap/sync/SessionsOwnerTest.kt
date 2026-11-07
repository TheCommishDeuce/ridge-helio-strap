package app.strap.sync

import app.strap.FakeServer
import app.strap.Phone
import app.strap.PhoneTest
import app.strap.ZONE
import app.strap.at
import app.strap.sync
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import strap.protocol.model.Workout as StrapWorkout
import strap.science.SessionOverlay
import strap.science.Workout
import strap.science.Zones
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner's workout changes (D43) on the phone: kept beside the strap's record, applied, backed up, restored. */
class SessionsOwnerTest : PhoneTest() {
    private val climbStart = at("2026-11-04", "18:10")

    private suspend fun Phone.withClimb(): Phone {
        store.save(sync(at("2026-11-04", "21:00"), workouts = listOf(StrapWorkout(climbStart, 10, 600, 80, 120, 150, 90, subtype = 1))), 80)
        owner.seed()
        return this
    }

    private fun Phone.workouts(): List<Workout> = store.scienceData(Zones(ZONE), null, emptyList()).workouts(at("2026-11-01"), at("2026-11-10"))

    @Test
    fun `an edit stretches the strap's workout on the phone and goes up pointing at it`() = runTest {
        val server = FakeServer()
        val phone = Phone(server).withClimb()
        val climb = phone.workouts().single()
        val touched = phone.owner.editWorkout(climb, 70, at("2026-11-04", "17:00"), at("2026-11-04", "19:00"))
        assertEquals(at("2026-11-04", "17:00"), touched)

        val edited = phone.workouts().single()
        assertEquals(Workout.Origin.EDITED, edited.origin)
        assertEquals(70, edited.sport)
        assertEquals(7200, edited.durationS)
        assertNull(edited.calories)

        assertEquals(1, phone.owner.unpushed())
        assertNull(phone.owner.push())
        val sent = server.addedSessions.single()
        assertEquals("edit", sent.getString("action"))
        assertEquals(70, sent.getInt("sport"))
        assertEquals(climbStart.toEpochMilli(), java.time.OffsetDateTime.parse(sent.getString("target")).toInstant().toEpochMilli())
        assertEquals(0, phone.owner.unpushed())

        // Taking the edit back shows the strap's record and deletes the server's copy.
        phone.owner.undoSession(edited.overlayId!!)
        assertEquals(climbStart, phone.workouts().single().start)
        assertNull(phone.owner.push())
        assertEquals(listOf("ses-1"), server.deletedSessions)
    }

    @Test
    fun `a session added around a strap workout is that workout, and deleting it before upload sends nothing`() = runTest {
        val server = FakeServer()
        val phone = Phone(server).withClimb()
        phone.owner.addSession(70, at("2026-11-04", "17:00"), at("2026-11-04", "19:00"))
        val only = phone.workouts().single()
        assertEquals(Workout.Origin.ADDED, only.origin)
        assertEquals(at("2026-11-04", "17:00"), only.start)

        phone.owner.hideWorkout(only)
        assertEquals(climbStart, phone.workouts().single().start)
        assertNull(phone.owner.push())
        assertTrue(server.addedSessions.isEmpty())
    }

    @Test
    fun `a hidden workout is gone until shown again`() = runTest {
        val phone = Phone(FakeServer()).withClimb()
        phone.owner.hideWorkout(phone.workouts().single())
        assertTrue(phone.workouts().isEmpty())
        val hide = phone.owner.sessions(at("2026-11-04", "00:00"), at("2026-11-05", "00:00")).single()
        assertEquals(SessionOverlay.Action.HIDE, hide.action)
        assertEquals(climbStart.plusSeconds(600), hide.end)
        phone.owner.undoSession(hide.id)
        assertEquals(1, phone.workouts().size)
    }

    @Test
    fun `a restore brings the changes back, already uploaded`() = runTest {
        val server = FakeServer(records = JSONObject().put("sleep", JSONArray()).put("daily_totals", JSONArray())
            .put("timezone", ZONE).put("zone_changes", JSONArray()).put("weights", JSONArray()).put("journal", JSONArray())
            .put("workouts", JSONArray().put(JSONObject().put("start_ts", climbStart.toEpochMilli()).put("sport", 0).put("duration_s", 600)))
            .put("sessions", JSONArray().put(JSONObject().put("id", "srv-a").put("action", "edit").put("target", climbStart.toEpochMilli())
                .put("start", at("2026-11-04", "17:00").toEpochMilli()).put("end", at("2026-11-04", "19:00").toEpochMilli()).put("sport", 70))))
        val phone = Phone(server)
        assertNull(phone.restore.run { })
        val w = phone.workouts().single()
        assertEquals(70, w.sport)
        assertEquals(Workout.Origin.EDITED, w.origin)
        assertEquals(0, phone.owner.unpushed())
    }
}
