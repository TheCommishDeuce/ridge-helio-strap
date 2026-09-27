"""The Jurca estimate is WITHHELD when the note says it must be.

``non_exercise_vo2max.md`` states the gate three times — in "How we compute it", in
Coach Directive 3 (confidence: high), and in the Healthee honesty policy:

    "Skip if any input is missing — never write a wrong value. Also skip (show
     'Insufficient data') if the 7-day RHR MAD > 8 bpm or weight_kg is missing,
     since a noisy RHR or absent mass makes the estimate untrustworthy."

The derivation honoured the RHR *range* gate but computed no MAD and had no noise
gate at all, so a week of illness / poor sensor contact / travel produced an estimate
from an untrustworthy median and shipped it as if it were solid — and VO2max is the
dominant term in [[biological_age_estimate]], so that noise reached the age number.

Every RHR series below is hand-built so its median and MAD are exact integers; the
arithmetic is shown so a reviewer can check the boundary against the note with a
calculator. None of these numbers came out of the implementation.
"""

from __future__ import annotations

from datetime import date, timedelta

import pytest

from strap_server.derive.robust import median, median_abs_deviation
from strap_server.derive.srpa import WITHHOLD_SRPA_NOT_REPORTED
from strap_server.derive.vo2max import (
    _RHR_MAD_MAX_BPM,
    WITHHOLD_FEW_RHR_DAYS,
    WITHHOLD_MESSAGES,
    WITHHOLD_RHR_OUT_OF_RANGE,
    WITHHOLD_RHR_TOO_NOISY,
    derive_vo2max,
    vo2max_withhold_reason,
    withhold_reason_for_day,
)
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction

# ── the series, with their arithmetic ────────────────────────────────────────
#
# CALM: [50, 52, 54, 56, 58, 60, 62]      median 56
#   |x - 56| = [6, 4, 2, 0, 2, 4, 6] -> sorted [0, 2, 2, 4, 4, 6, 6] -> MAD = 4
#   4 <= 8 -> DERIVE
_CALM_WEEK = [50.0, 52.0, 54.0, 56.0, 58.0, 60.0, 62.0]
_CALM_MAD = 4.0
#
# EXACTLY_AT_THE_GATE: [40, 48, 56, 64, 72]   median 56
#   |x - 56| = [16, 8, 0, 8, 16] -> sorted [0, 8, 8, 16, 16] -> MAD = 8
#   The note withholds when MAD > 8, so MAD == 8 is the LAST value that derives.
_MAD_EXACTLY_8 = [40.0, 48.0, 56.0, 64.0, 72.0]
#
# JUST_OVER: [40, 47, 56, 65, 72]             median 56
#   |x - 56| = [16, 9, 0, 9, 16] -> sorted [0, 9, 9, 16, 16] -> MAD = 9
#   9 > 8 -> WITHHOLD
_MAD_9 = [40.0, 47.0, 56.0, 65.0, 72.0]


def test_the_fixtures_have_the_mads_this_file_claims() -> None:
    """Guard the guards: if these series drift, every boundary test below lies."""
    assert median(_CALM_WEEK) == 56.0
    assert median_abs_deviation(_CALM_WEEK) == _CALM_MAD
    assert median(_MAD_EXACTLY_8) == 56.0
    assert median_abs_deviation(_MAD_EXACTLY_8) == 8.0
    assert median(_MAD_9) == 56.0
    assert median_abs_deviation(_MAD_9) == 9.0


def test_the_threshold_is_the_notes_number() -> None:
    """8 bpm, exactly as `non_exercise_vo2max.md` writes it."""
    assert _RHR_MAD_MAX_BPM == 8.0


def test_a_calm_week_derives() -> None:
    assert vo2max_withhold_reason(_CALM_WEEK) is None


def test_mad_exactly_at_the_threshold_still_derives() -> None:
    """The note says "MAD > 8", not ">=": 8.0 is inside the gate."""
    assert vo2max_withhold_reason(_MAD_EXACTLY_8) is None


def test_a_noisy_week_is_withheld() -> None:
    assert vo2max_withhold_reason(_MAD_9) == WITHHOLD_RHR_TOO_NOISY


def test_the_boundary_sits_between_8_and_9() -> None:
    """Bracket the gate from both sides — the threshold is the note's, not ours."""
    below = [56.0 - 8.0, 56.0 - 8.0, 56.0, 56.0 + 8.0, 56.0 + 8.0]  # MAD 8.0
    above = [56.0 - 8.1, 56.0 - 8.1, 56.0, 56.0 + 8.1, 56.0 + 8.1]  # MAD 8.1
    assert median_abs_deviation(below) == 8.0
    assert median_abs_deviation(above) == pytest.approx(8.1)
    assert vo2max_withhold_reason(below) is None
    assert vo2max_withhold_reason(above) == WITHHOLD_RHR_TOO_NOISY


def test_too_few_days_is_withheld_and_named_differently() -> None:
    """Not-enough-of-a-week is a DIFFERENT state from the-week-was-noisy."""
    assert vo2max_withhold_reason([]) == WITHHOLD_FEW_RHR_DAYS
    assert vo2max_withhold_reason([56.0, 56.0]) == WITHHOLD_FEW_RHR_DAYS
    assert vo2max_withhold_reason([56.0, 56.0, 56.0]) is None  # 3 days is a week


@pytest.mark.parametrize("rhr", [39.0, 101.0, 120.0])
def test_out_of_validated_range_is_withheld(rhr: float) -> None:
    """Jurca is validated for RHR 40-100; outside it the note withholds."""
    assert vo2max_withhold_reason([rhr] * 5) == WITHHOLD_RHR_OUT_OF_RANGE


@pytest.mark.parametrize("rhr", [40.0, 100.0])
def test_the_validated_range_is_inclusive(rhr: float) -> None:
    assert vo2max_withhold_reason([rhr] * 5) is None


def test_a_noisy_week_is_withheld_even_when_the_median_looks_fine() -> None:
    """The whole point: the median alone cannot reveal that the week was noise.

    Both weeks median to 56 bpm — a perfectly plausible RHR that the range gate waves
    through — but one is a flat week and the other swings 40-72. Before this gate the
    two produced an IDENTICAL estimate, and nothing on screen said which was which.
    """
    steady = [56.0] * 7
    chaotic = [40.0, 44.0, 56.0, 56.0, 68.0, 72.0, 72.0]  # median 56, MAD 12
    assert median(steady) == median(chaotic) == 56.0
    assert median_abs_deviation(chaotic) == 12.0
    assert vo2max_withhold_reason(steady) is None
    assert vo2max_withhold_reason(chaotic) == WITHHOLD_RHR_TOO_NOISY


# ── end-to-end against a seeded DB ───────────────────────────────────────────

_DAY = date(2026, 3, 8)


def _seed(cur, rhrs: list[float]) -> None:
    for table in ("derived_daily", "weight_log", "profile"):
        cur.execute(f"DELETE FROM {table}")
    cur.execute(
        # `srpa` is Jurca's self-reported activity category (#108) and it is REQUIRED —
        # an unanswered profile withholds on its own, which would mask the RHR gates
        # under test the same way a stale weight would.
        "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) "
        "VALUES (%s, 175, 'male', '1990-01-01', 0)",
        (SENTINEL_USER_ID,),
    )
    cur.execute(
        # The day before `_DAY`: a weight older than `freshness.WEIGHT_MAX_AGE_DAYS`
        # withholds on its own (#85), which would mask the RHR gates under test.
        "INSERT INTO weight_log (user_id, ts, kg) VALUES (%s, '2026-03-07T00:00:00+00', 72)",
        (SENTINEL_USER_ID,),
    )
    for k, rhr in enumerate(rhrs):
        cur.execute(
            "INSERT INTO derived_daily (user_id, day, metric, value) VALUES (%s, %s, %s, %s)",
            (SENTINEL_USER_ID, _DAY - timedelta(days=len(rhrs) - 1 - k), "rhr_daily", rhr),
        )


def _stored_vo2max(cur) -> float | None:
    cur.execute(
        "SELECT value FROM derived_daily WHERE user_id=%s AND metric='vo2max_estimate'",
        (SENTINEL_USER_ID,),
    )
    row = cur.fetchone()
    return float(row[0]) if row else None


@pytest.mark.usefixtures("db")
def test_a_calm_week_writes_a_row() -> None:
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _CALM_WEEK)
        out = derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
        stored = _stored_vo2max(cur)
    assert out is not None
    assert stored is not None


@pytest.mark.usefixtures("db")
def test_a_noisy_week_writes_no_row_at_all() -> None:
    """Never write a wrong value: no row, so no stale number can be read back."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _MAD_9)
        out = derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
        stored = _stored_vo2max(cur)
    assert out is None
    assert stored is None


@pytest.mark.usefixtures("db")
def test_an_unanswered_activity_question_withholds_rather_than_assuming_a_level() -> None:
    """#108: SR-PA is the owner's answer, and no answer means no estimate.

    Assuming the reference level (0) instead would be the cheap fix and it is the wrong
    one for the same reason `analytics/biological_age.py` refuses to drop a term: the
    reference category is not "unknown", it is the claim "you do no deliberate exercise".
    The previous code did assume — from step cadence — and the assumption was worth 5.5
    years at the extremes.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _CALM_WEEK)
        cur.execute("UPDATE profile SET srpa = NULL WHERE user_id = %s", (SENTINEL_USER_ID,))
        out = derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
        stored = _stored_vo2max(cur)
        reason = withhold_reason_for_day(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
    assert out is None
    assert stored is None
    assert reason == WITHHOLD_SRPA_NOT_REPORTED
    # The owner is told what to do about it — this gate is the one the strap cannot clear.
    assert "profile" in WITHHOLD_MESSAGES[WITHHOLD_SRPA_NOT_REPORTED]


@pytest.mark.usefixtures("db")
def test_the_stored_estimate_moves_with_the_answer_by_the_published_steps() -> None:
    """Each category is worth Jurca's published MET step, end to end through the DB.

    Guards the seam the unit test cannot: that the profile's value reaches the equation
    unmangled, and that nothing between them re-derives a category from activity.
    """
    stored: dict[int, float] = {}
    for level in range(5):
        with tenant_transaction(SENTINEL_USER_ID) as cur:
            _seed(cur, _CALM_WEEK)
            cur.execute(
                "UPDATE profile SET srpa = %s WHERE user_id = %s", (level, SENTINEL_USER_ID)
            )
            derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
            value = _stored_vo2max(cur)
        assert value is not None
        stored[level] = value
    steps_mets = [round((stored[i + 1] - stored[i]) / 3.5, 2) for i in range(4)]
    assert steps_mets == [0.32, 0.74, 0.70, 1.27]  # Jurca 2005 Table 5, NASA column


@pytest.mark.usefixtures("db")
def test_a_missing_weight_withholds_rather_than_dividing_by_a_missing_mass() -> None:
    """`_load_profile` returns None when NO weight is logged, so BMI never divides
    a missing mass — the note's "weight_kg is missing" case was already withheld.
    Pinned here because nothing asserted it, and the BMI line sits one statement away.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed(cur, _CALM_WEEK)
        cur.execute("DELETE FROM weight_log")
        out = derive_vo2max(cur, SENTINEL_USER_ID, SENTINEL_TZ, _DAY)
        stored = _stored_vo2max(cur)
    assert out is None
    assert stored is None
