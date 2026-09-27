"""A day no instrument counted is not a day of zero steps (audit C7).

``derive_daily_activity`` upserted ``steps_total`` unconditionally — "0 is valid, so
re-derivation overwrites stale rows" — and on a day the strap was not worn
``select_steps`` returns ``DailyValue(0.0, {"source": "steps_per_minute"})`` from a sum
over no rows at all. ``analytics/metrics.py``'s sentinel for the metric is ``value >= 0``,
so that zero entered every baseline and dragged the owner's usual down as though they had
walked nowhere, and it sat on the Today card as a measured "0". Compare ``rhr_daily``'s
``value > 30``, which exists precisely because an RHR of 0 means *not measured*.

The distinguishing evidence, and its limit, is stated in ``derive/activity.py``'s own
docstring: the strap's parser emits a ``steps_per_minute`` sample only for a minute that
recorded a step, so a day with no samples is a day no minute of which the instrument
spoke for. It cannot separate an unworn day from a worn day with no step in any minute of
it, and nothing in this server models wear.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from strap_server.derive.activity import derive_daily_activity
from tests.compat import SENTINEL_TZ, SENTINEL_USER_ID, tenant_transaction, user_today

pytestmark = pytest.mark.integration

ZONE = ZoneInfo(SENTINEL_TZ)


def _reset(cur) -> None:
    for table in ("derived_daily", "device_daily_total", "sample", "weight_log", "profile"):
        cur.execute(f"DELETE FROM {table}")


def _row(cur, day: date, metric: str) -> tuple[float, dict] | None:
    cur.execute(
        "SELECT value, flags FROM derived_daily WHERE user_id = %s AND day = %s AND metric = %s",
        (SENTINEL_USER_ID, day, metric),
    )
    row = cur.fetchone()
    return None if row is None else (float(row[0]), row[1] or {})


def _steps(cur, day: date, minutes: int) -> None:
    """``minutes`` per-minute step samples, from 09:00 local."""
    start = datetime.combine(day, time(9, 0), tzinfo=ZONE).astimezone(UTC)
    for i in range(minutes):
        cur.execute(
            "INSERT INTO sample (user_id, ts, metric, value) "
            "VALUES (%s, %s, 'steps_per_minute', %s)",
            (SENTINEL_USER_ID, start + timedelta(minutes=i), 40.0),
        )


def _counter(cur, day: date, steps: int | None, *, read_at: datetime) -> None:
    """A stored counter reading. `read_at` is when the strap was ASKED (0019).

    `reported_at` is left to its default (`now()`, the arrival) on purpose: after audit A1
    the partial-day disclosure reads `read_at` alone, and a test that set both to the same
    value could not tell the two apart.
    """
    cur.execute(
        "INSERT INTO device_daily_total (user_id, day, steps, source, read_at) "
        "VALUES (%s, %s, %s, 'strap_0x16', %s)",
        (SENTINEL_USER_ID, day, steps, read_at),
    )


@pytest.mark.usefixtures("db")
def test_a_day_no_instrument_counted_gets_no_step_row_at_all() -> None:
    """No samples and no counter → no ``steps_total``, not a measured zero."""
    day = user_today(SENTINEL_TZ) - timedelta(days=2)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        out = derive_daily_activity(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)
        stored = _row(cur, day, "steps_total")

    assert stored is None, "a zero here is an absence wearing a measurement's clothes"
    assert "steps_total" not in out


@pytest.mark.usefixtures("db")
def test_a_day_the_stream_spoke_for_is_written_with_its_count() -> None:
    """One minute is enough: the instrument spoke, so the day has an answer.

    ``sample_minutes`` ships with it, because a floor answers "may we say this" and a
    count answers "how much is behind it" — different questions.
    """
    day = user_today(SENTINEL_TZ) - timedelta(days=2)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _steps(cur, day, minutes=3)
        derive_daily_activity(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)
        stored = _row(cur, day, "steps_total")

    assert stored is not None
    value, flags = stored
    assert value == 120.0  # 3 minutes × 40
    assert flags["source"] == "steps_per_minute"
    assert flags["sample_minutes"] == 3


@pytest.mark.usefixtures("db")
def test_a_strap_that_reports_zero_steps_has_measured_zero_steps() -> None:
    """The device tier is untouched. A counter of 0 is a reading, and it is kept.

    This is the case the fix must NOT swallow: a narrowing that also dropped the device's
    own zero would be refusing a real measurement, which is the opposite failure.
    """
    day = user_today(SENTINEL_TZ) - timedelta(days=2)
    read_at = datetime.combine(day + timedelta(days=1), time(3, 0), tzinfo=ZONE).astimezone(UTC)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        _counter(cur, day, 0, read_at=read_at)
        derive_daily_activity(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)
        stored = _row(cur, day, "steps_total")

    assert stored is not None
    value, flags = stored
    assert value == 0.0
    assert flags["source"] == "strap_0x16"
    # Read after the day closed, so it describes the whole of it and discloses nothing.
    assert flags["caveats"] == []


@pytest.mark.usefixtures("db")
def test_an_uncounted_day_derives_no_stride_distance_either() -> None:
    """The stride tier multiplies the step count, so it inherits the step count's silence.

    A profile exists here, so the only thing standing between this day and a
    ``distance_m_daily`` of 0.0 is the rule under test.
    """
    day = user_today(SENTINEL_TZ) - timedelta(days=2)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _reset(cur)
        cur.execute(
            "INSERT INTO profile (user_id, height_cm, sex, dob) VALUES (%s, 178, 'male', %s)",
            (SENTINEL_USER_ID, date(1990, 1, 1)),
        )
        derive_daily_activity(cur, SENTINEL_USER_ID, SENTINEL_TZ, day)
        stored = _row(cur, day, "distance_m_daily")

    assert stored is None
