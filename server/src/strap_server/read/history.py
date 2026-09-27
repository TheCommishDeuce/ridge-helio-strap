"""Daily history and workouts — the week/month views read these.

A day without a derived row is simply absent: the client draws a gap, never a zero.
"""

from __future__ import annotations

from datetime import date
from uuid import UUID

from psycopg import Cursor

MAX_DAYS = 400


def daily(cur: Cursor, user_id: UUID, metrics: list[str], first: date, last: date) -> dict:
    """Per metric: [{day, value, flags}] over [first, last], oldest first."""
    if (last - first).days + 1 > MAX_DAYS or last < first:
        raise ValueError(f"range must be 1..{MAX_DAYS} days")
    cur.execute(
        "SELECT metric, day, value, flags FROM derived_daily "
        "WHERE user_id = %s AND metric = ANY(%s) AND day >= %s AND day <= %s ORDER BY metric, day",
        (user_id, metrics, first, last),
    )
    out: dict[str, list[dict]] = {m: [] for m in metrics}
    for metric, day, value, flags in cur.fetchall():
        out[metric].append({"day": day.isoformat(), "value": value, "flags": flags})
    return {"from": first.isoformat(), "to": last.isoformat(), "metrics": out}


def workouts(cur: Cursor, user_id: UUID, first_ts, last_ts) -> list[dict]:
    """Workouts starting in [first_ts, last_ts), newest first."""
    cur.execute(
        "SELECT start_ts, sport, duration_s, calories, distance_m, avg_hr, max_hr, min_hr FROM workout "
        "WHERE user_id = %s AND start_ts >= %s AND start_ts < %s ORDER BY start_ts DESC",
        (user_id, first_ts, last_ts),
    )
    return [
        {"start": int(s.timestamp() * 1000), "sport": sp, "duration_s": d, "calories": c, "distance_m": dist,
         "avg_hr": a, "max_hr": mx, "min_hr": mn}
        for s, sp, d, c, dist, a, mx, mn in cur.fetchall()
    ]
