"""The sleep payload names the source that actually supplied the night (audit C1).

`derive/sleep_score.derive_sleep_score` once stamped `session_source: "zepp_cloud"` on all
six rows a night produces, a verbatim carry-over from legacy, on nights the strap had
recorded over BLE. The fix made the strap's name a constant while the strap was the only
writer, and this file checked that premise, so that a second ingest path would fail here
rather than quietly make every row lie in the other direction.

The second path arrived (the Zepp cloud backfill, migration 0002), and as this file asked,
`sleep_session` got a `source` column instead. What is checked now is the property the
constant stood in for: each night serves the source of its own row, in both directions.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from strap_server.derive.sleep_score import SESSION_SOURCE
from strap_server.ingest.models import IngestPayload
from strap_server.ingest.service import ingest
from tests.derive._seed import OWNER


def test_the_default_source_names_the_strap() -> None:
    assert SESSION_SOURCE == "strap_ble"


@pytest.mark.db
def test_each_night_serves_its_own_source(db, test_dsn) -> None:
    def night(day: int) -> dict:
        start = datetime(2026, 7, day, 22, 0, tzinfo=UTC)
        end = start + timedelta(hours=8)
        return {"start_ts": ms(start), "end_ts": ms(end), "kind": "main",
                "rem_min": 90, "light_min": 240, "deep_min": 120, "wake_min": 30}

    with psycopg.connect(test_dsn) as conn:
        ingest(conn, OWNER, "UTC", IngestPayload.model_validate({"sleep": [night(1)]}))
        ingest(conn, OWNER, "UTC", IngestPayload.model_validate({"source": "zepp_cloud", "sleep": [night(3)]}))
        served = dict(conn.execute(
            "SELECT day::text, flags->>'session_source' FROM derived_daily WHERE metric = 'sleep_health_score_4dim'"
        ).fetchall())
    assert served == {"2026-07-02": "strap_ble", "2026-07-04": "zepp_cloud"}


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)
