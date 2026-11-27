# 03 · Integrations — openGym and Dawarich, as their servers actually answer

What Ridge reads from two self-hosted services, recorded from live instances on 2026-11-01
(openGym `main`, Dawarich `master`), not only from their published specs. Both are
**read-only**: Ridge never writes to either. The phone talks to them directly (D34); the
backup server never sees their data. No real response is kept in the repo: the test fixtures
are synthetic, shaped like what is recorded here.

Secrets for working against a live instance from the dev box sit in the repo root as
`opengym.url`, `opengym.token`, `dawarich.url`, `dawarich.token` (git-ignored, mode 600).
In the app they are entered in Settings → Integrations and sealed in `KeyVault`.

---

## 1. openGym (`github.com/DuarteSantos8/openGym`)

### Auth
| step | call | notes |
|---|---|---|
| Mint | browser: Settings → **"Pair the mobile app"** (`POST /api/pair/create`) | **8 characters**, alphabet without 0/O/1/I, one-shot, **5-minute TTL**, one live code per profile |
| Redeem | `POST /api/pair/redeem` `{"code": "K7WQ2MZP"}` (no auth, case-insensitive) | `200 {token, user: {id, name, admin}}`; `400 {error, code: "pair-invalid"}` for unknown, used or expired alike |
| Use | `Authorization: Bearer <token>` | same signed token as the session cookie; lives `SESSION_DAYS` (default 90) |
| Renew | `GET /api/me` | renews once past half its life; a 401 anywhere means pair again |

Not to be confused with a **device link** (`POST /api/account/device-link`): 12 characters
in groups, made under passkeys, for adding a browser or phone that signs in with a passkey. It does not redeem at
`/api/pair/redeem`.

### Reading
- `GET /api/data/rev` → `{"rev": 70, "wid": "<hex>"}`. Cheap; pull only when `rev` moved.
- `GET /api/data` → `{"rev": n, "state": {…}}`. The whole profile state (~80 keys, most of
  them UI settings). Ridge reads `state.unit` (`"kg"`/`"lb"`), `state.workouts`,
  `state.customEx`.
- `state.active` (a workout in progress) is always `null` on the server: a running session
  belongs to the device running it.
- `state.deleted` is an object keyed by collection (`{"routines": …}` seen); deleted
  workouts simply leave `workouts`, so replacing the stored set on every pull handles
  deletion.

### Workout
```json
{
  "id": "…", "d": "2026-11-01", "start": 1793440800000, "end": 1793443800000,
  "name": "Push A", "routineId": "…", "routineIds": ["…"],
  "bw": 80.5, "vol": 5230, "prs": ["0025"],
  "entries": [
    { "id": "0025", "rid": null, "planned": null, "target": null, "topW": 80,
      "sets": [
        { "w": 40, "r": 10, "done": true, "warmup": true, "phase": "warmup", "at": 1793440920000 },
        { "w": 80, "r": 6,  "done": true, "at": 1793441100000 }
      ] }
  ]
}
```
| field | meaning |
|---|---|
| `start`, `end` | epoch **ms**; `d` is openGym's own local day |
| `entries[].id` | exercise id: a 4-digit catalogue id (`"0025"`) or a `customEx` id |
| set `w`, `r` | weight in `state.unit`, reps |
| set `at` | epoch **ms** the set was ticked done. **Present on recent workouts only** (all 18 sets of a 2026-11-01 session, none on 2026-07 ones), and always inside `start..end`; spacing 2–3 min |
| set `warmup` / `phase:"warmup"` | warm-up set |
| set `min`, `speed` | a timed cardio set: minutes and speed (`state.speedUnit`); no `w`/`r` |
| `vol` | openGym's own volume (Σ w × r), shown as openGym's |

**Consequence for the overlay:** where sets carry `at`, each exercise is a band from its
first to its last set and each set is a tick, and the HR under them is the strap's
**per-minute** HR (a set is the minute it was ticked in, not its true duration). Where no set
has `at`, the session is shown whole: HR over `start..end`, the set list below without HR.

### Exercise names
The catalogue lives in the openGym repo (`catalogue/exercises/<id>.json`, `name` in English;
`catalogue/i18n/<lang>.json` for translations) and is compiled into its frontend; the
instance does not serve it. Ids are permanent (never changed, reused or deleted). The text is
AGPL like Ridge, so Ridge ships a **name-only** `id → name` table generated from it (credited
in `NOTICE`); the media is licensed to openGym alone and never copied. Custom exercises take
their name from `state.customEx`. An id in neither shows as "Exercise <id>".

---

## 2. Dawarich (`github.com/Freika/dawarich`)

### Auth
API key from Dawarich's account settings, sent as `Authorization: Bearer <key>` (the server
also accepts `?api_key=`; Ridge never puts it in a URL).

### Tracks for a day
`GET /api/v1/tracks?start_at=<ISO>&end_at=<ISO>&page=&per_page=` → GeoJSON
`FeatureCollection` of `LineString` features, `coordinates` as `[lon, lat]`. Paging in the
headers: `x-current-page`, `x-total-pages`, `x-total-count`.

| property | meaning |
|---|---|
| `id` | track id (integer) |
| `start_at`, `end_at` | ISO 8601 **with the local offset** (`2026-10-31T14:01:55+01:00`) |
| `distance` | metres (integer) |
| `duration` | seconds |
| `avg_speed` | km/h over the whole duration **including stops** (a 5 km ride in 50 min reads 6.0), so Ridge does not show it as a riding speed |
| `dominant_mode` | seen: `walking`, `cycling`, `running`, `driving`, `stationary` |
| `mode_timeline` | `[{start_time, end_time (unix s), emoji}]`: moving/stopped segments; only an emoji, no mode name (📍 = stopped) |
| `color`, `dominant_mode_emoji`, `revision` | unused |

Ridge reads `walking`, `cycling` and `running` tracks and decides itself how each was moved
(below); `driving` and `stationary` are not read.

### `dominant_mode` is the whole track's; transit hides inside it
A track is one mode end to end, and transit legs are labelled as the mode around them, in
`mode_timeline` too (it only ever held 🚶 🏃 🚴 🚗 📍 in 30 days: there is no transit mode).
Seen on the owner's data: a 14.9 km "walk" with a 1.7 km step at 45 km/h (metro) and a 2.2 km
step at 24 km/h (tram), both inside 🚶 segments; a 19.3 km "ride" with steps of 41–90 km/h (a
train). Across 30 days, real walks stayed under 9 km/h between fixes (rare GPS jumps of 10–13)
and real rides under 36.

Worse, the mode itself is unreliable: on 31 Oct two walks with a tram were "cycling", and on
17 Oct a real ride was "running". Between fixes 2 min apart a tram (with its stops) and a bike
both average 10–20 km/h, so speed cannot part them. The strap can: on foot it counts ~100 steps
a minute; on a tram it counts none and HR falls; on a bike it counts none and HR stays up.

So Ridge decides each step between two fixes (`DawarichFormat.split`, D42 as amended by D48):
**≥ 120 steps a minute and ≥ 6.5 km/h → ran** (runners: 150–185 a minute at 8–16 km/h; walkers:
90–125 at up to ~7; a stop at a light still averages over 120 across 2 min; untested on real
runs); **else ≤ 9 km/h → walked**, whatever the heart rate; **else ≥ 80 steps a minute through
the whole step → walked** (a GPS jump). What is left is faster than walking, and each unbroken
stretch of such steps is decided as one: **rode when the strap's heart rate over its minutes
averages at least the floor of zone 2 (60 % of the age-based maximum) and no step is over
36 km/h; else carried.** Measured on the owner's tracks (10 Oct – 13 Nov, 13 with anything faster
than walking): ride stretches averaged 118–146 beats a minute (five tracks the owner confirmed),
tram and metro stretches 72–104 (four confirmed, four more not asked about); the highest transit
value is the minutes right after boarding. A stretch is taken whole because single steps lag (the
first step of a ride read 108). The strap's own workouts are no evidence either way: it opened
none for 26 minutes of a ride, and ran one over a metro. Without heart rate over a stretch or
without a date of birth, a step inside a strap workout is a ride, as before D48. Minutes with
no steps sample count as 0 when the strap sent any step around the track, else the steps are
unknown and speed decides. A slow stepless step between two carried ones is a stop (carried).
Own distance is the great-circle sum of walked, run and ridden steps; own time is their
duration **without steps slower than 1 km/h** (standing still); the track is a Walk, a Run or a
Ride by whichever covered the most ground; under 100 m of its own, it is dropped. The points endpoint sends **no paging headers**: a page of
`per_page` (1000) means ask for the next.

### Geometry is the tracker's raw points
The line is not smoothed or densified: one point per fix, about every **2 minutes** for the
owner's tracker (18 points for a 5 km ride). `GET /api/v1/tracks/{id}/points?per_page=1000`
returns the same points with `timestamp` (unix s), `latitude`/`longitude` (**strings**) and
`velocity` (string), plus fields Ridge must not read or keep (`ssid`, `bssid`, `city`,
`geodata`, `battery`, …). Ridge parses latitude, longitude and timestamp only.

### One outing, several tracks
Dawarich ends a track at a pause of a few minutes and starts a new one. On the owner's data
(from about 9 Nov; earlier outings came whole) the pieces of one outing were 5–7 minutes apart,
and the smallest gap between two real outings in five weeks was 16 minutes. Ridge joins tracks
it reads that are **less than 10 minutes apart** (`DawarichFormat.join`, D49) before cutting
them: first start to last end, the first's id, all fixes, distances summed. The pause is then a
step between two fixes like any other.

### Days
No track in 30 days crossed midnight. Ridge asks for the day's real bounds
(`zones.bounds(day)`, D31) and lists a track on the day it starts.
