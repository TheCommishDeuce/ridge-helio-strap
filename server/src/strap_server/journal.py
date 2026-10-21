"""The journal: caffeine, alcohol and weight logged by the owner.

Caffeine and alcohol go to `manual_entry` (kinds the correlation and cut-off analytics read);
weight goes to `weight_log`. The phone owns the journal (D34); the server keeps its copy.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Literal
from uuid import UUID

from psycopg import Connection
from pydantic import BaseModel, Field, model_validator

# Units are fixed per kind so entries stay comparable.
UNITS = {"caffeine": "mg", "alcohol": "drinks", "weight": "kg"}


class JournalIn(BaseModel):
    kind: Literal["caffeine", "alcohol", "weight"]
    ts: datetime  # when it happened (ISO 8601 with offset)
    amount: float = Field(gt=0)
    name: str | None = Field(default=None, max_length=80)

    @model_validator(mode="after")
    def plausible(self) -> JournalIn:
        limits = {"caffeine": 2000, "alcohol": 30, "weight": 400}
        if self.amount > limits[self.kind] or (self.kind == "weight" and self.amount < 20):
            raise ValueError(f"implausible {self.kind} amount")
        if self.ts.tzinfo is None:
            raise ValueError("ts needs a UTC offset")
        return self


def add(conn: Connection, user_id: UUID, entry: JournalIn) -> dict:
    """Stores one entry and returns its id."""
    if entry.kind == "weight":
        conn.execute(
            "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, %s) ON CONFLICT (user_id, ts) DO UPDATE SET kg = EXCLUDED.kg",
            (user_id, entry.ts, entry.amount),
        )
        return {"id": f"weight:{int(entry.ts.timestamp() * 1000)}"}
    row = conn.execute(
        "INSERT INTO manual_entry (user_id, kind, ts, amount, unit, name) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
        (user_id, entry.kind, entry.ts, entry.amount, UNITS[entry.kind], entry.name),
    ).fetchone()
    return {"id": str(row[0])}


class ProfileIn(BaseModel):
    """The owner's profile as the phone holds it (D34: the phone owns it, the server keeps a copy)."""

    height_cm: float | None = Field(default=None, ge=100, le=250)
    sex: Literal["male", "female"] | None = None
    dob: date | None = None
    srpa: int | None = Field(default=None, ge=0, le=4)


def set_profile(conn: Connection, user_id: UUID, p: ProfileIn) -> dict:
    """Replaces the server's copy of the profile."""
    conn.execute(
        "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) VALUES (%s, %s, %s, %s, %s) "
        "ON CONFLICT (user_id) DO UPDATE SET height_cm = EXCLUDED.height_cm, sex = EXCLUDED.sex, "
        "dob = EXCLUDED.dob, srpa = EXCLUDED.srpa, updated_at = now()",
        (user_id, p.height_cm, p.sex, p.dob, p.srpa),
    )
    return {"stored": True}


def entries(conn: Connection, user_id: UUID, first: datetime, last: datetime) -> list[dict]:
    """Every journal entry in [first, last), newest first, one shape for all kinds."""
    rows = conn.execute(
        "SELECT id::text, kind, ts, amount, unit, name FROM manual_entry WHERE user_id = %(u)s AND ts >= %(a)s AND ts < %(b)s "
        "AND kind IN ('caffeine', 'alcohol') "
        "UNION ALL SELECT 'weight:' || (extract(epoch FROM ts) * 1000)::bigint, 'weight', ts, kg, 'kg', NULL FROM weight_log "
        "WHERE user_id = %(u)s AND ts >= %(a)s AND ts < %(b)s ORDER BY 3 DESC",
        {"u": user_id, "a": first, "b": last},
    ).fetchall()
    return [{"id": i, "kind": k, "ts": int(ts.timestamp() * 1000), "amount": a, "unit": u, "name": n} for i, k, ts, a, u, n in rows]


def delete(conn: Connection, user_id: UUID, entry_id: str) -> bool:
    if entry_id.startswith("weight:"):
        ms = int(entry_id.removeprefix("weight:"))
        cur = conn.execute("DELETE FROM weight_log WHERE user_id = %s AND ts = to_timestamp(%s / 1000.0)", (user_id, ms))
    else:
        cur = conn.execute(
            "DELETE FROM manual_entry WHERE user_id = %s AND id::text = %s AND kind IN ('caffeine', 'alcohol')", (user_id, entry_id)
        )
    return cur.rowcount > 0
