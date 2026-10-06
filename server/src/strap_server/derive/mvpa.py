"""Cadence-based moderate-to-vigorous physical activity minutes for one day.

Per-minute step cadence classifies each minute: moderate at >=100 steps/min
(with a >=80 prior minute), vigorous at >=130 (with a >=110 prior). The stored
``mvpa_min`` is the **MET-equivalent** total ``moderate + 2 x vigorous`` (WHO 2020),
not a raw minute count — see :data:`_VIGOROUS_MET_WEIGHT`. ``moderate`` and ``vigorous``
ride in the row's flags as the un-weighted halves. Cadence-derived only: workouts are
not counted, and [[mvpa_weekly_plan]] step 2 says so rather than describing a join
nothing performs. Knowledge: [[cadence_intensity]] (Tudor-Locke 2018),
[[mvpa_minutes_mortality]] (the 150 min/week target and the 1-vigorous-=-2-moderate rule).
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from uuid import UUID

from strap_server.derive._common import Cur, _day_bounds_utc, _upsert_daily
from strap_server.zones import ZoneLike

# Cadence thresholds (steps/min) with a one-minute "prior" gate [[cadence_intensity]].
_MODERATE_SPM = 100
_MODERATE_PRIOR_SPM = 80
_VIGOROUS_SPM = 130
_VIGOROUS_PRIOR_SPM = 110

# WHO 2020's own equivalence: 150-300 min/wk moderate **OR** 75-150 vigorous, i.e. one
# vigorous minute counts as two moderate. `mvpa_min` is therefore a MET-EQUIVALENT total,
# which is the only reading under which comparing it to the 150 target means anything.
# [[mvpa_minutes_mortality]] states the rule and the formula five times across three
# notes; `read/fitness.py` compares the sum to 150; the app's own explainer says
# "vigorous minutes count double". Until 2026-09-08 this module summed them unweighted,
# so every consumer measured MET-equivalent minutes against a raw-minute total and
# UNDER-credited anyone who ran. The direction was the safe one, which is why it survived
# — and why it is a defect and not a house convention.
_VIGOROUS_MET_WEIGHT = 2


# ── What used to live here, and why it does not (2026-08-02, #108) ───────────
#
# `_weekly_mvpa_to_srpa` mapped the trailing 7 days of MVPA-equivalent minutes onto
# Jurca 2005's five-level SELF-REPORTED physical-activity category, banded at
# 10/20/60/180 min/wk, and fed it to `derive/vo2max.py`.
#
# It was deleted rather than re-banded. The bands were not the defect; the CONSTRUCT was.
# Jurca's levels, as his Table 1 defines them, are about deliberate exercise — level 4 is
# "Aerobic exercise such as run/walk for 1 to 3 hours per week" and level 1 is "Little
# activity other than walking for pleasure". This module counts every minute above 100
# steps/min, which is brisk walking, and has no way to know whether it was training or a
# commute. Where questionnaire and accelerometer categories have been compared directly
# the agreement is close to nil (IPAQ lands in the accelerometer's category ~2% of the
# time; MVPA correlations run r ~= 0.2-0.44). So there was no band edge that would have
# made the mapping true.
#
# `mvpa_min` itself is untouched and still derived — it is a real metric with a real
# target (150 min/wk, [[mvpa_minutes_mortality]]). What it is not is a stand-in for a
# question about someone's exercise habits. That question now lives in the profile.


def derive_mvpa(cur: Cur, user_id: UUID, tz: ZoneLike, day: date) -> dict | None:
    """Cadence-based MVPA minutes for one local day. None when no steps recorded."""
    start_utc, end_utc = _day_bounds_utc(day, tz)
    cur.execute(
        "SELECT ts, value FROM sample WHERE user_id = %s AND metric='steps_per_minute' "
        "AND value>0 AND value<250 AND ts>=%s AND ts<%s ORDER BY ts",  # half-open bounds
        (user_id, start_utc, end_utc),
    )
    rows = cur.fetchall()
    if not rows:
        return None
    by_min: dict[datetime, float] = {}
    for ts, v in rows:
        k = ts.replace(second=0, microsecond=0)
        by_min[k] = max(by_min.get(k, 0.0), float(v))
    moderate = vigorous = 0
    for ts, spm in by_min.items():
        if spm < _MODERATE_SPM:
            continue
        prev = by_min.get(ts - timedelta(minutes=1), 0.0)
        if spm >= _VIGOROUS_SPM and prev >= _VIGOROUS_PRIOR_SPM:
            vigorous += 1
        elif prev >= _MODERATE_PRIOR_SPM:
            moderate += 1
    mvpa = moderate + _VIGOROUS_MET_WEIGHT * vigorous
    _upsert_daily(cur, user_id, day, "mvpa_min", mvpa, {"moderate": moderate, "vigorous": vigorous})
    return {"mvpa_min": mvpa, "moderate": moderate, "vigorous": vigorous}
