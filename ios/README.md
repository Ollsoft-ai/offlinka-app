# Offlinka for iOS

SwiftUI app (iOS 17+, iPhone 15+ and USB-C iPads) that reads the Offlinka flash drive in place through the Rust core. Spec: `docs/SPEC.md`. Written on Linux without Xcode: **read `UNVERIFIED.md` before the first build**.

## Layout

- `project.yml`: XcodeGen spec (targets `Offlinka`, `OfflinkaTests`, `OfflinkaUITests`). The `.xcodeproj` is generated and git-ignored.
- `Offlinka/App`: `OfflinkaApp` (root, tabs, routes), `AppModel` (drive state machine, reader engine), `Router`.
- `Offlinka/Core`: `ContentSource` protocol + value types, `CoreContentSource` (the only file that touches the UniFFI bindings), `FakeContentSource` (previews, demo drive, tests).
- `Offlinka/Drive`: `DriveScanner` (finds `Knihovna/*.zim`, `Telefon/*.zim`, `.offidx`; best edition per book), `DriveAccess` (security-scoped bookmark, scope lifetime, reachability).
- `Offlinka/Reader`: `OfflinkaURL` (`offlinka://<bookId>/<path>`), `SchemeResponder` (pure: MIME, ranges, ZIM redirects, error pages, reader CSS hook), `OfflinkaSchemeHandler` (WKURLSchemeHandler), `ReaderEngine` (configuration, offline content rules), `ReaderModel` + `ReaderScreen`.
- `Offlinka/Search`, `Offlinka/Screens`, `Offlinka/Library`, `Offlinka/Persistence` (JSON `UserStore`), `Offlinka/Localization` (`L10n`, generated `Str.swift`), `Offlinka/Design` (components; colours/type come from `design/out/ios/Brand.swift`).
- `Offlinka/Generated/`: UniFFI Swift bindings, written by `scripts/gen-bindings.sh`.
- Used straight from `design/`: `Brand.swift`, `Localizable.xcstrings`, `fonts/*.ttf`, `Icons.xcassets`, `reader/offlinka-reader.css` + `reader/inject.js`. The app icon is copied by `tools/sync-design.sh` (an icon set must sit inside an asset catalog).
- `tools/gen-str.py`: regenerates `Str.swift` from `design/strings/cs.json` after design adds a key.

## Build on macOS (first time)

1. Xcode 15.4 or newer (Swift 5.10+), command line tools selected: `sudo xcode-select -s /Applications/Xcode.app`.
2. `brew install xcodegen` (and `brew install fastlane` for the lanes).
3. Rust: `curl https://sh.rustup.rs -sSf | sh`, then `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`.
4. From the repo root: `scripts/build-ios-core.sh`. It builds the static libraries, regenerates `ios/Offlinka/Generated/` and writes `ios/Frameworks/OfflinkaCore.xcframework`.
5. `ios/tools/sync-design.sh` (app icon, `Str.swift`; harmless to re-run).
6. `cd ios && xcodegen` → `Offlinka.xcodeproj`.
7. Build for the simulator: `xcodebuild -project Offlinka.xcodeproj -scheme Offlinka -destination 'platform=iOS Simulator,name=iPhone 15' build`.
8. Unit + UI tests: `xcodebuild test -project Offlinka.xcodeproj -scheme Offlinka -destination 'platform=iOS Simulator,name=iPhone 15'`. For the real-core tests copy `offlinka_cs_prirucky.zim` and `offlinka_mul_hry.zim` (from `/srv/offlinka/drive/release/<edition>/files/Knihovna/`) into `ios/OfflinkaTests/Fixtures/` and run `xcodegen` again; without them `CoreContentSourceTests` are skipped.
9. Run the demo drive without hardware: scheme Run arguments `-demo-drive` (optional `-demo-language sk`). Debug builds only.
10. Device: set your team (`OFFLINKA_TEAM_ID=XXXXXXXXXX xcodegen`, or in Xcode Signing), plug the drive into the iPhone, run, pick `OFFLINKA` in the Files sheet. Then work through `UI-SMOKE-TESTS.md`.

## Release

- Before the first upload, check the required-reason APIs actually linked: `nm -u build/…/Offlinka.app/Offlinka | grep -E 'mach_absolute_time|_stat|fstat|statfs|getattrlist'` and compare with `Offlinka/Resources/PrivacyInfo.xcprivacy` (today: FileTimestamp 3B52.1 only).
- Archive: `xcodebuild archive -project Offlinka.xcodeproj -scheme Offlinka -destination 'generic/platform=iOS' -archivePath build/Offlinka.xcarchive`, or `fastlane beta` (TestFlight) / `fastlane release` (upload + metadata from `fastlane/metadata`, no auto-submit). Credentials only from the environment, see `fastlane/Appfile`.
- Store texts in `fastlane/metadata/{cs,sk,en-US}` are a first draft; `store/` is the shared source for listings.

## Checked on Linux (Swift 6.4.3 toolchain, `~/opt/swift-6.4.3`)

- Type-check of all Foundation-only files in Swift 5 and Swift 6 language modes; syntax parse of every file.
- Unit tests of scanner, URL scheme, responder, search model, store, catalog, plus `CoreContentSourceTests` against the real core (Linux build of `libofflinka_core.so` + generated bindings) on the real guides and games ZIMs. The SwiftUI/UIKit/WebKit files were not compiled.
