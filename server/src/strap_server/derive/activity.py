"""Daily step, distance, and calorie totals for one local day.

Steps come from the tiered choice in ``derive/device_totals.py`` — the strap's own daily
counter when it reported one, the per-minute sample sum otherwise (#121). Distance is the
device's metres, else steps × stride (0.414 × height); calories are delegated to the
MET-by-state energy model. Ported verbatim from legacy v2 ``derive_daily_activity``; the
step/distance PRECEDENCE is the one thing that is not legacy's, because legacy had none —
the strap counter was pasted over this pass's output after the fact and destroyed by the
next one. Knowledge: [[distance_from_steps]], [[steps_mortality]],
[[energy_expenditure_derivation]].
"""

from __future__ import annotations

from datetime import date, datetime
from uuid import UUID

from strap_server.derive._common import Cur, _day_bounds_utc, _load_profile, _upsert_daily
from strap_server.derive.device_totals import (
    device_total,
    partial_day_caveats,
    select_distance,
    select_steps,
)
from strap_server.derive.energy import derive_calories

_STRIDE_HEIGHT_FRACTION = 0.414  # stride length ~= 0.414 x height [[distance_from_steps]]


def _steps_per_minute_sum(
    cur: Cur, user_id: UUID, start_utc: datetime, end_utc: datetime
) -> tuple[float, int]:
    """The day's steps as the per-minute stream reports them, and HOW MANY minutes said so.

    Values >= 250 steps/min are excluded as implausible for a wrist counter. This sum is
    what ``steps_total`` used to be unconditionally; ``device_totals.select_steps`` decides
    whether it is what the day gets.

    The COUNT is the second return value because ``COALESCE(SUM(value), 0)`` cannot tell
    "the stream reported nothing" from "the stream reported zero", and those are not the
    same fact about a person (audit C7). See :func:`derive_daily_activity`.
    """
    cur.execute(
        "SELECT COALESCE(SUM(value),0), COUNT(*)::int FROM sample "
        "WHERE user_id = %s AND metric='steps_per_minute' "
        "AND value < 250 AND ts >= %s AND ts < %s",  # half-open: see `_day_bounds_utc`
        (user_id, start_utc, end_utc),
    )
    row = cur.fetchone() or (0.0, 0)
    return float(row[0] or 0.0), int(row[1] or 0)


def derive_daily_activity(cur: Cur, user_id: UUID, tz: str, day: date) -> dict:
    """steps_total, distance_m_daily, and total/active/basal calories for a day.

    steps_total is upserted whenever an instrument SPOKE, and always carries which one in
    ``flags.source``. Distance needs either a device-reported figure or a profile (for the
    stride); calories additionally need a logged weight, and are skipped when the profile
    is absent.

    ## A day no instrument counted is not a day of zero steps (audit C7)

    This used to upsert ``steps_total`` unconditionally — "0 is valid, so re-derivation
    overwrites stale rows" — and on a day the strap was not worn ``select_steps`` returns
    ``DailyValue(0.0, {"source": "steps_per_minute"})`` from a sum over no rows. That zero
    then entered every baseline (``analytics/metrics.py``'s sentinel for this metric is
    ``value >= 0``, which admits it), dragging the owner's step baseline down as though
    they had walked nowhere, and it sat on the Today card as a measured "0". Compare
    ``rhr_daily``'s ``value > 30``: that filter exists precisely because an RHR of 0 means
    *not measured*, and the same distinction was not drawn one row down.

    **The evidence that decides it, and its limit — stated rather than guessed.** The
    strap's own parser emits a ``steps_per_minute`` sample only for a minute that recorded
    a step (``apps/mobile/lib/ble/parsers/activity_parser.dart``: ``if (steps > 0 && steps
    != 0xFF)``), so a zero-step minute produces no row and the count below is "how many
    minutes of this day the instrument spoke for". Zero of them means the instrument did
    not speak. It does NOT distinguish an unworn day from a worn day on which the owner
    took no step in any minute of it — nothing in this server models wear, and inventing a
    wear signal out of the heart-rate stream would be a second definition of "worn" to sit
    beside no first one. So the claim made here is the narrow one the data supports: no
    instrument counted this day, therefore we have no step count for it.

    The device tier is untouched: a strap that reports a counter of 0 has MEASURED zero
    steps, and that row is written and kept.

    **What a narrowing derivation costs, priced (#118).** Declining to write leaves any
    false zero already in ``derived_daily`` serving forever, because every write is an
    upsert and nothing deletes. No migration fixes that — the rows are per-owner and
    per-day — but the tool for exactly this exists and needs no change:
    ``db/stale_derived.py``'s ``--purge-stale steps_total`` inside a re-derive's own
    transaction removes the rows this pass declined to rewrite. ``steps_total`` is not in
    ``GATED_METRICS``, so the purge admits it. Nothing is done to production here.
    """
    out: dict = {}
    start_utc, end_utc = _day_bounds_utc(day, tz)

    device = device_total(cur, user_id, day)
    per_minute_sum, per_minute_n = _steps_per_minute_sum(cur, user_id, start_utc, end_utc)
    steps = select_steps(device, per_minute_sum)
    counted = (device is not None and device.steps is not None) or per_minute_n > 0
    if counted:
        _upsert_daily(
            cur,
            user_id,
            day,
            "steps_total",
            steps.value,
            {
                **steps.flags,
                # How many minutes the per-minute stream spoke for. Beside the value on
                # every row, not only as the gate above: a floor answers "may we say
                # this" and a count answers "how much is behind it" (``read/fitness.py``
                # makes the same argument for ``baseline_30d_n``).
                "sample_minutes": per_minute_n,
                # A PERMANENT key, empty when there is nothing to disclose — the contract
                # ``derive/energy.py`` holds and ``read/today_series.py`` renders.
                "caveats": partial_day_caveats(device, steps, end_utc),
            },
        )
        out["steps_total"] = round(steps.value, 0)

    prof = _load_profile(cur, user_id, tz, day)
    stride_m = _STRIDE_HEIGHT_FRACTION * prof["height_cm"] / 100.0 if prof else None
    # The stride tier multiplies the step count, so it inherits the step count's silence:
    # with no instrument behind the steps there is no distance to derive either, and a
    # zero here would have entered the distance baseline the same way (audit C7). A
    # DEVICE-reported distance still lands — it is a measurement and needs no step count.
    dist = select_distance(device, steps, stride_m if counted else None)
    if dist is not None:
        _upsert_daily(cur, user_id, day, "distance_m_daily", dist.value, dist.flags)
        out["distance_m_daily"] = round(dist.value, 0)
    if prof and stride_m is not None:
        out.update(derive_calories(cur, user_id, day, prof, start_utc, end_utc, stride_m))
    return out
