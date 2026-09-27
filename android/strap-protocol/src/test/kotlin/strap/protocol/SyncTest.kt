package strap.protocol

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import strap.protocol.LegacyVectors.hex
import strap.protocol.auth.HandshakeFailure
import strap.protocol.fetch.FetchFailure
import strap.protocol.model.Metric
import strap.protocol.parse.FetchType
import strap.protocol.session.StrapException
import strap.protocol.session.StrapFailure
import strap.protocol.session.StrapSession
import strap.protocol.sync.StrapSync
import strap.protocol.sync.SyncPlan
import strap.protocol.sync.SyncWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SyncTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val authKey = ByteArray(16) { (0xA0 + it).toByte() }
    private val origin = LocalDateTime.of(2026, 6, 4, 8, 0)
    private val now = origin.plusHours(2).atZone(zone).toInstant()

    private fun activity(minutes: Int) = ByteArray(minutes * 8).also {
        for (m in 0 until minutes) { it[m * 8 + 2] = 10; it[m * 8 + 3] = (60 + m).toByte() }
    }

    private suspend fun TestScope.session(link: FakeStrapLink) =
        StrapSession.open(link, authKey, backgroundScope, zone, now = { now })

    private fun fullStrap() = FakeStrapLink(
        authKey,
        fetch = mapOf(
            FetchType.ACTIVITY to FakeFetchStrap(origin, 8, activity(12)),
            FetchType.STRESS to FakeFetchStrap(origin, 1, ByteArray(6) { (30 + it).toByte() }),
            FetchType.SLEEP to BlobFetchStrap(LegacyVectors.obj("parsers", "sleep").hex("raw")),
            FetchType.WORKOUTS to BlobFetchStrap(LegacyVectors.obj("parsers", "workout").hex("raw")),
        ),
    )

    @Test
    fun `a first sync authenticates, pulls every stream and reads the counter`() = runTest {
        val link = fullStrap()
        val progress = mutableListOf<String>()
        val result = StrapSync(session(link), now = { now }) { progress += it.label }
            .run(SyncWindow(lastSampleAt = mapOf(Metric.HR to origin.minusMinutes(1).atZone(zone).toInstant())))

        assertNull(result.failure)
        assertEquals(12, result.samples.count { it.metric == Metric.HR })
        assertEquals(12, result.samples.count { it.metric == Metric.STEPS })
        assertEquals(6, result.samples.count { it.metric == Metric.STRESS } / 2) // incremental + one-shot backfill
        assertEquals(3, result.sleepSessions.size)
        assertEquals(2, result.workouts.size)
        assertEquals(8123L, result.dailyTotals!!.steps)
        assertTrue(result.stressBackfillRan && result.napBackfillRan)
        assertEquals(SyncPlan.STEPS, progress.size)
        assertEquals("daily step counter", progress.first())
        assertTrue(link.acks.isNotEmpty(), "the totals frame asked for an ACK")
        assertEquals(true, link.fetchChannelOpenedAfterAuth)
        assertTrue(link.controlWrites.filter { it[0].toInt() == 0x03 }.all { it[1].toInt() == 0x09 }, "every ack keeps data")
        assertTrue(result.fetches.first { it.code == FetchType.ACTIVITY }.roundStats.all { it.packets > 0 })
    }

    @Test
    fun `a wrong auth key is refused by name`() = runTest {
        val link = FakeStrapLink(authKey)
        val e = assertFailsWith<StrapException> {
            StrapSession.open(link, ByteArray(16), backgroundScope, zone)
        }
        assertEquals(StrapFailure.HandshakeRefused(HandshakeFailure.WrongAuthKey), e.failure)
    }

    @Test
    fun `a silent strap times out, which is not a refusal`() = runTest {
        val e = assertFailsWith<StrapException> { session(FakeStrapLink(authKey, silent = true)) }
        assertIs<StrapFailure.HandshakeTimedOut>(e.failure)
    }

    @Test
    fun `without the fetch channel the sync still reports the counter`() = runTest {
        val result = StrapSync(session(FakeStrapLink(authKey, hasFetchChannel = false)), now = { now }).run(SyncWindow())
        assertNull(result.failure)
        assertEquals(8123L, result.dailyTotals!!.steps)
        assertTrue(result.samples.isEmpty() && result.fetches.isEmpty())
    }

    @Test
    fun `an unanswered counter is null, not zero`() = runTest {
        val result = StrapSync(session(FakeStrapLink(authKey, hasFetchChannel = false, totals = null)), now = { now }).run(SyncWindow())
        assertNull(result.dailyTotals)
    }

    @Test
    fun `a radio failure mid-sync keeps what arrived and stops`() = runTest {
        val link = fullStrap().apply { failControlWritesAfter = 6 }
        val result = StrapSync(session(link), now = { now }).run(SyncWindow(stressBackfillDone = true, napBackfillDone = true))
        assertEquals(FetchFailure.WriteFailed, result.failure)
        assertTrue(result.samples.any { it.metric == Metric.HR })
        assertTrue(result.workouts.isEmpty(), "later types were never fetched")
        assertNotNull(result.dailyTotals)
    }

    // ── the fetch windows (spec/01 §6) ──

    @Test
    fun `an exchange on an encrypted endpoint gets the reply with the asked-for command`() = runTest {
        val link = FakeStrapLink(authKey, services = mapOf(
            0x000a to { req -> if (req.contentEquals(byteArrayOf(0x01))) byteArrayOf(0x02, 0x03, 0x01, 0x08) else null },
        ))
        val s = session(link)
        val reply = s.exchange(0x000a, byteArrayOf(0x01), replyCommand = 0x02, encrypt = true)
        assertEquals(listOf<Byte>(0x02, 0x03, 0x01, 0x08), reply?.toList())
        assertNull(s.exchange(0x000a, byteArrayOf(0x03), replyCommand = 0x04, encrypt = true, timeout = 1.seconds))
    }

    @Test
    fun `fetch windows`() {
        val t = Instant.parse("2026-06-10T12:00:00Z")
        assertEquals(t.minus(Duration.ofDays(30)), SyncPlan.metricSince(null, t))
        assertEquals(t.minusSeconds(3600 - 60), SyncPlan.metricSince(t.minusSeconds(3600), t))
        assertEquals(t.minus(Duration.ofDays(2)), SyncPlan.staleRetrySince(t.minus(Duration.ofDays(3)), t, gotSamples = false))
        assertNull(SyncPlan.staleRetrySince(t.minus(Duration.ofDays(3)), t, gotSamples = true))
        assertNull(SyncPlan.staleRetrySince(t.minus(Duration.ofDays(1)), t, gotSamples = false))
        assertEquals(t.minus(Duration.ofDays(2)), SyncPlan.sleepSince(t.minusSeconds(3600), napBackfillDone = true, t))
        assertEquals(t.minus(Duration.ofDays(14)), SyncPlan.sleepSince(t.minusSeconds(3600), napBackfillDone = false, t))
        assertEquals(t.minus(Duration.ofDays(20)), SyncPlan.sleepSince(t.minus(Duration.ofDays(20)), napBackfillDone = true, t))
        assertEquals(t.minus(Duration.ofDays(90)), SyncPlan.workoutsSince(null, t))
        assertEquals(t.minus(Duration.ofDays(6)), SyncPlan.workoutsSince(t.minus(Duration.ofDays(5)), t))
    }
}
