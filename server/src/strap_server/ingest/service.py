"""One push: store it, in one transaction. Nothing is derived here (D34): the phone works
every number out itself, and the server keeps what it was sent as the phone's backup.
"""

from __future__ import annotations

from uuid import UUID

from psycopg import Connection

from strap_server.ingest import upsert
from strap_server.ingest.models import IngestPayload, IngestSummary
from strap_server.log import get_logger
from strap_server.zones import Zones

log = get_logger(__name__)


def ingest(conn: Connection, user_id: UUID, payload: IngestPayload) -> IngestSummary:
    fill = payload.source == "zepp_cloud"
    workouts = [] if fill else payload.workouts  # the backfill sends none; cloud sport codes are not the strap's
    with conn.cursor() as cur:
        # Zone changes first (D31): a counter reading is filed under the day it was read in.
        upsert.upsert_zone_changes(cur, user_id, payload.zones)
        tz = Zones.load(cur, user_id)
        stored, dropped = upsert.upsert_samples(cur, user_id, payload.samples, fill_only=fill)
        sleep = upsert.upsert_sleep(cur, user_id, payload.sleep, fill_only=fill)
        upsert.upsert_workouts(cur, user_id, workouts)
        total_days = upsert.upsert_daily_totals(cur, user_id, tz, payload.daily_totals, fill_only=fill)
    summary = IngestSummary(
        samples_stored=len(stored), samples_dropped=dropped, sleep=len(sleep), workouts=len(workouts), daily_totals=len(total_days),
    )
    log.info("ingest %s", summary.model_dump())
    return summary
