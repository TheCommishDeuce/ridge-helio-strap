# Self-hosting the Ridge server

One machine running Docker holds everything: TimescaleDB, the API and nightly backups. The
phone pushes what it synced from the strap here, and reads its days back.

You need:

- A machine that is always on (a home server, a small VPS) with **Docker and Compose v2**.
- **An HTTPS address the phone can reach.** The app refuses plain HTTP. Use a reverse
  proxy with a certificate: step 4.
- About 1 GB of disk to start. A year of per-minute data is a few hundred MB.

## 1 · Get the code and set it up

```sh
git clone https://github.com/TheCommishDeuce/ridge-helio-strap.git
cd ridge-helio-strap/deploy
cp .env.example .env
```

Edit `.env`:

- `POSTGRES_PASSWORD`: a long random value, e.g. from `openssl rand -hex 24`.
- `OWNER_TIMEZONE`: your IANA timezone, e.g. `Europe/Berlin`. Days are cut at your local
  midnight.

## 2 · Make the phone's token

```sh
./new-token.sh
```

It prints a 64-character token **once** and stores only its SHA-256 in `.env`. Keep the
token for step 5. Running it again makes a new token and the old one stops working.

## 3 · Start it

```sh
docker compose up -d --build
docker compose ps                         # db, api and backup: running / healthy
curl -fsS http://127.0.0.1:8766/healthz   # {"ok":true}
```

The API creates and upgrades its database tables on every start.

## 4 · Put HTTPS in front

The API speaks plain HTTP on `127.0.0.1:8766` (set `RIDGE_BIND`/`RIDGE_PORT` in `.env` to
change it). Never publish that port to the internet: Docker's port rules bypass host
firewalls such as ufw. Terminate TLS in a reverse proxy instead.

**Caddy** (gets and renews the certificate itself). Point a DNS name at the machine, open
ports 80 and 443, and use this `Caddyfile`:

```
ridge.example.com {
    reverse_proxy 127.0.0.1:8766
    request_body {
        max_size 8MB
    }
}
```

**Traefik, nginx, or a proxy on another machine**: route `https://ridge.example.com` to
`http://<RIDGE_BIND>:8766`. If the proxy is on another machine, set `RIDGE_BIND` to this
machine's **private** address, not `0.0.0.0`. Allow request bodies of 8 MB: the first
upload after a long gap is large.

Check from outside your network: `curl -fsS https://ridge.example.com/healthz`.

## 5 · Connect the app

In Ridge: the gear → **Server**. Enter `https://ridge.example.com` and the token from
step 2, then sync. What the strap delivered is uploaded after every sync, and the server
derives recovery, strain, sleep and the rest from it.

## Updating

```sh
cd ridge-helio-strap && git pull && cd deploy && docker compose up -d --build
```

Migrations run on start. After an update that changes how metrics are computed (the
release notes say so), recompute every day from the raw data:

```sh
docker compose exec api python -m strap_server.rederive
```

## Backups

The `backup` service dumps the database when it starts and every 24 hours into
`deploy/backups/ridge_<date>.sql.gz`, keeping 14 days. These backups sit on the same disk
as the database, so copy that folder elsewhere as well (another disk, Syncthing, restic…).

```sh
ls -lt backups | head -3
docker compose logs --tail 3 backup
```

**Restore** replaces the whole database with a dump. Use it only after losing data. The API
is stopped while it runs.

```sh
F=$(ls -t backups/ridge_*.sql.gz | head -1) && echo "restoring $F" \
 && docker compose stop api \
 && docker compose exec db psql -U strap -d postgres -c "DROP DATABASE strap WITH (FORCE)" -c "CREATE DATABASE strap OWNER strap" \
 && docker compose exec db psql -U strap -d strap -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()" \
 && gunzip -c "$F" | docker compose exec -T db psql -q -U strap -d strap \
 && docker compose exec db psql -U strap -d strap -c "SELECT timescaledb_post_restore()" \
 && docker compose start api
```

## Limits

- **One person per server.** One device token; there are no accounts or sign-up.
- The token is the only credential. Treat it like a password, and make a new one with
  `./new-token.sh` if it leaks.
