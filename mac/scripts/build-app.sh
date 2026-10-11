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
  echo "bundled APK ($(du -h "$APK" | cut -f1))"
else
  echo "warning: no APK found at $APK — the setup QR page will say so"
fi

codesign --force --deep --sign - --identifier app.tapdrop.mac "$APP"
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
