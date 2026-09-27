"""Two main sessions ending on one wake date stop being invisible (write-path audit B7).

`derive_night` keys every metric it writes to `_wake_date(end_ts)`, so two `kind='main'`
sessions ending on the same local date both write `rhr_daily`, `hrv_sleep_avg`,
`spo2_overnight`, `respiratory_rate_sleep` and `sleep_health_score_4dim` for that date —
and the later one wins every cell. `flags.tst_min` then holds only the second fragment's
total sleep time, which `_tst_window` reads into the 14-night debt and
`recovery._sleep_factor` reads into readiness. A fragmented night reads as a short one,
twice over.

**This is SUSPECTED and stays that way.** Nothing in the schema or the client prevents the
shape (`sleep_session`'s key is `(user_id, start_ts)`; the client sends `kind: isNap ?
'nap' : 'main'`), and whether the strap ever produces it is a statement about firmware
that needs the device. Deriving over the wake date's main sessions as one window set is a
science behaviour change and is owed evidence that the shape occurs, not the possibility
that it might.

So what is tested is the DETECTION: the condition was invisible, and now it names itself.
"""

from __future__ import annotations

import logging
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.orchestrator import derive_night
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction

pytestmark = pytest.mark.usefixtures("db")

ZONE = ZoneInfo(SENTINEL_TZ)
_WAKE = date(2026, 6, 16)
_WARNING = "more than one main sleep session ends on this wake date"


def _session(cur, *, end_hour: int, hours: float, kind: str = "main") -> tuple:
    end = datetime.combine(_WAKE, time(end_hour, 0), tzinfo=ZONE)
    start = end - timedelta(hours=hours)
    cur.execute(
        "INSERT INTO sleep_session "
        "(user_id,start_ts,end_ts,kind,rem_min,light_min,deep_min,wake_min,stages) "
        "VALUES (%s,%s,%s,%s,60,180,60,30,'[]'::jsonb)",
        (SENTINEL_USER_ID, start.astimezone(UTC), end.astimezone(UTC), kind),
    )
    return start.astimezone(UTC), end.astimezone(UTC)


def _reset(cur) -> None:
    cur.execute("DELETE FROM sleep_session WHERE user_id = %s", (SENTINEL_USER_ID,))
    cur.execute("DELETE FROM derived_daily WHERE user_id = %s", (SENTINEL_USER_ID,))


def test_a_wake_date_with_two_main_sessions_says_so(caplog: pytest.LogCaptureFixture) -> None:
    """The fragmented night: asleep 23:00-02:00, awake, asleep 03:00-06:00.

    Both fragments end on 2026-06-16 locally, so both derive into the same cells.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _session(cur, end_hour=2, hours=3)
        second = _session(cur, end_hour=6, hours=3)
        with caplog.at_level(logging.WARNING):
            derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, *second)

    assert _WARNING in caplog.text
    # The structured fields, not the message: a warning that cannot say WHICH date and
    # HOW MANY sessions is a warning nobody can act on, and `extra=` never reaches
    # `caplog.text`, so asserting the sentence alone would pass against an empty one.
    record = next(r for r in caplog.records if _WARNING in r.getMessage())
    assert record.wake_date == "2026-06-16"  # type: ignore[attr-defined]
    assert record.sessions == 2  # type: ignore[attr-defined]


def test_one_night_says_nothing(caplog: pytest.LogCaptureFixture) -> None:
    """The ordinary case must stay silent, or the warning is noise nobody reads."""
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        only = _session(cur, end_hour=6, hours=7)
        with caplog.at_level(logging.WARNING):
            derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, *only)

    assert _WARNING not in caplog.text


def test_a_nap_on_the_same_date_is_not_a_second_night(caplog: pytest.LogCaptureFixture) -> None:
    """A nap writes no metric keyed to a wake date, so it cannot collide with one.

    Counting it would make the warning fire on the most ordinary day there is.
    """
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        night = _session(cur, end_hour=6, hours=7)
        _session(cur, end_hour=14, hours=0.5, kind="nap")
        with caplog.at_level(logging.WARNING):
            derive_night(cur, SENTINEL_USER_ID, SENTINEL_TZ, *night)

    assert _WARNING not in caplog.text
