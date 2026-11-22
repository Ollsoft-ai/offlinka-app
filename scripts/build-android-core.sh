#!/usr/bin/env bash
# Cross-compile offlinka-core for Android with cargo-ndk into the app's jniLibs.
#
#   scripts/build-android-core.sh              # release (LTO, opt-level 3, stripped)
#   PROFILE=release-s scripts/build-android-core.sh   # size-optimised variant
#   OUT=/some/dir scripts/build-android-core.sh       # other output folder
#
# Output: android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libofflinka_core.so
# Needs: ANDROID_NDK_HOME, cargo-ndk, rustup targets aarch64-linux-android,
# armv7-linux-androideabi, x86_64-linux-android (all in ~/opt/offlinka-env.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$HOME/opt/offlinka-env.sh" ] && source "$HOME/opt/offlinka-env.sh"
PROFILE="${PROFILE:-release}"
source "$ROOT/scripts/lib-lock.sh"
OUT="${OUT:-$ROOT/android/app/src/main/jniLibs}"
mkdir -p "$OUT"
cd "$ROOT/core"
# No build-machine paths in the shipped library (panic messages name source files): map them to neutral ones.
export RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$HOME/.cargo/registry/src=/cargo --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup --remap-path-prefix=$ROOT=/offlinka"
# minSdk 26 (Android 8)
run cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o "$OUT" \
  build --profile "$PROFILE" --lib -p offlinka-core -j 4
ls -l "$OUT"/*/libofflinka_core.so
