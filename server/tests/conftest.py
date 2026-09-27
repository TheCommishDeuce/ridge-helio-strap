"""Test database: a fresh `strap_test` database per session, one rolled-back transaction per test.

Point STRAP_TEST_DSN at a TimescaleDB admin connection (default: the local docker one on
127.0.0.1:5598). Tests marked `db` skip when it is unreachable, so pure tests always run.
"""

from __future__ import annotations

import os
from collections.abc import Iterator

import psycopg
import pytest

from strap_server import migrate
from tests.derive._seed import OWNER

ADMIN_DSN = os.environ.get(
    "STRAP_TEST_DSN", "host=127.0.0.1 port=5598 dbname=strap user=strap password=local-test-only"
)
TEST_DB = "strap_test"


def _dsn(dbname: str) -> str:
    parts = dict(p.split("=", 1) for p in ADMIN_DSN.split())
    parts["dbname"] = dbname
    return " ".join(f"{k}={v}" for k, v in parts.items())


@pytest.fixture(scope="session")
def test_dsn() -> Iterator[str]:
    try:
        admin = psycopg.connect(ADMIN_DSN, autocommit=True, connect_timeout=3)
    except psycopg.OperationalError as e:
        pytest.skip(f"no test database at STRAP_TEST_DSN ({e.__class__.__name__})")
    with admin:
        admin.execute(f"DROP DATABASE IF EXISTS {TEST_DB} WITH (FORCE)")
        admin.execute(f"CREATE DATABASE {TEST_DB}")
    dsn = _dsn(TEST_DB)
    migrate.apply(dsn)
    with psycopg.connect(dsn) as conn:
        conn.execute("INSERT INTO app_user (id, timezone) VALUES (%s, 'Asia/Kolkata')", (OWNER,))
    yield dsn


@pytest.fixture
def cur(test_dsn: str) -> Iterator[psycopg.Cursor]:
    """A cursor inside a transaction that is always rolled back."""
    with psycopg.connect(test_dsn) as conn:
        with conn.cursor() as c:
            yield c
        conn.rollback()


_DATA_TABLES = (
    "sample", "sleep_session", "workout", "derived_daily", "device_daily_total",
    "profile", "weight_log", "manual_entry", "illness_flag",
)


@pytest.fixture
def db(test_dsn: str) -> None:
    """The test database with empty data tables, for the ported tests' helpers."""
    from tests import compat

    compat.DSN = test_dsn
    with psycopg.connect(test_dsn) as conn:
        conn.execute("TRUNCATE " + ", ".join(_DATA_TABLES))


@pytest.fixture(autouse=True)
def _integration_db(request: pytest.FixtureRequest) -> None:
    """Ported `integration` tests get `db` even when they do not ask for it by name."""
    if request.node.get_closest_marker("integration") is not None:
        request.getfixturevalue("db")
