"""The illness / recovery early-warning flag — the PRODUCER.

## Why this module exists

``illness_flag`` had four readers and no writer: the Today pill
(``read/health_metrics.py``), the recovery-guidance override (``read/recovery.py``),
[[recovery_readiness]] D7's hard training override (``challenges/recovery_guard.py``),
and the outcome ledger's illness-day confound (``challenges/confounds.py``) — while
every ``INSERT INTO illness_flag`` in the tree lived in a test. The table was empty in
production, so the flag was permanently ``None``: the pill never rendered, the ledger
always found zero illness days, and a **safety-critical** override was wired to a dead
input. [[illness_flag_plan]] recorded the plan as "SHIPPED"; only the reading half was.
This module is the missing half — computed from the owner's own overnight data, written
once per owner per local day, from ``jobs/chain.py``.

It does NOT decide the user-facing sentence: ``read/health_metrics._illness_framing``
renders that from the stored deltas at read time, and ``framing`` is deliberately not a
column — one definition of the wording, in the place that shows it. No LLM is involved
on either side.

## The rule is the note's, and the numbers carry the note's confidence

[[illness_flag_plan]] §"Trigger logic" is the specification: baselines over ``[N-14,
N-1]`` (median + MAD, ≥10 nights); ``rr_delta = rr_N - rr_med``, ``temp_delta = temp_N -
temp_med``; **High** = ``rr_delta ≥ 2.0`` AND ``temp_delta ≥ 0.5`` AND night ``N-1``
also had ``rr_delta ≥ 1.5``; **Moderate** = one robust strong signal; **Mild** never
surfaced.

The two trigger values do **not** carry the same weight, and the constants below say so
individually rather than presenting a uniform "the research says". The ≥2 br/min
sustained-over-two-nights RR pattern is cited (Smarr 2020, Quer 2021). The +0.5 °C
skin-temperature cut is **our operating heuristic** — the note retired its former
"Lim 2024" attribution as unresolvable rather than repairing it, and says outright that
"no citation in this corpus establishes that number". It is implemented unchanged (that
value is shipped science; moving it is its own PR) but it is never labelled as evidence.

## Two limbs, two independent sufficiency gates

The note's step 1 computes both baselines and says "Need ≥10 nights of data; else skip".
Read as ONE joint gate, an owner whose strap reports respiratory rate but no usable skin
temperature would never get a flag — losing the **strong, cited** limb to the absence of
the weak, unsourced one, which is the "safety rule with a dead input" this module exists
to end. So the gate is PER LIMB: a limb under ≥10 nights contributes nothing, the other
still fires, and only when *neither* qualifies is the night skipped. That reading is
also what makes the note's own Moderate tier ("single strong signal", an OR over the two
limbs) reachable. It is an interpretation of an ambiguous line, stated not assumed.

## Which baseline window, and why it is not evidence

**14 nights.** [[respiratory_rate_normal]] §"How we compute it" carries the one canonical
table of the three shipped RR baselines — 14 nights for the illness flag, 42 days for
``recovery_score``, 30 days for the generic anomaly layer — and states plainly that
**none of the three lengths is sourced**. 14 is this consumer's documented window and the
one [[illness_flag_plan]] specifies, "to react fast enough to be an *early* warning".
Adopting either of the others would be a behaviour change to two metrics.

## Freshness: a flag is a claim about ONE night

A flag for day N computed from the newest night we happen to hold is exactly the
stale-as-current defect ``derive/freshness.py`` was extracted to make impossible (three
live wrong numbers this week). So night N's own inputs must exist for day N:
:func:`~strap_server.derive.freshness.unavailable_reason` is the gate, shared and unchanged,
and a night without data returns ``NOT_DERIVED_YET`` rather than borrowing a
neighbour's — the note's "**No data, no flag** — silently skip", made mechanical.

## Auto-clear, in the two places it can happen

[[illness_flag_plan]] §Frontend: "auto-clears the morning the deltas drop below the
moderate thresholds". Two mechanisms, because there are two ways to stop being flagged.
**This day stops qualifying**: a re-run for day N whose deltas no longer clear the bar
DELETES N's row — a row that no longer follows from the data must not survive because it
was once true. **No new day qualifies**: ``read/health_metrics._ILLNESS_ACTIVE_DAYS``
bounds a flag's life to 2 days past its date, so a recovered owner ages out with no
write at all. That constant stays the single definition of "active" and is never
re-answered here.

## Known limit: a late sync means no flag for that day

The chain fires at each owner's local 10:30 and dedups once it runs, so an owner who
syncs at 18:00 has no night-N data when it asks. The alternative — writing from the
ingest path too — would put a 16-night baseline query on every pushed day inside the
5 s push budget and give one row two producers. Chain-only is deliberate; the gap is
the one ``recs``, ``briefing`` and ``correlate`` already have.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, timedelta
from uuid import UUID

from strap_server.derive._common import Cur
from strap_server.derive.freshness import unavailable_reason
from strap_server.derive.robust import median, median_abs_deviation
from strap_server.log import get_logger
from strap_server.zones import ZoneLike, as_zones

log = get_logger(__name__)

# ── the note's numbers ────────────────────────────────────────────────────────

# Trailing nights in the personal baseline: [N-14, N-1]. [[illness_flag_plan]] §Trigger
# logic step 1. An ENGINEERING CHOICE, not a finding — [[respiratory_rate_normal]]
# §"How we compute it" records that none of this project's three RR baseline windows
# (14 / 42 / 30) is sourced, and that 14 is this consumer's, picked to react fast enough
# to still be an *early* warning.
BASELINE_NIGHTS = 14

# Baseline nights a limb needs before it may fire. [[illness_flag_plan]] §Trigger logic
# step 1 ("Need ≥10 nights of data; else skip") and Coach Directive 1. Applied per limb —
# see the module docstring for why, and for the ambiguity that reading resolves.
MIN_BASELINE_NIGHTS = 10

# Respiratory rate, br/min above the personal median. VALIDATED: [[illness_flag_plan]]
# §The evidence, "[Established] Respiratory rate ≥ +2 br/min sustained over 2 nights is a
# validated pre-symptom illness signal [Smarr 2020 (n≈271k); Quer 2021]" — the strong
# limb (★★★).
RR_TRIGGER_BPM = 2.0

# The prior night's RR delta that makes tonight's elevation "sustained" — the
# Smarr-2020 / Quer-2021 two-night convention. [[illness_flag_plan]] §Trigger logic
# step 3, High tier: "AND night N-1 also had rr_delta ≥ 1.5".
RR_SUSTAINED_BPM = 1.5

# Skin temperature, °C above the personal median. **OUR OPERATING HEURISTIC, NOT
# EVIDENCE.** [[illness_flag_plan]] §"Provenance of the +0.5 °C trigger": the former
# "Lim 2024" attribution could not be resolved and was RETIRED rather than replaced;
# §The evidence marks this line "[Our heuristic — unsourced]". [[skin_temp_signals]]
# describes a ~0.3–0.5 °C personal-baseline band and says "the band itself is unsourced
# … never state it as 'studies show 0.5 °C'". The value is unchanged (shipped science);
# only its label is honest now. It sits at the TOP of that band deliberately: the
# weaker, cycle- and ambient-confounded limb should fire less often.
TEMP_TRIGGER_C = 0.5

# Robustness multipliers for the single-signal (Moderate) tier: the delta must clear the
# absolute trigger AND this multiple of the limb's own MAD, so a person whose nights are
# naturally noisy is not flagged by their own normal spread. [[illness_flag_plan]]
# §Trigger logic step 3, Moderate tier — "rr_delta ≥ 2.0 AND rr_delta ≥ 1.5 × rr_mad"
# OR "temp_delta ≥ 0.5 AND temp_delta ≥ 2.0 × temp_mad". The temp limb's stricter 2.0×
# is the same "weaker limb fires less often" argument as its absolute cut.
RR_MAD_MULTIPLE = 1.5
TEMP_MAD_MULTIPLE = 2.0

# The derived per-night respiratory rate: a bounded window mean over the sleep window
# (`derive/hrv_spo2_resp.py`), the canonical nightly RR. [[respiratory_rate_normal]].
_RR_METRIC = "respiratory_rate_sleep"

# Skin temperature is a RAW per-sample metric, not a `derived_daily` one
# ([[skin_temp_signals]] §"Healthee implementation": "It is not a derive/ _upsert_daily
# daily metric; the analysis uses the overnight average"). The night's value is averaged
# over the sleep window here, exactly as the read layer does it — including the
# plausibility floor `read/sleep_page.py` applies (`value > 25`), the only skin-temp
# validity bound that exists in this tree. No upper bound is invented; there is none.
_TEMP_METRIC = "skin_temp_c"
_TEMP_MIN_VALID_C = 25.0

# The corpus notes a flag cites. Only the limbs that actually produced a delta are
# cited: a citation chip for a signal we could not measure is a fake citation, and the
# corpus rule is citations-real-or-absent.
_RR_NOTE = "respiratory_rate_normal"
_TEMP_NOTE = "skin_temp_signals"

# Why a night produced no assessment at all. A reason id names a state
# (`derive/freshness.py` §The vocabulary), and this one is only this metric's: the
# baseline window HAS nights, just too few to judge against. Distinct from the shared
# NOT_DERIVED_YET (night N itself never landed) — "we cannot judge yet" and "we have no
# input" are different facts and the job surface must be able to tell them apart.
INSUFFICIENT_BASELINE = "insufficient_baseline_nights"


@dataclass(frozen=True)
class Limb:
    """One signal's night-N deviation from its own trailing baseline, plus that
    baseline's spread. ``mad`` is UNSCALED (raw units) — the note's multipliers are
    quoted against a raw MAD, so it must not be run through ``robust_sd``."""

    delta: float
    mad: float


# ── the science (pure — known-value tested) ───────────────────────────────────


def limb_for(nights: Mapping[date, float], night: date) -> Limb | None:
    """Night ``night``'s delta + MAD vs the ``[night-14, night-1]`` personal baseline.

    ``None`` when the night has no value of its own, or when its baseline holds fewer
    than :data:`MIN_BASELINE_NIGHTS` nights — "not enough data" beats a guess, and the
    note's step 1 makes that gate explicit.

    Median and MAD come from ``derive/robust`` — the ONE definition of each
    (CLAUDE.md: one canonical definition per metric).
    """
    value = nights.get(night)
    if value is None:
        return None
    first = night - timedelta(days=BASELINE_NIGHTS)
    history = [v for day, v in nights.items() if first <= day < night]
    if len(history) < MIN_BASELINE_NIGHTS:
        return None
    return Limb(delta=value - median(history), mad=median_abs_deviation(history))


def severity_for(rr: Limb | None, temp: Limb | None, *, sustained: bool) -> str | None:
    """The flag tier for one night, or ``None`` for "do not surface".

    [[illness_flag_plan]] §Trigger logic step 3, in its own order (most → least severe):

      * **high** — both limbs over their trigger AND the RR rise sustained into a
        second night (the Smarr 2020 / Quer 2021 pattern);
      * **moderate** — ONE limb over its trigger *and* over its own MAD multiple, so a
        naturally noisy person is not flagged by their normal spread;
      * **mild** (a single-night ``rr_delta ≥ 1.5`` or ``temp_delta ≥ 0.3``) is
        deliberately NOT surfaced and therefore not represented here at all — the note
        calls it "suggestive but inconclusive", and the table's CHECK constraint admits
        only 'moderate' and 'high'.

    A night can clear both triggers without being sustained and still fall through to
    ``None`` if neither robust test passes. That is the note's shape, not an oversight:
    two unremarkable-for-this-person rises are not an early warning.
    """
    if _over_trigger(rr, RR_TRIGGER_BPM) and _over_trigger(temp, TEMP_TRIGGER_C) and sustained:
        return "high"
    if _robustly_over(rr, RR_TRIGGER_BPM, RR_MAD_MULTIPLE) or _robustly_over(
        temp, TEMP_TRIGGER_C, TEMP_MAD_MULTIPLE
    ):
        return "moderate"
    return None


def _over_trigger(limb: Limb | None, trigger: float) -> bool:
    """The limb rose at least ``trigger`` above its own median (absent limb → False)."""
    return limb is not None and limb.delta >= trigger


def _robustly_over(limb: Limb | None, trigger: float, mad_multiple: float) -> bool:
    """Over the absolute trigger AND over ``mad_multiple`` × this limb's own spread."""
    if not _over_trigger(limb, trigger) or limb is None:
        return False
    return limb.delta >= mad_multiple * limb.mad


def sustained_for(rr_nights: Mapping[date, float], night: date) -> bool:
    """Did the RR rise already clear ``1.5`` br/min on night ``N-1``?

    Night N-1's delta is taken against **its own** ``[N-15, N-2]`` baseline, which is
    the note's rule applied to that night rather than tonight's median reused for it.
    The windows overlap in 13 of 14 nights so the two rarely disagree — but "the rule
    also fired last night" is what the Smarr/Quer sustained-elevation convention
    actually claims, and it is cheap to mean it literally.

    Unmeasurable is ``False``: persistence we could not check is not persistence.
    """
    prior = limb_for(rr_nights, night - timedelta(days=1))
    return prior is not None and prior.delta >= RR_SUSTAINED_BPM


# ── the per-owner per-day pass ────────────────────────────────────────────────


def derive_illness_flag(cur: Cur, user_id: UUID, tz: ZoneLike, day: date) -> dict:
    """Compute and persist (or clear) one owner's illness flag for their local ``day``.

    Returns what happened, always naming the reason when nothing was written — a
    silently skipped safety check is indistinguishable from one that ran and found
    nothing (standards §Errors), and this step's whole history is "nobody noticed it
    was not running".
    """
    rr_nights = _rr_nights(cur, user_id, day)
    temp_nights = _temp_nights(cur, user_id, tz, day)
    stale = unavailable_reason(day, _newest_night(rr_nights, temp_nights, day))
    if stale is not None:
        return {"day": day.isoformat(), "severity": None, "skipped": stale}

    rr, temp = limb_for(rr_nights, day), limb_for(temp_nights, day)
    if rr is None and temp is None:
        return {"day": day.isoformat(), "severity": None, "skipped": INSUFFICIENT_BASELINE}

    sustained = sustained_for(rr_nights, day)
    severity = severity_for(rr, temp, sustained=sustained)
    if severity is None:
        return {"day": day.isoformat(), "severity": None, "cleared": _clear(cur, user_id, day)}
    _write(cur, user_id, day, severity, rr, temp, sustained=sustained)
    log.info("illness flag %s for owner %s on %s (sustained=%s)", severity, user_id, day, sustained)
    return {
        "day": day.isoformat(),
        "severity": severity,
        "sustained": sustained,
        "rr_delta_bpm": _rounded(rr),
        "temp_delta_c": _rounded(temp),
    }


def _newest_night(
    rr_nights: Mapping[date, float], temp_nights: Mapping[date, float], day: date
) -> date | None:
    """The most recent night at or before ``day`` for which we hold ANY input.

    Handed to ``freshness.unavailable_reason``, which answers ``None`` only when that
    night IS ``day``. Passing the newest night rather than asking "is there a row for
    day" is deliberate: it is the same shape the read surfaces use, so the producer and
    the readers cannot drift into two definitions of "current".
    """
    return max((night for night in (*rr_nights, *temp_nights) if night <= day), default=None)


def _rounded(limb: Limb | None) -> float | None:
    """A limb's delta at storage precision, or ``None`` when the limb did not run."""
    return round(limb.delta, 3) if limb is not None else None


def _notes_for(rr: Limb | None, temp: Limb | None) -> list[str]:
    """The corpus notes this flag actually rests on — measured limbs only."""
    return [note for note, limb in ((_RR_NOTE, rr), (_TEMP_NOTE, temp)) if limb is not None]


def _rr_nights(cur: Cur, user_id: UUID, day: date) -> dict[date, float]:
    """Nightly ``respiratory_rate_sleep`` over ``[day-15, day]`` — the canonical per-night
    RR (a bounded window mean, ``derive/hrv_spo2_resp.py``). [[respiratory_rate_normal]]."""
    cur.execute(
        "SELECT day, value FROM derived_daily "
        "WHERE user_id = %s AND metric = %s AND day >= %s AND day <= %s",
        (user_id, _RR_METRIC, _first_night(day), day),
    )
    return {row[0]: float(row[1]) for row in cur.fetchall() if row[1] is not None}


def _first_night(day: date) -> date:
    """The oldest night either limb reads: ``day - 15``, one past the baseline window.

    Fifteen trailing nights, not fourteen: :func:`sustained_for` re-derives night N-1's
    delta against N-1's OWN ``[N-15, N-2]`` window, so the extra night is what lets the
    sustained test BE the note's rule rather than an approximation of it.
    """
    return day - timedelta(days=BASELINE_NIGHTS + 1)


def _temp_nights(cur: Cur, user_id: UUID, tz: ZoneLike, day: date) -> dict[date, float]:
    """Mean overnight ``skin_temp_c`` per wake-date over ``[day-15, day]``.

    Averaged over the main sleep session's own window — the same read-time definition
    ``read/sleep_page.py`` uses, plausibility floor included — because skin temperature
    has no daily derived row to read ([[skin_temp_signals]]). Awake and daytime samples
    are excluded by construction: [[skin_temp_signals]] is explicit that daytime wrist
    temperature is "useless for fever/illness detection".

    The wake date comes from the session's END, matching ``derive._wake_date`` and
    therefore ``derived_daily.day`` — the two limbs must be keyed to the same night.
    """
    zones = as_zones(tz)
    cur.execute(
        "SELECT session.end_ts, sum(sample.value)::float, count(sample.value) "
        "FROM sleep_session session "
        "JOIN sample ON sample.user_id = session.user_id "
        "  AND sample.ts >= session.start_ts AND sample.ts < session.end_ts "
        "WHERE session.user_id = %s AND session.kind = 'main' "
        "  AND sample.metric = %s AND sample.value > %s "
        "  AND session.end_ts >= %s AND session.end_ts < %s "
        "GROUP BY session.end_ts",
        (user_id, _TEMP_METRIC, _TEMP_MIN_VALID_C, zones.bounds(_first_night(day))[0], zones.bounds(day)[1]),
    )
    # Grouped per wake DATE in Python (the date needs the zone history): one average over
    # every sample of the sessions ending that date, as the per-date SQL AVG was.
    sums: dict[date, list[float]] = {}
    for end_ts, total, n in cur.fetchall():
        acc = sums.setdefault(zones.date_of(end_ts), [0.0, 0])
        acc[0] += total
        acc[1] += n
    return {d: s / n for d, (s, n) in sums.items() if n}


def _write(
    cur: Cur,
    user_id: UUID,
    day: date,
    severity: str,
    rr: Limb | None,
    temp: Limb | None,
    *,
    sustained: bool,
) -> None:
    """Upsert the day's flag. ``framing`` is not stored — the reader renders it."""
    cur.execute(
        "INSERT INTO illness_flag (user_id, date, severity, rr_delta_bpm, temp_delta_c, "
        "  sustained, research_note_ids) VALUES (%s, %s, %s, %s, %s, %s, %s) "
        "ON CONFLICT (user_id, date) DO UPDATE SET severity = EXCLUDED.severity, "
        "  rr_delta_bpm = EXCLUDED.rr_delta_bpm, temp_delta_c = EXCLUDED.temp_delta_c, "
        "  sustained = EXCLUDED.sustained, research_note_ids = EXCLUDED.research_note_ids",
        (user_id, day, severity, _rounded(rr), _rounded(temp), sustained, _notes_for(rr, temp)),
    )


def _clear(cur: Cur, user_id: UUID, day: date) -> bool:
    """Drop ``day``'s flag if one is stored — the same-day half of the auto-clear.

    True only when a row actually went away, so "the deltas fell back" is reportable and
    does not read like "there was never anything here".
    """
    cur.execute("DELETE FROM illness_flag WHERE user_id = %s AND date = %s", (user_id, day))
    cleared = cur.rowcount > 0
    if cleared:
        log.info("illness flag cleared for owner %s on %s — deltas under trigger", user_id, day)
    return cleared
