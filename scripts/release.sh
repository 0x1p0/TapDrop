#!/bin/zsh
# Builds the public downloads into release/:
#   TapDrop-<version>-mac.zip       the Mac app (with the Android app inside, for the setup page)
#   TapDrop-<version>-android.apk   the Android app
#   SHA256SUMS.txt
# The Mac app is signed ad-hoc, so the download names no one (an Apple Development signature carries its owner's
# Apple ID). The Android app is signed with the release key in android/keystore.properties, so updates install
# over earlier versions.
#
# usage: scripts/release.sh            then:  gh release create v<version> release/* --title "TapDrop <version>"
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f android/keystore.properties ]]; then
  echo "android/keystore.properties is missing: public APKs must be signed with your release key (see README)."
  exit 1
fi

MAC_V=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' mac/Resources/Info.plist)
AND_V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts | head -1)
if [[ "$MAC_V" != "$AND_V" ]]; then
  echo "The Mac app is $MAC_V and the Android app is $AND_V. Give them the same version for a release."
  exit 1
fi

# Android needs JDK 17.
if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)"
fi
(cd android && ./gradlew :app:assembleRelease -q --console=plain)
TAPDROP_SIGN_ID=- mac/scripts/build-app.sh --no-install

OUT=release
rm -rf "$OUT"
mkdir -p "$OUT"
ditto -c -k --sequesterRsrc --keepParent mac/build/TapDrop.app "$OUT/TapDrop-$MAC_V-mac.zip"
cp android/app/build/outputs/apk/release/app-release.apk "$OUT/TapDrop-$AND_V-android.apk"
(cd "$OUT" && shasum -a 256 TapDrop-* > SHA256SUMS.txt)

echo
ls -lh "$OUT"
echo
echo "Publish with:"
echo "  gh release create v$MAC_V $OUT/* --title \"TapDrop $MAC_V\" --notes-file <notes.md>"
