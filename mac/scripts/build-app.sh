#!/bin/zsh
# Builds TapDrop.app (release), bundles the Android APK if present, signs ad-hoc,
# and installs to /Applications.   usage: scripts/build-app.sh [--no-install]
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release
BIN="$(swift build -c release --show-bin-path)/TapDrop"

APP="build/TapDrop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/TapDrop"
cp Resources/Info.plist "$APP/Contents/Info.plist"
# One version for both apps, from VERSION at the repository root: "2.1.0" → build 20100.
VNAME=$(tr -d '[:space:]' < ../VERSION)
VCODE=$(echo "$VNAME" | awk -F. '{ print $1 * 10000 + $2 * 100 + $3 }')
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VNAME" -c "Set :CFBundleVersion $VCODE" "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
# The typefaces (Geist, Geist Mono, Instrument Serif; SIL OFL, licences beside them), registered at launch.
mkdir -p "$APP/Contents/Resources/Fonts"
cp Resources/Fonts/* "$APP/Contents/Resources/Fonts/"

APK="../android/app/build/outputs/apk/release/app-release.apk"
if [[ -f "$APK" ]]; then
  cp "$APK" "$APP/Contents/Resources/TapDrop.apk"
  # Its version, for the install page (and to tell it from a newer one placed in TapDrop's folder).
  printf '{"version":"%s","code":%s}\n' "$VNAME" "${VCODE:-0}" > "$APP/Contents/Resources/TapDrop.apk.json"
  echo "bundled APK ($(du -h "$APK" | cut -f1))"
else
  echo "warning: no APK found at $APK — the setup QR page will say so"
fi

# Sign with a stable identity when this Mac has one, so macOS keeps TapDrop's permissions (Local Network,
# Accessibility) across updates; an ad-hoc signature is a new app to macOS on every build.
# Releases use TAPDROP_SIGN_ID="TapDrop Release Signing" from TAPDROP_SIGN_KEYCHAIN (see scripts/release.sh).
SIGN_ID="${TAPDROP_SIGN_ID:-$(security find-identity -v -p codesigning 2>/dev/null | awk -F'"' '/Apple Development|Developer ID Application/ {print $2; exit}')}"
KEYCHAIN=()
[[ -n "${TAPDROP_SIGN_KEYCHAIN:-}" ]] && KEYCHAIN=(--keychain "$TAPDROP_SIGN_KEYCHAIN")
codesign --force --deep "${KEYCHAIN[@]}" --sign "${SIGN_ID:--}" --identifier app.tapdrop.mac "$APP"
echo "signed with ${SIGN_ID:-an ad-hoc signature}"
echo "built $APP ($VNAME, build $VCODE)"

if [[ "${1:-}" != "--no-install" ]]; then
  pkill -x TapDrop 2>/dev/null || true
  sleep 0.5
  # Update the installed copy in place (rather than deleting it), so macOS keeps treating it as the same app.
  mkdir -p /Applications/TapDrop.app
  rsync -a --delete "$APP/" /Applications/TapDrop.app/
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/TapDrop.app
  /System/Library/CoreServices/pbs -update 2>/dev/null || true
  open /Applications/TapDrop.app
  echo "installed and launched /Applications/TapDrop.app"
fi
