package strap.protocol

import strap.protocol.parse.Config
import strap.protocol.parse.ConfigValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Built from the layouts in spec/01 "Config"; to be joined by bytes read off the strap. */
class ConfigTest {
    // Layout as read off a strap on 2026-09-27 (firmware 0.132.27.2). Personal values (the step
    // goal, the second zone set, workout key 07) are replaced with neutral ones; every length and type is as read.
    private val health = ("04010803011d0110fe0600fe01050a1e0210960700646e78828c960310320400282d32040b00830b00110b01120b01130b01" +
        "140b00160b01181001060001050a0f1e310b01321055040050555a410b014230080043301500440b0145300c0046300e00510b01520310270000d007" +
        "00003075000053012c01c800b80b54035802000058020000a08c00005501e0010000a00556010c000600100057011e0005005a00711000020001210b" +
        "00220b00").unhex()
    private val workout = ("04010901010b0410010200010502065000640078008c00a000b40006061e00dc000602065f00720085009800ab00be0006061e" +
        "00dc00070146001e00640020100000300b0031301100401101df01df421001030001025010000300010251100006000102030405").unhex()

    @Test
    fun `the strap's health group decodes completely`() {
        val g = Config.group(health)!!
        assertTrue(g.complete)
        assertEquals(3, g.version)
        assertEquals(29, g.values.size)
        assertEquals(ConfigValue.Choice(0xfe, listOf(0x00, 0xfe, 0x01, 0x05, 0x0a, 0x1e)), g.values[Config.Health.HR_INTERVAL])
        assertEquals(Config.HrInterval.Continuous, Config.Health.hrInterval(0xfe))
        assertEquals(ConfigValue.Choice(150, listOf(0, 100, 110, 120, 130, 140, 150)), g.values[Config.Health.HR_HIGH_ALERT])
        assertEquals(ConfigValue.Choice(50, listOf(0, 40, 45, 50)), g.values[Config.Health.HR_LOW_ALERT])
        assertEquals(ConfigValue.Flag(true), g.values[Config.Health.STRESS])
        assertEquals(ConfigValue.Choice(85, listOf(0, 80, 85, 90)), g.values[Config.Health.SPO2_LOW_ALERT])
        assertEquals(ConfigValue.ClockTime(8, 0), g.values[0x42])
        assertEquals(ConfigValue.Number(10000, 2000, 30000), g.values[0x52])
    }

    @Test
    fun `the strap's workout group decodes completely`() {
        val g = Config.group(workout)!!
        assertTrue(g.complete)
        assertEquals(11, g.values.size)
        assertEquals(ConfigValue.Numbers(listOf(80, 100, 120, 140, 160, 180), 30, 220), g.values[Config.Workout.HR_ZONES])
        assertEquals(ConfigValue.Choice(1, listOf(0, 1, 2)), g.values[Config.Workout.DETECTION_SENSITIVITY])
        assertEquals(ConfigValue.Choices(listOf(0xdf), listOf(0xdf)), g.values[Config.Workout.DETECTION_CATEGORIES])
        assertEquals(ConfigValue.Choice(0, emptyList()), g.values[0x20]) // with constraints asked, yet none sent
    }

    @Test
    fun `the strap's capabilities`() {
        assertEquals(listOf(0x00, 0x0b, 0x08, 0x09, 0x0a), Config.groups("020305000b08090a".unhex()))
    }

    @Test
    fun `requests`() {
        assertEquals("01", Config.capabilitiesRequest.hex())
        assertEquals("03010800", Config.readRequest(Config.Health.GROUP).hex())
        assertEquals("0301080113", Config.readRequest(Config.Health.GROUP, listOf(Config.Health.STRESS)).hex())
    }

    @Test
    fun `capabilities list the groups`() {
        assertEquals(listOf(0x08, 0x09, 0x0a), Config.groups("0203030809 0a".unhex2()))
        assertNull(Config.groups("040103".unhex2()))
        assertNull(Config.groups("020305080 9".unhex2())) // says five groups, carries two
    }

    @Test
    fun `a health group with constraints decodes every type in it`() {
        val reply = (
            "04 01 08 03 01 05" +
                "01 10 ff 03 00 ff fe" + // HR interval: smart, of off/smart/continuous
                "02 10 96 02 00 96" + // high alert 150 bpm, of off/150
                "13 0b 01" + // stress monitoring on
                "52 03 10270000 e8030000 50c30000" + // steps goal 10000 in 1000..50000
                "42 30 16 1e" // 22:30
            ).unhex2()
        val g = Config.group(reply)!!
        assertEquals(0x08, g.group)
        assertEquals(3, g.version)
        assertTrue(g.complete)
        assertEquals(ConfigValue.Choice(0xff, listOf(0x00, 0xff, 0xfe)), g.values[Config.Health.HR_INTERVAL])
        assertEquals(ConfigValue.Choice(150, listOf(0, 150)), g.values[Config.Health.HR_HIGH_ALERT])
        assertEquals(ConfigValue.Flag(true), g.values[Config.Health.STRESS])
        assertEquals(ConfigValue.Number(10_000, 1_000, 50_000), g.values[0x52])
        assertEquals(ConfigValue.ClockTime(22, 30), g.values[0x42])
    }

    @Test
    fun `hr interval`() {
        assertEquals(Config.HrInterval.Smart, Config.Health.hrInterval(0xff))
        assertEquals(Config.HrInterval.Continuous, Config.Health.hrInterval(0xfe))
        assertEquals(Config.HrInterval.Off, Config.Health.hrInterval(0))
        assertEquals(Config.HrInterval.Every(10), Config.Health.hrInterval(10))
    }

    @Test
    fun `without constraints values stand alone`() {
        val g = Config.group("04 01 09 01 00 03 41 0b 00 42 10 01 40 11 02 03 28".unhex2())!!
        assertEquals(ConfigValue.Flag(false), g.values[Config.Workout.DETECTION_ALERT])
        assertEquals(ConfigValue.Choice(1, emptyList()), g.values[Config.Workout.DETECTION_SENSITIVITY])
        assertEquals(ConfigValue.Choices(listOf(3, 0x28), emptyList()), g.values[Config.Workout.DETECTION_CATEGORIES])
        assertTrue(g.complete)
    }

    @Test
    fun `lists, text and timestamps`() {
        val g = Config.group((
            "04 01 09 01 01 04" +
                "05 02 06 5000 6400 7800 8c00 a000 b400 06 06 1e00 dc00" + // HR zones 80..180, each 30..220
                "02 20 64642e6d6d00 10" + // "dd.mm", max length 16
                "0f 21 6100 10 02 6100 6200" + // "a" of a/b
                "09 40 0010a5d4e8000000" // epoch millis
            ).unhex2())!!
        assertEquals(ConfigValue.Numbers(listOf(80, 100, 120, 140, 160, 180), 30, 220), g.values[Config.Workout.HR_ZONES])
        assertEquals(ConfigValue.Text("dd.mm", emptyList()), g.values[0x02])
        assertEquals(ConfigValue.Text("a", listOf("a", "b")), g.values[0x0f])
        assertEquals(ConfigValue.Timestamp(1_000_000_000_000), g.values[0x09])
        assertTrue(g.complete)
    }

    @Test
    fun `an unknown type keeps what came before and says it stopped`() {
        val g = Config.group("04 01 08 03 00 03 13 0b 01 77 99 00 14 0b 00".unhex2())!!
        assertEquals(mapOf<Int, ConfigValue>(0x13 to ConfigValue.Flag(true)), g.values)
        assertFalse(g.complete)
    }

    @Test
    fun `a cut-off reply is incomplete, not a crash`() {
        val g = Config.group("04 01 08 03 01 02 13 0b 01 01 10 ff 03 00".unhex2())!!
        assertEquals(setOf(0x13), g.values.keys)
        assertFalse(g.complete)
    }

    @Test
    fun `bytes past the announced count mean the layout was misread`() {
        val g = Config.group("04 01 08 03 00 01 13 0b 01 14 0b 00".unhex2())!!
        assertEquals(setOf(0x13), g.values.keys)
        assertFalse(g.complete)
    }

    @Test
    fun `a refused read or another command is not a group`() {
        assertNull(Config.group("04 00 08 03 01 00".unhex2()))
        assertNull(Config.group("06 01".unhex2()))
    }

    private fun String.unhex2() = replace(" ", "").unhex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
