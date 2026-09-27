#!/usr/bin/env sh
# Makes a new phone token: stores only its SHA-256 in deploy/.env and prints the token once.
# A previous token stops working. Restart the API afterwards: docker compose up -d
set -eu
cd "$(dirname "$0")"
[ -f .env ] || { echo "deploy/.env is missing: cp .env.example .env first." >&2; exit 1; }
token=$(openssl rand -hex 32)
hash=$(printf %s "$token" | openssl dgst -sha256 -r | cut -d' ' -f1)
tmp=$(mktemp)
grep -v '^DEVICE_TOKEN_SHA256=' .env > "$tmp" || true
printf 'DEVICE_TOKEN_SHA256=%s\n' "$hash" >> "$tmp"
cat "$tmp" > .env && rm -f "$tmp"
chmod 600 .env
echo "Phone token (shown once; enter it in the app with your server's https address):"
echo "$token"
