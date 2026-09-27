package app.strap.api

import app.strap.pairing.ServerLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Reads from the server's API. Every call returns the parsed JSON or throws [ApiException]. */
class ApiClient(private val link: ServerLink) {
    suspend fun summary(day: LocalDate): JSONObject = get("/v1/day/$day/summary")

    suspend fun daySeries(day: LocalDate, metrics: String): JSONObject = get("/v1/day/$day/series?metrics=$metrics")

    suspend fun buckets(name: String, from: LocalDate, to: LocalDate, bucket: String): JSONObject =
        get("/v1/series/$name?from=$from&to=$to&bucket=$bucket")

    suspend fun daily(metrics: String, from: LocalDate, to: LocalDate): JSONObject = get("/v1/daily?metrics=$metrics&from=$from&to=$to")

    suspend fun workouts(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/workouts?from=$from&to=$to", null))

    suspend fun journal(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/journal?from=$from&to=$to", null))

    suspend fun addJournal(entry: JSONObject): JSONObject = JSONObject(request("POST", "/v1/journal", entry.toString()))

    suspend fun deleteJournal(id: String) {
        request("DELETE", "/v1/journal/$id", null)
    }

    private suspend fun get(path: String): JSONObject = JSONObject(request("GET", path, null))

    private suspend fun request(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val conn = URL(link.baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000 // a weight entry re-derives before answering
            conn.setRequestProperty("Authorization", "Bearer ${link.token}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                401 -> throw ApiException("The server refused the token.")
                else -> throw ApiException("The server answered HTTP $code.")
            }
        } catch (e: IOException) {
            throw ApiException("Could not reach the server.", e)
        } finally {
            conn.disconnect()
        }
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
