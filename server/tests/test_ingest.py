"""Ingest: the push the phone sends lands in the raw tables and re-derives what it touched."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

import psycopg
import pytest
from fastapi.testclient import TestClient

from strap_server import api, config
from strap_server.ingest.models import IngestPayload
from strap_server.ingest.service import ingest
from tests.derive._seed import OWNER

pytestmark = pytest.mark.db

TZ = "Europe/Berlin"
TOKEN = "test-token-not-secret"


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def night_payload() -> dict:
    """One night with overnight HR/HRV, then a morning of steps — the shape the phone pushes."""
    start = datetime(2026, 9, 24, 22, 30, tzinfo=UTC)
    end = start + timedelta(hours=8)
    samples = [{"metric": "hr", "ts": ms(start + timedelta(minutes=m)), "value": 50 + (m % 7)} for m in range(480)]
    samples += [{"metric": "hrv", "ts": ms(start + timedelta(minutes=m)), "value": 60.0} for m in range(0, 480, 5)]
    samples += [{"metric": "temperature_c", "ts": ms(start), "value": 33.1}]
    samples += [{"metric": "steps", "ts": ms(end + timedelta(minutes=30 + m)), "value": 110} for m in range(20)]
    samples += [{"metric": "made_up_stream", "ts": ms(end), "value": 1}]
    stages = [[ms(start), ms(start + timedelta(hours=2)), 4], [ms(start + timedelta(hours=2)), ms(end), 5]]
    return {
        "samples": samples,
        "sleep": [{"start_ts": ms(start), "end_ts": ms(end), "kind": "main", "score": 80, "avg_hr": 53,
                   "rem_min": 0, "light_min": 120, "deep_min": 360, "wake_min": 0, "stages": stages}],
        "workouts": [{"start_ts": ms(end + timedelta(hours=3)), "sport": 16, "duration_s": 1800, "calories": 250,
                      "avg_hr": 130, "max_hr": 160, "min_hr": 90}],
        "daily_totals": [{"read_at": ms(end + timedelta(hours=4)), "steps": 2400, "distance_m": 1800, "calories": 90}],
    }


def test_a_push_stores_raw_rows_and_derives_the_night_and_day(db, test_dsn) -> None:
    with psycopg.connect(test_dsn) as conn:
        summary = ingest(conn, OWNER, TZ, IngestPayload.model_validate(night_payload()))
        metrics = dict(conn.execute("SELECT metric, count(*) FROM sample GROUP BY metric").fetchall())
        derived = {r[0]: r[1] for r in conn.execute("SELECT metric, value FROM derived_daily WHERE day = '2026-09-25'")}
    assert summary.samples_dropped == 1 and summary.nights_derived == 1
    assert metrics["skin_temp_c"] == 1 and metrics["steps_per_minute"] == 20 and "temperature_c" not in metrics
    assert derived["rhr_daily"] == pytest.approx(53.0, abs=1.5)  # min 5-min bucket of the 50..56 cycle
    assert derived["hrv_sleep_avg"] == 60.0
    assert derived["steps_total"] == 2400  # the strap's counter wins over the per-minute sum
    assert "sleep_health_score_4dim" in derived


def test_a_repush_that_omits_fields_keeps_the_measured_values(db, test_dsn) -> None:
    payload = night_payload()
    with psycopg.connect(test_dsn) as conn:
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(payload))
        partial = {"sleep": [{k: v for k, v in payload["sleep"][0].items() if k in ("start_ts", "end_ts", "kind")}]}
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(partial))
        row = conn.execute("SELECT deep_min, score, jsonb_array_length(stages) FROM sleep_session").fetchone()
    assert row == (360, 80, 2)


def test_an_older_counter_reading_never_replaces_a_newer_one(db, test_dsn) -> None:
    day = datetime(2026, 9, 25, 10, 0, tzinfo=UTC)
    newer = {"daily_totals": [{"read_at": ms(day + timedelta(hours=5)), "steps": 5000}]}
    older = {"daily_totals": [{"read_at": ms(day), "steps": 1200}]}
    with psycopg.connect(test_dsn) as conn:
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(newer))
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(older))
        assert conn.execute("SELECT steps FROM device_daily_total").fetchone() == (5000,)


def test_the_api_requires_the_device_token(db, test_dsn, monkeypatch) -> None:
    monkeypatch.setenv("DEVICE_TOKEN_SHA256", hashlib.sha256(TOKEN.encode()).hexdigest())
    monkeypatch.setenv("OWNER_ID", str(OWNER))
    monkeypatch.setenv("OWNER_TIMEZONE", TZ)
    parts = dict(p.split("=", 1) for p in test_dsn.split())
    for key in ("host", "port", "dbname", "user", "password"):
        monkeypatch.setenv({"dbname": "POSTGRES_DB"}.get(key, f"POSTGRES_{key.upper()}"), parts[key])
    config.get_settings.cache_clear()
    client = TestClient(api.app)
    try:
        assert client.post("/v1/ingest", json={}).status_code == 401
        assert client.post("/v1/ingest", json={}, headers={"Authorization": "Bearer wrong"}).status_code == 401
        ok = client.post("/v1/ingest", json=night_payload(), headers={"Authorization": f"Bearer {TOKEN}"})
        assert ok.status_code == 200 and ok.json()["samples_stored"] > 0
        assert client.get("/healthz").json() == {"ok": True}
    finally:
        config.get_settings.cache_clear()


def test_the_cloud_backfill_never_overwrites_the_strap(db, test_dsn) -> None:
    """The strap's minute, night and day total win; the cloud fills only what is empty."""
    strap = night_payload()
    start = datetime(2026, 9, 24, 22, 30, tzinfo=UTC)
    cloud = {
        "source": "zepp_cloud",
        # Same minutes 20 s off the strap's grid, then one hour the strap never covered.
        "samples": [{"metric": "hr", "ts": ms(start + timedelta(minutes=m, seconds=20)), "value": 99} for m in range(5)]
        + [{"metric": "hr", "ts": ms(start - timedelta(hours=2, minutes=m)), "value": 70} for m in range(60)],
        "sleep": [{"start_ts": ms(start + timedelta(minutes=10)), "end_ts": ms(start + timedelta(hours=7)),
                   "kind": "main", "deep_min": 1}],
        "daily_totals": [{"read_at": ms(datetime(2026, 9, 26, tzinfo=UTC)), "day": "2026-09-25", "steps": 1}],
        "workouts": strap["workouts"],
    }
    with psycopg.connect(test_dsn) as conn:
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(strap))
        summary = ingest(conn, OWNER, TZ, IngestPayload.model_validate(cloud))
        assert summary.samples_stored == 60 and summary.sleep == 0 and summary.daily_totals == 0
        assert conn.execute("SELECT count(*) FROM sample WHERE metric = 'hr' AND value = 99").fetchone() == (0,)
        assert conn.execute("SELECT count(*), max(deep_min) FROM sleep_session").fetchone() == (1, 360)
        assert conn.execute("SELECT steps, source FROM device_daily_total").fetchone() == (2400, "strap_0x16")
        assert conn.execute("SELECT count(*) FROM workout").fetchone() == (1,)


def test_a_cloud_day_total_lands_on_its_own_day_as_a_closed_day(db, test_dsn) -> None:
    midnight = datetime(2026, 7, 2, tzinfo=UTC).astimezone(ZoneInfo(TZ)).replace(hour=0)
    cloud = {
        "source": "zepp_cloud",
        "daily_totals": [{"read_at": ms(midnight), "day": "2026-07-01", "steps": 8000, "distance_m": 6000}],
    }
    with psycopg.connect(test_dsn) as conn:
        assert ingest(conn, OWNER, TZ, IngestPayload.model_validate(cloud)).daily_totals == 1
        assert ingest(conn, OWNER, TZ, IngestPayload.model_validate(cloud)).daily_totals == 0  # idempotent
        assert conn.execute("SELECT day::text, steps, source FROM device_daily_total").fetchone() == (
            "2026-07-01", 8000, "zepp_cloud")
        flags = conn.execute(
            "SELECT value, flags FROM derived_daily WHERE day = '2026-07-01' AND metric = 'steps_total'"
        ).fetchone()
    assert flags[0] == 8000 and flags[1]["caveats"] == [] and flags[1]["source"] == "zepp_cloud"


def test_a_strap_push_after_the_backfill_replaces_what_the_cloud_put_there(db, test_dsn) -> None:
    """Backfill first, then the first sync: the strap's minute, night and day total win."""
    strap = night_payload()
    start = datetime(2026, 9, 24, 22, 30, tzinfo=UTC)
    cloud = {
        "source": "zepp_cloud",
        "samples": [{"metric": "hr", "ts": ms(start + timedelta(minutes=m)), "value": 99} for m in range(5)],
        "sleep": [{"start_ts": ms(start - timedelta(minutes=1)), "end_ts": ms(start + timedelta(hours=7)),
                   "kind": "main", "score": 10, "rem_min": 1, "light_min": 1, "deep_min": 1, "wake_min": 1}],
        "daily_totals": [{"read_at": ms(datetime(2026, 9, 26, tzinfo=UTC)), "day": "2026-09-25", "steps": 1, "calories": 5}],
    }
    with psycopg.connect(test_dsn) as conn:
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(cloud))
        ingest(conn, OWNER, TZ, IngestPayload.model_validate(strap))
        assert conn.execute("SELECT count(*) FROM sample WHERE metric = 'hr' AND value = 99").fetchone() == (0,)
        assert conn.execute("SELECT count(*), max(score), max(source) FROM sleep_session").fetchone() == (1, 80, "strap_ble")
        assert conn.execute("SELECT steps, calories, source FROM device_daily_total").fetchone() == (2400, 90, "strap_0x16")
