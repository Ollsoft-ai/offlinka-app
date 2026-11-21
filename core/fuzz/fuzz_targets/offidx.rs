//! Any bytes as the `.offidx` of a fixed small book (`corpus/book.zim`, written by
//! `cargo test --test fuzz -- --ignored write_fuzz_corpus`). The book's UUID and entry
//! count are patched in so the fuzzer explores past the "other book" check.
#![no_main]

use libfuzzer_sys::fuzz_target;
use offlinka_core::index::SearchIndex;
use offlinka_core::reader::MemSource;
use offlinka_core::{Book, Library};

static ZIM: &[u8] = include_bytes!("../corpus/book.zim");

fuzz_target!(|data: &[u8]| {
    let before = offlinka_core::caught_panics();
    let mut idx = data.to_vec();
    if idx.len() >= 36 {
        idx[16..32].copy_from_slice(&ZIM[8..24]);
        idx[32..36].copy_from_slice(&ZIM[24..28]);
    }
    let b = Book::open_source(Box::new(MemSource(ZIM.to_vec())), "book.zim").unwrap();
    for ix in [SearchIndex::from_vec(idx.clone()), SearchIndex::from_reader(Box::new(MemSource(idx)))] {
        let Ok(ix) = ix else { continue };
        if b.attach_index(ix).is_ok() {
            let _ = b.preload_index();
            for q in ["t", "te", "test 1", "pra", "cesky krumlov", "x"] {
                let _ = b.suggest(q.into(), 20);
            }
            let lib = Library::new();
            lib.add(b.clone());
            let _ = lib.suggest_all("test".into(), 20);
        }
    }
    assert_eq!(offlinka_core::caught_panics(), before, "the core caught a panic (see above)");
});
