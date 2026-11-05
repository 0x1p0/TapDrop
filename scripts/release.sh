#!/bin/zsh
# Builds the public downloads into release/ (GitHub Actions runs the same steps; see .github/workflows/release.yml):
#   TapDrop-<version>-mac.zip       the Mac app, with the Android app inside for the setup page
#   TapDrop-<version>-android.apk   the Android app
#   SHA256SUMS.txt                  checksums the in-app updaters verify
#   NOTES.md                        this version's notes from CHANGELOG.md
# The version comes from VERSION. The Mac app is signed with the release identity ("TapDrop Release Signing", which
# names no one), so it can update itself and keeps its permissions; the Android app with the release key in
# android/keystore.properties, so updates install over earlier versions.
#
# usage: scripts/release.sh
#   TAPDROP_MAC_P12 / TAPDROP_MAC_P12_PASSWORD: the release identity (default: ~/.tapdrop-signing/)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
V=$(tr -d '[:space:]' < VERSION)

if [[ ! -f android/keystore.properties ]]; then
  echo "android/keystore.properties is missing: public APKs must be signed with the release key (see README)."
  exit 1
fi
scripts/release-notes.sh "$V" > /dev/null  # fails if CHANGELOG.md has no notes for this version

# Android needs JDK 17.
if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"
fi
(cd android && ./gradlew :app:assembleRelease -q --console=plain)

# The Mac release identity, from a throwaway keychain.
P12="${TAPDROP_MAC_P12:-$HOME/.tapdrop-signing/tapdrop-mac-signing.p12}"
P12_PW="${TAPDROP_MAC_P12_PASSWORD:-$(cat "$HOME/.tapdrop-signing/p12-password.txt" 2>/dev/null || true)}"
if [[ ! -f "$P12" || -z "$P12_PW" ]]; then
  echo "The Mac release identity is missing ($P12). Without it the app can't update itself."
  exit 1
fi
KC="$(mktemp -d)/release.keychain-db"
KC_PW=$(uuidgen)
security create-keychain -p "$KC_PW" "$KC"
trap 'security delete-keychain "$KC" 2>/dev/null || true' EXIT
security unlock-keychain -p "$KC_PW" "$KC"
security import "$P12" -k "$KC" -P "$P12_PW" -T /usr/bin/codesign > /dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PW" "$KC" > /dev/null
TAPDROP_SIGN_ID="TapDrop Release Signing" TAPDROP_SIGN_KEYCHAIN="$KC" mac/scripts/build-app.sh --no-install

# Signed like every release before them, or installed copies can't update to these.
MAC_REQ='identifier "app.tapdrop.mac" and certificate root = H"a21607d15f9769c0770406f27c1b5c79e69c8288"'
APK_CERT=47f48d3374bd2ea3082d04d0ffaf6c9f3616ee8bc007feadd736225a93bac90c
codesign --verify --deep --strict mac/build/TapDrop.app
if ! codesign -dr - mac/build/TapDrop.app 2>&1 | grep -qF "$MAC_REQ"; then
  echo "The Mac app isn't signed with the release identity (TapDrop Release Signing)."
  exit 1
fi
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' android/local.properties 2>/dev/null)}"
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | tail -1)
if ! "$APKSIGNER" verify --print-certs android/app/build/outputs/apk/release/app-release.apk | grep -q "certificate SHA-256 digest: $APK_CERT"; then
  echo "The Android app isn't signed with the release key."
  exit 1
fi

OUT=release
rm -rf "$OUT"
mkdir -p "$OUT"
ditto -c -k --sequesterRsrc --keepParent mac/build/TapDrop.app "$OUT/TapDrop-$V-mac.zip"
cp android/app/build/outputs/apk/release/app-release.apk "$OUT/TapDrop-$V-android.apk"
(cd "$OUT" && shasum -a 256 TapDrop-* > SHA256SUMS.txt)
scripts/release-notes.sh "$V" > "$OUT/NOTES.md"

echo
ls -lh "$OUT"
echo
echo "Publish by pushing the tag (GitHub Actions builds and publishes it):  git tag v$V && git push origin v$V"
echo "Or publish these files yourself:  gh release create v$V $OUT/TapDrop-* $OUT/SHA256SUMS.txt --title \"TapDrop $V\" --notes-file $OUT/NOTES.md"
