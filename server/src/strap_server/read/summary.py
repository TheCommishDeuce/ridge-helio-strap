"""The day's cards: recovery, strain, sleep, steps, stress, heart rate, VO2max, illness.

Every card is either a value with its context (baseline, z, the flags the derivation
stored) or `withheld` with a reason id and one sentence — never a silent null, never an
older day's number presented as this day's (the freshness gates are the science's own).

Two formulas are read-time and ported verbatim from healthee@049c9ad: strain
(`read/fitness.strain_from_load`) and live readiness (`read/recovery.decayed_readiness`).
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Cursor

from strap_server.derive import freshness, sleep_score, vo2max
from strap_server.derive._common import _day_bounds_utc
from strap_server.derive.hr_validity import HR_VALID_BOUNDS, HR_VALID_SQL
from strap_server.derive.robust import MAD_TO_SD, median, median_abs_deviation

BASELINE_DAYS = 30
BASELINE_MIN_POINTS = 5
ILLNESS_ACTIVE_DAYS = 2  # a flag speaks for two days past its date (old read/health_metrics)

_MESSAGES: dict[str, str] = {
    freshness.NOT_DERIVED_YET: freshness.NOT_DERIVED_YET_MESSAGE,
    "no_data": "The strap recorded nothing for this yet.",
    **vo2max.WITHHOLD_MESSAGES,
    **sleep_score.SRI_MESSAGES,
    **sleep_score.SLEEP_DEBT_MESSAGES,
}


def withheld(reason: str) -> dict:
    return {"withheld": {"reason": reason, "message": _MESSAGES.get(reason, reason)}}


# ── verbatim read-time science ─────────────────────────────────────────────────


def strain_from_load(load: float, p95: float | None) -> float | None:
    """Strain 0-21: the same TRIMP load on a personal log scale — 0 load -> 0, the 90-day
    P95 ("a hard day") -> 21, mildly concave. [[training_stress_score]]."""
    if not (p95 and p95 > 0):
        return None
    return round(max(0.0, min(21.0, 21.0 * (load / p95) ** 0.75)), 1)


def decayed_readiness(recovery: int, strain_today: float, typical: float) -> int:
    """Live readiness = recovery x (1 - decay); decay = 0.5 * min(1, strain/typical), capped
    at -50%. Conservative, no validated intraday formula exists."""
    decay = 0.5 * min(1.0, strain_today / typical) if typical > 0 else 0.0
    return round(recovery * (1 - decay))


# ── helpers ───────────────────────────────────────────────────────────────────


def _row(cur: Cursor, user_id: UUID, day: date, metric: str) -> tuple[float, dict] | None:
    cur.execute("SELECT value, flags FROM derived_daily WHERE user_id = %s AND day = %s AND metric = %s", (user_id, day, metric))
    r = cur.fetchone()
    return (float(r[0]), r[1]) if r else None


def _history(cur: Cursor, user_id: UUID, day: date, metric: str, days: int) -> list[float]:
    cur.execute(
        "SELECT value FROM derived_daily WHERE user_id = %s AND metric = %s AND day < %s AND day >= %s",
        (user_id, metric, day, day - timedelta(days=days)),
    )
    return [float(r[0]) for r in cur.fetchall()]


def _baseline(cur: Cursor, user_id: UUID, day: date, metric: str, value: float) -> dict | None:
    """Median and robust z over the 30 days before `day`; None under 5 points or a flat history."""
    hist = _history(cur, user_id, day, metric, BASELINE_DAYS)
    if len(hist) < BASELINE_MIN_POINTS:
        return None
    med = median(hist)
    sd = median_abs_deviation(hist) * MAD_TO_SD
    return {"median": round(med, 1), "n": len(hist), "z": round((value - med) / sd, 2) if sd > 0 else None}


def _metric_card(cur: Cursor, user_id: UUID, day: date, metric: str, digits: int = 0) -> dict:
    row = _row(cur, user_id, day, metric)
    if row is None:
        return withheld(freshness.NOT_DERIVED_YET)
    value, flags = row
    return {"value": round(value, digits) if digits else round(value), "flags": flags, "baseline": _baseline(cur, user_id, day, metric, value)}


def _raw_stats(cur: Cursor, user_id: UUID, tz: str, day: date, metric: str, extra: str = "", params: tuple = ()) -> dict | None:
    start, end = _day_bounds_utc(day, tz)
    cur.execute(
        f"SELECT min(value), max(value), avg(value), count(*), last(ts, value), first(ts, value) FROM sample "
        f"WHERE user_id = %s AND metric = %s {extra} AND ts >= %s AND ts < %s",
        (user_id, metric, *params, start, end),
    )
    mn, mx, avg, n, t_max, t_min = cur.fetchone()
    if not n:
        return None
    return {"min": mn, "max": mx, "mean": round(avg, 1), "n": n, "t_max": int(t_max.timestamp() * 1000), "t_min": int(t_min.timestamp() * 1000)}


def _local_day(tz: str) -> str:
    return "(ts AT TIME ZONE '" + tz.replace("'", "''") + "')"


def _usual_mean(
    cur: Cursor, user_id: UUID, tz: str, day: date, metric: str, until: time | None, extra: str = "", params: tuple = ()
) -> dict | None:
    """Median, over the 30 days before `day`, of each day's mean up to the same local clock
    time (`until`; the whole day when None) — a day still running is compared with the
    same hours of other days, never with whole days. None under 5 such days."""
    start, _ = _day_bounds_utc(day - timedelta(days=BASELINE_DAYS), tz)
    end, _ = _day_bounds_utc(day, tz)
    local = _local_day(tz)
    clock = f"AND {local}::time < %s" if until else ""
    cur.execute(
        f"SELECT avg(value) FROM sample WHERE user_id = %s AND metric = %s {extra} AND ts >= %s AND ts < %s {clock} "
        f"GROUP BY {local}::date",
        (user_id, metric, *params, start, end, *((until,) if until else ())),
    )
    means = [float(r[0]) for r in cur.fetchall()]
    if len(means) < BASELINE_MIN_POINTS:
        return None
    return {"median": round(median(means), 1), "n": len(means), "until": until.strftime("%H:%M") if until else None}


def steps_usual_by(cur: Cursor, user_id: UUID, tz: str, day: date, until: time) -> dict | None:
    """Steps usually walked by `until`: for each of the 30 days before `day`, that day's
    ``steps_total`` times the share of its per-minute steps taken before `until`; the median.

    The per-minute stream only supplies the day's SHAPE (a unitless share); the count is
    always the served ``steps_total``, so the stream's stalls shrink both halves of the share
    alike instead of undercounting the reference. None under 5 such days."""
    start, _ = _day_bounds_utc(day - timedelta(days=BASELINE_DAYS), tz)
    end, _ = _day_bounds_utc(day, tz)
    local = _local_day(tz)
    cur.execute(
        f"SELECT {local}::date, sum(value) FILTER (WHERE {local}::time < %s), sum(value) FROM sample "
        "WHERE user_id = %s AND metric = 'steps_per_minute' AND value > 0 AND value < 250 AND ts >= %s AND ts < %s "
        f"GROUP BY {local}::date",
        (until, user_id, start, end),
    )
    shares = {d: float(before or 0) / float(total) for d, before, total in cur.fetchall() if total}
    cur.execute(
        "SELECT day, value FROM derived_daily WHERE user_id = %s AND metric = 'steps_total' AND day < %s AND day >= %s",
        (user_id, day, day - timedelta(days=BASELINE_DAYS)),
    )
    usual = [float(v) * shares[d] for d, v in cur.fetchall() if d in shares]
    if len(usual) < BASELINE_MIN_POINTS:
        return None
    return {"median": round(median(usual)), "n": len(usual), "until": until.strftime("%H:%M")}


# ── cards ─────────────────────────────────────────────────────────────────────


def recovery_card(cur: Cursor, user_id: UUID, day: date, today: date) -> dict:
    row = _row(cur, user_id, day, "recovery_score")
    if row is None:
        return withheld(freshness.NOT_DERIVED_YET)
    score, flags = int(row[0]), row[1]
    card: dict = {"value": score, "flags": flags, "readiness": None}
    load = _row(cur, user_id, day, "cardio_load")
    hist = _history(cur, user_id, day, "cardio_load", 30)
    if day == today and load and len(hist) >= 5:  # only the reference day's own recovery decays
        typical = median(hist) or 1.0
        card["readiness"] = {"value": decayed_readiness(score, load[0], typical), "load": round(load[0], 1), "typical": round(typical, 1)}
    return card


def strain_card(cur: Cursor, user_id: UUID, day: date) -> dict:
    row = _row(cur, user_id, day, "cardio_load")
    if row is None:
        return withheld(freshness.NOT_DERIVED_YET)
    cur.execute(
        "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY value) FROM derived_daily "
        "WHERE user_id = %s AND metric = 'cardio_load' AND value > 0 AND day >= %s AND day <= %s",
        (user_id, day - timedelta(days=90), day),
    )
    p95 = cur.fetchone()[0]
    return {"value": strain_from_load(row[0], float(p95) if p95 else None), "max": 21.0, "cardio_load": round(row[0], 1), "flags": row[1]}


def sleep_card(cur: Cursor, user_id: UUID, tz: str, day: date, today: date) -> dict:
    start, end = _day_bounds_utc(day, tz)
    cur.execute(
        "SELECT start_ts, end_ts, kind, rem_min, light_min, deep_min, wake_min, stages, score FROM sleep_session "
        "WHERE user_id = %s AND end_ts >= %s AND end_ts < %s ORDER BY start_ts",
        (user_id, start, end),
    )
    sessions = [
        # device_score: the strap's own 0-100 sleep score, shown as the strap's — we compute no
        # composite ourselves ([[no_validated_sleep_score]]); 0 means the strap gave none (naps).
        {"start": int(s.timestamp() * 1000), "end": int(e.timestamp() * 1000), "kind": k,
         "minutes": {"rem": r, "light": li, "deep": d, "awake": w}, "stages": st, "device_score": sc or None}
        for s, e, k, r, li, d, w, st, sc in cur.fetchall()
    ]
    score = _row(cur, user_id, day, "sleep_health_score_4dim")
    debt = _row(cur, user_id, day, "sleep_debt_min")
    need = _row(cur, user_id, day, "sleep_need_min")
    sri = _row(cur, user_id, day, "sleep_regularity_index")
    last_sri = _last_day(cur, user_id, day, "sleep_regularity_index")
    return {
        "sessions": sessions,
        "health": {"dimensions": score[0], "flags": score[1]} if score else withheld(freshness.NOT_DERIVED_YET),
        "need_min": need[0] if need else None,  # NSF 2015 by age; null without a date of birth
        "debt": {"minutes": debt[0], "flags": debt[1]} if debt else withheld(
            sleep_score.sleep_debt_unavailable_reason(cur, user_id, tz, day, None) or freshness.NOT_DERIVED_YET
        ),
        "regularity": {"sri": sri[0]} if sri else withheld(
            sleep_score.sri_unavailable_reason(cur, user_id, tz, day, last_sri) or freshness.NOT_DERIVED_YET
        ),
        "note_ids": ["no_validated_sleep_score", "wearable_sleep_stage_validity"],
    }


def _last_day(cur: Cursor, user_id: UUID, day: date, metric: str) -> date | None:
    cur.execute("SELECT max(day) FROM derived_daily WHERE user_id = %s AND metric = %s AND day <= %s", (user_id, metric, day))
    return cur.fetchone()[0]


def vo2max_card(cur: Cursor, user_id: UUID, tz: str, day: date) -> dict:
    last = _last_day(cur, user_id, day, "vo2max_estimate")
    reason = freshness.unavailable_reason(day, last, lambda: vo2max.withhold_reason_for_day(cur, user_id, tz, day))
    if reason is not None:
        return withheld(reason)
    card = _metric_card(cur, user_id, day, "vo2max_estimate", digits=1)
    return {**card, "note_ids": ["non_exercise_vo2max"]}


def illness_framing(rr_delta: float | None, temp_delta: float | None, sustained: bool) -> str:
    """Deterministic early-signal sentence (verbatim from healthee@049c9ad read/health_metrics).
    Names the 14-day baseline: [[respiratory_rate_normal]] Directive 1 forbids a
    "+X above baseline" without saying which baseline."""
    parts: list[str] = []
    if rr_delta is not None:
        parts.append(f"breathing rate +{rr_delta:.1f} bpm vs your 14-day baseline")
    if temp_delta is not None:
        parts.append(f"skin temperature +{temp_delta:.2f}°C")
    joined = "; ".join(parts)
    suffix = " — sustained across two nights, the Smarr 2020 / Quer 2021 pattern" if sustained else ""
    return f"Possible early signal — consider lighter activity today. {joined[:1].upper() + joined[1:]}{suffix}. Not a diagnosis."


def illness_card(cur: Cursor, user_id: UUID, day: date) -> dict | None:
    """The newest flag still active on `day`, or None. A safety input, not a diagnosis."""
    cur.execute(
        "SELECT date, severity, sustained, rr_delta_bpm, temp_delta_c, research_note_ids FROM illness_flag "
        "WHERE user_id = %s AND date <= %s AND date >= %s ORDER BY date DESC LIMIT 1",
        (user_id, day, day - timedelta(days=ILLNESS_ACTIVE_DAYS)),
    )
    r = cur.fetchone()
    if r is None:
        return None
    return {
        "date": r[0].isoformat(), "severity": r[1], "sustained": r[2], "rr_delta_bpm": r[3], "temp_delta_c": r[4],
        "note_ids": r[5], "framing": illness_framing(r[3], r[4], r[2]),
    }


def day_summary(cur: Cursor, user_id: UUID, tz: str, day: date) -> dict:
    now = datetime.now(ZoneInfo(tz))
    today = now.date()
    until = now.time().replace(second=0, microsecond=0) if day == today else None
    hr = _raw_stats(cur, user_id, tz, day, "hr", f"AND {HR_VALID_SQL}", HR_VALID_BOUNDS)
    if hr:
        hr["usual"] = _usual_mean(cur, user_id, tz, day, "hr", until, f"AND {HR_VALID_SQL}", HR_VALID_BOUNDS)
    stress = _raw_stats(cur, user_id, tz, day, "stress")
    if stress:
        stress["usual"] = _usual_mean(cur, user_id, tz, day, "stress", until)
    steps = _metric_card(cur, user_id, day, "steps_total")
    if until and "value" in steps:
        steps["usual_by_now"] = steps_usual_by(cur, user_id, tz, day, until)
    return {
        "date": day.isoformat(),
        "timezone": tz,
        "recovery": recovery_card(cur, user_id, day, today),
        "strain": strain_card(cur, user_id, day),
        "sleep": sleep_card(cur, user_id, tz, day, today),
        "steps": {
            "steps": steps,
            "distance_m": _metric_card(cur, user_id, day, "distance_m_daily"),
            "active_calories": _metric_card(cur, user_id, day, "active_calories"),
            "total_calories": _metric_card(cur, user_id, day, "total_calories"),
            "mvpa_min": _metric_card(cur, user_id, day, "mvpa_min"),
        },
        "heart": {
            "resting": _metric_card(cur, user_id, day, "rhr_daily"),
            "hrv": _metric_card(cur, user_id, day, "hrv_sleep_avg", digits=1),
            "today": hr or withheld("no_data"),
        },
        "stress": {**(stress or withheld("no_data")), "note_ids": ["wearable_stress_validity"]},
        "vo2max": vo2max_card(cur, user_id, tz, day),
        "illness": illness_card(cur, user_id, day),
    }
