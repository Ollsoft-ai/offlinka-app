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

Header (magic, v5/v6), MIME list, path pointer list, classic title pointer list and `X/listing/titleOrdered/v0|v1`, content/redirect/link-target/deleted dirents, clusters uncompressed / xz (lzma-rs, pure Rust) / zstd (libzstd via the `zstd` crate, default feature `zstd-c`; `--no-default-features --features zstd-pure` switches to the pure-Rust ruzstd) with normal and extended (64-bit) offsets, metadata `M/*`, `W/mainPage`, old (`A`/`I`/`-`) and new (`C`/`M`/`W`/`X`) namespaces. Not supported: zlib/bzip2 clusters (deprecated, `Unsupported`), checksum verification (optional in the spec). Corrupt input gives errors, never panics (fuzzed in tests); a single cluster may not inflate beyond 256 MB, `get()` refuses entries over 256 MB (use `get_range`). Parallel requests for one cluster inflate it once.

## Search index (`.offidx`)

**Drive build:** `offlinka-cli index <book>.zim -o <book>.offidx` for every ZIM, file next to the ZIM with the same stem (`Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.offidx`). The apps attach it when present; without it the core falls back to the ZIM's title listing (case variants of a plain prefix, no diacritics folding). The index is bound to the ZIM's UUID: rebuild it whenever the ZIM changes.

- **Normalisation** (`normalize.rs`): NFD, combining marks removed, lowercase, `ł ø đ ß æ œ þ ı` folded by hand, apostrophes dropped, other punctuation → one space. Czech/Slovak letters (`ěščřžýáíéůúňťďľĺŕôä`) all decompose, so `cesky krumlov` = `Český Krumlov`.
- **Records**: every article (front-article listing, else HTML in `C/`) and every redirect whose chain ends at one, as `(shown entry, target entry)`. Record ids are assigned in rank order — articles before redirects, then shorter normalised title, then alphabetical — so "best" = smallest id.
- **Maps** (`fst`, key → ordinal): normalised title, and every word suffix of an article title (`okres cesky krumlov` → `cesky krumlov`, `krumlov`, max 8 per title). An ordinal points into a CSR posting table, so a prefix is one contiguous ordinal range: two `fst` lookups + two reads. Prefixes with more than 1024 keys get a precomputed top-64 list, so a one-letter query never reads a big range.
- **Memory**: only the `fst` maps stay in RAM; records, postings and top lists are read with `pread` (files ≤ 4 MB are read whole).
- **Ranking**: exact title > title prefix > exact word suffix > word-suffix prefix; inside a class by record id; deduped by target (a redirect never shows next to its own article). `Library.suggest_all`: by class, then Czech Wikipedia first, then the order books were added, then in-book rank.
- **Format**: see the header comment in `src/index.rs` (magic `OFFIDX\0\x01`, version 3, ZIM UUID + entry count, 10 sections).

## offlinka-cli

`info`, `ls`, `get`, `suggest` (one ZIM, or several = library merge), `index`, `bench` (open + main page time, per-keystroke suggest latency p50/p95/p99/max), `bench-lib` (same for `Library.suggest_all` over several ZIMs), `verify` (inflate every cluster, resolve every entry), `inflate` (cluster decompression speed). It attaches `<zim stem>.offidx` from `--index-dir D` or next to the ZIM (`--index F` explicit, `--index none` off).

**Drive build, all books at once:** `scripts/build-drive-indexes.sh <edition>/files [out-dir]` (skips up-to-date indexes, `FORCE=1` rebuilds).

## Performance

Drive edition 2026-11.3, this box (x86_64, shared, load ~8), release build, warm page cache. Phones are slower and the USB drive adds ~0.5–1 ms per cold read; the budget is 100 ms per keystroke.

**Why libzstd:** inflating one ~2 MB Wikipedia cluster takes 3.3 ms (cs mini) / 3.7 ms (cs maxi) with libzstd vs 12.7 / 52.7 ms with ruzstd (`offlinka-cli inflate`). Opening an article = one cluster.

**Suggest per keystroke** (`offlinka-cli bench --queries bench/queries-cs.txt`, 353 keystrokes, limit 20):

| book | index | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| cs maxi | yes | 0.15 ms | 0.47 ms | 0.88 ms | 15.8 ms |
| cs mini | yes | 0.10 ms | 0.19 ms | 0.21 ms | 3.9 ms |
| sk maxi | yes | 0.09 ms | 0.47 ms | 1.3 ms | 2.5 ms |
| en top1m | yes | 0.08 ms | 1.5 ms | 16.6 ms | 81 ms (cold dirent reads) |
| cs mini | no (fallback listing) | 0.24 ms | 3.4 ms | 7.1 ms | 100 ms |

**Open + main page:** cs maxi 69 ms + 12 ms (incl. loading the 16 MB of index fst), en top1m 620 ms (76 MB of fst: attach big indexes off the UI thread or lazily).

**Index per book** (`offlinka-cli index`; "RAM" = fst maps kept in memory, the rest is read with `pread`):

| book | records | .offidx | RAM | build |
|---|---|---|---|---|
| wikipedia_cs_all_maxi | 975,172 | 34.7 MB | 16.0 MB | 19 s |
| wikipedia_cs_all_mini | 963,395 | 34.2 MB | 15.8 MB | 16 s |
| wikipedia_sk_all_maxi | 439,233 | 17.6 MB | 8.0 MB | 11 s |
| wikipedia_sk_all_mini | 438,360 | 17.6 MB | 8.0 MB | 6 s |
| wikipedia_en_top1m_nopic | 5,904,211 | 178.4 MB | 76.2 MB | 147 s (520 MB RSS) |
| wikipedia_en_medicine_nopic | 371,233 | 11.3 MB | 5.0 MB | 6 s |
| wikisource_cs | 65,396 | 5.8 MB | 3.3 MB | 2 s |
| wiktionary_cs | 164,473 | 3.5 MB | read whole (≤ 4 MB) | 1 s |
| wikiquote_cs | 16,833 | 0.66 MB | read whole | 1 s |
| wikiversity_cs | 6,611 | 0.55 MB | read whole | <1 s |
| wikivoyage_en_europe | 18,322 | 0.47 MB | read whole | <1 s |
| wikibooks_cs | 3,403 | 0.32 MB | read whole | <1 s |
| maps_en_czech-republic / slovakia | 8,632 / 4,091 | 0.22 / 0.10 MB | read whole | <1 s |
| offlinka_cs_prirucky / mul_hry | 6 / 11 | 1 KB | read whole | <1 s |

All indexes of the edition: ~305 MB on the drive (178 MB of it English).

## Licences

Our own code, no GPL/LGPL code (App Store safe). Dependencies and their licences: `LICENSES-THIRD-PARTY.md`.
