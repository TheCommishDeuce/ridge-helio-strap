"""Chart series: a known spike survives every zoom level; gaps stay gaps; local days are local."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import psycopg
import pytest

from strap_server.read.series import bucket_series, day_series
from tests.derive._seed import OWNER

pytestmark = pytest.mark.db

TZ = "Europe/Amsterdam"
DAY = date(2026, 9, 24)
LOCAL_MIDNIGHT_UTC = datetime(2026, 9, 23, 22, 0, tzinfo=UTC)  # CEST = UTC+2
SPIKE_AT = LOCAL_MIDNIGHT_UTC + timedelta(hours=14, minutes=37)


def _insert(conn: psycopg.Connection, rows: list[tuple[datetime, str, float]]) -> None:
    conn.cursor().executemany(
        "INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, %s, %s)", [(OWNER, *r) for r in rows]
    )


def _day_of_hr(conn: psycopg.Connection) -> None:
    """A full local day of 70 bpm, a 2-minute spike to 172, a 30-minute gap, and artefacts."""
    rows = []
    for m in range(24 * 60):
        ts = LOCAL_MIDNIGHT_UTC + timedelta(minutes=m)
        if 600 <= m < 630:
            continue  # not worn: a gap, never zeros
        rows.append((ts, "hr", 70.0))
    rows = [r for r in rows if r[0] not in (SPIKE_AT, SPIKE_AT + timedelta(minutes=1))]
    rows += [(SPIKE_AT, "hr", 172.0), (SPIKE_AT + timedelta(minutes=1), "hr", 168.0)]
    rows += [(LOCAL_MIDNIGHT_UTC - timedelta(minutes=1), "hr", 99.0)]  # previous local day
    _insert(conn, rows)
    conn.execute("UPDATE sample SET value = 250 WHERE ts = %s AND metric = 'hr'", (LOCAL_MIDNIGHT_UTC + timedelta(minutes=5),))


def test_the_day_view_is_every_minute_of_the_local_day_with_gaps_and_no_artefacts(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn:
        _day_of_hr(conn)
        out = day_series(conn.cursor(), OWNER, TZ, DAY, ["hr"])
    hr = out["series"]["hr"]
    assert len(hr) == 24 * 60 - 30 - 1  # the gap is absent; the 250 bpm artefact is filtered
    assert hr[0][0] == int(LOCAL_MIDNIGHT_UTC.timestamp() * 1000)  # starts at LOCAL midnight
    assert max(v for _, v in hr) == 172.0


@pytest.mark.parametrize("bucket", ["15m", "1h", "1d"])
def test_a_two_minute_spike_survives_every_zoom_level(db, test_dsn, bucket) -> None:
    with psycopg.connect(test_dsn) as conn:
        _day_of_hr(conn)
        out = bucket_series(conn.cursor(), OWNER, TZ, "hr", DAY, DAY, bucket)
    peak = max(out["buckets"], key=lambda b: b["max"])
    assert peak["max"] == 172.0
    assert peak["t_max"] == int(SPIKE_AT.timestamp() * 1000)
    assert peak["mean"] < 90  # the average alone would have hidden a 172 peak


def test_day_buckets_align_to_local_midnight_and_count_only_what_was_measured(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn:
        _day_of_hr(conn)
        day = bucket_series(conn.cursor(), OWNER, TZ, "hr", DAY, DAY, "1d")["buckets"]
        hours = bucket_series(conn.cursor(), OWNER, TZ, "hr", DAY, DAY, "1h")["buckets"]
    assert len(day) == 1 and day[0]["t"] == int(LOCAL_MIDNIGHT_UTC.timestamp() * 1000)
    assert day[0]["n"] == 24 * 60 - 30 - 1
    assert next(h for h in hours if h["t"] == int((LOCAL_MIDNIGHT_UTC + timedelta(hours=10)).timestamp() * 1000))["n"] == 30


def test_steps_buckets_carry_the_sum(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn:
        _insert(conn, [(LOCAL_MIDNIGHT_UTC + timedelta(hours=8, minutes=m), "steps_per_minute", 100.0) for m in range(12)])
        out = bucket_series(conn.cursor(), OWNER, TZ, "steps", DAY, DAY, "1h")["buckets"]
    assert out == [out[0]] and out[0]["sum"] == 1200 and out[0]["max"] == 100


def test_a_range_too_fine_for_one_screen_is_refused(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn, pytest.raises(ValueError, match="buckets"):
        bucket_series(conn.cursor(), OWNER, TZ, "hr", date(2025, 1, 1), date(2026, 1, 1), "15m")
