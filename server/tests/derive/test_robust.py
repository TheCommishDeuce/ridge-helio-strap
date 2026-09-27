"""Known-value tests for the ONE canonical robust-dispersion maths (``derive/robust``).

The expected numbers here are derived from the MATHEMATICAL DEFINITION of the
constant, not recorded from the implementation (a "known-value" test whose values
came out of the code only proves the code equals itself).

``MAD_TO_SD`` is the normal-consistency constant::

    for X ~ N(mu, sigma):  MAD = sigma * Phi^-1(0.75)
    therefore              sigma = MAD / Phi^-1(0.75) = MAD * 1.4826...

``test_mad_to_sd_is_the_normal_consistency_constant`` recomputes 1/Phi^-1(0.75) from
``statistics.NormalDist`` — an independent source — and checks the module's literal
against it. That is the whole justification for the number; it is a statistical
identity, NOT a research claim, which is why it carries no knowledge citation.
"""

from __future__ import annotations

import ast
from pathlib import Path
from statistics import NormalDist

import pytest

from strap_server.derive.robust import MAD_TO_SD, median, median_abs_deviation, robust_sd

_SRC = Path(__file__).resolve().parents[2] / "src" / "strap_server"

# The one module allowed to index a sequence at a floor-halved position — it is the
# definition of the median, so it has nowhere else to delegate to.
_MEDIAN_MODULE = "derive/robust.py"


def test_mad_to_sd_is_the_normal_consistency_constant() -> None:
    """1.4826 == 1/Phi^-1(0.75), to the 4dp the literal is written at.

    Phi^-1(0.75) = 0.6744897501960817 -> 1/0.6744897501960817 = 1.4826022185056018,
    which rounds to 1.4826. Computed here from NormalDist, never from our own code.
    """
    exact = 1.0 / NormalDist().inv_cdf(0.75)
    assert exact == pytest.approx(1.4826022185056018, abs=1e-12)
    assert round(exact, 4) == MAD_TO_SD


def test_robust_sd_scales_the_mad() -> None:
    """MAD 10 -> sigma = 10 * 1.4826 = 14.826. Unfloored by default."""
    assert robust_sd(10.0) == pytest.approx(14.826, abs=1e-9)
    assert robust_sd(0.0) == 0.0  # a flat history converts to 0, not an error


def test_robust_sd_floor_guards_a_degenerate_history() -> None:
    """The floor binds only BELOW itself; it never inflates a real spread.

    MAD 0.1 -> 0.14826, which is under a 0.5 floor -> 0.5.
    MAD 10  -> 14.826, far above it -> untouched.
    """
    assert robust_sd(0.1, floor=0.5) == 0.5
    assert robust_sd(0.0, floor=0.5) == 0.5
    assert robust_sd(10.0, floor=0.5) == pytest.approx(14.826, abs=1e-9)
    # The floor is exactly the crossover: MAD * 1.4826 == floor -> the floor is a no-op.
    assert robust_sd(0.5 / MAD_TO_SD, floor=0.5) == pytest.approx(0.5, abs=1e-12)


# ── the median itself ────────────────────────────────────────────────────────
#
# Every expected value below is the TEXTBOOK definition applied by hand, never a
# number recorded from the implementation. The sequences are deliberately given
# unsorted: `median` sorts its own input, and a caller that has to pre-sort is a
# caller that can forget to.


def test_median_of_an_odd_sample_is_the_central_value() -> None:
    """[1, 2, 3] -> 2. Odd n has a single middle, so every median agrees here."""
    assert median([3.0, 1.0, 2.0]) == 2.0
    assert median([7.0]) == 7.0
    assert median([50.0, 10.0, 30.0, 40.0, 20.0]) == 30.0


def test_median_of_an_even_sample_interpolates() -> None:
    """[1, 2, 3, 4] -> 2.5, the MEAN of the two central values.

    This is the whole point of the 2026-07-31 unification. Three modules used to write
    ``xs[len(xs) // 2]``, which returns the UPPER-middle value — 3.0 for this sample,
    not 2.5. That is a different statistic (the (n/2 + 1)-th order statistic, biased
    upward) and it agrees with the median only when n is odd. Naming it "median" in
    five places while three of them computed something else is precisely the "two
    definitions of one metric" CLAUDE.md forbids.
    """
    assert median([4.0, 1.0, 3.0, 2.0]) == 2.5  # the upper-middle form returns 3.0
    # The six-night sleep window used in tests/derive/test_median_unification.py.
    assert median([480.0, 300.0, 450.0, 330.0, 420.0, 360.0]) == 390.0  # (360 + 420) / 2


def test_median_of_an_empty_sample_is_an_error_not_a_zero() -> None:
    """ "No data" must not silently become a number — standards §1."""
    with pytest.raises(ValueError, match="empty sample"):
        median([])


def test_median_abs_deviation_odd_and_even() -> None:
    """MAD = median(|x - median(x)|), with the SAME median on both halves.

    Odd:  [1, 2, 3, 4, 10] -> med 3; |x-3| = [2, 1, 0, 1, 7] -> sorted [0,1,1,2,7] -> 1.
    Even: [300,330,360,420,450,480] -> med 390; |x-390| = [90,60,30,30,60,90]
          -> sorted [30,30,60,60,90,90] -> (60+60)/2 = 60.
    """
    assert median_abs_deviation([10.0, 1.0, 3.0, 2.0, 4.0]) == 1.0
    assert median_abs_deviation([480.0, 300.0, 450.0, 330.0, 420.0, 360.0]) == 60.0


def _midpoint_index_sites() -> list[str]:
    """Modules that subscript a sequence at a floor-halved index (``xs[n // 2]``).

    ``_MEDIAN_MODULE`` is excluded — it IS the definition. An AST scan so the pattern
    is matched as code: the slice expression must contain a ``BinOp(//, …, 2)``, which
    catches ``xs[n // 2]``, ``xs[len(xs) // 2]`` and ``xs[n // 2 - 1]`` alike while
    ignoring the same text inside a docstring or comment.
    """
    hits: list[str] = []
    for path in sorted(_SRC.rglob("*.py")):
        rel = str(path.relative_to(_SRC))
        if rel == _MEDIAN_MODULE:
            continue
        tree = ast.parse(path.read_text(), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Subscript) and _has_floor_half(node.slice):
                hits.append(rel)
    return sorted(set(hits))


def _has_floor_half(node: ast.expr) -> bool:
    """True if the expression contains a ``… // 2``."""
    return any(
        isinstance(sub, ast.BinOp)
        and isinstance(sub.op, ast.FloorDiv)
        and isinstance(sub.right, ast.Constant)
        and sub.right.value == 2
        for sub in ast.walk(node)
    )


def test_no_module_hand_rolls_a_median() -> None:
    """``xs[n // 2]`` appears nowhere outside ``derive/robust.py``.

    It lived in FIVE modules — ``derive/recovery.py`` (the MAD step),
    ``derive/vo2max_submax.py``, ``read/recovery.py`` (twice: the sleep baseline and
    the readiness-decay reference load), ``read/fitness.py`` and
    ``challenges/series.py``. THREE of them took the upper-middle value of an even
    sample, so the same trailing history produced a different "usual" depending on
    which screen asked; the other two were byte-identical copies of the correct form,
    which is the same defect one refactor away from becoming the first. The scaling
    constant already had this guard
    (``test_the_mad_constant_has_exactly_one_definition``); the estimator it scales
    did not.
    """
    assert _midpoint_index_sites() == []


def _literal_1_4826_sites() -> list[str]:
    """Every module holding a `1.4826` numeric literal, as a path relative to src."""
    hits: list[str] = []
    for path in sorted(_SRC.rglob("*.py")):
        tree = ast.parse(path.read_text(), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Constant) and node.value == 1.4826:
                hits.append(str(path.relative_to(_SRC)))
    return hits


def test_the_mad_constant_has_exactly_one_definition() -> None:
    """1.4826 is written ONCE in the tree — in ``derive/robust.py``.

    It lived in THREE modules (analytics/metrics.py, read/recovery.py,
    derive/recovery.py). Three copies of a constant is three chances for a z-score to
    mean two different things on two different screens (standards: "second occurrence
    = extract"; CLAUDE.md: "ONE canonical definition per metric").

    An AST scan, not a grep: a `1.4826` inside a docstring or comment is prose and
    must not trip this, while a re-typed literal in code must.
    """
    assert _literal_1_4826_sites() == ["derive/robust.py"]
