package app.strap.integrations

import app.strap.api.ApiException
import app.strap.pairing.OpenGymLink
import app.strap.pairing.serviceUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/** One logged openGym workout (spec/03 §1). Read-only: openGym owns the sets, the strap the signals (D41). */
data class GymSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val name: String?,
    /** `kg` or `lb`, openGym's setting when pulled. */
    val unit: String,
    /** openGym's own volume (Σ weight × reps). */
    val volume: Double?,
    val entries: List<GymEntry>,
) {
    /** Sets carry the time they were ticked done only on newer openGym versions. */
    val timed: Boolean get() = entries.any { e -> e.sets.any { it.at != null } }
}

/** One exercise in a session: its openGym id and its sets in order. */
data class GymEntry(val exercise: String, val sets: List<GymSet>) {
    /** First and last ticked set, when the sets carry times. */
    val span: Pair<Instant, Instant>? get() = sets.mapNotNull { it.at }.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() }
}

/** A set: weight × reps, or a timed cardio set (minutes at a speed). [at] is when it was ticked done. */
data class GymSet(
    val weight: Double?,
    val reps: Int?,
    val done: Boolean,
    val warmup: Boolean,
    val at: Instant?,
    val minutes: Double?,
    val speed: Double?,
    val rir: Double?,
    val rpe: Double?,
)

/** Parsing openGym's state (spec/03 §1). Tolerant: a malformed workout is skipped, never half-read. */
object OpenGymFormat {
    /** The workouts in a `GET /api/data` body (`{rev, state}`), plus the custom exercises' names. */
    fun parse(body: JSONObject): Pair<List<GymSession>, Map<String, String>> {
        val state = body.optJSONObject("state") ?: body
        val unit = state.optString("unit").takeIf { it == "kg" || it == "lb" } ?: "kg"
        val workouts = state.optJSONArray("workouts") ?: JSONArray()
        val sessions = (0 until workouts.length()).mapNotNull { i -> workouts.optJSONObject(i)?.let { session(it, unit) } }
        val custom = state.optJSONArray("customEx") ?: JSONArray()
        val names = (0 until custom.length()).mapNotNull { i ->
            custom.optJSONObject(i)?.let { c -> c.optString("id").takeIf { it.isNotEmpty() }?.let { id -> c.optString("name").takeIf { it.isNotBlank() }?.let { id to it } } }
        }.toMap()
        return sessions to names
    }

    fun session(w: JSONObject, unit: String): GymSession? = try {
        val start = w.getLong("start")
        val end = w.getLong("end")
        if (end <= start) null else GymSession(
            id = w.getString("id"),
            start = Instant.ofEpochMilli(start),
            end = Instant.ofEpochMilli(end),
            name = w.optString("name").takeIf { it.isNotBlank() },
            unit = unit,
            volume = w.optDouble("vol").takeIf { !it.isNaN() },
            entries = entries(w.optJSONArray("entries") ?: JSONArray()),
        )
    } catch (e: JSONException) {
        null
    }

    fun entries(a: JSONArray): List<GymEntry> = (0 until a.length()).mapNotNull { i ->
        val e = a.optJSONObject(i) ?: return@mapNotNull null
        val sets = e.optJSONArray("sets") ?: JSONArray()
        GymEntry(e.optString("id"), (0 until sets.length()).mapNotNull { j -> sets.optJSONObject(j)?.let(::set) })
    }

    private fun set(s: JSONObject) = GymSet(
        weight = s.num("w"),
        reps = s.num("r")?.toInt(),
        done = s.optBoolean("done", false),
        warmup = s.optBoolean("warmup", false) || s.optString("phase") == "warmup",
        at = s.num("at")?.toLong()?.let(Instant::ofEpochMilli),
        minutes = s.num("min"),
        speed = s.num("speed"),
        rir = s.num("rir"),
        rpe = s.num("rpe"),
    )

    /** Back to the JSON [entries] reads, for the phone's own store. */
    fun toJson(entries: List<GymEntry>): JSONArray = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().put("id", e.exercise).put("sets", JSONArray().apply {
                e.sets.forEach { s ->
                    put(JSONObject().apply {
                        s.weight?.let { put("w", it) }
                        s.reps?.let { put("r", it) }
                        put("done", s.done)
                        if (s.warmup) put("warmup", true)
                        s.at?.let { put("at", it.toEpochMilli()) }
                        s.minutes?.let { put("min", it) }
                        s.speed?.let { put("speed", it) }
                        s.rir?.let { put("rir", it) }
                        s.rpe?.let { put("rpe", it) }
                    })
                }
            }))
        }
    }

    private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
}

/** openGym over HTTP (spec/03 §1). Reads only; the one write is redeeming a pairing code. */
class OpenGymClient(private val link: OpenGymLink) {
    /** `rev` of the profile state: cheap, so a pull happens only when it moved. */
    suspend fun rev(): Long = JSONObject(ok(call(SERVICE, "GET", link.baseUrl + "/api/data/rev", link.token))).getLong("rev")

    suspend fun data(): JSONObject = JSONObject(ok(call(SERVICE, "GET", link.baseUrl + "/api/data", link.token)))

    /** Keeps the token alive: openGym renews a token past half its life when it is used here. */
    suspend fun me(): JSONObject = JSONObject(ok(call(SERVICE, "GET", link.baseUrl + "/api/me", link.token)))

    private fun ok(r: Reply): String = when (r.code) {
        200 -> r.body
        401 -> throw OpenGymUnpaired()
        else -> throw ApiException("openGym answered HTTP ${r.code}.")
    }

    companion object {
        const val SERVICE = "openGym"

        /** Turns the 8-character code from openGym's Settings → "Pair the mobile app" into a link. One-shot, 5 minutes. */
        suspend fun pair(url: String, code: String): OpenGymLink {
            val base = serviceUrl(url) ?: throw ApiException("Needs the https address of your openGym.")
            val clean = code.filterNot { it.isWhitespace() || it == '-' }
            if (clean.length != 8) throw ApiException("The pairing code has 8 characters: openGym → Settings → Pair the mobile app.")
            val r = call(SERVICE, "POST", "$base/api/pair/redeem", null, JSONObject().put("code", clean).toString())
            return when (r.code) {
                200 -> OpenGymLink.parse(base, JSONObject(r.body).optString("token")) ?: throw ApiException("openGym sent no usable token.")
                400 -> throw ApiException("openGym did not accept the code: it is used, expired (5 minutes) or mistyped. Make a new one.")
                else -> throw ApiException("openGym answered HTTP ${r.code}.")
            }
        }
    }
}

/** The token is dead (expired after its 90 days unused, or signed out everywhere): pair again. */
class OpenGymUnpaired : ApiException("openGym no longer accepts this phone. Pair it again in Settings.")
