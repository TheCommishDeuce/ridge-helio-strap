package app.strap.sync

import app.strap.FakeServer
import app.strap.Phone
import app.strap.PhoneTest
import app.strap.ZONE
import app.strap.at
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import strap.science.Profile
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerTest : PhoneTest() {
    private fun serverWithHistory() = FakeServer(
        profileCopy = JSONObject()
            .put("profile", JSONObject().put("height_cm", 181.5).put("sex", "f").put("dob", "1988-02-29").put("srpa", JSONObject.NULL))
            .put("timezone", ZONE)
            .put("zone_changes", JSONArray().put(JSONObject().put("since", at("2026-09-10").toEpochMilli()).put("timezone", "Asia/Tbilisi")))
            .put("weights", JSONArray().put(JSONObject().put("ts", at("2026-09-01").toEpochMilli()).put("kg", 70.3))),
        journalEntries = JSONArray()
            .put(JSONObject().put("id", "s1").put("kind", "caffeine").put("ts", at("2026-09-02", "09:00").toEpochMilli()).put("amount", 80.0).put("unit", "mg").put("name", "espresso"))
            .put(JSONObject().put("id", "s2").put("kind", "weight").put("ts", at("2026-09-01").toEpochMilli()).put("amount", 70.3).put("unit", "kg").put("name", JSONObject.NULL)),
    )

    @Test
    fun `seed copies the server's profile, weights, journal and zones once`() = runTest {
        val server = serverWithHistory()
        val phone = Phone(server)
        assertNull(phone.owner.seed())
        assertTrue(phone.owner.seeded)
        assertEquals(Profile(181.5f, "f", LocalDate.parse("1988-02-29"), null), phone.owner.profile())
        assertEquals(listOf(70.3f), phone.owner.weights().map { it.kg })
        // The weight came with the profile copy, not a second time from the journal.
        val journal = phone.owner.journal(at("2026-08-01"), at("2026-10-01"))
        assertEquals(listOf("s1", "weight:${at("2026-09-01").toEpochMilli()}"), journal.map { it.id })
        assertEquals("espresso", journal.first().name)
        val zones = phone.owner.zones()!!
        assertEquals(ZONE, zones.zoneOf(LocalDate.parse("2026-09-05")))
        assertEquals("Asia/Tbilisi", zones.zoneOf(LocalDate.parse("2026-09-12")))
        assertEquals(0, phone.owner.unpushed())

        // A second seed reads nothing.
        server.down = true
        assertNull(phone.owner.seed())
    }

    @Test
    fun `a failed seed says why and tries again later`() = runTest {
        val server = serverWithHistory().apply { down = true }
        val phone = Phone(server)
        assertEquals("Could not reach the server.", phone.owner.seed())
        assertTrue(!phone.owner.seeded)
        server.down = false
        assertNull(phone.owner.seed())
        assertTrue(phone.owner.seeded)
    }

    @Test
    fun `without a server nothing is copied and the phone's zone is the base`() = runTest {
        val phone = Phone(null)
        assertNull(phone.owner.seed())
        assertTrue(phone.owner.seeded)
        assertNull(phone.owner.profile())
        assertNull(phone.owner.zones())
        phone.owner.ensureBaseZone()
        assertEquals(ZoneId.systemDefault().id, phone.owner.zones()!!.zoneOf(LocalDate.parse("2026-09-01")))
    }

    @Test
    fun `every input the science reads moves the fingerprint`() = runTest {
        val phone = Phone(serverWithHistory())
        phone.owner.seed()
        val seeded = phone.owner.fingerprint()
        phone.owner.setProfile(Profile(182f, "f", LocalDate.parse("1988-02-29"), 2))
        val edited = phone.owner.fingerprint()
        assertNotEquals(seeded, edited)
        phone.owner.add("weight", 69.8, null, at("2026-09-20"))
        val weighed = phone.owner.fingerprint()
        assertNotEquals(edited, weighed)
        phone.zones.record(ZoneId.of("Asia/Tokyo"), at("2026-09-25"))
        assertNotEquals(weighed, phone.owner.fingerprint())
        // Caffeine is the journal's, not the science's.
        val before = phone.owner.fingerprint()
        phone.owner.add("caffeine", 60.0, null, at("2026-09-26"))
        assertEquals(before, phone.owner.fingerprint())
    }

    @Test
    fun `the zones join the server's changes and the phone's own`() = runTest {
        val phone = Phone(serverWithHistory())
        phone.owner.seed()
        phone.zones.record(ZoneId.of("America/New_York"), at("2026-09-20"))
        val zones = phone.owner.zones()!!
        assertEquals("Asia/Tbilisi", zones.zoneOf(LocalDate.parse("2026-09-15")))
        assertEquals("America/New_York", zones.zoneOf(LocalDate.parse("2026-09-22")))
    }

    @Test
    fun `push sends the profile and journal changes, then nothing waits`() = runTest {
        val server = serverWithHistory()
        val phone = Phone(server)
        phone.owner.seed()
        phone.owner.setProfile(Profile(182f, "f", LocalDate.parse("1988-02-29"), 2))
        phone.owner.add("alcohol", 1.0, "beer", at("2026-09-21", "20:00"))
        phone.owner.delete("s1") // the server's entry: deleted there too
        phone.owner.add("caffeine", 50.0, null, at("2026-09-22"))
        val local = phone.owner.journal(at("2026-09-22"), at("2026-09-23")).first().id
        phone.owner.delete(local) // never sent: just gone
        assertEquals(3, phone.owner.unpushed())

        assertNull(phone.owner.push())
        assertEquals(listOf(2), server.putProfiles.map { it.getInt("srpa") })
        assertEquals(listOf("alcohol"), server.added.map { it.getString("kind") })
        assertEquals("beer", server.added.single().getString("name"))
        assertEquals("2026-09-21T18:00Z", server.added.single().getString("ts"))
        assertEquals(listOf("s1"), server.deleted)
        assertEquals(0, phone.owner.unpushed())
        assertEquals(listOf("srv-1"), phone.store.journalRows("kind = 'alcohol'").map { it.serverId })
    }

    @Test
    fun `a failed push keeps the changes waiting`() = runTest {
        val server = serverWithHistory()
        val phone = Phone(server)
        phone.owner.seed()
        phone.owner.add("caffeine", 50.0, null, at("2026-09-22"))
        server.down = true
        assertEquals("Could not reach the server.", phone.owner.push())
        assertEquals(1, phone.owner.unpushed())
        server.down = false
        assertNull(phone.owner.push())
        assertEquals(0, phone.owner.unpushed())
    }

    @Test
    fun `a weight is kept as the server keeps it, its id its time`() {
        val phone = Phone(null)
        phone.owner.add("weight", 70.1, null, at("2026-09-20"))
        val row = phone.store.journalRows().single()
        assertEquals("weight:${at("2026-09-20").toEpochMilli()}", row.id)
        assertEquals(70.1f, row.amount.toFloat())
        assertEquals("kg", row.unit)
    }

    @Test
    fun `a day keeps one status, in its own zone, and active clears it`() {
        val phone = Phone(null)
        phone.owner.restore(JSONObject().put("timezone", "Asia/Tbilisi"))
        val day = LocalDate.parse("2026-09-20")
        phone.owner.setStatus(day, DayStatus.SICK)
        phone.owner.setStatus(day, DayStatus.INJURED)
        phone.owner.setStatus(day.plusDays(1), DayStatus.BREAK)
        assertEquals(mapOf(day to DayStatus.INJURED, day.plusDays(1) to DayStatus.BREAK), phone.owner.statuses(day.minusDays(3), day.plusDays(3)))
        assertEquals(mapOf(day to DayStatus.INJURED), phone.owner.statuses(day, day))
        val row = phone.store.journalRows("kind = 'status' AND name = 'injured'").single()
        assertEquals("day", row.unit)
        assertEquals(phone.owner.zones()!!.bounds(day).first.plusSeconds(12 * 3600), row.ts)
        phone.owner.setStatus(day, DayStatus.ACTIVE)
        assertEquals(mapOf(day.plusDays(1) to DayStatus.BREAK), phone.owner.statuses(day, day.plusDays(1)))
    }

    @Test
    fun `restore takes the profile, journal and zones from a backup`() {
        val phone = Phone(null)
        phone.owner.restore(JSONObject()
            .put("profile", JSONObject().put("height_cm", 175.0).put("sex", "m").put("dob", JSONObject.NULL).put("srpa", 3))
            .put("timezone", "Asia/Tbilisi")
            .put("weights", JSONArray().put(JSONObject().put("ts", at("2026-09-01").toEpochMilli()).put("kg", 80.0)))
            .put("journal", JSONArray().put(JSONObject().put("id", "j1").put("kind", "caffeine").put("ts", at("2026-09-02").toEpochMilli())
                .put("amount", 100.0).put("unit", "mg").put("name", JSONObject.NULL))))
        assertTrue(phone.owner.seeded)
        assertEquals(Profile(175f, "m", null, 3), phone.owner.profile())
        assertEquals(listOf(80f), phone.owner.weights().map { it.kg })
        assertEquals("Asia/Tbilisi", phone.owner.zones()!!.zoneOf(LocalDate.parse("2026-09-01")))
        assertEquals(setOf("j1", "weight:${at("2026-09-01").toEpochMilli()}"), phone.store.journalRows().map { it.id }.toSet())
        assertEquals(0, phone.owner.unpushed())
    }
}
