#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27", "cryptography>=42", "qrcode>=7.4"]
# ///
"""One-shot: Zepp account -> the strap's MAC and 16-byte auth key (decision O4).

The app has no Zepp code. Run this once per pairing, then paste the key into the app or
scan the QR it prints. Nothing is stored; the password is read without echo and is never
printed, and neither are the tokens.

    uv run tools/keyfetch/keyfetch.py you@example.com [--qr]

WARNING: signing in bumps the Zepp phone app's session (Zepp allows one session). The key
itself is unaffected; the Zepp app just asks you to sign in again next time you open it.

Protocol: spec/01 §1, transcribed from the old app's `data/pairing/zepp_*.dart`, which
transcribed the Python `huami_token` reference. Field ORDER and the duplicated fields are
part of the wire format; do not tidy them.
"""

from __future__ import annotations

import argparse
import getpass
import json
import secrets
import sys
import uuid
from urllib.parse import parse_qs, urlencode, urlsplit

import httpx
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

TOKENS_URL = "https://api-user-us2.zepp.com/v2/registrations/tokens"
LOGIN_URL = "https://api-mifit-us2.zepp.com/v2/client/login"
DEVICES_URL = "https://api-mifit.zepp.com/users/{user_id}/devices"

# Public constants of the Zepp Android client — obfuscation, not a secret.
CIPHER_KEY = b"xeNtBVqzDc6tuNTh"
CIPHER_IV = b"MAAAYAAAAAAAAABg"

APP_VERSION = "9.12.5"
CLIENT_VERSION = f"151689_{APP_VERSION}"
BUILD_STAMP = "202509151347"
CHANNEL = "a100900101016"
USER_AGENT = f"Zepp/{APP_VERSION} (Pixel 4; Android 12; Density/2.75)"


class KeyFetchError(Exception):
    """A named failure. Never carries a response body: on this host bodies hold tokens."""


def token_form(email: str, password: str) -> bytes:
    fields = [
        ("emailOrPhone", email),
        ("state", "REDIRECTION"),
        ("client_id", "HuaMi"),
        ("password", password),
        ("redirect_uri", "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html"),
        ("region", "us-west-2"),
        ("token", "access"),
        ("token", "refresh"),
        ("country_code", "US"),
    ]
    return urlencode(fields).encode("ascii")


def encrypt_payload(plain: bytes) -> bytes:
    padder = padding.PKCS7(128).padder()
    padded = padder.update(plain) + padder.finalize()
    encryptor = Cipher(algorithms.AES(CIPHER_KEY), modes.CBC(CIPHER_IV)).encryptor()
    return encryptor.update(padded) + encryptor.finalize()


def access_token(client: httpx.Client, email: str, password: str) -> str:
    headers = {
        "app_name": "com.huami.midong",
        "appname": "com.huami.midong",
        "cv": CLIENT_VERSION,
        "v": "2.0",
        "appplatform": "android_phone",
        "vb": BUILD_STAMP,
        "vn": APP_VERSION,
        "user-agent": USER_AGENT,
        "x-hm-ekv": "1",
        "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
    }
    r = client.post(TOKENS_URL, content=encrypt_payload(token_form(email, password)), headers=headers)
    if r.status_code in (400, 401, 403):
        raise KeyFetchError("Zepp rejected the email or password.")
    if not 300 <= r.status_code < 400:
        raise KeyFetchError(f"sign-in: expected a redirect, got HTTP {r.status_code} (Zepp API changed?)")
    query = parse_qs(urlsplit(r.headers.get("location", "")).query)
    if query.get("access"):
        return query["access"][0]
    if "error" in query or "error_code" in query:
        raise KeyFetchError("Zepp rejected the email or password.")
    raise KeyFetchError("sign-in: no access token in the redirect (Zepp API changed?)")


def app_session(client: httpx.Client, token: str) -> tuple[str, str]:
    form = {
        "code": token,
        "device_id": str(uuid.uuid4()),
        "device_model": "android_phone",
        "app_version": APP_VERSION,
        "dn": "api-mifit.zepp.com,api-user.zepp.com,api-mifit.zepp.com,api-watch.zepp.com,"
        "app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com",
        "third_name": "huami",
        "source": f"com.huami.watch.hmwatchmanager:{APP_VERSION}:151689",
        "app_name": "com.huami.midong",
        "country_code": "US",
        "grant_type": "access_token",
        "allow_registration": "false",
        "lang": "en",
        "countryState": "US-NY",
    }
    headers = {
        "app_name": "com.huami.webapp",
        "appname": "com.huami.webapp",
        "origin": "https://user.zepp.com",
        "referer": "https://user.zepp.com/",
        "user-agent": "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "accept": "application/json, text/plain, */*",
        "accept-language": "en-US,en;q=0.5",
    }
    r = client.post(LOGIN_URL, data=form, headers=headers)
    if r.status_code != 200:
        raise KeyFetchError(f"login: HTTP {r.status_code} (Zepp API changed?)")
    info = _json(r, "login").get("token_info") or {}
    if not info.get("app_token") or not info.get("user_id"):
        raise KeyFetchError("login: no app token / user id in the reply (Zepp API changed?)")
    return str(info["user_id"]), str(info["app_token"])


def devices(client: httpx.Client, user_id: str, app_token: str) -> list[dict]:
    request_id = str(uuid.uuid4())
    query = [
        ("r", request_id), ("r", request_id),
        ("enableMultiDeviceOnMultiType", "true"), ("enableMultiDeviceOnMultiType", "true"),
        ("userid", user_id),
        ("appid", str(secrets.randbits(64))),
        ("channel", CHANNEL),
        ("country", "US"),
        ("cv", CLIENT_VERSION),
        ("device", "android_32"),
        ("device_type", "android_phone"),
        ("enableMultiDevice", "true"),
        ("lang", "en_US"),
        ("timezone", "Europe/London"),
        ("v", "2.0"),
    ]
    headers = {
        "hm-privacy-diagnostics": "false",
        "hm-privacy-ceip": "false",
        "country": "US",
        "appplatform": "android_phone",
        "x-request-id": str(uuid.uuid4()),
        "timezone": "Europe/London",
        "channel": CHANNEL,
        "vb": BUILD_STAMP,
        "cv": CLIENT_VERSION,
        "appname": "com.huami.midong",
        "v": "2.0",
        "vn": APP_VERSION,
        "apptoken": app_token,
        "lang": "en_US",
        "user-agent": USER_AGENT,
    }
    r = client.get(DEVICES_URL.format(user_id=user_id) + "?" + urlencode(query), headers=headers)
    if r.status_code != 200:
        raise KeyFetchError(f"device list: HTTP {r.status_code} (Zepp API changed?)")
    items = _json(r, "device list").get("items")
    if not isinstance(items, list):
        raise KeyFetchError("device list: no items in the reply (Zepp API changed?)")
    return [d for d in (parse_device(i) for i in items if isinstance(i, dict)) if d]


def parse_device(item: dict) -> dict | None:
    """A bound device with a usable 16-byte key, or None."""
    mac = item.get("macAddress")
    info = item.get("additionalInfo") or {}
    if isinstance(info, str):
        try:
            info = json.loads(info)
        except json.JSONDecodeError:
            info = {}
    key = str(info.get("auth_key") or "").lower().removeprefix("0x")
    if not mac or len(key) != 32 or any(c not in "0123456789abcdef" for c in key):
        return None
    name = (info.get("deviceName") or item.get("deviceType") or "").strip() or None
    active = item.get("activeStatus") in (True, 1, "1", "true")
    return {"mac": mac.upper(), "key": key, "name": name, "active": active}


def qr_payload(device: dict) -> str:
    """What the app's key-entry screen scans. Versioned so the format can change."""
    return json.dumps({"v": 1, "mac": device["mac"], "key": device["key"]}, separators=(",", ":"))


def _json(r: httpx.Response, step: str) -> dict:
    try:
        body = r.json()
    except ValueError as e:
        raise KeyFetchError(f"{step}: reply is not JSON (Zepp API changed?)") from e
    if not isinstance(body, dict):
        raise KeyFetchError(f"{step}: reply is not an object (Zepp API changed?)")
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("email")
    parser.add_argument("--qr", action="store_true", help="also print a QR code for the app")
    args = parser.parse_args()
    password = getpass.getpass("Zepp password (not echoed): ")
    try:
        with httpx.Client(timeout=20, follow_redirects=False) as client:
            user_id, app_token = app_session(client, access_token(client, args.email, password))
            found = devices(client, user_id, app_token)
    except httpx.HTTPError as e:
        print(f"network error: {type(e).__name__}", file=sys.stderr)
        return 2
    except KeyFetchError as e:
        print(str(e), file=sys.stderr)
        return 1
    if not found:
        print("The account has no bound device with a usable key.", file=sys.stderr)
        return 1
    for d in found:
        print(f"{d['name'] or 'device'}  mac={d['mac']}  key={d['key']}  {'(active)' if d['active'] else ''}")
        if args.qr:
            import qrcode

            qr = qrcode.QRCode(border=1)
            qr.add_data(qr_payload(d))
            qr.print_ascii(invert=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
