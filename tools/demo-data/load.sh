#!/usr/bin/env bash
# A throwaway demo server with synthetic data, for screenshots and UI work without a strap.
# Runs the deploy/ stack as its own Compose project on 127.0.0.1:8767 (API and database only)
# and prints what the demo app (`./gradlew :app:assembleDemo`) needs.
#
#   tools/demo-data/load.sh          start and fill it
#   tools/demo-data/load.sh down     remove it, data and all
set -euo pipefail

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
compose=(docker compose -p ridge-demo -f "$repo/deploy/compose.yaml" --env-file "$repo/tools/demo-data/.env")

if [ "${1:-}" = down ]; then "${compose[@]}" down -v; rm -f "$repo/tools/demo-data/.env"; exit 0; fi

token=$(openssl rand -hex 32)
printf 'POSTGRES_PASSWORD=%s\nOWNER_TIMEZONE=%s\nDEVICE_TOKEN_SHA256=%s\nRIDGE_PORT=8767\n' \
    "$(openssl rand -hex 16)" "${DEMO_TIMEZONE:-Europe/Berlin}" \
    "$(printf %s "$token" | openssl dgst -sha256 -r | cut -d' ' -f1)" > "$repo/tools/demo-data/.env"
"${compose[@]}" up -d --build --wait db api

python3 "$repo/tools/demo-data/generate.py" http://127.0.0.1:8767 "$token" --timezone "${DEMO_TIMEZONE:-Europe/Berlin}"
# The profile (made up) has no API yet; then recompute every day with it in place.
"${compose[@]}" exec -T db psql -q -U strap -d strap -c \
    "INSERT INTO profile (user_id, height_cm, sex, dob, srpa) VALUES ('00000000-0000-0000-0000-000000000001', 176, 'male', '1991-05-12', 2) ON CONFLICT (user_id) DO UPDATE SET height_cm = 176, sex = 'male', dob = '1991-05-12', srpa = 2"
"${compose[@]}" exec -T api python -m strap_server.rederive | tail -1

cat <<EOT

Demo server ready. On the phone (USB or wireless adb):
  adb reverse tcp:8767 tcp:8767
  adb install -r android/app/build/outputs/apk/demo/app-demo.apk
In "Ridge demo" setup: any MAC and 32-hex key; server http://127.0.0.1:8767  token: $token
(The first sync fails without a strap: press "Continue to Today".)
Remove it afterwards: tools/demo-data/load.sh down
EOT
