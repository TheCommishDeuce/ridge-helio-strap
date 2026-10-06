"""The owner's timezone over time (D31): what a day is when the owner travels."""

from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from strap_server.derive._common import _day_bounds_utc
from strap_server.zones import Zones

BERLIN = "Europe/Berlin"  # +2 in October 2026
DUBAI = "Asia/Dubai"  # +4
NEW_YORK = "America/New_York"  # -4


def utc(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=UTC)


def test_no_change_is_the_old_single_zone_day():
    z = Zones(BERLIN)
    for day in (date(2026, 3, 29), date(2026, 10, 6), date(2026, 10, 25)):  # incl. both DST days
        assert z.bounds(day) == _day_bounds_utc(day, BERLIN)
    ts = utc("2026-10-05T22:30:00")  # 00:30 Berlin on the 6th
    assert z.date_of(ts) == date(2026, 10, 6)


def test_flying_east_shortens_the_day_left():
    # Landed in Dubai at 15:00 UTC on 5 Oct (17:00 Berlin, 19:00 Dubai).
    z = Zones(BERLIN, [(utc("2026-10-05T15:00:00"), DUBAI)])
    start5, end5 = z.bounds(date(2026, 10, 5))
    assert start5 == utc("2026-10-04T22:00:00")  # Berlin midnight
    assert end5 == utc("2026-10-05T20:00:00")  # Dubai midnight
    assert end5 - start5 == timedelta(hours=22)
    assert z.bounds(date(2026, 10, 6)) == (utc("2026-10-05T20:00:00"), utc("2026-10-06T20:00:00"))
    assert z.zone_of(date(2026, 10, 5)) == BERLIN
    assert z.zone_of(date(2026, 10, 6)) == DUBAI


def test_flying_west_lengthens_it():
    z = Zones(DUBAI, [(utc("2026-10-05T10:00:00"), BERLIN)])
    start, end = z.bounds(date(2026, 10, 5))
    assert start == utc("2026-10-04T20:00:00")
    assert end == utc("2026-10-05T22:00:00")
    assert end - start == timedelta(hours=26)


def test_days_tile_with_no_gap_or_overlap():
    z = Zones(BERLIN, [(utc("2026-10-05T15:00:00"), DUBAI), (utc("2026-10-09T06:00:00"), NEW_YORK), (utc("2026-10-12T23:00:00"), BERLIN)])
    day = date(2026, 10, 1)
    for _ in range(20):
        _, end = z.bounds(day)
        nxt, _ = z.bounds(day + timedelta(days=1))
        assert end == nxt and end > z.start(day)
        day += timedelta(days=1)


def test_a_skipped_midnight_starts_the_day_at_the_change():
    # At 21:00 UTC it is 23:00 in Berlin; the change moves to Dubai, where it is already
    # 01:00 on the 6th — midnight of the 6th never happened in either zone.
    z = Zones(BERLIN, [(utc("2026-10-05T21:00:00"), DUBAI)])
    assert z.start(date(2026, 10, 6)) == utc("2026-10-05T21:00:00")
    assert z.date_of(utc("2026-10-05T20:59:00")) == date(2026, 10, 5)
    assert z.date_of(utc("2026-10-05T21:00:00")) == date(2026, 10, 6)


def test_date_of_agrees_with_bounds():
    z = Zones(BERLIN, [(utc("2026-10-05T15:00:00"), DUBAI), (utc("2026-10-09T06:00:00"), NEW_YORK)])
    t = utc("2026-10-03T00:00:00")
    while t < utc("2026-10-12T00:00:00"):
        d = z.date_of(t)
        start, end = z.bounds(d)
        assert start <= t < end
        t += timedelta(minutes=37)


def test_repeats_are_not_changes():
    z = Zones(BERLIN, [(utc("2026-10-01T00:00:00"), BERLIN), (utc("2026-10-02T00:00:00"), DUBAI), (utc("2026-10-03T00:00:00"), DUBAI)])
    assert [n for _, n in z.changes] == [DUBAI]


def test_edges_are_starts_through_the_last_day():
    z = Zones(DUBAI)
    edges = z.starts(date(2026, 10, 5), date(2026, 10, 6))
    assert edges == [datetime(2026, 10, d, tzinfo=ZoneInfo(DUBAI)).astimezone(UTC) for d in (5, 6, 7)]
