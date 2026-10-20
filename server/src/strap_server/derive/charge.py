"""Charge 0-100, minute by minute: sleep fills it, the waking day drains it (D33).

A body-battery, replacing the old "readiness now" (recovery decayed by today's load), which
only exercise could move. The model is OpenStrap's (github.com/sayed0am/openstrap,
`docs/algorithms/calculations.md` §7, MIT/Apache-2.0), adapted to Ridge's own definitions:
the exercise drain uses the ONE TRIMP (``derive/trimp``) with cardio load's resting and max
HR, the sleep relaxation term uses ``rhr_daily`` of that night, and the HRV term uses the
recovery score's HRV part (50 = a usual night). No study validates a body-battery formula;
every constant here is a design choice, written down in spec/02 §2.12.

The simulation starts 48 hours before the day at 50, so two nights of sleep wash the guess
out, and says nothing until it has seen a sleep. It runs at read time and stores nothing.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from uuid import UUID

from strap_server.derive._common import Cur, _age, _date_of_birth
from strap_server.derive.cardio_load import _measured_rhr
from strap_server.derive.hr_validity import HR_VALID_BOUNDS, HR_VALID_SQL
from strap_server.derive.robust import median
from strap_server.derive.trimp import hr_reserve_fraction, trimp_minute
from strap_server.zones import ZoneLike, as_zones

START_VALUE = 50.0
WARMUP = timedelta(hours=48)
LIVING = 0.04  # per awake minute with data, ≈ 2.4 an hour
SLEEP_RATE = 0.006  # share of the room left (100 − charge) regained per sleeping minute
STAGE_WEIGHT = {5: 1.0, 4: 0.8, 8: 0.65, 7: 0.1}  # deep, light, REM, awake inside sleep; other codes count as light
GAP_STAGE = 0x80
EXERCISE_HRR = 0.30
ACTIVITY_HRR = 0.20
ACTIVITY_STEPS = 60
TRIMP_DRAIN = 0.32
AFTERBURN_DECAY = 0.966  # the after-exercise tally halves in ~20 minutes
AFTERBURN_MIN = 5.0
STRESS_RUN = 3  # minutes at or over the threshold before stress drains
STRESS_DRAIN = 0.007
STRESS_MAX_AGE = 10  # minutes a stress reading speaks for
RELAX_STRESS = 25
RELAX_STEPS = 10
RELAX_GAIN = 0.25 * SLEEP_RATE * 0.8
DEFAULT_THRESHOLD = 60.0  # where Zepp's "medium" stress band begins
THRESHOLD_DAYS = 14
THRESHOLD_MIN_DAYS = 3
THRESHOLD_MIN_READINGS = 60
CURVE_STEP = timedelta(minutes=10)

WITHHOLD_MESSAGES: dict[str, str] = {
    "charge_needs_profile": "Charge needs your date of birth and a measured resting heart rate.",
    "charge_no_sleep": "Charge starts after the strap has recorded a sleep.",
    "charge_no_data": "The strap recorded nothing this day.",
}


@dataclass
class Minute:
    """One minute's inputs. ``stage`` is the strap's stage code when asleep, else None."""

    hr: float | None = None
    stress: float | None = None
    steps: float = 0.0
    stage: int | None = None
    night_rhr: float | None = None
    hrv_factor: float = 1.0


@dataclass
class Run:
    """The simulation's output: the value after each minute and how each minute moved it."""

    values: list[float] = field(default_factory=list)
    states: list[str] = field(default_factory=list)
    recharged: list[float] = field(default_factory=list)
    drained: list[dict[str, float]] = field(default_factory=list)
    slept: list[bool] = field(default_factory=list)


def hrv_factor(hrv_sub: float | None) -> float:
    """0.75 + 0.25 × (the recovery score's HRV part ÷ 50), kept in 0.6-1.1; 1 without one."""
    if hrv_sub is None:
        return 1.0
    return max(0.6, min(1.1, 0.75 + 0.25 * hrv_sub / 50.0))


def personal_threshold(calm_by_day: dict[date, list[float]]) -> tuple[float, bool]:
    """Median calm stress reading + 10, kept in 50-70; the default 60 under 3 days or 60 readings."""
    readings = [v for vs in calm_by_day.values() for v in vs]
    if len([d for d, vs in calm_by_day.items() if vs]) < THRESHOLD_MIN_DAYS or len(readings) < THRESHOLD_MIN_READINGS:
        return DEFAULT_THRESHOLD, False
    return max(50.0, min(70.0, median(readings) + 10.0)), True


def simulate(minutes: list[Minute], rhr: float, hrmax: float, sex: str | None, threshold: float) -> Run:
    """Every minute gets one state, checked in order: sleep, no data, exercise, exercise
    recovery, activity, stress, relax, rest. Charge stays in 0-100."""
    run = Run()
    charge = START_VALUE
    afterburn = 0.0
    stress_run = 0
    prev_hr: float | None = None
    slept = False
    for m in minutes:
        drain: dict[str, float] = {}
        gain = 0.0
        exercising = False
        if m.stage is not None:
            slept = True
            stress_run = 0
            relax = 1.0
            if m.hr is not None and m.night_rhr is not None:
                relax = max(0.5, min(1.2, 1.2 - 0.04 * (m.hr - m.night_rhr)))
            gain = SLEEP_RATE * STAGE_WEIGHT.get(m.stage, 0.8) * relax * m.hrv_factor * (100.0 - charge)
            state = "sleep"
        elif m.hr is None:
            stress_run = 0
            state = "no_data"
        else:
            hrr = hr_reserve_fraction(m.hr, rhr, hrmax)
            load = trimp_minute(m.hr, rhr, hrmax, sex)
            stressed = m.stress is not None and m.stress >= threshold
            stress_run = stress_run + 1 if stressed else 0
            if hrr >= EXERCISE_HRR:
                state, exercising = "exercise", True
                afterburn += load
                drain = {"living": LIVING, "activity": TRIMP_DRAIN * load}
            elif afterburn > AFTERBURN_MIN and prev_hr is not None and m.hr <= prev_hr + 2:
                state = "exercise_recovery"
                drain = {"living": LIVING}
            elif hrr >= ACTIVITY_HRR or m.steps >= ACTIVITY_STEPS:
                state = "activity"
                drain = {"living": LIVING, "activity": TRIMP_DRAIN * load}
            elif stress_run >= STRESS_RUN:
                state = "stress"
                drain = {"living": LIVING, "stress": STRESS_DRAIN * (m.stress - threshold)}
            elif m.stress is not None and m.stress < RELAX_STRESS and m.steps < RELAX_STEPS:
                state = "relax"
                drain = {"living": LIVING}
                gain = RELAX_GAIN * (100.0 - charge)
            else:
                state = "rest"
                drain = {"living": LIVING}
        if not exercising:
            afterburn *= AFTERBURN_DECAY
        if m.hr is not None:
            prev_hr = m.hr
        before = charge
        charge = max(0.0, min(100.0, charge + gain - sum(drain.values())))
        delta = charge - before
        total_drain = sum(drain.values())
        run.values.append(charge)
        run.states.append(state)
        run.recharged.append(max(delta, 0.0))
        run.drained.append({k: -delta * v / total_drain for k, v in drain.items()} if delta < 0 and total_drain > 0 else {})
        run.slept.append(slept)
    return run


# ── loading the minutes ─────────────────────────────────────────────────────────


def _floor_minute(t: datetime) -> datetime:
    return t.replace(second=0, microsecond=0)


def _sex(cur: Cur, user_id: UUID) -> str | None:
    cur.execute("SELECT sex FROM profile WHERE user_id = %s", (user_id,))
    r = cur.fetchone()
    return r[0] if r else None


def _per_minute(cur: Cur, user_id: UUID, metric: str, start: datetime, end: datetime, agg: str, extra: str = "", params: tuple = ()) -> dict[datetime, float]:
    if agg not in ("AVG", "SUM"):
        raise ValueError(agg)
    cur.execute(
        f"SELECT date_trunc('minute', ts) m, {agg}(value) FROM sample WHERE user_id = %s AND metric = %s {extra} "
        "AND ts >= %s AND ts < %s GROUP BY m",
        (user_id, metric, *params, start, end),
    )
    return {m: float(v) for m, v in cur.fetchall() if v is not None}


def _stages(cur: Cur, user_id: UUID, start: datetime, end: datetime) -> list[tuple[datetime, datetime, int, datetime]]:
    """Every stage overlapping [start, end): (stage start, stage end, code, its session's end)."""
    cur.execute(
        "SELECT end_ts, stages FROM sleep_session WHERE user_id = %s AND end_ts > %s AND start_ts < %s",
        (user_id, start, end),
    )
    out = []
    for session_end, stages in cur.fetchall():
        rows = stages if isinstance(stages, list) else json.loads(stages or "[]")
        for s, e, code in rows:
            if code != GAP_STAGE:  # as the sleep science: any stage but the gap marker is in sleep
                out.append((datetime.fromtimestamp(s / 1000, UTC), datetime.fromtimestamp(e / 1000, UTC), int(code), session_end))
    return out


def _night_inputs(cur: Cur, user_id: UUID, first: date, last: date) -> tuple[dict[date, float], dict[date, float]]:
    """Per wake date: the night's ``rhr_daily`` and the recovery score's HRV part."""
    cur.execute(
        "SELECT day, metric, value, flags FROM derived_daily WHERE user_id = %s AND day >= %s AND day <= %s "
        "AND metric IN ('rhr_daily', 'recovery_score')",
        (user_id, first, last),
    )
    rhr: dict[date, float] = {}
    hrv: dict[date, float] = {}
    for d, metric, value, flags in cur.fetchall():
        if metric == "rhr_daily":
            rhr[d] = float(value)
        else:
            sub = ((flags or {}).get("factors") or {}).get("hrv", {}).get("sub")
            if sub is not None:
                hrv[d] = float(sub)
    return rhr, hrv


def _calm_readings(cur: Cur, user_id: UUID, tz: ZoneLike, end: datetime, rhr: float, hrmax: float) -> dict[date, list[float]]:
    """Stress readings in the 14 days before `end` taken awake, still (< 10 steps) and at HRR < 20 %."""
    start = end - timedelta(days=THRESHOLD_DAYS)
    hr = _per_minute(cur, user_id, "hr", start, end, "AVG", f"AND {HR_VALID_SQL}", HR_VALID_BOUNDS)
    steps = _per_minute(cur, user_id, "steps_per_minute", start, end, "SUM")
    stress = _per_minute(cur, user_id, "stress", start, end, "AVG")
    asleep = _asleep_minutes(_stages(cur, user_id, start, end))
    zones = as_zones(tz)
    out: dict[date, list[float]] = {}
    for m, v in stress.items():
        h = hr.get(m)
        if m in asleep or h is None or steps.get(m, 0.0) >= RELAX_STEPS or hr_reserve_fraction(h, rhr, hrmax) >= ACTIVITY_HRR:
            continue
        out.setdefault(zones.date_of(m), []).append(v)
    return out


def _asleep_minutes(stages: list[tuple[datetime, datetime, int, datetime]]) -> dict[datetime, tuple[int, datetime]]:
    out: dict[datetime, tuple[int, datetime]] = {}
    for s, e, code, session_end in stages:
        m = _floor_minute(s)
        while m < e:
            out[m] = (code, session_end)
            m += timedelta(minutes=1)
    return out


def charge_card(cur: Cur, user_id: UUID, tz: ZoneLike, day: date, now: datetime) -> dict:
    """The day's Charge: now (or at the day's end), at wake, high, low, what refilled and what
    drained it, the curve in 10-minute steps, and the stress threshold used. Withheld with a
    reason when it cannot be computed."""
    zones = as_zones(tz)
    day_start, day_end = zones.bounds(day)
    end = _floor_minute(min(day_end, now))
    if end <= day_start:
        return _withheld("charge_no_data")
    dob = _date_of_birth(cur, user_id)
    rhr = _measured_rhr(cur, user_id, day)
    if dob is None or rhr is None:
        return _withheld("charge_needs_profile")
    hrmax = 208 - 0.7 * _age(dob, day)  # Tanaka 2001, as cardio load
    if hrmax - rhr < 1:
        return _withheld("charge_needs_profile")
    sex = _sex(cur, user_id)
    start = day_start - WARMUP
    hr = _per_minute(cur, user_id, "hr", start, end, "AVG", f"AND {HR_VALID_SQL}", HR_VALID_BOUNDS)
    steps = _per_minute(cur, user_id, "steps_per_minute", start, end, "SUM", "AND value > 0 AND value < %s", (250,))
    stress_points = sorted(_per_minute(cur, user_id, "stress", start - timedelta(minutes=STRESS_MAX_AGE), end, "AVG").items())
    asleep = _asleep_minutes(_stages(cur, user_id, start, end))
    night_rhr, hrv_sub = _night_inputs(cur, user_id, zones.date_of(start), day)
    threshold, personal = personal_threshold(_calm_readings(cur, user_id, tz, day_start, rhr, hrmax))

    minutes: list[Minute] = []
    stamps: list[datetime] = []
    si = 0
    last_stress: tuple[datetime, float] | None = None
    m = start
    while m < end:
        while si < len(stress_points) and stress_points[si][0] <= m:
            last_stress = stress_points[si]
            si += 1
        stress = last_stress[1] if last_stress and m - last_stress[0] <= timedelta(minutes=STRESS_MAX_AGE) else None
        stage, session_end = asleep.get(m, (None, None))
        wake = zones.date_of(session_end) if session_end else None
        minutes.append(Minute(
            hr=hr.get(m), stress=stress, steps=steps.get(m, 0.0), stage=stage,
            night_rhr=night_rhr.get(wake) if wake else None, hrv_factor=hrv_factor(hrv_sub.get(wake)) if wake else 1.0,
        ))
        stamps.append(m)
        m += timedelta(minutes=1)

    run = simulate(minutes, rhr, hrmax, sex, threshold)
    first = next(i for i, t in enumerate(stamps) if t >= day_start)
    measured = [i for i in range(first, len(stamps)) if run.states[i] != "no_data"]
    if not measured:
        return _withheld("charge_no_data")
    last = measured[-1]
    if not run.slept[last]:
        return _withheld("charge_no_sleep")
    in_day = [i for i in measured if run.slept[i]]
    wake_i = _wake_index(stamps, run.states, first, last)
    drained: dict[str, float] = {"activity": 0.0, "stress": 0.0, "living": 0.0}
    for i in range(first, last + 1):
        for k, v in run.drained[i].items():
            drained[k] += v
    curve = [[_ms(stamps[i]), round(run.values[i], 1)] for i in range(first, last + 1)
             if (stamps[i] - day_start) % CURVE_STEP == timedelta(0) or i == last]
    return {
        "value": round(run.values[last]),
        "at": _ms(stamps[last] + timedelta(minutes=1)),
        "at_wake": round(run.values[wake_i]) if wake_i is not None else None,
        "high": round(max(run.values[i] for i in in_day)),
        "low": round(min(run.values[i] for i in in_day)),
        "recharged": round(sum(run.recharged[first:last + 1]), 1),
        "drained": {k: round(v, 1) for k, v in drained.items()},
        "threshold": {"stress": round(threshold), "personal": personal},
        "curve": curve,
        "note_ids": ["charge_body_battery"],
    }


def _wake_index(stamps: list[datetime], states: list[str], first: int, last: int) -> int | None:
    """The last sleeping minute of the day's longest sleep run that ended inside it."""
    best: tuple[int, int] | None = None  # (length, end index)
    run_start: int | None = None
    for i in range(max(first - 1, 0), last + 1):
        if states[i] == "sleep":
            run_start = i if run_start is None else run_start
        elif run_start is not None:
            if i - 1 >= first and (best is None or i - run_start > best[0]):
                best = (i - run_start, i - 1)
            run_start = None
    return best[1] if best else None


def _ms(t: datetime) -> int:
    return int(t.timestamp() * 1000)


def _withheld(reason: str) -> dict:
    return {"withheld": {"reason": reason, "message": WITHHOLD_MESSAGES[reason]}}
