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

from strap_server import backup, journal
from strap_server.config import Settings, get_settings
from strap_server.db import connection
from strap_server.ingest.models import IngestPayload, IngestSummary
from strap_server.ingest.service import ingest
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


@app.get("/v1/profile")
def get_profile(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """The profile, weights and zones the phone needs to compute the numbers itself (D34)."""
    with connection() as conn, conn.cursor() as cur:
        return backup.profile(cur, user_id, settings.owner_timezone)


@app.put("/v1/profile")
def put_profile(
    profile: journal.ProfileIn,
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """The phone's profile (D34: the phone owns it); the server keeps a copy."""
    with connection() as conn:
        _ensure_owner(conn, user_id, settings)
        return journal.set_profile(conn, user_id, profile)


@app.get("/v1/backup/records")
def get_backup_records(
    user_id: Annotated[UUID, Depends(owner)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> dict:
    """Everything but the samples, for a phone restoring from this server (D34 S5)."""
    with connection() as conn, conn.cursor() as cur:
        return backup.records(cur, user_id, settings.owner_timezone)


@app.get("/v1/backup/samples")
def get_backup_samples(
    user_id: Annotated[UUID, Depends(owner)],
    after_ts: int | None = None,
    after_metric: str = "",
    limit: int = backup.SAMPLE_PAGE,
) -> dict:
    """One page of samples, oldest first; pass ``next`` back for the following page."""
    with connection() as conn, conn.cursor() as cur:
        return backup.samples(cur, user_id, after_ts, after_metric, limit)


def _local_bounds(tz: Zones, first: date, last: date) -> tuple:
    """From the start of [first] to the end of [last], in the owner's days (D31)."""
    return tz.bounds(first)[0], tz.bounds(last)[1]


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
        return journal.add(conn, user_id, entry)


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
