# offlinka-core

Shared Rust engine of the Offlinka apps: **our own ZIM reader** (no libzim, no Kiwix code), a **title search index** (`.offidx`) and the **UniFFI API** that Android (Kotlin) and iOS (Swift) call. Contract: `docs/SPEC.md` → "Core API".

## Build and test

- Toolchain: `source ~/opt/offlinka-env.sh`. Heavy builds under the shared lock (see SPEC working rules).
- `cargo test` — unit + synthetic-ZIM tests + real small ZIMs from the drive (skipped when the drive folder is missing; `OFFLINKA_DRIVE=<…/files>` to point elsewhere).
- `cargo test --profile host -- --ignored` (optimised, no LTO) — big books (cs/sk mini index quality, cs maxi open + keystroke budget). `OFFLINKA_CS_MAXI_INDEX=<file>` reuses a built index.
- `cargo clippy --all-targets -- -D warnings`, `cargo fmt`.
- `scripts/gen-bindings.sh` — host cdylib (profile `host`, unstripped) → Kotlin (`android/core/src/main/kotlin/cz/offlinka/core/offlinka_core.kt`) + Swift (`ios/Offlinka/Generated/OfflinkaCore.swift` + `offlinka_coreFFI.h` + `.modulemap`) + JNA host lib `core/target/host/linux-x86-64/libofflinka_core.so`.
- **Regenerate the bindings after any change to the exported API, including its doc comments**: UniFFI's API checksums cover the docs, and stale bindings fail at load with "UniFFI API checksum mismatch".
- `scripts/build-android-core.sh` — cargo-ndk, minSdk 26 → `android/app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libofflinka_core.so` (release: LTO, 1 codegen unit, stripped).
- `scripts/build-ios-core.sh` — macOS only: static libs (device + lipo'd simulator), Swift bindings, `ios/Frameworks/OfflinkaCore.xcframework`.
- `scripts/build-host-core.sh` — just the host library + `offlinka-cli`.

## API (Kotlin names in brackets)

- `core_version()` [`coreVersion()`]
- `Book.open_path(path)` [`Book.openPath`], `Book.open_fd(fd, name)` [`Book.openFd`]: Android passes `ParcelFileDescriptor.detachFd()`; **the core owns the fd** and closes it when the `Book` is dropped (`destroy()`/`close()` in Kotlin), on `close_file()`, or immediately if opening fails. **Opening reads only the header and the MIME list (2 reads)**; metadata, main page and title listing are read on the first `info()`/`suggest()` (~15–28 reads whatever the book's size; before: ~90–110 reads inside open).
- `info()` → `BookInfo { id, name, title, description, language, creator, publisher, date, article_count, media_count, main_path, size_bytes, has_index }`. First call reads the metadata (one partial zstd decode per metadata cluster, no binary search: `M`/`W`/`X` entries sort last and are read with one window); never fails (a closed/corrupt book gives a minimal record named after the file).
  - **`id`** = ZIM `Name` metadata (`wikipedia_cs_all`), else the file name without `.zim`; ASCII-safe for `https://offlinka.local/<bookId>/<path>`. The maxi and mini edition of one wiki share it (saved articles survive switching editions).
  - `article_count` = HTML pages from `M/Counter` (mwoffliner's front-article listing also counts redirects); `media_count` = image/video/audio from `M/Counter`.
  - `main_path`: `W/mainPage` (redirects followed), else the header main page.
- `illustration(size)` → PNG: `Illustration_<s>x<s>@1`, else the nearest bigger, else the biggest, else the favicon.
- `get(path)` → `Resource { path, mime, data }`, redirects followed (max 10; loops are `InvalidZim`). `path` is the **resolved** path: when it differs from the requested one, answer the WebView with a redirect so relative links resolve.
- `get_range(path, offset, len)` (clamped to the end, empty past it) and `content_length(path)` for media: uncompressed clusters (images, video) are read straight from the file, never loaded whole.
- `suggest(query, limit)` → `Suggestion { title, path, snippet }`: `title` = the matched title (a redirect shows its own title), `path` = the target to open, `snippet` = for a redirect the target article's title (e.g. "Prag" → "Praha"), else `None`.
- `random_article()` → path of a random HTML article.
- `attach_index_path(path)` / `attach_index_fd(fd)` [`attachIndexPath`/`attachIndexFd`]: **reads only the 224-byte header**; the in-memory part (fst maps: cs maxi 16 MB, en top1m 76 MB) loads on the first `suggest`, or now with `preload_index()` [`preloadIndex`] on a background thread. The core owns the fd (closed with the book or by `close_file`; indexes ≤ 4 MB are read whole and the fd closed at once). An index of another ZIM is refused (`InvalidZim`). No `mmap`: a pulled drive gives `Io` errors, never a SIGBUS crash.
- `close_file()` [`closeFile`]: close the ZIM and index files now (unplug, book removed). The object stays valid; calls in flight finish, later `get`/`get_range`/`content_length`/`attach_*`/`build_index` fail with `CoreError.Io`, `suggest` returns nothing, `info()` keeps its cached value. Prefer it to Kotlin `close()`/`destroy()` while other threads may still hold the book: after `destroy()` the generated Kotlin throws `IllegalStateException` before reaching Rust.
- `build_index(book, out_path, listener)` [`buildIndex`] (free function): builds the `.offidx` **on the device** for drives sold without one (edition 2026-11.3), into app storage. Blocking (background thread), writes `<out_path>.part` then renames, byte-identical to `offlinka-cli index`. `IndexBuildListener` [Kotlin interface / Swift protocol] `on_progress(fraction: f64)` [`onProgress`] (0→1, increasing, at most every 0.2 % or 250 ms) and `is_cancelled()` [`isCancelled`] (polled with every progress call; `true` → `CoreError.Cancelled`, no file left). Does not attach: call `attach_index_path(out_path)` after.
- `trim_memory()` [`trimMemory`] (free function): on `onTrimMemory` / memory warning: drops all cached clusters and directory entries and unloads every index's in-memory part (reloads on the next suggest). `set_cluster_cache_budget(bytes)` [`setClusterCacheBudget`]: the decompressed-cluster cache is **shared by all books**, default 48 MB.
- `Library.new()` [**`BookLibrary()` in Kotlin**: the generated Kotlin imports `com.sun.jna.Library`; Swift keeps `Library`], `add(book)`, `books()`, `suggest_all(query, limit)` [`suggestAll`] → `LibrarySuggestion { book_id, title, path }`. `add` with an id already present keeps the book with more articles (maxi wins over mini; the same file added twice is kept once).
- `CoreError` [`CoreException`]: `Io` (unplugged drive, bad fd, closed book), `InvalidZim` (not a ZIM, truncated, corrupt, wrong index), `NotFound`, `Unsupported` (zlib/bzip2 clusters, unknown index version), `Cancelled` (`build_index` stopped by its listener). Non-throwing calls (`info`, `illustration`, `suggest`, `random_article`, `suggest_all`) return empty/`None` on I/O errors.

**Paths.** New-namespace ZIMs (all of ours): a path is the `C/` path without the namespace, exactly what relative links in the articles produce (`Praha`, `_mw_/style.css`). `C/Praha`, `/Praha` and other namespaces (`M/Title`) work too. Old-scheme ZIMs use `<ns>/<path>` (`A/Praha`).

**Threads and memory.** `Book` and `Library` are `Send + Sync`; reads use `pread` (no shared cursor). Caches are process-wide: decompressed clusters, one LRU of 48 MB for all books (`set_cluster_cache_budget`); 32k directory entries for all books; parallel requests for one cluster inflate it once. `trim_memory()` empties them.

## ZIM support

Header (magic, v5/v6), MIME list, path pointer list, classic title pointer list and `X/listing/titleOrdered/v0|v1`, content/redirect/link-target/deleted dirents, clusters uncompressed / xz (lzma-rs, pure Rust) / zstd (libzstd via the `zstd` crate, default feature `zstd-c`; `--no-default-features --features zstd-pure` switches to the pure-Rust ruzstd) with normal and extended (64-bit) offsets, metadata `M/*`, `W/mainPage`, old (`A`/`I`/`-`) and new (`C`/`M`/`W`/`X`) namespaces. Not supported: zlib/bzip2 clusters (deprecated, `Unsupported`), checksum verification (optional in the spec). Corrupt input gives errors, never panics (fuzzed in tests); a single cluster may not inflate beyond 256 MB, `get()` refuses entries over 256 MB (use `get_range`). Parallel requests for one cluster inflate it once.

## Search index (`.offidx`)

**On the phone** (drives of edition 2026-11.3 ship no `.offidx`): `build_index(book, filesDir/<stem>.offidx, listener)` once per book, then `attach_index_path`. Same bytes as the CLI, so a later drive's index can replace it. See Performance for time and memory per book; **recommendation: build cs/sk Wikipedia and the small books automatically on first use, English top1m only on demand** (minutes and ~460 MB peak).

**Drive build:** build the CLI once (`cd core && cargo build --release --bin offlinka-cli` → `core/target/release/offlinka-cli`), then `offlinka-cli index <book>.zim -o <book>.offidx` for every ZIM (or `scripts/build-drive-indexes.sh`), file next to the ZIM with the same stem (`Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.offidx`). The apps attach it when present; without it the core falls back to the ZIM's title listing (case variants of a plain prefix, no diacritics folding). The index is bound to the ZIM's UUID: rebuild it whenever the ZIM changes.

- **Normalisation** (`normalize.rs`): NFD, combining marks removed, lowercase, `ł ø đ ß æ œ þ ı` folded by hand, apostrophes dropped, other punctuation → one space. Czech/Slovak letters (`ěščřžýáíéůúňťďľĺŕôä`) all decompose, so `cesky krumlov` = `Český Krumlov`.
- **Records**: every article (front-article listing, else HTML in `C/`) and every redirect whose chain ends at one, as `(shown entry, target entry)` stored as directory-entry offsets in the ZIM (one read per title). Each record has a **static score** = `0.12 × normalised title length + 1 for a redirect − log2(target article size in bytes, min 1 KB) − 0.5 × log2(1 + redirects pointing at the target)`; ids: articles then redirects, each part in score order (ties: shorter title as written, then alphabetical). Article sizes come from the clusters' offset tables only (one zstd block per cluster).
- **Maps** (`fst`, key → ordinal): normalised title, and every word suffix of an article title (`okres cesky krumlov` → `cesky krumlov`, `krumlov`, max 8 per title). An ordinal points into a CSR posting table, so a prefix is one contiguous ordinal range: two `fst` lookups + two reads. Prefixes with more than 1024 keys get a precomputed top-64 list, so a one-letter query never reads a big range.
- **Memory**: only the `fst` maps stay in RAM; records, postings and top lists are read with `pread` (files ≤ 4 MB are read whole).
- **Ranking**: query score = tier cost (exact title 0, title prefix 2, exact word suffix 3, word-suffix prefix 5) + static score, lower first; one result per target. Popularity can beat a weaker tier: „masaryk“ → Tomáš Garrigue Masaryk, „krumlov“ → Český Krumlov, „karel“ → Karel IV. `Library.suggest_all`: merged by score, +2 for books other than the Czech Wikipedia, then add order. Constants in `src/index.rs`.
- **Format**: see the header comment in `src/index.rs` (magic `OFFIDX\0\x01`, version 5, ZIM UUID + entry count, 11 sections (v5 adds the score-level table)).

## offlinka-cli

`info`, `ls`, `get`, `suggest` (one ZIM, or several = library merge), `index`, `bench` (open + main page time, per-keystroke suggest latency p50/p95/p99/max), `bench-lib` (same for `Library.suggest_all` over several ZIMs), `verify` (inflate every cluster, resolve every entry), `inflate` (cluster decompression speed). It attaches `<zim stem>.offidx` from `--index-dir D` or next to the ZIM (`--index F` explicit, `--index none` off).

**Drive build, all books at once:** `scripts/build-drive-indexes.sh <edition>/files [out-dir]` (skips up-to-date indexes, `FORCE=1` rebuilds).

## Performance

Drive edition 2026-11.3, this box (x86_64, shared, load ~8), release build, warm page cache. Phones are slower and the USB drive adds ~0.5–1 ms per cold read; the budget is 100 ms per keystroke.

**Why libzstd:** inflating one ~2 MB Wikipedia cluster takes 3.3 ms (cs mini) / 3.7 ms (cs maxi) with libzstd vs 12.7 / 52.7 ms with ruzstd (`offlinka-cli inflate`). Opening an article = one cluster.

**Suggest per keystroke** (`offlinka-cli bench --queries bench/queries-cs.txt`, 353 keystrokes, limit 20, index v4, warm run):

| book | index | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| cs maxi | yes | 0.11 ms | 0.14 ms | 0.18 ms | 2.9 ms |
| cs mini | yes | 0.11 ms | 0.19 ms | 0.24 ms | 0.32 ms |
| sk maxi | yes | 0.10 ms | 0.15 ms | 0.21 ms | 5.1 ms |
| en top1m | yes | 0.10 ms | 0.18 ms | 0.21 ms | 0.26 ms |
| Library, all 14 books of `Knihovna/` (`bench-lib`) | yes | 0.25 ms | 0.60 ms | 2.2 ms | 14 ms |
| cs mini | no (fallback listing) | 0.24 ms | 3.4 ms | 7.1 ms | 100 ms |

Cold page cache on this VM disk (after other builds evicted it) the cs maxi worst keystroke was 54 ms (`cs_maxi_open_main_and_search`, budget asserted < 100 ms). A suggestion costs one index record read plus one ZIM dirent read per title shown (two for a redirect).

**Open + main page:** cs maxi 0.1–0.5 s (incl. loading 16 MB of index fst), library of 14 books 0.3–0.5 s, en top1m alone up to 0.9 s cold (76 MB of fst: attach big indexes off the UI thread or lazily).

**Index per book** (`offlinka-cli index`, v4; "RAM" = fst maps kept in memory, the rest is read with `pread`):

| book | records | .offidx | RAM | build |
|---|---|---|---|---|
| wikipedia_cs_all_maxi | 975,172 | 40.1 MB | 16.0 MB | 23 s |
| wikipedia_cs_all_mini | 963,395 | 39.6 MB | 15.8 MB | 16 s |
| wikipedia_sk_all_maxi | 439,233 | 19.7 MB | 8.0 MB | 11 s |
| wikipedia_sk_all_mini | 438,360 | 19.6 MB | 8.0 MB | 6 s |
| wikipedia_en_top1m_nopic | 5,904,211 | 218.6 MB | 76.2 MB | 104 s (570 MB RSS) |
| wikipedia_en_medicine_nopic | 371,233 | 13.8 MB | 5.0 MB | 4 s |
| wikisource_cs | 65,396 | 6.1 MB | 3.3 MB | 2 s |
| wiktionary_cs | 164,473 | 4.2 MB | 1.4 MB | 1 s |
| wikiquote_cs | 16,833 | 0.73 MB | read whole | <1 s |
| wikiversity_cs | 6,611 | 0.58 MB | read whole | <1 s |
| wikivoyage_en_europe | 18,322 | 0.57 MB | read whole | <1 s |
| wikibooks_cs | 3,403 | 0.34 MB | read whole | <1 s |
| maps_en_czech-republic / slovakia | 8,632 / 4,091 | 0.26 / 0.12 MB | read whole | <1 s |
| offlinka_cs_prirucky / mul_hry | 6 / 11 | 1 KB | read whole | <1 s |

All indexes of the edition: ~380 MB on the drive (219 MB of it English).

**Android library size** (`scripts/build-android-core.sh`, stripped, LTO): opt-level 3 → arm64-v8a 873 KB, armeabi-v7a 672 KB, x86_64 995 KB; opt-level "s" (`PROFILE=release-s`) → 862 / 648 / 962 KB. Only 1–4 % smaller, so the release profile stays at opt-level 3.

## Licences

Our own code, no GPL/LGPL code (App Store safe). Dependencies and their licences: `LICENSES-THIRD-PARTY.md`.
