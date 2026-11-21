//! Tests on generated ZIM files: every compression type, extended offsets, redirects,
//! metadata, search index, corruption, threads, fd ownership.

mod common;

use std::sync::Arc;

use common::{sample, tmp_path, Comp, ZimBuilder};
use offlinka_core::reader::MemSource;
use offlinka_core::{Book, CoreError, Library};

fn open(bytes: Vec<u8>) -> Arc<Book> {
    Book::open_source(Box::new(MemSource(bytes)), "sample.zim").expect("open")
}

fn sample_book() -> Arc<Book> {
    open(sample().build())
}

fn write_sample(name: &str) -> String {
    let p = tmp_path(name);
    std::fs::write(&p, sample().build()).unwrap();
    p
}

fn indexed_book() -> Arc<Book> {
    let zim = write_sample("indexed.zim");
    let b = Book::open_path(zim.clone()).unwrap();
    let idx = format!("{zim}.offidx");
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    let mut f = std::fs::File::create(&idx).unwrap();
    offlinka_core::index::build(&a, &mut f, &mut |_: &str| {}).unwrap();
    b.attach_index_path(idx).unwrap();
    b
}

#[test]
fn version() {
    assert_eq!(offlinka_core::core_version(), env!("CARGO_PKG_VERSION"));
}

#[test]
fn info_and_metadata() {
    let b = sample_book();
    let i = b.info();
    assert_eq!(i.id, "wikipedia_cs_test");
    assert_eq!(i.name, "wikipedia_cs_test");
    assert_eq!(i.title, "Ukázková kniha");
    assert_eq!(i.description, "Testovací ZIM");
    assert_eq!(i.language, "ces");
    assert_eq!(i.creator, "Wikipedie");
    assert_eq!(i.publisher, "Offlinka");
    assert_eq!(i.date, "2026-11-21");
    assert_eq!(i.article_count, 9, "front articles from the v1 listing");
    assert_eq!(i.media_count, 2);
    assert_eq!(i.main_path.as_deref(), Some("Hlavní_strana"));
    assert!(!i.has_index);
    assert!(i.size_bytes > 0);
}

#[test]
fn main_page_from_header_when_no_well_known_entry() {
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::None, false);
    z.article("Start", "Start", "<p>hi</p>", c);
    z.main_page = Some((b'C', "Start".into()));
    let b = open(z.build());
    assert_eq!(b.info().main_path.as_deref(), Some("Start"));
    assert_eq!(b.info().id, "sample", "no Name metadata: file stem");
}

#[test]
fn illustration_sizes() {
    let b = sample_book();
    assert_eq!(b.illustration(48).unwrap(), b"\x89PNG48");
    assert_eq!(b.illustration(96).unwrap(), b"\x89PNG96");
    assert_eq!(b.illustration(64).unwrap(), b"\x89PNG96", "nearest bigger");
    assert_eq!(b.illustration(512).unwrap(), b"\x89PNG96", "largest");
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::None, false);
    z.article("A", "A", "a", c);
    assert!(open(z.build()).illustration(48).is_none());
}

#[test]
fn every_cluster_kind() {
    let b = sample_book();
    for (path, body) in [
        ("Brno", "<h1>Brno</h1>"),                   // none
        ("Český_Krumlov", "<h1>Český Krumlov</h1>"), // zstd
        ("Praha", "<h1>Praha</h1>"),                 // xz
        ("Pražský_hrad", "<h1>Hrad</h1>"),           // zstd, extended offsets
        ("Krumlovský_zámek", "<h1>Zámek</h1>"),      // none, extended offsets
    ] {
        let r = b.get(path.into()).unwrap();
        assert_eq!(r.data, body.as_bytes(), "{path}");
        assert_eq!(r.mime, "text/html");
        assert_eq!(r.path, path);
    }
    assert_eq!(b.get("style.css".into()).unwrap().mime, "text/css");
}

#[test]
fn redirects() {
    let b = sample_book();
    let r = b.get("Prag".into()).unwrap();
    assert_eq!(r.path, "Praha");
    assert_eq!(r.data, b"<h1>Praha</h1>");
    let r = b.get("Chain1".into()).unwrap();
    assert_eq!(r.path, "Brno", "two-step chain");
    assert!(matches!(b.get("Loop1".into()), Err(CoreError::InvalidZim(_))), "loop is an error, not a hang");
}

#[test]
fn long_redirect_chain_is_cut() {
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::None, false);
    z.article("End", "End", "end", c);
    for i in 0..12 {
        let to = if i == 11 { "End".to_string() } else { format!("R{:02}", i + 1) };
        z.redirect(b'C', &format!("R{i:02}"), "", b'C', &to);
    }
    let b = open(z.build());
    assert_eq!(b.get("R05".into()).unwrap().path, "End", "6 hops is fine");
    assert!(b.get("R00".into()).is_err(), "12 hops is more than 10");
}

#[test]
fn missing_entries() {
    let b = sample_book();
    assert!(matches!(b.get("Neexistuje".into()), Err(CoreError::NotFound(_))));
    assert!(matches!(b.content_length("Neexistuje".into()), Err(CoreError::NotFound(_))));
    assert!(matches!(b.get_range("Neexistuje".into(), 0, 10), Err(CoreError::NotFound(_))));
    assert!(matches!(b.get("".into()), Err(CoreError::NotFound(_))));
}

#[test]
fn namespace_prefixed_paths() {
    let b = sample_book();
    assert_eq!(b.get("M/Language".into()).unwrap().data, b"ces");
    assert_eq!(b.get("C/Brno".into()).unwrap().path, "Brno");
    assert_eq!(b.get("/Brno".into()).unwrap().path, "Brno");
}

#[test]
fn ranges() {
    let b = sample_book();
    let all: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
    assert_eq!(b.content_length("video.webm".into()).unwrap(), 200_000);
    assert_eq!(b.get_range("video.webm".into(), 0, 10).unwrap(), &all[..10]);
    assert_eq!(b.get_range("video.webm".into(), 123_456, 1000).unwrap(), &all[123_456..124_456]);
    assert_eq!(b.get_range("video.webm".into(), 199_990, 1000).unwrap(), &all[199_990..], "clamped");
    assert!(b.get_range("video.webm".into(), 200_000, 10).unwrap().is_empty());
    assert!(b.get_range("video.webm".into(), u64::MAX, 10).unwrap().is_empty());
    // compressed clusters too
    assert_eq!(b.content_length("Praha".into()).unwrap(), 14);
    assert_eq!(b.get_range("Praha".into(), 4, 5).unwrap(), b"Praha");
    assert_eq!(b.get_range("Prag".into(), 4, 5).unwrap(), b"Praha", "range through a redirect");
}

#[test]
fn fallback_suggest_without_index() {
    let b = sample_book();
    let s = b.suggest("Praha".into(), 10);
    assert_eq!(s[0].title, "Praha");
    assert!(s.iter().any(|x| x.title == "Praha (rozcestník)"));
    let s = b.suggest("praž".into(), 10);
    assert_eq!(s.first().map(|x| x.title.as_str()), Some("Pražský hrad"), "case-insensitive first letter");
    assert!(b.suggest("".into(), 10).is_empty());
    assert!(b.suggest("zzz".into(), 10).is_empty());
}

#[test]
fn indexed_suggest_ranking() {
    let b = indexed_book();
    assert!(b.info().has_index);
    // diacritics- and case-insensitive
    let s = b.suggest("cesky krumlov".into(), 10);
    assert_eq!(s[0].title, "Český Krumlov");
    assert_eq!(s[0].path, "Český_Krumlov");
    assert_eq!(s.iter().filter(|x| x.path == "Český_Krumlov").count(), 1, "redirect Cesky Krumlov deduped");
    // word prefix inside the title comes after title prefixes
    let s = b.suggest("krumlov".into(), 10);
    let titles: Vec<&str> = s.iter().map(|x| x.title.as_str()).collect();
    assert_eq!(titles[0], "Krumlov", "exact (redirect) first, shown under its own title");
    assert_eq!(s[0].path, "Český_Krumlov", "opens the target");
    assert_eq!(s[0].snippet.as_deref(), Some("Český Krumlov"));
    assert!(titles.contains(&"Krumlovský zámek"));
    assert!(titles.contains(&"Okres Český Krumlov"), "word prefix inside the title: {titles:?}");
    let pos = |t: &str| titles.iter().position(|x| *x == t).unwrap();
    assert!(pos("Krumlovský zámek") < pos("Okres Český Krumlov"), "prefix before word-prefix");
    // main articles over redirects, shorter first
    let s = b.suggest("pra".into(), 10);
    let titles: Vec<&str> = s.iter().map(|x| x.title.as_str()).collect();
    assert_eq!(titles[0], "Praha", "{titles:?}");
    assert!(!titles.contains(&"Prag"), "redirect to Praha deduped: {titles:?}");
    let s = b.suggest("hlavni mesto".into(), 10);
    assert_eq!(s[0].title, "Hlavní město Praha");
    assert_eq!(s[0].path, "Praha");
    let s = b.suggest("ZLUTOUCKY".into(), 10);
    assert_eq!(s[0].title, "Žluťoučký kůň");
    assert_eq!(b.suggest("kun".into(), 10)[0].title, "Žluťoučký kůň");
    assert_eq!(b.suggest("pra".into(), 2).len(), 2, "limit");
    assert!(b.suggest("xyz".into(), 10).is_empty());
    assert!(b.suggest("  ".into(), 10).is_empty());
}

#[test]
fn index_must_match_book() {
    let zim = write_sample("other.zim");
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    let idx = tmp_path("other.offidx");
    offlinka_core::index::build(&a, &mut std::fs::File::create(&idx).unwrap(), &mut |_: &str| {}).unwrap();
    let mut z = sample();
    z.uuid = *b"another-book-id!";
    let other = open(z.build());
    assert!(matches!(other.attach_index_path(idx), Err(CoreError::InvalidZim(_))));
    assert!(!other.info().has_index);
    let junk = tmp_path("junk.offidx");
    std::fs::write(&junk, b"not an index at all, sorry").unwrap();
    assert!(other.attach_index_path(junk).is_err());
    assert!(matches!(other.attach_index_path("/nonexistent/x.offidx".into()), Err(CoreError::Io(_))));
}

#[test]
fn random_article_is_html() {
    let b = sample_book();
    for _ in 0..20 {
        let p = b.random_article().unwrap();
        assert_eq!(b.get(p).unwrap().mime, "text/html");
    }
}

#[test]
fn library_merges_books() {
    let lib = Library::new();
    let mut other = sample();
    other.uuid = *b"second-book-uuid";
    other.entries.retain(|e| e.ns != b'M' || e.path != "Name");
    other.meta("Name", b"wikivoyage_test", 5);
    other.entries.retain(|e| e.ns != b'M' || e.path != "Language");
    other.meta("Language", b"eng", 5);
    let voyage = Book::open_source(Box::new(MemSource(other.build())), "voyage.zim").unwrap();
    lib.add(voyage);
    lib.add(sample_book());
    assert_eq!(lib.books().len(), 2);
    let s = lib.suggest_all("Praha".into(), 10);
    assert_eq!(s[0].book_id, "wikipedia_cs_test", "Czech Wikipedia first although added second");
    assert_eq!(s[0].title, "Praha");
    assert_eq!(s[1].book_id, "wikivoyage_test");
    assert_eq!(s[1].title, "Praha");
    assert!(s.len() <= 10);
    assert_eq!(lib.suggest_all("Praha".into(), 1).len(), 1);
}

#[test]
fn library_keeps_the_bigger_edition() {
    let lib = Library::new();
    let mut mini = ZimBuilder::new();
    let c = mini.cluster(Comp::None, false);
    mini.article("A", "A", "a", c).meta("Name", b"wikipedia_cs_all", c);
    let mini = open(mini.build());
    lib.add(mini.clone());
    let mut maxi = sample();
    maxi.entries.retain(|e| e.ns != b'M' || e.path != "Name");
    maxi.meta("Name", b"wikipedia_cs_all", 5);
    let maxi = open(maxi.build());
    lib.add(maxi.clone());
    lib.add(mini);
    assert_eq!(lib.books().len(), 1);
    assert_eq!(lib.books()[0].info().article_count, maxi.info().article_count);
}

#[test]
fn not_a_zim() {
    assert!(matches!(Book::open_source(Box::new(MemSource(vec![0; 10])), "x"), Err(CoreError::InvalidZim(_))));
    assert!(matches!(Book::open_source(Box::new(MemSource(vec![0; 200])), "x"), Err(CoreError::InvalidZim(_))));
    assert!(matches!(Book::open_path("/nonexistent/book.zim".into()), Err(CoreError::Io(_))));
}

#[test]
fn truncated_files_error_without_panic() {
    let full = sample().build();
    for cut in [80, 100, full.len() / 3, full.len() / 2, full.len() - 17, full.len() - 1] {
        let r = Book::open_source(Box::new(MemSource(full[..cut].to_vec())), "t.zim");
        assert!(r.is_err(), "cut at {cut} must fail cleanly");
    }
}

#[test]
fn corrupt_bytes_never_panic() {
    let full = sample().build();
    let paths = ["Brno", "Praha", "Český_Krumlov", "Pražský_hrad", "video.webm", "Prag", "Krumlovský_zámek"];
    let mut seed = 0x9e3779b97f4a7c15u64;
    for round in 0..400 {
        let mut bytes = full.clone();
        for _ in 0..(1 + round % 8) {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            let pos = (seed % bytes.len() as u64) as usize;
            bytes[pos] = (seed >> 32) as u8;
        }
        if let Ok(b) = Book::open_source(Box::new(MemSource(bytes)), "c.zim") {
            let _ = b.info();
            let _ = b.illustration(48);
            for p in paths {
                let _ = b.get(p.into());
                let _ = b.get_range(p.into(), 3, 100);
                let _ = b.content_length(p.into());
            }
            let _ = b.suggest("pra".into(), 5);
            let _ = b.random_article();
        }
    }
}

#[test]
fn concurrent_reads() {
    let b = indexed_book();
    let mut handles = Vec::new();
    for t in 0..8 {
        let b = b.clone();
        handles.push(std::thread::spawn(move || {
            for i in 0..200 {
                let p = ["Brno", "Praha", "Český_Krumlov", "Pražský_hrad", "Prag"][(t + i) % 5];
                let r = b.get(p.into()).unwrap();
                assert!(r.data.starts_with(b"<h1>"));
                assert_eq!(b.get_range("video.webm".into(), (i * 97) as u64, 64).unwrap().len(), 64);
                assert!(!b.suggest("kr".into(), 5).is_empty());
            }
        }));
    }
    for h in handles {
        h.join().unwrap();
    }
}

/// Is `fd` open on `path`? (Other tests run in parallel and may reuse the number.)
#[cfg(target_os = "linux")]
fn fd_on(fd: i32, path: &str) -> bool {
    std::fs::read_link(format!("/proc/self/fd/{fd}")).is_ok_and(|p| p == std::path::Path::new(path))
}

#[cfg(target_os = "linux")]
#[test]
fn fd_ownership() {
    use std::os::unix::io::IntoRawFd;
    let zim = write_sample("fd.zim");
    let fd = std::fs::File::open(&zim).unwrap().into_raw_fd();
    let b = Book::open_fd(fd, "fd.zim".into()).unwrap();
    assert!(fd_on(fd, &zim));
    assert_eq!(b.get("Brno".into()).unwrap().data, b"<h1>Brno</h1>");
    drop(b);
    assert!(!fd_on(fd, &zim), "the core closes the fd when the book is dropped");

    // A failed open closes the fd as well.
    let junk = tmp_path("junk.bin");
    std::fs::write(&junk, b"definitely not a zim").unwrap();
    let fd = std::fs::File::open(&junk).unwrap().into_raw_fd();
    assert!(Book::open_fd(fd, "junk".into()).is_err());
    assert!(!fd_on(fd, &junk), "fd closed after a failed open");

    assert!(matches!(Book::open_fd(-1, "bad".into()), Err(CoreError::Io(_))));

    // Index by fd.
    let b = Book::open_path(zim.clone()).unwrap();
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    let idx = tmp_path("fd.offidx");
    offlinka_core::index::build(&a, &mut std::fs::File::create(&idx).unwrap(), &mut |_: &str| {}).unwrap();
    let ifd = std::fs::File::open(&idx).unwrap().into_raw_fd();
    b.attach_index_fd(ifd).unwrap();
    assert!(!fd_on(ifd, &idx), "a small index is read whole and its fd closed right away");
    assert!(b.info().has_index);
    assert_eq!(b.suggest("cesky".into(), 3)[0].title, "Český Krumlov");
}

#[test]
fn index_read_through_pread_matches_memory() {
    use offlinka_core::index::SearchIndex;
    use offlinka_core::reader::FileSource;
    let zim = write_sample("pread.zim");
    let a = offlinka_core::zim::Archive::open(Box::new(FileSource::open(&zim).unwrap())).unwrap();
    let idx = tmp_path("pread.offidx");
    offlinka_core::index::build(&a, &mut std::fs::File::create(&idx).unwrap(), &mut |_: &str| {}).unwrap();
    let mem = SearchIndex::from_vec(std::fs::read(&idx).unwrap()).unwrap();
    let file = SearchIndex::open_with_limit(FileSource::open(&idx).unwrap(), 0).unwrap();
    assert!(file.memory_bytes() < file.byte_len);
    for q in ["cesky", "cesky krumlov", "krumlov", "pra", "p", "k", "kun", "zzz", "hlavni mesto praha"] {
        assert_eq!(mem.search(q, 10).unwrap(), file.search(q, 10).unwrap(), "{q}");
    }
    for id in 0..mem.record_count {
        assert_eq!(mem.record(id).unwrap(), file.record(id).unwrap());
    }
    assert!(file.record(mem.record_count).is_err());
    // A truncated index is refused, not read past its end.
    let bytes = std::fs::read(&idx).unwrap();
    for cut in [10, 100, bytes.len() / 2, bytes.len() - 1] {
        assert!(SearchIndex::from_vec(bytes[..cut].to_vec()).is_err(), "cut {cut}");
    }
}

#[test]
fn unsupported_things() {
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::Zlib, false);
    let ok = z.cluster(Comp::None, false);
    z.article("Old", "Old", "zlib", c).article("New", "New", "fine", ok);
    let b = open(z.build());
    assert!(matches!(b.get("Old".into()), Err(CoreError::Unsupported(_))));
    assert_eq!(b.get("New".into()).unwrap().data, b"fine");
    // Unknown major version.
    let mut bytes = sample().build();
    bytes[4] = 9;
    assert!(matches!(Book::open_source(Box::new(MemSource(bytes)), "v9.zim"), Err(CoreError::Unsupported(_))));
}

#[test]
fn library_edge_cases() {
    let lib = Library::new();
    assert!(lib.suggest_all("praha".into(), 10).is_empty(), "empty library");
    lib.add(indexed_book());
    let mut other = sample();
    other.uuid = *b"fallback-book-01";
    other.entries.retain(|e| e.ns != b'M' || e.path != "Name");
    other.meta("Name", b"wikiquote_cs", 5);
    lib.add(open(other.build())); // no index: fallback listing
    let s = lib.suggest_all("cesky krumlov".into(), 10);
    assert_eq!(s[0].book_id, "wikipedia_cs_test");
    assert_eq!(s[0].title, "Český Krumlov");
    let s = lib.suggest_all("Praha".into(), 10);
    let books: Vec<&str> = s.iter().map(|x| x.book_id.as_str()).collect();
    assert!(books.contains(&"wikiquote_cs"), "fallback book merged: {books:?}");
    assert!(lib.suggest_all("".into(), 10).is_empty());
    assert!(lib.suggest_all("praha".into(), 0).is_empty());
}

#[test]
fn reattaching_an_index_replaces_it() {
    let b = indexed_book();
    let zim = write_sample("re.zim");
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    let idx = tmp_path("re.offidx");
    offlinka_core::index::build(&a, &mut std::fs::File::create(&idx).unwrap(), &mut |_: &str| {}).unwrap();
    b.attach_index_path(idx).unwrap();
    assert!(b.info().has_index);
    assert_eq!(b.suggest("brno".into(), 3)[0].title, "Brno");
}

#[test]
fn heavy_prefixes_rank_like_a_full_scan() {
    // > HEAVY_THRESHOLD keys under "test", so short prefixes use precomputed top lists.
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::Zstd, false);
    for i in 0..3000 {
        let t = format!("Test {i:04}");
        z.article(&t.replace(' ', "_"), &t, "<p>x</p>", c);
        let w = format!("Okres Test {i:04} Sever");
        z.article(&w.replace(' ', "_"), &w, "<p>x</p>", c);
    }
    z.article("Te", "Te", "<p>x</p>", c).article("Tešín", "Tešín", "<p>x</p>", c);
    z.article("!!!_(Tešín)", "!!! (Tešín)", "<p>x</p>", c); // normalises like "Tešín"
    z.redirect(b'C', "Tes", "Tes", b'C', "Test_0007");
    z.uuid = *b"heavy-prefix-zim";
    let zim = tmp_path("heavy.zim");
    std::fs::write(&zim, z.build()).unwrap();
    let b = Book::open_path(zim.clone()).unwrap();
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    let idx = tmp_path("heavy.offidx");
    let st = offlinka_core::index::build(&a, &mut std::fs::File::create(&idx).unwrap(), &mut |_: &str| {}).unwrap();
    assert!(st.heavy_prefixes > 0, "{st:?}");
    b.attach_index_path(idx).unwrap();
    let titles = |q: &str, n: u32| b.suggest(q.into(), n).into_iter().map(|s| s.title).collect::<Vec<_>>();
    // Equal sizes: shorter first; "Test 0007" has a redirect pointing at it ("Tes"), which
    // counts as popularity and lifts it above the other "Test" pages.
    assert_eq!(titles("t", 5), ["Te", "Test 0007", "Tešín", "!!! (Tešín)", "Test 0000"]);
    assert_eq!(titles("te", 4), ["Te", "Test 0007", "Tešín", "!!! (Tešín)"], "same key: shorter written title first");
    assert_eq!(titles("tesin", 2), ["Tešín", "!!! (Tešín)"]);
    assert_eq!(titles("tes", 4), ["Tes", "Tešín", "!!! (Tešín)", "Test 0000"], "exact redirect first");
    assert_eq!(b.suggest("tes".into(), 1)[0].path, "Test_0007");
    assert_eq!(titles("test 1", 2), ["Test 1000", "Test 1001"]);
    assert_eq!(titles("test 2999", 5), ["Test 2999", "Okres Test 2999 Sever"]);
    // word-suffix prefixes are heavy too ("test …" inside "Okres Test …")
    let s = titles("test 129", 20);
    assert_eq!(s[..10], (1290..1300).map(|i| format!("Test {i}")).collect::<Vec<_>>()[..]);
    assert_eq!(s[10..], (1290..1300).map(|i| format!("Okres Test {i} Sever")).collect::<Vec<_>>()[..]);
    assert_eq!(titles("sever", 1), ["Okres Test 0000 Sever"]);
}

struct Listener {
    fractions: std::sync::Mutex<Vec<f64>>,
    cancel_at: f64,
}

impl offlinka_core::IndexBuildListener for Listener {
    fn on_progress(&self, fraction: f64) {
        self.fractions.lock().unwrap().push(fraction);
    }
    fn is_cancelled(&self) -> bool {
        self.fractions.lock().unwrap().last().is_some_and(|&f| f >= self.cancel_at)
    }
}

#[test]
fn on_device_build_matches_cli_bytes() {
    let zim = write_sample("ondevice.zim");
    let book = Book::open_path(zim.clone()).unwrap();
    // What offlinka-cli index writes (same function the CLI calls).
    let cli_out = tmp_path("cli.offidx");
    let a =
        offlinka_core::zim::Archive::open(Box::new(offlinka_core::reader::FileSource::open(&zim).unwrap())).unwrap();
    offlinka_core::write_index_file(&a, &cli_out, &mut |_: &str| {}).unwrap();
    // What the apps call.
    let app_out = tmp_path("app.offidx");
    let l = Box::new(Listener { fractions: Default::default(), cancel_at: 2.0 });
    let fr = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    struct Fwd(std::sync::Arc<std::sync::Mutex<Vec<f64>>>, Box<Listener>);
    impl offlinka_core::IndexBuildListener for Fwd {
        fn on_progress(&self, f: f64) {
            self.0.lock().unwrap().push(f);
            self.1.on_progress(f);
        }
        fn is_cancelled(&self) -> bool {
            self.1.is_cancelled()
        }
    }
    offlinka_core::build_index(book.clone(), app_out.clone(), Box::new(Fwd(fr.clone(), l))).unwrap();
    assert_eq!(std::fs::read(&cli_out).unwrap(), std::fs::read(&app_out).unwrap(), "byte-identical");
    let fr = fr.lock().unwrap().clone();
    assert!(fr.windows(2).all(|w| w[1] >= w[0]), "progress increases: {fr:?}");
    assert_eq!(fr.first().copied(), Some(0.0));
    assert_eq!(fr.last().copied(), Some(1.0));
    assert!(!std::path::Path::new(&format!("{app_out}.part")).exists());
    book.attach_index_path(app_out).unwrap();
    assert_eq!(book.suggest("cesky krumlov".into(), 1)[0].title, "Český Krumlov");
}

#[test]
fn on_device_build_can_be_cancelled() {
    let zim = write_sample("cancel.zim");
    let book = Book::open_path(zim).unwrap();
    let out = tmp_path("cancel.offidx");
    let r = offlinka_core::build_index(
        book.clone(),
        out.clone(),
        Box::new(Listener { fractions: Default::default(), cancel_at: 0.0 }),
    );
    assert!(matches!(r, Err(CoreError::Cancelled(_))), "{r:?}");
    assert!(!std::path::Path::new(&out).exists());
    assert!(!std::path::Path::new(&format!("{out}.part")).exists(), "no partial file left");
    // Unwritable target: an I/O error, nothing left.
    let r = offlinka_core::build_index(
        book,
        "/nonexistent-dir/x.offidx".into(),
        Box::new(Listener { fractions: Default::default(), cancel_at: 2.0 }),
    );
    assert!(matches!(r, Err(CoreError::Io(_))), "{r:?}");
}

#[test]
fn closed_book_gives_io_errors_never_panics() {
    let b = indexed_book();
    let info = b.info();
    let mut handles = Vec::new();
    for t in 0..6 {
        let b = b.clone();
        handles.push(std::thread::spawn(move || {
            for i in 0..300 {
                let p = ["Brno", "Praha", "Prag", "video.webm"][(t + i) % 4];
                match b.get(p.into()) {
                    Ok(r) => assert!(!r.data.is_empty()),
                    Err(CoreError::Io(_)) => {}
                    Err(e) => panic!("unexpected error after close: {e:?}"),
                }
                let _ = b.get_range("video.webm".into(), 10, 100);
                let _ = b.suggest("kr".into(), 5);
                let _ = b.info();
            }
        }));
    }
    std::thread::sleep(std::time::Duration::from_millis(5));
    b.close_file();
    for h in handles {
        h.join().unwrap();
    }
    assert!(matches!(b.get("Brno".into()), Err(CoreError::Io(_))));
    assert!(matches!(b.get_range("video.webm".into(), 0, 10), Err(CoreError::Io(_))));
    assert!(matches!(b.content_length("Brno".into()), Err(CoreError::Io(_))));
    assert!(b.suggest("praha".into(), 5).is_empty());
    assert_eq!(b.info().title, info.title, "info keeps its cached value");
    assert!(!b.info().has_index);
    b.close_file(); // twice is fine
}

#[test]
fn trim_memory_and_shared_budget() {
    let b = indexed_book();
    assert_eq!(b.get("Praha".into()).unwrap().data, b"<h1>Praha</h1>");
    b.preload_index().unwrap();
    offlinka_core::trim_memory();
    assert_eq!(offlinka_core::zim::cluster_cache_bytes(), 0);
    assert_eq!(b.suggest("cesky krumlov".into(), 1)[0].title, "Český Krumlov", "index reloads after trim");
    assert_eq!(b.get("Praha".into()).unwrap().data, b"<h1>Praha</h1>");
}

#[test]
fn opening_reads_only_the_header() {
    // A source that counts reads: opening must not depend on the entry count.
    struct Counting(MemSource, std::sync::Arc<std::sync::atomic::AtomicUsize>);
    impl offlinka_core::reader::ReadAt for Counting {
        fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> std::io::Result<()> {
            self.1.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            self.0.read_exact_at(buf, offset)
        }
        fn size(&self) -> u64 {
            self.0.size()
        }
    }
    let mut z = sample();
    let c = z.cluster(Comp::Zstd, false);
    for i in 0..5000 {
        z.article(&format!("Clanek_{i}"), &format!("Článek {i}"), "<p>x</p>", c);
    }
    let reads = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let b = Book::open_source(Box::new(Counting(MemSource(z.build()), reads.clone())), "big.zim").unwrap();
    assert!(reads.load(std::sync::atomic::Ordering::Relaxed) <= 2, "open = header + MIME list");
    let info = b.info();
    let n = reads.load(std::sync::atomic::Ordering::Relaxed);
    assert_eq!(info.title, "Ukázková kniha");
    assert_eq!(info.main_path.as_deref(), Some("Hlavní_strana"));
    assert!(n <= 24, "info() = a handful of reads, not a binary search per field: {n}");
}
