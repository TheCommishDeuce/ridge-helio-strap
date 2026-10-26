package strap.science

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's golden derive fixture: eight synthetic days (server/tests/derive/_seed.py),
 * 172 rows over 23 metrics, value for value and flag for flag. The fixture predates this
 * port and was produced by the old server, so it holds the phone to the same science.
 */
class SeedGoldenTest {
    private val input = Golden.read("seed/input.json").jsonObject
    private val expected = Golden.read("seed/expected_daily.json").jsonArray.map { it.jsonObject }

    @Test
    fun `a full derive reproduces every golden row`() {
        val store = MemoryDerived()
        Science(Golden.data(input), store).rederive()
        val days = input.getValue("days").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }.toSet()
        val actual = store.all().filter { it.day in days }.associateBy { it.day.toString() to it.metric }
        val golden = expected.associateBy { it.getValue("day").jsonPrimitive.content to it.getValue("metric").jsonPrimitive.content }

        assertEquals(golden.keys.sortedBy { it.toString() }, actual.keys.sortedBy { it.toString() }, "metric set differs")
        val mismatches = golden.mapNotNull { (key, row) ->
            val got = actual.getValue(key)
            val load = key.second == Golden.LOAD_METRIC
            (if (load) null else Golden.diff(row["value"], toJson(got.value)))?.let { "$key value $it" }
                ?: Golden.diff(row["flags"], if (load) Golden.loadFlags(got.flags) else got.flags)?.let { "$key flags $it" }
        }
        assertTrue(mismatches.isEmpty(), "derived rows differ from the golden fixture:\n" + mismatches.take(12).joinToString("\n"))
        assertEquals(172, golden.size)
    }
}
