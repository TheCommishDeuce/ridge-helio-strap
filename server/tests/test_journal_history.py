"""Journal and profile writes: the server keeps the phone's copy (D34)."""

from __future__ import annotations

import hashlib
import uuid
from datetime import UTC, date, datetime

import psycopg
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from strap_server import api, config, journal
from strap_server.backup import profile
from tests import seed as _seed

pytestmark = pytest.mark.db
TZ = "Asia/Kolkata"


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
    return test_dsn


def test_caffeine_and_alcohol_round_trip_and_delete(seeded) -> None:
    ts = datetime(2026, 3, 5, 9, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        a = journal.add(conn, _seed.OWNER, journal.JournalIn(kind="caffeine", ts=ts, amount=80, name="flat white"))
        journal.add(conn, _seed.OWNER, journal.JournalIn(kind="alcohol", ts=ts, amount=1))
        listed = journal.entries(conn, _seed.OWNER, datetime(2026, 3, 5, tzinfo=UTC), datetime(2026, 3, 6, tzinfo=UTC))
        assert {e["kind"] for e in listed} >= {"caffeine", "alcohol"}
        assert next(e for e in listed if e["kind"] == "caffeine")["unit"] == "mg"
        assert journal.delete(conn, _seed.OWNER, a["id"])
        assert not journal.delete(conn, _seed.OWNER, a["id"])


def test_a_status_round_trips_and_only_names_a_status(seeded) -> None:
    ts = datetime(2026, 3, 6, 12, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        s = journal.add(conn, _seed.OWNER, journal.JournalIn(kind="status", ts=ts, amount=1, name="sick"))
        listed = journal.entries(conn, _seed.OWNER, datetime(2026, 3, 6, tzinfo=UTC), datetime(2026, 3, 7, tzinfo=UTC))
        assert [(e["kind"], e["name"], e["unit"]) for e in listed] == [("status", "sick", "day")]
        assert journal.delete(conn, _seed.OWNER, s["id"])
    for name in (None, "active", "tired"):
        with pytest.raises(ValidationError):
            journal.JournalIn(kind="status", ts=ts, amount=1, name=name)


def test_a_weight_is_stored_with_its_time_as_its_id(seeded) -> None:
    ts = datetime(2026, 3, 4, 6, 0, tzinfo=UTC)
    with psycopg.connect(seeded) as conn:
        out = journal.add(conn, _seed.OWNER, journal.JournalIn(kind="weight", ts=ts, amount=82.0))
        listed = journal.entries(conn, _seed.OWNER, ts, datetime(2026, 3, 5, tzinfo=UTC))
    assert out == {"id": f"weight:{int(ts.timestamp() * 1000)}"}
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


def test_the_profile_download_carries_what_the_phone_computes_with(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = profile(conn.cursor(), _seed.OWNER, "UTC")
    assert out["profile"] == {"height_cm": 175.0, "sex": "male", "dob": "1990-01-01", "srpa": 2}
    assert out["weights"] == [{"ts": int(_seed.WEIGHT_TS.timestamp() * 1000), "kg": 72.0}]
    assert out["timezone"] == "Asia/Kolkata" and out["zone_changes"] == []


def test_a_fresh_server_has_no_profile_and_its_configured_zone(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn:
        out = profile(conn.cursor(), uuid.uuid4(), "Europe/Berlin")
    assert out == {"profile": None, "weights": [], "timezone": "Europe/Berlin", "zone_changes": []}


def test_the_phones_profile_replaces_the_servers(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        journal.set_profile(conn, _seed.OWNER, journal.ProfileIn(height_cm=180, sex="male", dob=date(1990, 1, 1), srpa=3))
        assert profile(conn.cursor(), _seed.OWNER, "UTC")["profile"] == {"height_cm": 180.0, "sex": "male", "dob": "1990-01-01", "srpa": 3}


def test_an_implausible_profile_is_refused() -> None:
    with pytest.raises(ValidationError):
        journal.ProfileIn(height_cm=20)
    with pytest.raises(ValidationError):
        journal.ProfileIn(srpa=5)
