"""Robust dispersion — the ONE canonical MAD->sigma conversion.

Healthee judges a person against their OWN trailing history using a median and a
median absolute deviation (MAD) rather than a mean and a standard deviation: one bad
night must not move the yardstick it is being measured against. Converting that MAD
into a normal-equivalent sigma is what makes a z-score comparable, and this module is
that conversion's single definition. Every surface that scales a MAD — the analytics
baselines, the ``recovery_score`` derivation, and the Today page's recovery signals —
imports ``MAD_TO_SD`` from here, so the same flat history cannot yield two different
z-scores on two different screens.

It lived in three modules before this one existed (``analytics/metrics.py``,
``read/recovery.py``, ``derive/recovery.py``) — see ``derive/trimp.py``, the same
pattern applied to the load currency.

``MAD_TO_SD`` is a STATISTICAL IDENTITY, not a research claim. For X ~ N(mu, sigma),
MAD = sigma * Phi^-1(0.75), hence sigma = MAD / Phi^-1(0.75) = MAD * 1.4826... It
therefore carries NO knowledge citation: the corpus has no note that grounds it, and
aiming it at the nearest plausible note would be a fake citation for anyone tapping
the ⓘ. (A citation to a "baselines" id sat on the analytics copy until 2026-07-17;
no such note ever existed — it named a MODULE, ``analytics/baselines.py``, and
resolved to nothing.) The baselines this constant FEEDS are grounded: the
trailing-window median+MAD personal baseline behind ``recovery_score`` is specified
in [[recovery_readiness]].

This layer is pure (no DB, no I/O) and lives in ``derive`` — the science layer — so
``read`` and ``analytics`` may depend on it downward (standards §1: modules depend
downward only, never upward).
"""

from __future__ import annotations

from collections.abc import Sequence

# 1/Phi^-1(0.75) = 1/0.6744897501960817 = 1.4826022185056018, to 4dp. A statistical
# identity (see the module docstring) — deliberately uncited. Verified against
# `statistics.NormalDist` in tests/derive/test_robust.py.
MAD_TO_SD = 1.4826


def median(values: Sequence[float]) -> float:
    """The median of a non-empty sample: the MEAN of the two central values if even.

    The textbook definition, and now the ONLY one in the tree — enforced by
    ``tests/derive/test_robust.py::test_no_module_hand_rolls_a_median``.

    Five modules used to hand-roll it, and three of those took the UPPER-middle value
    of an even-length sample (``xs[len(xs) // 2]``), which is a different statistic
    wearing the same name: on ``[1, 2, 3, 4]`` it reports 3, not 2.5. That divergence
    reached user-facing numbers — the Today page's sleep-duration z-score, the recovery
    baseline's MAD, the readiness-decay reference load and the streak-protection
    threshold each measured against a slightly different "usual". Unifying them was a
    deliberate science-behaviour change (2026-07-31, its own PR with known-value tests),
    not a silent side effect of extracting a helper.
    """
    ordered = sorted(values)
    n = len(ordered)
    if not n:
        raise ValueError("median of an empty sample is undefined")
    mid = n // 2
    return ordered[mid] if n % 2 else 0.5 * (ordered[mid - 1] + ordered[mid])


def median_abs_deviation(values: Sequence[float]) -> float:
    """MAD = median(|x - median(x)|) — spread that a single outlier cannot inflate.

    Unscaled, in the sample's own units: a MAD in bpm stays bpm. Multiply by
    :data:`MAD_TO_SD` (or call :func:`robust_sd`) only when a normal-equivalent sigma
    is what's wanted; a threshold quoted in raw MAD — such as the 8 bpm noise gate in
    [[non_exercise_vo2max]] — must NOT be scaled.
    """
    med = median(values)
    return median(tuple(abs(v - med) for v in values))


def robust_sd(mad: float, floor: float = 0.0) -> float:
    """MAD scaled to a normal-equivalent sigma, never returned below ``floor``.

    ``floor`` is a DEGENERATE-HISTORY GUARD, not science: it exists so a history flat
    enough to give MAD ~ 0 divides into a finite z instead of exploding. It is
    inherently SCALE-DEPENDENT — a floor in bpm is meaningless in minutes — so there
    is no shared default and each caller names its own, in its own units, next to the
    baseline it guards. The default 0.0 leaves the conversion unfloored (the analytics
    baselines instead guard ``robust_sd == 0`` at the z-score itself).
    """
    return max(mad * MAD_TO_SD, floor)
