"""The sleep payload names the instrument that actually took the reading (audit C1).

`derive/sleep_score.derive_sleep_score` stamped `session_source: "zepp_cloud"` on all six
rows a night produces, and `read/sleep_page.py` repeated the literal at two more sites.
It is a verbatim carry-over from legacy, where sleep genuinely arrived from Zepp Cloud.
**There is no Zepp path in this repo**: the only writer of `sleep_session` is
`ingest.upsert.upsert_sleep`, fed by the strap over BLE.

On the product whose premise is that every number names its instrument, that is the
clearest instance of the defect in the layer, and it survived because nothing renders the
string — the client uses it as a presence sentinel only (`sleep_night.dart`).

Two properties, and the second is why this file exists rather than a one-line rename:

* the served value names the strap, from ONE constant, so the three sites cannot drift;
* the premise the constant rests on — one writer — is CHECKED here, not trusted to the
  grep that established it. A rename is only correct while that stays true, and a second
  ingest path (a Zepp importer, an Apple Health bridge) must fail this test rather than
  quietly make every row lie in the other direction.
"""

from __future__ import annotations

import re
from pathlib import Path

from strap_server.derive.sleep_score import SESSION_SOURCE

_SRC = Path(__file__).resolve().parents[2] / "src" / "strap_server"


def _python_sources() -> list[Path]:
    return sorted(_SRC.rglob("*.py"))


def test_the_session_source_names_the_strap() -> None:
    assert SESSION_SOURCE == "strap_ble"


def test_sleep_session_has_exactly_one_writer_in_the_tree() -> None:
    """The premise behind a constant instead of a stored column.

    A `source` column on `sleep_session` would be one value repeated on every row for as
    long as this holds. When it stops holding, the column becomes the right answer — and
    this assertion is what says so, instead of a comment nobody re-checks.
    """
    # Assembled rather than written out, so this file cannot match its own pattern.
    inserter = re.compile(r"INSERT\s+INTO\s+" + "sleep_" + "session", re.IGNORECASE)
    writers = [
        path.relative_to(_SRC).as_posix()
        for path in _python_sources()
        if inserter.search(path.read_text())
    ]
    assert writers == ["ingest/upsert.py"], (
        "a second writer of sleep_session means the instrument is no longer a constant; "
        "give the table a source column rather than letting one of them lie"
    )


def test_no_module_serves_the_legacy_clouds_name_as_a_value() -> None:
    """A derived check beats a listed one (`docs/HOW_WE_VERIFY.md` section 4).

    Two of the three sites were in `read/`, two hundred lines from the writer, and a
    rename that fixed only the one under the audit's nose would have shipped a payload
    disagreeing with itself. Nothing enumerates the sites here; the tree is walked.

    The scan is for the DOUBLE-quoted form, which is what a Python string value is after
    `ruff format`, and not for the single-quoted form, which is how half a dozen module
    docstrings quote the legacy `source='…'` FILTER they are explaining the absence of.
    That is the difference between serving a claim and recounting one, and it is a
    property of the text rather than a list of forgiven files.
    """
    served = '"' + "zepp_cloud" + '"'  # assembled, so this file is not its own offender
    offenders = [
        path.relative_to(_SRC).as_posix()
        for path in _python_sources()
        if served in path.read_text()
    ]
    assert offenders == [], offenders
