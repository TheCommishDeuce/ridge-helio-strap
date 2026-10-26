# How Ridge fits together

```
 Helio Strap ──Bluetooth LE──▶ Ridge (Android): store, science, screens ──HTTPS, nightly──▶ server (optional backup)
                                                  ◀──────────── restore, once ────────────┘
```

Three pieces. The **strap** records. The **phone** collects from it, keeps everything and works
out every number itself, offline (D34). The **server**, if you run one, is cold storage: it keeps
what the phone sends, so a new phone can restore it.

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
   not yet backed up. The watermarks move forward, so the next sync resumes from there.
4. **Derive.** The phone works out every day from the earliest one the sync touched
   ([`strap-science`](../android/strap-science), [spec/02](spec/02-science.md)), in one
   transaction; a changed profile or weight re-derives the whole history.
5. **Read.** The screens read the phone's own numbers. Energy, the day's "usual" values and
   the charts are worked out when shown.
6. **Back up.** Once a night, while the phone charges on Wi-Fi (or Settings → Back up now),
   everything not yet sent goes to the server: samples first, sleep, workouts and counters,
   then the profile and journal.

A sync runs when you press the button, when the app opens and the last complete sync is over
15 minutes old, or from the optional background collection job (D30). One component,
`SyncRunner`, owns the Bluetooth connection; a second request while it is busy is refused or
waits, never runs alongside. While Today shows today, it holds one connection with the strap's
real-time step reporting on, the one connection Ridge keeps open (spec/01 §5, D34), and drops it
when Today leaves the screen.

## The phone

- `android/strap-protocol`: pure Kotlin on the JVM, with no Android in it. Crypto
  (AES, B-163 on 64-bit limbs), chunk framing and encryption, the handshake and
  fetch-round state machines, and every decoder. Its tests run the whole protocol against
  a fake strap built from the spec, plus byte vectors read from real hardware.
- `android/strap-science`: pure Kotlin on the JVM. Every derived number: the per-night and
  per-day passes, the illness flag, Energy, the day summary, the chart series. It reads raw
  data through an interface and writes a derived store, never SQL. It was ported from the
  former server's science and is held to its golden outputs: the seed fixture (172 values)
  and six weeks of the demo person with a trip, a DST change and the strap's own summaries
  (957 rows, 43 summaries, the series), plus the server's known-value tests.
- `android/app`: the Compose UI, `AndroidStrapLink` (the Bluetooth GATT transport),
  `SyncRunner`, `LocalStore` (SQLite: samples, nights, workouts, counters, derived rows,
  illness flags, the journal, watermarks, backup flags), `PhoneScience` (keeps the derived
  rows current), `Owner` (the profile, journal and timezones), `Restore`, `KeyVault` (the
  auth key and server token, encrypted under an Android Keystore key) and `PushClient`.

## The server

- `ingest/`: validates and upserts what the phone sends. Unknown streams are counted and
  dropped, never guessed at. The Zepp-cloud backfill (D29) fills only what the strap left empty.
- `journal.py`: the server's copy of the journal and the profile.
- `backup.py`: `GET /v1/backup/records` and `/v1/backup/samples` (paged), what a restoring
  phone reads.
- `migrations/` and `migrate.py`: schema changes, applied on every start.

## Rules the design keeps

- **The phone is canonical; the server is its backup.** A phone without a server keeps
  everything itself; one with a server can be wiped and restored.
- **No invented numbers.** A metric whose inputs are missing or stale is withheld with a
  reason, never zero, never a population default presented as yours.
- **No composite scores without a written exception.** Recovery is always shown with its
  breakdown. The one documented exception is in spec/02 §2.11.
- **The protocol is written down before it is coded.** Byte layouts live in spec/01, and
  new ones are probed read-only on real hardware first. Writes to the strap are always
  read back.
