"""Schema migrations: committed Python modules, one SQL statement per `execute`.

psycopg3 runs one statement per call, and a pasted multi-statement script is how the old
project broke production once. Each migration is an ordered tuple of statements, applied in
one transaction and recorded in `schema_migrations`.

    uv run python -m strap_server.migrate            # apply pending
    uv run python -m strap_server.migrate --check    # list pending, exit 1 if any
"""

from __future__ import annotations

import sys

import psycopg

from strap_server.db import connection
from strap_server.migrations import MIGRATIONS


def pending(conn: psycopg.Connection) -> list[str]:
    conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations (id TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())")
    done = {row[0] for row in conn.execute("SELECT id FROM schema_migrations")}
    return [mid for mid, _ in MIGRATIONS if mid not in done]


def apply(conninfo: str | None = None) -> list[str]:
    """Applies every pending migration in order; returns the ids applied."""
    applied = []
    with connection(conninfo) as conn:
        todo = set(pending(conn))
        for mid, statements in MIGRATIONS:
            if mid not in todo:
                continue
            for statement in statements:
                conn.execute(statement)
            conn.execute("INSERT INTO schema_migrations (id) VALUES (%s)", (mid,))
            applied.append(mid)
    return applied


def main() -> int:
    if "--check" in sys.argv:
        with connection() as conn:
            todo = pending(conn)
        print("pending: " + (", ".join(todo) if todo else "none"))
        return 1 if todo else 0
    print("applied: " + (", ".join(apply()) or "nothing (up to date)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
