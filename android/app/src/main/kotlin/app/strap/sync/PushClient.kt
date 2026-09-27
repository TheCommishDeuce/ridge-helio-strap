package app.strap.sync

import app.strap.pairing.ServerLink
import app.strap.store.LocalStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the phone's outbox to the server (`POST /v1/ingest`).
 *
 * Order matters for the science: raw samples first, oldest first, in pages; then sleep,
 * workouts and counter readings in one final request — so every night is derived once its
 * samples are already on the server, and the days those nights touch are re-derived with it.
 * A row is marked sent only after a 200, so an interrupted push resumes where it stopped.
 * Called by [SyncRunner] under its lock, so nothing writes the outbox concurrently.
 */
class PushClient(private val store: LocalStore, private val log: (String) -> Unit) {
    data class Outcome(val samples: Int, val records: Int, val failure: String?)

    fun push(link: ServerLink, onProgress: (String) -> Unit = {}): Outcome {
        var sent = 0
        while (true) {
            val page = store.unpushedSamples(PAGE)
            if (page.isEmpty()) break
            onProgress("uploading samples: $sent sent")
            val failure = post(link, JSONObject().put("samples", JSONArray(page.map { it.second })))
            if (failure != null) return Outcome(sent, 0, failure)
            store.markSamplesPushed(page.map { it.first })
            sent += page.size
        }
        val records = store.unpushedRecords()
        val count = listOf("sleep", "workouts", "daily_totals").sumOf { records.getJSONArray(it).length() }
        if (count > 0) {
            onProgress("uploading sleep, workouts and step counter")
            post(link, records)?.let { return Outcome(sent, 0, it) }
            store.markRecordsPushed()
        }
        return Outcome(sent, count, null)
    }

    /** Null on success; otherwise a secret-free sentence. */
    private fun post(link: ServerLink, body: JSONObject): String? {
        val conn = URL("${link.baseUrl}/v1/ingest").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000 // a page is stored and derived before the reply
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${link.token}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            when (val code = conn.responseCode) {
                200 -> {
                    log("pushed: " + conn.inputStream.bufferedReader().use { it.readText() })
                    null
                }
                401 -> "The server refused the token. Check it in the settings."
                else -> "The server answered HTTP $code.".also { log("push failed: HTTP $code") }
            }
        } catch (e: IOException) {
            "Could not reach the server: ${e.javaClass.simpleName}".also { log("push failed: ${e.message}") }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val PAGE = 5_000
    }
}
