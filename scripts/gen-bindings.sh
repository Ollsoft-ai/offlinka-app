#!/usr/bin/env bash
# Generate the UniFFI Kotlin + Swift bindings of offlinka-core (library mode) and the
# host library the Android JVM tests load through JNA.
#
#   scripts/gen-bindings.sh            # Kotlin + Swift + host lib
#   OFFLINKA_NO_LOCK=1 scripts/...     # skip the shared build lock (CI)
#
# Outputs:
#   android/core/src/main/kotlin/cz/offlinka/core/offlinka_core.kt   (package cz.offlinka.core)
#   ios/Offlinka/Generated/{offlinka_core.swift,offlinka_coreFFI.h,offlinka_coreFFI.modulemap}
#   core/target/host/<os>-<arch>/libofflinka_core.{so,dylib}           (JNA resource layout)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$HOME/opt/offlinka-env.sh" ] && source "$HOME/opt/offlinka-env.sh"
LOCK=/tmp/claude-1000/offlinka-app-build.lock
run() {
  if [ -n "${OFFLINKA_NO_LOCK:-}" ] || ! command -v flock >/dev/null; then "$@"
  else mkdir -p "$(dirname "$LOCK")"; flock "$LOCK" nice -n 10 timeout 3600 "$@"; fi
}
cd "$ROOT/core"
run cargo build -j 4 --profile host --lib -p offlinka-core
run cargo build -j 4 --profile host -p uniffi-bindgen
case "$(uname -s)" in
  Darwin) EXT=dylib; OS=darwin ;;
  *) EXT=so; OS=linux ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=x86-64 ;;
  arm64|aarch64) ARCH=aarch64 ;;
  *) ARCH="$(uname -m)" ;;
esac
LIB="$ROOT/core/target/host/libofflinka_core.$EXT"
BINDGEN="$ROOT/core/target/host/uniffi-bindgen"

KT_OUT="$ROOT/android/core/src/main/kotlin"
SW_OUT="$ROOT/ios/Offlinka/Generated"
mkdir -p "$KT_OUT" "$SW_OUT"
"$BINDGEN" generate --library "$LIB" --language kotlin --out-dir "$KT_OUT" --no-format
"$BINDGEN" generate --library "$LIB" --language swift --out-dir "$SW_OUT" --no-format

# JNA looks for <os>-<arch>/lib<name>.<ext> on the classpath, or in jna.library.path.
HOST_DIR="$ROOT/core/target/host/$OS-$ARCH"
mkdir -p "$HOST_DIR"
cp "$LIB" "$HOST_DIR/"
echo "kotlin: $KT_OUT/cz/offlinka/core/offlinka_core.kt"
echo "swift:  $SW_OUT"
echo "host:   $HOST_DIR/libofflinka_core.$EXT  (JNA: -Djna.library.path=$HOST_DIR)"
