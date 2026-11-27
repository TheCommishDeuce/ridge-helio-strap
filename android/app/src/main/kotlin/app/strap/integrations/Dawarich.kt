package app.strap.integrations

import app.strap.api.ApiException
import app.strap.pairing.DawarichLink
import app.strap.pairing.KeyVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

data class LatLon(val lat: Double, val lon: Double)

/** One fix of the tracker: where, and when. */
data class Fix(val at: Instant, val pos: LatLon)

/** How the owner moved over a stretch: walking, running, on a bike, or carried (a tram, a train, a car). */
enum class Moved { WALKED, RAN, RODE, CARRIED }

/** A stretch of a track, moved one way throughout. */
data class Leg(val start: Instant, val end: Instant, val moved: Moved, val path: List<LatLon>) {
    val carried: Boolean get() = moved == Moved.CARRIED
}

/**
 * A track cut at its fixes by how the owner moved (D42, D48): Dawarich's mode is one guess for a
 * whole track and is often wrong (walk + tram as a ride, a ride as a run, a metro inside a walk).
 * The strap decides instead: its steps say walking or running, its heart rate says riding.
 * [ownS] is the time moving: standing still is left out.
 */
data class Split(val legs: List<Leg>, val walkedM: Int, val ranM: Int, val rodeM: Int, val ownS: Int) {
    val carried: List<Leg> get() = legs.filter { it.carried }
    val ownM: Int get() = walkedM + ranM + rodeM

    /** What the track was: walk, run or ride, whichever covered the most ground (a tie is a walk). */
    val moved: Moved get() = listOf(Moved.WALKED to walkedM, Moved.RAN to ranM, Moved.RODE to rodeM).maxBy { it.second }.first
}

/**
 * A track from Dawarich (spec/03 §2). [distanceM] and [mode] are Dawarich's own, for the whole
 * track; [split], once the strap's data has cut it, says how the owner moved.
 */
data class Track(
    val id: Long,
    val start: Instant,
    val end: Instant,
    val mode: String,
    val distanceM: Int?,
    val path: List<LatLon>,
    val fixes: List<Fix> = emptyList(),
    val split: Split? = null,
) {
    val durationS: Int get() = Duration.between(start, end).seconds.toInt()

    /** Distance and time moved by the owner: the split's when known, else Dawarich's whole track. */
    val ownM: Int? get() = split?.ownM ?: distanceM
    val ownS: Int get() = split?.ownS ?: durationS

    /** Walk, run or ride: the strap's verdict when there is one, else Dawarich's. */
    val moved: Moved get() = split?.moved ?: when (mode) {
        "cycling" -> Moved.RODE
        "running" -> Moved.RAN
        else -> Moved.WALKED
    }
}

/** Parsing `GET /api/v1/tracks` (GeoJSON, `[lon, lat]`). Tolerant: a malformed feature is skipped. */
object DawarichFormat {
    /**
     * The tracks Ridge looks at. Dawarich's mode is not trusted (a ride came as `running`), so
     * walking, cycling and running are all read and cut by the strap; driving and stationary are left.
     */
    val SHOWN = setOf("walking", "cycling", "running")

    /** Faster than this between two fixes (km/h), the owner was not walking: a brisk walk is 6–7. */
    const val WALK_KMH = 9.0

    /** At least this many steps a minute (on average over a stretch) is walking. */
    const val WALK_STEPS = 30.0

    /**
     * At least this many steps a minute and this fast (km/h) is running: runners step 150–185 a
     * minute at 8–16 km/h, walkers 90–125 at up to ~7, and a stop at a light still averages over
     * 120 across a 2-minute stretch (spec/03 §2). Not seen on the owner's data: the owner does not run.
     */
    const val RUN_STEPS = 120.0
    const val RUN_KMH = 6.5

    /** Less than this of a track's own movement (m) and it was a ride with a few steps around it: not shown. */
    const val MIN_OWN_M = 100

    /** `GET /api/v1/tracks/{id}/points`: the fixes in time order; latitude and longitude come as strings. */
    fun fixes(a: JSONArray): List<Fix> = (0 until a.length()).mapNotNull { i ->
        val p = a.optJSONObject(i) ?: return@mapNotNull null
        val lat = p.optString("latitude").toDoubleOrNull() ?: return@mapNotNull null
        val lon = p.optString("longitude").toDoubleOrNull() ?: return@mapNotNull null
        val t = p.optLong("timestamp", -1).takeIf { it > 0 } ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else Fix(Instant.ofEpochSecond(t), LatLon(lat, lon))
    }.sortedBy { it.at }

    /** Faster than this (km/h), nobody was pedalling: real rides stayed under 36 between fixes (spec/03 §2). */
    const val RIDE_KMH = 36.0

    /**
     * At least this many steps a minute over a whole step between two fixes is somebody walking
     * all of it (walkers: 90–125), so a speed over [WALK_KMH] there is a GPS jump, not a ride.
     */
    const val STEADY_STEPS = 80.0

    /** Slower than this (km/h) between two fixes, the owner stood still: not counted as time moving. */
    const val STILL_KMH = 1.0

    /**
     * Cuts a track at its fixes by how the owner moved over each step between two (D42, D48):
     * running-level steps and speed → ran; at walking speed or slower → walked; faster, with
     * walking steps all through it → walked (a GPS jump). What is left is faster than walking, and
     * each unbroken stretch of it is decided as one: **rode** when the strap's heart rate over it
     * ([hr], per minute) averages at least [rideHr] (where zone 2 starts) and no step is over
     * [RIDE_KMH]; else **carried** (heart rate falls on a tram and stays up on a bike). Without
     * heart rate for a stretch or without [rideHr], a workout the strap recorded ([workouts])
     * says it was a ride, as before D48.
     * Minutes with no steps sample count as 0 when the strap sent any step for the track, and as
     * unknown (speed decides) when it sent none. Null with fewer than two fixes.
     */
    fun split(
        fixes: List<Fix>,
        steps: Map<Instant, Double>,
        workouts: List<Pair<Instant, Instant>>,
        hr: Map<Instant, Double> = emptyMap(),
        rideHr: Double? = null,
    ): Split? {
        if (fixes.size < 2) return null
        val worn = steps.keys.any { !it.isBefore(fixes.first().at.minusSeconds(60)) && !it.isAfter(fixes.last().at) }
        val n = fixes.size - 1
        val secs = LongArray(n)
        val dist = DoubleArray(n)
        val speed = DoubleArray(n)
        val kind = arrayOfNulls<Moved>(n) // null: faster than walking, decided below
        val stepless = BooleanArray(n) // the strap was worn and counted (next to) no steps
        // The minutes a stretch overlaps: from its first minute up to, not including, a minute starting at its end or later.
        fun minutes(from: Instant, to: Instant) = generateSequence(from.truncatedTo(ChronoUnit.MINUTES)) { it.plusSeconds(60) }.takeWhile { it.isBefore(to) }.toList()
            .ifEmpty { listOf(from.truncatedTo(ChronoUnit.MINUTES)) }
        for (i in 0 until n) {
            val a = fixes[i]; val b = fixes[i + 1]
            val s = Duration.between(a.at, b.at).seconds
            val m = metres(a.pos, b.pos)
            val kmh = if (s > 0) m / s * 3.6 else Double.MAX_VALUE
            val stepsPerMin = if (worn) minutes(a.at, b.at).let { ms -> ms.sumOf { steps[it] ?: 0.0 } / ms.size } else null
            secs[i] = s; dist[i] = m; speed[i] = kmh
            stepless[i] = stepsPerMin != null && stepsPerMin < WALK_STEPS
            kind[i] = when {
                stepsPerMin != null && stepsPerMin >= RUN_STEPS && kmh >= RUN_KMH -> Moved.RAN
                kmh <= WALK_KMH -> Moved.WALKED
                stepsPerMin != null && stepsPerMin >= STEADY_STEPS -> Moved.WALKED
                else -> null
            }
        }
        // Each unbroken stretch faster than walking, as one: a single step's heart rate lags (the first
        // minutes of a ride, the first after boarding), the stretch's average does not.
        var i = 0
        while (i < n) {
            if (kind[i] != null) { i++; continue }
            var j = i
            while (j < n && kind[j] == null) j++
            val beats = minutes(fixes[i].at, fixes[j].at).mapNotNull { hr[it] }
            val tooFast = (i until j).any { speed[it] > RIDE_KMH }
            for (k in i until j) {
                val rode = when {
                    tooFast -> false
                    rideHr != null && beats.isNotEmpty() -> beats.average() >= rideHr
                    else -> fixes[k].at.plusSeconds(secs[k] / 2).let { mid -> workouts.any { (ws, we) -> !mid.isBefore(ws) && mid.isBefore(we) } }
                }
                kind[k] = if (rode) Moved.RODE else Moved.CARRIED
            }
            i = j
        }
        // A slow, stepless step between two carried ones is the tram standing at a stop: carried too.
        for (k in 1 until n - 1) {
            if (kind[k] == Moved.WALKED && stepless[k] && kind[k - 1] == Moved.CARRIED && kind[k + 1] == Moved.CARRIED) kind[k] = Moved.CARRIED
        }
        val legs = mutableListOf<Leg>()
        val metres = DoubleArray(Moved.entries.size)
        var ownS = 0L
        var from = 0
        for (k in 0 until n) {
            val moved = kind[k]!!
            metres[moved.ordinal] += dist[k]
            if (moved != Moved.CARRIED && speed[k] >= STILL_KMH) ownS += secs[k]
            if (k == n - 1 || kind[k + 1] != moved) {
                legs += Leg(fixes[from].at, fixes[k + 1].at, moved, fixes.subList(from, k + 2).map { it.pos })
                from = k + 1
            }
        }
        return Split(legs, metres[Moved.WALKED.ordinal].toInt(), metres[Moved.RAN.ordinal].toInt(), metres[Moved.RODE.ordinal].toInt(), ownS.toInt())
    }

    /** Tracks less than this apart (s) are one outing Dawarich cut at a pause (D49). */
    const val JOIN_GAP_S = 600L

    /**
     * Joins the pieces of one outing (D49): Dawarich ends a track at a pause of a few minutes (a
     * light, a shop door) and starts another, so one ride came as two tracks 6 minutes apart and
     * a morning walk as three. [tracks] in time order; each run of them less than [JOIN_GAP_S]
     * apart becomes one track from the first's start to the last's end, under the first's id,
     * with the fixes of all (so the pause is one more step for [split] to decide), Dawarich's
     * distances added up and the mode of the longest piece. If a piece came without fixes the
     * whole is left uncut rather than cut from part of it.
     */
    fun join(tracks: List<Track>): List<Track> {
        val out = mutableListOf<MutableList<Track>>()
        for (t in tracks) {
            val last = out.lastOrNull()?.last()
            if (last != null && Duration.between(last.end, t.start).seconds < JOIN_GAP_S) out.last() += t else out += mutableListOf(t)
        }
        return out.map { pieces ->
            if (pieces.size == 1) pieces[0]
            else pieces[0].copy(
                end = pieces.maxOf { it.end },
                mode = pieces.maxBy { it.distanceM ?: 0 }.mode,
                distanceM = pieces.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(),
                path = pieces.flatMap { it.path },
                fixes = if (pieces.any { it.fixes.isEmpty() }) emptyList() else pieces.flatMap { it.fixes }.sortedBy { it.at },
            )
        }
    }

    /** Great-circle distance (haversine, mean Earth radius). */
    fun metres(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun tracks(body: JSONObject): List<Track> {
        val features = body.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i -> features.optJSONObject(i)?.let(::track) }
    }

    private fun track(f: JSONObject): Track? = try {
        val p = f.getJSONObject("properties")
        val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: JSONArray()
        Track(
            id = p.getLong("id"),
            start = OffsetDateTime.parse(p.getString("start_at")).toInstant(),
            end = OffsetDateTime.parse(p.getString("end_at")).toInstant(),
            mode = p.optString("dominant_mode"),
            distanceM = p.optDouble("distance").takeIf { !it.isNaN() }?.toInt(),
            path = (0 until coords.length()).mapNotNull { j ->
                coords.optJSONArray(j)?.takeIf { it.length() >= 2 }?.let { c ->
                    LatLon(c.optDouble(1), c.optDouble(0)).takeIf { !it.lat.isNaN() && !it.lon.isNaN() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
                }
            },
        )
    } catch (e: JSONException) {
        null
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}

/**
 * The owner's Dawarich, read on demand (D42): tracks are fetched when a day is shown and kept
 * only in memory, never in the phone's store or the backup.
 */
class Dawarich(private val vault: KeyVault) {
    private class Cached(val at: Instant, val tracks: List<Track>)

    private val cache = HashMap<Pair<Instant, Instant>, Cached>()
    private val lock = Mutex()

    val connected: Boolean get() = vault.loadDawarich() != null

    /** The tracks that started in [from, until) with their fixes, the pieces of one outing joined (D49), not yet cut ([DawarichFormat.split]); empty when not connected. */
    suspend fun tracks(from: Instant, until: Instant): List<Track> {
        val link = vault.loadDawarich() ?: return emptyList()
        val key = from to until
        lock.withLock {
            cache[key]?.takeIf { Duration.between(it.at, Instant.now()) < freshFor(until) }?.let { return it.tracks }
        }
        val tracks = fetch(link, from, until).filter { it.mode in DawarichFormat.SHOWN && !it.start.isBefore(from) && it.start.isBefore(until) }.sortedBy { it.start }
            .map { t -> t.copy(fixes = runCatching { points(link, t.id) }.getOrDefault(emptyList())) } // uncut if the fixes cannot be read
            .let(DawarichFormat::join)
        lock.withLock { cache[key] = Cached(Instant.now(), tracks) }
        return tracks
    }

    fun forget() {
        vault.forgetDawarich()
        cache.clear()
    }

    /** A day still running changes as the tracker uploads; a past one hardly ever. */
    private fun freshFor(until: Instant): Duration = if (until.isAfter(Instant.now())) Duration.ofMinutes(5) else Duration.ofHours(6)

    companion object {
        const val SERVICE = "Dawarich"

        /** Every page of tracks overlapping [from, until). */
        suspend fun fetch(link: DawarichLink, from: Instant, until: Instant): List<Track> {
            val out = mutableListOf<Track>()
            var page = 1
            do {
                val r = call(SERVICE, "GET", link.baseUrl + "/api/v1/tracks?start_at=${q(from)}&end_at=${q(until)}&per_page=100&page=$page", link.apiKey)
                when (r.code) {
                    200 -> {}
                    401 -> throw ApiException("Dawarich refused the API key.")
                    else -> throw ApiException("Dawarich answered HTTP ${r.code}.")
                }
                out += try { DawarichFormat.tracks(JSONObject(r.body)) } catch (e: JSONException) { throw ApiException("Dawarich sent something that is not tracks.", e) }
                val pages = r.header("x-total-pages")?.toIntOrNull() ?: 1
            } while (++page <= minOf(pages, MAX_PAGES))
            return out
        }

        /** A track's fixes, every page. */
        suspend fun points(link: DawarichLink, id: Long): List<Fix> {
            val out = JSONArray()
            var page = 1
            do {
                val r = call(SERVICE, "GET", link.baseUrl + "/api/v1/tracks/$id/points?per_page=1000&page=$page", link.apiKey)
                if (r.code != 200) throw ApiException("Dawarich answered HTTP ${r.code}.")
                val a = try { JSONArray(r.body) } catch (e: JSONException) { throw ApiException("Dawarich sent something that is not points.", e) }
                for (i in 0 until a.length()) out.put(a.get(i))
                // No paging headers here (spec/03 §2): a full page means there may be more.
            } while (a.length() >= 1000 && ++page <= MAX_PAGES)
            return DawarichFormat.fixes(out)
        }

        /** Settings' Test: the key works and the server answers tracks. */
        suspend fun test(link: DawarichLink): Int = fetch(link, Instant.now().minus(Duration.ofDays(7)), Instant.now()).count { it.mode in DawarichFormat.SHOWN }

        private fun q(t: Instant) = URLEncoder.encode(t.toString(), "UTF-8")

        private const val MAX_PAGES = 20
    }
}
