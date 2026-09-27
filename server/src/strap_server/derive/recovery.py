"""Morning recovery / readiness score (0-100) for one local day.

Primary = overnight HRV (lnRMSSD) + resting HR vs the PERSONAL trailing baseline
(Plews/Buchheit); secondary = sleep-vs-NEED + respiratory rate. No peer-reviewed
formula combines these, so this is an honest evidence-weighted estimate ALWAYS
shown with its per-factor breakdown (the documented no-black-box exception). HRV/
RHR/RR are personal-relative; sleep is scored vs ABSOLUTE need. Ported verbatim
from legacy v2. Knowledge: [[recovery_readiness]].
"""

from __future__ import annotations

from datetime import date, timedelta
from uuid import UUID

from strap_server.derive._common import Cur, _clamp100, _upsert_daily
from strap_server.derive.robust import median, median_abs_deviation, robust_sd

# Weights by evidence strength (not fitted) — recovery_readiness.
RECOVERY_WEIGHTS = {"hrv": 0.42, "rhr": 0.28, "sleep": 0.20, "rr": 0.10}
_BASELINE_DAYS = 42  # trailing window for the personal baseline
_BASELINE_MIN_POINTS = 5  # need at least this many days to trust a baseline

# Degenerate-history guard on the robust SD, in the units of the AUTONOMIC markers
# this baseline serves: HRV (ms), RHR (bpm), respiratory rate (breaths/min). It binds
# only when the trailing MAD is under ~0.34 of those units — i.e. a history flat
# enough that an unfloored z would explode. Specified at 0.5 by
# [[recovery_readiness]] ("a 0.5 floor on the robust SD so a flat history can't
# explode the z-score"), which governs THIS baseline only.
#
# The Today-page sleep signal (read/recovery.py) floors at 1.0 instead — a different
# number for a different reason: it guards MINUTES of sleep duration, not ms/bpm. The
# two are not a shared science constant and must not be unified into one; see that
# module's `_SLEEP_MIN_SD_MIN`.
_AUTONOMIC_MIN_SD = 0.5


def _recovery_baseline(
    cur: Cur, user_id: UUID, metric: str, day: date, days: int = _BASELINE_DAYS
) -> tuple[float | None, float | None]:
    """Robust personal baseline (median + MAD*1.4826) over the trailing window.

    Excludes the day itself. (None, None) when fewer than 5 points are available.

    Median and MAD come from ``derive/robust`` — the ONE definition of each. This
    function used to hand-roll both, and its MAD step took the UPPER-middle deviation
    (``devs[len(devs) // 2]``) instead of interpolating, so on an even-length window it
    reported a spread the textbook MAD does not. That is a second definition of a named
    statistic, which CLAUDE.md forbids outright ("ONE canonical definition per metric").
    """
    cur.execute(
        "SELECT value FROM derived_daily "
        "WHERE user_id = %s AND metric=%s AND day < %s AND day >= %s",
        (user_id, metric, day, day - timedelta(days=days)),
    )
    vals = [float(r[0]) for r in cur.fetchall()]
    if len(vals) < _BASELINE_MIN_POINTS:
        return None, None
    return median(vals), robust_sd(median_abs_deviation(vals), _AUTONOMIC_MIN_SD)


def _personal_factor(
    cur: Cur,
    user_id: UUID,
    day: date,
    factors: dict,
    key: str,
    metric: str,
    k: float,
    higher_better: bool,
) -> None:
    """Score one personal-baseline factor (50 + k*z, direction by `higher_better`)."""
    med, sd = _recovery_baseline(cur, user_id, metric, day)
    cur.execute(
        "SELECT value FROM derived_daily WHERE user_id = %s AND metric=%s AND day=%s",
        (user_id, metric, day),
    )
    r = cur.fetchone()
    if not r or r[0] is None or med is None or sd is None:
        return
    v = float(r[0])
    z = (v - med) / sd
    sub = _clamp100(50 + k * z) if higher_better else _clamp100(50 - k * z)
    factors[key] = {
        "sub": round(sub),
        "z": round(z, 2),
        "value": round(v, 1),
        "baseline": round(med, 1),
    }


def _sleep_factor(cur: Cur, user_id: UUID, day: date, factors: dict) -> None:
    """Score sleep vs the owner's OWN stored need (not the personal baseline, and not a
    constant).

    ## No fallback need, because there is no such thing as a fallback need

    ``need`` used to be ``float(nr[0]) if nr and nr[0] else _DEFAULT_NEED_MIN``, a module
    constant of ``480.0``. That is a personal target invented for an owner we have never
    been able to compute one for, and it published itself: ``flags.factors.sleep.need_min``
    said 480 was **this owner's** need, and the sleep sub-score was the ratio of their
    real sleep to it. For an owner over 65 the canonical need is 450
    (``derive/sleep_score.SLEEP_NEED_MIN_65P``), so the flat value scored them against a
    target half an hour too high and reported it as theirs.

    It was also the third definition of one metric — this constant, the age-selected pair
    in ``derive/sleep_score.py``, and a fourth in the client — which is the failure
    CLAUDE.md names first: "ONE canonical definition per metric … two definitions of sleep
    debt is a lie waiting to surface". The definition that survives is
    ``sleep_score.derive_sleep_debt``'s, because it is the one with NSF 2015 behind it and
    an age selecting between its bands; every other reader takes the row it wrote.

    With no ``sleep_need_min`` row the sleep factor is simply ABSENT — not defaulted, not
    zeroed. ``derive_recovery`` already weights only the factors it has (``tw`` is the sum
    over ``factors``), so an absent sleep factor makes the score an honest composite of the
    autonomic markers rather than a composite containing a fabricated ratio.
    """
    cur.execute(
        "SELECT (flags->>'tst_min')::float FROM derived_daily "
        "WHERE user_id = %s AND metric='sleep_health_score_4dim' AND day=%s",
        (user_id, day),
    )
    sr = cur.fetchone()
    cur.execute(
        "SELECT value FROM derived_daily WHERE user_id = %s AND metric='sleep_need_min' "
        "AND day<=%s ORDER BY day DESC LIMIT 1",
        (user_id, day),
    )
    nr = cur.fetchone()
    if not (nr and nr[0]):
        return
    need = float(nr[0])
    if sr and sr[0] is not None:
        tst = float(sr[0])
        factors["sleep"] = {
            "sub": round(_clamp100(100.0 * tst / need)),
            "tst_min": round(tst),
            "need_min": round(need),
        }


def derive_recovery(cur: Cur, user_id: UUID, day: date) -> dict | None:
    """0-100 morning recovery from overnight autonomic + sleep markers.

    Each factor is scored vs its baseline and combined by evidence-weighted
    contribution; the full per-factor breakdown rides in `flags` so the UI/LLM
    never show a bare number. None without at least one autonomic marker (HRV or
    RHR). [[recovery_readiness]].
    """
    factors: dict = {}
    _personal_factor(cur, user_id, day, factors, "hrv", "hrv_sleep_avg", 20.0, higher_better=True)
    _personal_factor(cur, user_id, day, factors, "rhr", "rhr_daily", 20.0, higher_better=False)
    _personal_factor(
        cur, user_id, day, factors, "rr", "respiratory_rate_sleep", 15.0, higher_better=False
    )
    _sleep_factor(cur, user_id, day, factors)

    if "hrv" not in factors and "rhr" not in factors:
        return None
    tw = sum(RECOVERY_WEIGHTS[k] for k in factors)
    score = sum(RECOVERY_WEIGHTS[k] * factors[k]["sub"] for k in factors) / tw
    flags = {
        "factors": factors,
        "weights": {k: RECOVERY_WEIGHTS[k] for k in factors},
        "method": "evidence_weighted_personal_baseline",
        "note_id": "recovery_readiness",
    }
    _upsert_daily(cur, user_id, day, "recovery_score", round(score), flags)
    return {"recovery_score": round(score)}
