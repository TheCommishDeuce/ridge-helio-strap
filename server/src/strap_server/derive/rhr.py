"""Resting heart rate for one sleep window.

RHR = the lowest sustained at-rest heart rate during sleep: the minimum over
5-minute rolling averages of HR inside the session window. Ported verbatim from
legacy v2 ``derive_rhr``. Knowledge notes: ``resting_hr_health_marker`` (Aune
2017 — RHR predicts CV/all-cause mortality), ``resting_heart_rate``.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from strap_server.derive._common import Cur
from strap_server.derive.hr_validity import HR_VALID_BOUNDS, HR_VALID_SQL


def derive_rhr(
    cur: Cur, user_id: UUID, start_ts: datetime, end_ts: datetime
) -> tuple[float | None, int]:
    """Min of 5-min rolling-average HR in the sleep window.

    Returns (rhr_bpm, n_samples). Only 5-min buckets with >=3 HR samples count, and
    HR is bounded by ``derive.hr_validity`` — the ONE plausibility definition, shared
    with every other HR reader (an engineering artefact filter, not a research
    constant). (None, 0) when the window holds no qualifying HR data.
    Knowledge: [[resting_heart_rate]].
    """
    cur.execute(
        f"""
        SELECT MIN(bucket_avg)::float, SUM(n)::int
        FROM (
          SELECT AVG(value) AS bucket_avg, COUNT(*) AS n
          FROM sample
          WHERE user_id = %s AND metric='hr' AND {HR_VALID_SQL}
            AND ts >= %s AND ts < %s
          GROUP BY time_bucket('5 minutes', ts)
          HAVING COUNT(*) >= 3
        ) buckets
        """,
        (user_id, *HR_VALID_BOUNDS, start_ts, end_ts),
    )
    row = cur.fetchone()
    if not row or row[0] is None:
        return None, 0
    return float(row[0]), int(row[1])
