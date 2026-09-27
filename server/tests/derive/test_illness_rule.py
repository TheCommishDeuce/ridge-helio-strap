"""Known-value tests for the illness flag's trigger rule — the science, no database.

The numbers here are hand-computed from [[illness_flag_plan]] §"Trigger logic" and
checked against the note line by line, because this rule is the input to a
**safety-critical** override ([[recovery_readiness]] D7) and a rule nobody can
recompute by hand is a rule nobody can audit.

The fixtures are built so every expected value is exact in binary floating point
(halves and integers only), so an assertion can be `==` rather than `approx` and a
boundary test really tests the boundary.
"""

from __future__ import annotations

from datetime import date, timedelta

import pytest

from strap_server.derive.illness import (
    BASELINE_NIGHTS,
    MIN_BASELINE_NIGHTS,
    Limb,
    limb_for,
    severity_for,
    sustained_for,
)

NIGHT = date(2026, 5, 20)

# 14 baseline nights alternating 13 / 14 br/min. Median = (13 + 14) / 2 = 13.5 (the
# textbook even-length median `derive/robust.py` unified the tree onto); every
# deviation is 0.5, so MAD = 0.5. Both exact in binary.
_RR_BASELINE = {NIGHT - timedelta(days=k): 13.0 + (k % 2) for k in range(1, BASELINE_NIGHTS + 1)}

# 14 baseline nights alternating 33.0 / 33.5 °C. Median = 33.25, every deviation 0.25,
# so MAD = 0.25. Halves and quarters — exact.
_TEMP_BASELINE = {
    NIGHT - timedelta(days=k): 33.0 + 0.5 * (k % 2) for k in range(1, BASELINE_NIGHTS + 1)
}


def _rr(value: float, night: date = NIGHT) -> dict[date, float]:
    return {**_RR_BASELINE, night: value}


def _temp(value: float, night: date = NIGHT) -> dict[date, float]:
    return {**_TEMP_BASELINE, night: value}


# ── limb_for: the personal baseline and the deviation from it ─────────────────


def test_limb_is_the_night_minus_the_14_night_median_with_that_windows_mad() -> None:
    """The note's step 1 + 2, hand-checked: median 13.5, MAD 0.5, night 16 → delta 2.5."""
    limb = limb_for(_rr(16.0), NIGHT)
    assert limb == Limb(delta=2.5, mad=0.5)


def test_the_baseline_window_is_14_nights_and_excludes_the_night_itself() -> None:
    """A 15th night back must not move the median, and night N must not be in its own
    baseline — [[illness_flag_plan]] specifies ``[N-14, N-1]`` exactly."""
    with_extra = {**_rr(16.0), NIGHT - timedelta(days=BASELINE_NIGHTS + 1): 99.0}
    assert limb_for(with_extra, NIGHT) == Limb(delta=2.5, mad=0.5)


def test_a_limb_needs_ten_baseline_nights_before_it_may_speak() -> None:
    """Coach Directive 1: "≥10 nights; if it can't be computed, skip silently"."""
    nights = dict(list(_RR_BASELINE.items())[: MIN_BASELINE_NIGHTS - 1])
    assert limb_for({**nights, NIGHT: 16.0}, NIGHT) is None

    nights = dict(list(_RR_BASELINE.items())[:MIN_BASELINE_NIGHTS])
    assert limb_for({**nights, NIGHT: 16.0}, NIGHT) is not None


def test_a_night_with_no_value_of_its_own_has_no_limb() -> None:
    """ "No data, no flag" — the baseline alone can never produce a deviation."""
    assert limb_for(_RR_BASELINE, NIGHT) is None


# ── severity_for: the tiers, exactly as the note orders them ──────────────────


def test_no_flag_when_nothing_deviates() -> None:
    """The common case, and the one that must never cry wolf: an ordinary night."""
    assert (
        severity_for(limb_for(_rr(14.0), NIGHT), limb_for(_temp(33.0), NIGHT), sustained=False)
        is None
    )


def test_moderate_on_one_robustly_strong_signal() -> None:
    """Note step 3, Moderate: ``rr_delta ≥ 2.0 AND rr_delta ≥ 1.5 × rr_mad``.

    delta 2.5 ≥ 2.0, and 2.5 ≥ 1.5 × 0.5 = 0.75. The temperature limb sits flat, which
    is the point — the note's Moderate tier is a SINGLE strong signal.
    """
    verdict = severity_for(
        limb_for(_rr(16.0), NIGHT), limb_for(_temp(33.25), NIGHT), sustained=False
    )
    assert verdict == "moderate"


def test_the_rr_trigger_is_inclusive_at_exactly_two_breaths() -> None:
    """ "≥ +2 br/min" is the cited number (Smarr 2020 / Quer 2021) — ≥, not >.

    Built on a flat baseline so the delta is exactly 2.0 with no rounding anywhere:
    median 14.0, MAD 0.0, night 16.0.
    """
    flat = {NIGHT - timedelta(days=k): 14.0 for k in range(1, BASELINE_NIGHTS + 1)}
    limb = limb_for({**flat, NIGHT: 16.0}, NIGHT)
    assert limb == Limb(delta=2.0, mad=0.0)
    assert severity_for(limb, None, sustained=False) == "moderate"


def test_a_rise_under_the_trigger_is_mild_and_never_surfaced() -> None:
    """Note step 3, Mild: "suggestive but inconclusive — don't surface"."""
    assert severity_for(limb_for(_rr(15.0), NIGHT), None, sustained=False) is None


def test_a_noisy_persons_ordinary_spread_does_not_flag_them() -> None:
    """The MAD gate is why Moderate is not just "over 2 bpm".

    Baseline swinging 11/17 has median 14 and MAD 3.0, so a 2.5 bpm rise — over the
    absolute trigger — is well inside this person's own night-to-night noise:
    2.5 < 1.5 × 3.0 = 4.5. Flagging them would be the optimistic guess run backwards.
    """
    noisy = {NIGHT - timedelta(days=k): 11.0 + 6.0 * (k % 2) for k in range(1, BASELINE_NIGHTS + 1)}
    limb = limb_for({**noisy, NIGHT: 16.5}, NIGHT)
    assert limb == Limb(delta=2.5, mad=3.0)
    assert severity_for(limb, None, sustained=False) is None


def test_a_temperature_rise_under_the_heuristic_cut_does_not_flag() -> None:
    """The +0.5 °C trigger is the binding number on the weak limb — pinned deliberately.

    It is the value [[illness_flag_plan]] labels "[Our heuristic — unsourced]" after
    retiring the unresolvable "Lim 2024", and an unsourced constant is exactly the kind
    that drifts quietly. A +0.25 °C rise over a PERFECTLY FLAT baseline (MAD 0, so the
    robust gate is satisfied trivially and cannot be what refuses) must not flag: the
    only thing standing between this owner and a false alarm is the 0.5 itself.
    """
    flat = {NIGHT - timedelta(days=k): 33.0 for k in range(1, BASELINE_NIGHTS + 1)}
    limb = limb_for({**flat, NIGHT: 33.25}, NIGHT)
    assert limb == Limb(delta=0.25, mad=0.0)
    assert severity_for(None, limb, sustained=False) is None
    assert severity_for(None, limb_for({**flat, NIGHT: 33.5}, NIGHT), sustained=False) == "moderate"


def test_high_needs_both_limbs_and_a_sustained_rise() -> None:
    """Note step 3, High: ``rr_delta ≥ 2.0`` AND ``temp_delta ≥ 0.5`` AND sustained."""
    rr, temp = limb_for(_rr(16.0), NIGHT), limb_for(_temp(34.0), NIGHT)
    assert temp == Limb(delta=0.75, mad=0.25)
    assert severity_for(rr, temp, sustained=True) == "high"


def test_both_limbs_without_persistence_fall_back_to_moderate() -> None:
    """Sustained is what separates High from Moderate — it is not decoration.

    One night with both signals up is the SAME evidence as one night with one signal
    up, until it repeats. The note reserves the stronger framing for the repeat.
    """
    rr, temp = limb_for(_rr(16.0), NIGHT), limb_for(_temp(34.0), NIGHT)
    assert severity_for(rr, temp, sustained=True) == "high"
    assert severity_for(rr, temp, sustained=False) == "moderate"


def test_a_missing_limb_never_blocks_the_other() -> None:
    """The per-limb sufficiency reading: an owner with no usable skin temperature must
    still get the strong, cited respiratory-rate limb (see the module docstring)."""
    assert severity_for(limb_for(_rr(16.0), NIGHT), None, sustained=False) == "moderate"
    assert severity_for(None, limb_for(_temp(34.0), NIGHT), sustained=False) == "moderate"
    assert severity_for(None, None, sustained=True) is None


def test_the_weak_limb_is_held_to_a_stricter_robustness_bar() -> None:
    """2.0 × MAD for temperature vs 1.5 × for RR — the note's deliberate asymmetry.

    The SAME +0.55 °C rise, over two different personal histories. Against a steady
    baseline (33.0/33.4 → median 33.2, MAD 0.2) it clears 2.0 × 0.2 = 0.4 and flags.
    Against a person whose nights already swing twice as much (33.0/33.8 → median 33.4,
    MAD 0.4) it fails 2.0 × 0.4 = 0.8 and does not — the whole point of judging a rise
    against the individual's own spread rather than a population number.

    A rise of this size would have flagged on the RR limb's looser 1.5 × in both cases;
    the weaker, cycle- and ambient-confounded limb is deliberately harder to trip.
    """
    steady = {
        NIGHT - timedelta(days=k): 33.0 + 0.4 * (k % 2) for k in range(1, BASELINE_NIGHTS + 1)
    }
    flags = limb_for({**steady, NIGHT: 33.75}, NIGHT)
    assert flags is not None
    assert flags.delta == pytest.approx(0.55) and flags.mad == pytest.approx(0.2)
    assert severity_for(None, flags, sustained=False) == "moderate"

    swingy = {
        NIGHT - timedelta(days=k): 33.0 + 0.8 * (k % 2) for k in range(1, BASELINE_NIGHTS + 1)
    }
    quiet = limb_for({**swingy, NIGHT: 33.95}, NIGHT)
    assert quiet is not None
    assert quiet.delta == pytest.approx(0.55) and quiet.mad == pytest.approx(0.4)
    assert severity_for(None, quiet, sustained=False) is None


# ── sustained_for: the two-night convention ──────────────────────────────────


def test_sustained_reads_the_prior_night_against_its_own_baseline() -> None:
    """ "night N-1 also had rr_delta ≥ 1.5" — the Smarr/Quer two-night pattern.

    Night N-1's baseline is ``[N-15, N-2]``, which alternates the same way, so its
    median is 13.5 too; a 15.0 there is a +1.5 delta — the boundary, inclusive.
    """
    prior = NIGHT - timedelta(days=1)
    nights = {
        **{NIGHT - timedelta(days=k): 13.0 + (k % 2) for k in range(1, BASELINE_NIGHTS + 2)},
        prior: 15.0,
        NIGHT: 16.0,
    }
    assert sustained_for(nights, NIGHT) is True


def test_a_single_elevated_night_is_not_sustained() -> None:
    assert sustained_for(_rr(16.0), NIGHT) is False


def test_persistence_we_cannot_measure_is_not_persistence() -> None:
    """The prior night without its own 10-night baseline reads False, never True.

    "We could not check" must land on the safe side of a claim about the owner's data,
    and the safe side of `sustained` is the one that does not escalate to `high`.
    """
    short = {NIGHT - timedelta(days=k): 13.0 + (k % 2) for k in range(1, MIN_BASELINE_NIGHTS)}
    assert sustained_for({**short, NIGHT - timedelta(days=1): 20.0, NIGHT: 16.0}, NIGHT) is False
