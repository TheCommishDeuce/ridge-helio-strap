"""Shared plumbing for the derive package.

Small helpers every derivation reuses: the `derived_daily` upsert, sleep-window
statistics, the local-day <-> UTC bounds, profile/weight loading, and the score
clamp. Ported verbatim from the legacy v2 derive module — only the data plumbing
(the DB pool lives in ``healthee.core.db``) and the type hints are new; the SQL,
rounding, and math are identical.

All daily metrics anchor on the user's local wake date. The timezone is threaded
in as an IANA name (``tz: str``) rather than read from a module constant: 6.3b
removed the single-tenant ``USER_TZ``, and 6.4 sources the value from the
authenticated user's ``app_user.timezone``. SQL binds it to ``AT TIME ZONE %s``;
Python datetime math builds a local ``ZoneInfo(tz)``.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta
from typing import LiteralString, cast
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Cursor
from psycopg.rows import TupleRow

# A cursor over plain tuple rows — the row shape every derivation reads.
Cur = Cursor[TupleRow]

# Aggregate functions allowed in `_window_stat`'s SQL. The stat name is
# interpolated into the query, so it MUST come from this hardcoded allowlist and
# never from caller-controlled data (standards §2: no untrusted SQL f-strings).
_ALLOWED_STATS = frozenset({"AVG", "MIN", "MAX", "SUM"})


def _json(payload: dict) -> str:
    """Serialize a flags dict for a jsonb column."""
    return json.dumps(payload)


def _upsert_daily(
    cur: Cur, user_id: UUID, day: date, metric: str, value: float, flags: dict | None = None
) -> None:
    """Insert-or-update one materialized daily metric row (value rounded to 4dp).

    `user_id` is the row's owner and part of the key (0004 folded it into the PK),
    so the conflict target is (user_id, day, metric): two users' same-day rows no
    longer collide. It has no default on purpose — a derivation that silently wrote
    under the wrong owner is exactly the class of bug the explicit thread prevents.

    `derived_at` is re-stamped on EVERY write, including one that lands the identical
    value (0016). It is not a change log — it records that a derive pass *reached* this
    cell, which is the only thing that distinguishes a row today's code re-produced from
    a row today's code has stopped producing. `now()` is the transaction instant, so an
    entire batch shares one stamp and ``db/stale_derived.py`` can ask for "everything
    this transaction did not touch" in one predicate.
    """
    cur.execute(
        """
        INSERT INTO derived_daily (user_id, day, metric, value, flags, derived_at)
        VALUES (%s, %s, %s, %s, %s::jsonb, now())
        ON CONFLICT (user_id, day, metric) DO UPDATE
          SET value = EXCLUDED.value, flags = EXCLUDED.flags,
              derived_at = EXCLUDED.derived_at
        """,
        (user_id, day, metric, round(float(value), 4), _json(flags or {})),
    )


def _window_stat(
    cur: Cur,
    user_id: UUID,
    metric: str,
    start_ts: datetime,
    end_ts: datetime,
    lo: float,
    hi: float,
    stat: str = "AVG",
) -> float | None:
    """Aggregate one owner's metric over the [start, end) window, bounded to [lo, hi]."""
    if stat not in _ALLOWED_STATS:  # guard the interpolated aggregate name
        raise ValueError(f"unsupported stat {stat!r}")
    # `stat` is interpolated but validated against the hardcoded allowlist above,
    # so this is a constant literal to the DB — cast tells the type checker so.
    query = cast(
        LiteralString,
        f"SELECT {stat}(value)::float FROM sample "
        "WHERE user_id = %s AND metric=%s AND value BETWEEN %s AND %s AND ts >= %s AND ts < %s",
    )
    cur.execute(query, (user_id, metric, lo, hi, start_ts, end_ts))
    row = cur.fetchone()
    return float(row[0]) if row and row[0] is not None else None


def _wake_date(end_ts: datetime, tz: str) -> date:
    """Daily metrics anchor on the local wake (session end) date."""
    return end_ts.astimezone(ZoneInfo(tz)).date()


def _day_bounds_utc(day: date, tz: str) -> tuple[datetime, datetime]:
    """The ONE definition of a local (``tz``) day: half-open UTC ``[start, next_start)``.

    The end is the START OF THE NEXT LOCAL DAY, never this day's ``23:59:59``. That
    wall-clock time is not a reliable edge: in a zone that transitions AT MIDNIGHT it
    is ambiguous (falling back, it happens twice — PEP 495 resolves ``fold=0``, the
    FIRST pass, leaving the repeated hour outside the bracket) or non-existent
    (springing forward, it is resolved with the pre-transition offset and lands an hour
    PAST the day's real end). Both mis-measure the day by a full hour::

        America/Santiago 2026-04-04  25 h  (falls back at the following midnight)
        Asia/Beirut      2026-10-24  25 h
        America/Nuuk     2026-03-28  23 h  (springs forward at 23:00 -> next midnight)

    A local midnight has neither failure mode as a day edge, because whichever instant
    ``ZoneInfo`` picks for it is used for BOTH the day that ends there and the day that
    starts there. Consecutive days therefore tile the timeline exactly — no gap, no
    overlap — which is the property a per-day metric actually needs (verified across
    every zone in the tz database by ``tests/derive/test_energy_dst.py``, and against
    Postgres' own ``AT TIME ZONE`` by ``tests/read/test_day_window.py``).

    ZoneInfo re-resolves the UTC offset at each edge independently, so a DST day
    correctly spans 23 h, 23.5 h, 24.5 h or 25 h rather than a fixed 24 h. Two rules
    follow for callers: anything walking this window per-minute takes its length from
    :func:`_day_minutes`, never from a hardcoded 1440; and every SQL range built from
    it is ``ts >= start AND ts < end`` — a closed ``<=`` would hand the next day's
    first instant to this day as well.
    """
    zone = ZoneInfo(tz)
    nxt = day + timedelta(days=1)
    start = datetime(day.year, day.month, day.day, tzinfo=zone)
    end = datetime(nxt.year, nxt.month, nxt.day, tzinfo=zone)
    return start.astimezone(UTC), end.astimezone(UTC)


def _day_minutes(start_utc: datetime, end_utc: datetime) -> int:
    """Whole minutes in the local day bracketed by :func:`_day_bounds_utc`.

    The bracket is half-open, so this is just its span: local 00:00 through 23:59 on an
    ordinary day, which is 1440 exactly — the invariant to protect, since a non-DST
    day's calorie total must not move by a single minute.

    On a DST day it does NOT return 1440, which is the point. A local day legitimately
    spans 23 h or 25 h, and a fixed ``range(1440)`` therefore walked 60 minutes into
    the NEXT day each spring (over-counting) and missed the last hour each autumn
    (under-counting). Harmless while every owner was in Asia/Kolkata (no DST); 6.4 made
    per-user timezones live, so it mis-integrates a real user's day twice a year::

        America/New_York 2026-03-08 (spring forward):  1380 min  (23 h)
        America/New_York 2026-11-01 (fall back):       1500 min  (25 h)
        America/New_York 2026-06-15 (normal):          1440 min
        Asia/Kolkata     any day (no DST):             1440 min
        Australia/Lord_Howe 2026-04-05 (30 min shift): 1470 min  (24.5 h)
    """
    return int((end_utc - start_utc).total_seconds() // 60)


def _age(dob: date, on: date) -> int:
    """Whole years old on the given date."""
    years = on.year - dob.year
    if (on.month, on.day) < (dob.month, dob.day):
        years -= 1
    return years


def _date_of_birth(cur: Cur, user_id: UUID) -> date | None:
    """The owner's date of birth ALONE — for a metric whose only profile input it is.

    :func:`_load_profile` is the loader for a metric that spends the owner's BODY: it
    wants height, sex and a weight-as-of because BMI, BMR and the Jurca model all do, and
    it returns None when any of them is missing. Sleep need is not that kind of metric.
    NSF 2015 selects a band from AGE and nothing else (``derive/sleep_score.py``), so
    routing it through the full loader made a logged weight a hard input to a number that
    never reads one — a dependency that is not a dependency, withholding a value we can
    honestly give (audit C8).

    This is the same argument the loader below already makes about ``srpa``, in its own
    comment: that field is "NOT part of the 'profile is complete' gate above … sleep need
    only wants ``dob`` and must not be blocked by it (#108)". The reasoning was written
    down and applied to one neighbouring field but not to the other.
    """
    cur.execute("SELECT dob FROM profile WHERE user_id = %s", (user_id,))
    row = cur.fetchone()
    return row[0] if row and row[0] is not None else None


def _load_profile(cur: Cur, user_id: UUID, tz: str, day: date) -> dict | None:
    """Profile + weight as-of `day` (weight is a time-series, read at that date).

    Weight uses the most recent `weight_log` entry logged on or before `day`, so
    updating today's weight never retroactively rewrites past days; days before
    the first entry fall back to the earliest logged weight. Returns None if the
    profile or any weight is missing.

    `weight_as_of` is the local date that weight was LOGGED, and it is part of the
    contract, not diagnostics: a weight has no expiry stamped on it, so a consumer
    that receives `weight_kg` alone is structurally unable to tell a weigh-in from
    this morning apart from one from March. Dropping it here is what let a
    six-month-old mass anchor BMI → VO₂max → biological age with nothing to notice.
    Consumers decide what to do with the age via `derive.freshness.weight_is_stale`
    — the decision is deliberately theirs (a stale weight must not block sleep need,
    which only wants `dob`), the RULE is deliberately not.
    """
    cur.execute("SELECT height_cm, sex, dob, srpa FROM profile WHERE user_id = %s", (user_id,))
    prof = cur.fetchone()
    if not prof or prof[0] is None or prof[1] is None or prof[2] is None:
        return None
    weight = _weight_as_of(cur, user_id, tz, day)
    if not weight:
        return None
    return {
        "height_cm": float(prof[0]),
        "sex": prof[1],
        "dob": prof[2],
        # Jurca's self-reported activity category (0-4), or None when the owner has never
        # been asked. NOT part of the "profile is complete" gate above: it is needed only
        # by `derive/vo2max.py`, which withholds on it with its own reason and its own
        # sentence, while sleep need only wants `dob` and must not be blocked by it (#108).
        "srpa": None if prof[3] is None else int(prof[3]),
        "weight_kg": weight[0],
        "weight_as_of": weight[1],
    }


def _weight_as_of(cur: Cur, user_id: UUID, tz: str, day: date) -> tuple[float, date] | None:
    """Most-recent (kg, local log date) on/before `day`, else the earliest logged.

    The date comes back with the value because they are one fact — see `_load_profile`.
    """
    cur.execute(
        "SELECT kg, (ts AT TIME ZONE %s)::date FROM weight_log WHERE user_id = %s "
        "AND (ts AT TIME ZONE %s)::date <= %s ORDER BY ts DESC LIMIT 1",
        (tz, user_id, tz, day),
    )
    weight = cur.fetchone()
    if not weight:
        cur.execute(
            "SELECT kg, (ts AT TIME ZONE %s)::date FROM weight_log WHERE user_id = %s "
            "ORDER BY ts ASC LIMIT 1",
            (tz, user_id),
        )
        weight = cur.fetchone()
    return (float(weight[0]), weight[1]) if weight else None


def _clamp100(x: float) -> float:
    """Clamp a sub-score to [0, 100]."""
    return max(0.0, min(100.0, x))


def _scalar(cur: Cur) -> float:
    """Read one guaranteed-present numeric aggregate result (a COALESCE'd SUM/…).

    Aggregate queries always return exactly one row; the None branch is defensive
    (a loud error, never a silent 0) so a plumbing bug can't masquerade as data.
    """
    row = cur.fetchone()
    if row is None:
        raise RuntimeError("expected one aggregate row, got none")
    return float(row[0])


__all__ = [
    "Cur",
    "_age",
    "_clamp100",
    "_day_bounds_utc",
    "_day_minutes",
    "_json",
    "_load_profile",
    "_scalar",
    "_upsert_daily",
    "_wake_date",
    "_window_stat",
]
