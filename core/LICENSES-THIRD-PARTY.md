# Third-party licences of offlinka-core

**No GPL/LGPL code.** Everything compiled into `libofflinka_core` is MIT, Apache-2.0, BSD, Unlicense, Zlib or Unicode-3.0, except the UniFFI runtime (**MPL-2.0**, file-level copyleft: fine in App Store and Play apps; we do not modify its files; name it with a link to its source in About → licences).

Regenerate after a dependency change (build-time `cc`, `jobserver`, `shlex` compile libzstd and are not shipped): `cargo tree -p offlinka-core -e normal,no-proc-macro --target aarch64-linux-android --prefix none -f "{p}|{l}|{r}" | sort -u`.

## Compiled into the app library

- `fst` 0.4.7 — Unlicense OR MIT — https://github.com/BurntSushi/fst (search index maps)
- **libzstd 1.5.7** (C, Meta) — **BSD-3-Clause** (dual-licensed BSD-3-Clause OR GPL-2.0-only; we use it under BSD-3-Clause) — https://github.com/facebook/zstd — vendored and compiled by `zstd-sys`; zstd clusters (default feature `zstd-c`). Notice text: keep the zstd LICENSE (BSD) in About → licences.
- `zstd` 0.14.4, `zstd-safe` 8.3.0 — BSD-3-Clause; `zstd-sys` 2.1.3 — BSD-3-Clause AND BSD-2-Clause — https://github.com/gyscos/zstd-rs
- `ruzstd` 0.9.1 — MIT — https://github.com/KillingSpark/zstd-rs — only with feature `zstd-pure` (pure-Rust zstd, 3–20x slower on our clusters) and in tests to write fixtures
- `lzma-rs` 0.3.0 — MIT — https://github.com/gendx/lzma-rs (xz clusters, pure Rust)
- `crc` 3.4.0, `crc-catalog` 2.5.0 — MIT OR Apache-2.0 — https://github.com/mrhooray/crc-rs (via lzma-rs)
- `byteorder` 1.5.0 — Unlicense OR MIT — https://github.com/BurntSushi/byteorder
- `lru` 0.19.1 — MIT — https://github.com/jeromefroe/lru-rs (cluster and dirent caches)
- `libc` 0.2.191 — MIT OR Apache-2.0 — https://github.com/rust-lang/libc
- `unicode-normalization` 0.1.26 — (MIT OR Apache-2.0) AND Unicode-3.0 — https://github.com/unicode-rs/unicode-normalization
- `tinyvec` 1.13.3 — Zlib OR Apache-2.0 OR MIT — https://github.com/Lokathor/tinyvec
- `thiserror` 2.0.21 — MIT OR Apache-2.0 — https://github.com/dtolnay/thiserror
- `uniffi` / `uniffi_core` / `uniffi_pipeline` 0.32.2 — **MPL-2.0** — https://github.com/mozilla/uniffi-rs (FFI runtime)
- `anyhow` 1.0.104, `bytes` 1.12.2 (MIT), `static_assertions` 1.1.0, `once_cell` 1.21.4, `cfg-if` 1.0.5, `bitflags` 2.13.2, `hashbrown` 0.17.1, `indexmap` 2.14.2, `equivalent` 1.0.3, `heck` 0.5.0, `tempfile` 3.27.0, `fastrand` 2.5.0, `getrandom` 0.4.3, `rustix` 1.1.6, `linux-raw-sys` 0.12.1, `errno` 0.3.15 — MIT and/or Apache-2.0 (rustix, linux-raw-sys: Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT) — dependencies of uniffi; most are dropped by the linker.
- Rust standard library — MIT OR Apache-2.0.

## Build-time only (not shipped)

- Proc macros: `uniffi_macros`, `uniffi_internal_macros`, `thiserror-impl`, `serde_derive`, `syn`, `quote`, `proc-macro2`, `unicode-ident`, `toml*`, `winnow`, `serde*` — MIT/Apache-2.0, MPL-2.0 (uniffi), Unicode-3.0.
- `uniffi-bindgen` tool (`core/uniffi-bindgen/`, uniffi with `cli`): `uniffi_bindgen`, `uniffi_udl`, `weedle2`, `askama`, `clap`, `cargo_metadata`, `goblin`, `camino`, … — MPL-2.0 (uniffi) and MIT/Apache-2.0. Generates the Kotlin and Swift binding sources.
- The generated Kotlin/Swift binding files are produced from UniFFI templates (MPL-2.0); the Kotlin bindings load the library through **JNA** (Apache-2.0 OR LGPL-2.1, used under Apache-2.0), added on the Android side.
