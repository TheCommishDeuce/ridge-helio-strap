"""Jurca 2005's self-reported physical-activity scale — the fifth input, and its price.

The other four inputs of the non-exercise VO2max model (age, sex, BMI, resting HR) are
things we measure. This one is a **question**, and it has its own file because it has its
own reason to change: the coefficients come from one published table, the reason it cannot
be measured comes from the construct it describes, and the sentence that tells the owner
what their answer costs has to stay next to both. [[non_exercise_vo2max]].

## SR-PA is self-reported, and until #108 we invented it (2026-08-02)

Jurca's fifth input is a five-level SELF-REPORTED category, and the NASA arm the
coefficients come from defines its levels in terms of DELIBERATE EXERCISE ("Aerobic
exercise such as run/walk for 1 to 3 hours per week"), with level 1 explicitly covering
"Little activity other than walking for pleasure" (Jurca 2005, Table 1).

We used to synthesise it from step cadence: ``mvpa._weekly_mvpa_to_srpa(moderate +
2*vigorous)`` over 7 days, banded at 10/20/60/180 min/wk. That crosswalk was never
published by anyone, and the construct it crosses is not the same construct:

- A cadence detector sees minutes above 100 steps/min. It cannot tell a deliberate
  walk-run session from walking to the shops, and Jurca's own level 1 puts the latter in
  the REFERENCE category.
- Where questionnaire categories have been compared with device categories the agreement
  is close to nil. Prince 2008's review of 187 comparisons puts the mean correlation at
  **0.37**, spanning -0.71 to 0.96, with self-report landing **both above and below** the
  device — so there is not even a stable direction to correct for, let alone a crosswalk.
- Measured on the real owner: 159 min/wk of MVPA-equivalent, ALL of it moderate (zero
  vigorous minutes), 107 of it on one day, from a person who states he does not exercise.
  The crosswalk scored him SR-PA-3 and paid him 5.5 years of biological age for walking.

So the input is taken from the profile, where the owner answers Jurca's own question, and
the estimate is WITHHELD until they do. That is ``withheld`` in its exact defined sense
(you can fix this, here is how) rather than ``excluded`` — one honest answer restores the
whole metric. Guessing conservatively instead (pinning SR-PA to 0) was rejected for the
same reason [[biological_age_estimate]] rejects a silently-omitted term: the reference
category is not "unknown", it is the claim "you are inactive".
"""

from __future__ import annotations

# ── SR-PA enters DUMMY-CODED, not as a linear 0-4 term (#108) ────────────────
#
# Jurca 2005, Table 5, NASA column, verbatim:
#
#     Intercept                18.07      SR-PA-1 (low)         0.32
#     Gender (F=0; M=1)         2.77      SR-PA-2 (moderate)    1.06
#     Age (years)              -0.10      SR-PA-3 (high)        1.76
#     BMI (kg/m2)              -0.17      SR-PA-4 (very high)   3.03
#     Resting HR (beats/min)   -0.03
#
# Methods, verbatim: "the dummy-coded five-category SR-PA scale according to the Pedhauzur
# method". SR-PA-0 is the reference level and is folded into the intercept, which is why
# it has no row.
#
# We were adding the CATEGORY NUMBER (0/1/2/3/4 METs). Every level above the reference was
# therefore over-credited: +0.68 METs at level 1, +0.94 at 2, +1.24 at 3, +0.97 at 4 — up
# to 4.3 ml/kg/min of fitness nobody earned, worth ~2.2 years of biological age at level
# 3. The correction leaves the reference category untouched, which is why the note's
# worked sanity check (40yo male, BMI 24, RHR 55, SR-PA 0 -> 38.9 ml/kg/min) is unchanged
# by it.
JURCA_SRPA_METS = (0.0, 0.32, 1.06, 1.76, 3.03)
SRPA_MIN, SRPA_MAX = 0, len(JURCA_SRPA_METS) - 1

# Machine-readable withhold reason. Distinct from ``PROFILE_INCOMPLETE`` on purpose:
# height/sex/dob/weight are facts the app already collects, while this one is a question
# nobody has ever been asked, and the two need different sentences. It is also the only
# gate on this metric that no amount of wearing the strap can clear.
WITHHOLD_SRPA_NOT_REPORTED = "srpa_not_reported"

# ...and what it would take to fix, in the second person. Has to explain why we are asking
# rather than measuring, because "we need your activity level" reads like a shortcoming of
# the device to someone wearing one all day.
SRPA_NOT_REPORTED_MESSAGE = (
    "This estimate needs one thing we cannot read off the strap: how much deliberate "
    "aerobic exercise you actually do in a typical week. Step counts cannot tell training "
    "apart from getting around, and guessing moves this number by years. Answer the "
    "activity-level question in your profile and the estimate returns."
)

# The footing of the input — attached to the fitness term by
# ``analytics/biological_age.py::CAVEAT_TERMS``, beside the anchor caveats from
# ``analytics/reference_scales.py``. It lives HERE, with the coefficients it prices, for
# the reason that module gives: a footing statement kept two files from its numbers is how
# the last uncited constant lost its provenance.
#
# The year figures are OWNER-INDEPENDENT and that is why they can be stated flatly in a
# constant: ΔAge = ln(0.85^(ΔMET)) / (ln2 / 7.7), so a step of ΔMET is worth the same
# 1.81 y/MET wherever on the scale it happens. Pinned by a known-value test.
#   0→1  0.32 MET = 0.6 y   1→2  0.74 = 1.3 y
#   2→3  0.70 MET = 1.3 y   3→4  1.27 = 2.3 y   0→4  3.03 = 5.5 y
SRPA_SELF_REPORTED = "vo2max_srpa_self_reported"
SRPA_SELF_REPORT_CAVEAT = {
    "reason": SRPA_SELF_REPORTED,
    "message": (
        "The fitness half of this number rests partly on your own answer about how much "
        "deliberate aerobic exercise you do in a typical week. We ask instead of "
        "measuring because a step counter cannot tell a training session from walking to "
        "the shops, and the model's own scale is about the former — its bottom category "
        "is 'little activity other than walking for pleasure'. That answer is worth real "
        "years: one category up or down moves this estimate by between half a year and "
        "two and a half, and the full range from 'inactive' to 'over three hours a week' "
        "is about five and a half years. Answer it as your ordinary week, not your best "
        "one. Everything else in the fitness term — your age, sex, BMI and resting heart "
        "rate — we measure."
    ),
}


def srpa_mets(srpa: int) -> float:
    """The METs Jurca's regression adds for self-reported activity category ``srpa``.

    Clamped to the published scale. The clamp is defensive only — ``profile.srpa`` carries
    a CHECK constraint and ``ProfileIn`` bounds the field, so an out-of-range value cannot
    reach here through any supported path; silently extrapolating a fifth coefficient
    would be worse than pinning to the last published one. [[non_exercise_vo2max]].
    """
    return JURCA_SRPA_METS[max(SRPA_MIN, min(SRPA_MAX, srpa))]
