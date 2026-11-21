#!/usr/bin/env bash
# Linux checks of the iOS app without Xcode (there is no Mac on Company OS):
#   1. type-check every app file + generated bindings + design's Brand.swift in Swift 5 and 6 modes
#      against hand-written stand-ins for SwiftUI/UIKit/WebKit (stubs/: consistency only, they are
#      not Apple's SDK; extend them when the app uses a new API),
#   2. run the unit tests of the Foundation-only layer, and CoreContentSourceTests against the core
#      host library (scripts/gen-bindings.sh builds core/target/host/libofflinka_core.so).
# Needs a Swift toolchain for Linux (~/opt/swift-6.4.3 on Company OS). Heavy: run under the build lock.
#   flock /tmp/claude-1000/offlinka-app-build.lock nice -n 10 ios/tools/linux-check/check.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
export PATH="${SWIFT_BIN:-$HOME/opt/swift-6.4.3/usr/bin}:$PATH"
if [ -n "${OFFLINKA_IOS_CHECK_DIR:-}" ]; then
  WORK="$OFFLINKA_IOS_CHECK_DIR"
else
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
fi
mkdir -p "$WORK/mods" "$WORK/ffi"

# --- stub SDK modules
for m in Combine UIKit UniformTypeIdentifiers WebKit SwiftUI; do
  swiftc -swift-version 5 -parse-as-library -emit-module -module-name "$m" -I "$WORK/mods" \
    -emit-module-path "$WORK/mods/$m.swiftmodule" "$HERE/stubs/$m.swift"
done
cp "$ROOT/ios/Offlinka/Generated/offlinka_coreFFI.h" "$WORK/ffi/"
printf 'module offlinka_coreFFI {\n    header "offlinka_coreFFI.h"\n    link "offlinka_core"\n    export *\n}\n' > "$WORK/ffi/module.modulemap"

# --- type-check (previews need a macro plugin, so they are stripped from a copy)
python3 "$HERE/strip_previews.py" "$ROOT/ios/Offlinka" "$WORK/src"
status=0
for mode in 5 6; do
  out=$(swiftc -swift-version $mode -DDEBUG -typecheck -module-name Offlinka -I "$WORK/mods" -I "$WORK/ffi" \
        $(find "$WORK/src" -name '*.swift') "$ROOT/design/out/ios/Brand.swift" 2>&1 \
        | grep -E "error:" | grep -v "^ *|" | grep -v "ConformanceIsolation" | sort -u || true)
  if [ -n "$out" ]; then echo "type-check (Swift $mode) failed:"; echo "$out"; status=1; else echo "type-check (Swift $mode): ok"; fi
done

# --- unit tests (SwiftPM package of symlinks)
P="$WORK/pkg"; mkdir -p "$P/Sources/Offlinka" "$P/Sources/offlinka_coreFFI" "$P/Tests/OfflinkaTests"
cp "$WORK/ffi/"* "$P/Sources/offlinka_coreFFI/"
for f in Core/ContentSource.swift Core/ContentEngine.swift Core/FakeContentSource.swift Core/FakeContentEngine.swift \
         Core/CoreContentEngine.swift Drive/DriveScanner.swift Drive/Shelf.swift Drive/ShelfContent.swift \
         Reader/OfflinkaURL.swift Reader/ByteRange.swift Reader/SchemeResponder.swift Localization/L10n.swift \
         Localization/Str.swift Library/BookCatalog.swift Design/BrandIcon.swift Persistence/UserStore.swift \
         Search/SearchModel.swift Generated/OfflinkaCore.swift; do
  ln -sf "$ROOT/ios/Offlinka/$f" "$P/Sources/Offlinka/$(basename "$f")"
done
for t in "$ROOT"/ios/OfflinkaTests/*.swift; do ln -sf "$t" "$P/Tests/OfflinkaTests/"; done
LIB="$ROOT/core/target/host"
[ -f "$LIB/libofflinka_core.so" ] || { echo "no $LIB/libofflinka_core.so: run scripts/gen-bindings.sh first"; exit 1; }
cat > "$P/Package.swift" <<SWIFT
// swift-tools-version:5.10
import PackageDescription
let package = Package(name: "OfflinkaLinuxCheck", targets: [
    .systemLibrary(name: "offlinka_coreFFI"),
    .target(name: "Offlinka", dependencies: ["offlinka_coreFFI"], swiftSettings: [.unsafeFlags(["-swift-version", "5"])],
            linkerSettings: [.unsafeFlags(["-L$LIB", "-Xlinker", "-rpath", "-Xlinker", "$LIB"])]),
    .testTarget(name: "OfflinkaTests", dependencies: ["Offlinka"]),
])
SWIFT
# Small real ZIMs for CoreContentSourceTests (skipped without them).
FIX="${OFFLINKA_FIXTURES:-/srv/offlinka/drive/release/current/files/Knihovna}"
mkdir -p "$WORK/fixtures"
for z in offlinka_cs_prirucky.zim offlinka_mul_hry.zim; do [ -f "$FIX/$z" ] && ln -sf "$FIX/$z" "$WORK/fixtures/$z"; done
(cd "$P" && OFFLINKA_FIXTURES="$WORK/fixtures" swift test -j 4 2>&1 \
  | grep -E "error:|Test Suite '[A-Za-z]+Tests' (passed|failed)|Executed [0-9]+ tests" | tail -40) || status=1
exit $status
