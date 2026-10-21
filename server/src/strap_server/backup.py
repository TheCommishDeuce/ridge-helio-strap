"""The backup the phone restores from (D34 S5): everything the server holds as raw data.

The phone works every number out itself; the server keeps what it was sent so a new phone can
start from it. Records (profile, zones, weights, journal, sleep, workouts, the strap's day
counters) come in one answer; samples in pages ordered by (time, metric), so a restore can
resume where it stopped.
"""

from __future__ import annotations

from uuid import UUID

from psycopg import Cursor

SAMPLE_PAGE = 20_000


def _ms(t) -> int | None:
    return None if t is None else int(t.timestamp() * 1000)


def profile(cur: Cursor, user_id: UUID, default_timezone: str) -> dict:
    """What the phone needs to work the science out itself (D34): the profile, every logged
    weight, the owner's base zone and each recorded zone change. Times are epoch ms."""
    cur.execute("SELECT height_cm, sex, dob, srpa FROM profile WHERE user_id = %s", (user_id,))
    row = cur.fetchone()
    cur.execute("SELECT ts, kg FROM weight_log WHERE user_id = %s ORDER BY ts", (user_id,))
    weights = [{"ts": int(ts.timestamp() * 1000), "kg": kg} for ts, kg in cur.fetchall()]
    cur.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,))
    tz = cur.fetchone()
    cur.execute("SELECT since, timezone FROM zone_change WHERE user_id = %s ORDER BY since", (user_id,))
    changes = [{"since": int(at.timestamp() * 1000), "timezone": name} for at, name in cur.fetchall()]
    return {
        "profile": None if row is None else {
            "height_cm": row[0], "sex": row[1], "dob": row[2].isoformat() if row[2] else None, "srpa": row[3],
        },
        "weights": weights,
        "timezone": tz[0] if tz else default_timezone,
        "zone_changes": changes,
    }


def records(cur: Cursor, user_id: UUID, default_timezone: str) -> dict:
    out = profile(cur, user_id, default_timezone)
    cur.execute(
        "SELECT id::text, kind, ts, amount, unit, name FROM manual_entry WHERE user_id = %s AND kind IN ('caffeine', 'alcohol') ORDER BY ts",
        (user_id,),
    )
    out["journal"] = [{"id": i, "kind": k, "ts": _ms(ts), "amount": a, "unit": u, "name": n} for i, k, ts, a, u, n in cur.fetchall()]
    cur.execute(
        "SELECT start_ts, end_ts, kind, score, avg_hr, rem_min, light_min, deep_min, wake_min, stages, source "
        "FROM sleep_session WHERE user_id = %s ORDER BY start_ts",
        (user_id,),
    )
    out["sleep"] = [
        {"start_ts": _ms(s), "end_ts": _ms(e), "kind": k, "score": sc, "avg_hr": hr, "rem_min": rem, "light_min": li,
         "deep_min": de, "wake_min": wa, "stages": st, "source": src}
        for s, e, k, sc, hr, rem, li, de, wa, st, src in cur.fetchall()
    ]
    cur.execute(
        "SELECT start_ts, sport, duration_s, calories, distance_m, avg_hr, max_hr, min_hr FROM workout WHERE user_id = %s ORDER BY start_ts",
        (user_id,),
    )
    out["workouts"] = [
        {"start_ts": _ms(s), "sport": sp, "duration_s": d, "calories": c, "distance_m": dist, "avg_hr": a, "max_hr": mx, "min_hr": mn}
        for s, sp, d, c, dist, a, mx, mn in cur.fetchall()
    ]
    cur.execute(
        "SELECT day, steps, distance_m, calories, source, read_at, reported_at FROM device_daily_total WHERE user_id = %s ORDER BY day",
        (user_id,),
    )
    out["daily_totals"] = [
        {"day": d.isoformat(), "steps": st, "distance_m": dist, "calories": cal, "source": src, "read_at": _ms(r), "reported_at": _ms(rep)}
        for d, st, dist, cal, src, r, rep in cur.fetchall()
    ]
    cur.execute("SELECT count(*) FROM sample WHERE user_id = %s", (user_id,))
    out["samples"] = cur.fetchone()[0]
    return out


def samples(cur: Cursor, user_id: UUID, after_ts: int | None, after_metric: str, limit: int = SAMPLE_PAGE) -> dict:
    """One page of samples after the (time, metric) cursor; ``next`` is null on the last page."""
    limit = max(1, min(limit, SAMPLE_PAGE))
    if after_ts is None:
        cur.execute("SELECT ts, metric, value FROM sample WHERE user_id = %s ORDER BY ts, metric LIMIT %s", (user_id, limit))
    else:
        cur.execute(
            "SELECT ts, metric, value FROM sample WHERE user_id = %s AND (ts, metric) > (to_timestamp(%s / 1000.0), %s) "
            "ORDER BY ts, metric LIMIT %s",
            (user_id, after_ts, after_metric, limit),
        )
    rows = [[_ms(ts), m, v] for ts, m, v in cur.fetchall()]
    nxt = {"after_ts": rows[-1][0], "after_metric": rows[-1][1]} if len(rows) == limit else None
    return {"samples": rows, "next": nxt}
