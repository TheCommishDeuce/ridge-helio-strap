package strap.protocol.parse

import strap.protocol.Bytes
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import java.time.Instant

/**
 * Decoders for the activity-fetch data stream, one per fetch type (spec/01 §7).
 *
 * Round-relative types (`0x01`, `0x13`, `0x2E`) are stamped `roundStart + 1 min × index`;
 * the rest carry absolute epoch seconds. `0xFF` is the "allocated, never written" sentinel
 * and is dropped wherever the table says so — treating it as data inflates steps and makes
 * heart rate out of holes.
 */
public object ActivityParser {
    private const val SENTINEL = 0xFF
    private const val MINUTE_MS = 60_000L

    public fun parse(code: Int, data: ByteArray, roundStart: Instant): List<Sample> = when (code) {
        FetchType.ACTIVITY -> activity(data, roundStart)
        FetchType.MANUAL_HR -> sixByte(data, Metric.MANUAL_HR)
        FetchType.STRESS_MANUAL -> sixByte(data, Metric.STRESS_MANUAL)
        FetchType.STRESS -> stress(data, roundStart)
        FetchType.SPO2 -> spo2(data)
        FetchType.SPO2_SLEEP -> spo2Sleep(data)
        FetchType.TEMPERATURE -> temperature(data, roundStart)
        FetchType.RESPIRATORY_RATE -> respiratoryRate(data)
        FetchType.RESTING_HR -> sixByte(data, Metric.RESTING_HR)
        FetchType.MAX_HR -> sixByte(data, Metric.MAX_HR)
        FetchType.SLEEP -> sleepMarkers(data)
        FetchType.HRV -> sixByte(data, Metric.HRV)
        else -> emptyList()
    }

    /** Bytes per minute of a round-relative stream, used by the pager's gap-skip. */
    internal fun activityRecordSize(length: Int): Int = if (length % 8 == 0) 8 else 4

    private fun at(start: Instant, minute: Int): Instant = start.plusMillis(minute * MINUTE_MS)

    private fun epoch(data: ByteArray, offset: Int): Instant = Instant.ofEpochSecond(Bytes.u32(data, offset))

    private fun isReading(v: Int): Boolean = v != SENTINEL && v > 0

    private fun activity(data: ByteArray, start: Instant): List<Sample> {
        val size = activityRecordSize(data.size)
        val out = mutableListOf<Sample>()
        for (i in 0 until data.size / size) {
            val base = i * size
            val hr = Bytes.u8(data, base + 3)
            val steps = Bytes.u8(data, base + 2)
            if (isReading(hr)) out += Sample(at(start, i), Metric.HR, hr.toDouble())
            if (isReading(steps)) out += Sample(at(start, i), Metric.STEPS, steps.toDouble())
        }
        return out
    }

    private fun stress(data: ByteArray, start: Instant): List<Sample> = data.indices
        .filter { Bytes.u8(data, it) != SENTINEL }
        .map { Sample(at(start, it), Metric.STRESS, Bytes.u8(data, it).toDouble()) }

    private fun temperature(data: ByteArray, start: Instant): List<Sample> = (0 until data.size / 8)
        .map { Sample(at(start, it), Metric.TEMPERATURE_C, Bytes.i16(data, it * 8 + 2) / 100.0) }

    /** The 6-byte family: `sec u32 @0 · value u8 @5`. */
    private fun sixByte(data: ByteArray, metric: Metric): List<Sample> = (0 until data.size / 6)
        .map { it * 6 }
        .filter { isReading(Bytes.u8(data, it + 5)) }
        .map { Sample(epoch(data, it), metric, Bytes.u8(data, it + 5).toDouble()) }

    private fun respiratoryRate(data: ByteArray): List<Sample> = (0 until data.size / 8)
        .map { it * 8 }
        .filter { isReading(Bytes.u8(data, it + 5)) }
        .map { Sample(epoch(data, it), Metric.RESPIRATORY_RATE, Bytes.u8(data, it + 5).toDouble()) }

    /** Spot SpO₂: version byte `2`, then 65-byte records; the value's high bit is a flag. */
    private fun spo2(data: ByteArray): List<Sample> {
        if (data.isEmpty() || data[0].toInt() != 2) return emptyList()
        return (0 until (data.size - 1) / 65).map { 1 + it * 65 }.map {
            val raw = Bytes.u8(data, it + 4)
            Sample(epoch(data, it), Metric.SPO2, (if (raw >= 128) raw - 128 else raw).toDouble())
        }
    }

    /** Sleep SpO₂: version byte `2`, then 30-byte records; kept only in 1–100. */
    private fun spo2Sleep(data: ByteArray): List<Sample> {
        if (data.isEmpty() || data[0].toInt() != 2) return emptyList()
        return (0 until (data.size - 1) / 30).map { 1 + it * 30 }
            .filter { Bytes.u8(data, it + 4) in 1..100 }
            .map { Sample(epoch(data, it), Metric.SPO2_SLEEP, Bytes.u8(data, it + 4).toDouble()) }
    }

    private fun sleepMarkers(data: ByteArray): List<Sample> = (0 until data.size / SleepParser.RECORD_SIZE)
        .map { Sample(epoch(data, it * SleepParser.RECORD_SIZE), Metric.SLEEP_SESSION, 1.0) }
}

/** Fetch type codes on the `0004`/`0005` channel. */
public object FetchType {
    public const val ACTIVITY: Int = 0x01
    public const val MANUAL_HR: Int = 0x02
    public const val WORKOUTS: Int = 0x05
    public const val STRESS_MANUAL: Int = 0x12
    public const val STRESS: Int = 0x13
    public const val SPO2: Int = 0x25
    public const val SPO2_SLEEP: Int = 0x26
    public const val TEMPERATURE: Int = 0x2E
    public const val RESPIRATORY_RATE: Int = 0x38
    public const val RESTING_HR: Int = 0x3A
    public const val MAX_HR: Int = 0x3D
    public const val SLEEP: Int = 0x48
    public const val HRV: Int = 0x49
}
