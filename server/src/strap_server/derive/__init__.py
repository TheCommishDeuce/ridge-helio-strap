"""The derive package — the accuracy-gated science layer over the sample window.

Pure(ish) derivations that read the single-source `sample`/`sleep_session`/`workout`
tables and materialize `derived_daily`. Ported verbatim from the legacy v2 science
module, split by responsibility (each metric family in its own file). The public
contract:

    derive_night(cur, s, e)              -> dict  # per-night metrics for one session
    derive_day(cur, day)                 -> dict  # all daily metrics for one day
    derive_batch(conn, nights, days)     -> None  # BOTH, in dependency order, one txn
    stored_nights(cur, tz, since)        -> list  # the repair path's night windows

`derive_batch` is the ONLY transactional entry point, and that is deliberate: a
days-only one existed, ingest called it, and `derive_night` therefore never ran in the
running system (#107). See `orchestrator` for the full story.
"""

from __future__ import annotations

from strap_server.derive.orchestrator import (
    SleepWindow,
    derive_batch,
    derive_day,
    derive_night,
    stored_nights,
)

__all__ = ["SleepWindow", "derive_batch", "derive_day", "derive_night", "stored_nights"]
