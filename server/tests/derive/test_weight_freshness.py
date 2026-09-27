"""#85 — a logged weight is a claim about the day it was logged, and about no other.

Weight is the ONE metric in this codebase that gets a staleness *horizon* rather than
``derive/freshness.py``'s today-or-nothing rule, because nobody derives it: it exists
only on days the owner chose to step on a scale, and a two-day-old weight measurably IS
this person's mass ([[weight_bmi_body_composition]]).

These are the known-value tests for that horizon and for the chain it anchors —
weight → BMI → VO₂max → biological age — which is how a mass measured in March came to
be spent inside a number the app presents as the owner's biological age today.

The horizon's evidence lives in ``freshness.WEIGHT_MAX_AGE_DAYS``; the numbers asserted
here are that constant's boundary, so a change to it fails this file rather than
silently widening what counts as "current".
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta

import pytest

from strap_server.derive._common import _load_profile
from strap_server.derive.freshness import (
    WEIGHT_MAX_AGE_DAYS,
    WEIGHT_STALE,
    weight_age_days,
    weight_is_stale,
)
from strap_server.derive.vo2max import WITHHOLD_MESSAGES, derive_vo2max, withhold_reason_for_day
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction

pytestmark = pytest.mark.integration

_DAY = date(2026, 3, 8)
# median 56, MAD 4 — inside every RHR gate, so nothing but the weight can withhold.
_CALM = [50.0, 52.0, 54.0, 56.0, 58.0, 60.0, 62.0]


# ── the rule itself: pure, and testable without a database ───────────────────


def test_the_horizon_is_two_weeks_and_the_boundary_is_inclusive() -> None:
    """The constant is quoted from a two-week measurement, so the boundary is two weeks.

    Bhutani et al. 2017 measured 0.26 ± 1.2 kg of drift over two weeks of free living —
    less than the ~0.5–0.6 kg error of weighing yourself twice (Cheuvront 2004; Kutáč
    2015). "Over two weeks" is where the corpus stops having a measured figure at all,
    which is why the gate opens the day AFTER 14 and not on it.
    """
    assert WEIGHT_MAX_AGE_DAYS == 14
    on = date(2026, 3, 15)
    assert weight_is_stale(on - timedelta(days=15), on) is True
    assert weight_is_stale(on - timedelta(days=14), on) is False
    assert weight_is_stale(on - timedelta(days=1), on) is False
    assert weight_is_stale(on, on) is False


def test_a_weight_logged_after_the_day_is_just_as_stale() -> None:
    """The direction that would otherwise slip through.

    ``_weight_as_of`` falls back to the EARLIEST logged weight for days before the
    owner's first entry, so a day in March can be handed a weight logged in June. A
    weight from three months after a day says nothing about that day, and a signed
    comparison would have exempted every pre-first-entry day from the gate entirely.
    """
    on = date(2026, 3, 15)
    assert weight_age_days(on + timedelta(days=40), on) == 40
    assert weight_is_stale(on + timedelta(days=40), on) is True


# ── the loader: the date travels with the value ──────────────────────────────


def _seed(cur, weight_day: date) -> None:
    for table in ("derived_daily", "weight_log", "profile"):
        cur.execute(f"DELETE FROM {table}")
    cur.execute(
        "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) "
        "VALUES (%s, 175, 'male', '1990-01-01', 0)",
        (SENTINEL_USER_ID,),
    )
    cur.execute(
        "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, %s, 72)",
        (SENTINEL_USER_ID, datetime.combine(weight_day, time(6), tzinfo=UTC)),
    )
    for k, rhr in enumerate(_CALM):
        cur.execute(
            "INSERT INTO derived_daily (user_id, day, metric, value) VALUES (%s, %s, %s, %s)",
            (SENTINEL_USER_ID, _DAY - timedelta(days=len(_CALM) - 1 - k), "rhr_daily", rhr),
        )


@pytest.mark.usefixtures("db")
def test_the_profile_loader_carries_the_weights_log_date() -> None:
    """The root of the defect: ``_weight_as_of`` returned ``(kg,)`` and dropped the ts.

    Every consumer therefore received a mass with no way to tell a weigh-in from this
    morning apart from one from March. Nothing downstream could have caught it.
    """
    logged = _DAY - timedelta(days=3)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, logged)
        prof = _load_profile(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
    assert prof is not None
    assert prof["weight_kg"] == 72.0
    assert prof["weight_as_of"] == logged


# ── the chain: weight → BMI → VO₂max ─────────────────────────────────────────


@pytest.mark.usefixtures("db")
def test_a_fresh_weight_still_derives_a_vo2max() -> None:
    # The common path must not move: this whole change is a no-op for anyone who
    # weighed themselves inside the horizon.
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _DAY - timedelta(days=WEIGHT_MAX_AGE_DAYS))
        assert derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY) is not None
        assert withhold_reason_for_day(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY) is None


@pytest.mark.usefixtures("db")
def test_a_stale_weight_withholds_the_vo2max_and_writes_no_row() -> None:
    """The refusal. "Never write a wrong value" — [[non_exercise_vo2max]].

    One day past the horizon, with every OTHER input pristine (a calm RHR week, a
    complete profile), so the only thing that can withhold is the weight.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _DAY - timedelta(days=WEIGHT_MAX_AGE_DAYS + 1))
        assert derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY) is None
        cur.execute(
            "SELECT count(*) FROM derived_daily WHERE user_id = %s AND metric='vo2max_estimate'",
            (SENTINEL_USER_ID,),
        )
        row = cur.fetchone()
        assert row is not None and row[0] == 0  # withheld means NO row, not a flagged row
        assert withhold_reason_for_day(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY) == WEIGHT_STALE


def test_the_withhold_reason_tells_the_owner_what_to_do_about_it() -> None:
    # A reason id with no sentence is "no number", which tells an owner nothing. The
    # VO₂max wording has to join two facts that look unrelated from outside — that a
    # fitness estimate runs on BMI, and that BMI runs on a weight we no longer trust.
    message = WITHHOLD_MESSAGES[WEIGHT_STALE]
    assert "BMI" in message
    assert "Log a weight" in message


@pytest.mark.usefixtures("db")
def test_a_stale_weight_is_not_clamped_or_defaulted_to_a_population_mass() -> None:
    """Reject-never-clamp, the house pattern (challenges Gate A).

    The tempting "fix" for a stale weight is a substitute — the last known value, a
    BMI-22 mass for the owner's height, the population median. Every one of those is a
    fabricated measurement of a person's body wearing a real number's clothes. The
    evidence that we do not do it is that NOTHING is written.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _DAY - timedelta(days=200))
        assert derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY) is None
        cur.execute("SELECT count(*) FROM derived_daily WHERE metric='vo2max_estimate'")
        row = cur.fetchone()
        assert row is not None and row[0] == 0


@pytest.mark.usefixtures("db")
def test_a_stale_weight_does_not_block_metrics_that_do_not_use_the_weight() -> None:
    """The gate is per-consumer on purpose, and this is why.

    ``_load_profile`` is shared: sleep need wants only ``dob`` from it, and calories
    spend the weight at 10 kcal/kg where the note's own arithmetic makes a kilogram
    worth ~10 kcal/day. Putting the horizon inside the loader would have taken those
    down too — a refusal nobody's evidence asked for is its own dishonesty.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _DAY - timedelta(days=200))
        prof = _load_profile(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
    assert prof is not None  # the loader still answers; the CONSUMER decides
    assert prof["weight_kg"] == 72.0
