"""One canonical sleep need, and only the inputs it actually reads (audit C3, C4, C8).

``CLAUDE.md``'s first hard rule is ONE canonical definition per metric — "this is health
data; two definitions of sleep debt is a lie waiting to surface". Sleep need had FOUR:

* ``derive/sleep_score.py``'s age-selected pair (480 under 65, 450 at 65+, NSF 2015) —
  the one with a paper behind it, and the one that survives;
* ``derive/recovery._DEFAULT_NEED_MIN``, a flat 480 the recovery composite scored against
  and then PUBLISHED as this owner's need (C4);
* ``read/health_metrics.sleep_debt_payload``'s ``else 480.0``, found while converging the
  other three, which published a flat eight hours in the very field the Sleep tab is now
  told to read;
* ``features/sleep/sleep_format.dart``'s ``kSleepNeedMin`` on the client (C3), asserted on
  the mobile side.

And the surviving definition was gated on an input it never read: ``_load_profile``
additionally requires a height, a sex and a LOGGED WEIGHT, so the whole need-and-debt
block was withheld for the want of a weigh-in that no line of the computation touches
(C8).
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.freshness import DOB_MISSING
from strap_server.derive.recovery import derive_recovery
from strap_server.derive.sleep_score import (
    SLEEP_NEED_MIN_18_64,
    SLEEP_NEED_MIN_65P,
    derive_sleep_debt,
    sleep_debt_withhold_reason_for_day,
)
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction, user_today

pytestmark = pytest.mark.integration

ZONE = ZoneInfo(SENTINEL_TZ)


def _reset(cur) -> None:
    for table in ("derived_daily", "sleep_session", "sample", "weight_log", "profile"):
        cur.execute(f"DELETE FROM {table}")


def _profile(cur, dob: date, *, weight: bool) -> None:
    """A profile with a date of birth, and a logged weight only if asked for."""
    cur.execute(
        "INSERT INTO profile (user_id, height_cm, sex, dob) VALUES (%s, 178, 'male', %s) "
        "ON CONFLICT (user_id) DO UPDATE SET dob = EXCLUDED.dob",
        (SENTINEL_USER_ID, dob),
    )
    if weight:
        cur.execute(
            "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, 80.0)",
            (SENTINEL_USER_ID, datetime.now(tz=UTC)),
        )


def _nights(cur, today: date, count: int = 10) -> None:
    """``count`` staged nights ending on the days up to and including ``today``."""
    for back in range(count):
        wake = today - timedelta(days=back)
        end = datetime.combine(wake, time(7, 0), tzinfo=ZONE).astimezone(UTC)
        start = end - timedelta(hours=6)
        cur.execute(
            "INSERT INTO sleep_session "
            "(user_id,start_ts,end_ts,kind,rem_min,light_min,deep_min,wake_min,stages) "
            "VALUES (%s,%s,%s,'main',60,220,60,20,'[]'::jsonb)",
            (SENTINEL_USER_ID, start, end),
        )
        cur.execute(
            "INSERT INTO derived_daily (user_id, day, metric, value, flags) "
            "VALUES (%s,%s,'sleep_health_score_4dim',3,%s::jsonb) "
            "ON CONFLICT (user_id, day, metric) DO UPDATE SET flags = EXCLUDED.flags",
            (SENTINEL_USER_ID, wake, json.dumps({"tst_min": 340})),
        )


# ── C8. a weight the computation never reads cannot block it ─────────────────


@pytest.mark.usefixtures("db")
def test_sleep_need_and_debt_are_derived_with_no_logged_weight() -> None:
    """Date of birth is the ONLY profile input, so it is the only one that can gate.

    The gate ran ``_load_profile``, whose contract is "profile AND a weight as of this
    day". An owner who had never stepped on a scale therefore got no sleep need and no
    debt — a value we can honestly give, withheld for a dependency that is not one. The
    loader's own comment already made this argument about ``srpa`` and stopped there.
    """
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _profile(cur, date(1990, 1, 1), weight=False)
        _nights(cur, today)
        reason = sleep_debt_withhold_reason_for_day(cur, SENTINEL_USER_ID, today)
        out = derive_sleep_debt(cur, SENTINEL_USER_ID, today)

    assert reason is None, "no weight is not a reason to refuse a need computed from age"
    assert out is not None
    assert out["sleep_need_min"] == SLEEP_NEED_MIN_18_64


@pytest.mark.usefixtures("db")
def test_no_date_of_birth_withholds_and_names_only_the_date_of_birth() -> None:
    """The reason id is the narrow one. Telling an owner to log a weight would be telling
    them to do something that would not bring the number back."""
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today)
        reason = sleep_debt_withhold_reason_for_day(cur, SENTINEL_USER_ID, today)
        out = derive_sleep_debt(cur, SENTINEL_USER_ID, today)

    assert reason == DOB_MISSING
    assert out is None


@pytest.mark.usefixtures("db")
def test_the_need_band_is_selected_by_age_not_assumed() -> None:
    """An owner over 65 gets 450, not 480 — the whole reason the constant is a pair."""
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _profile(cur, date(today.year - 70, 1, 1), weight=True)
        _nights(cur, today)
        out = derive_sleep_debt(cur, SENTINEL_USER_ID, today)

    assert out is not None
    assert out["sleep_need_min"] == SLEEP_NEED_MIN_65P
    assert SLEEP_NEED_MIN_65P != SLEEP_NEED_MIN_18_64


def _hrv_history(cur, today: date) -> None:
    """Today's HRV plus eight prior days, so ``_recovery_baseline`` clears its floor.

    The recovery score needs at least one AUTONOMIC factor to exist at all, and a factor
    needs a baseline of five points. Without this the score is None and the assertions
    below would pass on the wrong absence.
    """
    for back in range(9):
        cur.execute(
            "INSERT INTO derived_daily (user_id, day, metric, value, flags) "
            "VALUES (%s,%s,'hrv_sleep_avg',%s,'{}'::jsonb) "
            "ON CONFLICT (user_id, day, metric) DO NOTHING",
            (SENTINEL_USER_ID, today - timedelta(days=back), 45.0 + back),
        )


# ── C4. the recovery composite has no fallback need ──────────────────────────


@pytest.mark.usefixtures("db")
def test_the_recovery_score_has_no_sleep_factor_without_a_stored_need() -> None:
    """No ``sleep_need_min`` row → no sleep factor, and certainly no published 480.

    ``_DEFAULT_NEED_MIN = 480.0`` scored the owner's sleep against a target invented for
    them and then wrote it into ``flags.factors.sleep.need_min`` as THEIR need. The
    composite already weights only the factors it has, so absence costs nothing except the
    fabrication.
    """
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, count=1)
        # An autonomic marker with enough history to earn a baseline, so the score exists
        # at all, and a night's TST so the sleep factor would be computable if a need were.
        _hrv_history(cur, today)
        out = derive_recovery(cur, SENTINEL_USER_ID, today)
        cur.execute(
            "SELECT flags FROM derived_daily "
            "WHERE user_id = %s AND day = %s AND metric = 'recovery_score'",
            (SENTINEL_USER_ID, today),
        )
        row = cur.fetchone()

    assert out is not None, "the autonomic markers still produce a score"
    assert row is not None
    assert "sleep" not in row[0]["factors"]


@pytest.mark.usefixtures("db")
def test_the_recovery_sleep_factor_uses_the_owners_own_stored_need() -> None:
    """With a need row, the factor scores against THAT number — 450 for an over-65."""
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, count=1)
        _hrv_history(cur, today)
        cur.execute(
            "INSERT INTO derived_daily (user_id, day, metric, value, flags) "
            "VALUES (%s,%s,'sleep_need_min',%s,'{}'::jsonb)",
            (SENTINEL_USER_ID, today, float(SLEEP_NEED_MIN_65P)),
        )
        derive_recovery(cur, SENTINEL_USER_ID, today)
        cur.execute(
            "SELECT flags FROM derived_daily "
            "WHERE user_id = %s AND day = %s AND metric = 'recovery_score'",
            (SENTINEL_USER_ID, today),
        )
        row = cur.fetchone()

    assert row is not None
    sleep = row[0]["factors"]["sleep"]
    assert sleep["need_min"] == SLEEP_NEED_MIN_65P
    assert sleep["need_min"] != SLEEP_NEED_MIN_18_64, "the flat 480 must not be reachable"
