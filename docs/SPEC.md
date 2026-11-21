# Offlinka app: spec (source of truth for every agent)

**Goal:** one branded, native app per platform that reads the Offlinka USB-C flash drive directly and feels far better than Kiwix. Android (Kotlin) first and tested here; iOS (Swift) built alongside, compiled and tested later on macOS.

## Product

- **Name** „Offlinka“. Android `applicationId` / iOS bundle id **`cz.offlinka.app`**. UI languages **cs** (default), **sk**, **en** (follow the system, switch in Settings).
- **Fully offline.** Android has **no INTERNET permission**; iOS makes no network calls. No analytics, no crash reporting, no accounts. Store privacy label: "Data not collected".
- **Content = the drive.** The user picks the drive once (Android: Storage Access Framework tree URI with a persisted permission; iOS: folder picker + security-scoped bookmark). The app finds every `*.zim` in `Knihovna/` and `Telefon/` and reads them **in place, never copying and never moving or deleting anything on the drive.** On the drive the big editions (`Knihovna/*maxi*`) win over the phone ones (`Telefon/*mini*`) for the same language. Later: an optional "keep a copy in the phone" for the `Telefon/` mini Czech Wikipedia (~1 GB).
- **Phones:** Android 8+ with USB-C OTG (exFAT drive); iPhone 15+ / USB-C iPad, iOS 17+. Lightning iPhones are not supported.
- **Tone:** short calm Czech, „vy“, no em dashes, no emoji in UI text. The product is „Offlinka“, the content is „česká Wikipedie“ etc. Never say „Kiwix“ in the UI (only in About → licences).

## What is on the drive (edition 2026-11.3, read-only test data)

`/srv/offlinka/drive/release/2026-11.3/files/`:
- `Knihovna/`: `wikipedia_cs_all_maxi-offlinka_2026-09.zim`, `wikipedia_sk_all_maxi…`, `wikipedia_en_top1m_nopic…`, `wikipedia_en_medicine_nopic…`, `wikibooks_cs…`, `wikiquote_cs…`, `wikisource_cs…`, `wikiversity_cs…`, `wiktionary_cs_all_nopic…`, `wikivoyage_en_europe…`, `maps_en_czech-republic_2026-06.zim`, `maps_en_slovakia…` (a web map viewer + vector tiles inside the ZIM, needs JS), `offlinka_cs_prirucky.zim` (our emergency guides), `offlinka_mul_hry.zim` (HTML5 games, needs JS). 32 GB total.
- `Telefon/`: `wikipedia_cs_all_mini-offlinka_2026-09.zim` (~1 GB), `wikipedia_sk_all_mini…`, `offlinka_cs_prirucky.zim`, `offlinka_mul_hry.zim`.
- Small ZIMs for fast tests: `offlinka_cs_prirucky.zim`, `offlinka_mul_hry.zim`, the `Telefon/` minis. Never write into `/srv/offlinka/drive`.
- ZIM format: openZIM spec (https://wiki.openzim.org/wiki/ZIM_file_format). These files use the new namespace scheme (`C` content, `M` metadata, `W` well-known, `X` indexes), zstd clusters, possibly xz in older ones.

## Architecture (decided)

- **`core/` Rust crate `offlinka-core`, our own ZIM reader (no libzim, no Kiwix code)** so the App Store has no GPL conflict. Random access through a `ReadAt` trait (`pread` on a file descriptor or a path). LRU cache of decompressed clusters. Redirects followed (max 10). Bindings with **UniFFI** (proc-macro) for Kotlin and Swift.
- **Search:** our own per-book index: `offlinka-cli index <book.zim> -o <book>.offidx` = `fst` maps of normalised titles (lowercase, diacritics removed, Czech/Slovak aware) and of the word suffixes of article titles → records. **Ranking (index v5):** score = tier cost (exact title 0, title prefix 2, exact word 3, word prefix 5) + static score of the record (`0.12 × normalised title length + 1 for a redirect − log2(size of the target article in bytes) − 0.5 × log2(1 + redirects pointing at it)`, lower is better); popularity = article size and number of redirects (alternative names), so a much bigger article can beat a weaker tier („masaryk“ → Tomáš Garrigue Masaryk, „krumlov“ → Český Krumlov). Redirects resolve to their target but show under the searched title; one result per target. `Library.suggest_all` merges by score with a +2 handicap for books other than the Czech Wikipedia. **Drives already sold (edition 2026-11.3) have no `.offidx`:** the apps build the index themselves on first use with `build_index` into app storage (never on the drive); the result is byte-identical to `offlinka-cli index`, so later drives can ship it. **Without an index the core still works** (raw title-ordered listing, case-insensitive prefix where possible). Full-text search is phase 2 (decide size vs value; tantivy is MIT).
- **Core API (the contract; Android/iOS code against exactly this; change it only together):**
  - `core_version() -> String`
  - `Book` (object): `open_path(path)`, `open_fd(fd: i32, name: String)` (Android passes a detached fd; the core owns and closes it; opening reads only the header and MIME list, everything else is lazy), `info() -> BookInfo {id, name, title, description, language, creator, publisher, date, article_count, media_count, main_path: Option<String>, size_bytes, has_index}` (metadata read on first call), `illustration(size: u32) -> Option<Vec<u8>>` (PNG), `get(path) -> Resource {path, mime, data: Vec<u8>}` (follows redirects; error `NotFound`), `get_range(path, offset: u64, len: u32) -> Vec<u8>` and `content_length(path) -> u64` (for media / big files), `suggest(query, limit) -> Vec<Suggestion {title, path, snippet: Option<String>}>`, `random_article() -> Option<String>`, `attach_index_path(path)` / `attach_index_fd(fd)` (reads only the header; the in-memory part loads on the first suggest), `preload_index()` (load it now, on a background thread), `close_file()` (close the ZIM and index files now, e.g. on unplug; returns at once even while a read hangs on a dying drive; later calls fail with `Io`, never panic; the fds close when the last call in flight ends).
  - Free functions: `core_version()`, `build_index(book, out_path, listener: IndexBuildListener)` (blocking; writes `<out_path>.part` then renames; error `Cancelled` when the listener asks to stop), callback interface `IndexBuildListener { on_progress(fraction: f64), is_cancelled() -> bool }` (implemented in Kotlin/Swift), `trim_memory()` (drop all cached clusters/entries and unload index maps; call on memory pressure; never waits for an index load in progress), `set_cluster_cache_budget(bytes: u64)` (one decompressed-cluster cache shared by all books, default 48 MB).
  - `Library` (object; **named `BookLibrary` in Kotlin** because `com.sun.jna.Library` is imported by the generated code; `Library` in Swift): `new()`, `add(book)`, `books()`, `suggest_all(query, limit) -> Vec<LibrarySuggestion {book_id, title, path}>` (merged and ranked, Czech Wikipedia first by default).
  - `CoreError` (`CoreException` in Kotlin): `Io`, `InvalidZim`, `NotFound`, `Unsupported`, `Cancelled`. A pulled drive is always `Io` (also a read that ends early); `build_index` never leaves a `.part` or a half-built index (pulled drive → `Io`, throwing listener → `Cancelled`). No exported call panics into Kotlin/Swift: an internal panic becomes `InvalidZim` or the empty answer of a non-throwing call. `suggest`/`suggest_all` return at most 1000 results.
- **`offlinka-cli`**: `info`, `ls`, `get`, `suggest`, `index`, `bench` (used by tests and by the drive build).
- **`android/`**: Kotlin, Jetpack Compose, Material 3 with the brand theme, minSdk 26, target/compile 36 (Google Play requires API 36 for new apps since 2026-08-31), Gradle Kotlin DSL + version catalog, Rust `.so` via `cargo-ndk` for arm64-v8a, armeabi-v7a, x86_64. Articles in a `WebView`: all requests go to `https://offlinka.local/<bookId>/<path>` and are answered by `shouldInterceptRequest` from the core (correct MIME, range requests for media); anything else is blocked. JS on (maps, games). Our reader stylesheet is injected into Wikipedia pages.
- **`ios/`**: Swift 5.10+, SwiftUI, iOS 17+, project generated by **XcodeGen** (`ios/project.yml`; no hand-edited `.pbxproj`), `WKWebView` with a `WKURLSchemeHandler` for scheme `offlinka://`, Rust core as an XCFramework built by `scripts/build-ios-core.sh` (runs on macOS) + UniFFI Swift bindings, XCTest target. It cannot be compiled on this Linux box: write it carefully, keep it parallel to Android, list every unverified assumption in `ios/UNVERIFIED.md`.
- **`design/`**: brand assets (from the drive: `design/brand/` logo, topo patterns, Archivo fonts; `design/reference/drive-app.html` + `brand.css` = the desktop start page on the drive: the look to match), tokens, the reader CSS, app icons, UI strings (cs/sk/en).

## Brand (match the drive's start page and offlinka.cz)

- Colours: forest `#1F3D2F` (dark `#14291F`, light `#2F5642`), paper `#F5F0E3`, card `#FFFDF7`, ink `#17231C`, line `#CDBF9F`, mist `#E9DFC8`, fire/orange `#F26A1B` (dark `#B94A0B`), moss `#7C9A68`, warn `#FBDCC8`, red `#B4321F`. Dark mode: its own steps from the same palette, not an automatic invert.
- Type: **Archivo** (text 400/600/700) and **Archivo Expanded 800** for headings; full Czech/Slovak diacritics (the bundled woff2 subsets are cs/sk; convert to TTF/OTF for the apps, OFL licence).
- Logo: `design/brand/logo.svg` / `logo-ink.svg` („offlinka“ wordmark with the orange accent); topo line pattern `topo-l.svg` / `topo-d.svg` as subtle backgrounds.
- Feel: calm, sturdy, a bit outdoorsy; big readable text; rounded 8 px corners; no stock icons where a simple custom one fits.

## Screens (Android and iOS alike)

1. **Connect the drive:** illustration of the drive in a phone, „Zapojte flashku Offlinka“, button „Vybrat flashku“, short help when the drive is not found or the wrong folder is picked; remembers the drive; a calm state when the drive is unplugged later.
2. **Home (start page):** big search field „Hledat v Offlince“; tiles for the books found (Wikipedie CZ, Wikipedie SK, English Wikipedia, Medicína, Mapy Česka/Slovenska, Nouzové příručky, Hry, Wikiknihy, Wikicitáty, Wikizdroje, Wikiverzita, Wikislovník, Wikivoyage), each with its icon and a one-line description; quick links „První pomoc“ and „Mějte doma předem“ (guides), „Náhodný článek“.
3. **Search:** suggestions while typing (≤ 100 ms per keystroke on the cs maxi with an index), diacritics-insensitive („cesky krumlov“ finds „Český Krumlov“), grouped by book, recent searches.
4. **Reader:** the article with our stylesheet, title bar, back/forward, in-article find, text size, light/dark, add to „Uložené“, history, open the book's main page.
5. **Library:** every book on the drive with title, size, date, language; open; details.
6. **Saved and history**, **Settings** (language, theme, text size), **About** (edition, licences: Wikipedia CC BY-SA, OpenStreetMap ODbL, fonts OFL, app licence, open-source notices).

## Quality bar

- Opens the cs maxi Wikipedia from the drive and shows the main page in under 2 s on a mid phone; scrolling and links never jank; unplugging the drive never crashes the app.
- Every screen has Czech, Slovak and English strings; accessibility: content descriptions, 48 dp targets, dynamic type / font scale, dark mode.
- Tests: Rust unit + integration tests against the real ZIMs; Android JVM tests (Robolectric), Paparazzi screenshot tests of every screen in light/dark and cs/en, and host-JVM tests that load the host-built Rust core through the generated Kotlin bindings and read the real ZIMs. **No Android emulator is possible here (no KVM)**: the founders test on real phones; anything only a device can prove goes into `docs/DEVICE-TESTS.md` as a checklist.

## Working rules (this box is shared)

- Toolchain: `source ~/opt/offlinka-env.sh` (Rust + Android targets + cargo-ndk, JDK 17, Android SDK 36 (+35) + NDK 27; the Android build runs Gradle on JDK 21, downloaded automatically).
- **Heavy builds one at a time:** wrap every `cargo build --release`, `cargo test` on big ZIMs, and `./gradlew` run in `flock /tmp/claude-1000/offlinka-app-build.lock nice -n 10 timeout 3600 …`. Gradle: `org.gradle.jvmargs=-Xmx3g`, `org.gradle.workers.max=3`, run with `--no-daemon` (no leftover daemons). Cargo: `-j 4`. Screenshots in Chromium only through `atd-render timeout 120 …`.
- Each agent works in its own git worktree of `/srv/offlinka/app` (`git -C /srv/offlinka/app worktree add /srv/offlinka/app-<name> -b <name> origin/main`), touches only its own folders, commits often, rebases on `origin/main` and pushes to `main` when its part builds and its tests pass. Pushing uses the repo's deploy key automatically (`core.sshCommand`).
- Commits: descriptive, ending with `Claude-Session: https://claude.ai/code/session_011pzDzup6okgYEzmi1KbmQR`; never a Co-Authored-By line or AI credit.
- Never `pkill -f`; stop only your own PIDs; leave no servers, daemons or emulators running. Never print secrets (signing passwords live in the project `_secrets/`).
