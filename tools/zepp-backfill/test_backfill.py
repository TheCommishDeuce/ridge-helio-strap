# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27", "cryptography>=42", "pytest>=8"]
# ///
"""Offline checks for the backfill's decoding, on synthetic days in the cloud's layout.

    uv run --script tools/zepp-backfill/test_backfill.py
"""

import base64
import json
import sys
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import backfill

ZONE = ZoneInfo("Europe/Berlin")


def b64(raw: bytes | str) -> str:
    return base64.b64encode(raw.encode() if isinstance(raw, str) else raw).decode()


def local_ms(*args) -> int:
    return int(datetime(*args, tzinfo=ZONE).timestamp() * 1000)


def day_item(slp: dict | None = None, width: int = 3) -> dict:
    hr = bytearray([0xFF] * 1440)
    hr[0], hr[61] = 60, 72  # 00:00 and 01:01
    activity = bytearray(1440 * width)
    activity[600 * width + 2] = 40  # 10:00, 40 steps
    activity[601 * width + 2] = 2
    summary = {"stp": {"ttl": 42, "dis": 30, "cal": 2}, "slp": slp or {}, "v": 6}
    return {"date_time": "2026-07-02", "data_hr": b64(bytes(hr)), "data": b64(bytes(activity)),
            "summary": b64(json.dumps(summary))}


def night() -> dict:
    # Minutes from the midnight BEFORE the day, as the strap counts, `stop` inclusive:
    # 23:00 .. 06:30, plus a nap 13:00 .. 13:40 on the day itself.
    stages = [
        {"start": 1380, "stop": 1499, "mode": 4},
        {"start": 1500, "stop": 1619, "mode": 5},
        {"start": 1620, "stop": 1649, "mode": 7},
        {"start": 1650, "stop": 1829, "mode": 8},
        {"start": 2220, "stop": 2259, "mode": 4},
    ]
    st = local_ms(2026, 7, 1, 23, 0) // 1000
    return {"st": st, "ed": local_ms(2026, 7, 2, 6, 30) // 1000, "dp": 120, "lt": 120, "ss": 81, "stage": stages}


@pytest.mark.parametrize("width", [3, 4])
def test_heart_rate_and_steps_land_on_their_local_minutes(width):
    d = backfill.decode_day(day_item(width=width), ZONE)
    hr = [(s["ts"], s["value"]) for s in d["samples"] if s["metric"] == "hr"]
    steps = [(s["ts"], s["value"]) for s in d["samples"] if s["metric"] == "steps"]
    assert hr == [(local_ms(2026, 7, 2, 0, 0), 60), (local_ms(2026, 7, 2, 1, 1), 72)]
    assert steps == [(local_ms(2026, 7, 2, 10, 0), 40), (local_ms(2026, 7, 2, 10, 1), 2)]
    assert d["checks"]["steps_minutes_vs_total"] == (42, 42)


def test_the_day_total_is_a_closed_day():
    total = backfill.decode_day(day_item(), ZONE)["total"]
    assert total == {"read_at": local_ms(2026, 7, 3), "day": "2026-07-02", "steps": 42, "distance_m": 30, "calories": 2}


def test_a_night_is_anchored_by_its_start_and_split_from_the_nap():
    d = backfill.decode_day(day_item(night()), ZONE)
    main, nap = d["sleep"]
    assert (main["kind"], main["start_ts"], main["end_ts"]) == ("main", local_ms(2026, 7, 1, 23), local_ms(2026, 7, 2, 6, 30))
    assert (main["light_min"], main["deep_min"], main["wake_min"], main["rem_min"], main["score"]) == (120, 120, 30, 180, 81)
    assert main["stages"][0] == [local_ms(2026, 7, 1, 23), local_ms(2026, 7, 2, 1), 4]
    assert (nap["kind"], nap["start_ts"], nap["score"]) == ("nap", local_ms(2026, 7, 2, 13), None)
    assert d["checks"]["sleep_anchor"] == "day before" and d["checks"]["sleep_anchor_off_min"] == 0


def test_a_night_counted_from_the_same_midnight_is_anchored_there_too():
    slp = {**night(), "stage": [{"start": 30, "stop": 299, "mode": 4}], "st": local_ms(2026, 7, 2, 0, 30) // 1000}
    d = backfill.decode_day(day_item(slp), ZONE)
    assert d["sleep"][0]["start_ts"] == local_ms(2026, 7, 2, 0, 30) and d["checks"]["sleep_anchor"] == "same day"


def test_no_stages_means_no_session():
    d = backfill.decode_day(day_item({"st": 1, "ed": 2, "dp": 100}), ZONE)
    assert d["sleep"] == [] and "without stages" in d["checks"]["sleep"]


def test_stress_points_keep_their_instants_and_drop_empties():
    items = [{"timestamp": 1, "data": json.dumps([{"time": 1000, "value": 30}, {"time": 2000, "value": 0}])}]
    assert backfill.decode_stress(items) == [{"metric": "stress", "ts": 1000, "value": 30}]


def test_load_cuts_at_the_last_day_and_dedups_overlapping_stress(tmp_path):
    (tmp_path / "band_a.json").write_text(json.dumps([day_item(), {**day_item(), "date_time": "2026-07-03"}]))
    point = {"time": local_ms(2026, 7, 2, 12), "value": 40}
    late = {"time": local_ms(2026, 7, 3, 12), "value": 40}
    for name in ("stress_a.json", "stress_b.json"):
        (tmp_path / name).write_text(json.dumps([{"data": json.dumps([point, late])}]))
    days, stress = backfill.load(tmp_path, ZONE, date(2026, 7, 2))
    assert [d["day"] for d in days] == [date(2026, 7, 2)] and len(stress) == 1


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
