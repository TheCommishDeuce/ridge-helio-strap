"""Re-derive the owner's whole history from the raw tables — after an import or a science change.

    python -m strap_server.rederive

Nights and days are derived oldest first, in two-week chunks, one transaction per chunk, so
every baseline (recovery's 42 days, sleep debt's 14 nights) is built from days already
derived. Only days that HAVE raw data are derived — the same rule ingest follows (a sample,
a sleep session's start or end date, a workout, a counter reading). A contiguous range
would hand every unworn day a whole-day calorie estimate made from nothing.
"""

from __future__ import annotations

import sys
from datetime import date, datetime, timedelta
from uuid import UUID

import psycopg

from strap_server.config import get_settings
from strap_server.db import connection
from strap_server.derive import derive_batch
from strap_server.derive.illness import derive_illness_flag
from strap_server.zones import Zones

CHUNK_DAYS = 14


def data_days(conn: psycopg.Connection, user_id: UUID, zones: Zones, since: date | None = None) -> list[date]:
    """Local dates with any raw data (from `since`) — what ingest would have marked as affected.

    The instants come back from SQL at quarter-hour resolution (every zone's midnight is on a
    quarter hour) and are dated here, because a date needs the owner's zone history (D31)."""
    lo = zones.start(since) if since else None
    rows = conn.execute(
        "SELECT DISTINCT to_timestamp(floor(extract(epoch FROM ts) / 900) * 900) FROM sample "
        "WHERE user_id = %(u)s AND (%(lo)s::timestamptz IS NULL OR ts >= %(lo)s) "
        "UNION SELECT start_ts FROM sleep_session WHERE user_id = %(u)s AND (%(lo)s::timestamptz IS NULL OR start_ts >= %(lo)s) "
        "UNION SELECT end_ts FROM sleep_session WHERE user_id = %(u)s AND (%(lo)s::timestamptz IS NULL OR end_ts >= %(lo)s) "
        "UNION SELECT start_ts FROM workout WHERE user_id = %(u)s AND (%(lo)s::timestamptz IS NULL OR start_ts >= %(lo)s)",
        {"u": user_id, "lo": lo},
    ).fetchall()
    days = {zones.date_of(r[0]) for r in rows}
    days |= {r[0] for r in conn.execute("SELECT day FROM device_daily_total WHERE user_id = %s", (user_id,)).fetchall()}
    return sorted(d for d in days if since is None or d >= since)


def rederive(conninfo: str | None, user_id: UUID, log=print, since: date | None = None) -> int:
    """Derives every night and day that has data (from [since] if given); returns days derived."""
    with connection(conninfo) as conn:
        with conn.cursor() as cur:
            zones = Zones.load(cur, user_id)
        days = data_days(conn, user_id, zones, since)
        nights: list[tuple[datetime, datetime]] = conn.execute(
            "SELECT start_ts, end_ts FROM sleep_session WHERE user_id = %s AND kind = 'main' ORDER BY start_ts", (user_id,)
        ).fetchall()
    if not days:
        log("nothing to derive: no raw data")
        return 0
    total = 0
    chunk_start = days[0]
    while chunk_start <= days[-1]:
        chunk_end = chunk_start + timedelta(days=CHUNK_DAYS - 1)
        chunk_days = [d for d in days if chunk_start <= d <= chunk_end]
        chunk_nights = [(s, e) for s, e in nights if chunk_start <= zones.date_of(e) <= chunk_end and (since is None or zones.date_of(e) >= since)]
        if chunk_days or chunk_nights:
            with connection(conninfo) as conn:
                derive_batch(conn, user_id, zones, chunk_nights, chunk_days)
                with conn.cursor() as cur:
                    for day in chunk_days:
                        derive_illness_flag(cur, user_id, zones, day)
            log(f"derived {chunk_start} .. {chunk_end}: {len(chunk_nights)} nights, {len(chunk_days)} days")
        total += len(chunk_days)
        chunk_start = chunk_end + timedelta(days=1)
    return total


def main() -> int:
    settings = get_settings()
    rederive(None, UUID(settings.owner_id))
    return 0


if __name__ == "__main__":
    sys.exit(main())
