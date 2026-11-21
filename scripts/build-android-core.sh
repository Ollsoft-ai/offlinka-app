#!/usr/bin/env bash
# Cross-compile offlinka-core for Android with cargo-ndk into the app's jniLibs.
#
#   scripts/build-android-core.sh              # release (LTO, opt-level 3, stripped)
#   PROFILE=release-s scripts/build-android-core.sh   # size-optimised variant
#
# Output: android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libofflinka_core.so
# Needs: ANDROID_NDK_HOME, cargo-ndk, rustup targets aarch64-linux-android,
# armv7-linux-androideabi, x86_64-linux-android (all in ~/opt/offlinka-env.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$HOME/opt/offlinka-env.sh" ] && source "$HOME/opt/offlinka-env.sh"
PROFILE="${PROFILE:-release}"
LOCK=/tmp/claude-1000/offlinka-app-build.lock
run() {
  if [ -n "${OFFLINKA_NO_LOCK:-}" ] || ! command -v flock >/dev/null; then "$@"
  else mkdir -p "$(dirname "$LOCK")"; flock "$LOCK" nice -n 10 timeout 3600 "$@"; fi
}
OUT="$ROOT/android/app/src/main/jniLibs"
mkdir -p "$OUT"
cd "$ROOT/core"
# minSdk 26 (Android 8)
run cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o "$OUT" \
  build --profile "$PROFILE" --lib -p offlinka-core -j 4
ls -l "$OUT"/*/libofflinka_core.so
