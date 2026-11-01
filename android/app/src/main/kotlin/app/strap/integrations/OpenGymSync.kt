package app.strap.integrations

import android.content.Context
import app.strap.api.ApiException
import app.strap.pairing.KeyVault
import app.strap.pairing.OpenGymLink
import app.strap.store.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Keeps the phone's copy of the owner's openGym sessions (D41): after each complete sync and
 * when Fitness opens. Asks `/api/data/rev` first and pulls the whole state only when it moved.
 * Never writes to openGym.
 */
class OpenGymSync(private val store: LocalStore, private val vault: KeyVault) {
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0)

    /** Bumped whenever the stored sessions change, so a shown Fitness tab reloads. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Pulls if connected; a failure is kept for Settings, never thrown. Returns the failure, or null. */
    suspend fun pull(force: Boolean = false): String? = lock.withLock {
        var link = vault.loadOpenGym() ?: return null
        try {
            if (force || stale(ME_AT, ME_EVERY)) {
                OpenGymClient(link).me().optString("token").takeIf { it.isNotBlank() }?.let { fresh ->
                    OpenGymLink.parse(link.baseUrl, fresh)?.let { link = it; vault.saveOpenGym(it) }
                }
                store.setMetaValue(ME_AT, Instant.now().toEpochMilli().toString())
            }
            val client = OpenGymClient(link)
            val rev = client.rev()
            if (force || store.metaValue(REV) != rev.toString()) {
                val body = client.data()
                val (sessions, custom) = OpenGymFormat.parse(body)
                store.replaceGym(sessions)
                _changes.value++
                store.setMetaValue(CUSTOM, JSONObject(custom).toString())
                store.setMetaValue(REV, body.optLong("rev", rev).toString())
            }
            store.setMetaValue(STATUS, "ok@${Instant.now().toEpochMilli()}")
            null
        } catch (e: ApiException) {
            store.setMetaValue(STATUS, "${if (e is OpenGymUnpaired) "unpaired" else "failed"}@${Instant.now().toEpochMilli()}@${e.message}")
            e.message
        }
    }

    /** Pulls only if the last try is more than [age] old: for opening Fitness, so tab switches do not each ask. */
    suspend fun pullIfOlder(age: Duration = Duration.ofMinutes(5)): String? = if (stale(STATUS, age)) pull() else null

    /** What Settings shows: when it last pulled, or why it could not. */
    fun status(): Status? {
        if (vault.loadOpenGym() == null) return null
        val parts = store.metaValue(STATUS)?.split('@', limit = 3) ?: return Status(null, null, false)
        val at = parts.getOrNull(1)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        return Status(at, parts.getOrNull(2).takeIf { parts[0] != "ok" }, parts[0] == "unpaired")
    }

    data class Status(val at: Instant?, val failure: String?, val unpaired: Boolean)

    /** Disconnects: forgets the token and drops the copied sessions. */
    fun forget() {
        vault.forgetOpenGym()
        store.clearGym()
        listOf(REV, STATUS, ME_AT, CUSTOM).forEach { store.setMetaValue(it, "") }
        _changes.value++
    }

    /** The owner's own exercises' names, from the last pull. */
    fun customNames(): Map<String, String> = store.metaValue(CUSTOM)?.takeIf { it.isNotEmpty() }?.let { s ->
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { o.getString(it) }
    } ?: emptyMap()

    private fun stale(key: String, age: Duration): Boolean {
        val at = store.metaValue(key)?.let { it.substringAfter('@', it).substringBefore('@').toLongOrNull() } ?: return true
        return Instant.now().toEpochMilli() - at > age.toMillis()
    }

    private companion object {
        const val REV = "opengym_rev"
        const val STATUS = "opengym_status" // "ok@ms", "failed@ms@message", "unpaired@ms@message"
        const val ME_AT = "opengym_me_at" // ms
        const val CUSTOM = "opengym_custom_names" // {"id": "name"}
        val ME_EVERY: Duration = Duration.ofDays(1)
    }
}

/** openGym's catalogue names (a generated asset, tools/opengym-names.sh) and the owner's own. */
class ExerciseNames(private val context: Context, private val custom: () -> Map<String, String>) {
    private val catalogue: Map<String, String> by lazy {
        runCatching {
            context.assets.open("opengym/exercises.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /** "Barbell bench press"; an id in neither list reads "Exercise <id>". */
    fun of(id: String): String = (custom()[id] ?: catalogue[id])?.replaceFirstChar { it.uppercase() } ?: "Exercise $id"
}
