"""Travel (D31): a zone change pushed by the phone re-cuts the owner's days."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import psycopg
import pytest

from strap_server.ingest.models import IngestPayload
from strap_server.ingest.service import ingest
from strap_server.read import series
from strap_server.zones import Zones
from tests.derive._seed import OWNER

LANDED = datetime(2026, 10, 5, 15, 0, tzinfo=UTC)  # Berlin (+2) → Dubai (+4)


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


@pytest.fixture
def berlin(db, test_dsn):
    with psycopg.connect(test_dsn) as conn:
        conn.execute("UPDATE app_user SET timezone = 'Europe/Berlin' WHERE id = %s", (OWNER,))
    yield test_dsn
    with psycopg.connect(test_dsn) as conn:
        conn.execute("UPDATE app_user SET timezone = 'Asia/Kolkata' WHERE id = %s", (OWNER,))


def hr_every_ten_minutes(first: datetime, last: datetime) -> list[dict]:
    out, t = [], first
    while t < last:
        out.append({"metric": "hr", "ts": ms(t), "value": 70.0})
        t += timedelta(minutes=10)
    return out


@pytest.mark.db
def test_a_zone_change_recuts_the_days(berlin) -> None:
    samples = hr_every_ten_minutes(datetime(2026, 10, 4, 0, 0, tzinfo=UTC), datetime(2026, 10, 7, 0, 0, tzinfo=UTC))
    with psycopg.connect(berlin) as conn:
        ingest(conn, OWNER, IngestPayload.model_validate({"samples": samples}))
        with conn.cursor() as cur:
            before = series.day_series(cur, OWNER, Zones.load(cur, OWNER), date(2026, 10, 6), ["hr"])
        assert before["timezone"] == "Europe/Berlin"
        assert before["start"] == ms(datetime(2026, 10, 5, 22, 0, tzinfo=UTC))

        summary = ingest(conn, OWNER, IngestPayload.model_validate({"zones": [{"since": ms(LANDED), "timezone": "Asia/Dubai"}]}))
        assert summary.days_derived >= 2  # the day left and every day after it

        with conn.cursor() as cur:
            zones = Zones.load(cur, OWNER)
            left = series.day_series(cur, OWNER, zones, date(2026, 10, 5), ["hr"])
            arrived = series.day_series(cur, OWNER, zones, date(2026, 10, 6), ["hr"])
        # The day left runs Berlin midnight → Dubai midnight: 22 hours.
        assert left["timezone"] == "Europe/Berlin"
        assert (left["start"], left["end"]) == (ms(datetime(2026, 10, 4, 22, 0, tzinfo=UTC)), ms(datetime(2026, 10, 5, 20, 0, tzinfo=UTC)))
        assert len(left["series"]["hr"]) == 22 * 6
        assert arrived["timezone"] == "Asia/Dubai"
        assert arrived["start"] == ms(datetime(2026, 10, 5, 20, 0, tzinfo=UTC))
        assert len(arrived["series"]["hr"]) == 24 * 6


@pytest.mark.db
def test_the_same_change_twice_is_not_new(berlin) -> None:
    change = {"zones": [{"since": ms(LANDED), "timezone": "Asia/Dubai"}]}
    with psycopg.connect(berlin) as conn:
        ingest(conn, OWNER, IngestPayload.model_validate(change))
        again = ingest(conn, OWNER, IngestPayload.model_validate(change))
        assert again.days_derived == 0
        rows = conn.execute("SELECT count(*) FROM zone_change WHERE user_id = %s", (OWNER,)).fetchone()[0]
    assert rows == 1


def test_an_unknown_zone_is_refused() -> None:
    with pytest.raises(ValueError):
        IngestPayload.model_validate({"zones": [{"since": 0, "timezone": "Mars/Olympus"}]})
