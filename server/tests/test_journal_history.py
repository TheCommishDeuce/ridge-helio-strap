"""Journal writes and the history reads behind the week/month views."""

from __future__ import annotations

import hashlib
import uuid
from datetime import UTC, date, datetime

import psycopg
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from strap_server import api, config, journal
from strap_server.derive import derive_day, derive_night
from strap_server.read.history import daily, workouts
from tests.derive import _seed

pytestmark = pytest.mark.db
TZ = "Asia/Kolkata"


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, TZ, start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, TZ, day)
    return test_dsn


def test_daily_history_returns_each_derived_day_and_leaves_gaps(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = daily(conn.cursor(), _seed.OWNER, ["steps_total", "sleep_health_score_4dim"], _seed.DAYS[0], _seed.DAYS[-1])
    assert len(out["metrics"]["steps_total"]) == 8
    assert "tst_min" in out["metrics"]["sleep_health_score_4dim"][0]["flags"]
    with psycopg.connect(seeded) as conn:
        empty = daily(conn.cursor(), _seed.OWNER, ["steps_total"], date(2026, 1, 1), date(2026, 1, 7))
    assert empty["metrics"]["steps_total"] == []


def test_workouts_in_range(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = workouts(conn.cursor(), _seed.OWNER, datetime(2026, 2, 1, tzinfo=UTC), datetime(2026, 4, 1, tzinfo=UTC))
    assert len(out) == 1 and out[0]["duration_s"] > 0


def test_caffeine_and_alcohol_round_trip_and_delete(seeded) -> None:
    ts = datetime(2026, 3, 5, 9, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        a = journal.add(conn, seeded, _seed.OWNER, journal.JournalIn(kind="caffeine", ts=ts, amount=80, name="flat white"))
        journal.add(conn, seeded, _seed.OWNER, journal.JournalIn(kind="alcohol", ts=ts, amount=1))
        listed = journal.entries(conn, _seed.OWNER, datetime(2026, 3, 5, tzinfo=UTC), datetime(2026, 3, 6, tzinfo=UTC))
        assert {e["kind"] for e in listed} >= {"caffeine", "alcohol"}
        assert next(e for e in listed if e["kind"] == "caffeine")["unit"] == "mg"
        assert journal.delete(conn, _seed.OWNER, a["id"])
        assert not journal.delete(conn, _seed.OWNER, a["id"])


def test_a_new_weight_rederives_the_days_after_it(seeded) -> None:
    ts = datetime(2026, 3, 4, 6, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        before = conn.execute("SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-06'").fetchone()[0]
        early = conn.execute("SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-02'").fetchone()[0]
        out = journal.add(conn, seeded, _seed.OWNER, journal.JournalIn(kind="weight", ts=ts, amount=82.0))
    with psycopg.connect(seeded) as conn:
        after = conn.execute("SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-06'").fetchone()[0]
        early_after = conn.execute("SELECT value FROM derived_daily WHERE metric = 'basal_calories' AND day = '2026-03-02'").fetchone()[0]
        listed = journal.entries(conn, _seed.OWNER, ts, datetime(2026, 3, 5, tzinfo=UTC))
    assert out["rederived_days"] > 0
    assert after == pytest.approx(before + 10 * (82.0 - 72.0))  # Mifflin: 10 kcal per kg
    assert early_after == early  # days before the weigh-in keep the old weight
    assert listed[0]["kind"] == "weight" and listed[0]["amount"] == 82.0


def test_implausible_entries_are_refused() -> None:
    ts = datetime(2026, 3, 5, tzinfo=UTC)
    for kind, amount in (("weight", 5), ("weight", 900), ("caffeine", 5000), ("alcohol", 99)):
        with pytest.raises(ValidationError):
            journal.JournalIn(kind=kind, ts=ts, amount=amount)
    with pytest.raises(ValidationError):
        journal.JournalIn(kind="caffeine", ts=datetime(2026, 3, 5), amount=80)  # noqa: DTZ001 — no offset, on purpose


def test_a_weight_before_the_first_sync_is_stored(db, test_dsn, monkeypatch) -> None:
    """A fresh server has no owner row until something creates it: the journal must, too."""
    owner, token = uuid.uuid4(), "journal-test-token"
    monkeypatch.setenv("DEVICE_TOKEN_SHA256", hashlib.sha256(token.encode()).hexdigest())
    monkeypatch.setenv("OWNER_ID", str(owner))
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    config.get_settings.cache_clear()
    try:
        r = TestClient(api.app).post(
            "/v1/journal", json={"kind": "weight", "ts": "2026-09-01T08:00:00+00:00", "amount": 74.0},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200, r.text
        with psycopg.connect(test_dsn) as conn:
            assert conn.execute("SELECT kg FROM weight_log WHERE user_id = %s", (owner,)).fetchone() == (74.0,)
            conn.execute("DELETE FROM app_user WHERE id = %s", (owner,))
    finally:
        config.get_settings.cache_clear()
