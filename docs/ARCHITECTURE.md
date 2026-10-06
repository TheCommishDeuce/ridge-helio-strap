# How Ridge fits together

```
 Helio Strap ──Bluetooth LE──▶ Ridge (Android) ──HTTPS: upload──▶ server (FastAPI) ──▶ TimescaleDB
                               ◀──────────── HTTPS: days, series, summaries ────────────┘
```

Three pieces. The **strap** records. The **phone** collects from it and forwards. The
**server** keeps everything and does the arithmetic. The phone holds a cache and an outbox,
and the server's database is the only complete copy.

## One sync, end to end

1. **Connect and authenticate.** The app connects to the strap by MAC and runs the Zepp OS
   handshake: B-163 elliptic-curve key agreement, then proof of the 16-byte auth key.
   That yields a session key for encrypted messages
   ([spec/01 §3–4](spec/01-strap-protocol.md)).
2. **Fetch.** First the since-midnight counters. Then, for each kind of data (per-minute
   activity, heart rate, stress, SpO₂, sleep, workouts, …), everything since that kind's
   own watermark. The app pages through the strap's rounds, checks each one's length and
   checksum, and retries or skips as spec/01 §6 prescribes.
3. **Store.** Everything lands in the phone's SQLite store in one transaction, marked as
   not yet uploaded. The watermarks move forward, so the next sync resumes from there.
4. **Upload.** The outbox goes to the server in pages: samples first, sleep sessions and
   workouts last. A failed upload resumes on the next sync, even if the strap part failed.
5. **Derive.** The server upserts idempotently, then recomputes the nights and days the new
   data touched.
6. **Read.** The app's screens ask the server for a day's per-minute series, summaries
   and history.

A sync runs when you press the button, when the app opens and the last complete sync is
over 15 minutes old, or from the optional background jobs: collection (strap → phone) and
upload (phone → server), each on its own interval (D30). One component, `SyncRunner`, owns
the Bluetooth connection. A second request while it is busy is refused, never queued. The
same goes for short strap jobs such as reading or writing alarms.

## The phone

- `android/strap-protocol`: pure Kotlin on the JVM, with no Android in it. Crypto
  (AES, B-163 on 64-bit limbs), chunk framing and encryption, the handshake and
  fetch-round state machines, and every decoder. Its tests run the whole protocol against
  a fake strap built from the spec, plus byte vectors read from real hardware.
- `android/app`: the Compose UI, `AndroidStrapLink` (the Bluetooth GATT transport),
  `SyncRunner`, `LocalStore` (SQLite: samples, nights, workouts, counters, watermarks,
  outbox flags), `KeyVault` (the auth key and server token, encrypted under an Android
  Keystore key) and `PushClient`.

## The server

- `ingest/`: validates and upserts what the phone sends. Unknown streams are counted and
  dropped, never guessed at.
- `derive/`: the science. One module per metric family, run in dependency order by
  `orchestrator.py`. The formulas were ported from the previous implementation and are
  held to it by a golden fixture, 172 values reproduced exactly
  ([spec/02](spec/02-science.md)).
- `read/`: the read API: day series at full per-minute resolution, week and month buckets
  that keep the minimum and maximum (so a spike survives any zoom), and day summaries in
  which each value is either present with its baseline or withheld with a named reason.
- `migrations/` and `migrate.py`: schema changes, applied on every start.
  `rederive.py` recomputes everything from the raw data after a formula change.

## Rules the design keeps

- **The server is canonical.** The phone can be wiped and re-synced. The database is what
  gets backed up.
- **No invented numbers.** A metric whose inputs are missing or stale is withheld with a
  reason, never zero, never a population default presented as yours.
- **No composite scores without a written exception.** Recovery is always shown with its
  breakdown. The one documented exception is in spec/02 §2.11.
- **The protocol is written down before it is coded.** Byte layouts live in spec/01, and
  new ones are probed read-only on real hardware first. Writes to the strap are always
  read back.
