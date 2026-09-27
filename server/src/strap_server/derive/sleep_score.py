"""Sleep regularity, the 4-dimension sleep-health score, and sleep need/debt.

All three port the legacy v2 methodology verbatim — only the plumbing changed.
Knowledge notes: ``sleep_regularity_index`` (SRI; Phillips 2017, Windred 2024),
``sleep_score_implementation_plan`` + ``sleep_health_score_multidim`` +
``no_validated_sleep_score`` (the 4-dim RU-SATED-style binary sum, shown with its
dimensions and never as a single validated "score"), ``sleep_need_debt`` (age
sleep need + rolling cumulative debt with partial recovery credit).
"""

from __future__ import annotations

from collections import defaultdict
from datetime import UTC, date, datetime, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo

from strap_server.derive._common import Cur, _age, _date_of_birth, _upsert_daily
from strap_server.derive.freshness import (
    DOB_MISSING,
    NO_NIGHTS_IN_WINDOW,
    NOT_DERIVED_YET,
    unavailable_reason,
)

# ── 4-dimension sleep score cutoffs — sleep_score_implementation_plan ─────────
# NSF 2015 (Hirshkowitz et al., Sleep Health 1(1):40-43) — the recommended range for
# adults 18-64. RE-ATTRIBUTED 2026-08-01 (#88), value UNCHANGED: this said "Cappuccio
# 2010", which states no reference band at all (its reference category varied across
# the 27 pooled cohort samples). Two notes cited that one paper for two different
# bands, 7-8 and 7-9, and this constant shipped the second. The number stays 7.0/9.0
# because it is defensible on NSF's own terms and moving a scoring cutoff is a science
# behaviour change owed its own PR with known-value tests (CLAUDE.md); only the
# citation was wrong. Cappuccio still backs the U-SHAPE — see [[sleep_duration_mortality]].
SLEEP_DURATION_MIN_H, SLEEP_DURATION_MAX_H = 7.0, 9.0
SLEEP_EFFICIENCY_MIN = 0.85  # Schutte-Rodin 2008 (AASM guideline) — clinical consensus
SLEEP_TIMING_RANGE = (2, 4)  # Buysse 2014 (midpoint hour)
# DERIVED, not cited: Windred 2024's least-regular quintile is SRI < 71.6 and no paper
# states 70. See sleep_score_implementation_plan §Dimension 4 for what the rounding costs.
SRI_GOOD = 70.0
SRI_DAYS = 7  # Phillips 2017 minimum window

# ── Sleep need / debt — sleep_need_debt ──────────────────────────────────────
SLEEP_NEED_MIN_18_64 = 480  # NSF 2015 midpoint of 7-9 h
SLEEP_NEED_MIN_65P = 450  # midpoint of 7-8 h
SLEEP_DEBT_WINDOW = 14  # rolling nights
SLEEP_RECOVERY_CREDIT = 0.5  # surplus sleep repays debt at half value (partial recovery)

_AWAKE_STAGE = 7  # stage type code for "awake" in the hypnogram triples

# WHICH INSTRUMENT recorded the sleep session behind these rows (write-path audit C1).
#
# This was the legacy cloud's name — hardcoded on all six rows a night produces, and again
# at two sites in ``read/sleep_page.py``. A verbatim carry-over from legacy, where sleep
# genuinely did arrive from that cloud. **In the rebuild there is no such path**: the only
# writer of the ``sleep_session`` table is ``ingest.upsert.upsert_sleep``, fed by the strap
# over BLE, and ``tests/derive/test_session_source.py`` asserts that premise rather than
# trusting the grep that established it.
#
# On the product whose premise is that every number names its instrument, a provenance
# field naming an instrument that did not take the reading is the clearest possible
# version of the defect — and it is C rather than higher only because nothing renders the
# string today. That is not a reason to keep it wrong; it is the reason it survived.
#
# One constant, three sites, so the payload cannot say two things: ``MEMORY``'s
# `project_source_naming_cleanup`.
SESSION_SOURCE = "strap_ble"


def _sleep_efficiency(tst_min: int, wake_min: int) -> float:
    """Sleep efficiency from the sleep timeline: asleep / (asleep + awake-in-bed).

    Bounded to [0, 1] by construction (belt-and-suspenders clamp), so
    ``efficiency_pct`` can never exceed 100. Legacy divided TST by wall-clock TIB,
    which could report an impossible >100% when the staged asleep+wake minutes
    overshot the session span. [[no_validated_sleep_score]].
    """
    total = tst_min + wake_min
    return min(1.0, tst_min / total) if total > 0 else 0.0


def _sri_grid(cur: Cur, user_id: UUID, tz: str, night_date: date) -> dict[int, set[int]]:
    """The asleep-minute grid for the 7-day window ending on ``night_date``.

    Extracted from :func:`_compute_sri` so the WITHHOLD GATE and the computation read
    the identical window: :func:`sri_withhold_reason_for_day` asks only how many days
    the grid covers, and asking it any other way (say, counting sessions in SQL) would
    be a second definition of "a complete week" — sessions can span a local midnight,
    the grid is what decides which day a minute belongs to.
    """
    start_local = datetime(
        night_date.year, night_date.month, night_date.day, tzinfo=ZoneInfo(tz)
    ) - timedelta(days=SRI_DAYS - 1)
    end_local = start_local + timedelta(days=SRI_DAYS)
    cur.execute(
        "SELECT stages FROM sleep_session "
        "WHERE user_id = %s AND kind='main' AND end_ts>=%s AND start_ts<%s",
        (user_id, start_local.astimezone(UTC), end_local.astimezone(UTC)),
    )
    return _sri_minute_grid(cur.fetchall(), start_local.date(), tz)


def _compute_sri(cur: Cur, user_id: UUID, tz: str, night_date: date) -> float | None:
    """Sleep Regularity Index over the 7-day window ending on `night_date`.

    Built from main-sleep hypnogram stages (asleep = any non-awake stage): the
    percentage agreement, at one-minute resolution, that the person is in the same
    sleep/wake state 24 h apart, mapped to -100..100. None until 7 days are
    present. Phillips 2017 [[sleep_regularity_index]].
    """
    grid = _sri_grid(cur, user_id, tz, night_date)
    if len(grid) < SRI_DAYS:
        return None
    minutes_per_day, days = 1440, SRI_DAYS
    matches = 0
    for j in range(days - 1):
        matches += minutes_per_day - len(grid[j] ^ grid[j + 1])
    return round(-100.0 + (200.0 / (minutes_per_day * (days - 1))) * matches, 2)


def _sri_minute_grid(rows: list, start_date: date, tz: str) -> dict[int, set[int]]:
    """Map each day-index -> set of minute-of-day the person is asleep."""
    zone = ZoneInfo(tz)
    grid: dict[int, set[int]] = defaultdict(set)
    for (stages,) in rows:
        for st in stages or []:
            if st[2] == _AWAKE_STAGE:
                continue
            start = datetime.fromtimestamp(st[0] / 1000, tz=UTC)
            end = datetime.fromtimestamp(st[1] / 1000, tz=UTC)
            minute = start
            while minute < end:
                local = minute.astimezone(zone)
                day_index = (local.date() - start_date).days
                if 0 <= day_index < SRI_DAYS:
                    grid[day_index].add(local.hour * 60 + local.minute)
                minute += timedelta(minutes=1)
    return grid


# ── SRI freshness: an SRI is a claim about ONE 7-day window ──────────────────
#
# Directive 4 of [[sleep_regularity_index]] (confidence: high) — "Do not compute or
# report SRI from <7 days of data" — is enforced on the WRITE side by `_compute_sri`
# returning None, so a short week writes no row. Consumers then read "the newest
# `sleep_regularity_index` row" and spent it as the owner's CURRENT regularity: the
# biological-age regularity term and `/api/sleep/consistency` both did. A 90-day-old row
# is a perfectly valid SRI *of a week 90 days ago*; presenting it as now is the report
# half of the same directive being broken, and it is the more consequential half,
# because an SRI has no visible age.
#
# The reason is RECOMPUTED, not persisted (`derive/vo2max.py`'s posture, and for the
# same reason): the gate is a pure function of sleep sessions still in the database, so
# it needs no migration and works for rows written before this existed.
SRI_WINDOW_TOO_SHORT = "sri_window_under_7_nights"

SRI_MESSAGES = {
    SRI_WINDOW_TOO_SHORT: (
        "Sleep regularity needs seven consecutive nights of recorded sleep — wear the "
        "strap overnight until the week is complete and this comes back."
    ),
    NOT_DERIVED_YET: "Last night's regularity has not been computed yet — sync the strap.",
}


def sri_withhold_reason_for_day(cur: Cur, user_id: UUID, tz: str, day: date) -> str | None:
    """Why ``day`` cannot carry an SRI, or None when its 7-day window is complete.

    The same check :func:`_compute_sri` makes, over the same grid, without writing
    anything — the equivalence is pinned by ``tests/derive/test_sri_freshness.py`` so a
    gate added to one and not the other fails the build. [[sleep_regularity_index]].
    """
    if len(_sri_grid(cur, user_id, tz, day)) < SRI_DAYS:
        return SRI_WINDOW_TOO_SHORT
    return None


def sri_unavailable_reason(
    cur: Cur, user_id: UUID, tz: str, today: date, last_day: date | None
) -> str | None:
    """Why this owner has no SRI FOR TODAY, or ``None`` when ``last_day`` IS today.

    The consumer-facing question, bound to the gate above through the one shared rule
    (``derive/freshness.py``). Two consumers must not fork it: the biological-age
    regularity term (``analytics/biological_age.py``) and ``/api/sleep/consistency``
    (``read/sleep_extras.py``, which also backs the coach's ``sleep_consistency`` tool).
    """
    return unavailable_reason(
        today, last_day, lambda: sri_withhold_reason_for_day(cur, user_id, tz, today)
    )


def derive_sleep_score(
    cur: Cur,
    user_id: UUID,
    tz: str,
    start_ts: datetime,
    end_ts: datetime,
    rem: int,
    light: int,
    deep: int,
    wake: int,
    night_date: date,
) -> dict:
    """4-dimension sleep-health score (duration, efficiency, timing, regularity).

    Each dimension is a 0/1 point; the score is their sum (0-4). Written both as
    the composite and as per-dimension metrics (legacy reads the dimensions as
    separate 0/1 rows). Raw measurements ride along in `flags`. Never presented as
    a single validated score [[no_validated_sleep_score]].
    """
    tst = light + deep + rem
    tib = max(1, int((end_ts - start_ts).total_seconds() / 60))  # raw wall-clock span
    p_dur = 1 if SLEEP_DURATION_MIN_H <= tst / 60.0 <= SLEEP_DURATION_MAX_H else 0
    eff = _sleep_efficiency(tst, wake)  # <= 1 by construction (never >100%)
    p_eff = 1 if (tst > 0 and eff >= SLEEP_EFFICIENCY_MIN) else 0
    mid = (start_ts + (end_ts - start_ts) / 2).astimezone(ZoneInfo(tz))
    p_tim = 1 if SLEEP_TIMING_RANGE[0] <= mid.hour < SLEEP_TIMING_RANGE[1] else 0
    sri = _compute_sri(cur, user_id, tz, night_date)
    p_reg = 1 if (sri is not None and sri >= SRI_GOOD) else 0
    score = p_dur + p_eff + p_tim + p_reg
    flags = {
        "duration": p_dur,
        "efficiency": p_eff,
        "timing": p_tim,
        "regularity": p_reg,
        "tst_min": tst,
        "tib_min": tib,
        "efficiency_pct": round(eff * 100, 1),
        "midpoint_local": mid.isoformat(),
        "midpoint_hr": mid.hour,
        "sri": sri,
        "session_source": SESSION_SOURCE,
    }
    _upsert_daily(cur, user_id, night_date, "sleep_health_score_4dim", score, flags)
    _upsert_daily(cur, user_id, night_date, "sleep_dim_duration", p_dur, flags)
    _upsert_daily(cur, user_id, night_date, "sleep_dim_efficiency", p_eff, flags)
    _upsert_daily(cur, user_id, night_date, "sleep_dim_timing", p_tim, flags)
    _upsert_daily(cur, user_id, night_date, "sleep_dim_regularity", p_reg, flags)
    if sri is not None:
        _upsert_daily(cur, user_id, night_date, "sleep_regularity_index", sri, flags)
    return {"sleep_health_score_4dim": score, "sri": sri}


def _sleep_debt_stats(tsts: list[float], need: int) -> dict:
    """Cumulative debt + window stats from the recorded nights' TST. Pure.

    Extracted (unchanged) from ``derive_sleep_debt`` so that function stays inside
    the 40-line gate once the tenant owner is threaded through its writes. The math
    is verbatim — debt = shortfall - half the surplus, floored at 0, over recorded
    nights only [[sleep_need_debt]]; the parity fixtures pin it.
    """
    shortfall = sum(max(0.0, need - t) for t in tsts)
    surplus = sum(max(0.0, t - need) for t in tsts)
    avg_tst = sum(tsts) / len(tsts)
    return {
        "debt": max(0.0, shortfall - SLEEP_RECOVERY_CREDIT * surplus),
        "avg_tst": avg_tst,
        "avg_deficit": max(0.0, need - avg_tst),
        "nights_below": sum(1 for t in tsts if t < need),
    }


def _tst_window(cur: Cur, user_id: UUID, day: date) -> list[float]:
    """The recorded nights' TST in the rolling window ending on ``day``, oldest first.

    The ONE definition of "the nights this day's debt is computed over" — shared by the
    derivation and by :func:`sleep_debt_withhold_reason_for_day`, so the gate cannot
    disagree with the writer about whether a day had anything to compute.
    """
    cur.execute(
        "SELECT (flags->>'tst_min')::float FROM derived_daily "
        "WHERE user_id = %s AND metric='sleep_health_score_4dim' AND day<=%s AND day>%s "
        "AND flags ? 'tst_min' ORDER BY day",
        (user_id, day, day - timedelta(days=SLEEP_DEBT_WINDOW)),
    )
    return [float(t[0]) for t in cur.fetchall() if t[0] is not None]


# ── Sleep-debt freshness: a rolling 14-night window has a LAST night ─────────
#
# `sleep_debt_min` is a cumulative claim over the 14 nights ending on its `day`. Two
# weeks later that window and today's share no nights at all, so the stored number is
# not "the debt, slightly out of date" — it describes a different fortnight. The Today
# card presented `latest_derived('sleep_debt_min')` with no date key of any kind.
SLEEP_DEBT_MESSAGES = {
    # Date of birth ALONE. This used to name a logged weight too, because the gate below
    # ran the full `_load_profile`; the message was honest about the gate and the gate was
    # wrong. NSF 2015 selects the need band from age (audit C8).
    DOB_MISSING: (
        "We need your date of birth to set your age-based sleep need before a debt can be tracked."
    ),
    NO_NIGHTS_IN_WINDOW: (
        "No sleep has been recorded in the last two weeks, so there is no window to "
        "compute a debt over — wear the strap overnight and this comes back."
    ),
    NOT_DERIVED_YET: "Today's sleep debt has not been computed yet — sync the strap.",
}


def sleep_debt_withhold_reason_for_day(cur: Cur, user_id: UUID, day: date) -> str | None:
    """Why ``day`` has no sleep debt, or None when its inputs CAN carry one.

    The same two checks :func:`derive_sleep_debt` makes, in the same order, over the same
    window, without writing — the ``derive/vo2max.py`` posture, and pinned by the same
    kind of writer/reader equivalence test. [[sleep_need_debt]].

    Neither check reads a timezone, so neither does this (audit C8 removed the profile
    loader that did).
    """
    if _date_of_birth(cur, user_id) is None:
        return DOB_MISSING
    if not _tst_window(cur, user_id, day):
        return NO_NIGHTS_IN_WINDOW
    return None


def sleep_debt_unavailable_reason(
    cur: Cur,
    user_id: UUID,
    tz: str,
    today: date,
    last_day: date | None,
) -> str | None:
    """Why this owner has no sleep debt FOR TODAY, or ``None`` when ``last_day`` IS today.

    ``tz`` is unread and KEPT, which is the one place in this change it was right to keep
    an unused parameter: ``insights/context_withheld._ReasonOf`` types every withhold gate
    as one five-argument callable, and the other two gates in that registry do read a
    timezone. Dropping it here would mean special-casing this gate in the registry, a
    worse trade than an argument one implementation happens not to need. The two functions
    it delegates to HAVE dropped theirs, which is where it mattered — a dependency that is
    not a dependency is what audit C8 was about, and a type-level contract is a real one.
    """
    return unavailable_reason(
        today, last_day, lambda: sleep_debt_withhold_reason_for_day(cur, user_id, today)
    )


def derive_sleep_debt(cur: Cur, user_id: UUID, day: date) -> dict | None:
    """Age-based sleep need (NSF 2015) + rolling 14-night cumulative debt.

    Debt = shortfall minus half the surplus (partial recovery), over recorded
    nights only — no artificial cap, so a real chronic deficit shows in full.
    Reads TST from the sleep-score flags. None without a DATE OF BIRTH or any recorded
    night. [[sleep_need_debt]].

    The need is a function of ``dob`` and nothing else, so ``dob`` is the only profile
    field read: this ran ``_load_profile``, which additionally requires a height, a sex
    and a logged weight, and withheld the whole need-and-debt block for the want of a
    weight no line of the computation touches (audit C8). ``tz`` went with it — the
    profile loader was the only thing here that wanted a timezone, so keeping the
    parameter would have advertised a dependency this function no longer has.
    """
    dob = _date_of_birth(cur, user_id)
    if dob is None:
        return None
    age = _age(dob, day)
    need = SLEEP_NEED_MIN_65P if age >= 65 else SLEEP_NEED_MIN_18_64
    tsts = _tst_window(cur, user_id, day)
    if not tsts:
        return None
    stats = _sleep_debt_stats(tsts, need)
    debt, avg_deficit = stats["debt"], stats["avg_deficit"]
    _upsert_daily(
        cur, user_id, day, "sleep_need_min", float(need), {"basis": "NSF2015", "age": age}
    )
    _upsert_daily(
        cur,
        user_id,
        day,
        "sleep_debt_min",
        round(debt, 0),
        {
            "window_nights": SLEEP_DEBT_WINDOW,
            "nights": len(tsts),
            "avg_tst_min": round(stats["avg_tst"]),
            "avg_deficit_min": round(avg_deficit),
            "nights_below": stats["nights_below"],
            "recovery_credit": SLEEP_RECOVERY_CREDIT,
        },
    )
    return {
        "sleep_need_min": need,
        "sleep_debt_min": round(debt),
        "avg_deficit_min": round(avg_deficit),
    }
