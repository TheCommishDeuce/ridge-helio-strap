"""HR sample validity — the ONE definition of "is this heart-rate sample real?".

Every surface that reads raw ``sample`` rows where ``metric='hr'`` filters out
implausible values, because the strap emits artefacts: dropped-contact zeros,
sentinel rows, and PPG lock-on to a harmonic. Before this module the same filter
was hardcoded five times in two *different* semantics — three sites inclusive
(``value BETWEEN 30 AND 220``) and two exclusive (``value > 30 AND value < 220``) —
so a sample at exactly 30 or exactly 220 bpm counted toward the day's
``cardio_load`` but vanished from the same session's workout HR profile. One
sample, two answers, on two screens quoting the same TRIMP currency.

⚠ **This is an engineering artefact filter, NOT a research constant.** No note in
``packages/knowledge`` governs 30/220, and none is cited here on purpose:
``wearable_hr_validity`` is about PPG *accuracy* (±2–3 bpm at rest, 10–30 bpm under
intensity, skin-tone bias) and says nothing about plausibility bounds. A citation
to a note that does not support the claim is precisely the fabrication our
validator blocks in LLM output; the standards' "every constant derived from
research cites its note" does not apply to a constant that is not derived from
research. It is a plausibility gate, and it is documented as one.

The bounds are INCLUSIVE — ``[30, 220]``, both endpoints valid. Rationale:

  * Both endpoints are attainable human values, not artefacts. A trained
    endurance athlete's true resting HR reaches 30 bpm, and 220 is the ceiling of
    the classic ``220 − age`` HRmax formula (i.e. the theoretical maximum HRmax at
    age 0) — treating the ceiling itself as impossible is arbitrary.
  * The asymmetry of the error matters. Excluding a real sample silently
    under-measures a day's load and can erase an athlete's actual RHR — a *wrong
    number*, presented with full confidence. Including a borderline-plausible one
    costs a rounding error inside an average. For an honest-by-default product,
    under-measuring silently is the worse failure.
  * It is also what 3 of the 5 sites already did, so inclusive is the smaller
    behaviour change.

This layer is pure (no DB, no I/O) and lives in ``derive`` — the science layer —
so ``read`` may depend on it downward (standards §1: modules depend downward only),
exactly as ``derive/trimp`` is shared with ``read/workout``.
"""

from __future__ import annotations

from typing import Final, LiteralString

# Plausibility bounds for one raw HR sample, in bpm. INCLUSIVE on both ends — see
# the module docstring for why. Engineering artefact filter; no note governs these.
HR_VALID_MIN_BPM: Final[int] = 30
HR_VALID_MAX_BPM: Final[int] = 220

# The SQL predicate every ``sample``/``metric='hr'`` read filters with. Parameterized
# (``%s``) rather than interpolated, so the bounds above stay the single definition —
# there is no second copy of "30" and "220" hiding in a query string (standards §2:
# SQL is always parameterized). Bind ``HR_VALID_BOUNDS`` in the matching position.
HR_VALID_SQL: Final[LiteralString] = "value BETWEEN %s AND %s"

# The bind parameters for ``HR_VALID_SQL``, in order. Splice into a caller's tuple:
#   cur.execute(f"... AND {HR_VALID_SQL} AND ts >= %s", (user_id, *HR_VALID_BOUNDS, ts))
HR_VALID_BOUNDS: Final[tuple[int, int]] = (HR_VALID_MIN_BPM, HR_VALID_MAX_BPM)


def hr_is_valid(bpm: float) -> bool:
    """True iff one HR sample is physiologically plausible — the Python twin of ``HR_VALID_SQL``.

    Kept in lockstep with the SQL predicate by ``tests/derive/test_hr_validity.py``,
    which pins both against the same boundary table (29 / 30 / 220 / 221).
    """
    return HR_VALID_MIN_BPM <= bpm <= HR_VALID_MAX_BPM
