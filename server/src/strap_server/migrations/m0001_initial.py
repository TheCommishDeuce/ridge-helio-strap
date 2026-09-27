"""0001 — the raw strap tables, the derived table, profile and logs.

Column-for-column the old schema (healthee@049c9ad `db/schema.sql`) for every table the
science reads, so the verbatim derive port runs unchanged. Dropped: RLS, tenancy
roles, subscriptions, challenges, coach state, GPS. `user_id` stays as a column with a
single owner row: the science SQL filters on it everywhere, and rewriting proven queries to
remove it would be a behaviour risk for no gain.

Canonical sample metric names are the old server's: hr, hrv, spo2, skin_temp_c,
respiratory_rate, stress, steps_per_minute.
"""

STATEMENTS: tuple[str, ...] = (
    "CREATE EXTENSION IF NOT EXISTS timescaledb",
    """CREATE TABLE app_user (
        id         UUID        PRIMARY KEY,
        timezone   TEXT        NOT NULL DEFAULT 'UTC',
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )""",
    """CREATE TABLE sample (
        ts      TIMESTAMPTZ      NOT NULL,
        metric  TEXT             NOT NULL,
        value   DOUBLE PRECISION NOT NULL,
        user_id UUID             NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, metric, ts)
    )""",
    "SELECT create_hypertable('sample', 'ts', chunk_time_interval => INTERVAL '7 days')",
    """CREATE TABLE sleep_session (
        start_ts  TIMESTAMPTZ NOT NULL,
        end_ts    TIMESTAMPTZ NOT NULL,
        kind      TEXT        NOT NULL DEFAULT 'main' CHECK (kind IN ('main', 'nap')),
        score     INTEGER,
        avg_hr    INTEGER,
        rem_min   INTEGER,
        light_min INTEGER,
        deep_min  INTEGER,
        wake_min  INTEGER,
        stages    JSONB       NOT NULL DEFAULT '[]'::jsonb,
        user_id   UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, start_ts)
    )""",
    """CREATE TABLE workout (
        start_ts   TIMESTAMPTZ NOT NULL,
        sport      INTEGER     NOT NULL DEFAULT 0,
        duration_s INTEGER     NOT NULL DEFAULT 0,
        calories   INTEGER,
        distance_m REAL,
        avg_hr     INTEGER,
        max_hr     INTEGER,
        min_hr     INTEGER,
        user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, start_ts)
    )""",
    """CREATE TABLE derived_daily (
        day        DATE             NOT NULL,
        metric     TEXT             NOT NULL,
        value      DOUBLE PRECISION NOT NULL,
        flags      JSONB            NOT NULL DEFAULT '{}'::jsonb,
        derived_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
        user_id    UUID             NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, day, metric)
    )""",
    "CREATE INDEX derived_daily_user_metric_day ON derived_daily (user_id, metric, day DESC)",
    """CREATE TABLE device_daily_total (
        day         DATE             NOT NULL,
        steps       INTEGER,
        distance_m  DOUBLE PRECISION,
        calories    DOUBLE PRECISION,
        source      TEXT             NOT NULL DEFAULT 'strap_0x16',
        reported_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
        read_at     TIMESTAMPTZ,
        user_id     UUID             NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, day)
    )""",
    """CREATE TABLE profile (
        height_cm  REAL,
        sex        TEXT        CHECK (sex IN ('male', 'female')),
        dob        DATE,
        srpa       SMALLINT    CHECK (srpa BETWEEN 0 AND 4),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id)
    )""",
    """CREATE TABLE weight_log (
        ts      TIMESTAMPTZ NOT NULL,
        kg      REAL        NOT NULL,
        user_id UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, ts)
    )""",
    """CREATE TABLE manual_entry (
        id         UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
        kind       TEXT             NOT NULL,
        ts         TIMESTAMPTZ      NOT NULL,
        end_ts     TIMESTAMPTZ,
        name       TEXT,
        amount     DOUBLE PRECISION,
        unit       TEXT,
        severity   INTEGER,
        notes      TEXT,
        flags      JSONB            NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
        user_id    UUID             NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX manual_entry_user_kind_ts ON manual_entry (user_id, kind, ts DESC)",
    """CREATE TABLE illness_flag (
        date              DATE    NOT NULL,
        severity          TEXT    NOT NULL CHECK (severity IN ('moderate', 'high')),
        rr_delta_bpm      REAL,
        temp_delta_c      REAL,
        hrv_delta_z       REAL,
        rhr_delta_z       REAL,
        sustained         BOOLEAN NOT NULL DEFAULT FALSE,
        research_note_ids TEXT[]  NOT NULL,
        created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
        user_id           UUID    NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, date)
    )""",
)
