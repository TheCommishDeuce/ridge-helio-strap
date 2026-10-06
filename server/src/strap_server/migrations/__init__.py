"""Ordered migrations. Append only; never edit one that has been applied anywhere."""

from strap_server.migrations import m0001_initial, m0002_sleep_source, m0003_zone_change

MIGRATIONS: list[tuple[str, tuple[str, ...]]] = [
    ("0001_initial", m0001_initial.STATEMENTS),
    ("0002_sleep_source", m0002_sleep_source.STATEMENTS),
    ("0003_zone_change", m0003_zone_change.STATEMENTS),
]
