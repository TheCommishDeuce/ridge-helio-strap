"""The calorie walk integrates a LOCAL day, including the two that aren't 24 h long.

``_day_bounds_utc`` is the ONE definition of a local day — half-open, from this local
midnight to the next, which is what makes it DST-correct: ZoneInfo re-resolves the
offset at each edge, so a transition day legitimately brackets 23 h or 25 h.
``energy._tee_met`` then walked a fixed ``range(1440)`` from the day's first instant,
which on those two days is not the length of the day:

    America/New_York 2026-03-08 (spring forward, 23 h): real minutes 1380
        -> a 1440-minute walk runs 60 min INTO the next local day (over-counts)
    America/New_York 2026-11-01 (fall back, 25 h):      real minutes 1500
        -> a 1440-minute walk MISSES the last local hour (under-counts)

Harmless while every owner was in Asia/Kolkata (no DST); 6.4 made per-user timezones
live, so this mis-integrated a real user's calories twice a year.

The NON-DST day is the regression guard and matters as much as the fixes: any change
to a normal day's calories would be a regression, not a fix. ``test_normal_day_*``
pins the count at exactly 1440 and ``test_non_dst_day_calories_are_byte_identical``
pins the resulting kcal against the pre-fix hardcoded-1440 walk.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo, available_timezones

import pytest

from strap_server.derive._common import _day_bounds_utc, _day_minutes
from strap_server.derive.energy import _tee_met
from tests.compat import SENTINEL_USER_ID, tenant_transaction

_NY = "America/New_York"
_IST = "Asia/Kolkata"

# ── the minute count itself (pure, no DB) ────────────────────────────────────
#
# Hand-derived from the bounds contract, NOT recorded from the code. `_day_bounds_utc`
# returns the half-open [local midnight, next local midnight), so the span IS the day
# and the count is simply span // 60 — local 00:00 through 23:59 inclusive:
#
#   normal 24 h day:  86400 s // 60 = 1440
#   spring forward:   the local day is 23 h, 82800 s // 60 = 1380
#   fall back:        the local day is 25 h, 90000 s // 60 = 1500


@pytest.mark.parametrize(
    ("tz", "day", "expected"),
    [
        (_NY, date(2026, 3, 8), 1380),  # spring forward: 23 h
        (_NY, date(2026, 11, 1), 1500),  # fall back: 25 h
        (_NY, date(2026, 6, 15), 1440),  # normal EDT day — REGRESSION GUARD
        (_NY, date(2026, 1, 15), 1440),  # normal EST day — REGRESSION GUARD
        (_IST, date(2026, 3, 8), 1440),  # India never transitions — REGRESSION GUARD
        (_IST, date(2026, 11, 1), 1440),  # REGRESSION GUARD
        ("UTC", date(2026, 3, 8), 1440),  # REGRESSION GUARD
    ],
)
def test_day_minutes_follows_the_real_local_day(tz: str, day: date, expected: int) -> None:
    assert _day_minutes(*_day_bounds_utc(day, tz)) == expected


# ── #58: the MIDNIGHT transitions, where a `23:59:59` end lies ───────────────
#
# Every zone above transitions at 02:00/03:00, so a day's own 23:59:59 is a perfectly
# ordinary wall-clock time. In a zone that transitions AT MIDNIGHT it is not, and the
# old end — `start.replace(hour=23, minute=59, second=59)` — was wrong by exactly an
# hour in both directions:
#
#   falling back, that hour runs TWICE and PEP 495 resolves fold=0, the FIRST pass,
#     leaving the repeat outside the bracket: a 25 h day measured 1440 and the walk
#     dropped its final hour (under-count).
#   springing forward at 23:00, that hour NEVER HAPPENS and ZoneInfo resolves it with
#     the pre-transition offset, landing an hour PAST the day's real end: a 23 h day
#     measured 1440 and the walk ran into the next local day (over-count).
#
# The half-open [midnight, next midnight) bracket has neither failure mode, because a
# local midnight is the edge BOTH neighbouring days use, whichever instant ZoneInfo
# picks for it.
#
# Verified against `zoneinfo` for these exact dates (never assumed — offsets are data;
# `test_the_midnight_transition_days_really_are_23_or_25h` re-proves it every run):
#   America/Santiago 2026-04-04  03:00Z -> 2026-04-05 04:00Z  = 25 h, old code 1440
#   Asia/Beirut      2026-10-24  21:00Z -> 2026-10-24 22:00Z  = 25 h, old code 1440
#   America/Nuuk     2026-03-28  02:00Z -> 2026-03-29 01:00Z  = 23 h, old code 1440
_MIDNIGHT_TRANSITION_DAYS = [
    ("America/Santiago", date(2026, 4, 4), 1500),
    ("Asia/Beirut", date(2026, 10, 24), 1500),
    ("America/Nuuk", date(2026, 3, 28), 1380),
]


@pytest.mark.parametrize(("tz", "day", "expected"), _MIDNIGHT_TRANSITION_DAYS)
def test_midnight_transition_days_measure_their_real_length(
    tz: str, day: date, expected: int
) -> None:
    """A zone that transitions at midnight still owns 25 h (or 23 h), not 24."""
    assert _day_minutes(*_day_bounds_utc(day, tz)) == expected


@pytest.mark.parametrize(("tz", "day", "expected"), _MIDNIGHT_TRANSITION_DAYS)
def test_the_midnight_transition_days_really_are_23_or_25h(
    tz: str, day: date, expected: int
) -> None:
    """The premise of the cases above, proven from `zoneinfo` rather than asserted.

    If a tz-database update moves these transitions, THIS fails — loudly — instead of
    the DST cases silently becoming tests of an ordinary day.
    """
    zone = ZoneInfo(tz)
    start = datetime(day.year, day.month, day.day, tzinfo=zone).astimezone(UTC)
    nxt = day + timedelta(days=1)
    end = datetime(nxt.year, nxt.month, nxt.day, tzinfo=zone).astimezone(UTC)
    assert (end - start) == timedelta(minutes=expected), f"{tz} {day} changed length"


def test_spring_forward_at_midnight_starts_at_the_days_first_real_instant() -> None:
    """A local midnight that never happens: the day starts at its first REAL instant.

    America/Santiago 2026-09-06 jumps 00:00 -> 01:00, so that date has no midnight.
    `ZoneInfo` resolves the non-existent time with the pre-transition offset, which
    lands exactly on 01:00 — the first instant the day actually has — and the day
    measures 23 h. Pinned because "ZoneInfo silently picks one" is only benign here
    by luck of which one, and a regression would shift a day's whole window.
    """
    tz, day = "America/Santiago", date(2026, 9, 6)
    start_utc, end_utc = _day_bounds_utc(day, tz)
    first = start_utc.astimezone(ZoneInfo(tz))
    assert (first.hour, first.date()) == (1, day), f"day starts at {first}, not 01:00"
    assert _day_minutes(start_utc, end_utc) == 1380
    assert (end_utc - timedelta(seconds=1)).astimezone(ZoneInfo(tz)).date() == day


def test_consecutive_days_tile_the_timeline_in_every_zone() -> None:
    """The property the half-open bracket buys: no instant is in two days, or in none.

    A closed `[00:00, 23:59:59]` end leaves a one-second hole every day and a whole
    missing/duplicated hour on a midnight transition. Swept over EVERY zone in the tz
    database across a two-year window so a zone nobody thought of cannot break it:
    each day's end must be the next day's start, and no day may collapse or explode.
    """
    for tz in sorted(available_timezones()):
        prev_end: datetime | None = None
        for k in range(730):
            day = date(2026, 1, 1) + timedelta(days=k)
            start_utc, end_utc = _day_bounds_utc(day, tz)
            if prev_end is not None:
                assert start_utc == prev_end, f"{tz} {day}: gap/overlap at the day edge"
            minutes = _day_minutes(start_utc, end_utc)
            assert 1320 <= minutes <= 1560, f"{tz} {day}: {minutes} min is not a day"
            prev_end = end_utc


def test_normal_day_minute_count_is_exactly_the_old_constant() -> None:
    """Every non-DST day still yields 1440 — the semantic the old `range(1440)` had.

    Swept across a full year in a no-DST zone: if the new derivation disagreed with
    1440 on even one ordinary day, the fix would be a regression.
    """
    for k in range(365):
        day = date(2026, 1, 1) + timedelta(days=k)
        assert _day_minutes(*_day_bounds_utc(day, _IST)) == 1440


def test_the_walk_covers_midnight_to_2359_and_stops() -> None:
    """The inclusive [00:00, 23:59] semantic, asserted on the walk's own endpoints.

    The loop steps UTC minutes from the day's first instant; minute 0 must be local
    00:00 and the LAST minute must be local 23:59 — never 00:00 of the next day. On the
    midnight fall-back rows the last 23:59 is the SECOND pass through that wall time,
    which is exactly the minute the old bracket left outside the day.
    """
    days = (
        (_NY, date(2026, 3, 8)),
        (_NY, date(2026, 11, 1)),
        (_IST, date(2026, 6, 1)),
        ("America/Santiago", date(2026, 4, 4)),
        ("Asia/Beirut", date(2026, 10, 24)),
    )
    for tz, day in days:
        start_utc, end_utc = _day_bounds_utc(day, tz)
        n = _day_minutes(start_utc, end_utc)
        local = ZoneInfo(tz)
        first = start_utc.astimezone(local)
        last = (start_utc + timedelta(minutes=n - 1)).astimezone(local)
        one_past = (start_utc + timedelta(minutes=n)).astimezone(local)
        assert (first.hour, first.minute) == (0, 0)
        assert (last.hour, last.minute, last.date()) == (23, 59, day)
        assert one_past.date() == day + timedelta(days=1)  # the next minute has left


def test_the_walk_stops_at_the_last_minute_the_day_actually_has() -> None:
    """The one shape where "last minute" is NOT 23:59: a day with no 23:xx at all.

    America/Nuuk springs forward at 23:00 on 2026-03-28, so the walk's last minute is
    22:59 and the next one is already 2026-03-29 00:00. The old fixed 1440 ran a full
    hour past that edge, billing the next day's first hour to this one.
    """
    tz, day = "America/Nuuk", date(2026, 3, 28)
    start_utc, end_utc = _day_bounds_utc(day, tz)
    n = _day_minutes(start_utc, end_utc)
    local = ZoneInfo(tz)
    last = (start_utc + timedelta(minutes=n - 1)).astimezone(local)
    one_past = (start_utc + timedelta(minutes=n)).astimezone(local)
    assert n == 1380
    assert (last.hour, last.minute, last.date()) == (22, 59, day)
    assert (one_past.hour, one_past.minute, one_past.date()) == (0, 0, day + timedelta(days=1))


# ── the calorie total (seeded DB) ────────────────────────────────────────────

_BMR = 1700.0  # kcal/day; 1 MET == BMR/1440 per minute
_STRIDE_M = 0.7
_STEPS_PER_MIN = 100.0

# Hand-derived METs for the seeded fixture (energy.py + [[energy_expenditure_derivation]]):
#   a stepping minute: 100 steps x 0.7 m = 70 m/min. 70 < 134 (the ACSM walk/run switch),
#     so VO2 = 0.1 x 70 + 3.5 = 10.5 ml/kg/min -> 10.5 / 3.5 = 3.0 MET.
#   a minute within +/-7 min of a stepping minute (NEAT_WINDOW): AWAKE_ACTIVE_MET = 1.55.
#   any other awake, unstepped minute: AWAKE_SEDENTARY_MET = 1.3.
# No sleep_session and no workout are seeded, so nothing is asleep or excluded.
_STEP_MET = 3.0
_ACTIVE_MET = 1.55
_SEDENTARY_MET = 1.3

# One isolated stepping minute lifts a day by, in MET-minutes:
#   the minute itself:            3.0 - 1.3          = 1.7
#   its 14 halo minutes (+/-7):  14 x (1.55 - 1.3)   = 3.5
#                                               total  5.2 MET-minutes
# Written as the arithmetic so a reviewer can check each term; the METs above are
# re-typed from the note/model here, never imported from `energy`, so this stays an
# independent expectation rather than a restatement of the implementation.
_HALO_MINUTES = 14  # +/-NEAT_WINDOW(7) around the stepping minute, excluding itself
_ONE_STEP_MINUTE_MET_GAIN = (_STEP_MET - _SEDENTARY_MET) + _HALO_MINUTES * (
    _ACTIVE_MET - _SEDENTARY_MET
)  # = 1.7 + 3.5 = 5.2


def _kcal(met_minutes: float) -> float:
    """MET-minutes -> kcal at this fixture's BMR anchor (1 MET == BMR/1440 per min)."""
    return met_minutes * _BMR / 1440.0


def _seed_steps(cur, minutes: list[datetime]) -> None:
    """One `steps_per_minute` sample at each given UTC minute; nothing else exists."""
    for table in ("sample", "sleep_session", "workout", "derived_daily"):
        cur.execute(f"DELETE FROM {table}")
    for m in minutes:
        cur.execute(
            "INSERT INTO sample (user_id, ts, metric, value) VALUES (%s, %s, %s, %s)",
            (SENTINEL_USER_ID, m, "steps_per_minute", _STEPS_PER_MIN),
        )


def _local_minute(day: date, tz: str, hour: int, minute: int) -> datetime:
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=ZoneInfo(tz)).astimezone(UTC)


def _tee_fixed(cur, day: date, tz: str) -> float:
    """The day's TEE as the FIXED code integrates it — length from the real bounds."""
    return _tee_met(cur, SENTINEL_USER_ID, *_day_bounds_utc(day, tz), _BMR, _STRIDE_M)


def _tee_hardcoded_1440(cur, day: date, tz: str) -> float:
    """The day's TEE as the BUGGY code integrated it — always 1440 minutes.

    Reproduced by handing `_tee_met` an end bound exactly 1440 minutes after the start:
    `_day_minutes` returns the half-open span, so the walk (and the sample window it
    queries) is precisely the old fixed-length one. On a non-DST day that IS the real
    bracket, which is the whole claim of the regression guard below; the DST tests are
    where it diverges.
    """
    start_utc, _ = _day_bounds_utc(day, tz)
    return _tee_met(
        cur, SENTINEL_USER_ID, start_utc, start_utc + timedelta(minutes=1440), _BMR, _STRIDE_M
    )


@pytest.mark.usefixtures("db")
def test_fall_back_day_counts_its_last_local_hour() -> None:
    """25 h day: steps at 23:30 local are INSIDE the day and must be counted.

    The old walk stopped at UTC-minute 1439 = 22:59 local here, so a real evening walk
    vanished from the total entirely.
    """
    day = date(2026, 11, 1)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        at_2330 = _local_minute(day, _NY, 23, 30)
        _seed_steps(cur, [at_2330])
        fixed_with = _tee_fixed(cur, day, _NY)
        buggy_with = _tee_hardcoded_1440(cur, day, _NY)
        _seed_steps(cur, [])
        fixed_empty = _tee_fixed(cur, day, _NY)
        buggy_empty = _tee_hardcoded_1440(cur, day, _NY)

    # THE BUG: the old walk cannot see the 23:30 steps at all — stepping or not, it
    # returns the same number.
    assert buggy_with == buggy_empty
    # THE FIX: the stepping minute lands, worth exactly its hand-derived 5.2 MET-min.
    assert fixed_with - fixed_empty == pytest.approx(_kcal(_ONE_STEP_MINUTE_MET_GAIN), abs=1e-9)
    # A quiet 25 h day is 1500 sedentary minutes — the old walk under-counted by 60.
    assert fixed_empty == pytest.approx(_kcal(1500 * _SEDENTARY_MET), abs=1e-9)
    assert buggy_empty == pytest.approx(_kcal(1440 * _SEDENTARY_MET), abs=1e-9)


@pytest.mark.usefixtures("db")
def test_spring_forward_day_stops_at_its_own_midnight() -> None:
    """23 h day: steps at 00:30 local on the NEXT day must NOT be billed to this one.

    The old walk ran to UTC-minute 1439 = 00:59 of March 9 local, so the next
    morning's first hour was counted twice — once here, once on its own day.
    """
    day = date(2026, 3, 8)
    next_morning = _local_minute(day + timedelta(days=1), _NY, 0, 30)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed_steps(cur, [next_morning])
        fixed_with = _tee_fixed(cur, day, _NY)
        buggy_with = _tee_hardcoded_1440(cur, day, _NY)
        _seed_steps(cur, [])
        fixed_empty = _tee_fixed(cur, day, _NY)

    # THE BUG: March 9's steps moved March 8's total.
    assert buggy_with > _kcal(1380 * _SEDENTARY_MET)
    # THE FIX: March 8 is untouched by March 9's steps — identical to a stepless day.
    assert fixed_with == pytest.approx(fixed_empty, abs=1e-9)
    # A quiet 23 h day is 1380 sedentary minutes.
    assert fixed_with == pytest.approx(_kcal(1380 * _SEDENTARY_MET), abs=1e-9)


# The regression guard's hand-derived expectation. The fixture seeds a stepping minute
# at :00 and :05 of five hours; within a pair the NEAT haloes overlap, so the non-step
# minutes within +/-7 of either are :53 of the previous hour through :12 — 20 minutes,
# less the 2 stepping minutes = 18 active. Over the five pairs:
_GUARD_STEP_MINUTES = 10  # 5 hours x {:00, :05}
_GUARD_ACTIVE_MINUTES = 90  # 5 hours x 18 halo minutes
_GUARD_MET_MINUTES = (
    _GUARD_STEP_MINUTES * _STEP_MET
    + _GUARD_ACTIVE_MINUTES * _ACTIVE_MET
    + (1440 - _GUARD_STEP_MINUTES - _GUARD_ACTIVE_MINUTES) * _SEDENTARY_MET
)  # = 30 + 139.5 + 1742 = 1911.5


@pytest.mark.usefixtures("db")
@pytest.mark.parametrize("tz", [_IST, _NY])  # a no-DST zone AND a DST zone, off-transition
def test_non_dst_day_calories_are_the_hand_derived_1440_minute_total(tz: str) -> None:
    """THE REGRESSION GUARD: an ordinary day is still exactly 1440 minutes of MET.

    Anchored on hand-derived MET-minutes, NOT on a second call to the walk. Before #58
    this compared the fixed walk against the old hardcoded-1440 one; the half-open
    bracket makes those two calls literally identical on a 24 h day, so the comparison
    became a tautology that would have guarded nothing. The arithmetic here is re-typed
    from the model instead, so it stays an independent expectation — and it still pins
    the same claim: a normal day's calories must not move by a single minute.
    """
    day = date(2026, 6, 15)
    with tenant_transaction(SENTINEL_USER_ID) as cur:
        _seed_steps(cur, [_local_minute(day, tz, h, m) for h in (7, 8, 12, 19, 23) for m in (0, 5)])
        new = _tee_fixed(cur, day, tz)
        old = _tee_hardcoded_1440(cur, day, tz)
    assert _day_minutes(*_day_bounds_utc(day, tz)) == 1440
    assert new == pytest.approx(_kcal(_GUARD_MET_MINUTES), abs=1e-9)
    assert new == old  # and the old fixed-length walk agrees, to the last float bit
