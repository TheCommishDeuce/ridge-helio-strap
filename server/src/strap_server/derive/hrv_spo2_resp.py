"""Overnight autonomic/respiratory window means for one sleep session.

Mean overnight HRV (RMSSD) and SpO2 (plus the overnight SpO2 minimum) and
respiratory rate, each a bounded average over the sleep window. Ported verbatim
from the legacy v2 ``derive_night`` window-stat blocks. Knowledge notes:
``hrv_recovery_marker`` / ``heart_rate_variability`` (nightly RMSSD as an
autonomic-recovery marker), ``wearable_spo2_validity``, ``respiratory_rate_normal``.
"""

from __future__ import annotations

from datetime import date, datetime
from uuid import UUID

from strap_server.derive._common import Cur, _upsert_daily, _window_stat


def derive_night_vitals(
    cur: Cur, user_id: UUID, day: date, start_ts: datetime, end_ts: datetime
) -> dict:
    """Upsert overnight HRV / SpO2 / SpO2-min / respiratory-rate for the wake day.

    Each metric is a bounded window mean (SpO2 also carries its window minimum).
    Missing metrics are simply omitted (no row written), so "no data" stays
    distinct from a real value. Returns the rounded values that were written.
    """
    out: dict = {}

    # 5-200 ms: a PLAUSIBILITY gate on RMSSD, not a research constant, and deliberately
    # uncited — no note in the corpus states a physiological range for it, and
    # `heart-rate-variability` is about interpretation rather than sensor artefacts.
    # (Contrast the SpO2 bounds below, which ARE cited, at
    # `wearable_spo2_validity.md`.) Same reasoning as `derive/hr_validity.py`.
    hrv = _window_stat(cur, user_id, "hrv", start_ts, end_ts, 5, 200)
    if hrv is not None:
        _upsert_daily(cur, user_id, day, "hrv_sleep_avg", hrv)
        out["hrv_sleep_avg"] = round(hrv, 2)

    spo2 = _window_stat(cur, user_id, "spo2", start_ts, end_ts, 70, 100)
    if spo2 is not None:
        _upsert_daily(cur, user_id, day, "spo2_overnight", spo2)
        out["spo2_overnight"] = round(spo2, 2)
        lo = _window_stat(cur, user_id, "spo2", start_ts, end_ts, 70, 100, stat="MIN")
        if lo is not None:
            _upsert_daily(cur, user_id, day, "spo2_overnight_min", lo)
            out["spo2_overnight_min"] = round(lo, 2)

    # 4-40 br/min: a PLAUSIBILITY gate, uncited for the same reason. It is wider than
    # `respiratory_rate_normal`'s 12-20 healthy range ON PURPOSE — a filter that
    # narrowed to the healthy band would delete exactly the elevated nights the illness
    # flag exists to see.
    rr = _window_stat(cur, user_id, "respiratory_rate", start_ts, end_ts, 4, 40)
    if rr is not None:
        _upsert_daily(cur, user_id, day, "respiratory_rate_sleep", rr)
        out["respiratory_rate_sleep"] = round(rr, 2)

    return out
