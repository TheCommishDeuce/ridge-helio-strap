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

### 2.4 Cardio load — Banister TRIMP + Edwards zones (waking minutes only)

```
HRmax = 208 − 0.7 × age                     (Tanaka 2001)
RHR   = latest rhr_daily within 30 d        (else WITHHOLD — never a default 60)
per waking minute (mean valid HR in that minute, sleep windows excluded):
   ΔHR  = clamp((HR − RHR)/(HRmax − RHR), 0, 1)
   TRIMP += ΔHR × a × e^(b·ΔHR)      male (0.64, 1.92) · female (0.86, 1.67) · unknown → male
   zone  = highest i with HR/HRmax ≥ (0.50, 0.60, 0.70, 0.80, 0.90)[i]
cardio_load = round(TRIMP, 1);  edwards = Σ (i+1)·zone_minutes[i]
```
Withhold if no profile, no fresh RHR, `HRmax − RHR < 1`, or no waking HR. Same TRIMP
function is used per workout. Sources: training_stress_score (Probable),
heart_rate_zones (Probable), maximum_heart_rate (Established), load_currency (Probable).

### 2.5 Strain 0–21 and ACWR (read-time)

```
strain = clamp(21 × (load / P95)^0.75, 0, 21)        P95 = 95th pct of cardio_load>0 over the 90 d ending that day
ACWR   = mean(last 7 cardio_load rows) / mean(last 28)   needs ≥ 28 rows and chronic > 0
```
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
**Live readiness** (only for the current day):
`decay = 0.5 × min(1, cardio_load_today / median(cardio_load, prior 30 d))`,
`readiness = round(recovery × (1 − decay))`; needs ≥ 5 history rows. Weights are not
fitted — a product decision documented in recovery_readiness (Probable).

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

### 2.11 Biological age (documented exception to "no composites")

```
b = ln 2 / 7.7                          Gompertz mortality-rate doubling time
Δyears(HR) = clamp(ln(HR) / b, −10, +10)
fitness HR = 0.85 ^ ((VO2max − ref)/3.5)          ref = FRIEND treadmill median for age decade/sex
sleep HR:  h = measured_h + clamp(3.2 − 0.4·measured_h, 0, 1.2)     (Lauderdale 2008 self-report correction)
           h < 7 ? 1.06^(7 − h) : 1.13^(h − 7)                       (Yin 2017)
bio_age = chrono + Σ Δyears         WITHHELD entirely if any term is absent
```
FRIEND medians (male/female by decade 20…80): 46.5/36.6, 39.7/28.3, 35.3/25.7,
29.2/22.9, 24.6/19.6, 20.6/17.2, 17.6/15.4. Regularity term deliberately excluded.
Framed as a motivational estimate. Source: biological_age_estimate (Probable).

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
device sleep score, device workout max HR. wearable_stress_validity is Probable — if the
rebuild shows stress, it needs its own honest framing.

## 6. Where the math runs

The old phone deliberately recomputes nothing (e.g. `data/models/recovery_signals.dart`:
"not recomputed on the phone, ever"); it renders server payloads and caches them. Keep
that: **one** definition per metric, on the server, unless a metric is explicitly moved
on-device as its own decision.
