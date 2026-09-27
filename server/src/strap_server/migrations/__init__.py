"""Ordered migrations. Append only; never edit one that has been applied anywhere."""

from strap_server.migrations import m0001_initial

MIGRATIONS: list[tuple[str, tuple[str, ...]]] = [
    ("0001_initial", m0001_initial.STATEMENTS),
]
