"""Charge (D33, spec/02 §2.12): the minute simulation, known values and the order of states."""

from __future__ import annotations

from datetime import date

import pytest

from strap_server.derive.charge import (
    DEFAULT_THRESHOLD,
    LIVING,
    START_VALUE,
    Minute,
    hrv_factor,
    personal_threshold,
    simulate,
)

RHR, HRMAX = 55.0, 185.0  # reserve 130 bpm: 30 % HRR = 94 bpm, 20 % = 81 bpm


def run(minutes: list[Minute], threshold: float = DEFAULT_THRESHOLD):
    return simulate(minutes, RHR, HRMAX, "male", threshold)


def test_a_quiet_awake_minute_drains_the_living_rate_and_no_data_holds() -> None:
    r = run([Minute(hr=60, stress=40, steps=20)] * 10 + [Minute()] * 5)
    assert r.values[9] == pytest.approx(START_VALUE - 10 * LIVING)
    assert r.values[14] == r.values[9]  # strap off: the value holds
    assert r.states[0] == "rest" and r.states[-1] == "no_data"
    assert sum(d["living"] for d in r.drained[:10]) == pytest.approx(10 * LIVING)


def test_sleep_refills_a_share_of_the_room_left_so_it_slows_as_it_fills() -> None:
    night = [Minute(hr=55, stage=5, night_rhr=55)] * 450  # 7.5 h of deep sleep at resting HR
    r = run(night)
    first_gain = r.values[0] - START_VALUE
    assert first_gain == pytest.approx(0.006 * 1.0 * 1.2 * (100 - START_VALUE))  # calm heart: relax 1.2
    assert r.values[-1] > 95 and r.values[-1] - r.values[-2] < first_gain / 10
    assert all(r.slept)


def test_a_racing_heart_in_sleep_and_light_stages_recharge_less() -> None:
    calm = run([Minute(hr=55, stage=5, night_rhr=55)] * 60).values[-1]
    racing = run([Minute(hr=70, stage=5, night_rhr=55)] * 60).values[-1]  # relax 1.2 − 0.6 = 0.6
    light = run([Minute(hr=55, stage=4, night_rhr=55)] * 60).values[-1]
    assert calm > light > START_VALUE and calm > racing > START_VALUE


def test_exercise_drains_by_its_trimp_and_its_after_burn_is_recovery_not_stress() -> None:
    workout = [Minute(hr=150, stress=90, steps=0)] * 30
    after = [Minute(hr=88, stress=90, steps=0)] * 10  # 25 % HRR, falling; stress still high
    r = run(workout + after)
    assert set(r.states[:30]) == {"exercise"}
    assert set(r.states[30:]) == {"exercise_recovery"}
    activity = sum(d.get("activity", 0) for d in r.drained)
    assert activity > 30 * LIVING and sum(d.get("stress", 0) for d in r.drained) == 0


def test_stress_drains_only_after_three_minutes_over_the_threshold() -> None:
    r = run([Minute(hr=65, stress=80, steps=0)] * 5, threshold=60)
    assert r.states == ["rest", "rest", "stress", "stress", "stress"]
    assert r.drained[2]["stress"] == pytest.approx(0.007 * 20)


def test_steps_count_as_activity_and_a_calm_still_minute_relaxes() -> None:
    r = run([Minute(hr=62, stress=30, steps=90), Minute(hr=58, stress=10, steps=0)])
    assert r.states == ["activity", "relax"]
    assert r.recharged[1] > 0  # at ~50 the relax gain (0.0012 × 50 = 0.06) outweighs the living rate


def test_charge_stays_inside_0_to_100() -> None:
    drained = run([Minute(hr=180, stress=99, steps=0)] * 600)
    filled = run([Minute(hr=50, stage=5, night_rhr=55, hrv_factor=1.1)] * 3000)
    assert min(drained.values) == 0.0 and max(filled.values) <= 100.0


def test_hrv_factor_is_one_on_a_usual_night_and_bounded() -> None:
    assert hrv_factor(50) == 1.0
    assert hrv_factor(None) == 1.0
    assert hrv_factor(0) == 0.75
    assert hrv_factor(100) == 1.1  # 1.25 capped
    assert hrv_factor(-100) == 0.6


def test_the_stress_threshold_is_personal_only_with_enough_calm_readings() -> None:
    days = {date(2026, 10, d): [30.0] * 25 for d in range(1, 4)}
    assert personal_threshold(days) == (50.0, True)  # median 30 + 10 = 40, kept at the 50 floor
    assert personal_threshold({date(2026, 10, 1): [30.0] * 100}) == (DEFAULT_THRESHOLD, False)  # one day
    assert personal_threshold({d: [65.0] * 30 for d in days}) == (70.0, True)  # 75 capped
