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

/** The server as the phone's backup (D34): what [app.strap.sync.Owner] and [app.strap.sync.Restore] fetch and send. */
interface BackupServer {
    /** The profile, weights and zones the phone computes with (D34). */
    suspend fun profile(): JSONObject

    /** The server's backup (D34 S5): everything but the samples, then the samples a page at a time. */
    suspend fun backupRecords(): JSONObject

    suspend fun backupSamples(afterTs: Long?, afterMetric: String): JSONObject

    /** Replaces the server's copy of the profile (D34: the phone owns it). */
    suspend fun putProfile(profile: JSONObject): JSONObject

    suspend fun journal(from: LocalDate, to: LocalDate): JSONArray

    suspend fun addJournal(entry: JSONObject): JSONObject

    suspend fun deleteJournal(id: String)
}

/** The server over HTTP. Every call returns the parsed JSON or throws [ApiException]. */
class ApiClient(private val link: ServerLink) : BackupServer {
    override suspend fun profile(): JSONObject = get("/v1/profile")

    override suspend fun backupRecords(): JSONObject = get("/v1/backup/records")

    override suspend fun backupSamples(afterTs: Long?, afterMetric: String): JSONObject =
        get("/v1/backup/samples" + (afterTs?.let { "?after_ts=$it&after_metric=" + java.net.URLEncoder.encode(afterMetric, "UTF-8") } ?: ""))

    override suspend fun putProfile(profile: JSONObject): JSONObject = JSONObject(request("PUT", "/v1/profile", profile.toString()))

    override suspend fun journal(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/journal?from=$from&to=$to", null))

    override suspend fun addJournal(entry: JSONObject): JSONObject = JSONObject(request("POST", "/v1/journal", entry.toString()))

    override suspend fun deleteJournal(id: String) {
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
