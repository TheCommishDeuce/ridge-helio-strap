"""The owner's timezone over time, and what a "day" is under it (D31).

The owner travels, so one fixed zone cuts the wrong days: in a zone two hours east of the
server's, every day began at 02:00. The phone reports each change of its zone (the instant
and the new IANA name); before the first report the owner's ``app_user.timezone`` holds.

**A day starts at local midnight wherever the owner is at that moment.** Its end is the
next day's start, so days tile the timeline with no gap and no overlap whatever the travel:
flying east shortens the day being left (to 22 h for a two-hour jump), flying west
lengthens it. When a flight skips a midnight entirely (the change
jumps past it), the day starts at the change itself.

With no change recorded this is exactly the old single-zone definition — the same midnight
construction as before, so every derived number is unchanged (the parity tests hold it).

Everything that once took ``tz: str`` takes a :data:`ZoneLike`: a plain zone name still
works (tests, one-zone callers), a :class:`Zones` carries the history.
"""

from __future__ import annotations

from bisect import bisect_right
from datetime import UTC, date, datetime, timedelta
from uuid import UUID
from zoneinfo import ZoneInfo


class Zones:
    def __init__(self, base: str, changes: list[tuple[datetime, str]] | None = None):
        self.base = base
        # (instant, zone) sorted; consecutive repeats of one zone are not changes.
        merged: list[tuple[datetime, str]] = []
        for at, name in sorted(changes or [], key=lambda c: c[0]):
            if name != (merged[-1][1] if merged else base):
                merged.append((at.astimezone(UTC), name))
        self.changes = merged
        self._at = [at for at, _ in merged]
        self._starts: dict[date, datetime] = {}

    @classmethod
    def load(cls, cur, user_id: UUID) -> Zones:
        """The owner's base zone and every recorded change."""
        cur.execute("SELECT timezone FROM app_user WHERE id = %s", (user_id,))
        row = cur.fetchone()
        base = row[0] if row else "UTC"
        cur.execute("SELECT since, timezone FROM zone_change WHERE user_id = %s ORDER BY since", (user_id,))
        return cls(base, list(cur.fetchall()))

    def zone_at(self, instant: datetime) -> str:
        """The zone in force at `instant`."""
        i = bisect_right(self._at, instant)
        return self.base if i == 0 else self.changes[i - 1][1]

    def zone_of(self, day: date) -> str:
        """The zone the day began in: the one its times are told in."""
        return self.zone_at(self.start(day))

    def start(self, day: date) -> datetime:
        """When `day` begins, in UTC (see the module docstring)."""
        if (hit := self._starts.get(day)) is not None:
            return hit
        periods = [(None, self.base)] + self.changes
        found: list[datetime] = []
        for i, (since, name) in enumerate(periods):
            # The same construction the single-zone day always used (fold=0), so a zone's
            # DST quirks at midnight resolve exactly as before.
            midnight = datetime(day.year, day.month, day.day, tzinfo=ZoneInfo(name)).astimezone(UTC)
            until = periods[i + 1][0] if i + 1 < len(periods) else None
            if (since is None or midnight >= since) and (until is None or midnight < until):
                found.append(midnight)
        if found:
            result = min(found)
        else:
            # The midnight was jumped over: the day starts at the change that crossed it.
            result = next(
                (at for k, (at, name) in enumerate(self.changes)
                 if at.astimezone(ZoneInfo(name)).date() >= day
                 and at.astimezone(ZoneInfo(periods[k][1])).date() < day),
                datetime(day.year, day.month, day.day, tzinfo=ZoneInfo(self.zone_at(datetime.now(UTC)))).astimezone(UTC),
            )
        self._starts[day] = result
        return result

    def bounds(self, day: date) -> tuple[datetime, datetime]:
        """The day as a half-open UTC range ``[start, next start)``."""
        start = self.start(day)
        return start, max(start, self.start(day + timedelta(days=1)))

    def date_of(self, ts: datetime) -> date:
        """The day `ts` falls in."""
        d = ts.astimezone(ZoneInfo(self.zone_at(ts))).date()
        if not self.changes:
            return d
        while ts < self.start(d):
            d -= timedelta(days=1)
        while ts >= self.start(d + timedelta(days=1)):
            d += timedelta(days=1)
        return d

    def starts(self, first: date, last: date) -> list[datetime]:
        """Every day's start from `first` through the end of `last` (len = days + 1): the
        edges SQL buckets rows into with ``width_bucket(ts, edges)``."""
        return [self.start(first + timedelta(days=i)) for i in range((last - first).days + 2)]


ZoneLike = Zones | str


def as_zones(tz: ZoneLike) -> Zones:
    """A zone name means that zone throughout."""
    return tz if isinstance(tz, Zones) else Zones(tz)


def valid_zone(name: str) -> bool:
    try:
        ZoneInfo(name)
    except (ValueError, KeyError):
        return False
    return True
