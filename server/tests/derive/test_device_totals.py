"""The steps/distance precedence, tested where it is pure (#121).

`derive/device_totals.py` owns the ORDER — the strap's own daily counter first, the
per-minute sample sum as the fallback — and owns it as two total functions over plain
data, so the rule can be pinned without a database. The integration half (a push stores
the counter, a re-derive still lands it, owner scoping) is
`tests/integration/test_device_daily_totals.py`.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from strap_server.derive.device_totals import (
    SOURCE_PER_MINUTE,
    DeviceDailyTotal,
    partial_day_caveats,
    select_distance,
    select_steps,
)
from strap_server.derive.freshness import COUNTER_MID_DAY, COUNTER_READ_TIME_UNKNOWN

_DAY = date(2026, 6, 16)
_REPORTED_AT = datetime(2026, 6, 16, 21, 30, tzinfo=UTC)
_STRAP = "strap_0x16"


def _device(
    steps: int | None = 9264,
    distance_m: float | None = None,
    source: str = _STRAP,
    reported_at: datetime = _REPORTED_AT,
    read_at: datetime | None = _REPORTED_AT,
) -> DeviceDailyTotal:
    return DeviceDailyTotal(
        day=_DAY,
        steps=steps,
        distance_m=distance_m,
        calories=451.0,
        source=source,
        reported_at=reported_at,
        read_at=read_at,
    )


# ── steps: the precedence ─────────────────────────────────────────────────────


def test_the_device_counter_beats_the_per_minute_sum() -> None:
    """The whole point of #121: the strap's own count is what the day carries.

    The per-minute stream drops whole stretches when the strap's pager stalls (the
    0xFF-gap stall this override was originally written for), and a stalled hour looks
    exactly like a quiet one — so the sum can only undercount, silently. 6,100 vs 9,264
    is that gap.
    """
    steps = select_steps(_device(steps=9264), per_minute_sum=6100.0)
    assert steps.value == 9264.0
    assert steps.flags["source"] == _STRAP


def test_the_per_minute_sum_is_the_fallback_when_the_strap_reported_nothing() -> None:
    steps = select_steps(None, per_minute_sum=6100.0)
    assert steps.value == 6100.0
    assert steps.flags["source"] == SOURCE_PER_MINUTE


def test_a_report_that_carries_no_step_count_falls_back_too() -> None:
    """A row with distance but no steps is not a step measurement — each field stands
    alone, so the fallback is decided on `steps`, not on the row existing."""
    steps = select_steps(_device(steps=None, distance_m=5081.0), per_minute_sum=6100.0)
    assert steps.value == 6100.0
    assert steps.flags["source"] == SOURCE_PER_MINUTE


def test_the_source_names_the_instrument_that_actually_reported() -> None:
    """Read off the stored row, never assumed — a future reporter names itself."""
    steps = select_steps(_device(source="strap_0x99"), per_minute_sum=1.0)
    assert steps.flags["source"] == "strap_0x99"


def test_the_losing_instrument_stays_visible_in_flags() -> None:
    """Both numbers reach the row; only one of them IS the number.

    Without this a reader cannot tell a genuinely quiet day from a stalled stream, which
    is the diagnosis the 0xFF-gap incident needed and did not have.
    """
    steps = select_steps(_device(steps=9264), per_minute_sum=6100.4)
    assert steps.flags["steps_per_minute_sum"] == 6100
    assert steps.flags["reported_at"] == _REPORTED_AT.isoformat()


def test_the_row_carries_both_instants_and_never_confuses_them() -> None:
    """`read_at` is when the strap was ASKED; `reported_at` is when the push LANDED.

    One column did both jobs until `0019` and the disclosure quoted the wrong one (A1).
    """
    read_at = datetime(2026, 6, 16, 9, 0, tzinfo=UTC)
    steps = select_steps(_device(read_at=read_at, reported_at=_REPORTED_AT), 6100.0)
    assert steps.flags["read_at"] == read_at.isoformat()
    assert steps.flags["reported_at"] == _REPORTED_AT.isoformat()


def test_an_unrecorded_read_instant_is_null_on_the_row_not_the_arrival() -> None:
    """The one substitution that must never happen: arrival standing in for reading."""
    steps = select_steps(_device(read_at=None), 6100.0)
    assert steps.flags["read_at"] is None
    assert steps.flags["reported_at"] == _REPORTED_AT.isoformat()


# ── the partial-day disclosure: which instant it asks about (audit A1) ────────

_DAY_END_UTC = datetime(2026, 6, 16, 18, 30, tzinfo=UTC)  # next local midnight, Asia/Kolkata
_READ_MID_DAY = datetime(2026, 6, 16, 3, 30, tzinfo=UTC)  # 09:00 local
_PUSHED_AFTER_MIDNIGHT = datetime(2026, 6, 17, 2, 0, tzinfo=UTC)  # the normal case


def _caveats(**kwargs: object) -> list[dict]:
    device = _device(**kwargs)  # type: ignore[arg-type]
    return partial_day_caveats(device, select_steps(device, 6100.0), _DAY_END_UTC)


def test_a_counter_read_before_the_day_closed_names_the_read_instant() -> None:
    caveats = _caveats(read_at=_READ_MID_DAY, reported_at=_READ_MID_DAY)
    assert [c["reason"] for c in caveats] == [COUNTER_MID_DAY]
    assert caveats[0]["read_at"] == _READ_MID_DAY.isoformat()
    assert _READ_MID_DAY.isoformat() in caveats[0]["message"]


def test_the_caveat_survives_a_push_that_crossed_local_midnight() -> None:
    """THE A1 defect, in one assertion.

    The counter was read at 09:00 and the push landed after midnight, which is the normal
    case — auto-sync fires on a foreground transition. The gate compared the ARRIVAL
    against the day's end, found it later, and returned `[]`; `select_steps` prefers the
    counter unconditionally, so nine hours served as the whole day with nothing said.
    """
    caveats = _caveats(read_at=_READ_MID_DAY, reported_at=_PUSHED_AFTER_MIDNIGHT)
    assert [c["reason"] for c in caveats] == [COUNTER_MID_DAY]
    assert caveats[0]["read_at"] == _READ_MID_DAY.isoformat()
    assert caveats[0]["reported_at"] == _PUSHED_AFTER_MIDNIGHT.isoformat()


def test_a_counter_read_after_the_day_closed_says_nothing() -> None:
    """It covers the whole day; there is no interval to disclose."""
    read_at = datetime(2026, 6, 16, 19, 0, tzinfo=UTC)  # past the local day's end
    assert _caveats(read_at=read_at, reported_at=_PUSHED_AFTER_MIDNIGHT) == []


def test_an_unknown_read_instant_gets_its_own_caveat_rather_than_silence() -> None:
    """Every row written before `0019`, and every row an older app build writes.

    Unknown is neither "read mid-day" nor "read after the day closed". Folding it into the
    second is exactly how the true caveat went missing, so it gets its own id and says in
    the second person that we cannot name the interval.
    """
    caveats = _caveats(read_at=None, reported_at=_PUSHED_AFTER_MIDNIGHT)
    assert [c["reason"] for c in caveats] == [COUNTER_READ_TIME_UNKNOWN]
    assert caveats[0]["read_at"] is None
    assert "when it was taken" in caveats[0]["message"]


def test_the_per_minute_tier_discloses_nothing() -> None:
    """A sum over samples covers whatever the day delivered, not a prefix of it."""
    assert partial_day_caveats(None, select_steps(None, 6100.0), _DAY_END_UTC) == []
    assert _caveats(steps=None, read_at=None) == []


def test_the_two_instruments_are_never_blended() -> None:
    """No sum, mean, median or max of the two can be the served value — ever.

    `derive/vo2max_tier.py` had to make the same guarantee structural because a blend of
    two instruments landed one tenth from a single instrument and was invisible to
    inspection. Here a max() would look just as reasonable and would be just as much a
    number no instrument measured.
    """
    for counter, per_minute in ((9264, 6100.0), (1000, 12_000.0), (0, 0.0), (5, 5.0)):
        chosen = select_steps(_device(steps=counter), per_minute)
        assert chosen.value == float(counter), "the value must be ONE instrument's, unmixed"
        assert chosen.value in (float(counter), per_minute)


# ── distance: the same shape, one layer down ──────────────────────────────────


_STRIDE_M = 0.7245  # 0.414 × 175 cm [[distance_from_steps]]


def test_the_device_metres_win_when_it_reported_them() -> None:
    device = _device(steps=9264, distance_m=5081.0)
    dist = select_distance(device, select_steps(device, 6100.0), _STRIDE_M)
    assert dist is not None
    assert dist.value == 5081.0
    assert (dist.flags["source"], dist.flags["method"]) == (_STRAP, "device")


def test_a_device_distance_lands_without_a_profile() -> None:
    """It is a measurement; it needs no height. The stride tier is what needs one."""
    device = _device(steps=None, distance_m=5081.0)
    dist = select_distance(device, select_steps(device, 0.0), None)
    assert dist is not None and dist.value == 5081.0


def test_the_stride_tier_follows_whichever_step_count_won() -> None:
    """Distance from the DEVICE's steps when the device's steps are what we serve.

    Recomputing it from the per-minute sum while `steps_total` carries the counter would
    put two different days' worth of walking on one screen.
    """
    device = _device(steps=9264)  # no distance reported
    dist = select_distance(device, select_steps(device, 6100.0), _STRIDE_M)
    assert dist is not None
    assert dist.value == 9264 * _STRIDE_M
    assert (dist.flags["source"], dist.flags["method"]) == (_STRAP, "stride")
    assert dist.flags["stride_m"] == 0.725  # the stride is reported, rounded to mm


def test_the_stride_tier_uses_the_fallback_steps_when_that_is_what_won() -> None:
    dist = select_distance(None, select_steps(None, 6100.0), _STRIDE_M)
    assert dist is not None
    assert dist.value == 6100.0 * _STRIDE_M
    assert dist.flags["source"] == SOURCE_PER_MINUTE


def test_no_distance_at_all_when_neither_tier_can_speak() -> None:
    """No device metres and no stride → nothing is written, rather than a guess."""
    assert select_distance(None, select_steps(None, 6100.0), None) is None
