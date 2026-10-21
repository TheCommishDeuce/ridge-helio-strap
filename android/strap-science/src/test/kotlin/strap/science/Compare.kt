package strap.science

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Where two JSON values differ, leaf by leaf: the golden tests' check of the phone's numbers against
 * the server's outputs (it also ran on the phone as the shadow comparison, D34 S2). Numbers are
 * equal within [tolerance] (the server rounded to 4 dp, so this is effectively exact).
 */
internal object Compare {
    data class Difference(val path: String, val expected: JsonElement?, val actual: JsonElement?) {
        override fun toString(): String = "$path: server $expected, phone $actual"
    }

    fun diff(
        expected: JsonElement?,
        actual: JsonElement?,
        tolerance: Double = 1e-9,
        ignore: (String) -> Boolean = { false },
        path: String = "",
    ): List<Difference> {
        if (ignore(path)) return emptyList()
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        if (e is JsonObject && a is JsonObject) {
            return (e.keys + a.keys).flatMap { k -> diff(e[k], a[k], tolerance, ignore, "$path.$k") }
        }
        if (e is JsonArray && a is JsonArray && e.size == a.size) {
            return e.indices.flatMap { i -> diff(e[i], a[i], tolerance, ignore, "$path[$i]") }
        }
        return if (sameLeaf(e, a, tolerance)) emptyList() else listOf(Difference(path, expected, actual))
    }

    private fun sameLeaf(e: JsonElement, a: JsonElement, tolerance: Double): Boolean {
        if (e is JsonPrimitive && a is JsonPrimitive && !e.isString && !a.isString) {
            val x = e.doubleOrNull
            val y = a.doubleOrNull
            if (x != null && y != null) return Math.abs(x - y) <= tolerance
            if (e.booleanOrNull != null || a.booleanOrNull != null) return e.booleanOrNull == a.booleanOrNull
        }
        return e == a
    }

    /**
     * The server's one known wrong text: every "not computed yet" card says "sleep debt" (fixed on
     * the phone with D34 S3). A comparison skips that message, never its reason.
     */
    fun serverNotYetMessage(path: String, expected: JsonElement?): Boolean =
        path.endsWith(".withheld.message") && (expected as? JsonPrimitive)?.content == "Today's sleep debt has not been computed yet — sync the strap."

    /** A path with its array indices blanked, so the same field on many days counts as one kind. */
    fun kind(path: String): String = path.replace(Regex("\\[\\d+]"), "[]")
}
