"""Ordered migrations. Append only; never edit one that has been applied anywhere."""

from strap_server.migrations import m0001_initial, m0002_sleep_source

MIGRATIONS: list[tuple[str, tuple[str, ...]]] = [
    ("0001_initial", m0001_initial.STATEMENTS),
    ("0002_sleep_source", m0002_sleep_source.STATEMENTS),
]
