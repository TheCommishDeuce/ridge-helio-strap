"""The backup a phone restores from (D34 S5)."""

from __future__ import annotations

import psycopg
import pytest

from strap_server import backup
from tests import seed as _seed

pytestmark = pytest.mark.db


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        cur.execute("INSERT INTO manual_entry (user_id, kind, ts, amount, unit, name) VALUES (%s, 'caffeine', now(), 95, 'mg', 'coffee')", (_seed.OWNER,))
    return test_dsn


def test_the_records_hold_everything_but_the_samples(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = backup.records(conn.cursor(), _seed.OWNER, "UTC")
    assert out["profile"]["dob"] == "1990-01-01" and len(out["weights"]) == 1
    assert len(out["sleep"]) == 8 and out["sleep"][0]["source"] == "strap_ble" and out["sleep"][0]["stages"]
    assert len(out["workouts"]) == 1 and [j["kind"] for j in out["journal"]] == ["caffeine"]
    assert out["samples"] == len(_seed.samples())


def test_sample_pages_cover_every_row_once_even_when_rows_share_a_time(seeded) -> None:
    seen, after_ts, after_metric = [], None, ""
    with psycopg.connect(seeded) as conn:
        while True:
            page = backup.samples(conn.cursor(), _seed.OWNER, after_ts, after_metric, limit=7)
            seen += page["samples"]
            if page["next"] is None:
                break
            after_ts, after_metric = page["next"]["after_ts"], page["next"]["after_metric"]
    assert len(seen) == len(_seed.samples()) == len({(t, m) for t, m, _ in seen})
    assert seen == sorted(seen, key=lambda r: (r[0], r[1]))


def test_every_endpoint_the_phone_uses_is_served(db, test_dsn, monkeypatch) -> None:
    """Through HTTP, not the functions: an endpoint lost from api.py fails here (it once did)."""
    import hashlib

    from fastapi.testclient import TestClient

    from strap_server import api, config

    token = "backup-test-token"
    monkeypatch.setenv("DEVICE_TOKEN_SHA256", hashlib.sha256(token.encode()).hexdigest())
    monkeypatch.setenv("OWNER_ID", str(_seed.OWNER))
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    config.get_settings.cache_clear()
    try:
        client = TestClient(api.app)
        auth = {"Authorization": f"Bearer {token}"}
        for path in ("/v1/profile", "/v1/backup/records", "/v1/backup/samples", "/v1/journal?from=2026-01-01&to=2026-01-02"):
            assert client.get(path, headers=auth).status_code == 200, path
            assert client.get(path).status_code == 401, path
        put = client.put("/v1/profile", json={"height_cm": 180, "sex": "male", "dob": "1990-01-01", "srpa": 1}, headers=auth)
        assert put.status_code == 200, put.text
        assert client.post("/v1/ingest", json={}, headers=auth).status_code == 200
    finally:
        config.get_settings.cache_clear()
