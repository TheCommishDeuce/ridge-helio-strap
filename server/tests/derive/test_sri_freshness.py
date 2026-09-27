"""The SRI's withhold gate, and the freshness rule consumers read it through.

Directive 4 of [[sleep_regularity_index]] is two halves — "do not COMPUTE or REPORT SRI
from <7 days of data" — and only the compute half was enforced (``_compute_sri`` returns
``None`` on a short grid, so nothing is written). Every consumer then read "the newest
``sleep_regularity_index`` row" and reported it as the owner's current regularity, which
breaks the report half in a way the compute half cannot catch: the row it reports IS from
a complete week, just not this one.

What is pinned here:

1. **The reader's gate agrees with the writer**, across the whole 0–7 night range. The
   payloads recompute the reason instead of storing it, so a gate that drifted between
   the two would let a surface explain a withhold that never happened.
2. **A complete week that is not TODAY's is still unavailable** — that is the whole
   defect, and the reason distinguishes it ("sync") from a short week ("wear the strap
   for the rest of the week"), because those send the owner to different actions.
3. **The fresh path is free.** A row keyed to today short-circuits before the gate runs,
   so freshness costs no query when the data is fresh — asserted by counting queries, not
   by reading the source.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.freshness import NOT_DERIVED_YET
from strap_server.derive.sleep_score import (
    SRI_DAYS,
    SRI_MESSAGES,
    SRI_WINDOW_TOO_SHORT,
    _compute_sri,
    sri_unavailable_reason,
    sri_withhold_reason_for_day,
)
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction, user_today

pytestmark = pytest.mark.integration

_ZONE = ZoneInfo(SENTINEL_TZ)


def _reset(cur) -> None:
    for table in ("derived_daily", "sleep_session"):
        cur.execute(f"DELETE FROM {table}")


def _nights(cur, last_wake: date, n: int) -> None:
    """``n`` consecutive main-sleep nights ending on ``last_wake`` (23:00 → 06:30 local).

    Real sessions with a hypnogram, because the gate reads the minute grid: a fixture
    that wrote the derived row instead would share the code's assumption and could not
    fail.
    """
    for k in range(n):
        wake = last_wake - timedelta(days=k)
        start = datetime.combine(wake - timedelta(days=1), time(23, 0), tzinfo=_ZONE)
        end = datetime.combine(wake, time(6, 30), tzinfo=_ZONE)
        stages = [[int(start.timestamp() * 1000), int(end.timestamp() * 1000), 4]]
        cur.execute(
            "INSERT INTO sleep_session (user_id, start_ts, end_ts, kind, rem_min, light_min, "
            "deep_min, wake_min, stages) VALUES (%s,%s,%s,'main',90,200,90,20,%s) "
            "ON CONFLICT (user_id, start_ts) DO NOTHING",
            (SENTINEL_USER_ID, start.astimezone(UTC), end.astimezone(UTC), json.dumps(stages)),
        )


# ── 1 · the reader's gate is the writer's gate ───────────────────────────────


# How many recorded nights this fixture needs before the grid covers all 7 day-indexes.
# SIX, not seven, because these nights start at 23:00 and end at 06:30: one session
# deposits asleep-minutes on TWO local days, so n nights cover n+1 day-indexes. That is
# ``_compute_sri``'s own long-standing behaviour — the gate mirrors it rather than
# re-deciding it, which is the point of sharing ``_sri_grid``. Written as a literal
# derived from the fixture, not from ``SRI_DAYS``, so a change to either fails here.
_NIGHTS_FOR_A_COMPLETE_GRID = 6


@pytest.mark.usefixtures("db")
@pytest.mark.parametrize("nights", list(range(SRI_DAYS + 1)))
def test_the_read_gate_and_the_write_gate_agree(nights: int) -> None:
    """``_compute_sri`` returns None exactly when the gate names a reason.

    Parametrised over EVERY night count including the boundary: an off-by-one in one of
    the two (``<`` vs ``<=``) is the failure this range exists to catch, and a matrix that
    only tested 0 and 7 would pass with the boundary broken.
    """
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, nights)
        reason = sri_withhold_reason_for_day(cur, SENTINEL_USER_ID, SENTINEL_TZ, today)
        computed = _compute_sri(cur, SENTINEL_USER_ID, SENTINEL_TZ, today)

    assert (reason is None) is (computed is not None)
    complete = nights >= _NIGHTS_FOR_A_COMPLETE_GRID
    assert reason == (None if complete else SRI_WINDOW_TOO_SHORT)


# ── 2 · a complete week is not evidence about a week it does not cover ───────


@pytest.mark.usefixtures("db")
def test_a_complete_but_older_week_is_not_todays_sri() -> None:
    """THE defect. The window is complete, so the note's compute gate is satisfied — and
    the value still is not a statement about today."""
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, SRI_DAYS)
        reason = sri_unavailable_reason(
            cur, SENTINEL_USER_ID, SENTINEL_TZ, today, today - timedelta(days=30)
        )
    assert reason == NOT_DERIVED_YET
    assert SRI_MESSAGES[NOT_DERIVED_YET].strip()


@pytest.mark.usefixtures("db")
def test_a_short_week_names_the_directive_rather_than_the_sync() -> None:
    """ "Wear the strap for the rest of the week" and "sync" are different instructions."""
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, 3)
        reason = sri_unavailable_reason(cur, SENTINEL_USER_ID, SENTINEL_TZ, today, None)
    assert reason == SRI_WINDOW_TOO_SHORT


class _CountingCursor:
    """A cursor that records how many statements were issued through it.

    A wrapper rather than a monkeypatch: psycopg's ``Cursor.execute`` is read-only, and
    wrapping keeps the real cursor answering anything the code does ask.
    """

    def __init__(self, cur) -> None:
        self._cur = cur
        self.calls = 0

    def execute(self, *args, **kwargs):
        self.calls += 1
        return self._cur.execute(*args, **kwargs)

    def __getattr__(self, name: str):
        return getattr(self._cur, name)


@pytest.mark.usefixtures("db")
def test_todays_row_is_available_and_asks_the_database_nothing() -> None:
    """The common path must not pay for the gate.

    ``unavailable_reason`` takes the gate as a callable precisely so a current row never
    triggers the grid rebuild. Counting the cursor's executions is the only way to assert
    that property without asserting on the source text.
    """
    today = user_today(SENTINEL_TZ)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _nights(cur, today, SRI_DAYS)
        spy = _CountingCursor(cur)
        reason = sri_unavailable_reason(spy, SENTINEL_USER_ID, SENTINEL_TZ, today, today)  # type: ignore[arg-type]

    assert reason is None
    assert spy.calls == 0


def test_every_sri_reason_has_a_message() -> None:
    """A reason with no message would reach a person as a bare machine string."""
    for reason in (SRI_WINDOW_TOO_SHORT, NOT_DERIVED_YET):
        assert SRI_MESSAGES[reason].strip()
