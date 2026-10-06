"""Chart series: raw per-minute points for a day, and bucketed ranges that keep the peaks.

Decision D12: HR, stress and steps are shown at full resolution. A bucket never reports a
bare average — it carries min, max, mean, sum, count and WHEN the max happened, so a
two-minute spike survives every zoom level. Minutes the strap did not measure are absent
(a gap), never zero.

Local days come from `derive._common._day_bounds_utc` and HR validity from
`derive.hr_validity` — the same single definitions the science uses, so a chart cannot
disagree with the numbers derived from the same samples.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from itertools import pairwise
from uuid import UUID

from psycopg import Cursor

from strap_server.derive.hr_validity import HR_VALID_BOUNDS, HR_VALID_SQL
from strap_server.zones import ZoneLike, as_zones

# API name -> (stored metric, extra validity predicate or None).
SERIES: dict[str, tuple[str, str | None]] = {
    "hr": ("hr", HR_VALID_SQL),
    "stress": ("stress", None),
    "steps": ("steps_per_minute", "value > 0 AND value < %s"),
    "hrv": ("hrv", None),
    "spo2": ("spo2", None),
    "skin_temp": ("skin_temp_c", "value > %s"),
    "respiratory_rate": ("respiratory_rate", None),
}
_EXTRA_PARAMS: dict[str, tuple] = {"hr": HR_VALID_BOUNDS, "steps": (250,), "skin_temp": (25.0,)}

BUCKETS: dict[str, str] = {"15m": "15 minutes", "1h": "1 hour", "1d": "1 day"}
MAX_BUCKETS = 5_000  # a year of hours is 8,760: keep a request to a screenful of data
_BUCKET_SECONDS = {"15m": 900, "1h": 3600, "1d": 86400}


def _ms(ts: datetime) -> int:
    return int(ts.timestamp() * 1000)


def _where(name: str) -> tuple[str, tuple]:
    metric, extra = SERIES[name]
    return (f"metric = %s AND {extra}" if extra else "metric = %s"), (metric, *_EXTRA_PARAMS.get(name, ()))


def day_series(cur: Cursor, user_id: UUID, tz: ZoneLike, day: date, names: list[str]) -> dict:
    """Every stored point of each series in the local day, plus the sleep windows to shade.

    The day is the one lived (D31): ``start``/``end`` are its real edges (22 to 26 hours on a
    travel day) and ``timezone`` the zone it began in, which its times are told in."""
    zones = as_zones(tz)
    start, end = zones.bounds(day)
    out: dict[str, list[list[float]]] = {}
    for name in names:
        where, params = _where(name)
        cur.execute(
            f"SELECT ts, value FROM sample WHERE user_id = %s AND {where} AND ts >= %s AND ts < %s ORDER BY ts",
            (user_id, *params, start, end),
        )
        out[name] = [[_ms(ts), v] for ts, v in cur.fetchall()]
    cur.execute(
        "SELECT start_ts, end_ts, kind FROM sleep_session WHERE user_id = %s AND end_ts > %s AND start_ts < %s ORDER BY start_ts",
        (user_id, start, end),
    )
    sleep = [{"start": _ms(s), "end": _ms(e), "kind": k} for s, e, k in cur.fetchall()]
    return {"date": day.isoformat(), "timezone": zones.zone_of(day), "start": _ms(start), "end": _ms(end), "series": out, "sleep": sleep}


def bucket_series(cur: Cursor, user_id: UUID, tz: ZoneLike, name: str, first: date, last: date, bucket: str) -> dict:
    """Buckets over local days [first, last]: start, min, max, mean, sum, count, time of max."""
    if bucket not in BUCKETS:
        raise ValueError(f"bucket must be one of {sorted(BUCKETS)}")
    if last < first:
        raise ValueError("the range ends before it starts")
    span_s = ((last - first).days + 1) * 86400
    if span_s // _BUCKET_SECONDS[bucket] > MAX_BUCKETS:
        raise ValueError(f"more than {MAX_BUCKETS} buckets: use a coarser bucket or a shorter range")
    zones = as_zones(tz)
    edges = _bucket_edges(zones, first, last, bucket)
    where, params = _where(name)
    # Buckets are cut at edges built from each day's own start (D31: a day begins at
    # midnight wherever the owner was), so day buckets follow travel and DST alike;
    # last(ts, value) is the timestamp of the largest value — when the peak happened.
    cur.execute(
        f"SELECT width_bucket(ts, %s::timestamptz[]) AS b, min(value), max(value), avg(value), sum(value), count(*), "
        f"last(ts, value) FROM sample WHERE user_id = %s AND {where} AND ts >= %s AND ts < %s GROUP BY b ORDER BY b",
        (edges, user_id, *params, edges[0], edges[-1]),
    )
    rows = [
        {"t": _ms(edges[b - 1]), "min": mn, "max": mx, "mean": round(avg, 2), "sum": sm, "n": n, "t_max": _ms(tmax)}
        for b, mn, mx, avg, sm, n, tmax in cur.fetchall()
    ]
    return {"metric": name, "bucket": bucket, "from": first.isoformat(), "to": last.isoformat(), "timezone": zones.zone_of(last), "buckets": rows}


def _bucket_edges(zones, first: date, last: date, bucket: str) -> list[datetime]:
    """Bucket starts plus the final end. Day buckets are the days themselves; smaller ones
    step from each day's own start, so they sit on that day's local clock (a half-hour
    zone's hours start at :30 UTC) and never straddle two days."""
    days = zones.starts(first, last)
    if bucket == "1d":
        return days
    step = timedelta(seconds=_BUCKET_SECONDS[bucket])
    edges: list[datetime] = []
    for start, end in pairwise(days):
        t = start
        while t < end:
            edges.append(t)
            t += step
    edges.append(days[-1])
    return edges
