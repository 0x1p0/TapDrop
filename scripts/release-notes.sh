#!/bin/sh
# Prints the notes for one version from CHANGELOG.md (everything under "## <version>" up to the next "## ").
# usage: scripts/release-notes.sh 2.1.0
set -eu
V="${1:?usage: release-notes.sh <version>}"
cd "$(dirname "$0")/.."
NOTES=$(awk -v v="$V" '
  /^## / { if (on) exit; on = ($2 == v); next }
  on { print }
' CHANGELOG.md | sed -e '/./,$!d')
if [ -z "$NOTES" ]; then
  echo "CHANGELOG.md has no notes for $V" >&2
  exit 1
fi
printf '%s\n' "$NOTES"
