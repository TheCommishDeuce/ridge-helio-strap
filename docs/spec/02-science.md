# 02 · Science — every derived number, its formula, its gate, its source

Language-agnostic extraction of the old server's science layer. Source at extraction
time: `healthee@049c9ad`, `apps/server/src/healthee/{derive,analytics,read}/`. Evidence
grades (Established · Probable · Emerging · Contested · Myth) come from the old project's
research notes, which are not published; each metric below names its sources.

**Acceptance harness to carry over:** `apps/server/tests/fixtures/derive/expected_daily.json`
(golden output of the full derive chain over the deterministic seed in
`tests/derive/_seed.py`), `fixtures/analytics/expected_stats.json`, and
`fixtures/gps_track_2026_06_15.json`. A port is correct when it reproduces those
metric-for-metric. Behaviour changes are separate, deliberate re-baselines.

---

## 0. Conventions that every formula depends on

| rule | definition |
|---|---|
| Local day | half-open UTC interval `[local 00:00, next local 00:00)` in the owner's IANA zone. Minutes in a day = span (1380/1440/1500 on DST days), never a hardcoded 1440 |
| Night → day | a sleep session's metrics are filed on its **local wake date** (end time) |
| Median | textbook (mean of two central values when even). One implementation only |
| MAD | `median(|x − median(x)|)`, raw units |
| Robust SD | `MAD × 1.4826` (statistical identity, `1/Φ⁻¹(0.75)`), optionally floored per caller |
| HR sample validity | `30 ≤ bpm ≤ 220`, inclusive (engineering artefact filter, not research) |
| Steps/min validity | `0 < spm < 250` |
| Missing ≠ zero | a day no instrument measured produces **no row**, never a 0 |
| Withhold, don't invent | every metric has named withhold reasons; a withheld number carries the reason id + one sentence |

### Freshness horizons (how old an input may be and still speak for today)

| input | max age | why |
|---|---|---|
| logged weight (for BMR/BMI) | 14 d | past it: caveat, don't withhold (≈0.6 % per kg error bound) |
| measured VO₂max session | 14 d | detraining shows at ~2 weeks |
| resting HR for TRIMP reserve | 30 d | else withhold cardio load |
| any "today" card | same day | an older row is labelled as that day's, never presented as today |

---

## 1. Per-night metrics (one sleep session → its wake date)

| metric | formula | gate / bounds | source |
|---|---|---|---|
| `rhr_daily` | min over 5-min buckets (with ≥3 valid HR samples) of the bucket mean HR, within the sleep window | none if no qualifying bucket | resting_heart_rate (Established) |
| `hrv_sleep_avg` | mean of `hrv` samples in window | keep 5–200 ms (plausibility, uncited) | heart_rate_variability (Probable) |
| `spo2_overnight`, `spo2_overnight_min` | mean / min of `spo2` in window | keep 70–100 % | wearable_spo2_validity (Established) |
| `respiratory_rate_sleep` | mean of RR in window | keep 4–40 br/min (deliberately wider than 12–20 healthy band) | respiratory_rate_normal (Established) |
| skin temp (night) | mean of `skin_temp_c` in window | keep > 25 °C; not stored as a daily metric | skin_temp_signals (Probable) |

### 1.1 Sleep-health score, 4 dimensions (never shown as one validated "score")

Needs a complete stage breakdown (all four stage minutes), else no score.

```
TST  = light + deep + rem                     (minutes)
eff  = min(1, TST / (TST + wake))             (never > 100 %)
mid  = local clock hour of (start + (end − start)/2)
duration   = 1 if 7.0 ≤ TST/60 ≤ 9.0          NSF 2015 (Hirshkowitz)
efficiency = 1 if TST > 0 and eff ≥ 0.85      AASM (Schutte-Rodin 2008)
timing     = 1 if 2 ≤ mid < 4                 Buysse 2014
regularity = 1 if SRI ≥ 70                    derived from Windred 2024 (least-regular quintile < 71.6)
score = sum (0–4), stored with each dimension + tst_min, tib_min, efficiency_pct, midpoint, sri
```
Sources: no_validated_sleep_score (Established), sleep_health_score_multidim (Probable),
sleep_score_implementation_plan (Probable).

### 1.2 Sleep Regularity Index (Phillips 2017)

Main sleep only (naps excluded). Build a 7-day × 1440-minute asleep grid in local time
(asleep = any stage except awake `7`). Requires all 7 days present, else withhold
(`sri_window_under_7_nights`).
```
matches = Σ_{j=0..5} (1440 − |grid[j] XOR grid[j+1]|)
SRI     = −100 + (200 / (1440 × 6)) × matches        (range −100..100, round 2 dp)
```
Source: sleep_regularity_index (Established).

---

### 1.3 The night's headline (display only; D38)

A few words naming what shaped the night, after OpenStrap 2.1 (`strap-science` `SleepHeadline.kt`).
Never a score of Ridge's own; the strap's score is only one of the inputs. First match wins:
```
asleep = tst_min (else deep + light + REM);  WASO = awake stages between the first and last
  sleep stage (the 0x80 gap ignored);  wake-ups = separate runs of them
short   = asleep < 360 min                         (the bottom of Zepp's duration bands)
broken  = WASO ≥ 30 min or wake-ups ≥ 6            (where Zepp's continuity loses points; about
                                                    one an hour, high because wake detection is
                                                    staging's least reliable part)
Short, broken night · Short night · Broken sleep
strap score ≥ 90 Great night · ≥ 80 Good night
(deep + REM) / asleep < 0.25 Light sleep           (adults usually 40–50 %; the weakest cause)
otherwise Okay night
```
The strap's score is put into words on Zepp's own bands, the scale it is made on: 90+
Excellent, 80+ Good, 60+ Fair, below Poor (Ridge used 85/70/55 until D38).

## 2. Per-day metrics (dependency order)

Order in the old orchestrator: MVPA → activity/steps/distance/calories → VO₂max →
cardio load → sleep need/debt → recovery. Nights are always derived before days.

### 2.1 Steps and distance

- `steps_total` = strap daily counter (§01-5) if it reported for that day, else Σ
  per-minute steps (`< 250`). Stored with `source`, the other number, `sample_minutes`,
  and a caveat when the counter was read mid-day or read time unknown.
- No counter and zero per-minute rows → **no row** (unworn ≠ 0 steps).
- `distance_m_daily` = device metres, else `steps × 0.414 × height_m`.
  Source: distance_from_steps (Probable), steps_mortality (Established).

### 2.2 MVPA minutes (cadence, Tudor-Locke 2018)

Per minute (max spm within that minute), with the previous minute as a gate:
```
vigorous if spm ≥ 130 and prev ≥ 110
moderate if spm ≥ 100 and prev ≥ 80   (and not vigorous)
mvpa_min = moderate + 2 × vigorous            (WHO 2020 MET-equivalence)
```
Workouts are not added in. Weekly target 150. Sources: cadence_intensity (Established),
mvpa_minutes_mortality (Established).

### 2.3 Energy expenditure — MET-by-state, BMR-anchored (never HR-based)

```
BMR (Mifflin-St Jeor) = 10·kg + 6.25·cm − 5·age + (male ? +5 : −161)
per non-workout minute m, MET(m):
   steps > 0:  speed = steps × stride (m/min), stride = 0.414 × height
               VO2 = speed ≥ 134 ? 0.2·speed + 3.5 : 0.1·speed + 3.5     (ACSM)
               MET = VO2 / 3.5
   asleep:     0.95
   awake, a step within ±7 min: 1.55   (practitioner choice, not sourced)
   awake, isolated:             1.30   (Compendium 07021, sourced)
total = Σ MET(m) × BMR/1440  +  Σ device workout calories
active = max(0, total − BMR);  basal = BMR;  pal = total / BMR
```
Workout minutes are excluded from the walk and replaced by the strap's calories; a
workout with no calorie figure → caveat `workout_without_device_calories`. Stale weight
(>14 d) → caveat with the error bound `100 × 10 / BMR` % per kg; never withhold.
Individual error advertised: ±15–20 % (Brage 2015). Source:
energy_expenditure_derivation (Probable). **Hard rule: no Keytel/HR-EE for free-living.**

### 2.4 Cardio load — Banister TRIMP + Edwards zones (workouts, and moving minutes; D37)

```
HRmax = 208 − 0.7 × age                     (Tanaka 2001)
RHR   = latest rhr_daily within 30 d        (else WITHHOLD — never a default 60)
per minute with HR (mean valid HR in that minute):
   ΔHR  = clamp((HR − RHR)/(HRmax − RHR), 0, 1)
   TRIMP_minute = ΔHR × a × e^(b·ΔHR)      male (0.64, 1.92) · female (0.86, 1.67) · unknown → male
workout_load  = Σ TRIMP_minute over minutes inside a strap workout (start .. start + duration),
                whatever the intensity, asleep or not; each minute once
everyday_load = Σ TRIMP_minute over other minutes that are awake and pass
                  steps > 0 and (ΔHR ≥ 0.35, or steps ≥ 60 and ΔHR ≥ 0.20)       (steps: 0 < spm < 250)
                and have ≥ 3 of the 5 minutes ending with them (itself included) passing too
cardio_load = round(workout_load + everyday_load, 1)    both parts kept in its flags
per waking minute (sleep windows excluded), the descriptive zone time, every worn minute:
   zone  = highest i with HR/HRmax ≥ (0.50, 0.60, 0.70, 0.80, 0.90)[i]
   edwards = Σ (i+1)·zone_minutes[i];  hr_minutes = waking minutes with HR (Biology's "worn")
```
Why (D37, after OpenStrap 2.1): every waking minute used to count, so a seated, raised heart
rate (heat, coffee, a fever) added load and light days carried most of a hard day's. 0.35 is
Zepp's cut-off; sustained walking at about 100 steps a minute sits at HRR 0.24–0.34, hence the
walking line. Cycling or weights outside a recorded workout add nothing: record a workout.
Withhold if no profile, no fresh RHR, `HRmax − RHR < 1`, or no waking HR. Same TRIMP
function is used per workout. Sources: training_stress_score (Probable),
heart_rate_zones (Probable), maximum_heart_rate (Established), load_currency (Probable).

### 2.5 Strain 0–21 and ACWR (read-time)

```
strain = clamp(21 × (load / P95)^0.75, 0, 21)        P95 = 95th pct of cardio_load>0 over the 90 d ending that day
ACWR   = mean(last 7 cardio_load rows) / mean(last 28)   needs ≥ 28 rows and chronic > 0
```
The app shows the 7-day mean against 0.8–1.3× the 28-day mean as "below range", "in range"
or "a load spike" (Fitness, Today, a workout's load), never as a verdict.
ACWR is **Contested** (training_load_acwr): descriptive "load spike" only, never a
verdict; the 0.8–1.3/1.5 bands are soft heuristics.

### 2.6 Sleep need and debt (NSF 2015)

```
need = age ≥ 65 ? 450 : 480                 minutes (needs DOB only)
over the recorded nights in the 14 nights ending on day (TST from the 4-dim score):
   shortfall = Σ max(0, need − TST);  surplus = Σ max(0, TST − need)
   debt = max(0, shortfall − 0.5 × surplus)          no cap
```
Stored with avg TST, avg deficit, nights below need. Withhold: no DOB, no nights.
Source: sleep_need_debt (Probable).

### 2.7 Recovery score 0–100 (evidence-weighted, always shown with its breakdown)

```
baseline(metric) = median, robust SD (floor 0.5) over the 42 days before `day`; needs ≥ 5 points
hrv   sub = clamp(50 + 20·z)       z = (hrv_sleep_avg − median)/sd
rhr   sub = clamp(50 − 20·z)
rr    sub = clamp(50 − 15·z)       (respiratory_rate_sleep)
sleep sub = clamp(100 × TST / need)   only if a sleep_need row exists (no fallback 480)
weights: hrv 0.42 · rhr 0.28 · sleep 0.20 · rr 0.10, renormalised over factors present
score = Σ w·sub / Σ w                  withhold unless HRV or RHR present
```
Weights are not fitted — a product decision documented in recovery_readiness (Probable).
The old **live readiness** (recovery × (1 − 0.5 × min(1, today's load ÷ typical))) was
removed in 0.3.0 (D33): only exercise could move it. Energy (§2.12) answers "how much is
left now" instead. The typical load it used is still served, as the strain card's
`typical_load` (median `cardio_load` of the 30 days before, ≥ 5 rows).

### 2.8 Recovery signals (no composite)

Each marker vs its own recent history (≥ 5 days); HRV favourable z > 0.3, unfavourable
z < −0.5; RHR mirrored; sleep uses floor 1.0 min SD and absolute floors 360/300 min,
z thresholds −0.5 / −1.0. Source: `read/recovery_signals.py`.

### 2.9 Illness early-warning flag (safety-relevant)

```
per limb (RR = respiratory_rate_sleep; temp = night mean skin temp):
   history = values in [N−14, N−1];  need ≥ 10 nights PER LIMB
   delta = value_N − median(history);  mad = MAD(history)  (unscaled)
sustained = RR limb for night N−1 (vs its own [N−15, N−2]) has delta ≥ 1.5
high     = rr.delta ≥ 2.0 AND temp.delta ≥ 0.5 AND sustained
moderate = (rr.delta ≥ 2.0 AND rr.delta ≥ 1.5·rr.mad) OR (temp.delta ≥ 0.5 AND temp.delta ≥ 2.0·temp.mad)
mild     = never surfaced
```
Night N must have its own data (no borrowing). A re-run that no longer qualifies
deletes the flag; a flag is "active" for 2 days past its date. RR limb cited (Smarr
2020, Quer 2021); **+0.5 °C is an unsourced operating heuristic** and must be labelled
so. Hard override: an active flag or low recovery blocks any "push harder" advice.
Sources: illness_flag_plan (Probable), skin_temp_signals (Probable).

### 2.10 VO₂max — one metric, tiered instruments, no blending

Precedence: **graded GPS fit → HR-reserve inversion → Jurca non-exercise model**. The
two measured tiers need a phone-GPS track (see decisions: GPS is out of scope unless
re-added) — without GPS only Jurca remains.

**Jurca 2005 (fallback):**
```
CRF_METs = 18.07 + 2.77·male − 0.10·age − 0.17·BMI − 0.03·RHR + SRPA_METs[srpa]
SRPA_METs = [0, 0.32, 1.06, 1.76, 3.03]    self-reported 0–4 exercise category (a question, never inferred from steps)
VO2max = max(20, CRF_METs × 3.5)           ±1.45 METs (5.1 ml/kg/min) SEE
RHR = median rhr_daily over 7 days ending on day
```
Withhold: incomplete profile/weight, SR-PA unanswered, < 3 RHR days, RHR median
outside 40–100, 7-day RHR MAD > 8 bpm. Flag (don't withhold) age outside 20–70 or BMI
outside 16–45. Source: non_exercise_vo2max (Probable).

**Graded GPS fit (Carrier 2023, MAPE 6.85 %):** 30 s windows after a 180 s warm-up,
elevation from a DEM (SRTM), speed smoothed ±15 s, elevation ±25 s; per window VO₂ =
ACSM level × Minetti 2002 gradient-cost ratio; fit VO₂ = a·HR + b (≥ 6 windows, HR
range ≥ 15, R² ≥ 0.5, speed CV ≤ 0.20), extrapolate to HRmax; accept 20–85.
Minetti: running `155.4i⁵ − 30.4i⁴ − 43.3i³ + 46.3i² + 19.5i + 3.6` (walking
`280.5 −58.7 −76.8 51.9 19.6 2.5`), grade clamped ±0.45, cost floored 0.7. Source:
submaximal_vo2max (Probable). Full constants: `derive/vo2max_submax.py`.

**HR-reserve inversion (Swain & Leutholtz):** per steady window at ≥ 2.0 m/s and
0.35 ≤ %HRR ≤ 0.95, `VO2max = 3.5 + (VO2 − 3.5)/%HRR`; median of ≥ 6 windows with
≥ 40 bpm span. Source: hr_reserve_vo2max (**Contested**).

### 2.11 Biological age (documented exception to "no composites"; D36)

How old your habits look: OpenStrap's healthspan age (2.1, `docs/algorithms/healthspan.md`,
MIT/Apache-2.0), ported in `strap-science` `BioAge.kt` with its three worked examples as tests.
Each term is scored against the **population median** for your age and sex, so someone at every
median reads their own age.
```
age_shown = age + clamp(S × (c_fit + c_sri + c_sleep + c_strength), −5, +5)     S = 0.6
c_fit     = clamp(c_rhr + c_steps [+ c_vo2], −5, +5)       the fitness terms overlap: shared limit
c         = ln(HR) / G, G = 0.09165 (PhenoAge's Gompertz slope)
c_rhr      0.0940 × (max(rhr + 10, 45) − NHANES seated P50(age, sex)), ±4        Zhang 2016
             rhr = median rhr_daily; + 10 bpm for sleeping vs seated (Böhm 2020)
             ref men 69/68/67, women 74/71/70 at 30/50/70, linear between, flat outside
c_steps    Paluch 2022 age-specific curves (ln HR joined linearly), minus their value at the
             median (7,803 under 60, 5,649 from 60); ±1.5 under 55, ±2 from 65, blended between
c_sri      Windred 2024 duration-adjusted quintiles: SRI 68.2 +2.71 · 75.1 +0.68 · 80.9 0 ·
             85.2 −0.43 · 89.4 −0.57, linear, flat outside
c_sleep    Yuan 2024 device sleep, penalty only: 270 min +9.17 · 330 +2.12 · 390+ 0, then 0…+4
c_strength Momma 2022 RR by min/week (40 min: 0.83), credit only, 0 from 140 min
c_vo2      0.5 × ln(0.87)/G × (VO₂max − FRIEND P50)/3.5, ±2; only an exercise-based estimate,
             which Ridge doesn't have: the term is left out (= the median). Jurca is not used.
inputs, from the 180 complete days before the day:
  rhr_daily nights; steps_total on days cardio_load saw ≥ 600 waking HR minutes (10 h worn);
  one SRI a week (the latest night's sri in each 7-day block back from the window's end);
  mean tst_min of main nights; strap workouts of sport 52 (strength) or 50 (core), min/week
withhold: no date of birth or sex (bioage_needs_profile); age outside 20–79; under 30 days of
  data (bioage_too_early); fewer than 90 rhr nights, 90 worn step days, 8 weekly SRIs or 90 main
  nights in 180 days (each scaled to the days there are while under 180: "early")
band: each term at its band's ends (RHR: the CI's slopes; steps, SRI, sleep: ±50 % after limits;
  strength: the curve's CI), summed, shrunk, capped, then widened to ±2 y (±4 y early)
```
Shown in whole years as a range ("34–38"), "about N years younger/older than A", each habit as
"adds / takes off about N years" (S × c) with its figure and target (8,000–10,000 steps, at
least 6 h 30 m asleep, 40 min of strength a week), and a trend of the estimate as of each of the
last 12 weeks. Ridge's nightly low (the lowest 5-minute mean) sits a little under OpenStrap's
lowest 30 minutes, so the resting-HR term leans slightly young. Not taken: pace of aging, the
beta-blocker and pregnancy withholding (Ridge records neither). Source:
biological_age_estimate (Probable), and the papers above.

### 2.12 Energy 0–100, minute by minute (read-time, D35; Charge from D33 to 0.5)

A body battery: sleep fills it, the waking day drains it. Model from OpenStrap's Energy
(2.1, `docs/algorithms/recovery.md` and `calculations.md` §7, MIT/Apache-2.0), adapted to
Ridge's definitions. **No study validates a body-battery formula; every constant is a design
choice.** `strap-science` `Energy.kt`.

```
inputs per minute: valid HR (minute mean), steps (sum, 0 < spm < 250; any step = "moving"),
  stress (latest reading ≤ 10 min old, readings in a workout window dropped), sleep stage
  (any stage but the 0x80 gap is in sleep), workout phase (the strap's workout start..end =
  active, then 20 min = after), a high illness flag (§2.9) from its night's start to the next
  main sleep's start, the usual sleep window, main-sleep ends (wakes)
rhr, HRmax = cardio load's (§2.4); HRR = clamp((HR − rhr)/(HRmax − rhr), 0, 1)
start 8 days before the day at 50; a past day runs 14 h past its end, only to see whether a gap
  closed. One state per minute, first match wins:
  not worn           no HR, not asleep, in a gap ≥ 10 min that later data closes
                       drift − 0.0482 (value ≥ 50) or − 0.0349 (below), counted as living;
                       then NO VALUE once 384 not-worn minutes have built up since the last
                       wake, or from the start when the stretch covers ≥ ½ of the usual sleep
  no value           stays so until: a sleep stage (restart at 50), or, after a night off and
                       once a sleep has been seen, heart rate (restart at the median of the
                       last 7 wake values, 80 without any; the day is "estimated")
  sleep              + 0.006 × stage_w × relax × hrv_f × (100 − e)
                       stage_w deep 1.0 · light 0.8 · REM 0.65 · awake-in-sleep 0.1 · other 0.8
                       relax = clamp(1.2 − 0.04 × (HR − that night's rhr_daily), 0.5, 1.2); 1 without HR
                       hrv_f = clamp(0.75 + 0.25 × recovery HRV part / 50, 0.6, 1.1); 1 without one
  no data            no HR in a gap < 10 min, or one still open at the end: 0 (holds)
  effort = in a workout window, or moving, or in a run of ≥ 10 awake minutes outside
           workouts at HRR ≥ 0.30 (unrecorded cycling)
  exercise           HRR ≥ 0.30 and effort: − 0.04 − 0.32 × TRIMP_minute (§2.4's one TRIMP)
  exercise recovery  after-burn tally > 5 and HR ≤ previous + 2: − 0.04
                       (tally += exercise TRIMP; × 0.966 each non-exercise minute, half-life ~20 min;
                       zeroed by a restart)
  activity           (HRR ≥ 0.20 and effort) or ≥ 60 steps: − 0.04 − 0.32 × TRIMP_minute
  in a workout       active and HRR ≥ 0.10: activity; after and HRR ≥ 0.10: exercise recovery;
                       otherwise rest at − 0.04. Never stress or relax.
  stress             stress ≥ threshold for ≥ 3 awake minutes in a row outside workout windows:
                       − 0.04 − 0.007 × (stress − threshold)
  relax              stress < 25, < 10 steps and HRR < 0.20: + 0.00015 × (100 − e), no drain
  rest               − 0.0005 × e  (0.04 at 80, 0.025 at 50)
high illness flag: every gain × 0.5, every drain × 1.5
e kept in 0–100
threshold = median(calm stress readings, 14 d before the day) + 10, kept in 50–70
  calm = awake, < 10 steps, HRR < 0.20, outside workout windows; needs ≥ 3 days and ≥ 60
  readings, else 60 (Zepp's "medium")
usual sleep = median start and end (as minutes from the noon before the wake day) of the last
  14 main sleeps before the day, looked for over 30 days; midnight–06:00 without any
```
Served: the value at the last measured minute (today) or the day's end, at wake (the restart
value after a night off, else the end of the day's longest sleep run), high, low, recharged,
drained split into activity / stress / living (the 0.04 base of every movement or stress minute,
all of a rest or not-worn drift), the threshold used, `estimated` (the strap was off overnight),
and the curve every 10 min with no points where there is no value. Withheld without a date of
birth or a fresh measured RHR (`energy_needs_profile`), before the simulation has seen a sleep
(`energy_no_sleep`), on a day whose minutes all have no value (`energy_not_worn`) and on a day
with no measured minute (`energy_no_data`).

Not taken from OpenStrap: the strap's off-wrist marks in the activity record and its live wear
pushes (Ridge decodes neither, so a gap is the only not-worn signal); its moderate-flag
equivalent ("Watch"), which changes nothing there either; leaving workout-window stress out of
the day's stress card and charts (only Energy and its threshold drop those readings).

### 2.13 The strap's own figures, shown as the strap's (D33)

- **PAI:** the latest `pai_total_device` of the day (7-day total) and `pai_daily_device`;
  the week's daily points; goal 100 (Nes et al. 2017, HUNT). Not recomputed.
- **Breathing disturbances (ODI):** the `odi_*_device` night stamped at the day's local
  midnight. `drops/hour = odi_events × 3600 / odi_valid_time` (s); usual = median of the
  30 nights before (≥ 5). `odi_raw` = ODI × 100 (checked against real nights: the two agree
  within about 1 %). No severity words; under 5 an hour is stated as the usual
  range only in the explanation. Withheld when the strap measured 0 s (`odi_not_measured`).
- **SDNN:** chart series `hrv_sdnn` only; not mixed into recovery (which uses RMSSD).

---

## 3. Analytics (pattern finding over daily metrics)

| engine | method | thresholds |
|---|---|---|
| Baselines | median / MAD / quartiles over trailing 30 d, per-metric sentinel filters (e.g. RHR 30–120, HRV 5–200) | — |
| Anomalies | robust z vs 30-day baseline excluding the day | abs(z) ≥ 2.0 |
| Correlations | Spearman lag correlation between daily metrics (incl. next-day), Mann-Whitney U for logged-event days vs others, Benjamini-Hochberg FDR | abs(r) ≥ 0.30, n ≥ 10, event days ≥ 3, q ≤ 0.10, ≤ 90 pairs |
| Caffeine/alcohol cutoff | MWU on sleep outcomes for intake after hour H ∈ {12,14,16,18,20,22} vs before/none; earliest H significant **in the literature direction** | ≥ 10 intake nights, ≥ 5 after-H, ≥ 10 control, p ≤ 0.10, rank-biserial ≥ 0.30, q ≤ 0.20 |

Personal findings are n=1 and must never be presented as population research.

---

## 4. Inputs the science needs that the strap does not provide

Profile: date of birth, sex, height, weight log (time series), timezone, SR-PA answer
(0–4). Manual logs: alcohol, caffeine (with time), meditation, fasting, weight.

## 5. Streams collected but unused by any formula

`stress` (per minute), device `resting_hr`/`max_hr`, `manual_hr`, `stress_manual`,
device sleep score, device workout max HR, `odi_score_device`. `stress` feeds Energy
(§2.12, Charge until D35) since D33; PAI, ODI and SDNN are shown as the strap's figures (§2.13). wearable_stress_validity is Probable — if the
rebuild shows stress, it needs its own honest framing.

## 6. Where the math runs

**On the phone (D34).** One definition per metric, in `android/strap-science`; the server
stores raw data as a backup and computes nothing. The port was held to the former server's
golden outputs and, on real data, to its numbers day by day (the shadow comparison, D34 S2)
before the server's science was removed. (Before D34 the rule was the old app's: the phone
recomputes nothing and renders server payloads.)
