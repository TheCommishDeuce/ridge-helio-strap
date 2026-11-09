package strap.protocol

import strap.protocol.fetch.FetchFailure
import strap.protocol.fetch.FetchJob
import strap.protocol.fetch.FetchResult
import strap.protocol.model.Metric
import strap.protocol.parse.FetchType
import strap.protocol.parse.WorkoutParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetchJobTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val origin = LocalDateTime.of(2026, 6, 4, 8, 0)
    private val now = origin.plusDays(1).atZone(zone).toInstant()
    private fun at(local: LocalDateTime): Instant = local.atZone(zone).toInstant()

    /** Per-minute activity: steps = minute+1, hr = 60+minute. */
    private fun activity(minutes: Int) = ByteArray(minutes * 8).also {
        for (m in 0 until minutes) {
            it[m * 8 + 2] = (m + 1).toByte()
            it[m * 8 + 3] = (60 + m).toByte()
        }
    }

    private fun job(code: Int, since: LocalDateTime, maxRounds: Int = 400) =
        FetchJob(code, at(since), zone, now = { now }, maxRounds = maxRounds)

    @Test
    fun `pages across rounds until the stream is exhausted, always acking KEEP`() {
        val strap = FakeFetchStrap(origin, 8, activity(12), minutesPerRound = 5)
        val result = strap.run(job(FetchType.ACTIVITY, origin))
        assertNull(result.failure)
        val hr = result.samples.filter { it.metric == Metric.HR }
        assertEquals((0 until 12).map { 60.0 + it }, hr.map { it.value })
        assertEquals((0 until 12).map { at(origin.plusMinutes(it.toLong())) }, hr.map { it.at })
        // rounds start at 08:00, 08:05, 08:10, then 08:12 finds nothing left
        assertEquals(listOf(0L, 5L, 10L, 12L), strap.startCommands.map { java.time.Duration.between(origin, it).toMinutes() })
        assertEquals(strap.startCommands.size, strap.acks)
    }

    @Test
    fun `a counter gap discards the round and retries the same cursor`() {
        val strap = FakeFetchStrap(origin, 8, activity(5), minutesPerRound = 5, packetSize = 12).apply { dropPacketInRound += 1 }
        val result = strap.run(job(FetchType.ACTIVITY, origin))
        assertNull(result.failure)
        assertEquals(5, result.samples.count { it.metric == Metric.HR })
        assertEquals(origin, strap.startCommands[1]) // retried from the same place
    }

    @Test
    fun `three gaps in a row fail with the packets named, keeping earlier rounds`() {
        val strap = FakeFetchStrap(origin, 8, activity(10), minutesPerRound = 5, packetSize = 12).apply { dropPacketInRound += setOf(2, 3, 4) }
        val result = strap.run(job(FetchType.ACTIVITY, origin))
        assertEquals(FetchFailure.MissingPackets, result.failure)
        assertEquals(5, result.samples.count { it.metric == Metric.HR }) // round 1 survived
    }

    /** The counter check cannot see a lost FINAL packet; the announced count can (the old code missed this). */
    @Test
    fun `a dropped final packet is caught by the announced count and retried`() {
        val strap = FakeFetchStrap(origin, 8, activity(5), minutesPerRound = 5, packetSize = 20).apply { dropPacketInRound += 1 }
        val result = strap.run(job(FetchType.ACTIVITY, origin))
        assertNull(result.failure)
        assertEquals(5, result.samples.count { it.metric == Metric.HR })
        assertEquals(true, result.rounds.first().short)
        assertEquals(origin, strap.startCommands[1])
    }

    @Test
    fun `announced count is records for activity and bytes for everything else`() {
        assertEquals(800L, FetchJob.announcedBytes(FetchType.ACTIVITY, 100))
        assertEquals(100L, FetchJob.announcedBytes(FetchType.STRESS, 100))
        assertEquals(100L, FetchJob.announcedBytes(FetchType.SLEEP, 100))
    }

    @Test
    fun `an all-0xFF stress stretch is stepped over instead of stalling`() {
        val stress = ByteArray(15) { if (it < 10) 0xFF.toByte() else (20 + it).toByte() }
        val strap = FakeFetchStrap(origin, 1, stress, minutesPerRound = 5)
        val result = strap.run(job(FetchType.STRESS, origin))
        assertNull(result.failure)
        assertEquals((10 until 15).map { 20.0 + it }, result.samples.map { it.value })
        assertEquals(at(origin.plusMinutes(10)), result.samples.first().at)
        // each empty round jumps by its own minute count (5), not one minute at a time
        assertEquals(listOf(0L, 5L, 10L, 15L), strap.startCommands.map { java.time.Duration.between(origin, it).toMinutes() })
    }

    @Test
    fun `nothing to send finishes after one ack`() {
        val strap = FakeFetchStrap(origin, 8, ByteArray(0))
        val result = strap.run(job(FetchType.ACTIVITY, origin))
        assertNull(result.failure)
        assertTrue(result.samples.isEmpty())
        assertEquals(0L, result.announced)
        assertEquals(1, strap.acks)
    }

    @Test
    fun `round cap is reported, not silently truncated`() {
        val strap = FakeFetchStrap(origin, 8, activity(30), minutesPerRound = 5)
        val result = strap.run(job(FetchType.ACTIVITY, origin, maxRounds = 2))
        assertEquals(FetchFailure.RoundLimit, result.failure)
        assertEquals(10, result.samples.count { it.metric == Metric.HR })
        assertEquals(at(origin.plusMinutes(10)), result.cursor) // past both rounds, where the next would start
    }

    /** A new strap answers each window before its first day with three empty minutes (`3/1p/24b`). */
    @Test
    fun `a round cap keeps the ground its gap-skipped rounds covered`() {
        val strap = FakeFetchStrap(origin, 8, ByteArray(30 * 8) { 0xFF.toByte() } + activity(5), minutesPerRound = 3)
        val first = strap.run(job(FetchType.ACTIVITY, origin, maxRounds = 4))
        assertEquals(FetchFailure.RoundLimit, first.failure)
        assertTrue(first.samples.isEmpty())
        assertEquals(at(origin.plusMinutes(12)), first.cursor) // four rounds of three empty minutes

        val resumed = strap.run(FetchJob(FetchType.ACTIVITY, first.cursor, zone, now = { now }, maxRounds = 400))
        assertNull(resumed.failure)
        assertEquals(origin.plusMinutes(12), strap.startCommands[4])
        assertEquals((0 until 5).map { at(origin.plusMinutes(30L + it)) }, resumed.samples.filter { it.metric == Metric.HR }.map { it.at })
    }

    @Test
    fun `a rejected start and a driver timeout both end the job with a name`() {
        val rejected = job(FetchType.HRV, origin)
        rejected.start()
        assertEquals(FetchFailure.StartRejected(0x02), rejected.onControl("100102".unhex()).result!!.failure)

        val timedOut = job(FetchType.HRV, origin)
        timedOut.start()
        val out = timedOut.fail(FetchFailure.TimedOut)
        assertEquals(FetchFailure.TimedOut, out.result!!.failure)
        assertEquals(-1L, out.result!!.announced)
    }

    @Test
    fun `probe reports the packet count and never downloads`() {
        val strap = FakeFetchStrap(origin, 8, activity(5))
        val probe = FetchJob(FetchType.ACTIVITY, at(origin), zone, now = { now }, probeOnly = true)
        val result = strap.run(probe)
        assertNull(result.failure)
        assertEquals(5L, result.announced) // five 8-byte minute records
        assertTrue(result.samples.isEmpty())
    }

    @Test
    fun `start command carries local wall time and the zone offset`() {
        val first = job(FetchType.ACTIVITY, origin).start().writes.single()
        assertContentEquals("0101ea07060408000008".unhex(), first) // 2026-06-04 08:00, +2h = 8 units
    }

    /** A summary as the strap sends it: the 2-byte record header, then "2.1" and the record's start. */
    private fun summary(start: Instant): ByteArray {
        var v = start.epochSecond
        val varint = buildList { while (v >= 0x80) { add(((v and 0x7f) or 0x80).toByte()); v = v ushr 7 }; add(v.toByte()) }.toByteArray()
        return "00800a03322e31".unhex() + byteArrayOf(0x12, (varint.size + 1).toByte(), 0x08) + varint
    }

    /**
     * A strap that keeps workouts on a wall clock [aheadMinutes] ahead of the one the phone asks
     * in (workouts recorded in summer time, read in winter time): one summary a round, the first
     * whose wall-clock start is not before `since`.
     */
    private class WorkoutStrap(private val records: List<Pair<LocalDateTime, ByteArray>>, private val aheadMinutes: Long) : FetchResponder {
        private var pending = ByteArray(0)
        var rounds = 0

        override fun handle(write: ByteArray): List<FakeFetchStrap.Event> = when (write[0].toInt()) {
            0x01 -> {
                rounds++
                val since = sinceOf(write)
                val next = records.firstOrNull { !it.first.plusMinutes(aheadMinutes).isBefore(since) }
                pending = next?.second ?: ByteArray(0)
                listOf(FakeFetchStrap.Event.Control(startReply(write[1].toInt(), pending.size, next?.first ?: since)))
            }
            0x02 -> listOf(FakeFetchStrap.Event.Data(byteArrayOf(0) + pending), FakeFetchStrap.Event.Control("100201".unhex()))
            else -> listOf(FakeFetchStrap.Event.Control("100301".unhex()))
        }
    }

    private fun runWorkouts(strap: WorkoutStrap, since: LocalDateTime): FetchResult {
        val job = job(FetchType.WORKOUTS, since)
        val writes = ArrayDeque(job.start().writes)
        while (writes.isNotEmpty()) {
            for (event in strap.handle(writes.removeFirst())) {
                val out = when (event) {
                    is FakeFetchStrap.Event.Control -> job.onControl(event.bytes)
                    is FakeFetchStrap.Event.Data -> { job.onData(event.bytes); null }
                } ?: continue
                writes.addAll(out.writes)
                out.result?.let { return it }
            }
        }
        error("job never finished")
    }

    /** The owner's strap, 2026-11-09: stuck on a 17 Oct workout, with 29 newer ones never fetched. */
    @Test
    fun `a workout the strap sends again does not end the fetch, the cursor steps on to the next`() {
        val starts = listOf(origin.plusHours(1), origin.plusHours(2), origin.plusHours(9))
        val strap = WorkoutStrap(starts.map { it to summary(at(it)) }, aheadMinutes = 60)
        val result = runWorkouts(strap, origin)
        assertNull(result.failure)
        assertEquals(starts.map { at(it) }, WorkoutParser.parseStream(result.raw).map { it.start }.distinct())
    }

    @Test
    fun `a strap that only ever sends one workout ends the fetch after a bounded number of rounds`() {
        val strap = WorkoutStrap(listOf(origin.plusHours(1) to summary(at(origin.plusHours(1)))), aheadMinutes = 100_000)
        assertNull(runWorkouts(strap, origin).failure)
        assertTrue(strap.rounds in 3..60)
    }
}
