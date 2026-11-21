#!/usr/bin/env bash
# Build the .offidx search index for every ZIM of a drive edition.
#
#   scripts/build-drive-indexes.sh <files-dir> [out-dir]
#
# <files-dir> is the drive root with Knihovna/ and Telefon/ (e.g.
# /srv/offlinka/drive/release/2026-11.3/files). Each <name>.zim gets <name>.offidx in
# the same relative folder under [out-dir] (default: next to the ZIM, i.e. on the drive
# build itself). Up-to-date indexes are skipped (FORCE=1 rebuilds). Needs offlinka-cli:
# built here with the release profile under the shared build lock.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?usage: build-drive-indexes.sh <files-dir> [out-dir]}"
OUT="${2:-$SRC}"
[ -f "$HOME/opt/offlinka-env.sh" ] && source "$HOME/opt/offlinka-env.sh"
LOCK=/tmp/claude-1000/offlinka-app-build.lock
CLI="$ROOT/core/target/release/offlinka-cli"
if [ ! -x "$CLI" ] || [ -n "${REBUILD:-}" ]; then
  (cd "$ROOT/core" && flock "$LOCK" nice -n 10 timeout 3600 cargo build -j 4 --release -p offlinka-core --bin offlinka-cli)
fi
cd "$SRC"
find . -name '*.zim' -print0 | sort -z | while IFS= read -r -d '' z; do
  rel="${z#./}"
  out="$OUT/${rel%.zim}.offidx"
  mkdir -p "$(dirname "$out")"
  if [ -z "${FORCE:-}" ] && [ -f "$out" ] && [ "$out" -nt "$z" ]; then
    echo "up to date: $out"; continue
  fi
  echo "== $rel"
  nice -n 10 "$CLI" index "$SRC/$rel" -o "$out" | tail -1
  ls -l "$out"
done
