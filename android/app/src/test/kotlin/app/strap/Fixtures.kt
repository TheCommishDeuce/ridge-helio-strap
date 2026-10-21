package app.strap

import android.app.Application
import app.strap.api.ApiException
import app.strap.api.BackupServer
import app.strap.store.LocalStore
import app.strap.sync.Owner
import app.strap.sync.PhoneScience
import app.strap.sync.Restore
import app.strap.sync.ZoneLog
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import strap.protocol.model.DailyTotals
import strap.protocol.model.Metric
import strap.protocol.model.Sample
import strap.protocol.model.SleepSession
import strap.protocol.model.SleepStage
import strap.protocol.model.Stage
import strap.protocol.model.Workout
import strap.protocol.sync.SyncResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The owner's zone in every test: days start at midnight here. */
const val ZONE = "Europe/Berlin"

fun at(date: String, time: String = "12:00"): Instant = java.time.LocalDateTime.parse("${date}T$time").atZone(ZoneId.of(ZONE)).toInstant()

fun day(date: String): LocalDate = LocalDate.parse(date)

/** The app's objects over a fresh database, wired as StrapApp wires them, around [server]. */
class Phone(var server: FakeServer? = null) {
    init {
        // A phone without a server takes the system's zone as its base: make it the owner's.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZONE))
    }

    val context: Application = RuntimeEnvironment.getApplication()
    val store = LocalStore(context).also { open += it }
    val zones = ZoneLog(context)
    val owner = Owner(store, { server }, zones)
    val science = PhoneScience(store, { server }, owner)
    val restore = Restore(store, { server }, owner, science).also { r -> science.backfill = r::backfill }
}

/** Every store a test opened, closed after it ([PhoneTest]). */
private val open = mutableListOf<LocalStore>()

/** Runs each test on Robolectric and closes the stores it opened. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
abstract class PhoneTest {
    @org.junit.After
    fun closeStores() {
        open.forEach { it.close() }
        open.clear()
    }
}

/** One sync's yield from the strap, as SyncRunner hands it to [LocalStore.save]. */
fun sync(
    completedAt: Instant,
    samples: List<Sample> = emptyList(),
    sleep: List<SleepSession> = emptyList(),
    workouts: List<Workout> = emptyList(),
    totals: DailyTotals? = null,
) = SyncResult(null, true, samples, sleep, workouts, totals, stressBackfillRan = false, napBackfillRan = false, fetches = emptyList(), completedAt = completedAt)

/** A minute of heart rate and steps every [everyMin] minutes over a day: enough for the day passes to write rows. */
fun dayOfSamples(date: String, everyMin: Long = 10): List<Sample> {
    val start = at(date, "00:00")
    return (0 until 24 * 60 / everyMin).flatMap { i ->
        val t = start.plusSeconds(i * everyMin * 60)
        listOf(Sample(t, Metric.HR, 60.0 + i % 20), Sample(t, Metric.STEPS, (i % 7) * 10.0))
    }
}

/** A night from [bed] to [wake], light sleep throughout. */
fun night(bed: Instant, wake: Instant, nap: Boolean = false) =
    SleepSession(bed, 0, 0, 55, 80, listOf(SleepStage(bed, wake, Stage.LIGHT)), 0, ((wake.epochSecond - bed.epochSecond) / 60).toInt(), 0, 0, nap)

/**
 * The server as a backup, in memory: the profile copy, the journal, and a backup of [samples]
 * served [pageSize] at a time. Records what the phone sent.
 */
class FakeServer(
    var profileCopy: JSONObject = JSONObject()
        .put("profile", JSONObject().put("height_cm", 180.0).put("sex", "m").put("dob", "1990-05-01").put("srpa", 1))
        .put("timezone", ZONE).put("zone_changes", JSONArray()).put("weights", JSONArray()),
    var journalEntries: JSONArray = JSONArray(),
    var records: JSONObject = JSONObject().put("sleep", JSONArray()).put("workouts", JSONArray()).put("daily_totals", JSONArray()),
    var samples: List<Triple<Long, String, Double>> = emptyList(),
    private val pageSize: Int = 2,
) : BackupServer {
    var down = false
    val putProfiles = mutableListOf<JSONObject>()
    val added = mutableListOf<JSONObject>()
    val deleted = mutableListOf<String>()

    private fun up() {
        if (down) throw ApiException("Could not reach the server.")
    }

    override suspend fun profile(): JSONObject = up().let { profileCopy }

    override suspend fun journal(from: LocalDate, to: LocalDate): JSONArray = up().let { journalEntries }

    override suspend fun backupRecords(): JSONObject = up().let { JSONObject(records.toString()).put("samples", samples.size.toLong()) }

    override suspend fun backupSamples(afterTs: Long?, afterMetric: String): JSONObject {
        up()
        val sorted = samples.sortedWith(compareBy({ it.first }, { it.second }))
        val rest = if (afterTs == null) sorted else sorted.filter { it.first > afterTs || (it.first == afterTs && it.second > afterMetric) }
        val page = rest.take(pageSize)
        val out = JSONObject().put("samples", JSONArray(page.map { JSONArray().put(it.first).put(it.second).put(it.third) }))
        if (rest.size > pageSize) out.put("next", JSONObject().put("after_ts", page.last().first).put("after_metric", page.last().second))
        return out
    }

    override suspend fun putProfile(profile: JSONObject): JSONObject = up().let { putProfiles += profile; profile }

    override suspend fun addJournal(entry: JSONObject): JSONObject = up().let { added += entry; JSONObject().put("id", "srv-${added.size}") }

    override suspend fun deleteJournal(id: String) {
        up()
        deleted += id
    }
}
