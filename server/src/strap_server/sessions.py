"""The owner's workout changes (D43): sessions added by hand, and edits or hides of the strap's workouts.

The phone owns them and applies them over the strap's records (strap-science `Sessions`); the
server keeps a copy for restore and never applies them itself. Each is a `manual_entry` row of
kind 'session': `ts`..`end_ts` is the window, `name` the action, and `flags` the sport and, for an
edit or a hide, the start of the strap workout it points at.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Literal
from uuid import UUID

from psycopg import Connection
from psycopg.types.json import Jsonb
from pydantic import BaseModel, Field, model_validator

KIND = "session"
LONGEST = timedelta(hours=24)


class SessionIn(BaseModel):
    action: Literal["add", "edit", "hide"]
    start: datetime
    end: datetime
    sport: int = Field(ge=0, le=100_000)
    target: datetime | None = None  # the strap workout's start, for an edit or a hide

    @model_validator(mode="after")
    def plausible(self) -> SessionIn:
        if any(t is not None and t.tzinfo is None for t in (self.start, self.end, self.target)):
            raise ValueError("times need a UTC offset")
        if not (timedelta(0) < self.end - self.start <= LONGEST):
            raise ValueError("a session ends after it starts, within a day")
        if (self.target is None) != (self.action == "add"):
            raise ValueError("an edit or a hide names its workout's start; an added session doesn't")
        return self


def add(conn: Connection, user_id: UUID, s: SessionIn) -> dict:
    """Stores one change and returns its id."""
    flags = {"sport": s.sport}
    if s.target is not None:
        flags["target"] = int(s.target.timestamp() * 1000)
    row = conn.execute(
        "INSERT INTO manual_entry (user_id, kind, ts, end_ts, name, flags) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (user_id, KIND, s.start, s.end, s.action, Jsonb(flags)),
    ).fetchone()
    return {"id": str(row[0])}


def delete(conn: Connection, user_id: UUID, session_id: str) -> bool:
    cur = conn.execute(
        "DELETE FROM manual_entry WHERE user_id = %s AND id::text = %s AND kind = %s",
        (user_id, session_id, KIND),
    )
    return cur.rowcount > 0


def all_of(conn_or_cur, user_id: UUID) -> list[dict]:
    """Every change, oldest first, as the phone restores them (times in epoch ms)."""
    rows = conn_or_cur.execute(
        "SELECT id::text, name, ts, end_ts, flags FROM manual_entry WHERE user_id = %s AND kind = %s ORDER BY ts",
        (user_id, KIND),
    ).fetchall()
    return [
        {
            "id": i,
            "action": a,
            "start": int(s.timestamp() * 1000),
            "end": int(e.timestamp() * 1000),
            "sport": f.get("sport", 0),
            "target": f.get("target"),
        }
        for i, a, s, e, f in rows
    ]
