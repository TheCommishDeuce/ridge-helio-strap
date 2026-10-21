package strap.science

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `numbers agree within the tolerance, ints and floats alike`() {
        assertEquals(emptyList(), Compare.diff(j("""{"a":1,"b":2.00000000001,"c":[1,2]}"""), j("""{"a":1.0,"b":2.0,"c":[1.0,2]}""")))
    }

    @Test
    fun `every differing leaf is named, including a missing key and a changed length`() {
        val d = Compare.diff(j("""{"a":{"x":1,"y":"s"},"b":[1,2],"c":true}"""), j("""{"a":{"x":2},"b":[1],"c":false}"""))
        assertEquals(listOf(".a.x", ".a.y", ".b", ".c"), d.map { it.path })
    }

    @Test
    fun `ignored paths are skipped and a kind blanks the indices`() {
        val d = Compare.diff(j("""{"flags":{"reported_at":"x","n":1}}"""), j("""{"flags":{"reported_at":"y","n":1}}"""), ignore = { it.endsWith(".reported_at") })
        assertEquals(emptyList(), d)
        assertEquals(".sessions[].stages[]", Compare.kind(".sessions[0].stages[12]"))
    }
}
