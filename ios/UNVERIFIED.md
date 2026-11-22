# iOS: what is verified and what is not (0.2.0, build 2)

## Verified on real Xcode (GitHub Actions, `macos-26`, Xcode 26.6, iOS 26.5 simulator)

- **Compiles** with the real SDK in Swift 5 mode: Debug for the simulator, Release **unsigned archive for devices** (arm64, `CODE_SIGNING_ALLOWED=NO`). No debug launch argument in the Release binary.
- **Core links**: `scripts/build-ios-core.sh` builds the XCFramework (static lib with C `libzstd`) on macOS; no extra linker flags needed. The committed bindings match what the core generates.
- **102 unit tests pass** on the simulator, including `CoreEngineTests` on the real core (open, ranges, index build, cancel, `closeFile`) and `LocalizationTests` (every key in cs, sk, en from the compiled string catalog).
- **Real core in the app** (`FixtureDriveUITests`, review sample drive: Czech Wikipedia mini + index, Slovak map, games): Connect → Home with the tiles → search „prvni pomoc“ → article → Back with the query kept.
- **WKURLSchemeHandler**: pages, reader CSS/JS, fonts and images through `offlinka://`; internal links; external links show the offline notice. Reads take 0–60 ms.
- **Bundled guides** open from inside the app bundle and get a search index like drive books. As on Android 0.3.1 they always stand in for the drive's copy, which still explains the Kiwix apps.
- **Demo-drive smoke** (`SmokeUITests`): search, reader, save, Saved, Library, Settings, Slovak order, external link notice.
- **Maps**: MapLibre (WebGL, web workers, tiles through `offlinka://`) draws the Slovak map in the simulator.
- **Store screenshots** on iPhone 17 Pro Max, 17 Pro and iPad Pro 13" in cs, sk and en, on the review sample drive.
- Linux check (`tools/linux-check/check.sh`) still runs for quick type-checks without a Mac.

## Not verified (ranked by risk)

### High

1. **The real USB drive.** CI opens a folder through `-fixture-drive`, not the Files sheet. Untested: `.fileImporter` returning the drive root, `startAccessingSecurityScopedResource()` letting the core `pread` files below it, the bookmark surviving unplug and replug, and exFAT over the external file provider. Needs an iPhone with the drive (`UI-SMOKE-TESTS.md`).
2. **Speed and memory on a device.** Opening a 10 GB Wikipedia, suggestions per keystroke, the cluster cache (48/32 MB), `trimMemory` on memory warnings and building the Czech Wikipedia index (minutes) in the foreground were measured only on Linux and the simulator.
3. **First page after launch.** On the CI simulator WebKit takes 3–9 s to start its processes before the first request reaches the scheme handler. Expected well under a second on a device; if not, prewarm a web view at launch.
4. **Signing, TestFlight, App Store processing.** No Apple account yet: no signed build, no upload checks (entitlements, privacy manifest validation, export compliance).

### Medium

5. **Background index builds.** A running build gets `beginBackgroundTask` time, then stops cleanly (`Cancelled`) and resumes on the next start. Check on a device: build, lock the phone, unlock.
6. **Maps and games on a device.** The map draws in the simulator (slowly on CI: up to ~20 s for the first tiles); games open, but a kept score (`localStorage` per book origin) is untested. Check pan/zoom and a score on a device.
7. **Privacy manifest.** FileTimestamp `3B52.1` and DiskSpace `E174.1` declared; the archive job lists the required-reason symbols actually linked; compare before the first upload.
8. **Round-3 behaviour ported from Android, checked in tests and screenshots only:** „Vyzkoušejte hned“ on Home, the search note („Hledání se připravuje… / je připravené“), index phases in book details (Ve frontě, Málo místa, Zastavit přípravu), „Uložit / Uloženo“ in the reader bar, help in two groups.

### Low

9. **iPad and large text.** iPad layout is checked only in the store captures; largest Dynamic Type and VoiceOver are manual (`UI-SMOKE-TESTS.md`).
10. **iOS 17–18.** CI runs iOS 26 only; the deployment target is 17.0 (the iOS 26 toolbar code has an iOS 17 path that no test runs).
11. **fastlane.** Lanes not run (no Apple account).
