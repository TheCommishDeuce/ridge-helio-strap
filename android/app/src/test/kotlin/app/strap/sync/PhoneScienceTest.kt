package app.strap.sync

import app.strap.FakeServer
import app.strap.Phone
import app.strap.PhoneTest
import app.strap.ZONE
import app.strap.at
import app.strap.day
import app.strap.dayOfSamples
import app.strap.sync
import kotlinx.coroutines.test.runTest
import org.junit.Test
import strap.science.Derived
import strap.science.Profile
import strap.science.Zones
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneScienceTest : PhoneTest() {
    private val zones = Zones(ZONE)

    /** The strap's data for [days], each day synced the evening it was recorded. */
    private fun Phone.synced(vararg days: String) = days.forEach { d -> store.save(sync(at(d, "22:00"), samples = dayOfSamples(d)), 70) }

    /** A row no derive pass writes: it survives exactly when its day is not worked out again. */
    private fun Phone.mark(vararg days: String) = days.forEach { store.derived.put(Derived(day(it), "test_mark", 1.0)) }

    private fun Phone.marked(d: String) = store.derived.get(day(d), "test_mark") != null

    @Test
    fun `without a server the era starts with the first data`() {
        val phone = Phone(null)
        assertNull(phone.science.era(zones))
        phone.synced("2026-09-03", "2026-09-04")
        phone.store.restoreSamples(listOf(Triple("hr", at("2026-09-01").toEpochMilli(), 60.0)))
        assertEquals(day("2026-09-01"), phone.science.era(zones))
    }

    @Test
    fun `beside a server the era starts two days after the first sync, until the history is copied`() {
        val phone = Phone(FakeServer())
        phone.store.restoreSamples(listOf(Triple("hr", at("2026-08-01").toEpochMilli(), 60.0))) // the old app's, pushed by the server's import
        phone.synced("2026-09-03", "2026-09-04")
        assertEquals(day("2026-09-05"), phone.science.era(zones))
        phone.store.setMetaValue(Restore.BACKFILLED, "1")
        assertEquals(day("2026-08-01"), phone.science.era(zones))
    }

    @Test
    fun `a restored phone works out its whole history`() {
        val phone = Phone(FakeServer())
        phone.store.restoreSamples(listOf(Triple("hr", at("2026-08-01").toEpochMilli(), 60.0)))
        phone.synced("2026-09-03")
        phone.store.setMetaValue(Restore.RESTORED, "1")
        assertEquals(day("2026-08-01"), phone.science.era(zones))
    }

    @Test
    fun `an update works out the days from the earliest one touched`() = runTest {
        val phone = Phone(null)
        phone.synced("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04")
        assertNull(phone.science.update(null))
        assertEquals(day("2026-09-04"), phone.store.derivedThrough())
        assertNotNull(phone.store.derived.get(day("2026-09-01"), "steps_total"))
        val version = phone.science.version.value

        phone.mark("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04")
        phone.synced("2026-09-05")
        assertNull(phone.science.update(at("2026-09-03", "08:00")))
        assertTrue(phone.marked("2026-09-01") && phone.marked("2026-09-02"))
        assertTrue(!phone.marked("2026-09-03") && !phone.marked("2026-09-04"))
        assertEquals(day("2026-09-05"), phone.store.derivedThrough())
        assertEquals(version + 1, phone.science.version.value)
    }

    @Test
    fun `a changed profile or weight works out the whole era again`() = runTest {
        val phone = Phone(null)
        phone.synced("2026-09-01", "2026-09-02", "2026-09-03")
        phone.science.update(null)

        phone.mark("2026-09-01", "2026-09-02")
        phone.owner.setProfile(Profile(180f, "m", LocalDate.parse("1990-01-01"), 1))
        phone.science.update(at("2026-09-03"))
        assertTrue(!phone.marked("2026-09-01") && !phone.marked("2026-09-02"))

        phone.mark("2026-09-01")
        phone.science.update(at("2026-09-03"))
        assertTrue(phone.marked("2026-09-01"), "nothing changed: only the touched day")

        phone.owner.add("weight", 75.0, null, at("2026-09-02"))
        phone.science.update(at("2026-09-03"))
        assertTrue(!phone.marked("2026-09-01"))
    }

    @Test
    fun `an update says why it cannot run yet`() = runTest {
        val server = FakeServer().apply { down = true }
        val phone = Phone(server)
        assertEquals("the owner's data is not copied yet: Could not reach the server.", phone.science.update(null))
        server.down = false
        assertEquals("no data yet", phone.science.update(null))
    }

    @Test
    fun `a sync's data is caught up from where it starts, and nothing new derives nothing`() = runTest {
        val phone = Phone(null)
        phone.synced("2026-09-01", "2026-09-02", "2026-09-03")
        assertNull(phone.science.update(null))
        assertNull(phone.store.underivedFrom())

        phone.mark("2026-09-01", "2026-09-02", "2026-09-03")
        assertNull(phone.science.catchUp())
        assertTrue(phone.marked("2026-09-01") && phone.marked("2026-09-03"), "nothing new: nothing derived")

        phone.store.save(sync(at("2026-09-04", "09:00"), samples = dayOfSamples("2026-09-03").takeLast(10)), 70)
        assertEquals(at("2026-09-03", "23:10"), phone.store.underivedFrom())
        assertNull(phone.science.catchUp())
        assertTrue(phone.marked("2026-09-02") && !phone.marked("2026-09-03"))
        assertNull(phone.store.underivedFrom())
    }

    @Test
    fun `a derive the process never got to runs on first use`() = runTest {
        val phone = Phone(null)
        phone.synced("2026-09-01", "2026-09-02")
        phone.science.update(null)
        phone.mark("2026-09-01", "2026-09-02")
        phone.synced("2026-09-03") // stored, then the app was killed before it derived

        val restarted = PhoneScience(phone.store, { null }, phone.owner)
        restarted.at(day("2026-09-03"))
        assertTrue(phone.marked("2026-09-02"))
        assertNotNull(phone.store.derived.get(day("2026-09-03"), "steps_total"))
    }

    @Test
    fun `the screens read the recent window, or one loaded for an older day`() = runTest {
        val phone = Phone(null)
        phone.synced("2026-06-01", "2026-09-01")
        phone.science.update(at("2026-09-01")) // the first derive works out the whole era
        phone.science.update(at("2026-09-01", "13:00"))
        val today = java.time.LocalDate.now(java.time.ZoneId.of(ZONE))
        val recent = phone.science.at(today)
        assertTrue(recent.data.samples("hr", at("2026-06-01", "00:00"), at("2026-06-02", "00:00")).isEmpty(), "June is outside today's window")
        val june = phone.science.at(day("2026-06-01"))
        assertEquals(144, june.data.samples("hr", at("2026-06-01", "00:00"), at("2026-06-02", "00:00")).size)
        assertTrue(phone.science.at(day("2026-06-01")) === june, "kept for the next read of that day")
        val range = phone.science.over(day("2026-06-01"), day("2026-06-01"))
        assertEquals(144, range.data.samples("hr", at("2026-06-01", "00:00"), at("2026-06-02", "00:00")).size)
    }
}
