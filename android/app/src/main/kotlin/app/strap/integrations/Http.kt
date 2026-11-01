package app.strap.integrations

import app.strap.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A service's answer: the status, the body as text, and the headers Ridge reads (lower-case names). */
internal class Reply(val code: Int, val body: String, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * One HTTP call to a service the owner runs (openGym, Dawarich), off the main thread. Never
 * logs the URL's query or the Authorization header. Only a failure to reach the service
 * throws ([ApiException] naming [service]); every status comes back for the caller to judge.
 */
internal suspend fun call(service: String, method: String, url: String, bearer: String?, json: String? = null): Reply = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false // a redirect would carry the token elsewhere
        conn.setRequestProperty("Accept", "application/json")
        bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (json != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
        Reply(code, body, headers)
    } catch (e: IOException) {
        throw ApiException("Could not reach $service.", e)
    } finally {
        conn.disconnect()
    }
}
