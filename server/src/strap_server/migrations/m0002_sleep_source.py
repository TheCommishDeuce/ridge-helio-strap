"""0002 — which source a sleep session came from.

Until the Zepp cloud backfill, the strap over BLE was the only writer and the source was a
constant (`derive/sleep_score.py`). Now nights before the first strap sync can come from
the Zepp cloud, so each row says which.
"""

STATEMENTS: tuple[str, ...] = (
    (
        "ALTER TABLE sleep_session ADD COLUMN source TEXT NOT NULL DEFAULT 'strap_ble' "
        "CHECK (source IN ('strap_ble', 'zepp_cloud'))"
    ),
)
