"""Banister TRIMP — the ONE canonical implementation of Healthee's load currency.

Healthee reasons over exactly one load unit: HR-based Banister TRIMP, surfaced as
``cardio_load`` [[load_currency]]. This module is that unit's single definition;
every surface that quotes a TRIMP (the daily ``derive/cardio_load`` accumulation
and the per-session figure in ``read/workout``) imports it from here, so the same
athlete-minute can never score two different loads on two different screens.

The formula, VERIFIED against Banister 1991 [[training_stress_score]]::

    ΔHR   = (HR_ex − HR_rest) / (HR_max − HR_rest)   # Karvonen HR-reserve fraction, 0–1
    y     = 0.64 · e^(1.92 · ΔHR)   (men)            # lactate-derived weighting
            0.86 · e^(1.67 · ΔHR)   (women)
    TRIMP = Σ_minutes ( 1 min · ΔHR · y )

ΔHR is a *fraction of the reserve*: 0–1 by definition. It is clamped to that range
because HR_max is Tanaka-*estimated* (``208 − 0.7 × age``), so a real session
routinely records HR above it — and the weighting is exponential, so an unclamped
sample at ΔHR 1.2 contributes ~76% more than the ceiling the formula defines.
Clamping keeps an HR_max estimation error from being amplified into the load
history; it does NOT discard the minute (it still scores the maximum load).

This layer is pure (no DB, no I/O) and lives in ``derive`` — the science layer —
so ``read`` may depend on it downward (standards §1: modules depend downward only).
"""

from __future__ import annotations

import math
from collections.abc import Iterable

# Banister lactate-weighting coefficients (a, b) in y = a · e^(b · ΔHR), by sex.
# [[training_stress_score]] (VERIFIED against Banister 1991).
TRIMP_WEIGHTS: dict[str, tuple[float, float]] = {
    "male": (0.64, 1.92),
    "female": (0.86, 1.67),
}
_DEFAULT_SEX = "male"


def trimp_weights(sex: str | None) -> tuple[float, float]:
    """The (a, b) lactate-weighting pair for a sex; men's pair when unknown.

    Unknown/absent sex falls back to the men's coefficients — the same default both
    call sites already carried, kept here so there is one fallback, not two.
    """
    return TRIMP_WEIGHTS.get(sex or _DEFAULT_SEX, TRIMP_WEIGHTS[_DEFAULT_SEX])


def hr_reserve_fraction(hr: float, rhr: float, hrmax: float) -> float:
    """Karvonen HR-reserve fraction ΔHR, clamped to its defining range 0–1.

    Clamped, not filtered: HR below rest scores 0 load, HR above the Tanaka-estimated
    HR_max scores the ceiling load. Caller must ensure ``hrmax > rhr``.
    """
    return max(0.0, min(1.0, (hr - rhr) / (hrmax - rhr)))


def trimp_minute(hr: float, rhr: float, hrmax: float, sex: str | None) -> float:
    """The Banister TRIMP contribution of ONE minute at ``hr``. Unrounded."""
    a, b = trimp_weights(sex)
    dhr = hr_reserve_fraction(hr, rhr, hrmax)
    return dhr * a * math.exp(b * dhr)


def trimp_total(hrs: Iterable[float], rhr: float, hrmax: float, sex: str | None) -> float:
    """Banister TRIMP summed over per-minute heart rates. Unrounded.

    Each element is taken to be one minute — the sum's unit is minutes × weighting,
    exactly as the note defines it.
    """
    a, b = trimp_weights(sex)
    total = 0.0
    for hr in hrs:
        dhr = hr_reserve_fraction(hr, rhr, hrmax)
        total += dhr * a * math.exp(b * dhr)
    return total
