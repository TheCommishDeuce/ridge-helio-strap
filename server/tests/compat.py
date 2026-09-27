"""The old test suite's database helpers, re-created for the ported derive tests.

Those tests (copied from healthee@049c9ad) call `tenant_transaction(SENTINEL_USER_ID)`
inline. This keeps their bodies untouched: same name, same commit-on-success semantics,
backed by the session's test database. `conftest.py` empties the data tables before each
`integration`-marked test.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import date, datetime
from uuid import UUID
from zoneinfo import ZoneInfo

import psycopg

from tests.derive._seed import OWNER

SENTINEL_USER_ID: UUID = OWNER
SENTINEL_TZ: str = "Asia/Kolkata"

DSN: str | None = None  # set by conftest once the test database exists


@contextmanager
def tenant_transaction(user_id: UUID) -> Iterator[psycopg.Cursor]:
    assert DSN, "integration test ran without the test database fixture"
    with psycopg.connect(DSN) as conn, conn.cursor() as cur:
        yield cur


def user_today(tz: str) -> date:
    """Today's date in the owner's timezone."""
    return datetime.now(tz=ZoneInfo(tz)).date()
