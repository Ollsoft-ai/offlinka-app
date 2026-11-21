#!/usr/bin/env bash
# Build offlinka-core for iOS and package it as an XCFramework (run on macOS with Xcode).
#
#   scripts/build-ios-core.sh
#
# Steps: static libraries for device (aarch64-apple-ios) and simulator (aarch64 + x86_64,
# merged with lipo), Swift bindings + C header + module map via scripts/gen-bindings.sh,
# then xcodebuild -create-xcframework.
# Output: ios/Frameworks/OfflinkaCore.xcframework (git-ignored),
#         ios/Offlinka/Generated/offlinka_core.swift (compiled into the app target).
# Not run on the Linux box: written for macOS, keep it simple.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ "$(uname -s)" = Darwin ] || { echo "build-ios-core.sh runs on macOS (Xcode) only" >&2; exit 1; }
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios >/dev/null
export IPHONEOS_DEPLOYMENT_TARGET=17.0
cd "$ROOT/core"
for T in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
  cargo rustc --release --lib -p offlinka-core --target "$T" --crate-type staticlib
done
SIM="$ROOT/core/target/ios-sim/libofflinka_core.a"
mkdir -p "$(dirname "$SIM")"
lipo -create \
  "$ROOT/core/target/aarch64-apple-ios-sim/release/libofflinka_core.a" \
  "$ROOT/core/target/x86_64-apple-ios/release/libofflinka_core.a" \
  -output "$SIM"

# Swift bindings (also writes the header and module map we package below).
OFFLINKA_NO_LOCK=1 "$ROOT/scripts/gen-bindings.sh"
GEN="$ROOT/ios/Offlinka/Generated"
HDR="$ROOT/core/target/ios-headers"
rm -rf "$HDR" && mkdir -p "$HDR"
cp "$GEN/offlinka_coreFFI.h" "$HDR/"
# Xcode wants the module map named module.modulemap inside the headers folder.
cp "$GEN/offlinka_coreFFI.modulemap" "$HDR/module.modulemap"

XCF="$ROOT/ios/Frameworks/OfflinkaCore.xcframework"
rm -rf "$XCF" && mkdir -p "$(dirname "$XCF")"
xcodebuild -create-xcframework \
  -library "$ROOT/core/target/aarch64-apple-ios/release/libofflinka_core.a" -headers "$HDR" \
  -library "$SIM" -headers "$HDR" \
  -output "$XCF"
echo "built $XCF"
