package strap.protocol

import kotlinx.coroutines.test.runTest
import strap.protocol.LegacyVectors.hex
import strap.protocol.fetch.FetchFailure
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import strap.protocol.parse.ActivityParser
import strap.protocol.parse.FetchType
import strap.protocol.session.StrapSession
import strap.protocol.sync.StrapSync
import strap.protocol.sync.SyncPlan
import strap.protocol.sync.SyncWindow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strap's own summaries: SDNN `0x4E`, PAI `0x0D`, sleep-apnea ODI `0x4A`. Layouts are
 * OpenStrap's (docs/protocol/02-history-sync.md §3.5–3.9); the vectors are synthetic, in the
 * shape of our own probe dumps (record sizes, the tz byte, belongTime at local midnight).
 */
class SummaryParserTest {
    private val roundStart = Instant.EPOCH

    private fun parse(code: Int, raw: ByteArray) = ActivityParser.parse(code, raw, roundStart)

    private fun List<Sample>.of(metric: Metric) = filter { it.metric == metric }.map { it.at.epochSecond to it.value }

    @Test
    fun `SDNN is hundredths of a millisecond, sentinels dropped`() {
        val reading = "704c016a0088130000".unhex()
        val empty = "9c4d016a0000000000".unhex()
        val unset = "c84e016a00ffffffff".unhex()
        val samples = parse(FetchType.HRV_SDNN, reading + empty + unset + "0102".unhex())
        assertEquals(listOf(1_778_470_000L to 50.0), samples.of(Metric.HRV_SDNN))
        assertEquals(1, samples.size)
    }

    private fun pai(version: Int, at: Long, daily: Float, total: Float): ByteArray {
        val size = when { version < 2 -> 69; version < 4 -> 97; version < 5 -> 99; else -> 102 }
        return ByteArray(size).also {
            it[0] = version.toByte()
            Bytes.putU32(it, 1, at)
            Bytes.putU32(it, 55, daily.toRawBits().toLong() and 0xFFFFFFFFL)
            Bytes.putU32(it, 59, total.toRawBits().toLong() and 0xFFFFFFFFL)
            Bytes.putU16(it, 63, 58) // resting HR, not read
        }
    }

    @Test
    fun `PAI reads the day's points and the 7-day total, whatever the version's length`() {
        for (v in listOf(1, 3, 4, 5)) {
            val samples = parse(FetchType.PAI, pai(v, 1_780_300_000L, 12.5f, 87.25f))
            assertEquals(listOf(1_780_300_000L to 12.5), samples.of(Metric.PAI_DAILY), "v$v")
            assertEquals(listOf(1_780_300_000L to 87.25), samples.of(Metric.PAI_TOTAL), "v$v")
        }
    }

    @Test
    fun `PAI drops values that are not numbers and records with no real time`() {
        assertEquals(listOf(Metric.PAI_TOTAL), parse(FetchType.PAI, pai(5, 1_780_300_000L, Float.NaN, 40f)).map { it.metric })
        assertTrue(parse(FetchType.PAI, pai(5, 0L, 1f, 2f)).isEmpty())
        assertTrue(parse(FetchType.PAI, ByteArray(0)).isEmpty())
        assertTrue(parse(FetchType.PAI, pai(5, 1_780_300_000L, 1f, 2f).copyOf(80)).isEmpty(), "truncated record")
    }

    private fun odi(header: String) = ByteArray(2896).also { header.unhex().copyInto(it) }

    @Test
    fun `ODI keeps a measured night's fields and only the valid time of an unmeasured one`() {
        // belongTime 2026-05-11 00:00 UTC (local midnight in UTC), valid 27000.
        val measured = odi("001c016a7869000007000000ff000203")
        val unmeasured = odi("806d026a")
        val blank = ByteArray(2896)
        val samples = parse(FetchType.ODI, measured + unmeasured + blank)
        assertEquals(listOf(1_778_457_600L to 27_000.0, 1_778_544_000L to 0.0), samples.of(Metric.ODI_VALID_TIME))
        assertEquals(listOf(1_778_457_600L to 3.0), samples.of(Metric.ODI_EVENTS))
        assertEquals(listOf(1_778_457_600L to 7.0), samples.of(Metric.ODI_RAW))
        assertEquals(emptyList(), samples.of(Metric.ODI_SCORE), "0xFF score is unset")
    }

    @Test
    fun `ODI keeps a measured zero score and skips a partial trailing record`() {
        val samples = parse(FetchType.ODI, odi("001c016a78690000") + odi("806d026a78690000").copyOf(100))
        assertEquals(1, samples.of(Metric.ODI_VALID_TIME).size)
        assertEquals(listOf(1_778_457_600L to 0.0), samples.of(Metric.ODI_SCORE))
    }

    private val zone = ZoneId.of("Europe/Berlin")
    private val authKey = ByteArray(16) { (0xA0 + it).toByte() }
    private val now = LocalDateTime.of(2026, 6, 4, 10, 0).atZone(zone).toInstant()

    /** Answers every start with status 0x02: the strap does not serve this type. */
    private object Refusing : FetchResponder {
        override fun handle(write: ByteArray) = listOf(FakeFetchStrap.Event.Control("100102".unhex()))
    }

    @Test
    fun `the summaries come after workouts, so a refused one costs nothing before it`() = runTest {
        val link = FakeStrapLink(
            authKey,
            fetch = mapOf(
                FetchType.WORKOUTS to BlobFetchStrap(LegacyVectors.obj("parsers", "workout").hex("raw")),
                FetchType.HRV_SDNN to BlobFetchStrap("704c016a0088130000".unhex()),
                FetchType.PAI to Refusing,
            ),
        )
        val session = StrapSession.open(link, authKey, backgroundScope, zone, now = { now })
        val result = StrapSync(session, now = { now }).run(SyncWindow())
        assertEquals(FetchFailure.StartRejected(0x02), result.failure)
        assertEquals(2, result.workouts.size)
        assertEquals(1, result.samples.count { it.metric == Metric.HRV_SDNN })
        val order = link.controlWrites.filter { it[0].toInt() == 0x01 }.map { it[1].toInt() and 0xFF }.distinct()
        assertEquals(listOf(FetchType.WORKOUTS, FetchType.HRV_SDNN, FetchType.PAI), order.takeLast(3))
        assertEquals(SyncPlan.METRICS.size + SyncPlan.SUMMARIES.size + 4, SyncPlan.STEPS)
    }
}
