"""`derive_batch` derives every night BEFORE any day — the order, asserted directly.

`tests/integration/test_ingest_derive_chain.py` proves the CONSEQUENCE of the order
against a real database (a day metric carrying a value only a same-transaction night
could have produced). This file proves the order itself, with no DB, so the reason a
reversal fails is stated in one line rather than inferred from a wrong resting HR.

Both are worth having. The integration test would still pass if some future refactor
happened to make a day metric independent of its night; this one would not.
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, date, datetime
from typing import Any
from uuid import UUID

import pytest

from strap_server.derive import orchestrator

_USER = UUID("00000000-0000-0000-0000-000000000000")
_TZ = "Asia/Kolkata"

_NIGHTS = [
    (datetime(2026, 6, 18, 22, tzinfo=UTC), datetime(2026, 6, 19, 5, tzinfo=UTC)),
    (datetime(2026, 6, 19, 22, tzinfo=UTC), datetime(2026, 6, 20, 5, tzinfo=UTC)),
]
_DAYS = [date(2026, 6, 19), date(2026, 6, 20)]


class _FakeConnection:
    """The one thing `derive_batch` asks of a connection: a context-managed cursor."""

    def __init__(self) -> None:
        self.cursors_opened = 0

    @contextmanager
    def cursor(self) -> Any:
        self.cursors_opened += 1
        yield object()


@pytest.fixture
def calls(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, object]]:
    """Record every night/day derive `derive_batch` makes, in order.

    Patched on the ORCHESTRATOR module (where `derive_batch` resolves the names), not on
    the packages the real functions live in — patching the latter would leave the module
    globals bound to the originals and record nothing.
    """
    recorded: list[tuple[str, object]] = []

    def _night(_cur: object, _uid: UUID, _tz: str, start: datetime, end: datetime) -> dict:
        recorded.append(("night", (start, end)))
        return {}

    def _day(_cur: object, _uid: UUID, _tz: str, day: date) -> dict:
        recorded.append(("day", day))
        return {}

    monkeypatch.setattr(orchestrator, "derive_night", _night)
    monkeypatch.setattr(orchestrator, "derive_day", _day)
    return recorded


def test_every_night_is_derived_before_any_day(calls: list[tuple[str, object]]) -> None:
    """The dependency order: `derive_day` reads what `derive_night` writes."""
    conn = _FakeConnection()
    orchestrator.derive_batch(conn, _USER, _TZ, _NIGHTS, _DAYS)  # type: ignore[arg-type]

    kinds = [kind for kind, _ in calls]
    assert kinds == ["night", "night", "day", "day"]


def test_each_night_and_day_is_derived_exactly_once(calls: list[tuple[str, object]]) -> None:
    """No batch member is skipped and none is derived twice."""
    conn = _FakeConnection()
    orchestrator.derive_batch(conn, _USER, _TZ, _NIGHTS, _DAYS)  # type: ignore[arg-type]

    assert [arg for kind, arg in calls if kind == "night"] == _NIGHTS
    assert [arg for kind, arg in calls if kind == "day"] == _DAYS
    assert conn.cursors_opened == 1  # one cursor for the batch, not one per member


def test_a_batch_with_no_nights_still_derives_its_days(calls: list[tuple[str, object]]) -> None:
    """A push with no fresh sleep is not an error — the day pass runs alone."""
    conn = _FakeConnection()
    orchestrator.derive_batch(conn, _USER, _TZ, [], _DAYS)  # type: ignore[arg-type]

    assert [kind for kind, _ in calls] == ["day", "day"]
