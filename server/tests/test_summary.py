"""Day summary: cards carry the derived numbers with context, or a named withheld reason."""

from __future__ import annotations

import json
from datetime import date, time
from pathlib import Path

import psycopg
import pytest

from strap_server.derive import derive_day, derive_night
from strap_server.read.summary import (
    _usual_mean,
    day_summary,
    decayed_readiness,
    steps_usual_by,
    strain_from_load,
)
from tests.derive import _seed

TZ = "Asia/Kolkata"
_FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "derive" / "expected_daily.json").read_text())


def _golden(day: str, metric: str) -> float:
    return next(r["value"] for r in _FIXTURE if r["day"] == day and r["metric"] == metric)


# ── verbatim read-time formulas, known values ──


def test_strain_is_21_at_the_personal_p95_and_concave_below() -> None:
    assert strain_from_load(100, 100) == 21.0
    assert strain_from_load(0, 100) == 0.0
    assert strain_from_load(50, 100) == 12.5  # 21 x 0.5^0.75 = 12.487
    assert strain_from_load(300, 100) == 21.0  # capped
    assert strain_from_load(50, None) is None


def test_readiness_decays_at_most_by_half() -> None:
    assert decayed_readiness(80, 50, 100) == 60
    assert decayed_readiness(80, 300, 100) == 40
    assert decayed_readiness(80, 50, 0) == 80


def test_illness_framing_names_the_baseline_and_is_not_a_diagnosis() -> None:
    from strap_server.read.summary import illness_framing

    assert illness_framing(2.4, 0.35, True) == (
        "Possible early signal — consider lighter activity today. Breathing rate +2.4 bpm vs your 14-day "
        "baseline; skin temperature +0.35°C — sustained across two nights, the Smarr 2020 / Quer 2021 pattern. "
        "Not a diagnosis."
    )


# ── cards over the golden seed ──


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
        for start, end in _seed.nights():
            derive_night(cur, _seed.OWNER, TZ, start, end)
        for day in _seed.DAYS:
            derive_day(cur, _seed.OWNER, TZ, day)
    return test_dsn


@pytest.mark.db
def test_cards_carry_the_derived_numbers(seeded) -> None:
    day = _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        out = day_summary(conn.cursor(), _seed.OWNER, TZ, day)
    iso = day.isoformat()
    assert out["recovery"]["value"] == round(_golden(iso, "recovery_score"))
    assert out["recovery"]["flags"]["method"] == "evidence_weighted_personal_baseline"
    assert out["steps"]["steps"]["value"] == round(_golden(iso, "steps_total"))
    assert out["strain"]["cardio_load"] == round(_golden(iso, "cardio_load"), 1)
    assert 0 < out["strain"]["value"] <= 21
    assert out["sleep"]["health"]["dimensions"] == _golden(iso, "sleep_health_score_4dim")
    assert out["sleep"]["sessions"] and out["sleep"]["sessions"][0]["kind"] == "main"
    assert out["sleep"]["sessions"][0]["device_score"] == 85  # the seed's strap score
    assert out["sleep"]["need_min"] == 480  # 36 years old: NSF 2015 18-64 band
    assert out["heart"]["resting"]["value"] == round(_golden(iso, "rhr_daily"))
    assert out["heart"]["resting"]["baseline"]["n"] == 7  # the seven days before
    assert out["heart"]["today"]["max"] >= out["heart"]["today"]["min"] > 0
    assert out["vo2max"]["value"] == round(_golden(iso, "vo2max_estimate"), 1)
    assert out["illness"] is None


@pytest.mark.db
def test_a_day_without_data_is_withheld_by_name_never_null(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        out = day_summary(conn.cursor(), _seed.OWNER, TZ, date(2026, 6, 1))
    for card in (out["recovery"], out["strain"], out["steps"]["steps"], out["heart"]["resting"], out["heart"]["today"], out["vo2max"]):
        assert card["withheld"]["reason"] and card["withheld"]["message"]
    assert out["sleep"]["sessions"] == [] and "withheld" in out["sleep"]["health"]


@pytest.mark.db
def test_usual_steps_by_a_clock_time_scale_each_days_total_by_its_share_so_far(seeded) -> None:
    day = _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        cur = conn.cursor()
        by_0811 = steps_usual_by(cur, _seed.OWNER, TZ, day, time(8, 11))
        by_noon = steps_usual_by(cur, _seed.OWNER, TZ, day, time(12, 0))
        too_few = steps_usual_by(cur, _seed.OWNER, TZ, _seed.DAYS[3], time(12, 0))
    total = _golden(_seed.DAYS[0].isoformat(), "steps_total")
    # The seed walks 21 x 110 + 120 + 5 x 135 = 3105 per-minute steps, 11 x 110 of them before 08:11.
    assert by_0811 == {"median": round(total * 1210 / 3105), "n": 7, "until": "08:11"}
    assert by_noon["median"] == round(total)
    assert too_few is None  # three days before DAYS[3]


@pytest.mark.db
def test_usual_mean_compares_the_same_hours_of_other_days(seeded) -> None:
    day = _seed.DAYS[-1]
    with psycopg.connect(seeded) as conn:
        cur = conn.cursor()
        walk = _usual_mean(cur, _seed.OWNER, TZ, day, "hr", time(9, 0))
        out = day_summary(cur, _seed.OWNER, TZ, day)
    assert walk["n"] == 7 and walk["until"] == "09:00"
    assert out["heart"]["today"]["usual"]["n"] == 8  # the first seeded night starts the evening before DAYS[0]
    assert out["heart"]["today"]["usual"]["until"] is None  # a past day compares whole days
    assert "usual_by_now" not in out["steps"]["steps"]  # only a day still running gets one
