"""The owner's workout changes (D43): the server keeps the phone's copy for restore."""

from __future__ import annotations

from datetime import UTC, datetime

import psycopg
import pytest
from pydantic import ValidationError

from strap_server import backup, journal, sessions
from tests import seed as _seed

pytestmark = pytest.mark.db
T = datetime(2026, 3, 5, 17, 0, tzinfo=UTC)


@pytest.fixture
def seeded(db, test_dsn):
    with psycopg.connect(test_dsn) as conn, conn.cursor() as cur:
        _seed.seed(cur)
    return test_dsn


def test_changes_round_trip_through_the_backup_and_delete(seeded) -> None:
    with psycopg.connect(seeded) as conn:
        a = sessions.add(
            conn, _seed.OWNER, sessions.SessionIn(action="add", start=T, end=T.replace(hour=19), sport=70)
        )
        e = sessions.add(
            conn,
            _seed.OWNER,
            sessions.SessionIn(
                action="edit",
                start=T.replace(hour=20),
                end=T.replace(hour=21),
                sport=4,
                target=T.replace(hour=20, minute=10),
            ),
        )
        out = backup.records(conn.cursor(), _seed.OWNER, "UTC")
        got = {s["id"]: s for s in out["sessions"]}
        assert got[a["id"]] == {
            "id": a["id"],
            "action": "add",
            "start": int(T.timestamp() * 1000),
            "end": int(T.replace(hour=19).timestamp() * 1000),
            "sport": 70,
            "target": None,
        }
        assert got[e["id"]]["target"] == int(T.replace(hour=20, minute=10).timestamp() * 1000)
        # Not journal entries: the journal's list and backup never show them.
        assert all(j["id"] not in got for j in out["journal"])
        assert not journal.entries(conn, _seed.OWNER, T.replace(hour=0), T.replace(hour=23))
        assert sessions.delete(conn, _seed.OWNER, a["id"])
        assert not sessions.delete(conn, _seed.OWNER, a["id"])
        assert not journal.delete(conn, _seed.OWNER, e["id"])


def test_implausible_changes_are_refused() -> None:
    for bad in (
        {"action": "add", "start": T, "end": T, "sport": 1},  # empty
        {"action": "add", "start": T, "end": T.replace(day=7), "sport": 1},  # over a day
        {"action": "edit", "start": T, "end": T.replace(hour=18), "sport": 1},  # an edit names its workout
        {
            "action": "add",
            "start": T,
            "end": T.replace(hour=18),
            "sport": 1,
            "target": T,
        },  # an added session doesn't
        {"action": "add", "start": datetime(2026, 3, 5, 17), "end": datetime(2026, 3, 5, 18), "sport": 1},  # noqa: DTZ001 (no offset on purpose)
    ):
        with pytest.raises(ValidationError):
            sessions.SessionIn(**bad)
