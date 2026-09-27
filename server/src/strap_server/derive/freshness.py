"""Is the newest stored row a claim about the owner's TODAY? — the one answer.

## The defect this module exists to make impossible

A consumer reads the NEWEST row of a daily metric, drops its ``day``, and presents the
value as current. It has shipped live wrong numbers three times: a stale VO₂max as the
fitness hero (``read/vo2max.py``), a stale VO₂max spent as years inside the biological
age (``analytics/biological_age.py``), and the five sites closed alongside this module —
the SRI in the biological age and in ``/api/sleep/consistency``, sleep debt and "last
night's sleep" on the Today page, and a stale recovery score fed to the LLM as *today's*
readiness ceiling.

The shape is always identical, so the answer is one function rather than five date
checks. Five checks is how a metric ends up with two definitions of "current" —
the failure mode CLAUDE.md names explicitly.

## What "current" means here, and why it is a single rule

**A stored row is a claim about the day it is keyed to, and about no other day.** The
owner's today is therefore the only anchor: not "recent", not "within N days". A
tolerance would be a tunable, and a tunable in an honesty gate is a place to hide.

That rule has one visible consequence worth stating plainly: between an owner's local
midnight and their morning sync, today's rows do not exist yet, so these metrics read as
:data:`NOT_DERIVED_YET` — "sync the strap" — rather than showing yesterday's number.
That is the correct answer. We genuinely do not have today's yet, and the reason
vocabulary distinguishes *waiting on a sync* from a gate that has REFUSED, so the owner
is told which one they are looking at.

## The vocabulary (reason ids are shared, messages are not)

A **reason id** names a state, and one state has exactly one id — the ids below cover
conditions shared across metrics, and a metric adds its own only for a gate only it has
(e.g. ``derive/vo2max.py``'s RHR-noise withhold).

A **message** is the second-person "here is what we'd need", and it is deliberately
per-metric: what would restore a VO₂max is not what would restore an SRI. Each metric
owns a ``…_MESSAGES`` dict keyed by these ids. :data:`NOT_DERIVED_YET_MESSAGE` is the
default wording for metrics with nothing more specific to say.

## Weight: the one documented exception, and why it is here rather than elsewhere

:func:`unavailable_reason` above asks "is the newest row keyed to today", which is the
right question for a metric we DERIVE every night — a value for today either exists or
is waiting on a sync. Body weight is not that kind of row. Nobody derives it; the owner
types it in when they feel like it, so "today's weight" exists only on days they stepped
on a scale. Applying the today-or-nothing rule to it would take BMI, BMR, VO₂max and the
biological age dark on every day the owner did not weigh themselves, and it would be
wrong to do so: a two-day-old weight measurably IS this person's mass
([[weight_bmi_body_composition]]).

So weight gets a horizon, and the horizon lives HERE — in the module that already
answers "is this current" — rather than in the four modules that consume weight. That is
the whole point of one module: :data:`WEIGHT_MAX_AGE_DAYS` is a tunable, tunables in
honesty gates are places to hide, and the mitigation for a place to hide is that there is
exactly one of it, with its evidence written next to it. See :func:`weight_is_stale`.

## A MEASURED VO₂max is the second row of that kind (#117)

Nobody derives a session-measured VO₂max nightly either — it exists only on days the
owner recorded a qualifying effort, which for a real owner is a handful of days a year.
Today-or-nothing would mean the measured tier of ``vo2max_estimate`` could never win, so
it gets a horizon too, by the same rule and for the same reason. Its evidence is entirely
its own and sits with :data:`MEASURED_VO2MAX_MAX_AGE_DAYS`.

## One question, TWO answers: withhold and caveat (#127)

Answering "is this input a claim about this day" does not settle what to do about a *no*.
:func:`withheld_block` refuses; :func:`caveat_block` serves the number and names the tilt.
Both live here, next to each other and next to the one question, precisely so the choice
between them is visible as a choice — a metric that quietly picked one is how a rule ends
up applied in two places and two ways.

The branch is the METRIC's decision and is argued at its call site from the SIZE of the
tilt against that metric's own published error: ``derive/vo2max.py`` withholds,
``derive/energy.py`` caveats, and each says why. What is never the metric's decision is
the question or its horizon — those are :func:`weight_is_stale` for every consumer, which
is why a stale weight cannot mean 14 days to the fitness card and 30 to the calorie card.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import date

# ── shared reason ids ────────────────────────────────────────────────────────

# The newest row is not today's and no gate refused: the day simply has not been derived
# (nothing synced for it yet). Not a withhold any research note asks for — it shares the
# vocabulary because the USER-VISIBLE state is the same ("there is no number for today"),
# and a payload that names every other absence and shrugs at this one would be the same
# silence in a different place.
NOT_DERIVED_YET = "not_derived_yet"

# ``derive._common._load_profile`` returned None: height/sex/dob incomplete, or no
# logged weight. One id because it is one condition — the shared loader's one failure —
# however many metrics happen to depend on it.
PROFILE_INCOMPLETE = "profile_or_weight_missing"

# A rolling-window metric whose window contains no recorded nights at all. Distinct from
# NOT_DERIVED_YET: the derivation would run and still write nothing, because there is no
# sleep to compute over. "No data" and "not computed" are different states and must stay
# distinguishable to the caller (standards §1).
NO_NIGHTS_IN_WINDOW = "no_recorded_nights_in_window"

# There is no age-and-sex reference distribution for this owner, because their date of
# birth or sex was never recorded — so anything priced AGAINST the population (a median,
# a percentile, a gap-scaled projection) has no reference to be priced against. One id,
# because it is one condition: ``read/vo2max.py`` declines to name a ``median_for_age``,
# and every consumer of that null is looking at the same absence. Distinct from
# PROFILE_INCOMPLETE, which additionally wants a height and a logged weight — a metric
# that needs only the demographics must not report the weight's absence as its reason.
NO_AGE_MEDIAN = "no_age_sex_median"

# No date of birth on the profile. Narrower than PROFILE_INCOMPLETE and deliberately its
# own id: sleep need is a function of AGE alone (NSF 2015), so reporting it under an id
# that names a height and a logged weight would tell the owner to do two things that
# would not bring the number back. "One state, one id" cuts both ways — two states must
# not share one id either.
DOB_MISSING = "date_of_birth_missing"

# A CAVEAT id, not a withhold: the strap's own daily counter was read while the day it
# counts was still running, so the number is that day up to the moment it was read. The
# value is served — it is a real measurement of a real interval — and this says which
# interval. ``derive/device_totals.py``'s own comment made the point and nothing acted on
# it: "a counter read at 09:00 is a statement about a partial day".
COUNTER_MID_DAY = "device_counter_read_mid_day"

# The other half of the same disclosure, and its own id because it is a DIFFERENT state:
# the counter's reading carries no read instant at all, so we cannot say which interval it
# covers. True of every ``device_daily_total`` row written before ``0019`` and of every row
# an app build older than that writes.
#
# It is not COUNTER_MID_DAY with a missing field. "This counts the day up to 09:00" and
# "we do not know what this counts" are different sentences about a person's day, and one
# id for both would be the collapse this vocabulary exists to prevent — the same rule
# DOB_MISSING states one screen up: two states must not share one id. The write-path
# audit's A1 is precisely what happened when the third state, "unknown", was silently
# folded into "read after the day closed": a true caveat vanished.
COUNTER_READ_TIME_UNKNOWN = "device_counter_read_time_unknown"

NOT_DERIVED_YET_MESSAGE = "Today's number has not been computed yet — sync the strap."

# The most recent logged weight is too far from the day being computed for it to be a
# statement about the owner's body on that day. One id, because it is one condition
# however many models spend the weight (BMI in ``derive/vo2max.py``, BMR in
# ``derive/energy.py``, the weight card, the profile payload).
WEIGHT_STALE = "logged_weight_stale"

# Weight's message IS shared, unlike every other message in this vocabulary, because
# weight has exactly one restoring action wherever it is missed: log a weight. The
# per-metric rule exists so "what would bring back a VO₂max" and "what would bring back
# an SRI" can differ; here they cannot. A metric that wants to say more (VO₂max does —
# it has to explain that BMI is the thing the weight feeds) still keys its own dict on
# :data:`WEIGHT_STALE` and writes its own sentence.
WEIGHT_STALE_MESSAGE = (
    "The last weight you logged is more than two weeks old, so we can't call it your "
    "weight today — log a new one and this comes straight back."
)

# How far a logged weight may sit from the day being computed before we stop treating it
# as that day's weight.
#
# ## Why 14, and what is actually evidence for it
#
# [[weight_bmi_body_composition]] (grade Established) states plainly that it could NOT
# source a detection window: "We could not source 'how many days until a trend is real.'
# The literature gives a noise floor, not a validated detection window." So this number
# is NOT lifted from a paper, and pretending otherwise would be the exact failure this
# repo keeps re-learning. What the corpus does give:
#
#   * The single verified measurement of how far body mass actually DRIFTS over a stated
#     elapsed interval: 0.26 ± 1.2 kg over two weeks of unrestricted free living, of
#     which 84% is fat-free mass (Bhutani et al. 2017, n = 46, isotope dilution + serial
#     DXA).
#   * The noise floor of a single weigh-in: 0.51 ± 0.20 kg (Cheuvront et al. 2004) and a
#     0.6 kg weekly typical error (Kutáč 2015).
#
# Read together: across two weeks the expected drift (0.26 kg) is SMALLER than the error
# of weighing yourself twice (~0.5–0.6 kg). Inside that window the weight we hold and the
# weight the owner would read today are not distinguishable by our own measurement.
#
# Fourteen days is therefore the far edge of what the corpus can support, not a point at
# which weight "goes wrong": past it we have no measured drift figure at all, and
# extrapolating one is the optimistic guess that "not enough data" is supposed to beat.
# Two weeks is also the interval the note's own coach directives are written in
# ("compare rolling means at least two weeks apart").
#
# Widening this constant is allowed — but it needs evidence in this comment, not a
# product argument, because everything below it is a claim about somebody's body.
WEIGHT_MAX_AGE_DAYS = 14


# How far a SESSION-MEASURED VO₂max may sit from the day it is offered as, before it
# stops being a claim about that day.
#
# ## The rule is the weight rule; only the evidence is different
#
# A held measurement stays current while the drift we would expect over the interval is
# SMALLER than the error of the instrument that measured it. Past that we cannot tell the
# held value from a fresh one, so holding it invents nothing; before that we can, so
# holding it does.
#
# **How fast a real VO₂max moves.** [[specificity_and_recovery]], grade Established, from
# a systematic review of endurance detraining [Barbieri et al. 2024] and the classic
# two-part review [Mujika & Padilla 2000a/b]: VO₂max falls **~4–7% by ~2–3 weeks of
# cessation and ~13% by ~8 weeks**, and "brief, deliberate rest — days, not weeks — …
# does not cause meaningful detraining". Note which rate is NOT the binding one: the
# ~1%/yr untrained decline [[non_exercise_vo2max]] cites is 0.015 ml/kg/min over a
# fortnight, four orders below anything we can resolve. Ageing never bounds this; training
# and its absence do.
#
# **What our own instruments can resolve.** The graded fit's independent accuracy is MAPE
# 6.85% [Carrier 2023, [[submaximal_vo2max]]] ≈ 2.7 ml/kg/min at 40. The reserve
# inversion's modelled 1 SD for a six-window session median is ±3.2 ml/kg/min at 80–90% of
# reserve [[hr_reserve_vo2max]].
#
# **The arithmetic, at a 40 ml/kg/min owner:**
#
#     2 weeks of cessation   −4%   = 1.6 ml/kg/min   < our 2.7–3.2 resolution
#     3 weeks                −7%   = 2.8            ≈ our resolution
#     8 weeks               −13%   = 5.2            ≈ 2× resolution, and larger than
#                                                     Jurca's own 5.075 SEE — past there
#                                                     the number we are holding is more
#                                                     wrong than the model we refused
#
# Fourteen days is the last point at which the WORST-CASE drift is strictly inside the
# measuring instrument's own error. It is not a point at which a measurement "goes wrong".
#
# **Which way being wrong here would hurt.** Detraining decay makes a held value read
# HIGH, i.e. it flatters. #108 was exactly that failure — a fitness input overstating a
# real owner by years — so the loose direction is the one this product must not take.
#
# **What the corpus does NOT give, stated rather than papered over.** Those decay figures
# are for COMPLETE cessation in endurance-trained adults. There is no measured curve for
# partial reduction, and none for a mostly-sedentary owner who keeps walking. *Reasoned,
# not measured:* a person with less trained adaptation has less to shed, so their real
# decay is slower and this horizon errs conservative for them. The opposite direction —
# someone who starts training and gains — makes a held value read LOW, which is the safe
# side of this product's contract. Widening this constant needs evidence in this comment,
# exactly as widening the weight horizon does.
#
# **It is deliberately NOT ``WEIGHT_MAX_AGE_DAYS``, despite landing on the same number.**
# Two claims about two different quantities that happen to agree; one shared constant
# would mean an edit justified by body-mass drift silently moving a fitness gate.
MEASURED_VO2MAX_MAX_AGE_DAYS = 14


# How far a measured ``rhr_daily`` may sit from the day it anchors an HR reserve for.
#
# ## Why this exists at all (audit C5, 2026-09-08)
#
# ``derive/cardio_load.py`` used to read "the most recent rhr_daily on or before the day"
# with **no maximum age**, and to substitute a flat 60 bpm when there was none. Both
# halves are gone: the fabricated 60 is the only place in ``derive/`` that invented an
# input instead of withholding, and an unbounded lookback is the stale-as-current shape
# ``_profile_withhold_reason`` was written to close for weight.
#
# ## Where 30 comes from, and what it is not
#
# It is the outer edge of the corpus's own baseline vocabulary, not a drift measurement.
# ``resting-heart-rate.md`` D1 says RHR is read against "a ~7–30 day robust mean of
# same-method readings", and its implementation section names two windows — ~7–30 nights
# for the recovery/illness read, 30–90 days for the coarse health-marker read. A single
# held reading older than 30 nights is outside the widest window the note is willing to
# average a baseline over, so it is no longer a same-method reading about this stretch of
# this person's life. There is **no published figure for how fast an individual's resting
# HR drifts**, and none is claimed here; this is the note's window applied to a single
# value rather than to a mean, and it is deliberately the generous end of it.
#
# Direction of the error, which is what decides a tie in this product: RHR falls with
# training and rises with detraining, illness and alcohol. A stale LOW value inflates the
# HR reserve and therefore inflates TRIMP, strain and ACWR — flattery of the effort. That
# is the #108 shape, and it is why the bound is a bound rather than a caveat.
#
# Widening it needs evidence in this comment, exactly as the two horizons above do.
RHR_MAX_AGE_DAYS = 30


def measured_fitness_is_stale(as_of: date, on: date) -> bool:
    """Is this session-measured VO₂max too far from ``on`` to be that day's fitness?

    Unlike :func:`weight_is_stale` this is SIGNED: a session recorded after ``on`` is not
    a claim about ``on`` at all, however close it sits. (Weight compares absolutely
    because ``_weight_as_of`` deliberately hands pre-first-entry days a later weigh-in;
    nothing does that here — a day is only ever offered sessions at or before it.)

    The boundary is inclusive, matching how the evidence above is quoted ("by ~2–3
    weeks"): a session exactly :data:`MEASURED_VO2MAX_MAX_AGE_DAYS` old still speaks.
    """
    age = (on - as_of).days
    return not (0 <= age <= MEASURED_VO2MAX_MAX_AGE_DAYS)


def weight_age_days(as_of: date, on: date) -> int:
    """How far the logged weight sits from the day being computed, in days.

    ABSOLUTE distance, and that is not a detail. ``derive._common._weight_as_of`` falls
    back to the EARLIEST logged weight for days before the owner's first entry, so a day
    in 2026-03 can be handed a weight logged in 2026-06. A weight logged three months
    after a day is exactly as uninformative about that day as one logged three months
    before it, and signing the comparison would quietly exempt the whole pre-first-entry
    era from the gate.
    """
    return abs((on - as_of).days)


def weight_is_stale(as_of: date, on: date) -> bool:
    """Is this logged weight too far from ``on`` to be that day's weight?

    The ONE weight-freshness question, so that the VO₂max withhold, the Today card and
    the profile payload cannot answer it differently — CLAUDE.md's "one canonical
    definition per metric" applied to a metric's currency rather than its formula.

    The boundary is inclusive: a weight exactly :data:`WEIGHT_MAX_AGE_DAYS` old is still
    usable, matching how the evidence is quoted ("over two weeks").
    """
    return weight_age_days(as_of, on) > WEIGHT_MAX_AGE_DAYS


def unavailable_reason(
    today: date,
    last_day: date | None,
    gate: Callable[[], str | None] | None = None,
) -> str | None:
    """Why the newest stored row is not a claim about TODAY, or ``None`` when it is.

    ``last_day is None`` (no row at all) is the same answer as a stale one — there is no
    value for today either way, which is why ONE rule covers both. That matters: two
    treatments of one state is how a second definition gets in.

    ``gate`` is the metric's own "could today carry a value" check, and it is a callable
    so it stays UNEVALUATED on the common path: a row keyed to today short-circuits
    before any extra query runs, so freshness costs nothing when data is fresh. A metric
    with no gate of its own passes nothing and gets :data:`NOT_DERIVED_YET`.
    """
    if last_day == today:
        return None
    return (gate() if gate is not None else None) or NOT_DERIVED_YET


def withheld_block(
    reason: str,
    message: str,
    today: date,
    last_day: date | None,
    **extra: object,
) -> dict:
    """The ``withheld`` block: why there is no current value, and how old the last one is.

    The shape ``read/vo2max.py`` established and ``data_health`` uses for a dead feed —
    a reason an operator can filter on, a message a person can act on, and the age of
    what we *do* have. ``extra`` carries the metric's own last value (``last_estimate``,
    ``last_debt_min``, …) INSIDE this block, where nothing can mistake it for today's.

    The paired half of the contract lives at the call site and is not optional: the
    current-looking field itself must be ``None`` whenever this block is present. A dated
    field the UI may not render does not undo a confident current-looking number.
    """
    return {
        "reason": reason,
        "message": message,
        "last_as_of_date": last_day.isoformat() if last_day else None,
        "age_days": (today - last_day).days if last_day else None,
        **extra,
    }


def caveat_block(reason: str, message: str, on: date, as_of: date, **extra: object) -> dict:
    """The ``caveat`` block: the value IS served, and this is what tilts it.

    :func:`withheld_block`'s sibling, deliberately the same shape because they are two
    answers to ONE question, not two questions. ``analytics/biological_age.py`` fixed the
    vocabulary and the three states are not interchangeable::

        withheld   you could have this; here is the action that brings it back
        excluded   nobody can price this, ever
        caveats    this IS in your number, and here is which way it leans

    ``extra`` carries the metric's own sizing of the lean — the whole point of choosing
    this branch over a withhold is that the tilt was measured and found small, so the
    number that made the decision travels with the disclosure rather than staying in a
    commit message.

    The paired half of the contract mirrors ``withheld_block``'s and is not optional: the
    value stays NON-null, and this block must reach every surface that renders it. A
    caveat only the database can see is the same silence somewhere new.

    ``age_days`` is an ABSOLUTE distance, for the reason :func:`weight_age_days` gives —
    an input dated long after the day is exactly as uninformative about it as one dated
    long before, and a signed measure would report a negative age and read as fresh.
    """
    return {
        "reason": reason,
        "message": message,
        "as_of_date": as_of.isoformat(),
        "age_days": abs((on - as_of).days),
        **extra,
    }
