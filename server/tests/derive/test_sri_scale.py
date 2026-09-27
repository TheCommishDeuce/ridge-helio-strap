"""WHERE OUR SRI SITS — measured against the estimator, not asserted from a paper.

[[sleep_regularity_index]] carried, for months, the honest admission that "we have
never measured where our own SRI distribution sits", and [[biological_age_estimate]]
built a required term on Cribb 2023's hazard anchors anyway. That is the whole of
#83c: two UK Biobank papers analysing the SAME accelerometry report medians of
**81.0** (Windred 2024, GGIR + `sleepreg`) and **60** (Cribb 2023, GGIR 2.7-1 alone,
no nap detection). A 21-point spread on one cohort means an SRI cutoff is a property
of a PIPELINE, not a portable number — so "which scale is ours on" decides whether a
borrowed anchor is approximately right or inverted.

This file answers that by driving ``_compute_sri`` over real sleep sessions with a
KNOWN day-to-day shift and recovering its scale, then checking that scale against
Windred's own published *behavioural* description of its quintiles. Nothing here
asserts a paper's number against another paper's number; every expectation is either
computed by our code or quoted from a primary source.

What it pins:

1. **The estimator's closed form.** For a fixed-duration sleeper whose bed and wake
   times both shift by ``d`` minutes between consecutive days,
   ``SRI = 100 - (200/1440) * 2d``. Verified against the estimator over a real
   7-night grid, and cross-checked against the canonical worked example the note
   already documents (7 h sleep drifting 2 h → 66.67).
2. **Our scale is Windred's, ~4 points high — and is NOT Cribb's.** Windred reports
   its most-regular quintile keeping sleep/wake "within roughly a 1-hour band" and its
   least-regular quintile varying "across roughly a 3-hour band", and separately
   publishes those quintiles' SRI boundaries (87.32 and 71.65). Running Windred's
   BEHAVIOUR through our estimator must land near Windred's NUMBERS. It does, at both
   ends, with the same small offset.
3. **Cribb's anchors describe behaviour our estimator says is extreme.** Cribb's
   median (60) and 5th-percentile anchor (41) correspond, on our scale, to shifting
   sleep by hours at each end every night. This is why
   ``analytics/biological_age.py::_regularity_term`` — which interpolated Cribb's
   41→1.53 / 75→0.90 anchors against OUR values — was a wrong number.

**What was done about it (#86, 2026-08-01): the term was REMOVED, not re-scaled.** The
measurement above tells us which published scale we are *near*; it does not license
borrowing a hazard curve, and the literature now says explicitly that it cannot be
borrowed. Czeisler et al. 2026 (*Sleep* 49(4):zsaf299, PMID 41001850) scored >70 000 UK
Biobank adults with BOTH standard SRI calculators: the scores *"differed markedly, both
in absolute and relative values"*, *"only two-fifths of participants were classified into
the same sleep regularity index quintile"* (editorial, *Sleep* 49(4):zsaf289), and for
all-cause mortality *"the method of calculation alone meaningfully changed results and
interpretations"* — a 1.19-fold adjusted hazard under one calculator, no significant
association under the other, on the same people. Ours is a third calculator again. So
sleep regularity is no longer a term of the biological age, and
``tests/analytics/test_biological_age_math.py`` fails if anything reads an SRI back into
it.

This file survives that change because everything in it is still true and still load-
bearing: it is the measurement of where our SRI sits, which is what keeps
``SRI_GOOD = 70`` honest and what a future re-anchoring (if our pipeline is ever
validated against an outcome cohort) would have to start from.

Deliberately NOT pinned, still: any SRI→years conversion. There is none to pin.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.sleep_score import SRI_DAYS, _compute_sri
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction

pytestmark = pytest.mark.integration

_ZONE = ZoneInfo(SENTINEL_TZ)

# The estimator's own constants, restated as the scale factor a shift costs. One
# mismatched minute in a day-pair costs 200/1440 SRI points; a shift of d minutes
# mismatches 2d minutes (one edge at each end of the night).
_POINTS_PER_MISMATCHED_MINUTE = 200.0 / 1440.0

# Windred DP et al. 2024, Sleep 47(1):zsad253 — published quintile boundaries (Table 1)
# and the paper's own behavioural description of the extreme quintiles.
_WINDRED_MOST_REGULAR_BOUNDARY = 87.32  # Q4/Q5 — "within roughly a 1-hour band"
_WINDRED_LEAST_REGULAR_BOUNDARY = 71.65  # Q1/Q2 — "across roughly a 3-hour band"
_WINDRED_MEDIAN = 81.0

# Cribb L et al. 2023, eLife 12:RP88359 — the anchors biological_age interpolated until #86.
_CRIBB_MEDIAN = 60.0
_CRIBB_P5_HAZARD_ANCHOR = 41.0


def _reset(cur) -> None:
    for table in ("derived_daily", "sleep_session"):
        cur.execute(f"DELETE FROM {table}")


def _nights_shifting_by(cur, last_day: date, shift_min: int) -> None:
    """Seven nights that alternate between two bedtimes ``shift_min`` apart.

    Each night is a single 7-hour block from 01:00 (+ shift on alternate days) to
    08:00, deliberately inside ONE local day so a night maps to exactly one day-index
    — that keeps the expected mismatch exactly ``2 * shift_min`` per consecutive pair
    with no midnight-spanning arithmetic to reason about. Alternating (rather than
    drifting monotonically) makes EVERY one of the six pairs carry the same shift, so
    the recovered scale is the shift's, not an average of six different ones.

    Real ``sleep_session`` rows with a real hypnogram, because ``_compute_sri`` reads
    the minute grid: a fixture that wrote the derived row instead would share the
    code's assumption and could never fail (reference_ci_test_gotchas).
    """
    for k in range(SRI_DAYS):
        day = last_day - timedelta(days=SRI_DAYS - 1 - k)
        offset = timedelta(minutes=shift_min if k % 2 else 0)
        start = datetime.combine(day, time(1, 0), tzinfo=_ZONE) + offset
        end = start + timedelta(hours=7)
        stages = [[int(start.timestamp() * 1000), int(end.timestamp() * 1000), 4]]
        cur.execute(
            "INSERT INTO sleep_session (user_id, start_ts, end_ts, kind, rem_min, light_min, "
            "deep_min, wake_min, stages) VALUES (%s,%s,%s,'main',90,200,90,20,%s) "
            "ON CONFLICT (user_id, start_ts) DO NOTHING",
            (SENTINEL_USER_ID, start.astimezone(UTC), end.astimezone(UTC), json.dumps(stages)),
        )


def _sri_for_shift(shift_min: int) -> float:
    """Our estimator's SRI for a sleeper shifting both edges by ``shift_min``."""
    last_day = date(2026, 3, 20)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights_shifting_by(cur, last_day, shift_min)
        sri = _compute_sri(cur, SENTINEL_USER_ID, SENTINEL_TZ, last_day)
        _reset(cur)
    assert sri is not None, "the 7-night fixture must produce a full window"
    return sri


def _expected(shift_min: int) -> float:
    return round(100.0 - _POINTS_PER_MISMATCHED_MINUTE * 2 * shift_min, 2)


# ── 1 · the estimator's closed form ──────────────────────────────────────────


@pytest.mark.parametrize("shift_min", [0, 15, 30, 45, 60, 90, 120, 150, 180])
def test_our_sri_is_linear_in_the_nightly_shift(shift_min: int) -> None:
    """SRI = 100 - (200/1440) * 2d, measured on the real estimator.

    This is the identity every scale claim below rests on, so it is measured rather
    than assumed — and it is measured across the whole realistic range, not at one
    point, because a scale claim that only held near one value would not be a scale.
    """
    assert _sri_for_shift(shift_min) == pytest.approx(_expected(shift_min), abs=0.01)


def test_the_canonical_worked_example_still_reproduces() -> None:
    """The note's documented known value: 7 h sleep drifting 2 h between days → 66.67.

    Independent of the parametrized identity above — it is the number
    [[sleep_regularity_index]] has always claimed, so it anchors the closed form to the
    literature's worked example rather than only to our own arithmetic.
    """
    assert _sri_for_shift(120) == pytest.approx(66.67, abs=0.01)


# ── 2 · which published scale is ours? ───────────────────────────────────────


def test_windred_behaviour_run_through_our_estimator_lands_on_windred_numbers() -> None:
    """Windred's OWN description of its quintiles reproduces Windred's OWN boundaries.

    Windred 2024 says its most-regular quintile keeps sleep/wake "within roughly a
    1-hour band" (± ~30 min) and its least-regular varies "across roughly a 3-hour
    band" (± ~90 min), and separately publishes those quintiles' SRI cut-points. If
    our estimator sits on Windred's scale, feeding it Windred's behaviour must return
    Windred's numbers. It does: 91.67 against a published 87.32, and 75.00 against a
    published 71.65 — high by **4.35 and 3.35 points**. Small, same sign at both ends,
    and comfortably inside the slack in Windred's own hedged wording ("roughly a
    1-hour band"). Compare Cribb, where the same exercise is ~20 points out and
    implies behaviour no median adult has (next test).
    """
    most_regular = _sri_for_shift(30)
    least_regular = _sri_for_shift(90)

    high_at_top = most_regular - _WINDRED_MOST_REGULAR_BOUNDARY
    high_at_bottom = least_regular - _WINDRED_LEAST_REGULAR_BOUNDARY

    # Both ends high by a few points — a small offset, not a different scale.
    assert 3.0 < high_at_bottom < 5.0
    assert 3.0 < high_at_top < 5.0
    # Not a stretch either: the two offsets differ by ~1 point across a 16-point span.
    assert abs(high_at_top - high_at_bottom) < 1.5


def test_cribbs_anchors_are_not_on_our_scale() -> None:
    """Cribb's median and 5th-percentile anchor describe hours of nightly drift here.

    ``analytics/biological_age.py::_regularity_term`` interpolated Cribb's 41 → HR 1.53
    and 75 → HR 0.90 anchors against OUR SRI until #86. This test is the evidence that
    those two scales are different objects: on our estimator, Cribb's *median* sleeper
    moves their sleep by over two hours at each end every night — which Windred's cohort,
    quoted above, places in its least-regular quintile. A median is not a worst quintile,
    so the scales cannot be the same.

    It stays after the removal because it is the reason the removal is right, and because
    the next person to reach for a published SRI cutoff needs to find this before they
    reach for it. See [[sleep_regularity_index]] §Honesty.
    """

    # What nightly shift does our estimator need to produce Cribb's numbers?
    def shift_for(sri: float) -> float:
        return (100.0 - sri) / _POINTS_PER_MISMATCHED_MINUTE / 2

    assert _sri_for_shift(round(shift_for(_CRIBB_MEDIAN))) == pytest.approx(_CRIBB_MEDIAN, abs=0.5)
    # Cribb's median is >2 h of drift at each end on our scale...
    assert shift_for(_CRIBB_MEDIAN) > 120
    # ...i.e. worse than the behaviour behind Windred's LEAST-regular boundary.
    assert shift_for(_CRIBB_MEDIAN) > shift_for(_WINDRED_LEAST_REGULAR_BOUNDARY)
    # And Cribb's hazard anchor is near-total non-overlap: >7 h of drift at each end.
    assert shift_for(_CRIBB_P5_HAZARD_ANCHOR) > 180

    # Whereas Windred's median is ordinary behaviour on our scale (~1 h at each end).
    assert shift_for(_WINDRED_MEDIAN) < 90
