"""``mvpa_min`` is the WHO MET-equivalent total, not a raw minute count (audit C2).

WHO 2020 recommends 150–300 min/week of **moderate** OR 75–150 of **vigorous**, or any
equivalent combination — i.e. one vigorous minute counts as two moderate. The corpus
states that rule and its formula, ``moderate + 2 x vigorous``, five times across three
notes (``mvpa_minutes_mortality`` :37/:69/:137, ``mvpa_weekly_plan`` :81/:120,
``cadence_intensity`` :80), the app's own explainer says "vigorous minutes count double",
and ``read/fitness.py`` compares the weekly sum to a target of **150** — which is WHO's
*moderate-equivalent* number.

``derive/mvpa.py`` summed them un-weighted until 2026-09-08. So the product measured raw
minutes against a MET-equivalent target and under-credited every vigorous minute by half.
The error direction is the safe one — under-crediting, never flattery — which is exactly
why it survived a numeric sweep that found nothing else wrong in this file.

**Known-value test, hand-computed.** Nine walking minutes at 105 spm and four running
minutes at 140 spm, each with its debounce prior satisfied, is 9 moderate + 4 vigorous =
``9 + 2 x 4 = 17`` MET-equivalent minutes. The un-weighted answer is 13, and the
un-weighted answer is what this file exists to fail on.

The two halves stay un-weighted in the row's flags, and that is deliberate: the weekly
card's subline reads "moderate {m} + vigorous {v} x 2", so the UI needs the raw pair and
the total needs the weighting. One place applies it.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.mvpa import _VIGOROUS_MET_WEIGHT, derive_mvpa
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction, user_today

pytestmark = pytest.mark.integration

ZONE = ZoneInfo(SENTINEL_TZ)


def _reset(cur) -> None:
    for table in ("derived_daily", "sample"):
        cur.execute(f"DELETE FROM {table}")


def _cadence(cur, day: date, start_minute: int, spm: list[float]) -> None:
    """One ``steps_per_minute`` sample per minute, from 09:00 + ``start_minute`` local."""
    base = datetime.combine(day, time(9, 0), tzinfo=ZONE).astimezone(UTC)
    for i, value in enumerate(spm):
        cur.execute(
            "INSERT INTO sample (user_id, ts, metric, value) "
            "VALUES (%s, %s, 'steps_per_minute', %s)",
            (SENTINEL_USER_ID, base + timedelta(minutes=start_minute + i), value),
        )


def test_the_weight_is_two_and_is_named() -> None:
    """WHO's own equivalence. A magic 2 inline is what this constant replaced."""
    assert _VIGOROUS_MET_WEIGHT == 2


def test_a_known_day_sums_to_its_met_equivalent_total() -> None:
    """9 moderate + 4 vigorous = 17, and NOT 13."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        # A brisk-walk block: one prior minute at 85 spm to satisfy the moderate
        # debounce, then nine minutes at 105.
        _cadence(cur, day, 0, [85.0] + [105.0] * 9)
        # A run block, offset so it cannot chain onto the walk: one prior minute at
        # 115 spm to satisfy the vigorous debounce, then four minutes at 140.
        _cadence(cur, day, 30, [115.0] + [140.0] * 4)
        out = derive_mvpa(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)

    assert out is not None
    # The debounce priors are themselves counted: 85 is below the moderate floor of 100
    # so it contributes nothing, and 115 is below the vigorous floor of 130 but above
    # 100 — with a 0 spm predecessor it fails the >= 80 prior gate, so it too is out.
    assert out["moderate"] == 9, out
    assert out["vigorous"] == 4, out
    assert out["mvpa_min"] == 17, "one vigorous minute must count as two moderate"
    assert out["mvpa_min"] != out["moderate"] + out["vigorous"], (
        "the total is the un-weighted sum again — this is audit C2 returning"
    )


def test_the_stored_row_carries_the_weighted_total_and_the_raw_halves() -> None:
    """What the wire reads: the row's VALUE is weighted, its flags are not.

    ``read/mvpa_week.py`` sums stored ``mvpa_min`` values for the week and reports the
    ``moderate``/``vigorous`` flags separately. If the weighting lived in the read layer
    instead, a re-derive and a read would disagree — two definitions of one metric.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _cadence(cur, day, 0, [115.0] + [140.0] * 3)
        derive_mvpa(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)
        cur.execute(
            "SELECT value, flags FROM derived_daily "
            "WHERE user_id = %s AND day = %s AND metric = 'mvpa_min'",
            (SENTINEL_USER_ID, day),
        )
        row = cur.fetchone()

    assert row is not None
    value, flags = float(row[0]), row[1] or {}
    assert flags == {"moderate": 0, "vigorous": 3}
    assert value == 6.0, "three vigorous minutes are six MET-equivalent minutes"


def test_a_moderate_only_day_is_unchanged_by_the_weighting() -> None:
    """The owner's own days. The change must be invisible where it should be.

    This owner has recorded zero vigorous minutes, which is why C2 was invisible on his
    screen and why the audit banded it C rather than A. Pinning that keeps the fix from
    being blamed for a movement it did not cause.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        day = user_today(SENTINEL_TZ)
        _cadence(cur, day, 0, [85.0] + [105.0] * 12)
        out = derive_mvpa(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)

    assert out is not None
    assert out["vigorous"] == 0
    assert out["mvpa_min"] == out["moderate"] == 12
