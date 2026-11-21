package cz.offlinka.app.ui

import cz.offlinka.ui.screens.Notice

/**
 * Open-source components shipped in the app. Rust side: core/LICENSES-THIRD-PARTY.md (keep in sync when
 * the core's dependencies change).
 */
object Notices {
    val all = listOf(
        Notice("Jetpack Compose, AndroidX (Activity, AppCompat, Core, Lifecycle, Navigation, Room, WebKit)", "Apache License 2.0"),
        Notice("Kotlin, kotlinx.coroutines", "Apache License 2.0"),
        Notice("JNA (Java Native Access)", "Apache License 2.0"),
        Notice("UniFFI (mozilla/uniffi-rs), runtime and generated bindings", "Mozilla Public License 2.0, github.com/mozilla/uniffi-rs"),
        Notice("fst, byteorder", "Unlicense / MIT"),
        Notice("Zstandard (libzstd, Meta), zstd-rs", "BSD 3-Clause License, github.com/facebook/zstd"),
        Notice("lzma-rs, lru", "MIT"),
        Notice("crc, memmap2, libc, thiserror, Rust standard library", "MIT / Apache License 2.0"),
        Notice("unicode-normalization", "MIT / Apache License 2.0, Unicode-3.0"),
        Notice("tinyvec", "Zlib / Apache License 2.0 / MIT"),
        Notice("Archivo (Omnibus-Type)", "SIL Open Font License 1.1"),
    )
}
