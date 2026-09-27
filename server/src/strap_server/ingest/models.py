"""The phone → server push contract. Timestamps are epoch milliseconds, UTC.

Metric names are the STRAP's (the phone sends what it decoded); `upsert.py` maps them to
the server's canonical names. An unknown metric is dropped and counted, never a 422, so a
newer app never loses a whole push to one new stream.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

# Caps sized well above one sync (a first 30-day sync is ~115k samples, sent in pages).
MAX_SAMPLES = 20_000
MAX_SLEEP = 200
MAX_WORKOUTS = 500
MAX_TOTALS = 400
MAX_STAGES = 2_000


class SampleIn(BaseModel):
    metric: str = Field(max_length=64)
    ts: int
    value: float


class SleepIn(BaseModel):
    start_ts: int  # first stage start
    end_ts: int  # last stage end
    kind: Literal["main", "nap"]
    score: int | None = None
    avg_hr: int | None = None
    rem_min: int | None = None
    light_min: int | None = None
    deep_min: int | None = None
    wake_min: int | None = None
    stages: list[tuple[int, int, int]] = Field(default_factory=list, max_length=MAX_STAGES)


class WorkoutIn(BaseModel):
    start_ts: int
    sport: int | None = None
    duration_s: int | None = None
    calories: int | None = None
    distance_m: float | None = None
    avg_hr: int | None = None
    max_hr: int | None = None
    min_hr: int | None = None


class DailyTotalIn(BaseModel):
    read_at: int  # when the strap's since-midnight counter was read
    steps: int | None = None
    distance_m: float | None = None
    calories: float | None = None


class IngestPayload(BaseModel):
    samples: list[SampleIn] = Field(default_factory=list, max_length=MAX_SAMPLES)
    sleep: list[SleepIn] = Field(default_factory=list, max_length=MAX_SLEEP)
    workouts: list[WorkoutIn] = Field(default_factory=list, max_length=MAX_WORKOUTS)
    daily_totals: list[DailyTotalIn] = Field(default_factory=list, max_length=MAX_TOTALS)


class IngestSummary(BaseModel):
    samples_stored: int
    samples_dropped: int
    sleep: int
    workouts: int
    daily_totals: int
    nights_derived: int
    days_derived: int
