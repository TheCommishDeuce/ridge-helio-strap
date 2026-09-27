"""``cardio_load`` withholds rather than inventing a resting HR (audit C5).

``derive/cardio_load.py`` carried ``_RHR_FALLBACK = 60.0  # when no measured resting HR
is available`` and published a full row off it — TRIMP, Edwards load, per-zone minutes —
which then fed strain, ACWR and the intraday readiness decay. It was the ONLY place in
``derive/`` that fabricated an input instead of withholding, and the corpus says so
plainly: ``training-stress-score.md`` specifies *"measured resting HR (`rhr_daily`, taken
from the sleep window — **preferred over a generic 60**)"*.

The second defect was in the same function and had not been reported anywhere: the query
was ``day <= %s ORDER BY day DESC LIMIT 1`` with **no maximum age**, so a resting HR from
a year ago was used as today's, silently. That is the stale-as-current lie
(``HOW_WE_VERIFY.md`` section 3) sitting in the anchor of the HR reserve, and its
direction is the aggravating part: RHR falls with training, so a stale LOW value widens
the reserve and inflates every load number built on it.

Both are now gates. ``freshness.RHR_MAX_AGE_DAYS`` carries the argument for 30 days.

Written as behaviour, not as a grep for the deleted constant: a test asserting
``_RHR_FALLBACK`` is gone would pass just as happily against a module that renamed it.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.cardio_load import _measured_rhr, derive_cardio_load
from strap_server.derive.freshness import RHR_MAX_AGE_DAYS
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction, user_today

pytestmark = pytest.mark.integration

ZONE = ZoneInfo(SENTINEL_TZ)


def _reset(cur) -> None:
    for table in ("derived_daily", "sample", "sleep_session", "weight_log", "profile"):
        cur.execute(f"DELETE FROM {table}")


def _profile(cur, *, dob: date, day: date) -> None:
    """A COMPLETE profile — height, sex, dob and a logged weight.

    The weight matters to this file for a reason worth stating: ``_load_profile`` returns
    None when any of them is missing, and ``derive_cardio_load`` then returns None before
    it ever reaches the resting-HR gate. Omitting it makes every withhold assertion below
    pass for the wrong reason — a green test proving nothing, which is `HOW_WE_VERIFY`'s
    "fictional mutation" in test form. ``test_a_fresh_measured_rhr_still_produces_a_row``
    is the control that catches it.
    """
    cur.execute(
        "INSERT INTO profile (user_id, dob, sex, height_cm) VALUES (%s, %s, 'male', 175) "
        "ON CONFLICT (user_id) DO UPDATE SET dob = EXCLUDED.dob",
        (SENTINEL_USER_ID, dob),
    )
    cur.execute(
        "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, 72.0)",
        (SENTINEL_USER_ID, datetime.combine(day, time(7, 0), tzinfo=ZONE).astimezone(UTC)),
    )


def _rhr(cur, day: date, value: float) -> None:
    cur.execute(
        "INSERT INTO derived_daily (user_id, day, metric, value, flags) "
        "VALUES (%s, %s, 'rhr_daily', %s, '{}'::jsonb)",
        (SENTINEL_USER_ID, day, value),
    )


def _waking_hr(cur, day: date, minutes: int, bpm: float) -> None:
    """Per-minute waking HR from 10:00 local, well clear of any sleep window."""
    start = datetime.combine(day, time(10, 0), tzinfo=ZONE).astimezone(UTC)
    for i in range(minutes):
        cur.execute(
            "INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, 'hr', %s)",
            (SENTINEL_USER_ID, start + timedelta(minutes=i), bpm),
        )


def test_no_measured_rhr_withholds_the_whole_row() -> None:
    """The fabricated 60 is gone, and nothing is published in its place.

    Note what makes this the right shape rather than a smaller one: the day has plenty of
    waking HR. Everything needed to compute a plausible-looking TRIMP is present except
    the one thing nobody measured, and the old code shipped exactly that.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _profile(cur, dob=date(1994, 1, 1), day=day)
        _waking_hr(cur, day, minutes=60, bpm=120.0)
        out = derive_cardio_load(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)

        assert out is None, "a cardio load was published with no measured resting HR"
        cur.execute(
            "SELECT metric FROM derived_daily WHERE user_id = %s AND day = %s "
            "AND metric IN ('cardio_load', 'hr_zone_minutes')",
            (SENTINEL_USER_ID, day),
        )
        assert cur.fetchall() == [], "a row survived the withhold"


def test_a_year_old_resting_hr_is_not_todays() -> None:
    """The unbounded lookback. The row exists; it is simply not about this day."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _profile(cur, dob=date(1994, 1, 1), day=day)
        _rhr(cur, day - timedelta(days=365), 52.0)
        _waking_hr(cur, day, minutes=60, bpm=120.0)

        assert _measured_rhr(cur, SENTINEL_USER_ID, day) is None
        assert derive_cardio_load(cur, SENTINEL_USER_ID, SENTINEL_TZ, day) is None


def test_the_horizon_boundary_is_inclusive_and_one_day_past_it_is_not() -> None:
    """Both sides of the edge, so a widened or narrowed horizon fails here.

    A single assertion on the inside would pass against an unbounded query — which is the
    bug — and a single one on the outside would pass against a horizon of zero.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _profile(cur, dob=date(1994, 1, 1), day=day)

        _rhr(cur, day - timedelta(days=RHR_MAX_AGE_DAYS), 52.0)
        assert _measured_rhr(cur, SENTINEL_USER_ID, day) == 52.0

        cur.execute("DELETE FROM derived_daily")
        _rhr(cur, day - timedelta(days=RHR_MAX_AGE_DAYS + 1), 52.0)
        assert _measured_rhr(cur, SENTINEL_USER_ID, day) is None


def test_a_fresh_measured_rhr_still_produces_a_row() -> None:
    """The gate must not eat the ordinary case — and it must use the MEASURED number.

    52 is deliberately far from the deleted 60: the reserve, and therefore TRIMP, differ
    enough that a silent return to a default would move the value rather than hide in it.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _profile(cur, dob=date(1994, 1, 1), day=day)
        _rhr(cur, day, 52.0)
        _waking_hr(cur, day, minutes=60, bpm=120.0)
        out = derive_cardio_load(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)

        assert out is not None
        assert out["cardio_load"] > 0
        cur.execute(
            "SELECT flags FROM derived_daily WHERE user_id = %s AND day = %s "
            "AND metric = 'cardio_load'",
            (SENTINEL_USER_ID, day),
        )
        row = cur.fetchone()
        assert row is not None
        assert row[0]["rhr"] == 52, "the row is stamped with the measured RHR, not a default"
