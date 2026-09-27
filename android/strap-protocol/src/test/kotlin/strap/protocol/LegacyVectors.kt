package strap.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Vectors produced by the old, hardware-proven Dart BLE layer (healthee@049c9ad) — see the
 * file's `_provenance`. Matching them is parity with the code that talks to a real strap.
 */
object LegacyVectors {
    val root: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResource("/legacy_dart_vectors.json")).readText()
        Json.parseToJsonElement(text).jsonObject
    }

    fun obj(vararg path: String): JsonObject = path.fold(root as JsonElement) { e, k -> e.jsonObject.getValue(k) }.jsonObject

    fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonObject.hex(key: String): ByteArray = str(key).unhex()

    fun JsonObject.num(key: String): Long = getValue(key).jsonPrimitive.long

    fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
}

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
