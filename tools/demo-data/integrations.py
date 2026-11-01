#!/usr/bin/env python3
"""A fake openGym and Dawarich for the demo app (D41, D42): serves the made-up gym sessions and
tracks generate.py wrote to integrations.json, on 127.0.0.1 only. Any pairing code and any API
key are accepted. Standard library only.

    python3 tools/demo-data/integrations.py [--port 8768]
    adb reverse tcp:8768 tcp:8768

In "Ridge demo", Settings → Integrations: openGym http://127.0.0.1:8768 with any 8-character
code, Dawarich http://127.0.0.1:8768 with any key of 16+ characters.
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DATA = json.loads((Path(__file__).parent / "integrations.json").read_text())
TOKEN = "demo-opengym-token-not-a-secret"
USER = {"id": "demo", "name": "Demo", "admin": False}


def when(s: str) -> float:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


class Handler(BaseHTTPRequestHandler):
    def reply(self, body: object, code: int = 200, headers: dict[str, str] | None = None) -> None:
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:
        if self.path == "/api/pair/redeem":
            return self.reply({"token": TOKEN, "user": USER})
        self.reply({"error": "not here"}, 404)

    def do_GET(self) -> None:
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if not self.headers.get("Authorization", "").startswith("Bearer ") and url.path != "/api/health":
            return self.reply({"error": "sign in"}, 401)
        if url.path == "/api/health":
            return self.reply({"ok": True})
        if url.path == "/api/me":
            return self.reply({"user": USER})
        if url.path == "/api/data/rev":
            return self.reply({"rev": 1, "wid": "demo"})
        if url.path == "/api/data":
            return self.reply({"rev": 1, "state": {"unit": "kg", "workouts": DATA["sessions"], "customEx": [], "active": None}})
        if url.path == "/api/v1/tracks":
            lo, hi = when(q["start_at"]), when(q["end_at"])
            found = [t["feature"] for t in DATA["tracks"] if lo <= when(t["feature"]["properties"]["start_at"]) < hi]
            return self.reply({"type": "FeatureCollection", "features": found},
                              headers={"x-current-page": "1", "x-total-pages": "1", "x-total-count": str(len(found))})
        if m := re.fullmatch(r"/api/v1/tracks/(\d+)/points", url.path):
            pts = next((t["points"] for t in DATA["tracks"] if t["feature"]["properties"]["id"] == int(m[1])), None)
            return self.reply(pts) if pts is not None else self.reply({"error": "no such track"}, 404)
        self.reply({"error": "not here"}, 404)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.command} {urlparse(self.path).path} → {args[1] if len(args) > 1 else ''}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--port", type=int, default=8768)
    a = ap.parse_args()
    print(f"{len(DATA['sessions'])} gym sessions, {len(DATA['tracks'])} tracks on http://127.0.0.1:{a.port}")
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
