"""Deterministic synthetic dataset for the derive parity tests.

Copied from healthee@049c9ad (tests/derive/_seed.py); the golden fixture was produced from
exactly these rows, so they must stay byte-identical. No random:
every value is a closed-form function of the day index, so the fixture is
reproducible on any machine.

Eight consecutive local (Asia/Kolkata) days, each with: a main sleep session +
per-minute overnight HR / periodic HRV·SpO2·RR, a morning moderate walk + a short
vigorous burst (per-minute steps), daytime per-minute HR, and one workout mid-week.
This exercises rhr, the night vitals, SRI + the 4-dim sleep score, sleep debt,
steps/distance/calories, MVPA, Jurca VO2max, cardio load, and recovery.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Asia/Kolkata")

# The owner every seeded row belongs to. Named explicitly at each insert because
# `0007` dropped the transitional `user_id` DEFAULT — omitting it now raises
# NotNullViolation rather than silently landing on the sentinel. The row *shapes*
# built by `samples()`/`_sessions()`/`workouts()` are deliberately unchanged: the
# legacy fixture generator consumes them too, and parity depends on identical input.
_OWNER = UUID("00000000-0000-0000-0000-000000000001")
OWNER = _OWNER

BASE_DAY = date(2026, 3, 1)
N_DAYS = 8
DAYS: list[date] = [BASE_DAY + timedelta(days=k) for k in range(N_DAYS)]

# Profile + a single weight (as-of every day).
PROFILE = {"height_cm": 175.0, "sex": "male", "dob": date(1990, 1, 1)}
WEIGHT_KG = 72.0
# The DAY BEFORE the window, and that is load-bearing since #85: `derive/vo2max.py`
# withholds the estimate when the weight behind its BMI is older than
# `freshness.WEIGHT_MAX_AGE_DAYS` relative to the day being derived. This was
# 2026-02-01 — 28 to 35 days before the derived days — which now (correctly) withholds
# every vo2max row and makes this fixture a test of the freshness gate instead of a
# test of the Jurca science it exists for. The VALUE is untouched, so every legacy
# golden number still stands: only the log date moved.
WEIGHT_TS = datetime(2026, 2, 28, 6, 0, tzinfo=TZ).astimezone(UTC)


def _local(day: date, hour: int, minute: int) -> datetime:
    """A local wall-clock instant on `day`, as UTC."""
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=TZ).astimezone(UTC)


def _night_bounds(day: date) -> tuple[datetime, datetime]:
    """Main sleep for wake-day `day`: previous day 23:00 -> `day` 07:00 local."""
    start = _local(day - timedelta(days=1), 23, 0)
    end = _local(day, 7, 0)
    return start, end


def nights() -> list[tuple[datetime, datetime]]:
    """(start_utc, end_utc) for each night, in order."""
    return [_night_bounds(d) for d in DAYS]


def _sessions() -> list[tuple]:
    """sleep_session rows: (start, end, kind, score, avg_hr, rem, light, deep, wake, stages)."""
    rows = []
    for start, end in nights():
        asleep_end = end - timedelta(minutes=30)  # last 30 min awake
        stages = [
            [int(start.timestamp() * 1000), int(asleep_end.timestamp() * 1000), 2],  # deep
            [int(asleep_end.timestamp() * 1000), int(end.timestamp() * 1000), 7],  # awake
        ]
        rows.append((start, end, "main", 85, 55, 70, 280, 110, 20, stages))
    return rows


def _overnight_samples(day: date, night: tuple[datetime, datetime], k: int) -> list[tuple]:
    """Per-minute HR + periodic HRV/SpO2/RR across one night (deterministic by day)."""
    start, end = night
    rhr = 54 + (k % 4)  # 54..57 bpm — min-of-5-min-avg recovers this exactly
    hrv = 45 + (k % 3) * 3  # 45/48/51 ms
    rr = 14 + (k % 2)  # 14/15
    out: list[tuple] = []
    minute = start
    while minute < end:
        out.append((minute, "hr", float(rhr)))
        if int((minute - start).total_seconds()) % 1800 == 0:  # every 30 min
            out.append((minute, "hrv", float(hrv)))
            out.append((minute, "spo2", 97.0))
            out.append((minute, "respiratory_rate", float(rr)))
        minute += timedelta(minutes=1)
    return out


def _steps_samples(day: date) -> list[tuple]:
    """Morning moderate walk (110 spm) + a short vigorous burst (135 spm)."""
    out: list[tuple] = []
    for m in range(21):  # 08:00..08:20 moderate
        out.append((_local(day, 8, 0) + timedelta(minutes=m), "steps_per_minute", 110.0))
    out.append((_local(day, 8, 29), "steps_per_minute", 120.0))  # prime the vigorous prior
    for m in range(5):  # 08:30..08:34 vigorous
        out.append((_local(day, 8, 30) + timedelta(minutes=m), "steps_per_minute", 135.0))
    return out


def _daytime_hr(day: date) -> list[tuple]:
    """Per-minute waking HR 08:00-09:00 (drives cardio load / zones)."""
    return [(_local(day, 8, 0) + timedelta(minutes=m), "hr", 125.0) for m in range(60)]


def workouts() -> list[tuple]:
    """One workout mid-week: (start, sport, duration_s, calories, distance_m, avg,max,min hr)."""
    start = _local(DAYS[3], 17, 0)
    return [(start, 0, 1800, 200, 3000.0, 130, 150, 100)]


def samples() -> list[tuple]:
    """Every (ts, metric, value) sample row, deduped on (metric, ts)."""
    seen: dict[tuple[str, datetime], float] = {}
    for k, day in enumerate(DAYS):
        for ts, metric, value in (
            _overnight_samples(day, nights()[k], k) + _steps_samples(day) + _daytime_hr(day)
        ):
            seen[(metric, ts)] = value
    return [(ts, metric, value) for (metric, ts), value in seen.items()]


def seed(cur) -> None:
    """Insert the whole synthetic dataset into a fresh schema via one cursor."""
    cur.execute("DELETE FROM derived_daily")
    cur.execute("DELETE FROM sample")
    cur.execute("DELETE FROM sleep_session")
    cur.execute("DELETE FROM workout")
    cur.execute("DELETE FROM weight_log")
    cur.execute("DELETE FROM profile")
    cur.execute(
        # srpa 2 ("light-to-moderate") — Jurca's SELF-REPORTED activity category, now an
        # answer the owner gives rather than something derived from cadence (#108).
        # Deliberately not the reference level, so the fixture exercises a real dummy
        # coefficient (1.06 METs) instead of the zero one.
        "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) VALUES (%s, %s, %s, %s, 2)",
        (_OWNER, PROFILE["height_cm"], PROFILE["sex"], PROFILE["dob"]),
    )
    cur.execute(
        "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, %s)",
        (_OWNER, WEIGHT_TS, WEIGHT_KG),
    )
    cur.executemany(
        "INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, %s, %s) "
        "ON CONFLICT (user_id, metric, ts) DO UPDATE SET value = EXCLUDED.value",
        [(_OWNER, *row) for row in samples()],
    )
    cur.executemany(
        "INSERT INTO sleep_session "
        "(user_id, start_ts, end_ts, kind, score, avg_hr, rem_min, light_min, deep_min, "
        "wake_min, stages) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb)",
        [(_OWNER, *row[:9], json.dumps(row[9])) for row in _sessions()],
    )
    cur.executemany(
        "INSERT INTO workout "
        "(user_id, start_ts, sport, duration_s, calories, distance_m, avg_hr, max_hr, min_hr) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)",
        [(_OWNER, *row) for row in workouts()],
    )
