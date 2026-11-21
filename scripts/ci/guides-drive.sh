#!/usr/bin/env bash
# CI stand-in for the drive: a folder with Knihovna/ and Telefon/ holding only the small guides ZIM that is
# bundled with the app (android/app/src/main/assets). Tests that need the big books skip themselves.
#
#   scripts/ci/guides-drive.sh <dir>     # prints <dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIR="${1:?usage: guides-drive.sh <dir>}"
ZIM="$ROOT/android/app/src/main/assets/offlinka_cs_prirucky.zim"
mkdir -p "$DIR/Knihovna" "$DIR/Telefon"
cp "$ZIM" "$DIR/Knihovna/"
cp "$ZIM" "$DIR/Telefon/"
echo "$DIR"
