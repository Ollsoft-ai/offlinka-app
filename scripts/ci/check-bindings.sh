#!/usr/bin/env bash
# Fails when the UniFFI bindings in git (Kotlin for Android, Swift + header + module map for iOS) differ
# from the ones generated from the current core. Stale bindings fail at load with
# "UniFFI API checksum mismatch", so they must be regenerated with every API or doc-comment change.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OFFLINKA_NO_LOCK=1 scripts/gen-bindings.sh
PATHS=(android/core/src/main/kotlin ios/Offlinka/Generated)
if ! git diff --exit-code --stat -- "${PATHS[@]}" || [ -n "$(git ls-files --others --exclude-standard -- "${PATHS[@]}")" ]; then
  git --no-pager diff -- "${PATHS[@]}" | head -200
  git ls-files --others --exclude-standard -- "${PATHS[@]}"
  echo "::error title=UniFFI bindings drift::The committed bindings do not match the core. Run scripts/gen-bindings.sh and commit android/core/src/main/kotlin and ios/Offlinka/Generated."
  exit 1
fi
echo "bindings match the core"
