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
import strap.protocol.sync.SyncResult
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
import kotlin.test.fail
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

    // ── a new strap: nothing before the day it was first worn ──

    private val worn = LocalDateTime.of(2026, 6, 4, 7, 13)
    private val end = now.atZone(zone).toLocalDateTime()

    private fun newStrap() = FakeStrapLink(
        authKey,
        fetch = mapOf(
            FetchType.ACTIVITY to NewStrapHistory(worn, end, 8, { t -> byteArrayOf(0, 0, 10, (60 + t.minute).toByte(), 0, 0, 0, 0) }),
            FetchType.STRESS to NewStrapHistory(worn, end, 1, { t -> byteArrayOf((20 + t.minute).toByte()) }),
        ),
    )

    /** Syncs until one completes, carrying the window forward as the phone's store does; every sync's result. */
    private suspend fun TestScope.syncUntilComplete(start: SyncWindow, max: Int): List<SyncResult> {
        var window = start
        val results = mutableListOf<SyncResult>()
        while (results.size < max) {
            val steps = mutableListOf<String>()
            val r = StrapSync(session(newStrap()), now = { now }) { steps += it.label }.run(window)
            assertEquals(SyncPlan.STEPS, steps.size, "sync ${results.size + 1} ran every step")
            results += r
            if (r.failure == null) return results
            window = window.after(r)
        }
        fail("no sync completed in $max: ${results.last().fetches.filter { it.failure != null }}")
    }

    private fun SyncWindow.after(r: SyncResult) = copy(
        lastSampleAt = lastSampleAt + r.samples.groupBy { it.metric }.mapValues { (m, s) -> listOfNotNull(lastSampleAt[m], s.maxOf { it.at }).max() },
        fetchCursors = fetchCursors + r.fetchCursors.mapValues { (code, at) -> listOfNotNull(fetchCursors[code], at).max() },
        stressBackfillCursor = listOfNotNull(stressBackfillCursor, r.stressBackfillCursor).maxOrNull(),
        stressBackfillDone = stressBackfillDone || r.stressBackfillRan,
        napBackfillDone = napBackfillDone || r.napBackfillRan,
    )

    /** Syncs it takes to walk [days] of empty rounds of [minutes] each, at the round cap. */
    private fun syncsFor(days: Long, minutes: Int) = Math.ceil(days * 1440.0 / (SyncPlan.ROUNDS * minutes)).toInt()

    @Test
    fun `a new strap's first sync completes within a bounded number of syncs, from a fresh install`() = runTest {
        val results = syncUntilComplete(SyncWindow(), max = 60)

        assertTrue(results.size <= syncsFor(30, 3), "${results.size} syncs") // 30 days of 3-minute rounds
        val wornMinutes = Duration.between(worn, end).toMinutes().toInt()
        for (metric in listOf(Metric.HR, Metric.STEPS)) {
            val at = results.flatMap { r -> r.samples.filter { it.metric == metric }.map { it.at } }
            assertEquals((0 until wornMinutes).map { worn.plusMinutes(it.toLong()).atZone(zone).toInstant() }, at, "$metric: every worn minute once")
        }
        val stress = results.flatMap { r -> r.samples.filter { it.metric == Metric.STRESS }.map { it.at } }
        assertEquals(wornMinutes, stress.toSet().size)
        assertTrue(results.any { it.stressBackfillRan })
        for (r in results) {
            assertTrue(r.fetches.map { it.code }.containsAll(listOf(FetchType.SLEEP, FetchType.WORKOUTS, FetchType.HRV_SDNN, FetchType.PAI, FetchType.ODI)))
        }
    }

    @Test
    fun `a new strap's first sync completes within a bounded number of syncs, after an HR backfill`() = runTest {
        val restored = worn.plusMinutes(60).atZone(zone).toInstant()
        val results = syncUntilComplete(SyncWindow(lastSampleAt = mapOf(Metric.HR to restored)), max = 60)

        assertTrue(results.size <= syncsFor(30, 24), "${results.size} syncs") // stress: 30 days of 24-minute rounds
        val hr = results.flatMap { r -> r.samples.filter { it.metric == Metric.HR }.map { it.at } }
        assertEquals(hr.distinct(), hr)
        assertEquals(restored.plusSeconds(60), hr.first())
        assertEquals(now.minusSeconds(60), hr.last())
        assertTrue(results.any { it.stressBackfillRan })
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
        assertEquals(t.minus(Duration.ofDays(9)), SyncPlan.metricSince(null, t, cursor = t.minus(Duration.ofDays(9)))) // a capped fetch's cursor
        assertEquals(t.minusSeconds(3600 - 60), SyncPlan.metricSince(t.minusSeconds(3600), t, cursor = t.minus(Duration.ofDays(9))))
        assertEquals(t.minus(Duration.ofDays(25)), SyncPlan.stressBackfillSince(t))
        assertEquals(t.minus(Duration.ofDays(3)), SyncPlan.stressBackfillSince(t, cursor = t.minus(Duration.ofDays(3))))
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
