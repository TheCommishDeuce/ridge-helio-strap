"""A PARTIAL stage breakdown scores nothing either (write-path audit B3).

`derive_night`'s gate was `any(v is not None for v in sr)` over `int(v or 0)`. The
comment above it argued — correctly — that a night with NO breakdown must not be scored,
and `0018` made that state representable. A night with SOME of the breakdown still became
zeros for the rest.

The sharpest case is an absent `wake_min`. `_sleep_efficiency(tst, 0)` is
`min(1.0, tst/tst)`, i.e. exactly 1.0, so the night scored a full efficiency point and
stored `flags.efficiency_pct = 100.0` — a fabricated PERFECT efficiency, on a dimension of
the 4-dim score, from a measurement nobody made. An absent `rem_min` or `light_min`
understates `tst_min` instead, which `_tst_window` reads into the 14-night sleep debt and
`recovery._sleep_factor` reads into readiness.

Latent with the shipped clients (the v02 store's four stage columns are non-nullable and
`push_batch` sends all four), and closed anyway for the same reason `0018` was:
`/ingest/helio` is reachable by any device token, including an older app build.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.orchestrator import derive_night
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction

pytestmark = pytest.mark.usefixtures("db")

ZONE = ZoneInfo(SENTINEL_TZ)
_WAKE = date(2026, 6, 16)
# 7 h in bed, 6 h staged asleep, 60 min awake — a complete, ordinary night.
_COMPLETE = (90, 210, 60, 60)  # rem, light, deep, wake


def _night(cur, minutes: tuple[int | None, ...]) -> tuple[datetime, datetime]:
    cur.execute("DELETE FROM sleep_session WHERE user_id = %s", (SENTINEL_USER_ID,))
    cur.execute("DELETE FROM derived_daily WHERE user_id = %s", (SENTINEL_USER_ID,))
    end = datetime.combine(_WAKE, time(7, 0), tzinfo=ZONE)
    start = end - timedelta(hours=7)
    cur.execute(
        "INSERT INTO sleep_session "
        "(user_id,start_ts,end_ts,kind,rem_min,light_min,deep_min,wake_min,stages) "
        "VALUES (%s,%s,%s,'main',%s,%s,%s,%s,'[]'::jsonb)",
        (SENTINEL_USER_ID, start.astimezone(UTC), end.astimezone(UTC), *minutes),
    )
    return start.astimezone(UTC), end.astimezone(UTC)


def _score_row(cur) -> tuple | None:
    cur.execute(
        "SELECT value, flags FROM derived_daily "
        "WHERE user_id = %s AND day = %s AND metric = 'sleep_health_score_4dim'",
        (SENTINEL_USER_ID, _WAKE),
    )
    return cur.fetchone()


def test_a_night_with_no_recorded_wake_minutes_scores_nothing() -> None:
    """THE case. An unknown denominator term used to produce an efficiency of exactly 1.0."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        start, end = _night(cur, (90, 210, 60, None))
        out = derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, start, end)
        stored = _score_row(cur)

    assert "sleep_health_score_4dim" not in out
    assert stored is None, "a 100% efficiency nobody measured is worse than no score"


def test_a_night_missing_one_sleep_stage_scores_nothing() -> None:
    """An absent `rem_min` understates TST, which feeds the debt and the readiness score."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        start, end = _night(cur, (None, 210, 60, 60))
        out = derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, start, end)
        stored = _score_row(cur)

    assert "sleep_health_score_4dim" not in out
    assert stored is None


def test_a_night_with_no_breakdown_at_all_still_scores_nothing() -> None:
    """The case `0018` opened, unchanged: the narrowing must not have moved the floor."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        start, end = _night(cur, (None, None, None, None))
        derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, start, end)
        assert _score_row(cur) is None


def test_a_complete_breakdown_still_scores_and_a_genuine_zero_is_a_measurement() -> None:
    """The other direction, and the one a narrowing must not swallow.

    A recorded 0 minutes of deep sleep IS a measurement, and `all(v is not None ...)`
    admits it where a truthiness gate would not.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        start, end = _night(cur, _COMPLETE)
        derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, start, end)
        complete = _score_row(cur)

        start, end = _night(cur, (90, 210, 0, 60))
        derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, start, end)
        zero_deep = _score_row(cur)

    assert complete is not None
    assert complete[1]["tst_min"] == 360
    assert complete[1]["efficiency_pct"] == pytest.approx(85.7, abs=0.1)
    assert zero_deep is not None, "a measured zero is not an absence"
    assert zero_deep[1]["tst_min"] == 300
