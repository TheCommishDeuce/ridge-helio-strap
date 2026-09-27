#!/usr/bin/env python3
"""Synthetic data for a demo server: five weeks of a made-up person, up to now.

Deterministic (fixed seed), never anyone's real data. Pushes through the public API the
phone uses, so every derived number comes from the real science:

    python3 tools/demo-data/generate.py http://127.0.0.1:8766 <token> [--days 35] [--timezone Europe/Berlin]

Use it only against a throwaway server (see load.sh); it adds five weeks of data.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

LIGHT, DEEP, AWAKE, REM = 4, 5, 7, 8
PAGE = 15_000


def ms(t: datetime) -> int:
    return int(t.timestamp() * 1000)


def night(rng: random.Random, bed: datetime) -> tuple[dict, list[dict]]:
    """One night: ~90-minute cycles, deep early, REM late, brief wakes; plus night vitals."""
    stages, t = [], bed
    end = bed + timedelta(minutes=rng.randint(415, 485))
    cycle = 0
    while t < end:
        deep = max(0, 38 - cycle * 11 + rng.randint(-6, 6))
        rem = min(40, 8 + cycle * 8 + rng.randint(-4, 6))
        for code, minutes in ((LIGHT, rng.randint(25, 40)), (DEEP, deep), (LIGHT, rng.randint(8, 15)), (REM, rem)):
            if minutes <= 0 or t >= end:
                continue
            stop = min(end, t + timedelta(minutes=minutes))
            stages.append([ms(t), ms(stop), code])
            t = stop
        if t < end and rng.random() < 0.35:
            stop = min(end, t + timedelta(minutes=rng.randint(2, 6)))
            stages.append([ms(t), ms(stop), AWAKE])
            t = stop
        cycle += 1
    minutes = {c: sum((b - a) // 60000 for a, b, s in stages if s == c) for c in (LIGHT, DEEP, AWAKE, REM)}
    samples, base = [], 50 + rng.uniform(-2, 2)
    for m in range(int((end - bed).total_seconds() // 60)):
        at = bed + timedelta(minutes=m)
        dip = -4 * math.sin(math.pi * m / max(1, (end - bed).total_seconds() / 60))
        samples.append({"metric": "hr", "ts": ms(at), "value": round(base + dip + rng.gauss(0, 1.8))})
        if m % 5 == 0:
            samples.append({"metric": "hrv", "ts": ms(at), "value": round(62 + rng.gauss(0, 7))})
            samples.append({"metric": "spo2_sleep", "ts": ms(at), "value": round(96 + rng.gauss(0, 0.8))})
            samples.append({"metric": "respiratory_rate", "ts": ms(at), "value": round(14.5 + rng.gauss(0, 0.6), 1)})
        if m % 11 == 0:
            samples.append({"metric": "stress", "ts": ms(at), "value": max(1, round(14 + rng.gauss(0, 5)))})
    samples.append({"metric": "temperature_c", "ts": ms(bed + timedelta(hours=3)), "value": round(34.2 + rng.gauss(0, 0.2), 1)})
    session = {
        "start_ts": stages[0][0], "end_ts": stages[-1][1], "kind": "main", "score": rng.randint(72, 90),
        "avg_hr": round(base - 2), "rem_min": minutes[REM], "light_min": minutes[LIGHT],
        "deep_min": minutes[DEEP], "wake_min": minutes[AWAKE], "stages": stages,
    }
    return session, samples


def day(rng: random.Random, wake: datetime, until: datetime, workout: bool) -> tuple[list[dict], list[dict], int]:
    """A waking day: HR each minute, stress every few minutes, walks, an optional run."""
    samples, workouts, steps_total = [], [], 0
    bed = wake.replace(hour=23, minute=0) + timedelta(minutes=rng.randint(-40, 30))
    walks = [wake + timedelta(minutes=rng.randint(30, 60)), wake.replace(hour=12, minute=rng.randint(10, 50)),
             wake.replace(hour=17, minute=rng.randint(30, 59))]
    run_at = wake.replace(hour=18, minute=30) if workout else None
    t = wake
    while t < min(bed, until):
        hour = t.hour + t.minute / 60
        walking = any(w <= t < w + timedelta(minutes=18) for w in walks)
        running = run_at is not None and run_at <= t < run_at + timedelta(minutes=35)
        if running:
            hr = 150 + 10 * math.sin((t - run_at).total_seconds() / 600) + rng.gauss(0, 3)
            steps = rng.randint(158, 172)
        elif walking:
            hr, steps = 98 + rng.gauss(0, 5), rng.randint(95, 118)
        else:
            hr = 66 + 6 * math.sin(math.pi * (hour - 7) / 14) + rng.gauss(0, 3.5)
            steps = rng.randint(0, 12) if rng.random() < 0.25 else 0
        samples.append({"metric": "hr", "ts": ms(t), "value": round(hr)})
        if steps:
            samples.append({"metric": "steps", "ts": ms(t), "value": steps})
            steps_total += steps
        if t.minute % 5 == 0 and not running:
            stress = 32 + 14 * math.sin(math.pi * (hour - 8) / 10) + rng.gauss(0, 8) + (18 if walking else 0)
            samples.append({"metric": "stress", "ts": ms(t), "value": max(1, min(99, round(stress)))})
        t += timedelta(minutes=1)
    if run_at is not None and run_at + timedelta(minutes=35) <= until:
        workouts.append({"start_ts": ms(run_at), "sport": 1, "duration_s": 35 * 60, "calories": 390,
                         "avg_hr": 152, "max_hr": 168, "min_hr": 118})
    return samples, workouts, steps_total


def build(days: int, now: datetime) -> dict:
    """[now] carries the demo person's timezone: bedtimes and walks are local clock times."""
    rng = random.Random(20260927)
    samples, sleep, workouts, totals = [], [], [], []
    start = (now - timedelta(days=days)).replace(hour=22, minute=45, second=0, microsecond=0)
    for d in range(days):
        bed = start + timedelta(days=d, minutes=rng.randint(-35, 35))
        session, night_samples = night(rng, bed)
        if session["end_ts"] > ms(now):
            break
        sleep.append(session)
        samples += night_samples
        wake = datetime.fromtimestamp(session["end_ts"] / 1000, now.tzinfo)
        day_samples, day_workouts, steps = day(rng, wake, now, workout=d % 7 in (1, 3, 5))
        samples += day_samples
        workouts += day_workouts
        read_at = min(now, wake.replace(hour=21, minute=0))
        totals.append({"read_at": ms(read_at), "steps": steps, "distance_m": round(steps * 0.74), "calories": round(steps * 0.04)})
    return {"samples": samples, "sleep": sleep, "workouts": workouts, "daily_totals": totals}


def post(url: str, token: str, path: str, body: dict) -> dict:
    req = urllib.request.Request(url + path, json.dumps(body).encode(), method="POST", headers={
        "Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("url")
    ap.add_argument("token")
    ap.add_argument("--days", type=int, default=35)
    ap.add_argument("--timezone", default="UTC", help="the server's OWNER_TIMEZONE")
    a = ap.parse_args()
    now = datetime.now(ZoneInfo(a.timezone)).replace(second=0, microsecond=0)
    data = build(a.days, now)
    for week in range(0, a.days, 7):  # a weekly weigh-in keeps the weight fresh
        post(a.url, a.token, "/v1/journal", {"kind": "weight", "amount": round(74.6 - week * 0.03, 1),
                                             "ts": (now - timedelta(days=a.days - week)).isoformat()})
    s = data["samples"]
    for i in range(0, len(s), PAGE):
        post(a.url, a.token, "/v1/ingest", {"samples": s[i:i + PAGE]})
    out = post(a.url, a.token, "/v1/ingest", {k: v for k, v in data.items() if k != "samples"})
    print(f"{len(s)} samples, {len(data['sleep'])} nights, {len(data['workouts'])} workouts; last push: {out}")


if __name__ == "__main__":
    main()
