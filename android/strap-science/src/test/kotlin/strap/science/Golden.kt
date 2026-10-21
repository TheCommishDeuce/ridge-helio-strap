package strap.science

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.LocalDate

/** A golden case exported from the server (tools/science-golden): its raw inputs and expected outputs. */
internal object Golden {
    fun read(path: String): JsonElement {
        val stream = Golden::class.java.getResourceAsStream("/golden/$path") ?: error("no resource $path")
        val text = (if (path.endsWith(".gz")) java.util.zip.GZIPInputStream(stream) else stream).bufferedReader().readText()
        return Json.parseToJsonElement(text)
    }

    fun data(input: JsonObject, now: () -> Instant = Instant::now, extra: List<Sample> = emptyList()): MemoryData {
        val zones = Zones(
            input.str("timezone")!!,
            input.getValue("zone_changes").jsonArray.map { c -> Instant.ofEpochMilli(c.jsonObject.long("since")) to c.jsonObject.str("timezone")!! },
            now,
        )
        val profile = (input["profile"] as? JsonObject)?.let { p ->
            Profile(p.dbl("height_cm")?.toFloat(), p.str("sex"), p.str("dob")?.let(LocalDate::parse), p.int("srpa"))
        }
        return MemoryData(
            zones,
            samples = input.getValue("samples").jsonArray.map { row ->
                val r = row.jsonArray
                Sample(Instant.ofEpochMilli(r[0].jsonPrimitive.long), r[1].jsonPrimitive.content, r[2].jsonPrimitive.doubleOrNull!!)
            } + extra,
            sessions = input.getValue("sessions").jsonArray.map { it.jsonObject }.map { s ->
                SleepSession(
                    Instant.ofEpochMilli(s.long("start")), Instant.ofEpochMilli(s.long("end")), s.str("kind")!!, s.int("score"), s.int("avg_hr"),
                    s.int("rem_min"), s.int("light_min"), s.int("deep_min"), s.int("wake_min"),
                    s.getValue("stages").jsonArray.map { st -> st.jsonArray.let { Stage(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long, it[2].jsonPrimitive.int) } },
                    s.str("source") ?: "strap_ble",
                )
            },
            workouts = input.getValue("workouts").jsonArray.map { it.jsonObject }.map { w ->
                Workout(
                    Instant.ofEpochMilli(w.long("start")), w.int("sport") ?: 0, w.int("duration_s") ?: 0, w.int("calories"),
                    w.dbl("distance_m")?.toFloat(), w.int("avg_hr"), w.int("max_hr"), w.int("min_hr"),
                )
            },
            totals = input.getValue("totals").jsonArray.map { it.jsonObject }.map { t ->
                DeviceDailyTotal(
                    LocalDate.parse(t.str("day")), t.int("steps"), t.dbl("distance_m"), t.dbl("calories"), t.str("source")!!,
                    Instant.ofEpochMilli(t.long("reported_at")), t["read_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long?.let(Instant::ofEpochMilli),
                )
            },
            profile = profile,
            weights = input.getValue("weights").jsonArray.map { it.jsonObject }.map { w -> WeightEntry(Instant.ofEpochMilli(w.long("ts")), w.dbl("kg")!!.toFloat()) },
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.dbl(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.long

    /** Equal as the server's parity test means it: numbers within 1e-9, everything else exactly. */
    fun same(a: JsonElement?, b: JsonElement?): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            if (x != null && y != null) return Math.abs(x - y) <= 1e-9
            if (a.booleanOrNull != null || b.booleanOrNull != null) return a.booleanOrNull == b.booleanOrNull
        }
        if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        if (a is JsonArray && b is JsonArray) return a.size == b.size && a.indices.all { same(a[it], b[it]) }
        return a == b
    }

    /** Where two JSON values first differ, for a readable failure. */
    fun diff(a: JsonElement?, b: JsonElement?, path: String = ""): String? {
        if (same(a, b)) return null
        if (a is JsonObject && b is JsonObject) {
            for (k in a.keys + b.keys) diff(a[k], b[k], "$path.$k")?.let { return it }
        }
        if (a is JsonArray && b is JsonArray && a.size == b.size) {
            for (i in a.indices) diff(a[i], b[i], "$path[$i]")?.let { return it }
        }
        return "$path: expected $a, got $b"
    }
}
