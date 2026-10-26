package strap.science

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.LocalTime

// The models as the server's read API answered them (`GET /v1/day/{day}/summary`, `/series`,
// `/buckets`), key for key: what the golden tests compare with the server's own outputs.

private fun <T> Card<T>.toJson(shown: (T) -> JsonObject): JsonObject = when (this) {
    is Card.Shown -> shown(value)
    is Card.Withheld -> json("withheld" to json("reason" to reason, "message" to message))
}

private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

private fun Usual.toJson(): JsonObject = json("median" to median, "n" to n, "until" to until?.let(::hhmm))

private fun DailyNumber.toJson(): JsonObject =
    json("value" to value, "flags" to flags, "baseline" to baseline?.let { json("median" to it.median, "n" to it.n, "z" to it.z) })

private fun DayStats.toJson(): JsonObject = json(
    "min" to min, "max" to max, "mean" to mean, "n" to n, "t_max" to maxAt.ms(), "t_min" to minAt.ms(), "usual" to usual?.toJson(),
)

private operator fun JsonObject.plus(other: JsonObject): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>(this).apply { putAll(other) })

internal fun EnergyCard.toJson(): JsonObject = json(
    "value" to value, "at" to at.ms(), "at_wake" to atWake, "high" to high, "low" to low, "recharged" to recharged,
    "drained" to json("activity" to activity, "stress" to stress, "living" to living),
    "threshold" to json("stress" to threshold, "personal" to personal), "estimated" to estimated,
    "curve" to curve.map { listOf(it.at.ms(), it.value) },
    "note_ids" to listOf("energy_body_battery"),
)

internal fun Card<EnergyCard>.toJson(): JsonObject = toJson { it.toJson() }

private fun SleepCard.toJson(): JsonObject = json(
    "sessions" to nights.map { s ->
        json(
            "start" to s.start.ms(), "end" to s.end.ms(), "kind" to s.kind,
            "minutes" to json("rem" to s.remMin, "light" to s.lightMin, "deep" to s.deepMin, "awake" to s.awakeMin),
            "stages" to s.stages.map { listOf(it.startMs, it.endMs, it.code) },
            "device_score" to s.deviceScore,
        )
    },
    "health" to health.toJson { json("dimensions" to it.dimensions, "flags" to it.flags) },
    "need_min" to needMin,
    "debt" to debt.toJson { json("minutes" to it.minutes, "flags" to it.flags) },
    "regularity" to regularity.toJson { json("sri" to it) },
    "breathing" to breathing.toJson { b ->
        json(
            "value" to b.value, "drops" to b.drops, "hours" to b.hours, "usual" to b.usual?.let { json("median" to it.median, "n" to it.n) },
            "note_ids" to listOf("wearable_spo2_validity"),
        )
    },
    "note_ids" to listOf("no_validated_sleep_score", "wearable_sleep_stage_validity"),
)

internal fun DaySummary.toJson(): JsonObject {
    val steps = activity.steps.toJson { it.toJson() }.let { card ->
        if (until != null && activity.steps is Card.Shown) card + json("usual_by_now" to activity.usualByNow?.toJson()) else card
    }
    return json(
        "date" to date.toString(), "timezone" to timezone, "start" to start.ms(), "end" to end.ms(),
        "recovery" to recovery.toJson { json("value" to it.value, "flags" to it.flags) },
        "energy" to energy.toJson(),
        "strain" to strain.toJson {
            json("value" to it.value, "max" to it.max, "cardio_load" to it.cardioLoad, "typical_load" to it.typicalLoad, "flags" to it.flags)
        },
        "sleep" to sleep.toJson(),
        "steps" to json(
            "steps" to steps, "distance_m" to activity.distanceM.toJson { it.toJson() }, "active_calories" to activity.activeCalories.toJson { it.toJson() },
            "total_calories" to activity.totalCalories.toJson { it.toJson() }, "mvpa_min" to activity.mvpaMin.toJson { it.toJson() },
        ),
        "heart" to json(
            "resting" to heart.resting.toJson { it.toJson() }, "hrv" to heart.hrv.toJson { it.toJson() }, "today" to heart.today.toJson { it.toJson() },
        ),
        "stress" to stress.toJson { it.toJson() } + json("note_ids" to listOf("wearable_stress_validity")),
        "pai" to pai.toJson { p ->
            json(
                "value" to p.value, "today" to p.today, "week" to p.week.map { (d, v) -> json("day" to d.toString(), "points" to v) },
                "goal" to p.goal, "note_ids" to listOf("pai_personal_activity_intelligence"),
            )
        },
        "vo2max" to vo2max.toJson { it.toJson() + json("note_ids" to listOf("non_exercise_vo2max")) },
        "illness" to (illness?.let {
            json(
                "date" to it.date.toString(), "severity" to it.severity, "sustained" to it.sustained, "rr_delta_bpm" to it.rrDeltaBpm,
                "temp_delta_c" to it.tempDeltaC, "note_ids" to it.noteIds, "framing" to it.framing,
            )
        } ?: JsonNull),
    )
}

internal fun DaySeries.toJson(): JsonObject = json(
    "date" to date.toString(), "timezone" to timezone, "start" to start.ms(), "end" to end.ms(),
    "series" to json(*series.map { (k, v) -> k to v.map { listOf(it.at.ms(), it.value) } }.toTypedArray()),
    "sleep" to sleep.map { json("start" to it.start.ms(), "end" to it.end.ms(), "kind" to it.kind) },
)

internal fun BucketSeries.toJson(): JsonObject = json(
    "metric" to metric, "bucket" to bucket.key, "from" to from.toString(), "to" to to.toString(), "timezone" to timezone,
    "buckets" to buckets.map {
        json("t" to it.start.ms(), "min" to it.min, "max" to it.max, "mean" to it.mean, "sum" to it.sum, "n" to it.n, "t_max" to it.maxAt.ms())
    },
)
