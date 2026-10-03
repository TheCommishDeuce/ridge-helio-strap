# Backfilling history from the Zepp cloud

**Optional.** Without it, Ridge starts from what the strap holds (about the last 30 days)
and the Zepp cloud plays no part. Use this if you want your older history as well.

The strap keeps about a month of data, so the first Ridge sync reaches back only that far.
Anything older exists only in the Zepp cloud, from when the Zepp app synced it.
`backfill.py` pulls that history out once and sends it to your Ridge server. The server
uses it **only to fill gaps**: wherever the strap already pushed a minute, a night or a
day's total, the strap's data stays. Order doesn't matter: a strap push still overwrites
cloud data afterwards.

## What comes across

| Data | |
|---|---|
| Heart rate, per minute | yes |
| Steps, per minute and day totals (with distance and calories) | yes |
| Stress | yes, the strap's own values |
| Sleep sessions and naps, with stages (light, deep, REM, awake) | yes, the strap's own staging |
| HRV, SpO₂, skin temperature, respiratory rate | no, not in the cloud's history |
| Workouts | no, the cloud numbers sports differently from the strap |

Nothing is computed to fill gaps. The strap computes stress and sleep stages itself, and
the cloud stores its values, so they are imported as they are. A night the cloud holds
without stages is not imported. With no HRV or respiratory rate, a backfilled day's
recovery uses only resting heart rate (from the night's per-minute HR) and sleep, and
its breakdown shows which inputs it had.

## Run it

Needs [uv](https://docs.astral.sh/uv/) and the same Zepp email and password as
`tools/keyfetch`. Signing in logs the Zepp app out, as keyfetch's sign-in does. Before or after
your first Ridge sync both work.

```sh
uv run tools/zepp-backfill/backfill.py fetch you@example.com --from 2024-01-01 --to 2026-12-31
uv run tools/zepp-backfill/backfill.py inspect --tz Europe/Berlin
uv run tools/zepp-backfill/backfill.py push --server https://your-ridge-server --tz Europe/Berlin
```

1. **fetch** saves the cloud's raw replies to `~/zepp-backfill`, 30 days per file. Pick
   a wide range: months before you had the strap just come back empty, and days the
   strap already delivered are skipped at push time. This
   is your health data: it never goes in the repo. Delete it when you're done.
2. **inspect** decodes them and prints one line per day. Check the result before pushing:
   - `steps(min/total)`: the per-minute sum should be close to the day's total.
   - sleep `anchor ±0-1m`: the stages line up with the night's recorded start.
   - `deep`/`light`: the minutes from the stages equal the cloud's own summary.
   - `devices`: if an older band also synced to this account, its days are in here too.
     Cut them off with `--to` / a later `--from`.
3. **push** asks for the server token (the one the app uses, or set `RIDGE_TOKEN`) and
   sends the days oldest first, about a week per request. Each line says how much was
   new and how much the strap already had.
4. **Re-derive on the server** afterwards, so the days after the backfill rebuild their
   baselines with the older history included. In `deploy/`:
   `docker compose exec api python -m strap_server.rederive`

`--tz` is the time zone you lived in back then (default: this computer's). The cloud
stores each day in local time, so it decides which minute is which. On the two DST
switch days, minutes after the change are an hour off.

The endpoints are the Zepp app's own and undocumented, and they may change. If a reply
isn't what the tool expects, it names the step that failed. It has been tested on one
account, in the EU region.
