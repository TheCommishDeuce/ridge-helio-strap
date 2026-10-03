"""One push: store it, then re-derive exactly what it touched — in the same transaction.

Affected work follows the old server's rules (healthee@049c9ad `ingest/service.py`):
every local day that got a sample, workout, daily total or sleep session (naps included —
they change that day's calories and load); every pushed main night; every stored night a
late vital landed inside; and the wake/start days of those nights. `derive_batch` owns
the order (nights before days).
"""

from __future__ import annotations

from datetime import date
from uuid import UUID
from zoneinfo import ZoneInfo

from psycopg import Connection

from strap_server.derive import derive_batch
from strap_server.derive.illness import derive_illness_flag
from strap_server.ingest import upsert
from strap_server.ingest.models import IngestPayload, IngestSummary
from strap_server.log import get_logger

log = get_logger(__name__)


def ingest(conn: Connection, user_id: UUID, tz: str, payload: IngestPayload) -> IngestSummary:
    fill = payload.source == "zepp_cloud"
    workouts = [] if fill else payload.workouts  # the backfill sends none; cloud sport codes are not the strap's
    with conn.cursor() as cur:
        stored, dropped = upsert.upsert_samples(cur, user_id, payload.samples, fill_only=fill)
        sleep = upsert.upsert_sleep(cur, user_id, payload.sleep, fill_only=fill)
        upsert.upsert_workouts(cur, user_id, workouts)
        total_days = upsert.upsert_daily_totals(cur, user_id, tz, payload.daily_totals, fill_only=fill)
        late = upsert.nights_containing(cur, user_id, [ts for ts, m in stored if m in upsert.NIGHT_INPUTS])

    pushed = [(upsert.epoch_to_utc(s.start_ts), upsert.epoch_to_utc(s.end_ts)) for s in sleep if s.kind == "main"]
    nights = sorted(set(pushed) | set(late))
    zone = ZoneInfo(tz)
    days: set[date] = {ts.astimezone(zone).date() for ts, _ in stored}
    days |= {upsert.local_date(w.start_ts, tz) for w in workouts}
    days |= set(total_days)
    for s in sleep:
        days |= {upsert.local_date(s.start_ts, tz), upsert.local_date(s.end_ts, tz)}
    for start, end in nights:
        days |= {start.astimezone(zone).date(), end.astimezone(zone).date()}

    derive_batch(conn, user_id, tz, nights, sorted(days))
    # The illness flag reads the nights just derived; it is a safety input (recovery D7), so
    # it runs on every push rather than in a daily job that could silently not run.
    with conn.cursor() as cur:
        for day in sorted(days):
            derive_illness_flag(cur, user_id, tz, day)
    summary = IngestSummary(
        samples_stored=len(stored),
        samples_dropped=dropped,
        sleep=len(sleep),
        workouts=len(workouts),
        daily_totals=len(total_days),
        nights_derived=len(nights),
        days_derived=len(days),
    )
    log.info("ingest %s", summary.model_dump())
    return summary
