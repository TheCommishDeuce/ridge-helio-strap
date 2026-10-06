"""HTTP API. Single owner; the phone authenticates with one bearer token.

    uv run uvicorn strap_server.api:app --host 0.0.0.0 --port 8766
"""

from __future__ import annotations

import hashlib
import hmac
from datetime import date
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Query, status

from strap_server import journal
from strap_server.config import Settings, get_settings
from strap_server.db import connection
from strap_server.ingest.models import IngestPayload, IngestSummary
from strap_server.ingest.service import ingest
from strap_server.read import history, series, summary
from strap_server.zones import Zones

app = FastAPI(title="strap", docs_url=None, redoc_url=None)


def owner(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> UUID:
    """The owner's id when the bearer token matches; 401 otherwise, never saying why."""
    expected = settings.device_token_sha256.lower()
    token = (authorization or "").removeprefix("Bearer ").strip()
    presented = hashlib.sha256(token.encode()).hexdigest()
    if not expected or not token or not hmac.compare_digest(presented, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "unauthorized")
    return UUID(settings.owner_id)


@app.get("/healthz")
def healthz() -> dict:
    with connection() as conn:
        conn.execute("SELECT 1")
    return {"ok": True}


def _ensure_owner(conn, user_id: UUID, settings: Settings) -> None:
    """The owner's row, created by the first write of any kind (a fresh server has none)."""
    conn.execute(
        "INSERT INTO app_user (id, timezone) VALUES (%s, %s) ON CONFLICT (id) DO NOTHING",
        (user_id, settings.owner_timezone),
    )


def _owner_tz(conn, user_id: UUID, settings: Settings) -> Zones:
    """The owner's zone over time (D31); the configured zone before the first write."""
    with conn.cursor() as cur:
        zones = Zones.load(cur, user_id)
    exists = conn.execute("SELECT 1 FROM app_user WHERE id = %s", (user_id,)).fetchone()
    return zones if exists else Zones(settings.owner_timezone)


@app.get("/v1/day/{day}/series")
def get_day_series(
    day: date,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    metrics: str = "hr,stress,steps",
) -> dict:
    """Raw per-minute points for one local day (D12) and the sleep windows to shade."""
    names = [m for m in metrics.split(",") if m]
    unknown = [m for m in names if m not in series.SERIES]
    if unknown or not names:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, f"unknown series: {unknown}; known: {sorted(series.SERIES)}")
    with connection() as conn, conn.cursor() as cur:
        return series.day_series(cur, user_id, _owner_tz(conn, user_id, settings), day, names)


@app.get("/v1/day/{day}/summary")
def get_day_summary(
    day: date,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """The day's cards, each a value with context or a named withheld reason."""
    with connection() as conn, conn.cursor() as cur:
        return summary.day_summary(cur, user_id, _owner_tz(conn, user_id, settings), day)


@app.get("/v1/series/{name}")
def get_bucket_series(
    name: str,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
    bucket: str = "1h",
) -> dict:
    """Buckets that keep the peaks: min, max, mean, sum, count and the time of the max."""
    if name not in series.SERIES:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"unknown series; known: {sorted(series.SERIES)}")
    with connection() as conn, conn.cursor() as cur:
        try:
            return series.bucket_series(cur, user_id, _owner_tz(conn, user_id, settings), name, start, end, bucket)
        except ValueError as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e)) from e


def _local_bounds(tz: Zones, first: date, last: date) -> tuple:
    from strap_server.derive._common import _day_bounds_utc

    return _day_bounds_utc(first, tz)[0], _day_bounds_utc(last, tz)[1]


@app.get("/v1/daily")
def get_daily(
    user_id: Annotated[UUID, Depends(owner)],
    metrics: str,
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> dict:
    """Derived daily rows per metric (value + flags); absent days are gaps."""
    with connection() as conn, conn.cursor() as cur:
        try:
            return history.daily(cur, user_id, [m for m in metrics.split(",") if m], start, end)
        except ValueError as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e)) from e


@app.get("/v1/workouts")
def get_workouts(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> list[dict]:
    with connection() as conn, conn.cursor() as cur:
        return history.workouts(cur, user_id, *_local_bounds(_owner_tz(conn, user_id, settings), start, end))


@app.get("/v1/journal")
def get_journal(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
    start: Annotated[date, Query(alias="from")],
    end: Annotated[date, Query(alias="to")],
) -> list[dict]:
    with connection() as conn:
        return journal.entries(conn, user_id, *_local_bounds(_owner_tz(conn, user_id, settings), start, end))


@app.post("/v1/journal")
def post_journal(
    entry: journal.JournalIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return journal.add(conn, None, user_id, entry)


@app.delete("/v1/journal/{entry_id}")
def delete_journal(entry_id: str, user_id: Annotated[UUID, Depends(owner)]) -> dict:
    with connection() as conn:
        if not journal.delete(conn, user_id, entry_id):
            raise HTTPException(status.HTTP_404_NOT_FOUND, "no such entry")
    return {"deleted": entry_id}


@app.post("/v1/ingest")
def post_ingest(
    payload: IngestPayload,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> IngestSummary:
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return ingest(conn, user_id, payload)
