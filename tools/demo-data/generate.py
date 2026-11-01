#!/usr/bin/env python3
"""Synthetic data for a demo server: five weeks of a made-up person, up to now.

Deterministic (fixed seed), never anyone's real data. Pushes through the public API the
phone uses; the demo app then restores it and works every number out itself (D34):

    python3 tools/demo-data/generate.py http://127.0.0.1:8766 <token> [--days 35] [--timezone Europe/Berlin]

Use it only against a throwaway server (see load.sh); it adds five weeks of data. It also writes
integrations.json beside itself: made-up openGym sessions and Dawarich tracks that line up with
the demo person's heart rate and steps, for integrations.py to serve (D41, D42).
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


def day(rng: random.Random, wake: datetime, until: datetime, workout: bool) -> tuple[list[dict], list[dict], int, list[datetime], datetime | None]:
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
    return samples, workouts, steps_total, walks, run_at


# ── openGym and Dawarich (D41, D42): made up, in Berlin to match the demo timezone ──────────

GYM = [("0025", [(40, 10), (60, 8), (60, 8), (62.5, 6)]), ("0027", [(40, 10), (50, 8), (50, 8)]),
       ("0334", [(8, 12), (10, 12), (10, 10)]), ("0241", [(20, 12), (25, 10), (25, 10)]), ("0031", [(20, 10), (25, 8), (25, 8)])]


def gym(rng: random.Random, start: datetime) -> tuple[dict, dict[int, float], datetime]:
    """A 55-minute openGym session from [start]: the session, the HR each minute, its end."""
    entries, hr, t = [], {}, start + timedelta(minutes=2)
    for exercise, sets in GYM:
        logged = []
        for i, (w, r) in enumerate(sets):
            done = t + timedelta(seconds=50)
            hr[ms(t.replace(second=0))] = 132 + rng.gauss(0, 5) + 3 * i
            hr[ms(t.replace(second=0) + timedelta(minutes=1))] = 124 + rng.gauss(0, 4)
            set_ = {"w": w, "r": r, "done": True, "at": ms(done)}
            if i == 0:
                set_ |= {"warmup": True, "phase": "warmup"}
            logged.append(set_)
            t += timedelta(minutes=2, seconds=30)
        entries.append({"id": exercise, "rid": None, "planned": None, "target": None, "topW": max(w for w, _ in sets), "sets": logged})
        t += timedelta(minutes=1)
    end = start + timedelta(minutes=55)
    vol = sum(s["w"] * s["r"] for e in entries for s in e["sets"])
    return ({"id": f"demo-{start:%Y%m%d}", "d": f"{start:%Y-%m-%d}", "start": ms(start), "end": ms(end), "name": "Push and pull",
             "routineId": "demo", "vol": vol, "bw": 74.5, "prs": [], "entries": entries}, hr, end)


def line(a: tuple[float, float], b: tuple[float, float], n: int) -> list[tuple[float, float]]:
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(1, n + 1)]


def track(tid: int, mode: str, start: datetime, points: list[tuple[float, float]], every: timedelta) -> dict:
    """A Dawarich track: the GeoJSON feature and its fixes ([lat, lon] [every] apart)."""
    fixes = [{"id": tid * 1000 + i, "latitude": f"{la:.6f}", "longitude": f"{lo:.6f}", "timestamp": int((start + every * i).timestamp()),
              "velocity": "0", "country_name": "Germany"} for i, (la, lo) in enumerate(points)]
    end = start + every * (len(points) - 1)
    distance = sum(math.dist((a[0] * 111_195, a[1] * 67_700), (b[0] * 111_195, b[1] * 67_700)) for a, b in zip(points, points[1:]))
    return {"feature": {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[lo, la] for la, lo in points]},
                        "properties": {"id": tid, "start_at": start.isoformat(), "end_at": end.isoformat(), "distance": round(distance),
                                       "duration": int((end - start).total_seconds()), "avg_speed": 0, "dominant_mode": mode,
                                       "dominant_mode_emoji": "", "color": "#6366F1", "revision": 1, "mode_timeline": []}},
            "points": fixes}


def lunch_walk(tid: int, start: datetime) -> dict:
    """18 minutes on foot along Straße des 17. Juni, then 5 minutes on a bus to Zoo (no steps: left out)."""
    gate, column, zoo = (52.51627, 13.37770), (52.51451, 13.35010), (52.50695, 13.33240)
    walked = [gate] + line(gate, column, 18)
    return track(tid, "walking", start, walked + line(column, zoo, 5), timedelta(minutes=1))


def evening_run(tid: int, start: datetime) -> dict:
    """35 minutes around Tempelhofer Feld: Dawarich calls it running; the strap's cadence agrees."""
    c = (52.4745, 13.4010)
    pts = [(c[0] + 0.0085 * math.sin(2 * math.pi * i / 35), c[1] + 0.0125 * math.cos(2 * math.pi * i / 35)) for i in range(36)]
    return track(tid, "running", start, pts, timedelta(minutes=1))


def strap_summaries(rng: random.Random, session: dict, wake: datetime, until: datetime, workout: bool, week: list[float]) -> list[dict]:
    """The strap's own figures (D32): SDNN 3-4 times a day, the night's breathing summary at
    the wake day's local midnight, and PAI at 23:59 (or as it stands now on the last day)."""
    out = []
    midnight = wake.replace(hour=0, minute=0)
    hours = (session["end_ts"] - session["start_ts"]) / 3_600_000
    drops = max(0, round(hours * (2.2 + rng.gauss(0, 0.6))))
    out += [{"metric": "odi_valid_time", "ts": ms(midnight), "value": round(hours * 3600)},
            {"metric": "odi_events", "ts": ms(midnight), "value": drops},
            {"metric": "odi_raw", "ts": ms(midnight), "value": round(100 * drops / hours)},
            {"metric": "odi_score", "ts": ms(midnight), "value": rng.randint(93, 97)}]
    for h in (3, 10, 15, 20):
        at = midnight + timedelta(hours=h, minutes=rng.randint(0, 50))
        if at <= until:
            out.append({"metric": "hrv_sdnn", "ts": ms(at), "value": round(80 + rng.gauss(0, 18), 2)})
    points = (28 + rng.uniform(0, 12) if workout else 0) + rng.uniform(1, 5)
    week.append(points)
    at = min(until, wake.replace(hour=23, minute=59))
    out += [{"metric": "pai_daily", "ts": ms(at), "value": round(points, 1)},
            {"metric": "pai_total", "ts": ms(at), "value": round(sum(week[-7:]), 1)}]
    return out


def build(days: int, now: datetime) -> dict:
    """[now] carries the demo person's timezone: bedtimes and walks are local clock times."""
    rng = random.Random(20260927)
    strap_rng = random.Random(20261020)  # its own stream, so the older demo data is unchanged
    pai_week: list[float] = []
    gym_rng = random.Random(20261101)  # its own stream too: the HR of a gym morning replaces the resting minutes
    samples, sleep, workouts, totals = [], [], [], []
    sessions, tracks = [], []
    start = (now - timedelta(days=days)).replace(hour=22, minute=45, second=0, microsecond=0)
    for d in range(days):
        bed = start + timedelta(days=d, minutes=rng.randint(-35, 35))
        session, night_samples = night(rng, bed)
        if session["end_ts"] > ms(now):
            break
        sleep.append(session)
        samples += night_samples
        wake = datetime.fromtimestamp(session["end_ts"] / 1000, now.tzinfo)
        day_samples, day_workouts, steps, walks, run_at = day(rng, wake, now, workout=d % 7 in (1, 3, 5))
        if d % 7 in (0, 4):
            lifted, gym_hr, end = gym(gym_rng, wake.replace(hour=10, minute=0))
            if end <= now:
                sessions.append(lifted)
                lo, hi = lifted["start"], lifted["end"]
                day_samples = [x for x in day_samples if not (lo <= x["ts"] < hi and x["metric"] in ("hr", "steps", "stress"))]
                day_samples += [{"metric": "hr", "ts": t, "value": round(v)} for t, v in gym_hr.items()]
                day_samples += [{"metric": "hr", "ts": t, "value": round(104 + gym_rng.gauss(0, 4))}
                                for t in range(lo, hi, 60_000) if t not in gym_hr]
        if walks[1] + timedelta(minutes=28) <= now:
            tracks.append(lunch_walk(len(tracks) + 1, walks[1]))
        if run_at is not None and run_at + timedelta(minutes=35) <= now:
            tracks.append(evening_run(len(tracks) + 1, run_at))
        samples += day_samples
        samples += strap_summaries(strap_rng, session, wake, now, d % 7 in (1, 3, 5), pai_week)
        workouts += day_workouts
        read_at = min(now, wake.replace(hour=21, minute=0))
        totals.append({"read_at": ms(read_at), "steps": steps, "distance_m": round(steps * 0.74), "calories": round(steps * 0.04)})
    return {"samples": samples, "sleep": sleep, "workouts": workouts, "daily_totals": totals,
            "integrations": {"sessions": sessions, "tracks": tracks}}


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
    out = post(a.url, a.token, "/v1/ingest", {k: v for k, v in data.items() if k not in ("samples", "integrations")})
    with open(__file__.rsplit("/", 1)[0] + "/integrations.json", "w") as f:
        json.dump(data["integrations"], f)
    print(f"{len(s)} samples, {len(data['sleep'])} nights, {len(data['workouts'])} workouts, "
          f"{len(data['integrations']['sessions'])} gym sessions, {len(data['integrations']['tracks'])} tracks; last push: {out}")


if __name__ == "__main__":
    main()
