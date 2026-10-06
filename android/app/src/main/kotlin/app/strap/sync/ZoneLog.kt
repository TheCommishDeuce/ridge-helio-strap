package app.strap.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.strap.StrapApp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * The phone's timezone over time (D31): every change, with when it happened, for the server
 * to cut the owner's days by (a day starts at midnight wherever the owner is). Recorded on
 * the system's timezone broadcast and, as a backstop, whenever the app starts or uploads.
 */
class ZoneLog(context: Context) {
    private val prefs = context.getSharedPreferences("zones", Context.MODE_PRIVATE)

    /** Notes [zone] from [at] on, unless it is already the last zone noted. */
    @Synchronized
    fun record(zone: ZoneId = ZoneId.systemDefault(), at: Instant = Instant.now()) {
        val all = entries()
        if (all.lastOrNull()?.zone == zone.id) return
        all += Entry(at.toEpochMilli(), zone.id, pushed = false)
        save(all)
        Log.i("strap", "timezone now ${zone.id}")
    }

    /** Changes the server has not acknowledged, oldest first, in the ingest shape. */
    @Synchronized
    fun unpushed(): JSONArray = JSONArray(entries().filter { !it.pushed }.map { JSONObject().put("since", it.since).put("timezone", it.zone) })

    @Synchronized
    fun markPushed() = save(entries().map { it.copy(pushed = true) }.toMutableList())

    private data class Entry(val since: Long, val zone: String, val pushed: Boolean)

    private fun entries(): MutableList<Entry> = runCatching {
        val a = JSONArray(prefs.getString("log", "[]"))
        MutableList(a.length()) { a.getJSONObject(it).let { o -> Entry(o.getLong("since"), o.getString("zone"), o.getBoolean("pushed")) } }
    }.getOrDefault(mutableListOf())

    private fun save(all: List<Entry>) {
        val a = JSONArray(all.map { JSONObject().put("since", it.since).put("zone", it.zone).put("pushed", it.pushed) })
        prefs.edit().putString("log", a.toString()).apply()
    }
}

/** The system's timezone broadcast (exempt from Android's implicit-broadcast limits). */
class TimezoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val zone = intent.getStringExtra(Intent.EXTRA_TIMEZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        (context.applicationContext as StrapApp).zones.record(zone)
    }
}
