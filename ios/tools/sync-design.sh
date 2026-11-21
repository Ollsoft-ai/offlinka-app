#!/usr/bin/env bash
# Copies the design outputs the iOS project cannot reference in place into ios/.
#   ios/tools/sync-design.sh
# Everything else (Brand.swift, Localizable.xcstrings, fonts, Icons.xcassets, reader CSS/JS) is
# referenced straight from design/ by project.yml. Re-run after design changes the app icon or strings.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/design/out/ios/AppIcon.appiconset"
DST="$ROOT/ios/Offlinka/Resources/Assets.xcassets/AppIcon.appiconset"
# An app icon set must live inside an asset catalog, so it is copied (design ships it bare).
rm -rf "$DST" && mkdir -p "$DST"
cp "$SRC"/* "$DST"/
echo "app icon: $(ls "$DST" | tr '\n' ' ')"
python3 "$ROOT/ios/tools/gen-str.py"
