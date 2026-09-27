package strap.protocol.parse

import strap.protocol.Bytes

/**
 * One setting's value as the config endpoint sends it (spec/01 "Config"). With constraints
 * requested, choices carry the values the strap accepts and numbers their range.
 */
public sealed interface ConfigValue {
    public data class Flag(val on: Boolean) : ConfigValue

    /** One byte, 0–255; [options] are the values the strap accepts. */
    public data class Choice(val value: Int, val options: List<Int>) : ConfigValue

    public data class Choices(val values: List<Int>, val options: List<Int>) : ConfigValue

    public data class Number(val value: Long, val min: Long?, val max: Long?) : ConfigValue

    public data class Numbers(val values: List<Int>, val min: Int?, val max: Int?) : ConfigValue

    public data class Text(val value: String, val options: List<String>) : ConfigValue

    public data class ClockTime(val hour: Int, val minute: Int) : ConfigValue

    public data class Timestamp(val epochMillis: Long) : ConfigValue
}

/** A config group as read. [complete] is false when decoding stopped at a type we cannot size. */
public data class ConfigGroup(val group: Int, val version: Int, val values: Map<Int, ConfigValue>, val complete: Boolean)

/** The config endpoint `0x000a` (encrypted): capabilities, and reading a group (spec/01 "Config"). */
public object Config {
    public const val ENDPOINT: Int = 0x000a
    public const val CAPABILITIES_REPLY: Int = 0x02
    public const val READ_REPLY: Int = 0x04

    public val capabilitiesRequest: ByteArray get() = byteArrayOf(0x01)

    /** `03 · constraints · group · n · keys`; no keys = every key the group has. */
    public fun readRequest(group: Int, keys: List<Int> = emptyList()): ByteArray =
        byteArrayOf(0x03, 0x01, group.toByte(), keys.size.toByte()) + ByteArray(keys.size) { keys[it].toByte() }

    /** `02 · version · n · n groups` → the groups the strap supports, or null. */
    public fun groups(payload: ByteArray): List<Int>? {
        if (payload.size < 3 || Bytes.u8(payload, 0) != CAPABILITIES_REPLY) return null
        val n = Bytes.u8(payload, 2)
        if (payload.size < 3 + n) return null
        return List(n) { Bytes.u8(payload, 3 + it) }
    }

    /** `04 · status(1 = ok) · group · version · constraints · n · entries`, or null. */
    public fun group(payload: ByteArray): ConfigGroup? {
        if (payload.size < 6 || Bytes.u8(payload, 0) != READ_REPLY || Bytes.u8(payload, 1) != 1) return null
        val reader = Reader(payload, 6, constraints = Bytes.u8(payload, 4) == 1)
        val values = linkedMapOf<Int, ConfigValue>()
        var complete = true
        for (n in 0 until Bytes.u8(payload, 5)) {
            // A short buffer or an unknown type ends decoding; what came before still stands.
            val entry = try {
                reader.entry()
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: NoSuchElementException) {
                null // a string without its terminator
            }
            if (entry == null) {
                complete = false
                break
            }
            values[entry.first] = entry.second
        }
        return ConfigGroup(Bytes.u8(payload, 2), Bytes.u8(payload, 3), values, complete && reader.atEnd)
    }

    /** Entry: `key · type · value`, little-endian; the value's layout depends on its type. */
    private class Reader(private val b: ByteArray, private var i: Int, private val constraints: Boolean) {
        val atEnd: Boolean get() = i == b.size

        private fun u8() = Bytes.u8(b, i).also { i += 1 }
        private fun i16() = Bytes.i16(b, i).also { i += 2 }
        private fun i32() = (Bytes.u32(b, i).toInt()).also { i += 4 }
        private fun i64() = (Bytes.u32(b, i) or (Bytes.u32(b, i + 4) shl 32)).also { i += 8 }
        private fun bytes(n: Int) = List(n) { u8() }

        private fun text(): String {
            val end = (i until b.size).first { b[it] == 0.toByte() }
            return String(b, i, end - i, Charsets.UTF_8).also { i = end + 1 }
        }

        /** Null for a type we do not know: its length is unknown, so nothing after it can be read. */
        fun entry(): Pair<Int, ConfigValue>? {
            val key = u8()
            val value: ConfigValue = when (u8()) {
                0x0b -> when (u8()) {
                    0 -> ConfigValue.Flag(false)
                    1 -> ConfigValue.Flag(true)
                    else -> return null
                }
                0x10 -> ConfigValue.Choice(u8(), if (constraints) bytes(u8()) else emptyList())
                0x11 -> ConfigValue.Choices(bytes(u8()), if (constraints) bytes(u8()) else emptyList())
                0x01 -> i16().let { v -> if (constraints) ConfigValue.Number(v.toLong(), i16().toLong(), i16().toLong()) else ConfigValue.Number(v.toLong(), null, null) }
                0x03 -> i32().let { v -> if (constraints) ConfigValue.Number(v.toLong(), i32().toLong(), i32().toLong()) else ConfigValue.Number(v.toLong(), null, null) }
                0x50 -> ConfigValue.Number(i32().toLong(), null, null) // no constraints even when asked
                0x02 -> {
                    val values = List(u8()) { i16() }
                    if (constraints) {
                        i += 2 // min and max count
                        ConfigValue.Numbers(values, i16(), i16())
                    } else {
                        ConfigValue.Numbers(values, null, null)
                    }
                }
                0x20 -> ConfigValue.Text(text(), emptyList()).also { if (constraints) i += 1 } // max length
                0x21 -> {
                    val value = text()
                    val options = if (constraints) {
                        i += 1 // max length
                        List(u8()) { text() }
                    } else {
                        emptyList()
                    }
                    ConfigValue.Text(value, options)
                }
                0x30 -> ConfigValue.ClockTime(u8(), u8())
                0x40 -> ConfigValue.Timestamp(i64())
                else -> return null
            }
            return key to value
        }
    }

    /** HEALTH group keys (reference: Gadgetbridge's Zepp OS config service, reading only, D8). */
    public object Health {
        public const val GROUP: Int = 0x08
        public const val HR_INTERVAL: Int = 0x01 // see [hrInterval]
        public const val HR_HIGH_ALERT: Int = 0x02 // bpm, 0 = off
        public const val HR_LOW_ALERT: Int = 0x03
        public const val HR_DURING_ACTIVITY: Int = 0x04
        public const val HR_BROADCAST: Int = 0x05
        public const val SLEEP_HIGH_ACCURACY: Int = 0x11
        public const val SLEEP_BREATHING: Int = 0x12
        public const val STRESS: Int = 0x13
        public const val STRESS_RELAX_REMINDER: Int = 0x14
        public const val SPO2_ALL_DAY: Int = 0x31
        public const val SPO2_LOW_ALERT: Int = 0x32

        /** The HR interval byte: minutes between readings, 0 = off, 0xff = smart, 0xfe = continuous. */
        public fun hrInterval(raw: Int): HrInterval = when (raw) {
            0 -> HrInterval.Off
            0xff -> HrInterval.Smart
            0xfe -> HrInterval.Continuous
            else -> HrInterval.Every(raw)
        }
    }

    public sealed interface HrInterval {
        public data object Off : HrInterval

        public data object Smart : HrInterval

        public data object Continuous : HrInterval

        public data class Every(val minutes: Int) : HrInterval
    }

    /** WORKOUT group keys (same reference). */
    public object Workout {
        public const val GROUP: Int = 0x09
        public const val HR_ZONES: Int = 0x05
        public const val DETECTION_CATEGORIES: Int = 0x40
        public const val DETECTION_ALERT: Int = 0x41
        public const val DETECTION_SENSITIVITY: Int = 0x42 // 0 high, 1 standard, 2 low
    }
}
