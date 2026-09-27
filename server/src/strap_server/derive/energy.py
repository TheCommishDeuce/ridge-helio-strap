"""Daily energy expenditure (calories) — free-living, MET-by-state, BMR-anchored.

Total EE is built minute-by-minute from a MET assigned to each minute's state
(walking from steps via ACSM | asleep | awake-NEAT), anchored so 1 MET == BMR/min
(Mifflin-St Jeor). Heart rate is deliberately NOT used for free-living EE — without
raw accelerometry it can't separate awake-rest from activity and overcounts. Ported
verbatim from legacy v2. Knowledge: [[energy_expenditure_derivation]].

## The weight behind BMR is CAVEATED, not withheld (#127)

All three metrics rest on a Mifflin-St Jeor BMR, and its mass is the owner's last logged
weight — which can be arbitrarily old. Until #127 this module never asked how old, so a
calorie figure served today could rest on a mass measured months ago and say nothing
about it. That is the same stale-as-current class ``derive/vo2max.py`` and
``read/today_series.py`` already close for weight; the rule was simply not held here.

**The question is the shared one and the horizon is the shared constant** —
``freshness.weight_is_stale``, ``WEIGHT_MAX_AGE_DAYS``. It is the same quantity being
asked about (is this logged mass a statement about this day's body?), so a second horizon
would be a second definition of one metric's currency, which is the failure
``derive/freshness.py``'s docstring exists to prevent. #117's
``MEASURED_VO2MAX_MAX_AGE_DAYS`` is deliberately NOT this constant because it asks about
a different quantity — fitness, not mass. Here the quantity is mass.

**The ANSWER differs from ``vo2max.py``'s, and the difference is argued, not assumed.**
Three reasons, in the order that decided it:

1. *The tilt is bounded, and the bound is provable.* Total EE is
   ``k·BMR + workout_cal`` where ``k = Σ MET_minute / 1440`` and the workout term is the
   device's own measurement; both are weight-free. So for every one of the three metrics
   the RELATIVE error a wrong mass induces is at most
   :data:`MIFFLIN_KCAL_PER_KG_DAY` ``· Δkg / BMR`` — about **0.6% per kilogram** at a
   1639 kcal BMR, equal at ``basal_calories`` and strictly smaller wherever a workout
   contributes. It would take **~25 kg** of undetected drift to reach the ±15-20%
   individual error this estimate already advertises [Brage 2015,
   [[energy_expenditure_derivation]]]. There is no elapsed interval for which any
   evidence we hold predicts that. #117 set the shape of this test — drift against the
   instrument's own resolution — and applied here it never crosses.
2. *The tilt has no direction.* #117's tie-breaker was that detraining decay makes a held
   VO₂max read HIGH, i.e. flatter, which is the #108 failure. Body mass in free living
   has no such signed decay: 0.26 ± 1.2 kg over two weeks, a mean swamped by its own
   spread [Bhutani et al. 2017, [[weight_bmi_body_composition]]]. A stale weight is as
   likely to under-report this owner's burn as to over-report it.
3. *The corpus already ruled on exactly this, and asked for the disclosure it never got.*
   [[weight_bmi_body_composition]] Honesty says ``derive/energy.py`` is deliberately
   unchanged by #85 and why; its Coach Directive 6 says to name the weight's date
   "whenever weight is used to justify anything". No payload carried one, so the
   directive was unsatisfiable. That — not the arithmetic — is what #127 fixes.

So the weight's provenance ships on ALL THREE metrics unconditionally, and past the
horizon a ``caveats`` entry says which way the number leans. Nothing is withheld and no
number moves: withholding a whole day's calories over a lean this size would be a refusal
no evidence asked for, and "not enough data" only beats a guess when there is a guess.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from uuid import UUID

from strap_server.derive._common import Cur, _age, _day_minutes, _upsert_daily
from strap_server.derive.freshness import (
    WEIGHT_MAX_AGE_DAYS,
    WEIGHT_STALE,
    caveat_block,
    weight_age_days,
    weight_is_stale,
)

# Awake non-step NEAT — context-aware by step proximity instead of a flat value.
# A flat 1.4 overcounts long sedentary stretches (Compendium: sitting-quiet 1.3)
# AND undercounts time up-and-about between strides (standing/light 1.5-1.8). So
# each non-step waking minute is classified: near step activity (within
# +/-NEAT_WINDOW min) => up & moving ~1.55; isolated => seated/resting ~1.3.
#
# ⚠ Only ONE of the three is a research constant, and saying which is the point
# (standards section 1 asks every research constant to cite its note; it does not ask a
# product decision to invent one). `derive/hr_validity.py` is the reference for this
# shape of comment. [[energy_expenditure_derivation]] carries the same split.
AWAKE_SEDENTARY_MET = 1.3  # Compendium 07021 — sitting quietly. SOURCED.
AWAKE_ACTIVE_MET = 1.55  # standing / light household between steps.
# PRACTITIONER CHOICE, not a Compendium entry: it sits between standing-quiet (1.3) and
# light household activity (~1.8) because a minute between strides is neither. No paper
# is claimed and none should be invented.
NEAT_WINDOW = 7  # minutes either side to look for movement.
# PRODUCT DECISION with no literature behind it at all: how near a step has to be for a
# still minute to read as up-and-about. Moving either of these two is a science change
# and gets its own PR with the parity fixture re-baselined.
SLEEP_MET = 0.95  # sleep is ~0.9-0.95 x RMR

_WALK_RUN_SPEED_M_MIN = 134  # ACSM equation switch (m/min): walking vs running VO2

# Mifflin-St Jeor's weight coefficient: `BMR = 10*kg + ...`, so one kilogram of weight
# error is 10 kcal/day of BMR. Named rather than read off the equation because the caveat
# below QUOTES it — this is the number the caveat-not-withhold decision rests on, and a
# decision's evidence has to move when the code it describes does.
MIFFLIN_KCAL_PER_KG_DAY = 10.0

# The individual error this estimate already advertises [Brage et al. 2015, DLW;
# [[energy_expenditure_derivation]] Honesty: "expect TEE bias < 5% on average but
# individual error ~ +/-15-20%"]. The lower edge is the honest one to compare a tilt
# against, so the comparison is made at the model's BEST claimed individual accuracy.
_INDIVIDUAL_ERROR_PCT = 15.0


def _tee_met(
    cur: Cur, user_id: UUID, start_utc: datetime, end_utc: datetime, bmr: float, stride_m: float
) -> float:
    """Total EE for one day via state->MET, anchored to BMR (1 MET == BMR/min).

    Per minute: walking (ACSM, from steps) | asleep (0.95) | awake-NEAT
    (1.3/1.55). Workout minutes are excluded — counted via the device's measured
    calories by the caller.
    """
    bmr_min = bmr / 1440.0
    # `end_utc` is the START of the next local day, so every window closes with `<`:
    # anything landing exactly on it belongs to tomorrow (`_day_bounds_utc`).
    cur.execute(
        "SELECT start_ts, end_ts FROM sleep_session "
        "WHERE user_id = %s AND end_ts>=%s AND start_ts<%s",
        (user_id, start_utc, end_utc),
    )
    sleep_wins = cur.fetchall()
    cur.execute(
        "SELECT start_ts, duration_s FROM workout "
        "WHERE user_id = %s AND start_ts>=%s AND start_ts<%s",
        (user_id, start_utc, end_utc),
    )
    wk_wins = [(w[0], w[0] + timedelta(seconds=int(w[1] or 0))) for w in cur.fetchall()]
    cur.execute(
        "SELECT date_trunc('minute', ts) m, SUM(value) FROM sample "
        "WHERE user_id = %s AND metric='steps_per_minute' AND value < 250 "
        "AND ts>=%s AND ts<%s GROUP BY m",
        (user_id, start_utc, end_utc),
    )
    steps_by_min = {r[0]: float(r[1]) for r in cur.fetchall()}

    def _asleep(m: datetime) -> bool:
        return any(s <= m < e for s, e in sleep_wins)

    def _in_wk(m: datetime) -> bool:
        return any(s <= m < e for s, e in wk_wins)

    total = 0.0
    base = start_utc.replace(second=0, microsecond=0)
    # Length from the BOUNDS, never a hardcoded 1440: a local day is 23 h or 25 h on a
    # DST transition, and the walk is in UTC (which has no transitions), so the minute
    # count is the only thing that knows how long the day was. See `_day_minutes`.
    for i in range(_day_minutes(start_utc, end_utc)):
        m = base + timedelta(minutes=i)
        if _in_wk(m):
            continue
        total += _minute_met(m, steps_by_min, stride_m, _asleep) * bmr_min
    return total


def _minute_met(m: datetime, steps_by_min: dict, stride_m: float, is_asleep) -> float:
    """MET for one minute: walking (ACSM) | asleep | seated/active NEAT."""
    st = steps_by_min.get(m, 0.0)
    if st > 0:
        speed = st * stride_m  # m/min
        vo2 = (0.2 * speed + 3.5) if speed >= _WALK_RUN_SPEED_M_MIN else (0.1 * speed + 3.5)
        return vo2 / 3.5
    if is_asleep(m):
        return SLEEP_MET
    # awake, no steps this minute: seated unless step activity is nearby (then the
    # person is up & moving between strides).
    near = any(
        (m + timedelta(minutes=k)) in steps_by_min for k in range(-NEAT_WINDOW, NEAT_WINDOW + 1)
    )
    return AWAKE_ACTIVE_MET if near else AWAKE_SEDENTARY_MET


_WEIGHT_STALE_MESSAGE = (
    "These calories rest on the {kg} kg you logged on {as_of}, {days} days from the day "
    "they cover — we have no nearer weight, so the mass behind them is assumed rather than "
    "something you told us. Every kilogram you have changed since moves this by about "
    "{per_kg} kcal a day, at most {per_kg_pct}% of the number, against the plus-or-minus "
    "{err}% individual error this estimate already carries — which is why we show it "
    "rather than withhold it. Log a weight and it stops being an assumption."
)


# The reason id and sentence for a workout the strap logged with no calorie figure.
#
# `_tee_met` removes workout minutes from the MET walk "because they are counted via the
# device's measured calories by the caller" — and the caller summed a NULLABLE column, so
# a session with no calorie figure removed its minutes and added nothing back. A 60-minute
# run subtracted an hour of at-least-sedentary METs and contributed zero. The direction is
# conservative rather than flattering, which is the right side to be wrong on, but a wrong
# number served with `caveats: []` in a payload built with a caveat vocabulary for exactly
# this is silence, not modesty.
#
# A caveat rather than a model change: keeping those minutes in the MET walk would be a
# behaviour change to science code, which is its own PR with its own known-value tests
# (CLAUDE.md). This says what the number is missing; it does not bend the number.
UNCOUNTED_WORKOUT = "workout_without_device_calories"
_UNCOUNTED_WORKOUT_MESSAGE = (
    "{n} recorded {sessions} on this day ({minutes} min in total) came from the strap with "
    "no calorie figure. Workout minutes are counted from the strap's own measurement rather "
    "than from the movement model, so those minutes contributed nothing and this total is "
    "lower than the day actually was — by at least the resting energy of {minutes} minutes."
)


def uncounted_workout_caveats(sessions_n: int, minutes: int) -> list[dict]:
    """The ``caveats`` entry for workouts the strap logged without calories, or ``[]``.

    Not a date-bearing caveat: the disclosure is about THIS day's own sessions, so it uses
    the day for both edges of :func:`caveat_block` and reports an ``age_days`` of 0. The
    counts travel inside the block for the reason :func:`weight_caveats` gives — the size
    of the lean is why a caveat was the right branch.
    """
    if sessions_n <= 0:
        return []
    return [
        {
            "reason": UNCOUNTED_WORKOUT,
            "message": _UNCOUNTED_WORKOUT_MESSAGE.format(
                n=sessions_n,
                sessions="session" if sessions_n == 1 else "sessions",
                minutes=minutes,
            ),
            "sessions": sessions_n,
            "uncounted_minutes": minutes,
        }
    ]


def weight_tilt_pct_per_kg(bmr: float) -> float:
    """Largest relative error, in %, that one kilogram of wrong mass puts on a calorie.

    ``basal = BMR``; ``total = k·BMR + workout_cal``; ``active = (k−1)·BMR + workout_cal``,
    with ``k = Σ MET_minute / 1440 > 1`` and the workout term the device's own measurement.
    Neither ``k`` nor ``workout_cal`` sees the weight, so each metric's relative error is
    ``10·Δkg / (BMR + workout_cal/…)`` — maximised, for all three at once, when there are no
    workout calories. Hence ONE bound covers the whole family, and it is an upper bound
    rather than an estimate. Module docstring, reason 1.
    """
    return 100.0 * MIFFLIN_KCAL_PER_KG_DAY / bmr


def weight_caveats(prof: dict, day: date, bmr: float) -> list[dict]:
    """The ``caveats`` list for a day's calories: empty, or the stale-weight lean.

    A LIST rather than a single block, matching ``analytics/biological_age.py``'s payload
    vocabulary, so a second thing worth disclosing later does not change this shape.
    ``[]`` is the fresh-weight answer and it is written out rather than omitted — an
    absent key and "we checked and there is nothing to say" are different claims.

    Caveat, never withhold: see the module docstring for the three reasons and the
    arithmetic behind them. The bound travels inside the block because the decision rests
    on its size.
    """
    as_of = prof["weight_as_of"]
    if not weight_is_stale(as_of, day):
        return []
    per_kg_pct = weight_tilt_pct_per_kg(bmr)
    return [
        caveat_block(
            WEIGHT_STALE,
            _WEIGHT_STALE_MESSAGE.format(
                kg=round(float(prof["weight_kg"]), 1),
                as_of=as_of.isoformat(),
                days=weight_age_days(as_of, day),
                per_kg=round(MIFFLIN_KCAL_PER_KG_DAY),
                per_kg_pct=round(per_kg_pct, 2),
                err=round(_INDIVIDUAL_ERROR_PCT),
            ),
            day,
            as_of,
            horizon_days=WEIGHT_MAX_AGE_DAYS,
            kcal_per_day_per_kg=MIFFLIN_KCAL_PER_KG_DAY,
            max_percent_per_kg=round(per_kg_pct, 2),
        )
    ]


def weight_flags(prof: dict, day: date, bmr: float) -> dict:
    """Which weight produced this number, and how far it sits from the day it is offered as.

    On all three calorie metrics and on every day, fresh or stale. [[weight_bmi_body_composition]]
    Coach Directive 6 asks that a weight-justified number "name its date when it has one";
    a date that appears only once the gate fires would let the coach describe a 13-day-old
    mass as this morning's, which is the same conflation one day earlier.
    """
    as_of = prof["weight_as_of"]
    return {
        "weight_kg": float(prof["weight_kg"]),
        "weight_as_of": as_of.isoformat(),
        "weight_age_days": weight_age_days(as_of, day),
        "caveats": weight_caveats(prof, day, bmr),
    }


def derive_calories(
    cur: Cur,
    user_id: UUID,
    day: date,
    prof: dict,
    start_utc: datetime,
    end_utc: datetime,
    stride_m: float,
) -> dict:
    """Total / active / basal calories for one local day; upserts all three.

    BMR from Mifflin-St Jeor; TEE from the MET-by-state model plus the device's
    measured workout calories; active = TEE - BMR (floored at 0).

    Every row carries the weight it was built on and that weight's age, and past
    ``freshness.WEIGHT_MAX_AGE_DAYS`` a ``caveats`` entry naming the lean. All three, not
    just ``total_calories``: they are three cards, and a marker on one of them is a marker
    the owner reading either of the other two never sees.
    """
    age = _age(prof["dob"], day)
    bmr = (
        10 * prof["weight_kg"]
        + 6.25 * prof["height_cm"]
        - 5 * age
        + (5 if prof["sex"] == "male" else -161)
    )
    total = _tee_met(cur, user_id, start_utc, end_utc, bmr, stride_m)
    # The uncounted sessions come back beside the sum, because a NULL in this column is
    # not a zero: the caller's MET walk has already skipped these minutes.
    cur.execute(
        "SELECT COALESCE(SUM(calories),0), "
        "COUNT(*) FILTER (WHERE calories IS NULL), "
        "COALESCE(SUM(duration_s) FILTER (WHERE calories IS NULL), 0) FROM workout "
        "WHERE user_id = %s AND start_ts >= %s AND start_ts < %s",  # half-open bounds
        (user_id, start_utc, end_utc),
    )
    workout_cal, uncounted_n, uncounted_s = cur.fetchone() or (0.0, 0, 0)
    workout_cal = float(workout_cal or 0.0)
    total += workout_cal
    active_total = max(0.0, total - bmr)
    weight = weight_flags(prof, day, bmr)
    uncounted = uncounted_workout_caveats(int(uncounted_n or 0), round(int(uncounted_s or 0) / 60))
    # Both disclosures ride the same list. `basal_calories` is untouched by the workout gap
    # — it is BMR, which no session enters — so it keeps the weight caveats alone.
    energy_caveats = {**weight, "caveats": [*weight["caveats"], *uncounted]}
    flags = {
        "bmr": round(bmr),
        "workout_cal": round(workout_cal),
        "stride_m": round(stride_m, 3),
        "pal": round(total / bmr, 2),
        **energy_caveats,
    }
    _upsert_daily(cur, user_id, day, "total_calories", total, flags)
    _upsert_daily(cur, user_id, day, "active_calories", active_total, energy_caveats)
    _upsert_daily(cur, user_id, day, "basal_calories", bmr, weight)
    return {
        "total_calories": round(total),
        "active_calories": round(active_total),
        "basal_calories": round(bmr),
    }
