package strap.science

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// The science was written in Python and is held to the server's numbers, so the few places
// where Python and Kotlin disagree are spelled out once here.

/** Python's `round(x, digits)`: half-even on the exact binary value. */
internal fun round(x: Double, digits: Int): Double =
    if (!x.isFinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

/** Python's `round(x)`: half-even to an integer. */
internal fun roundInt(x: Double): Long = BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).toLong()

/** Python's `str(float)` for the magnitudes the science prints. */
internal fun pyStr(x: Double): String = if (x == Math.rint(x) && Math.abs(x) < 1e16) "${x.toLong()}.0" else x.toString()

/** Python's `datetime.isoformat()` of an aware datetime. */
internal fun isoformat(t: ZonedDateTime): String {
    val micros = t.nano / 1000
    val base = "%04d-%02d-%02dT%02d:%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    val frac = if (micros != 0) ".%06d".format(micros) else ""
    val total = t.offset.totalSeconds
    val sign = if (total < 0) "-" else "+"
    val abs = Math.abs(total)
    val off = "%s%02d:%02d".format(sign, abs / 3600, abs / 60 % 60) + if (abs % 60 != 0) ":%02d".format(abs % 60) else ""
    return base + frac + off
}

/** An instant as Python prints a UTC-aware datetime. */
internal fun isoformatUtc(t: Instant): String = isoformat(t.atZone(java.time.ZoneOffset.UTC))

/**
 * A REAL column as the server reads it: Postgres prints a float4 as its shortest decimal and
 * psycopg parses that, so 74.6 stays 74.6 rather than widening to 74.5999984741211.
 */
public fun Float.real(): Double = toString().toDouble()

internal fun Instant.floorMinute(): Instant = truncatedTo(ChronoUnit.MINUTES)

internal fun Instant.ms(): Long = toEpochMilli()

internal fun days(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

/** A JSON object from Kotlin values, in the given key order (Python dicts keep theirs). */
public fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*pairs.map { it.first to toJson(it.second) }.toTypedArray()))

public fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Float -> JsonPrimitive(v.toDouble())
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is LocalDate -> JsonPrimitive(v.toString())
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map(::toJson))
    else -> error("no JSON form for ${v::class}")
}

/** `{**a, **b}`. */
internal operator fun JsonObject.plus(other: JsonObject): JsonObject = JsonObject(LinkedHashMap(this).apply { putAll(other) })
