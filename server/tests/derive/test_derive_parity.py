"""Golden parity: this derive package reproduces the old system's science exactly.

`_seed.py` and `fixtures/derive/expected_daily.json` are copied unchanged from
healthee@049c9ad, where the fixture was produced by the old, accuracy-gated derive over
exactly this seed (its documented divergences from the legacy v1 code — Jurca coding, MVPA
MET-equivalence, provenance flags — are listed in that repo's test_derive_parity.py).
172 rows, 23 metrics, value-for-value and flag-for-flag.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from strap_server.derive import derive_day, derive_night
from tests.derive import _seed

pytestmark = pytest.mark.db

_FIXTURE = Path(__file__).resolve().parent.parent / "fixtures" / "derive" / "expected_daily.json"
_TOL = 1e-9  # values are rounded to 4 dp by the upsert, so this is effectively exact
_TZ = "Asia/Kolkata"


def _num_eq(a: object, b: object) -> bool:
    return isinstance(a, int | float) and isinstance(b, int | float) and abs(a - b) <= _TOL


def _deep_eq(a: object, b: object) -> bool:
    if _num_eq(a, b):
        return True
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_deep_eq(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_deep_eq(x, y) for x, y in zip(a, b, strict=True))
    return a == b


def test_derive_matches_the_golden_fixture(cur) -> None:
    _seed.seed(cur)
    for start, end in _seed.nights():
        derive_night(cur, _seed.OWNER, _TZ, start, end)
    for day in _seed.DAYS:
        derive_day(cur, _seed.OWNER, _TZ, day)
    cur.execute("SELECT day, metric, value, flags FROM derived_daily ORDER BY day, metric")
    actual = {(r[0].isoformat(), r[1]): {"value": r[2], "flags": r[3]} for r in cur.fetchall()}
    expected = {(r["day"], r["metric"]): r for r in json.loads(_FIXTURE.read_text())}

    assert actual.keys() == expected.keys(), (
        f"metric set differs: only-new={sorted(actual.keys() - expected.keys())[:5]} "
        f"only-golden={sorted(expected.keys() - actual.keys())[:5]}"
    )
    mismatches = [
        (k, e, actual[k])
        for k, e in expected.items()
        if not _num_eq(actual[k]["value"], e["value"]) or not _deep_eq(actual[k]["flags"], e["flags"])
    ]
    assert not mismatches, "derived rows differ from the golden fixture:\n" + "\n".join(
        f"  {k}: golden={e} new={a}" for k, e, a in mismatches[:8]
    )
    assert len(expected) == 172


def test_rederive_over_the_seed_reproduces_the_golden_fixture(db, test_dsn) -> None:
    """The repair/import path must agree with the live path: same rows, from raw data alone."""
    import psycopg

    from strap_server.rederive import rederive

    with psycopg.connect(test_dsn) as conn, conn.cursor() as c:
        _seed.seed(c)
    rederive(test_dsn, _seed.OWNER, log=lambda _: None)
    with psycopg.connect(test_dsn) as conn:
        rows = conn.execute("SELECT day, metric, value, flags FROM derived_daily ORDER BY day, metric").fetchall()
    actual = {(r[0].isoformat(), r[1]): {"value": r[2], "flags": r[3]} for r in rows}
    expected = {(r["day"], r["metric"]): r for r in json.loads(_FIXTURE.read_text())}
    # Ingest also derives the day a night STARTS on (2026-02-28 here), which the fixture's
    # generator did not; every row on the fixture's own days must match exactly.
    assert {k for k in actual if k[0] in {d.isoformat() for d in _seed.DAYS}} == expected.keys()
    assert all(
        _num_eq(actual[k]["value"], e["value"]) and _deep_eq(actual[k]["flags"], e["flags"]) for k, e in expected.items()
    )
