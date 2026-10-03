#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27", "cryptography>=42"]
# ///
"""One-off: the strap's history from before the first Ridge sync, out of the Zepp cloud.

The strap keeps about a month. Everything older exists only where the Zepp app once
synced it. This pulls it in three steps, so nothing reaches the server unseen:

    uv run tools/zepp-backfill/backfill.py fetch you@example.com --from 2025-01-01 --to 2026-08-22
    uv run tools/zepp-backfill/backfill.py inspect
    uv run tools/zepp-backfill/backfill.py push --server https://ridge.example --tz Europe/Berlin

`fetch` saves the cloud's raw replies to ~/zepp-backfill (personal data: kept out of the
repo, delete it when done). `inspect` decodes them and prints what each day holds plus the
checks that say whether the decoding is right. `push` sends them as source "zepp_cloud",
which the server only uses to fill gaps: wherever the strap already pushed a minute, a
night or a day total, the strap's stays.

What comes across, all of it the strap's own measurements as the Zepp app synced them:
per-minute heart rate and steps, stress, sleep sessions with their stages, and each day's
step/distance/calorie totals. Not in the cloud's history: HRV, SpO2, skin temperature,
respiratory rate. Workouts are left out: the cloud numbers sports differently from the strap.

Stress and sleep stages are not computed here. The strap computes both on the device;
the cloud holds its values, and this imports them as they are.

The endpoints are the Zepp app's own, undocumented. Signing in logs the Zepp app out
(tools/keyfetch/README.md). Layouts: `band_data.json` per day = base64 `data` (per
minute: activity kind, intensity, steps; 3 or 4 bytes), base64 `data_hr` (1 byte a
minute, >= 0xFE none), base64 JSON `summary` (`stp` totals, `slp` night with stages in
minutes, `stop` inclusive, counted from the midnight before the day in the local zone;
modes 4 light, 5 deep, 7 awake, 8 REM, as on the strap: spec/01 §7.1). The summary's `tz`
is the standard offset without DST, so it is not used: `--tz` places the minutes.
`events?eventType=all_day_stress` = one item a day, `data` a JSON list of {time, value}.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
import sys
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "keyfetch"))
import keyfetch

BAND_DATA_URL = "https://api-mifit.zepp.com/v1/data/band_data.json"
EVENTS_URL = "https://api-mifit.zepp.com/users/{user_id}/events"
RAW_DIR = Path.home() / "zepp-backfill"
CHUNK_DAYS = 30
EVENTS_LIMIT = 1000

SLEEP_MODES = {4, 5, 7, 8}  # light, deep, awake, REM; same codes as the strap
LIGHT, DEEP, AWAKE, REM = 4, 5, 7, 8
NAP_GAP_MIN = 60  # stages further apart than this are separate sleeps
PUSH_SAMPLES = 15_000  # under the server's 20k cap per push


# ── fetch ───────────────────────────────────────────────────────────────────────────────


def _headers(app_token: str) -> dict:
    return {
        "apptoken": app_token,
        "appplatform": "android_phone",
        "appname": "com.huami.midong",
        "cv": keyfetch.CLIENT_VERSION,
        "v": "2.0",
        "vn": keyfetch.APP_VERSION,
        "user-agent": keyfetch.USER_AGENT,
        "x-request-id": str(uuid.uuid4()),
    }


def _get(client: httpx.Client, url: str, params: dict, app_token: str, what: str) -> dict:
    r = client.get(url, params=params, headers=_headers(app_token))
    if r.status_code != 200:
        raise keyfetch.KeyFetchError(f"{what}: HTTP {r.status_code} (Zepp API changed?)")
    return keyfetch._json(r, what)


def _chunks(first: date, last: date):
    start = first
    while start <= last:
        end = min(last, start + timedelta(days=CHUNK_DAYS - 1))
        yield start, end
        start = end + timedelta(days=1)


def fetch(args: argparse.Namespace) -> int:
    out: Path = args.out
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    password = getpass.getpass("Zepp password (not echoed): ")
    with httpx.Client(timeout=60, follow_redirects=False) as client:
        user_id, app_token = keyfetch.app_session(client, keyfetch.access_token(client, args.email, password))
        for start, end in _chunks(args.first, args.last):
            band = _get(client, BAND_DATA_URL, {
                "query_type": "detail", "device_type": "android_phone", "userid": user_id,
                "from_date": start.isoformat(), "to_date": end.isoformat(),
            }, app_token, "band data")
            days = band.get("data") or []
            (out / f"band_{start}_{end}.json").write_text(json.dumps(days))
            stress = _get(client, EVENTS_URL.format(user_id=user_id), {
                "eventType": "all_day_stress", "limit": EVENTS_LIMIT,
                # A day wider each side: items are UTC instants, the chunk is local days.
                "from": _epoch_ms(start - timedelta(days=1)), "to": _epoch_ms(end + timedelta(days=2)),
            }, app_token, "stress")
            items = stress.get("items") or []
            (out / f"stress_{start}_{end}.json").write_text(json.dumps(items))
            more = "  (stress list full: some may be missing)" if len(items) >= EVENTS_LIMIT else ""
            print(f"{start} .. {end}: {len(days)} days, {len(items)} stress days{more}")
    print(f"saved to {out}. Next: inspect")
    return 0


def _epoch_ms(day: date) -> int:
    return int(datetime(day.year, day.month, day.day, tzinfo=ZoneInfo("UTC")).timestamp() * 1000)


# ── decode ──────────────────────────────────────────────────────────────────────────────


def _midnight(day: date, zone: ZoneInfo) -> datetime:
    return datetime(day.year, day.month, day.day, tzinfo=zone)


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def _b64(value) -> bytes:
    return base64.b64decode(value) if value else b""


def decode_day(item: dict, zone: ZoneInfo) -> dict:
    """One band_data day -> samples, sleep sessions, the day's total, and the checks."""
    day = date.fromisoformat(item["date_time"])
    midnight = _midnight(day, zone)
    summary = json.loads(_b64(item.get("summary")) or b"{}")
    samples: list[dict] = []
    checks: dict = {}

    for i, bpm in enumerate(_b64(item.get("data_hr"))):
        if 25 <= bpm <= 230:
            samples.append({"metric": "hr", "ts": _ms(midnight + timedelta(minutes=i)), "value": bpm})

    activity = _b64(item.get("data"))
    width = len(activity) // 1440 if activity and len(activity) % 1440 == 0 else 0
    checks["activity_bytes_per_min"] = width or (len(activity) and f"? ({len(activity)} B)")
    step_sum = 0
    if width in (3, 4):
        for i in range(1440):
            steps = activity[i * width + 2]
            if steps:
                step_sum += steps
                samples.append({"metric": "steps", "ts": _ms(midnight + timedelta(minutes=i)), "value": steps})

    stp = summary.get("stp") or {}
    total = None
    if stp.get("ttl") is not None:
        total = {
            "read_at": _ms(_midnight(day + timedelta(days=1), zone)), "day": day.isoformat(),
            "steps": int(stp["ttl"]), "distance_m": stp.get("dis"), "calories": stp.get("cal"),
        }
    checks["steps_minutes_vs_total"] = (step_sum, stp.get("ttl"))

    sleep, sleep_checks = decode_sleep(summary.get("slp") or {}, midnight)
    checks.update(sleep_checks)
    checks["summary_keys"] = sorted(summary)
    checks["device"] = item.get("device_id") or summary.get("sn") or item.get("source")
    return {"day": day, "samples": samples, "sleep": sleep, "total": total, "checks": checks}


def decode_sleep(slp: dict, midnight: datetime) -> tuple[list[dict], dict]:
    """The day's sleeps from `slp`. Stage minutes count from a midnight that has to be
    found: the strap's own record counts from the midnight BEFORE (spec/01 §7.1); the cloud
    is anchored by whichever of the two puts the first stage nearest `slp.st`."""
    # `stop` is inclusive: the next stage starts at stop + 1, and stop - start + 1 summed
    # per mode equals the summary's dp/lt/wk (checked on real data, 2026-10-03).
    stages = [
        {**s, "stop": s["stop"] + 1}
        for s in slp.get("stage") or []
        if s.get("mode") in SLEEP_MODES and s["stop"] >= s["start"]
    ]
    st = slp.get("st") or 0
    if not stages:
        return [], {"sleep": "none" if not st or st == slp.get("ed") else "window without stages (not imported)"}
    stages.sort(key=lambda s: s["start"])
    first = stages[0]["start"]
    bases = [midnight - timedelta(days=1), midnight]
    base = min(bases, key=lambda b: abs((b + timedelta(minutes=first)).timestamp() - st))
    off_s = abs((base + timedelta(minutes=first)).timestamp() - st)

    groups: list[list[dict]] = [[stages[0]]]
    for s in stages[1:]:
        if s["start"] - groups[-1][-1]["stop"] > NAP_GAP_MIN:
            groups.append([s])
        else:
            groups[-1].append(s)
    main = max(groups, key=lambda g: g[-1]["stop"] - g[0]["start"])

    def at(minute: int) -> int:
        return _ms(base + timedelta(minutes=minute))

    sessions = []
    for g in groups:
        minutes = {m: sum(s["stop"] - s["start"] for s in g if s["mode"] == m) for m in SLEEP_MODES}
        sessions.append({
            "start_ts": at(g[0]["start"]), "end_ts": at(g[-1]["stop"]),
            "kind": "main" if g is main else "nap",
            "score": slp.get("ss") if g is main else None,
            "rem_min": minutes[REM], "light_min": minutes[LIGHT], "deep_min": minutes[DEEP], "wake_min": minutes[AWAKE],
            "stages": [[at(s["start"]), at(s["stop"]), s["mode"]] for s in g],
        })
    m = next(s for s in sessions if s["kind"] == "main")
    checks = {
        "sleep_anchor": "day before" if base == bases[0] else "same day",
        "sleep_anchor_off_min": round(off_s / 60),
        "deep_vs_summary": (m["deep_min"], slp.get("dp")),
        "light_vs_summary": (m["light_min"], slp.get("lt")),
        "wake_vs_summary": (m["wake_min"], slp.get("wk")),
        "naps": len(sessions) - 1,
    }
    return sessions, checks


def decode_stress(items: list[dict]) -> list[dict]:
    out = []
    for item in items:
        try:
            points = json.loads(item.get("data") or "[]")
        except json.JSONDecodeError:
            continue
        for p in points:
            value = p.get("value")
            if isinstance(value, int | float) and 0 < value <= 100 and p.get("time"):
                out.append({"metric": "stress", "ts": int(p["time"]), "value": value})
    return out


def load(raw: Path, zone: ZoneInfo, last: date | None) -> tuple[list[dict], list[dict]]:
    days = [d for f in sorted(raw.glob("band_*.json")) for d in json.loads(f.read_text())]
    decoded = sorted((decode_day(d, zone) for d in days), key=lambda d: d["day"])
    points = [p for f in sorted(raw.glob("stress_*.json")) for p in decode_stress(json.loads(f.read_text()))]
    stress = sorted({p["ts"]: p for p in points}.values(), key=lambda p: p["ts"])  # chunks overlap by a day
    if last:
        decoded = [d for d in decoded if d["day"] <= last]
        end = _ms(_midnight(last + timedelta(days=1), zone))
        stress = [p for p in stress if p["ts"] < end]
    return decoded, stress


# ── inspect / push ──────────────────────────────────────────────────────────────────────


def inspect(args: argparse.Namespace) -> int:
    zone = ZoneInfo(args.tz)
    days, stress = load(args.raw, zone, args.last)
    if not days:
        print(f"nothing in {args.raw}: run fetch first", file=sys.stderr)
        return 1
    stress_by_day: dict[date, int] = {}
    for p in stress:
        d = datetime.fromtimestamp(p["ts"] / 1000, zone).date()
        stress_by_day[d] = stress_by_day.get(d, 0) + 1
    print("day         hr_min  step_min  steps(min/total)  stress  sleep")
    for d in days:
        c = d["checks"]
        hr = sum(1 for s in d["samples"] if s["metric"] == "hr")
        st = sum(1 for s in d["samples"] if s["metric"] == "steps")
        main = next((s for s in d["sleep"] if s["kind"] == "main"), None)
        night = (
            f"{(main['end_ts'] - main['start_ts']) // 60000} min, anchor {c['sleep_anchor']} "
            f"±{c['sleep_anchor_off_min']}m, deep {c['deep_vs_summary']}, light {c['light_vs_summary']}, "
            f"wake {c['wake_vs_summary']}"
            + (f", +{c['naps']} nap" if c["naps"] else "")
            if main else c.get("sleep", "")
        )
        steps = c["steps_minutes_vs_total"]
        print(f"{d['day']}  {hr:6}  {st:8}  {steps[0]:>6}/{steps[1]!s:<9}  {stress_by_day.get(d['day'], 0):6}  {night}")
    devices = sorted({str(d["checks"]["device"]) for d in days})
    widths = sorted({str(d["checks"]["activity_bytes_per_min"]) for d in days})
    keys = sorted({k for d in days for k in d["checks"]["summary_keys"]})
    print(f"\n{len(days)} days, {sum(len(d['samples']) for d in days) + len(stress)} samples")
    print(f"devices: {devices}\nactivity bytes/min: {widths}\nsummary keys: {keys}")
    print("Healthy: steps(min/total) equal, sleep anchor ±0m, deep/light/wake equal to the summary's.")
    return 0


def push(args: argparse.Namespace) -> int:
    zone = ZoneInfo(args.tz)
    days, stress = load(args.raw, zone, args.last)
    if not days:
        print(f"nothing in {args.raw}: run fetch first", file=sys.stderr)
        return 1
    token = os.environ.get("RIDGE_TOKEN") or getpass.getpass("Ridge server token (not echoed): ")
    stress_by_day: dict[date, list[dict]] = {}
    for p in stress:
        stress_by_day.setdefault(datetime.fromtimestamp(p["ts"] / 1000, zone).date(), []).append(p)

    page: dict = {"source": "zepp_cloud", "samples": [], "sleep": [], "daily_totals": []}
    first_day = None
    with httpx.Client(timeout=600) as client:
        def send(last_day: date) -> None:
            r = client.post(f"{args.server.rstrip('/')}/v1/ingest", json=page, headers={"Authorization": f"Bearer {token}"})
            if r.status_code != 200:
                raise SystemExit(f"{first_day} .. {last_day}: HTTP {r.status_code} {r.text[:200]}")
            s = r.json()
            print(f"{first_day} .. {last_day}: +{s['samples_stored']} samples, +{s['sleep']} sleeps, "
                  f"+{s['daily_totals']} day totals ({len(page['samples']) - s['samples_stored']} already there)")

        for d in days:  # oldest first, so each day's baselines are built from days already in
            first_day = first_day or d["day"]
            page["samples"] += d["samples"] + stress_by_day.get(d["day"], [])
            page["sleep"] += d["sleep"]
            page["daily_totals"] += [d["total"]] if d["total"] else []
            if len(page["samples"]) >= PUSH_SAMPLES:
                send(d["day"])
                page.update(samples=[], sleep=[], daily_totals=[])
                first_day = None
        if page["samples"] or page["sleep"] or page["daily_totals"]:
            send(days[-1]["day"])
    print("done. Re-derive on the server so the days after these get their new baselines.")
    return 0


def _local_tz() -> str:
    try:
        return str(Path("/etc/localtime").resolve()).split("zoneinfo/", 1)[1]
    except (OSError, IndexError):
        return "UTC"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch", help="sign in and save the cloud's raw history")
    f.add_argument("email")
    f.add_argument("--from", dest="first", type=date.fromisoformat, required=True)
    f.add_argument("--to", dest="last", type=date.fromisoformat, required=True)
    f.add_argument("--out", type=Path, default=RAW_DIR)
    for name, help_ in (("inspect", "decode and print what each day holds"), ("push", "send it to the Ridge server")):
        p = sub.add_parser(name, help=help_)
        p.add_argument("--raw", type=Path, default=RAW_DIR)
        p.add_argument("--tz", default=_local_tz(), help="the zone you lived in then (default: this computer's)")
        p.add_argument("--to", dest="last", type=date.fromisoformat, help="last day to use (default: all fetched)")
        if name == "push":
            p.add_argument("--server", required=True, help="the Ridge server's URL, as in the app")
    args = parser.parse_args()
    try:
        return {"fetch": fetch, "inspect": inspect, "push": push}[args.cmd](args)
    except httpx.HTTPError as e:
        print(f"network error: {type(e).__name__}", file=sys.stderr)
        return 2
    except keyfetch.KeyFetchError as e:
        print(str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
