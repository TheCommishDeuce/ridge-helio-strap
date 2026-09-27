# Contributing

Issues and pull requests are welcome. Ridge is maintained in spare time for one strap
model, so please keep changes focused.

## Reporting a problem

Include the app version (Android settings → Apps → Ridge), the strap's firmware version
(Zepp app → the strap → About), and the "Last sync" block from the app's gear screen.
**Remove your strap's MAC address first**, and never paste the auth key or the server
token anywhere.

## Building and testing

**App and protocol** (JDK 17; Android SDK with platform 37):

```sh
cd android
./gradlew :strap-protocol:test            # the protocol, with no phone needed
./gradlew :app:lintRelease :app:assembleRelease
```

Without the release key the release APK comes out unsigned. For your own phone, build
`:app:assembleDebug` (slower UI) or sign with your own key.

**Server** ([uv](https://docs.astral.sh/uv/) and Docker):

```sh
docker run -d --name strap-test-db -p 127.0.0.1:5598:5432 \
  -e POSTGRES_DB=strap -e POSTGRES_USER=strap -e POSTGRES_PASSWORD=local-test-only \
  timescale/timescaledb:latest-pg17
cd server
uv sync
uv run ruff check
uv run pytest -q          # database tests skip when the test database is not running
```

CI runs all of the above on every push.

**UI work without a strap** (and the README screenshots): a demo server with five weeks of
synthetic data, and a demo build of the app that installs beside the real one.

```sh
tools/demo-data/load.sh                     # starts it on 127.0.0.1:8767, prints a token
cd android && ./gradlew :app:assembleDemo
adb reverse tcp:8767 tcp:8767 && adb install -r app/build/outputs/apk/demo/app-demo.apk
# In "Ridge demo": gear → server http://127.0.0.1:8767 and the printed token.
tools/demo-data/load.sh down                # remove it afterwards
```

Screenshots for the repo come only from this demo, never from real data.

## Changing things

- **Strap protocol.** Write the layout into `docs/spec/01-strap-protocol.md` first. Probe
  new commands **read-only** on real hardware before any write, and read back every
  write. Add the real bytes as test vectors, with personal values (times, goals, MACs)
  replaced by neutral ones.
- **Science.** A formula change is a decision: record it in `docs/DECISIONS.md`, update
  `docs/spec/02-science.md`, and add known-value tests. The golden fixture
  (`server/tests/fixtures/derive/expected_daily.json`) must keep passing unless the change
  deliberately re-baselines it, and the pull request should say so.
- **No invented numbers.** When an input is missing, withhold the metric with a named
  reason rather than showing a default.
- **Gadgetbridge is a reference, not a source.** Reading its code to learn the protocol is
  fine; copying code from it (or any other project) is not (D8).
- **Style.** Match the code around yours: naming, comment density, idioms. Kotlin lint and
  `ruff` must stay clean.

## Privacy

Never commit auth keys, tokens, passwords, `.env` files, real MAC addresses, real health
data, or screenshots that show real data. Test data is synthetic, or real bytes with the
personal values replaced.

## Licence

By contributing you agree that your contribution is licensed under the AGPL-3.0-or-later,
like the rest of the project.
