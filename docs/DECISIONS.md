# Decisions

Status: **Decided** = owner said so. **Open** = needs an answer; a recommendation is given
but nothing is built on it until confirmed.

## Decided

| # | decision |
|---|---|
| D1 | New repo, fresh structure (too much tech baggage in `healthee`). Branding later; `strap` is a codename. |
| D2 | Backend runs on the **same server** as the current project, next to the existing database. |
| D3 | LLM = **Gemma 12B QAT (Unsloth GGUF) on llama.cpp** on a LAN host, port TBD. |
| D4 | **Keep the honesty layer** (cited, grade-calibrated claims; fail closed). |
| D5 | **Android only.** |
| D6 | Health Connect: **write only**, and **computed results only**, never raw strap data (see D13). |
| D7 | UI/UX: lighter, **Bevel-like in look and scope**. |
| D8 | Client: **Kotlin + Jetpack Compose**. Strap protocol re-implemented from `spec/01` with test vectors; Gadgetbridge (AGPL) is reference reading only, no copied code. |
| D9 | Backend: **Python (FastAPI + psycopg3)**, fresh code, science ported verbatim and checked against the old golden fixtures. |
| D10 | Database: **new database in the existing Postgres/TimescaleDB instance**. One-time import of the old *raw* tables, then a full re-derive. The old app runs untouched until cutover. |
| D11 | **No GPS anywhere.** VO₂max can only come from the Jurca model (needs the self-reported exercise question). Drop `gps_track`/`gps_point`, the DEM, and both measured VO₂max tiers. |
| D12 | v1 focus metrics: **sleep, steps, stress, heart rate**. HR, stress and steps are shown at **full per-minute resolution**. Hourly/15-min averages hid the peaks (see §R1). |
| D13 | Health Connect export happens **after the server computes**. The phone pulls derived results and writes them to HC. |
| D14 | HC export set = the computed-results table in O7, **plus sleep sessions with stages** (a deliberate exception: the stages are the strap's, not ours). Heart rate reaches HC only as nightly resting HR, with no intraday curve. |
| D15 | **v1 scope, and nothing more:** sleep, steps, stress, heart rate, recovery, strain, coach (local Gemma), journal (caffeine/alcohol/weight), VO₂max card (Jurca). Everything else in the old app is out, including challenges/programs, notable feed, recommendations engine, biological age, Telegram briefing, paid-plan layer, multi-user onboarding. |
| D17 | **Health Connect export deferred** (supersedes D6/D13/D14 for now): the internet-reachable API is the integration point; future Home Assistant integrations read it. |
| D18 | LLM host = llama.cpp on a LAN host with an **8 GB VRAM** GPU → small context; the LLM design must fit a few thousand tokens. |
| D19 | Background sync: **none (decided 2026-09-26).** Syncing stays manual from the app. Android would only run a periodic sync at times it picks, which the owner doesn't want. |
| D20 | **No Jev, no cloud model — data stays offline.** The LLM layer is a local decision engine in Jev's style: Gemma decides over closed, typed choices (grammar-constrained JSON, token probabilities as confidence); every sentence shown comes from a pre-written claim library tied to graded notes; numbers are filled in by code. Hardware: GTX 1070 8 GB, Gemma 4 12B QAT Q4 (6.7 GB), n_ctx 6144, 4 slots. |
| D21 | **No LLM, no Coach (2026-09-25).** After the first GPU run (route confidence 0.0, 120 s timeouts) the owner cut the Coach and every LLM part: endpoint, client, bench/eval, the Coach-only safety screen, the Coach tab, and the LLM key in the server `.env`. Supersedes D3, D18, D20. Kept: the claim library and insights — pre-written, cited, grade-checked, evaluated by code, never a model. All removed code is in git history (commit `d617b1f` and before). |
| D22 | **No written insights (2026-09-26).** The owner found the claim-library sentences under the cards redundant with the numbers they sat under. Removed: the claim library (`knowledge/claims/`), the server `insights/` module and its tests, `insights` in the day summary, the corpus copy in the server image, and every insight list in the app (Today's Why card and stress, Sleep, Activity). Also dropped "of 8h 00m need" from Today's Last night headline (constant, adds nothing). Supersedes the "Kept" part of D21. Removed code is in git history (commit `3f926ba` and before). The knowledge corpus stays in the repo as the derive code's reference. |
| D24 | **Branding and signing (2026-09-26).** The app is **Ridge**, package `io.github.thecommishdeuce.ridge`, icon = a ridge line with the charts' peak marker. The repo, server and code namespace (`app.strap`) keep the codename. Release builds are signed with `~/.android/ridge-release.jks` (alias `ridge`, RSA 4096, 100 years), password in the macOS Keychain item `ridge-release-keystore`; cert SHA-256 `9754b457…61e4`. **Losing the keystore means no update can ever install over the app**, so back the file up (and the password with it). Without the key the release APK is built unsigned. |
| D25 | **Licence: AGPL-3.0-or-later (2026-09-27).** The strap protocol was learned from Gadgetbridge (AGPL) and first ported from it by the old app; the AGPL keeps this repo compatible with that lineage. `LICENSE` (gnu.org text) + `NOTICE` (credits, bundled Gradle wrapper, trademarks). Copyright holder named by GitHub handle. |
| D26 | **Published scope: one person per server (2026-09-27).** One device token, one owner, no accounts or sign-up; each person runs their own server with `deploy/`. The schema keeps `user_id`, so a household mode stays possible, but it is not promised. |
| D27 | **First-run setup and Settings (R2, 2026-09-27).** A fresh install shows only setup: welcome → pair the strap (MAC + key, Nearby devices permission) → connect the server (**required**; Test with the token before Next) → first sync → Today. The gear opens **Settings** (strap, server, about; "Change…" reopens that step); raw diagnostics only under Advanced. The top bar stays Sync left, gear right. |
| D28 | **Today's heart and stress cards compare like with like (2026-09-28).** A day still running is compared with the **same local hours** of the 30 days before it (`usual` on `heart.today` and `stress`: the median of each day's mean up to the same clock time; whole days for a past day). Steps get `usual_by_now`: each earlier day's `steps_total` times the share of its per-minute steps taken by that time, then the median. The per-minute stream supplies only the day's shape, never the count, so its stalls don't shrink the reference (see `device_totals`). Minimum 5 days, else no usual. Stress is shown as the strap's own 0-100 arousal number, with no bands or verdicts (see `wearable_stress_validity`). |
| D29 | **History from the Zepp cloud fills gaps; the strap always wins (2026-10-03).** `tools/zepp-backfill` pulls the strap's own pre-Ridge history (per-minute HR and steps, stress, sleep with stages, day totals) out of the Zepp cloud once, and pushes it as `source: zepp_cloud`. The server stores it only where the strap has nothing: no sample of the metric within the minute, no overlapping sleep session, no total for the day. The strap wins in either order: its uploads overwrite a cloud minute (both are minute-aligned) and delete any cloud night or day total they overlap, so backfilling before the first sync is fine. Nothing is computed to replace what the cloud lacks: stress and sleep stages are the strap's on-device values, and stress can't be rebuilt from per-minute HR (it needs beat-to-beat intervals). HRV, SpO₂, skin temperature and respiratory rate are not in the cloud's history, so those days have none. Workouts are skipped because the cloud's sport codes aren't the strap's. `sleep_session.source` (0002) records each night's source, and the sleep score passes it on (this supersedes the constant `strap_ble`). Runtime still has no Zepp code (O4). |
| D16 | Knowledge corpus: **copied into this repo** when the LLM work starts; this repo owns it from then on, and the old repo's copy is frozen. |

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
