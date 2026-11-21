# Offlinka for iOS

SwiftUI app (iOS 17+, iPhone 15+ and USB-C iPads) that reads the Offlinka flash drive in place through the Rust core. Spec: `docs/SPEC.md`. Written on Linux; built and tested on Xcode 26 by CI (below). What still needs a device: `UNVERIFIED.md`.

## Layout

- `project.yml`: XcodeGen spec (targets `Offlinka`, `OfflinkaTests`, `OfflinkaUITests`). The `.xcodeproj` is generated and git-ignored.
- `Offlinka/App`: `OfflinkaApp` (root, tabs, routes), `AppModel` (drive phases, picking, reader engine, background time, memory), `Router`.
- `Offlinka/Core`: `ContentEngine`/`ContentBook`/`ContentLibrary` (the core, book by book), `CoreContentEngine` (the only file that touches the UniFFI bindings), `FakeContentEngine` + `FakeContentSource` (demo drive, previews, tests), `ContentSource` (what reader and search read).
- `Offlinka/Drive`: `DriveScanner` (finds `Knihovna/*.zim`, `Telefon/*.zim`, `.offidx`; best edition per book), `DriveAccess` (security-scoped bookmark), `Shelf` (opens the UI-language Wikipedia first and the rest 3 at a time, failed tiles with retry, publishes before closing old books, builds missing search indexes in Application Support, bundled guides), `ShelfContent` (the open books, thread-safe).
- `Offlinka/Reader`: `OfflinkaURL` (`offlinka://<bookId>/<path>`), `SchemeResponder` (pure: MIME, ranges, ZIM redirects, error pages, reader CSS hook), `OfflinkaSchemeHandler` (WKURLSchemeHandler), `ReaderEngine` (configuration, offline content rules), `ReaderModel` + `ReaderScreen`.
- `Offlinka/Search`, `Offlinka/Screens`, `Offlinka/Library`, `Offlinka/Persistence` (JSON `UserStore`), `Offlinka/Localization` (`L10n`, generated `Str.swift`), `Offlinka/Design` (components; colours/type come from `design/out/ios/Brand.swift`).
- `Offlinka/Generated/`: UniFFI Swift bindings, committed by the core and rewritten by `scripts/build-ios-core.sh` / `scripts/gen-bindings.sh`.
- Bundled guides: `android/app/src/main/assets/offlinka_cs_prirucky.zim`, the one file both apps ship (first aid works without the drive), referenced from `project.yml`.
- Used straight from `design/`: `Brand.swift`, `Localizable.xcstrings`, `fonts/*.ttf`, `Icons.xcassets`, `reader/offlinka-reader.css` + `reader/inject.js`. The app icon is copied by `tools/sync-design.sh` (an icon set must sit inside an asset catalog).
- `tools/gen-str.py`: regenerates `Str.swift` from `design/strings/cs.json` after design adds a key. `tools/linux-check/`: type-check against SDK stand-ins + unit tests on Linux.
- `tools/pick-simulator.py`, `tools/sim-prepare.sh` (boot, 9:41 status bar), `tools/export-shots.py` (screenshots out of an `.xcresult`): used by CI, work on any Mac.

## Build on macOS (first time)

1. Xcode 26 (the App Store requires it; CI uses 26.6), command line tools selected: `sudo xcode-select -s /Applications/Xcode.app`.
2. `brew install xcodegen` (and `brew install fastlane` for the lanes).
3. Rust: `curl https://sh.rustup.rs -sSf | sh`, then `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`.
4. From the repo root: `scripts/build-ios-core.sh`. It builds the static libraries, regenerates `ios/Offlinka/Generated/` and writes `ios/Frameworks/OfflinkaCore.xcframework`.
5. `ios/tools/sync-design.sh` (app icon, `Str.swift`; harmless to re-run).
6. `cd ios && xcodegen` → `Offlinka.xcodeproj`.
7. Build for the simulator: `xcodebuild -project Offlinka.xcodeproj -scheme Offlinka -destination 'platform=iOS Simulator,name=iPhone 17' build`.
8. Unit + UI tests: `xcodebuild test -project Offlinka.xcodeproj -scheme Offlinka -destination 'platform=iOS Simulator,name=iPhone 17'`. Core tests: copy `android/app/src/main/assets/offlinka_cs_prirucky.zim` into `ios/OfflinkaTests/Fixtures/` and run `xcodegen` again (else skipped). UI tests on real books: `TEST_RUNNER_OFFLINKA_FIXTURE_DRIVE=<unzipped review sample>/OFFLINKA`.
9. Run without hardware (debug builds only): `-demo-drive` (fake books; optional `-demo-language sk`, `-demo-theme dark`), or `-fixture-drive <folder>` (real core; Connect opens `<folder>` with `Knihovna/*.zim` instead of the Files sheet; no folder = the bundled guides).
10. Device: set your team (`OFFLINKA_TEAM_ID=XXXXXXXXXX xcodegen`, or in Xcode Signing), plug the drive into the iPhone, run, pick `OFFLINKA` in the Files sheet. Then work through `UI-SMOKE-TESTS.md`.

## CI (GitHub Actions, `.github/workflows/ios.yml`)

Runs on `macos-26` with Xcode 26.6 for pushes to `main` and PRs touching `ios/`, `core/`, `design/` (a newer push cancels the run in progress). Built once: one `build-for-testing`, then every test job runs the same `.xctestrun` products.

- **Core XCFramework**: `scripts/build-ios-core.sh`, cached by the core sources (an iOS-only change skips Rust); warns when the committed bindings differ.
- **Review sample drive**: the public release asset `review-sample-2026-11` (1.5 GB: Czech Wikipedia mini + index, games, Slovak map), SHA-256 checked, cached once on Linux for the macOS jobs.
- **Build for testing**: Debug, arm64 simulator, DerivedData cached by the hash of `ios/**` + `design/**`; artifact `ios-test-products`.
- **Test (iPhone 17)**: unit tests, core tests on the guides ZIM, UI smoke on the demo drive and on the sample drive. Artifact `ios-test-results`: `.xcresult`, `screenshots/`, the app's debug log.
- **Unsigned Release archive**: `CODE_SIGNING_ALLOWED=NO`; checks the bundle, no debug launch argument, required-reason symbols.
- **By hand, after the tests are green** (`gh workflow run ios.yml -f store_shots=true -f demo_video=true`):
  - **App Store screenshots**: `StoreScreenshotTests` on the sample drive in cs, sk, en on iPhone 17 Pro Max (6.9"), 17 Pro (6.3"), iPad Pro 13" (M5). Artifact `ios-store-shots/<iphone-69|iphone-63|ipad-13>/` (`-f shot_devices=iphone-69` captures one set again); frame them with `SNAPSHOTS=<folder> SNAPSHOT_PAGES=app store/screenshots/build.sh <ios-69|ios-63|ipad-13>`.
  - **App Review demo video**: `simctl io recordVideo` while `testDemoWalkthrough` walks drive → Home → search „cesky krumlov“ → article → Back → map → games → first aid. Artifact `ios-demo-video/offlinka-demo-walk.mp4`.
- **Only some tests** while fixing: `gh workflow run ios.yml -f only_testing=OfflinkaUITests/FixtureDriveUITests` (comma-separated `-only-testing` targets; skips the archive).

## Release

- Before the first upload, check the required-reason APIs actually linked: `nm -u build/…/Offlinka.app/Offlinka | grep -E 'mach_absolute_time|_stat|fstat|statfs|getattrlist'` and compare with `Offlinka/Resources/PrivacyInfo.xcprivacy` (today: FileTimestamp 3B52.1 only).
- Archive: `xcodebuild archive -project Offlinka.xcodeproj -scheme Offlinka -destination 'generic/platform=iOS' -archivePath build/Offlinka.xcarchive`, or `fastlane beta` (TestFlight) / `fastlane release` (upload + metadata from `fastlane/metadata`, no auto-submit). Credentials only from the environment, see `fastlane/Appfile`.
- Store texts in `fastlane/metadata/{cs,sk,en-US}` are a first draft; `store/` is the shared source for listings.

## Checked on Linux (Swift 6.4.3 toolchain, `~/opt/swift-6.4.3`)

Re-run after changes: `flock /tmp/claude-1000/offlinka-app-build.lock nice -n 10 ios/tools/linux-check/check.sh` (needs `core/target/host/libofflinka_core.so` from `scripts/gen-bindings.sh`).

- Every file parses; the whole app + generated bindings + Brand.swift type-check in Swift 5 and 6 modes against hand-written stand-ins for SwiftUI/UIKit/WebKit (consistency only, not Apple's real signatures).
- Unit tests of scanner, URL scheme, responder, search model, store, catalog, and `CoreContentSourceTests` against the core built from `main` (Linux `libofflinka_core.so` + the committed bindings) on the real guides and games ZIMs.
- `project.yml` generates with XcodeGen 2.42.0 (built on Linux); build phases and Info.plist checked.
- Real SDK builds, simulator tests and what is still unverified on a device: CI above and `UNVERIFIED.md`.
