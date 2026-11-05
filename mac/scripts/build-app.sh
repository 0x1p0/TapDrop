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
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

APK="../android/app/build/outputs/apk/release/app-release.apk"
if [[ -f "$APK" ]]; then
  cp "$APK" "$APP/Contents/Resources/TapDrop.apk"
  # Its version, for the install page (and to tell it from a newer one placed in TapDrop's folder).
  GRADLE=../android/app/build.gradle.kts
  VNAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE" | head -1)
  VCODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE" | head -1)
  printf '{"version":"%s","code":%s}\n' "$VNAME" "${VCODE:-0}" > "$APP/Contents/Resources/TapDrop.apk.json"
  echo "bundled APK ($(du -h "$APK" | cut -f1))"
else
  echo "warning: no APK found at $APK — the setup QR page will say so"
fi

# Sign with a stable identity when this Mac has one, so macOS keeps TapDrop's permissions (Local Network,
# Accessibility) across updates; an ad-hoc signature is a new app to macOS on every build.
SIGN_ID="${TAPDROP_SIGN_ID:-$(security find-identity -v -p codesigning 2>/dev/null | awk -F'"' '/Apple Development|Developer ID Application/ {print $2; exit}')}"
codesign --force --deep --sign "${SIGN_ID:--}" --identifier app.tapdrop.mac "$APP"
echo "signed with ${SIGN_ID:-an ad-hoc signature}"
echo "built $APP"

if [[ "${1:-}" != "--no-install" ]]; then
  pkill -x TapDrop 2>/dev/null || true
  sleep 0.5
  rm -rf /Applications/TapDrop.app
  cp -R "$APP" /Applications/TapDrop.app
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/TapDrop.app
  /System/Library/CoreServices/pbs -update 2>/dev/null || true
  open /Applications/TapDrop.app
  echo "installed and launched /Applications/TapDrop.app"
fi
