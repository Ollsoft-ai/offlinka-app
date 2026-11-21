//! Any bytes as a ZIM: every public operation and an index build. A panic the core
//! catches at its FFI boundary still counts as a crash here.
#![no_main]

use libfuzzer_sys::fuzz_target;
use offlinka_core::index::SearchIndex;
use offlinka_core::reader::MemSource;
use offlinka_core::{Book, Library};

fuzz_target!(|data: &[u8]| {
    let before = offlinka_core::caught_panics();
    if let Ok(b) = Book::open_source(Box::new(MemSource(data.to_vec())), "f.zim") {
        let _ = b.info();
        let _ = b.illustration(48);
        let a = b.archive();
        let paths: Vec<String> =
            (0..a.entry_count().min(32)).filter_map(|i| a.dirent(i).ok().map(|d| a.api_path(&d))).collect();
        for p in paths {
            let _ = b.get(p.clone());
            let _ = b.get_range(p.clone(), 1, 300);
            let _ = b.content_length(p);
        }
        for q in ["P", "pra", "Praha", "t"] {
            let _ = b.suggest(q.into(), 10);
        }
        let _ = b.random_article();
        let mut cur = std::io::Cursor::new(Vec::new());
        if offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).is_ok() {
            if let Ok(ix) = SearchIndex::from_vec(cur.into_inner()) {
                if b.attach_index(ix).is_ok() {
                    let _ = b.suggest("pra".into(), 10);
                }
            }
        }
        let lib = Library::new();
        lib.add(b.clone());
        let _ = lib.suggest_all("pra".into(), 10);
        b.close_file();
    }
    assert_eq!(offlinka_core::caught_panics(), before, "the core caught a panic (see above)");
});
