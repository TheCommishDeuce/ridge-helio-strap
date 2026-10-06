"""0003 — the owner's timezone over time (D31).

The phone reports every change of its zone: from `since` on, days are cut at midnight in
`timezone`. Before the first row, ``app_user.timezone`` holds. See ``strap_server.zones``.
"""

STATEMENTS: tuple[str, ...] = (
    """CREATE TABLE zone_change (
        user_id  UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        since    TIMESTAMPTZ NOT NULL,
        timezone TEXT        NOT NULL,
        PRIMARY KEY (user_id, since)
    )""",
)
