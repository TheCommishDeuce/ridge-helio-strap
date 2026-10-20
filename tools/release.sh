#!/usr/bin/env bash
# Builds the signed release APK on the owner's Mac or Linux machine (the key lives only on the
# owner's own machines, D24) and attaches it to a DRAFT GitHub release v<appVersion> on the
# public repo. Publishing the draft is a manual click on GitHub. Nothing here pushes code:
# sync the public repo first (tools/export-public.sh) so the tag lands on the matching commit.
#
#   tools/release.sh            version from android/app/build.gradle.kts (appVersion)
set -euo pipefail

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
public=${RIDGE_PUBLIC_REPO:-TheCommishDeuce/ridge-helio-strap}
cd "$repo"

version=$(sed -nE 's/^val appVersion = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p' android/app/build.gradle.kts)
[ -n "$version" ] || { echo "No appVersion in android/app/build.gradle.kts." >&2; exit 1; }
tag="v$version"
# Every release says what changed, in a committed file that ships with the code it describes.
whats_new="docs/releases/$version.md"
[ -f "$whats_new" ] && grep -q "^## What's new" "$whats_new" || {
    echo "Write $whats_new first: a \"## What's new\" section (see docs/releases/0.3.0.md), then commit it." >&2; exit 1; }
git ls-files --error-unmatch "$whats_new" >/dev/null 2>&1 || { echo "Commit $whats_new first." >&2; exit 1; }
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "Commit or stash your changes first." >&2; exit 1; }
[ -f "$HOME/.android/ridge-release.jks" ] || { echo "Release key missing: ~/.android/ridge-release.jks (D24)." >&2; exit 1; }
if gh release view "$tag" -R "$public" >/dev/null 2>&1; then echo "$tag already exists on $public." >&2; exit 1; fi

# Homebrew's JDK on the Mac; elsewhere the system Java unless JAVA_HOME says otherwise.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17; fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
(cd android && ./gradlew -q :strap-protocol:test :app:lintRelease :app:assembleRelease)

sdk=$(sed -nE 's/^sdk.dir=//p' android/local.properties)
apksigner=$(ls "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
apk=android/app/build/outputs/apk/release/app-release.apk
"$apksigner" verify "$apk" || { echo "The APK is not signed; is the key in place?" >&2; exit 1; }

out="$repo/dist"
mkdir -p "$out"
name="ridge-$version.apk"
cp "$apk" "$out/$name"
(cd "$out" && shasum -a 256 "$name" > "$name.sha256")
cert=$("$apksigner" verify --print-certs "$apk" | sed -nE 's/.*certificate SHA-256 digest: //p' | head -1)

notes="$out/notes-$version.md"
{ printf 'Ridge %s for Android 12+ (Amazfit Helio Strap).\n\n' "$version"; cat "$whats_new"; printf '\n'; } > "$notes"
cat >> "$notes" <<EOF
Install \`$name\` (allow installs from your browser or file manager). Updates install over
the previous version. Pairing needs your strap's auth key (see tools/keyfetch); the server
is optional to sync but needed for the daily numbers (see deploy/README.md).

- APK SHA-256: \`$(cut -d' ' -f1 "$out/$name.sha256")\`
- Signing certificate SHA-256: \`$cert\` (the same for every release)
EOF

gh release create "$tag" "$out/$name" "$out/$name.sha256" -R "$public" --draft \
    --title "Ridge $version" --notes-file "$notes"
echo "Draft $tag created on $public. Review it and press Publish on GitHub."
