# Decisions

Status: **Decided** = owner said so. **Open** = needs an answer; a recommendation is given
but nothing is built on it until confirmed. Superseded decisions and resolved questions are
kept at the end, for the reasoning.

## Decided

| # | decision |
|---|---|
| D1 | New repo, fresh structure (too much tech baggage in `healthee`). Branding later; `strap` is a codename. |
| D2 | Backend runs on the **same server** as the current project, next to the existing database. |
| D4 | **Keep the honesty layer** (cited, grade-calibrated claims; fail closed). |
| D5 | **Android only.** |
| D7 | UI/UX: lighter, **Bevel-like in look and scope**. |
| D8 | Client: **Kotlin + Jetpack Compose**. Strap protocol re-implemented from `spec/01` with test vectors; Gadgetbridge (AGPL) is reference reading only, no copied code. |
| D9 | Backend: **Python (FastAPI + psycopg3)**, fresh code, science ported verbatim and checked against the old golden fixtures. |
| D10 | Database: **new database in the existing Postgres/TimescaleDB instance**. One-time import of the old *raw* tables, then a full re-derive. The old app runs untouched until cutover. |
| D11 | **No GPS anywhere.** VO₂max can only come from the Jurca model (needs the self-reported exercise question). Drop `gps_track`/`gps_point`, the DEM, and both measured VO₂max tiers. |
| D12 | v1 focus metrics: **sleep, steps, stress, heart rate**. HR, stress and steps are shown at **full per-minute resolution**. Hourly/15-min averages hid the peaks (see §R1). |
| D15 | **v1 scope, and nothing more:** sleep, steps, stress, heart rate, recovery, strain, coach (local Gemma), journal (caffeine/alcohol/weight), VO₂max card (Jurca). Everything else in the old app is out, including challenges/programs, notable feed, recommendations engine, biological age, Telegram briefing, paid-plan layer, multi-user onboarding. |
| D21 | **No LLM, no Coach (2026-09-25).** After the first GPU run (route confidence 0.0, 120 s timeouts) the owner cut the Coach and every LLM part: endpoint, client, bench/eval, the Coach-only safety screen, the Coach tab, and the LLM key in the server `.env`. Supersedes D3, D18, D20. Kept: the claim library and insights — pre-written, cited, grade-checked, evaluated by code, never a model. All removed code is in git history (commit `d617b1f` and before). |
| D22 | **No written insights (2026-09-26).** The owner found the claim-library sentences under the cards redundant with the numbers they sat under. Removed: the claim library (`knowledge/claims/`), the server `insights/` module and its tests, `insights` in the day summary, the corpus copy in the server image, and every insight list in the app (Today's Why card and stress, Sleep, Activity). Also dropped "of 8h 00m need" from Today's Last night headline (constant, adds nothing). Supersedes the "Kept" part of D21. Removed code is in git history (commit `3f926ba` and before). The knowledge corpus stays in the repo as the derive code's reference. |
| D24 | **Branding and signing (2026-09-26).** The app is **Ridge**, package `io.github.thecommishdeuce.ridge`, icon = a ridge line with the charts' peak marker. The repo, server and code namespace (`app.strap`) keep the codename. Release builds are signed with `~/.android/ridge-release.jks` (alias `ridge`, RSA 4096, 100 years), password in the macOS Keychain item `ridge-release-keystore` (on Linux, the libsecret item `service=ridge-release-keystore`; the build reads whichever the OS has); cert SHA-256 `9754b457…61e4`. **Losing the keystore means no update can ever install over the app**, so back the file up (and the password with it). Without the key the release APK is built unsigned. |
| D25 | **Licence: AGPL-3.0-or-later (2026-09-27).** The strap protocol was learned from Gadgetbridge (AGPL) and first ported from it by the old app; the AGPL keeps this repo compatible with that lineage. `LICENSE` (gnu.org text) + `NOTICE` (credits, bundled Gradle wrapper, trademarks). Copyright holder named by GitHub handle. |
| D26 | **Published scope: one person per server (2026-09-27).** One device token, one owner, no accounts or sign-up; each person runs their own server with `deploy/`. The schema keeps `user_id`, so a household mode stays possible, but it is not promised. |
| D27 | *Server no longer required (D34).* **First-run setup and Settings (R2, 2026-09-27).** A fresh install shows only setup: welcome → pair the strap (MAC + key, Nearby devices permission) → connect the server (**required**; Test with the token before Next) → first sync → Today. The gear opens **Settings** (strap, server, about; "Change…" reopens that step); raw diagnostics only under Advanced. The top bar stays Sync left, gear right. |
| D28 | **Today's heart and stress cards compare like with like (2026-09-28).** A day still running is compared with the **same local hours** of the 30 days before it (`usual` on `heart.today` and `stress`: the median of each day's mean up to the same clock time; whole days for a past day). Steps get `usual_by_now`: each earlier day's `steps_total` times the share of its per-minute steps taken by that time, then the median. The per-minute stream supplies only the day's shape, never the count, so its stalls don't shrink the reference (see `device_totals`). Minimum 5 days, else no usual. Stress is shown as the strap's own 0-100 arousal number, with no bands or verdicts (see `wearable_stress_validity`). |
| D29 | **History from the Zepp cloud fills gaps; the strap always wins (2026-10-03).** `tools/zepp-backfill` pulls the strap's own pre-Ridge history (per-minute HR and steps, stress, sleep with stages, day totals) out of the Zepp cloud once, and pushes it as `source: zepp_cloud`. The server stores it only where the strap has nothing: no sample of the metric within the minute, no overlapping sleep session, no total for the day. The strap wins in either order: its uploads overwrite a cloud minute (both are minute-aligned) and delete any cloud night or day total they overlap, so backfilling before the first sync is fine. Nothing is computed to replace what the cloud lacks: stress and sleep stages are the strap's on-device values, and stress can't be rebuilt from per-minute HR (it needs beat-to-beat intervals). HRV, SpO₂, skin temperature and respiratory rate are not in the cloud's history, so those days have none. Workouts are skipped because the cloud's sport codes aren't the strap's. `sleep_session.source` (0002) records each night's source, and the sleep score passes it on (this supersedes the constant `strap_ble`). Runtime still has no Zepp code (O4). |
| D30 | *Upload becomes nightly (D34).* **Background sync, sync on open, and alarms in a timezone (2026-10-06).** Supersedes D19. Ported from the old Healthee app: two WorkManager jobs, off by default — **collection** (strap → phone; 15/30/60/180 min, default 30) and **upload** (phone → server; 30/60/360 min, default 60; Wi-Fi only optional), both optionally only while charging, plain workers (no foreground service), always "success" so a busy strap or failed link waits for the next period. **Sync on open** (on by default): coming to the front syncs when the last *complete* sync is over 15 min old. Both go through `SyncRunner`'s one lock. Not ported: Healthee's held foreground connection (it locked the Zepp app out and drained the strap). **Alarms:** each alarm has a timezone the owner picks (default the phone's); the phone keeps (time, days, zone) per slot and writes the strap the time that rings then on the strap's own clock, whose offset is read on every connection (Current Time `2a2b`; if absent, assumed to be the phone's zone, as the history fetch does). Every complete sync re-checks and rewrites stale alarms, which covers DST. The conversion is on the phone, not the server: only the phone talks to the strap. |
| D31 | **Days follow the owner's timezone (2026-10-06).** The owner travels often, so one fixed zone cut the wrong days (two hours east of the server's zone, every day began at 02:00 and every chart sat two hours off its axis). **A day starts at local midnight wherever the owner is at that moment**; it ends where the next day starts, so days tile with no gap or overlap: flying east shortens the day being left (to 22 h, say), flying west lengthens it; a flight that skips a midnight starts the day at the change itself. The phone records each change of its zone (the system broadcast, plus a check at app start and before every upload) and uploads them FIRST; the server keeps them in `zone_change` (migration 0003) on top of `app_user.timezone`, and a new change re-derives from the day before it. `strap_server.zones.Zones` is the one definition; every `tz` in the science takes it (a plain zone name still means that zone throughout, so with no change recorded every number is identical — the parity tests hold it). Sleep timing and regularity use the clock of the zone in force at each minute. The day's series and summary now carry the day's real `start`/`end` and its zone, and the app draws and labels each day with them. |
| D32 | *Where metrics are computed: superseded by D34.* **The strap's own summaries are collected raw; the server stays the one place metrics are computed (2026-10-20).** Sync now also fetches HRV as SDNN (`0x4E`), the strap's PAI (`0x0D`) and its nightly sleep-apnea summary (`0x4A`), after workouts so a refused or failing type cannot cost the core streams. They are stored under their own names (`hrv_sdnn`, `pai_*_device`, `odi_*_device`) and **no formula reads them yet**. This lifts the old "PAI: skip, proprietary composite" rule for storage only: PAI is kept as the strap's figure, not shown as ours. Layouts come from OpenStrap's clean-room spec (MIT/Apache-2.0, `docs/protocol/02-history-sync.md`), checked against our own 2026-06-04 probe dumps. OpenStrap computes everything on the phone; Ridge deliberately does not (spec/02 §6), so the data stays portable. |
| D33 | *Computed on the phone from D34 on; Charge became Energy (D35).* **Charge replaces "readiness now"; the strap's PAI, breathing disturbances and SDNN are shown (2026-10-20).** Readiness now was too linear: only exercise moved it. **Charge** (spec/02 §2.12), a minute-by-minute body battery after OpenStrap's model, takes its place on the Recovery screen: sleep fills it, activity (the same TRIMP as strain), stress over a personal threshold and the waking day drain it. It is computed on the server at read time (D32: the server stays the one place metrics are worked out) and stores nothing. Recovery, the morning score, is unchanged, and so is the illness flag (spec/02 §2.9), which has held up in use. New cards, each labelled as the strap's own figure (§2.13): **PAI** on Activity (7-day total, goal 100), **breathing disturbances** on Sleep (blood-oxygen drops per hour measured, against your usual; no severity words, not a diagnosis), and **SDNN** as a chart from the Recovery screen. |
| D34 | **The phone does the science; the server is an optional backup (2026-10-21).** Supersedes spec/02 §6, the "server is the one place metrics are computed" part of D32 and D33, ARCHITECTURE's "the server is canonical", D27's required server, D17's "the API is the integration point" and D30's upload interval. Every metric is worked out on the phone, from its own store, in a new pure-Kotlin module `android/strap-science` (JVM, no Android, like `strap-protocol`): **one definition per metric, there**. Why: numbers are current offline, steps can be live, and the server shrinks to cold storage. **The server** keeps raw data, the journal and the profile, uploaded **once a night** (charging + Wi-Fi), and hands them back to a new phone (restore); it computes and serves no metrics, and nothing integrates against it any more (a Health Connect export from the phone is the likely route later, not scheduled). Setup no longer requires a server; first run offers "restore from server". Zepp-cloud history (D29) still goes to the server and reaches the phone by restore. The app shows how old the last backup is and warns when it is stale. **Live steps** only while Today is on screen. The probe (spec/01 §5) showed a reconnect costs 4.8 s, so live holds **one connection while Today shows today** with the strap's real-time step reporting on, and drops it the moment Today leaves the screen; this is the one exception to D30's no-held-connection rule, which otherwise stands. A sync pauses it; the last reading is stored as a counter reading. The background collection job is unchanged. **Migration, in order:** port with the golden fixture (172/172) and the known-value tests; shadow mode on real data (the phone computes, the server stays the reference, Diagnostics shows the differences) until there are none unexplained; screens switch to local numbers one at a time; then the server's science and read API are deleted. Until shadow mode passes, the server's numbers stay the reference. |
| D35 | **Charge becomes Energy, after OpenStrap 2.1 (2026-10-26).** The owner found Charge hard to believe: a minute without heart rate held the value, so a night or afternoon with the strap off froze it; a calm minute recharged faster than rest drained, so quiet days climbed; a raised heart rate while sitting counted as activity; and the strap's HRV-based stress reading, high just after exercise, drained as stress. OpenStrap's revised model (its "Energy", MIT/Apache-2.0) answers each, and is ported to spec/02 §2.12: **not worn** (a gap ≥ 10 min closed by later data) drifts at Zepp's default no-wear rate, has no value after 6.4 h since the last wake or at once over the usual sleep, and after a night off restarts from the median of the last 7 wake values (80 without), labelled estimated; **rest** drains 0.0005 × the value, **relax** only holds (+0.00015 × headroom, no drain); **heart rate is effort only while moving**, inside a workout window, or in a run of 10+ minutes at HRR ≥ 0.30; **stress readings inside the strap's workouts and the 20 min after** are dropped, from Energy and its calm threshold; **a high illness flag** (§2.9) halves recharge and multiplies drain by 1.5 from its night until the next main sleep (a moderate flag, one signal for one night, changes nothing). The warm-up is 8 days (the week of wake values). Renamed **Energy** because "Charge" read like the strap's battery; the kcal tiles that said "Energy" now say **Calories**. Not taken: the strap's off-wrist marks and wear pushes (not decoded; a gap is the only signal), and dropping workout-window stress from the day's stress card. The demo golden case no longer covers this card: the server's numbers were Charge's. |
| D36 | **Biological age becomes a habits age, after OpenStrap 2.1 (2026-10-26).** The owner found the old estimate meaningless, and it barely could move: its fitness term was Jurca's VO₂max, a function of BMI, age, a self-rated activity answer and resting HR (±2.5 years of its own error), and its sleep term was duration alone after a self-report correction that lifted most nights onto the flat of the curve. OpenStrap's healthspan age replaces it (spec/02 §2.11): resting HR, steps, sleep regularity, short sleep (penalty only) and strength training (credit only), each against the population median for age and sex from accelerometer-era cohorts where possible, turned into years with PhenoAge's Gompertz slope, the fitness terms sharing a ±5-year limit, everything shrunk by 0.6 and capped at ±5, over 180 days, shown as a whole-year range of at least ±2 (±4 while early, from 30 days). Jurca leaves the estimate (it stays as its own card); the strap's exercise VO₂max isn't decoded, so that term is out. The screen names what each habit adds or takes off, with targets. |
| D37 | **Daily load counts workouts and movement, not sitting (2026-10-26).** After OpenStrap 2.1. `cardio_load` used to sum TRIMP over every waking minute, so a seated, raised heart rate added load: on the demo person a desk day carried 165 against a hard day's 260, and strain read 15 on days with no training. Now (spec/02 §2.4) every minute of a strap workout counts whatever its intensity, and outside workouts a waking minute counts only with steps and HRR ≥ 0.35, or with 60+ steps at HRR ≥ 0.20 (a walk), when 3 of the 5 minutes ending with it pass too. The parts are kept (`workout_load`, `everyday_load`) and the strain screen says how the day split. Zone minutes, Edwards and `hr_minutes` still count every worn waking minute. On the demo, desk days fell to about 24 (strain about 7) while hard days held (strain 21). Strain, its ring, ACWR and the 90-day percentile follow. The golden cases no longer compare the load's value or strain's numbers. The phone re-derives its whole history once when the derive rules change (a rules version in its inputs fingerprint). |
| D38 | **The night leads with a headline (2026-10-26).** After OpenStrap 2.1. The Sleep screen's summary card is titled with what shaped the night (spec/02 §1.3): short (under 6 h asleep) and broken (30+ min awake after falling asleep, or 6+ wake-ups) come first, alone or together, because duration and continuity are the night's dependable numbers; then the strap's score (90+ Great, 80+ Good); then Light sleep when deep + REM is under a quarter of sleep, the weakest cause since staging agrees with the lab least; else Okay. The card opens with the reason. The strap's score is now worded on Zepp's bands (90/80/60, the scale it is made on) instead of Ridge's 85/70/55. Display only: nothing is stored and no score of Ridge's own is made. |
| D39 | **Home-screen widgets: Today and Steps (2026-10-26).** After OpenStrap 2.1, cut to Ridge: no Moment (the Pixel Launcher has no stacks, so one Today widget carries Recovery, Energy, last night's headline and time asleep, and steps) and no Start a workout (Ridge records no phone workouts). Each comes in three sizes, 2×2, 4×2 and 4×3 (Jetpack Glance 1.2, `SizeMode.Responsive`); the large adds Energy through the day (Today) or the week's bars (Steps). Ridge's own card colours, light and dark; charts are white bitmaps tinted per theme. They read the phone's numbers as the screens do, so steps are as of the last sync (live steps stay on Today, D34), against 8,000 (the bottom of Biology's target; no goal setting yet). A strap line shows only when it matters: amber "Synced 09:10" once the last sync is over 3 h old, which runs one sync when tapped (the foreground sync service, which a widget tap may start; not a held connection, D30), "Syncing…", or "Pair your strap". Refreshed after every derive, when a sync starts or ends, at midnight and when the sync turns stale (an inexact alarm); never on a system timer. A tap elsewhere opens Today or Fitness. |
| D40 | **Find strap (2026-10-26).** On the Strap page: Find connects (a short strap job, refused while a sync holds the strap, D30), sends `03` to Find device `0x001A` and the strap buzzes until `06`; Ridge sends `06` on Stop, after 60 s (Zepp's find dialog), on leaving the page or any failure, unless the strap already sent `07`. From OpenStrap 2.1's hardware notes. Owner-triggered only: call and notification buzzing stays out for good (a screenless strap that buzzes for the phone isn't one). |

## Resolved facts behind the decisions

### R1 · Why peaks were missing (D12)

The strap already delivers **per-minute** HR (`0x01`), steps (`0x01`) and stress
(`0x13`), and the old server stores them per minute in `sample`. The averaging happened only
when building the response (`read/today_series.py`): `hr_hourly` (hourly avg/min/max),
the stress series (hourly avg), and steps (15-min buckets). So nothing is lost in collection
or storage. The fix is in the API and the charts:

- Day view: serve per-minute points (≤ 1440 per metric per day, a few KB).
- Week/month views: buckets carry **min / max / mean + timestamp of the max**, drawn as a
  range band, so a spike is never averaged away.
- The strap can't give anything finer than 1/min. Minutes it didn't measure show up as
  gaps, never as zeros. **Measured 2026-09-25:** HR every minute (99 % of the last 24 h),
  stress every 5 minutes, steps per minute. So day views can draw true per-minute HR and
  steps curves; stress is a 5-minute series.

## Open

### O4 · Auth key without the Zepp cloud, and firmware updates

What we know (from the old repo's protocol notes and Gadgetbridge's documented practice;
**not re-verified on the Helio Strap**):

- The 16-byte auth key is created when the **official Zepp app pairs** the strap, and it is
  stored in the Zepp cloud and in the Zepp app's local data. For Zepp OS devices there is
  no known way to pair, or to make the strap accept a key we generate, without the
  official app. (Older Mi Bands allowed that; Zepp OS devices don't.)
- Routes to read the existing key: (a) Zepp cloud API (what the old app does in-app);
  (b) a Gadgetbridge export (GB got it from the cloud too); (c) the Zepp app's local data
  on a **rooted** phone.
- Sniffing the handshake does **not** reveal the key: step 4 is AES of a visible random
  under the key, so recovering it means brute-forcing AES-128.
- The key stays valid until the strap is unpaired/factory-reset and paired again, which
  creates a new key.

**Recommendation: cut the cloud from the app, keep it as a one-time external step.**
1. The new app has **no Zepp login code**. It accepts the key by paste or QR, and it lives
   in the Android keystore.
2. Key acquisition is a small standalone script (Zepp cloud → key + MAC), run once on a
   laptop/server per pairing. Runtime has zero cloud dependency.
3. **Firmware:** keep the Zepp app installed but inactive (revoke its Nearby Devices
   permission, or force-stop it) so it doesn't grab the strap. To update: pause our
   sync, re-enable Zepp, update, disable it again, run a sync. **Update deliberately,
   not automatically.** A firmware change is also a parser risk: the June-2026 firmware
   froze the per-minute step buffer. After any update, check that the handshake still
   succeeds (status `0x25` = key changed → re-run the script) and the decoders still pass.
4. Not recommended: flashing firmware ourselves over the OTA service (`0x1530`). High
   brick risk, no upside over the Zepp app.

The Zepp app is also the only place to change strap settings (e.g. HR monitoring
frequency, R1). Writing those over BLE ourselves is possible in principle but
unexplored. It's a later item.

## Superseded

| # | decision |
|---|---|
| D3 | *Superseded by D21.* LLM = **Gemma 12B QAT (Unsloth GGUF) on llama.cpp** on a LAN host, port TBD. |
| D6 | *Superseded by D17.* Health Connect: **write only**, and **computed results only**, never raw strap data (see D13). |
| D13 | *Superseded by D17.* Health Connect export happens **after the server computes**. The phone pulls derived results and writes them to HC. |
| D14 | *Superseded by D17.* HC export set = the computed-results table in O7, **plus sleep sessions with stages** (a deliberate exception: the stages are the strap's, not ours). Heart rate reaches HC only as nightly resting HR, with no intraday curve. |
| D16 | *Moot: the corpus is in the repo, the LLM work was cut (D21).* Knowledge corpus: **copied into this repo** when the LLM work starts; this repo owns it from then on, and the old repo's copy is frozen. |
| D17 | *Superseded by D34.* **Health Connect export deferred** (supersedes D6/D13/D14 for now): the internet-reachable API is the integration point; future Home Assistant integrations read it. |
| D18 | *Superseded by D21.* LLM host = llama.cpp on a LAN host with an **8 GB VRAM** GPU → small context; the LLM design must fit a few thousand tokens. |
| D19 | *Superseded by D30.* Background sync: **none (decided 2026-09-26).** Syncing stays manual from the app. Android would only run a periodic sync at times it picks, which the owner doesn't want. |
| D20 | *Superseded by D21.* **No Jev, no cloud model — data stays offline.** The LLM layer is a local decision engine in Jev's style: Gemma decides over closed, typed choices (grammar-constrained JSON, token probabilities as confidence); every sentence shown comes from a pre-written claim library tied to graded notes; numbers are filled in by code. Hardware: GTX 1070 8 GB, Gemma 4 12B QAT Q4 (6.7 GB), n_ctx 6144, 4 slots. |

## Resolved questions

### O7 · Health Connect: which computed results — RESOLVED → D14

Computed results that have an HC record type:

| our metric | HC record | granularity |
|---|---|---|
| `rhr_daily` | `RestingHeartRateRecord` | 1/day |
| `hrv_sleep_avg` | `HeartRateVariabilityRmssdRecord` | 1/night (assumes strap HRV is RMSSD) |
| `spo2_overnight` | `OxygenSaturationRecord` | 1/night |
| `respiratory_rate_sleep` | `RespiratoryRateRecord` | 1/night |
| `steps_total` | `StepsRecord` | 1 day-long interval |
| `distance_m_daily` | `DistanceRecord` | 1 day-long interval |
| `total_calories`, `active_calories` | `TotalCaloriesBurnedRecord`, `ActiveCaloriesBurnedRecord` | 1 day-long interval |
| `basal_calories` | `BasalMetabolicRateRecord` | 1/day |
| `vo2max_estimate` (Jurca) | `Vo2MaxRecord` | when computed |

No HC type (they stay in-app only): recovery, strain, cardio load, stress, sleep-health
dimensions, SRI, sleep debt, MVPA. (Check whether the current HC SDK has an
activity-intensity type before writing MVPA off.)

**Needs your call.** Under "computed only":
- **Sleep sessions** (with stages) would *not* be exported. The stages are computed by the
  strap, not by us. Sleep is a main metric, so is it an exception?
- **Heart rate** would appear in HC only as the nightly resting HR, with no intraday curve.
  Intended?

### O6 · Knowledge corpus — RESOLVED → D16

Copy `packages/knowledge` into this repo at cutover and stop editing it in the old repo?
(Recommendation: yes.)

### O8 · v1 screens beyond the four metrics — RESOLVED → D15

The focus list is sleep, steps, stress, HR. Are **recovery / strain** (Bevel's core cards),
**coach** (the local-Gemma feature), and a **journal** (caffeine/alcohol/weight, which the
correlations need) in v1 or later? Is **VO₂max** (Jurca-only now) worth a card?
