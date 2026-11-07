"""0004 — workouts stored before app 0.7.1 have no known sport.

Until app 0.7.1 the phone read the workout summary's field 2.3 as the sport; it is the record's
timezone (OpenStrap's restatement of Zepp's sport_summary2.proto: the sport is field 3.1). Every
row here came that way (the Zepp cloud backfill skips workouts), so 0 — no sport — is the truth.
Only workouts that started before 0.7.1 was built: a later one may already carry a real sport
from an updated phone. A 0.7.0 phone's later rows are corrected when it updates, since 0.7.1
re-uploads every workout it holds with sport 0.
"""

STATEMENTS: tuple[str, ...] = (
    "UPDATE workout SET sport = 0 WHERE start_ts < '2026-11-07T00:00:00Z'",
)
