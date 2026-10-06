# 01 · Strap protocol — everything the Amazfit Helio Strap gives us over BLE

Language-agnostic extraction of the proven BLE layer. Source of truth at extraction
time: `healthee@049c9ad`, `apps/mobile/lib/ble/**` (Dart, itself a verbatim port of
HelioCore/Gadgetbridge, validated on real hardware and against an HCI snoop of the
official Zepp app). The old project's protocol notes (`zeppos_ble_handshake.md`,
`zepp_app_data_audit.md`) are not published; everything they established is in this file.

**Rule for the rebuild:** byte layouts, sentinels, paging rules and fetch windows below
are empirical. Port them exactly; "odd-looking" arithmetic is load-bearing. Every
decoder gets a known-value test built from the tables here, not from the old code.

All integers are **little-endian**. "sec" = Unix epoch seconds, uint32 LE.

---

## 1. Credentials (once per strap)

Two secrets, both per-owner, both stored in the Android keystore — never build config,
never logged:

| Secret | Form | Source |
|---|---|---|
| MAC address | `AA:BB:…` | BLE scan / Zepp device list |
| Auth key | 16 bytes, stored as 32 hex chars (optional `0x` prefix) | Zepp cloud (below) or a Gadgetbridge export (`Export_preference_device.xml`, key `authkey`) |

The key is static per pairing: fetch once, then BLE is fully offline.

### Zepp cloud login (auth-key fetch)

Region hardcoded `us2`. Three calls:

1. `POST https://api-user-us2.zepp.com/v2/registrations/tokens` — form body built from
   email+password, **AES-128-CBC/PKCS7** with the public client constants
   key=`xeNtBVqzDc6tuNTh`, iv=`MAAAYAAAAAAAAABg` (not secret). Reply is a redirect; the
   access token is in the `Location` header query.
2. `POST https://api-mifit-us2.zepp.com/v2/client/login` → app token + user id.
3. `GET https://api-mifit.zepp.com/users/{user_id}/devices` → per-device payload
   including `auth_key` and MAC.

Form field **order** and duplicated fields (`app_name` *and* `appname`, `token` twice)
are part of the wire format — Zepp rejects requests that drop them. Impersonated client:
app version `9.12.5`, client version `151689_9.12.5`, build `202509151347`, channel
`a100900101016`, UA `Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)`. Exact forms:
`apps/mobile/lib/data/pairing/zepp_endpoints.dart` (reference: Python `huami_token`).
⚠ Cloud login bumps the owner's Zepp phone session (single-session policy).

---

## 2. GATT

Huami base UUID suffix `-0000-3512-2118-0009af100700`.

| Role | Char |
|---|---|
| Chunked WRITE (app→strap) | `00000016-…` |
| Chunked NOTIFY (strap→app) | `00000017-…` (ACKs are also written here) |
| Activity-fetch CONTROL (write + notify) | `00000004-…` |
| Activity-fetch DATA (notify) | `00000005-…` |
| Battery level (standard) | `2A19` (1 byte, 0–100) |
| Live HR (standard, unused so far) | `2A37` |

Also physically present but unused: `0001 0002 0006 0023 0024 0025 1531 1532`, `2a38`,
`fedd`, `fede`. Services: `1800 1801 180a fee0 1530 180d fee1 180f`.

**Connect sequence (order matters):** connect (20 s timeout, autoConnect=false) →
request **MTU 247** (best-effort; chunk size depends on it, so request before first
write) → discover → subscribe `0017` → handshake → subscribe `0004` + `0005`.

---

## 3. Chunked transport (chars 0016/0017) — "Huami2021"

Multiplexes logical **endpoints** over one char pair.

### Chunk header

| off | size | field |
|---|---|---|
| 0 | 1 | `0x03` marker |
| 1 | 1 | flags: `0x01` first · `0x02` last · `0x04` needs-ACK (set with last) · `0x08` encrypted |
| 2 | 1 | `0x00` |
| 3 | 1 | handle — one per message, `(prev+1) & 0xFF`, starts at 1 |
| 4 | 1 | count — chunk index in message |
| 5 | 4 | *first chunk only*: **original** (plaintext) payload length, u32 |
| 9 | 2 | *first chunk only*: endpoint, u16 |
| 5/11 | … | payload slice |

Payload budget per chunk = `(mtu − 3) − header` (header 11 first, 5 after). The
encoder sets `0x02|0x04` on the final chunk.

### Encryption (post-auth, per message)

- `messageKey[i] = sessionKey[i] ^ handle` (i = 0..15), AES-128-**ECB**, no padding.
- plaintext = `data ‖ seq u32 ‖ crc32(data ‖ seq) u32`, zero-padded to a multiple of 16.
  `seq` starts at the value derived in auth and increments per encrypted message sent.
- CRC-32 = IEEE/zlib (poly `0xEDB88320`, init/xorout `0xFFFFFFFF`).
- Decoder: ciphertext length = `pad16(originalLength + 8)`; decrypt, keep the first
  `originalLength` bytes.
- Auth frames (endpoint `0x0082`) are never encrypted.

### ACK

When a reassembled incoming frame had flag `0x04`, write to char **0017**:
`[0x04, 0x00, handle, 0x01, count]`. A failed ACK write is logged, not fatal.

### Endpoints we use

| Endpoint | Purpose |
|---|---|
| `0x0082` | Auth handshake |
| `0x0016` | Daily totals: send `[0x03]` → reply below |
| `0x0000` | Services list reply `[0x04, n u16, (endpoint u16, encrypted u8) × n]` — diagnostic; proves `0x004b` (ZeppOS activity fetch) is **unsupported**, which is why history uses chars 0004/0005 |

Seen in the official app's sync but not used by us: `0x0047` set-time, `0x0029` sync
range, `0x0028` status/config, `0x000a`/`0x0019` encrypted bulk channel.

**Services list, read from the strap 2026-09-26** (request `[0x03]` unencrypted on `0x0000`;
the strap does not volunteer it; `*` = encrypted): `0000 000a* 000c* 000d 000f 0015 0016
0017* 0018* 0019* 001a* 001d 0022 0025 0028 0029 0030* 0031 0032 0036* 0043 0047 0048 0049
004b 004d* 0081* 0082`. Note `0x004b` IS listed, contrary to the table above (history via
chars 0004/0005 works and stays).

**What the endpoints are** (names from Gadgetbridge's Zepp OS services, reading only, D8;
2026-09-27). Only the ones marked ✓ have been talked to.

| Endpoint | Service | Notes |
|---|---|---|
| `0000` ✓ | Services list | above |
| `000a*` | Config | settings groups, below; HR/stress/SpO₂/sleep and workout-detection settings live here |
| `000d` | File transfer | |
| `000f` ✓ | Alarms | below |
| `0015` | Connection | MTU request `01`→`02`, ping `03`/pong `04` |
| `0016` ✓ | Steps (daily totals) | §5 |
| `0017*` | User info | set only (`01`) |
| `0018*` | Vibration patterns | |
| `0019*` | Workout | live workout events (status `11`, app open `20`); no settings |
| `001a*` | Find device | |
| `001d` | Heart rate | realtime HR `04`→`05`, sleep events `06`; no settings |
| `0029` | Battery | request `03` → `04` |
| `0043` | Device info | request `01` → `02` |
| `0047` | Time | |
| `004b` | Activity fetch (Zepp OS) | unused, §6 uses chars 0004/0005 |
| `0082` ✓ | Auth | §4 |

Not in Gadgetbridge, left alone (no read command is known, so no probe is safe): `000c* 0022 0025
0028 0030* 0031 0032 0036* 0048 0049 004d* 0081*`.

### Config (endpoint `0x000a`, encrypted) — read verified on the strap 2026-09-27

Commands: `01` capabilities → `02 · version · n · n group ids`; `03 · constraints(1) · group ·
n · n keys` read (n = 0: every key) → `04 · status(1 = ok) · group · version · constraints ·
n · n entries`; `05` set → `06` ack (not used yet).

Entry: `key u8 · type u8 · value`, little-endian. Types (constraints part only when asked):
`0b` bool (1 byte, 0/1) · `10` byte + `n · n options` · `11` byte list `n · values` + `n ·
options` · `01` i16 + min + max · `03` i32 + min + max · `50` i32, never constrained · `02` i16
list `n · values` + min count u8 + max count u8 + min i16 + max i16 · `20` NUL-terminated
string + max length u8 · `21` string + max length + `n · n strings` · `30` hour u8, minute u8 ·
`40` i64 epoch millis. An unknown type ends decoding: its length is unknown.

Groups: `08` HEALTH — `01` HR interval (minutes; `0` off, `ff` smart, `fe` continuous), `02`/`03`
high/low HR alert (bpm, 0 off), `04` HR during activity, `05` broadcast HR, `11` high-accuracy
sleep, `12` sleep breathing, `13` stress, `14` relaxation reminder, `31` all-day SpO₂, `32`
low-SpO₂ alert. `09` WORKOUT — `05` HR zones, `40` detection categories, `41` detection alert,
`42` sensitivity (0 high, 1 standard, 2 low).

**Seen on a Helio Strap** (fw 0.132.27.2; replies are test vectors in `ConfigTest`):
capabilities `02 03 05 · 00 0b 08 09 0a` (version 3, five groups; groups `00`, `0b`, `0a` are
not in the reference's HEALTH/WORKOUT sense: `00` has flags `0b 0c 10`, `0b` has flags `01 02
03`, `0a` has flags `09 2c 2d`). The read order does not matter (a HEALTH read before
capabilities gets the same reply).

- HEALTH v3, 29 entries, 174 bytes, all decoded. HR interval `fe` = **continuous**, options
  off/continuous/1/5/10/30 min. Options offered: high HR alert 0 or 100–150 by 10, low HR
  alert 0/40/45/50, low-SpO₂ alert 0/80/85/90 %. The switches `04 11 12 13 14 31` are bools.
  Inactivity warnings: `41` switch, `42`/`43` window, `44` pause switch, `45`/`46` pause
  window (clock times). Goals `51`–`57` (`52` steps, 2000–30000). Keys not in the reference:
  `16` bool, `18` byte (options 0/1/5/10/15/30), `21` `22` bools, `71` byte (0/1), `83` bool.
- WORKOUT v1, 11 entries, 103 bytes, all decoded. `05` HR zones (six i16, each 30–220), `06`
  a second zone set, `07` a number 30–100, `20` byte with no options, `30`/`31` a reminder
  switch and time, `40` categories (`df` the only option), `42` sensitivity. No `41`
  detection alert. Unknown: `04` (0/1), `50` (0–2), `51` (0–5).
- Once, on the first connection after an install, the two reads came back as 232 and 125
  bytes and HEALTH stopped decoding after `01`; it did not recur. An incomplete group now
  logs its raw bytes (`config group … not fully decoded`) so a repeat can be studied.

**Current Time `2a2b`** (standard GATT characteristic, plain read; read on every connection
since 2026-10-06, D30): `year u16 · month · day · hour · minute · second · weekday · fractions ·
adjust reason · tz` with tz = offset / 15 min, signed. Seen: `ea07 0a 06 0a 15 36 02 00 00 08` =
2026-10-06 10:21:54, Tuesday, **+02:00** — with the phone on a +04:00 zone. So the strap's
clock keeps the zone the Zepp app last set; nothing in Ridge sets it. Its local reading minus the
true UTC time, rounded to 15 min, is the offset alarms are converted against.

Battery `0029`: `03` → 21 bytes `04 · 0f · level · charging · 2 × (u16 year, month, day,
hour, minute, second, tz byte) · level`; the second timestamp is likely the last charge. Device info `0043`: `01` → `02 …` with the
strap's MAC, serial number and firmware/hardware versions as NUL-terminated strings — kept
out of the repo on purpose (serial, MAC).

### Alarms (endpoint `0x000f`, unencrypted) — read, update, create, delete verified 2026-09-26

Commands (reference: Gadgetbridge's Zepp OS alarms service, reading only, D8): `0x01`
capabilities → `0x02`; `0x09` list → `0x0a`; `0x03` create → `0x04`; `0x07` update →
`0x08`; `0x05` delete → `0x06`; `0x0f` the strap reports a change.

- Capabilities reply seen: `02 03 0a 00 00 00` (likely version 3, 10 slots).
- List reply: `0a · count u8 · count × 10-byte entries`. Entry: `flags u8` (`0x04` enabled,
  `0x01` smart wake) · `slot u8` · `hour u8` · `minute u8` · `repeat u8` (bit 0 = Monday …
  bit 6 = Sunday; `0x00` = once) · 5 bytes unknown (seen `00 00 00 01 00` on every entry;
  write back unchanged).
- Writes: `03 01 · entry` create → `04`; `07 01 · entry` update → `08`; `05 01 · slot`
  delete → `06`. Each acked within the 5 s wait; a re-read showed the change. The entry's
  last five bytes are written back as read (new alarms: `00 00 00 01 00`).
- Examples (the `AlarmsTest` vector): `04 00 07 00 1f …` (on, 07:00, Mon–Fri),
  `00 01 09 00 60 …` (off, 09:00, Sat–Sun), `00 02 06 1e 00 …` (off, 06:30, once).

---

## 4. Auth handshake (endpoint 0x0082)

ECDH over **NIST B-163 (sect163r2)** — the old code's file says `sect163k1`, but its constants are B-163's — `y² + xy = x³ + x² + b` over GF(2¹⁶³), reduction
`x¹⁶³ + x⁷ + x⁶ + x³ + 1`. Keys: private 24 B, public 48 B = `x[24] ‖ y[24]`, each a
little-endian array of six u32 words.

| const | u32 words LE (w0…w5) |
|---|---|
| poly | `000000c9 00000000 00000000 00000000 00000000 00000008` |
| b | `4a3205fd 512f7874 1481eb10 b8c953ca 0a601907 00000002` |
| Gx | `e8343e36 d4994637 a0991168 86a2d57e f0eba162 00000003` |
| Gy | `797324f1 b11c5c0c a2cdd545 71a0094f d51fbc6c 00000000` |

Private key: 24 random bytes, masked `byte[20] &= 0x03`, bytes 21–23 zeroed (≤ 162 bits);
regenerate if its bit length is < 81. Group order n = `0x40000000000000000000292fe77e70c12a4234c33`.
(Reference impls: Gadgetbridge `util/ECDH_B163.java`, HelioCore `HuamiECDH`. Not
constant-time — interop only.)

| step | dir | bytes |
|---|---|---|
| 1 | → | `04 02 00 02` ‖ our pubkey (48) — 52 B |
| 2 | ← | `10 04 <status>` ‖ remoteRandom (16) ‖ remotePub (48) — 67 B; status `01` = ok |
| 3 | — | `shared = ECDH(priv, remotePub)`; `seq = u32(shared[0..4])`; `sessionKey[i] = shared[i+8] ^ authKey[i]` |
| 4 | → | `05` ‖ AES_ECB(authKey, remoteRandom) ‖ AES_ECB(sessionKey, remoteRandom) — 33 B |
| 5 | ← | `10 05 <status>`: `01` success · `25` **wrong auth key** |

Distinguish "refused" (status) from "timed out" (no reply). Never log auth key,
session key, or frame payloads (length + 3-byte header only).

---

## 5. Daily totals (endpoint 0x0016) — the authoritative step count

Request `[0x03]`, **sent first** in every sync. Reply:

| off | size | field |
|---|---|---|
| 0–2 | 3 | `04 01 0c` |
| 3 | 4 | steps since strap-local midnight |
| 7 | 4 | distance, metres |
| 11 | 4 | calories (device's figure) |

Stamp with `readAt` (arrival time). This counter beats the per-minute sum: the
per-minute buffer freezes mid-day on June-2026 firmware (writes `0xFF`). It needs its
own durable table — in the old system a re-derive overwrote it and 142 of 143 days lost
it permanently. "No reply" ≠ "zero steps".

---

## 6. History fetch (chars 0004 control / 0005 data)

Gadgetbridge's `AbstractFetchOperation`, plaintext.

```
app → 0004  [0x01, type] ‖ time8(since)
dev → 0004  [0x10, 0x01, status, expected u32, year u16, mo, d, h, mi, s]
app → 0004  [0x02]                                  (skip if expected == 0)
dev → 0005  [counter u8, payload…] × n
dev → 0004  [0x10, 0x02, status]
app → 0004  [0x03, 0x09]     ← 0x09 = ACK and KEEP data on the strap. Never delete.
```

- **time8(since)** (local wall time): year u16, month, day, hour, minute, `0` (sec),
  tz = `(utcOffsetMinutes / 15) & 0xFF`.
- Start reply: status `0x01` = ok; `expected == 0` → nothing to send (ack, finish).
  The reply's date is the **roundStart** that anchors round-relative types.
- **Integrity:** data packet counter must be `(prev+1) & 0xFF` from 0, AND the bytes
  received must equal what the start reply announced. **The announced count is 8-byte
  records for `0x01` and bytes for every other type** (measured on the strap 2026-09-25:
  22 rounds over 12 types, all exact). The count check catches a lost *final* packet,
  which the counter cannot (the old app missed this). On either: ack(keep), discard the
  round, retry same cursor, max 2 retries, then fail.
- **Paging:** after each round, next `since` = last decoded sample + 1 min, while that
  is > 30 s in the past and > current since; cap rounds (`maxRounds`, 400 normally).
- **0xFF gap-skip (types 0x01, 0x13):** a round that decodes to zero samples but has
  raw bytes → advance `since` by `rawLen / recordSize` minutes (clamp 1–1440); record
  size 8 (0x01; 4 if len%8≠0) or 1 (0x13). Without it the pager stalls forever.
- **Workouts (0x05)** page by latest decoded workout start + 1 min.
- **Probe** mode: send start, read `expected`, ack-abort (1.5 s timeout) — used to
  discover codes.
- Timeouts: 30 s per type (150 s for workouts). On failure, keep samples from completed
  rounds.

### Fetch plan (one sync, in order)

| # | step | type | window |
|---|---|---|---|
| 1 | daily totals | ep `0x0016` | — |
| 2–10 | metrics | `0x01 0x49 0x25 0x26 0x2E 0x13 0x38 0x3A 0x3D` | since last sample of that metric + 1 min, else 30 d back. If empty **and** watermark > 2 d old → retry from now − 2 d (stale-feed recovery) |
| 11 | stress backfill | `0x13` | one-shot, now − 25 d |
| 12 | sleep | `0x48` | min(lastSleepStart, now − 2 d); one-shot 14 d on first run (nap backfill). Always re-pulls 2 d so a nap appended later in the day is caught |
| 13 | workouts | `0x05` | lastWorkoutStart − 1 d, else 90 d back |

Dedup: sleep by session start, workouts by start (overlaps are intentional).
Watermark key per type: `0x2E → temperature_c`, `0x38 → respiratory_rate`, rest by label.

---

## 7. Record decoders

Two timestamp styles: **round-relative** (roundStart + 60 s per record) and
**absolute** (`sec` inside the record). `0xFF` is a sentinel ("allocated, never
written") — drop it everywhere; treating it as data inflates steps/calories and makes
HR out of holes.

| type | metric(s) | record | layout | keep if |
|---|---|---|---|---|
| `0x01` | `hr`, `steps` (per minute) | 8 B (4 if len%8≠0), round-relative | +2 steps u8 · +3 HR u8 | ≠0xFF and >0 |
| `0x13` | `stress` 0–100 | 1 B, round-relative | +0 value | ≠0xFF |
| `0x2E` | `temperature_c` (skin) | 8 B, round-relative | +2 int16 ×100 → °C | always (server floors at >25 °C) |
| `0x02` | `manual_hr` | 6 B | +0 sec · +5 value | ≠0xFF, >0 |
| `0x12` | `stress_manual` | 6 B | same | same |
| `0x3A` | `resting_hr` (device's) | 6 B | same | same |
| `0x3D` | `max_hr` (device's) | 6 B | same | same |
| `0x49` | `hrv` (ms) | 6 B | same | same |
| `0x38` | `respiratory_rate` (sleep) | 8 B | +0 sec · +5 br/min | ≠0xFF, >0 |
| `0x25` | `spo2` (spot) | first byte version must be `2`, then 65 B | +0 sec · +4 value (`v≥128 ? v−128 : v`) | — |
| `0x26` | `spo2_sleep` | version `2`, then 30 B | +0 sec · +4 % · +5 duration · +6 24 B undecoded | 1–100 |
| `0x48` | sleep sessions | 594 B | §7.1 | header valid |
| `0x05` | workout summaries | protobuf | §7.2 | start ≥ 1e9 |

⚠ The HRV value is treated downstream as nightly RMSSD. That is an assumption carried
from the old system, not something the byte format proves.

### 7.1 Sleep record (0x48, 594 B, fixed-size aligned)

| off | size | field |
|---|---|---|
| 0x000 | 4 | session sec |
| 0x004 | 4 | midnight sec |
| 0x008, 0x009 | 1+1 | markers, both must be `1` |
| 0x00a / 0x00c | 2 / 2 | night start / end minutes |
| 0x015 | 1 | avg HR |
| 0x016 | 1 | device sleep score |
| 0x018 | 6×n | nap descriptors `{start u16, end u16, dur u16}` up to 0x054; `start==0 && dur==0` ends |
| 0x054 | 1 | night stage count (cap 51) |
| 0x056 | 5×≤51 | night stages `{start u16, end u16, type u8}`; `{0,0}` terminates |
| 0x155 | 5×≤49 | nap stages, all naps on one timeline (skip `{0,0}`) |
| 0x24a/c/e, 0x250 | 2 each | night REM / light / deep / awake minutes |

- All stage minutes are offsets from **midnight − 24 h** (`base = midnightSec − 86400`).
- Stage types: `4` light · `5` deep · `8` REM · `7` awake · `0x80` gap marker (skip).
- **Validation:** both sec in [1.6e9, 2.0e9] and markers `1,1`, else **skip the slot and
  continue** (don't break — valid sessions follow empty ones).
- Naps: for each descriptor with `dur>0 && end>start`, take nap stages whose start ∈
  [ns, ne]; emit a nap session (avgHr 0, score 0, minutes summed from stages) only if
  it has stages.

### 7.2 Workout summary (0x05, protobuf, no .proto)

Stream holds several summaries; each starts with `0a 03 32 2e` (field 1, "2.") and is
preceded by a 2-byte header (`00 80`) → record n ends at `start[n+1] − 2`. Use a
lenient hand-rolled reader that skips unknown fields (wire types 0/1/2/5).

| field | contents |
|---|---|
| 1 | version string `"2.1"` |
| 2 | `{1 startTime sec, 3 sportType}` |
| 7 | `{1 durationSec}` |
| 16 | `{1 calories}` |
| 19 | `{1 avgHr, 2 maxHr, 3 minHr}` |
| 11 | `{1,2 floats}` distance/speed — unconfirmed |

Verified sample: 2026-05-01, 409 s, 53 kcal, avg HR 122. Summary max HR is smoothed
(140 vs app's 144 from the per-second track `0x06`, not decoded). Sport-code table not
mapped (legacy knew 10 climbing, 14 strength, 21 HIIT, 22 core).

---

## 8. Known-but-undecoded (future work)

Fetch codes with data found by probing 0x00–0x4f: `0x4e` (9 B `sec + 0x16 + u32`,
episodic — AFib/OSA/ODI candidate), `0x27` (version 2 + sec + float32[]), `0x3b`
(protobuf daily summary), `0x4a` (large sparse buffer, PPG-RR candidate), `0x06`
(per-second workout detail). Decoding path: our own authenticated session + APK parsers.

---

## 9. Data inventory — what one sync yields

Measured cadence (first real sync, 2026-09-25, last 24 h): HR every minute (99 %
coverage) · steps every minute that had steps · stress every 5 min · HRV every 1–2 min,
all day, not only at night · SpO₂ spot every 5 min · skin temp every minute (≥ 25.8 °C seen
off-wrist) · respiratory rate every minute during sleep · retention: 30 d+ for most
streams, ~3 d for respiratory rate, ~12 d for spot SpO₂. Daily counter 3949 vs per-minute
sum 3940 on the same day.


| stream | cadence | old system: sent to server? |
|---|---|---|
| hr | per minute | yes (`hr`) |
| steps | per minute (only minutes with steps) | yes (`steps_per_minute`) |
| stress | per minute | yes, but **no derivation uses it** |
| skin temp | per minute | yes (`skin_temp_c`) |
| hrv | sparse, overnight | yes |
| spo2 spot + sleep | sparse | yes, both → `spo2` |
| respiratory rate | overnight | yes |
| resting_hr / max_hr (device) | ~daily | **no — dropped**; server derives its own RHR from sleep HR |
| manual_hr / stress_manual | on demand | **no — dropped** |
| sleep sessions + naps + hypnogram | per night | yes |
| workout summaries | per session | yes |
| daily totals | live counter | yes (own table) |
| battery % | per connect | no (UI only) |

GPS: the strap has none. The old app recorded phone GPS for VO₂max; that recorder was
removed and only legacy tracks remain.

---

## 10. Operational gotchas (from the old repo's bug history)

- One BLE owner at a time: the UI isolate and background task must share a lease or
  they fight over the connection (bugs B6–B8).
- Timestamps: round-relative types are in strap local time via roundStart; absolute
  types are true epoch. Store UTC; derive local days with the owner's IANA zone.
- A stream can go quiet for weeks and resume (stress, June 2026) → watermark logic
  above. Keep the two one-shot flags (`stressBackfillDone`, `napBackfillDone`) or drop
  them deliberately.
- Always ack with `0x09` (keep) so any window can be re-fetched after a parser fix.
