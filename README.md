# Offlinka app

Native apps that read the Offlinka USB-C flash drive (offline Czech/Slovak Wikipedia, maps, guides, games): **Android** (Kotlin, Jetpack Compose) and **iOS** (Swift, SwiftUI) on one shared **Rust core** (`core/`, our own ZIM reader + search). Fully offline, no tracking.

- **Spec (read first):** `docs/SPEC.md`: product, architecture, core API contract, brand, screens, quality bar, working rules.
- `core/` Rust core + `offlinka-cli` · `android/` · `ios/` · `design/` brand assets, tokens, reader CSS, icons · `store/` Google Play / App Store listings · `docs/` release, testing, device checklist.
- Toolchain on Company OS: `source ~/opt/offlinka-env.sh` (installer: `~/opt/install-offlinka-toolchain.sh`).
