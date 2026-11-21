# offlinka-core

Shared Rust engine of the Offlinka apps: **our own ZIM reader** (no libzim, no Kiwix code), a **title search index** (`.offidx`) and the **UniFFI API** that Android (Kotlin) and iOS (Swift) call. Contract: `docs/SPEC.md` → "Core API".

## Build and test

- Toolchain: `source ~/opt/offlinka-env.sh`. Heavy builds under the shared lock (see SPEC working rules).
- `cargo test` — unit + synthetic-ZIM tests + real small ZIMs from the drive (skipped when the drive folder is missing; `OFFLINKA_DRIVE=<…/files>` to point elsewhere).
- `cargo test --release -- --ignored` — big books (cs/sk mini index quality, cs maxi open + keystroke budget). `OFFLINKA_CS_MAXI_INDEX=<file>` reuses a built index.
- `cargo clippy --all-targets -- -D warnings`, `cargo fmt`.
- `scripts/gen-bindings.sh` — host cdylib (profile `host`, unstripped) → Kotlin (`android/core/src/main/kotlin/cz/offlinka/core/offlinka_core.kt`) + Swift (`ios/Offlinka/Generated/OfflinkaCore.swift` + `offlinka_coreFFI.h` + `.modulemap`) + JNA host lib `core/target/host/linux-x86-64/libofflinka_core.so`.
- `scripts/build-android-core.sh` — cargo-ndk, minSdk 26 → `android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libofflinka_core.so` (release: LTO, 1 codegen unit, stripped).
- `scripts/build-ios-core.sh` — macOS only: static libs (device + lipo'd simulator), Swift bindings, `ios/Frameworks/OfflinkaCore.xcframework`.
- `scripts/build-host-core.sh` — just the host library + `offlinka-cli`.

## API (Kotlin names in brackets)

- `core_version()` [`coreVersion()`]
- `Book.open_path(path)` [`Book.openPath`], `Book.open_fd(fd, name)` [`Book.openFd`]: Android passes `ParcelFileDescriptor.detachFd()`; **the core owns the fd** and closes it when the `Book` is dropped (`destroy()`/`close()` in Kotlin), or immediately if opening fails.
- `info()` → `BookInfo { id, name, title, description, language, creator, publisher, date, article_count, media_count, main_path, size_bytes, has_index }`.
  - **`id`** = ZIM `Name` metadata (`wikipedia_cs_all`), else the file name without `.zim`; ASCII-safe for `https://offlinka.local/<bookId>/<path>`. The maxi and mini edition of one wiki share it (saved articles survive switching editions).
  - `article_count` = HTML pages from `M/Counter` (mwoffliner's front-article listing also counts redirects); `media_count` = image/video/audio from `M/Counter`.
  - `main_path`: `W/mainPage` (redirects followed), else the header main page.
- `illustration(size)` → PNG: `Illustration_<s>x<s>@1`, else the nearest bigger, else the biggest, else the favicon.
- `get(path)` → `Resource { path, mime, data }`, redirects followed (max 10; loops are `InvalidZim`). `path` is the **resolved** path: when it differs from the requested one, answer the WebView with a redirect so relative links resolve.
- `get_range(path, offset, len)` (clamped to the end, empty past it) and `content_length(path)` for media: uncompressed clusters (images, video) are read straight from the file, never loaded whole.
- `suggest(query, limit)` → `Suggestion { title, path, snippet }`: `title` = the matched title (a redirect shows its own title), `path` = the target to open, `snippet` = for a redirect the target article's title (e.g. "Prag" → "Praha"), else `None`.
- `random_article()` → path of a random HTML article.
- `attach_index_path(path)` / `attach_index_fd(fd)` [`attachIndexPath`/`attachIndexFd`]: the core owns the fd (closed with the book, or right away for indexes ≤ 4 MB, which are read whole). An index of another ZIM is refused (`InvalidZim`). No `mmap`: a pulled drive gives `Io` errors, never a SIGBUS crash.
- `Library.new()`, `add(book)`, `books()`, `suggest_all(query, limit)` → `LibrarySuggestion { book_id, title, path }`. `add` with an id already present keeps the book with more articles (maxi wins over mini; the same file added twice is kept once).
- `CoreError` [`CoreException`]: `Io` (unplugged drive, bad fd), `InvalidZim` (not a ZIM, truncated, corrupt, wrong index), `NotFound`, `Unsupported` (zlib/bzip2 clusters, unknown index version). Non-throwing calls (`info`, `illustration`, `suggest`, `random_article`, `suggest_all`) return empty/`None` on I/O errors.

**Paths.** New-namespace ZIMs (all of ours): a path is the `C/` path without the namespace, exactly what relative links in the articles produce (`Praha`, `_mw_/style.css`). `C/Praha`, `/Praha` and other namespaces (`M/Title`) work too. Old-scheme ZIMs use `<ns>/<path>` (`A/Praha`).

**Threads.** `Book` and `Library` are `Send + Sync`; reads use `pread` (no shared cursor). Caches: decompressed clusters, LRU bounded to 48 MB per book; 16k directory entries per book.

## ZIM support

Header (magic, v5/v6), MIME list, path pointer list, classic title pointer list and `X/listing/titleOrdered/v0|v1`, content/redirect/link-target/deleted dirents, clusters uncompressed / xz (lzma-rs) / zstd (ruzstd) with normal and extended (64-bit) offsets, metadata `M/*`, `W/mainPage`, old (`A`/`I`/`-`) and new (`C`/`M`/`W`/`X`) namespaces. Not supported: zlib/bzip2 clusters (deprecated, `Unsupported`), checksum verification (optional in the spec). Corrupt input gives errors, never panics (fuzzed in tests); a single cluster may not inflate beyond 512 MB.

## Search index (`.offidx`)

**Drive build:** `offlinka-cli index <book>.zim -o <book>.offidx` for every ZIM, file next to the ZIM with the same stem (`Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.offidx`). The apps attach it when present; without it the core falls back to the ZIM's title listing (case variants of a plain prefix, no diacritics folding). The index is bound to the ZIM's UUID: rebuild it whenever the ZIM changes.

- **Normalisation** (`normalize.rs`): NFD, combining marks removed, lowercase, `ł ø đ ß æ œ þ ı` folded by hand, apostrophes dropped, other punctuation → one space. Czech/Slovak letters (`ěščřžýáíéůúňťďľĺŕôä`) all decompose, so `cesky krumlov` = `Český Krumlov`.
- **Records**: every article (front-article listing, else HTML in `C/`) and every redirect whose chain ends at one, as `(shown entry, target entry)`. Record ids are assigned in rank order — articles before redirects, then shorter normalised title, then alphabetical — so "best" = smallest id.
- **Maps** (`fst`, key → ordinal): normalised title, and every word suffix of an article title (`okres cesky krumlov` → `cesky krumlov`, `krumlov`, max 8 per title). An ordinal points into a CSR posting table, so a prefix is one contiguous ordinal range: two `fst` lookups + two reads. Prefixes with more than 1024 keys get a precomputed top-64 list, so a one-letter query never reads a big range.
- **Memory**: only the `fst` maps stay in RAM; records, postings and top lists are read with `pread` (files ≤ 4 MB are read whole).
- **Ranking**: exact title > title prefix > exact word suffix > word-suffix prefix; inside a class by record id; deduped by target (a redirect never shows next to its own article). `Library.suggest_all`: by class, then Czech Wikipedia first, then the order books were added, then in-book rank.
- **Format**: see the header comment in `src/index.rs` (magic `OFFIDX\0\x01`, version 3, ZIM UUID + entry count, 10 sections).

## offlinka-cli

`info`, `ls`, `get`, `suggest` (one ZIM, or several = library merge), `index`, `bench` (open + main page time, per-keystroke suggest latency p50/p95/p99/max), `verify` (inflate every cluster, resolve every entry). It auto-attaches `<zim stem>.offidx` when it sits next to the ZIM (`--index none` to disable).

## Performance

Filled in from `offlinka-cli bench` / `index` runs on the drive edition 2026-11.3 (this box, release build).

## Licences

Our own code, no GPL/LGPL code (App Store safe). Dependencies and their licences: `LICENSES-THIRD-PARTY.md`.
