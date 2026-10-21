package strap.science

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's own outputs over six weeks of the demo person (tools/science-golden/export_demo.py):
 * stress, the strap's counters and summaries, a trip across timezones, a DST change, a nap, a
 * workout without calories and two feverish nights.
 */
class DemoGoldenTest {
    private val input = Golden.read("demo/input.json.gz").jsonObject
    private val now = Instant.ofEpochMilli(input.getValue("now").jsonPrimitive.long)
    private val data = Golden.data(input, now = { now })
    private val store = MemoryDerived().also { Science(data, it).rederive() }

    @Test
    fun `every derived row matches the server's re-derive`() {
        val golden = Golden.read("demo/expected_daily.json.gz").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("day").jsonPrimitive.content to it.getValue("metric").jsonPrimitive.content }
        val actual = store.all().associateBy { it.day.toString() to it.metric }
        assertEquals(golden.keys.sortedBy { it.toString() }, actual.keys.sortedBy { it.toString() }, "metric set differs")
        val mismatches = golden.mapNotNull { (key, row) ->
            val got = actual.getValue(key)
            Golden.diff(row["value"], toJson(got.value))?.let { "$key value $it" } ?: Golden.diff(row["flags"], got.flags)?.let { "$key flags $it" }
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} rows differ:\n" + mismatches.take(12).joinToString("\n"))
    }

    @Test
    fun `every day's summary matches, Charge and the usual-by-now included`() {
        val golden = Golden.read("demo/expected_summary.json.gz").jsonObject
        val science = Science(data, store)
        val mismatches = golden.mapNotNull { (day, expected) ->
            val actual = science.daySummary(LocalDate.parse(day), now).toJson()
            tiesAgree(expected.jsonObject, actual, "heart", "hr")?.let { return@mapNotNull "$day $it" }
            tiesAgree(expected.jsonObject, actual, "stress", "stress")?.let { return@mapNotNull "$day $it" }
            Compare.diff(withoutExtremeTimes(expected.jsonObject), withoutExtremeTimes(actual))
                .filterNot { Compare.serverNotYetMessage(it.path, it.expected) }.firstOrNull()?.let { "$day $it" }
        }
        assertTrue(golden.size > 40)
        assertTrue(mismatches.isEmpty(), "${mismatches.size} summaries differ:\n" + mismatches.take(12).joinToString("\n"))
    }

    /**
     * When several minutes share the day's max (or min), the server reports whichever its table
     * scan meets first, which depends on the order rows were stored in. The phone reports the
     * earliest; any tied minute is right, so this checks the server's pick is a tie.
     */
    private fun tiesAgree(expected: JsonObject, actual: JsonObject, card: String, metric: String): String? {
        val e = (if (card == "heart") expected["heart"]?.jsonObject?.get("today") else expected[card])?.jsonObject ?: return null
        if ("t_max" !in e) return null
        val values = data.samples(metric, Instant.ofEpochMilli(expected.getValue("start").jsonPrimitive.long), Instant.ofEpochMilli(expected.getValue("end").jsonPrimitive.long))
            .associate { it.ts.toEpochMilli() to it.value }
        for ((key, extreme) in listOf("t_max" to "max", "t_min" to "min")) {
            val at = e.getValue(key).jsonPrimitive.long
            if (values[at] != e.getValue(extreme).jsonPrimitive.content.toDouble()) return "$card.$key: ${values[at]} at $at is not the $extreme"
        }
        return null
    }

    private fun withoutExtremeTimes(summary: JsonObject): JsonObject {
        fun strip(o: JsonObject) = JsonObject(o - "t_max" - "t_min")
        val heart = summary["heart"] as? JsonObject
        val today = heart?.get("today") as? JsonObject
        val stress = summary["stress"] as? JsonObject
        return JsonObject(
            summary + listOfNotNull(
                heart?.let { "heart" to JsonObject(it + listOfNotNull(today?.let { t -> "today" to strip(t) })) },
                stress?.let { "stress" to strip(it) },
            ),
        )
    }

    @Test
    fun `the day series and the buckets match`() {
        val golden = Golden.read("demo/expected_series.json.gz").jsonObject
        val science = Science(data, store)
        val names = listOf("hr", "stress", "steps", "hrv", "spo2", "skin_temp", "respiratory_rate", "hrv_sdnn")
        val mismatches = ArrayList<String>()
        for ((day, expected) in golden.getValue("days").jsonObject) {
            Golden.diff(expected, science.daySeries(LocalDate.parse(day), names).toJson())?.let { mismatches += "$day $it" }
        }
        val first = LocalDate.parse(golden.getValue("from").jsonPrimitive.content)
        val last = LocalDate.parse(golden.getValue("to").jsonPrimitive.content)
        for ((key, expected) in golden.getValue("buckets").jsonObject) {
            val (name, bucket) = key.split("/")
            Golden.diff(expected, science.bucketSeries(name, first, last, Bucket.of(bucket)).toJson())?.let { mismatches += "$key $it" }
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} series differ:\n" + mismatches.take(12).joinToString("\n"))
    }

    @Test
    fun `the illness flags match`() {
        val golden = Golden.read("demo/expected_illness.json").jsonArray
        val actual = store.allIllness().map {
            json(
                "date" to it.date.toString(), "severity" to it.severity, "rr_delta_bpm" to it.rrDeltaBpm?.real(),
                "temp_delta_c" to it.tempDeltaC?.real(), "sustained" to it.sustained, "note_ids" to it.noteIds,
            )
        }
        assertEquals(2, golden.size)
        assertEquals(golden.size, actual.size, "flags: expected $golden, got $actual")
        golden.zip(actual).forEach { (e, a) -> assertEquals(null, Golden.diff(e, a as JsonObject)) }
    }
}
