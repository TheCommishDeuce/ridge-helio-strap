"""The ONLY writer of the raw strap tables (sample, sleep_session, workout, device_daily_total).

The SQL is the old server's (healthee@049c9ad `ingest/upsert.py`, `daily_totals.py`),
including its rule that a re-push which omits a field keeps the measured value (COALESCE)
rather than overwriting it with a default.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Cursor

from strap_server.ingest.models import DailyTotalIn, SampleIn, SleepIn, WorkoutIn

# Strap stream name -> canonical server metric (the names the science reads).
# spo2 and spo2_sleep are both blood oxygen; the server windows them itself.
METRIC_NAMES: dict[str, str] = {
    "hr": "hr",
    "hrv": "hrv",
    "spo2": "spo2",
    "spo2_sleep": "spo2",
    "temperature_c": "skin_temp_c",
    "respiratory_rate": "respiratory_rate",
    "stress": "stress",
    "steps": "steps_per_minute",
    # Kept raw though no formula reads them yet (spec/02 §5): the strap's own figures.
    "resting_hr": "resting_hr_device",
    "max_hr": "max_hr_device",
    "manual_hr": "manual_hr",
    "stress_manual": "stress_manual",
}

# Samples inside a stored night change that night's derived vitals.
NIGHT_INPUTS = frozenset({"hr", "hrv", "spo2", "respiratory_rate", "skin_temp_c"})


def epoch_to_utc(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000, tz=UTC)


def local_date(ms: int, tz: str) -> date:
    return epoch_to_utc(ms).astimezone(ZoneInfo(tz)).date()


def upsert_samples(cur: Cursor, user_id: UUID, samples: list[SampleIn]) -> tuple[list[tuple[datetime, str]], int]:
    """Stores samples under canonical names. Returns (stored (ts, metric) pairs, dropped count)."""
    rows = []
    dropped = 0
    for s in samples:
        metric = METRIC_NAMES.get(s.metric)
        if metric is None:
            dropped += 1
            continue
        rows.append((user_id, epoch_to_utc(s.ts), metric, float(s.value)))
    if rows:
        cur.executemany(
            "INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, %s, %s) "
            "ON CONFLICT (user_id, metric, ts) DO UPDATE SET value = EXCLUDED.value",
            rows,
        )
    return [(r[1], r[2]) for r in rows], dropped


def upsert_sleep(cur: Cursor, user_id: UUID, sessions: list[SleepIn]) -> None:
    for s in sessions:
        cur.execute(
            "INSERT INTO sleep_session "
            "(user_id, start_ts, end_ts, kind, score, avg_hr, rem_min, light_min, deep_min, wake_min, stages) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb) "
            "ON CONFLICT (user_id, start_ts) DO UPDATE SET "
            "end_ts = EXCLUDED.end_ts, kind = EXCLUDED.kind, "
            "score = COALESCE(EXCLUDED.score, sleep_session.score), "
            "avg_hr = COALESCE(EXCLUDED.avg_hr, sleep_session.avg_hr), "
            "rem_min = COALESCE(EXCLUDED.rem_min, sleep_session.rem_min), "
            "light_min = COALESCE(EXCLUDED.light_min, sleep_session.light_min), "
            "deep_min = COALESCE(EXCLUDED.deep_min, sleep_session.deep_min), "
            "wake_min = COALESCE(EXCLUDED.wake_min, sleep_session.wake_min), "
            "stages = CASE WHEN jsonb_array_length(EXCLUDED.stages) > 0 "
            "THEN EXCLUDED.stages ELSE sleep_session.stages END",
            (
                user_id, epoch_to_utc(s.start_ts), epoch_to_utc(s.end_ts), s.kind, s.score, s.avg_hr,
                s.rem_min, s.light_min, s.deep_min, s.wake_min, json.dumps([list(st) for st in s.stages]),
            ),
        )


def upsert_workouts(cur: Cursor, user_id: UUID, workouts: list[WorkoutIn]) -> None:
    for w in workouts:
        cur.execute(
            "INSERT INTO workout (user_id, start_ts, sport, duration_s, calories, distance_m, avg_hr, max_hr, min_hr) "
            "VALUES (%s, %s, COALESCE(%s::int, 0), COALESCE(%s::int, 0), %s, %s, %s, %s, %s) "
            "ON CONFLICT (user_id, start_ts) DO UPDATE SET "
            "sport = COALESCE(%s::int, workout.sport), "
            "duration_s = COALESCE(%s::int, workout.duration_s), "
            "calories = COALESCE(EXCLUDED.calories, workout.calories), "
            "distance_m = COALESCE(EXCLUDED.distance_m, workout.distance_m), "
            "avg_hr = COALESCE(EXCLUDED.avg_hr, workout.avg_hr), "
            "max_hr = COALESCE(EXCLUDED.max_hr, workout.max_hr), "
            "min_hr = COALESCE(EXCLUDED.min_hr, workout.min_hr)",
            (
                user_id, epoch_to_utc(w.start_ts), w.sport, w.duration_s, w.calories, w.distance_m,
                w.avg_hr, w.max_hr, w.min_hr, w.sport, w.duration_s,
            ),
        )


def upsert_daily_totals(cur: Cursor, user_id: UUID, tz: str, totals: list[DailyTotalIn]) -> list[date]:
    """Stores each reading under its local day; a later reading of the same day wins, an earlier one never does."""
    days = []
    for t in totals:
        day = local_date(t.read_at, tz)
        days.append(day)
        cur.execute(
            "INSERT INTO device_daily_total (user_id, day, steps, distance_m, calories, read_at, reported_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, now()) "
            "ON CONFLICT (user_id, day) DO UPDATE SET "
            "steps = COALESCE(EXCLUDED.steps, device_daily_total.steps), "
            "distance_m = COALESCE(EXCLUDED.distance_m, device_daily_total.distance_m), "
            "calories = COALESCE(EXCLUDED.calories, device_daily_total.calories), "
            "read_at = EXCLUDED.read_at, reported_at = EXCLUDED.reported_at "
            "WHERE device_daily_total.read_at IS NULL OR EXCLUDED.read_at >= device_daily_total.read_at",
            (user_id, day, t.steps, t.distance_m, t.calories, epoch_to_utc(t.read_at)),
        )
    return days


def nights_containing(cur: Cursor, user_id: UUID, instants: list[datetime]) -> list[tuple[datetime, datetime]]:
    """Stored main sleeps that any of [instants] fall inside — late vitals re-derive their night."""
    if not instants:
        return []
    cur.execute(
        "SELECT start_ts, end_ts FROM sleep_session "
        "WHERE user_id = %s AND kind = 'main' AND start_ts <= %s AND end_ts >= %s "
        "AND EXISTS (SELECT 1 FROM unnest(%s::timestamptz[]) AS incoming(ts) "
        "WHERE incoming.ts >= start_ts AND incoming.ts <= end_ts) ORDER BY start_ts",
        (user_id, max(instants), min(instants), sorted(instants)),
    )
    return [(r[0], r[1]) for r in cur.fetchall()]
