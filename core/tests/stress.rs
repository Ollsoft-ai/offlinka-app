//! Concurrency and unplug races: reads that hang on a dying drive must not block
//! `close_file` or `trim_memory`; many threads read one book while the cache budget
//! changes and memory is trimmed; `close_file` and `build_index` race with readers;
//! two builds of one index path never expose a half-written file. Also runs under
//! ThreadSanitizer and (the `miri_` test, small) under Miri: see core/README.md.

mod common;
#[path = "common/track.rs"]
mod track;

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::SeqCst};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use common::{sample, tmp_path};
use offlinka_core::index::SearchIndex;
use offlinka_core::reader::{MemSource, ReadAt};
use offlinka_core::{Book, CoreError, Library};
use track::guarded;

fn drive() -> String {
    std::env::var("OFFLINKA_DRIVE").unwrap_or_else(|_| "/srv/offlinka/drive/release/2026-11.3/files".into())
}

/// A private copy of a small drive ZIM (own path, so fd checks see only this test).
fn real_copy(rel: &str, name: &str) -> Option<String> {
    let p = format!("{}/{rel}", drive());
    if !std::path::Path::new(&p).exists() {
        eprintln!("skipping: {p} not found");
        return None;
    }
    let dst = tmp_path(name);
    std::fs::copy(p, &dst).unwrap();
    Some(dst)
}

/// A source whose reads hang while the gate is shut, like a pread stuck in USB retries.
#[derive(Default)]
struct Gate {
    shut: Mutex<bool>,
    cv: Condvar,
    waiting: AtomicU64,
}

impl Gate {
    fn shut(&self) {
        *self.shut.lock().unwrap() = true;
    }
    fn open(&self) {
        *self.shut.lock().unwrap() = false;
        self.cv.notify_all();
    }
    fn pass(&self) {
        let mut g = self.shut.lock().unwrap();
        if *g {
            self.waiting.fetch_add(1, SeqCst);
            while *g {
                g = self.cv.wait(g).unwrap();
            }
        }
    }
    fn wait_for_hung_read(&self) {
        let t = Instant::now();
        while self.waiting.load(SeqCst) == 0 {
            assert!(t.elapsed() < Duration::from_secs(10), "no read reached the gate");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

struct Hanging {
    data: MemSource,
    gate: Arc<Gate>,
    dropped: Arc<AtomicBool>,
}

impl ReadAt for Hanging {
    fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> std::io::Result<()> {
        self.gate.pass();
        self.data.read_exact_at(buf, offset)
    }
    fn size(&self) -> u64 {
        self.data.size()
    }
}

impl Drop for Hanging {
    fn drop(&mut self) {
        self.dropped.store(true, SeqCst);
    }
}

fn hanging(bytes: Vec<u8>) -> (Hanging, Arc<Gate>, Arc<AtomicBool>) {
    let gate = Arc::new(Gate::default());
    let dropped = Arc::new(AtomicBool::new(false));
    (Hanging { data: MemSource(bytes), gate: gate.clone(), dropped: dropped.clone() }, gate, dropped)
}

/// `f` must return within 2 s (it may not wait for the hung read).
fn quick(what: &str, f: impl FnOnce() + Send + 'static) {
    let t = Instant::now();
    guarded(what, 10, f).clean(what, usize::MAX);
    assert!(t.elapsed() < Duration::from_secs(2), "{what} took {:?}: it waited for a hung read", t.elapsed());
}

fn index_bytes(b: &Book) -> Vec<u8> {
    let mut cur = std::io::Cursor::new(Vec::new());
    offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
    cur.into_inner()
}

#[test]
fn close_file_returns_while_a_read_hangs() {
    let (src, gate, dropped) = hanging(sample().build());
    let b = Book::open_source(Box::new(src), "hang.zim").unwrap();
    let _ = b.info();
    gate.shut();
    let reader = {
        let b = b.clone();
        std::thread::spawn(move || b.get("Praha".into()).map(|r| r.data))
    };
    gate.wait_for_hung_read();
    let b2 = b.clone();
    quick("close_file", move || b2.close_file());
    // New calls fail at once although the drive still hangs.
    let b3 = b.clone();
    quick("get after close", move || assert!(matches!(b3.get("Brno".into()), Err(CoreError::Io(_)))));
    let b4 = b.clone();
    quick("trim_memory + info + suggest after close", move || {
        offlinka_core::trim_memory();
        offlinka_core::set_cluster_cache_budget(48 << 20);
        let _ = b4.info();
        assert!(b4.suggest("Praha".into(), 5).is_empty());
    });
    assert!(!dropped.load(SeqCst), "the source is still in use by the hung read");
    gate.open();
    match reader.join().unwrap() {
        Ok(d) => assert_eq!(d, b"<h1>Praha</h1>"),
        Err(e) => assert!(matches!(e, CoreError::Io(_)), "{e:?}"),
    }
    assert!(dropped.load(SeqCst), "the file is released when the last read in flight ends");
}

#[test]
fn trim_memory_and_close_return_while_an_index_loads() {
    let book = Book::open_source(Box::new(MemSource(sample().build())), "s.zim").unwrap();
    let (src, gate, dropped) = hanging(index_bytes(&book));
    let idx = SearchIndex::from_reader(Box::new(src)).unwrap(); // header read, gate open
    book.attach_index(idx).unwrap();
    let idx = book.search_index().unwrap();
    gate.shut();
    let loader = {
        let b = book.clone();
        std::thread::spawn(move || b.preload_index())
    };
    gate.wait_for_hung_read();
    quick("trim_memory during a preload", offlinka_core::trim_memory);
    let b = book.clone();
    quick("close_file during a preload", move || b.close_file());
    gate.open();
    assert!(loader.join().unwrap().is_ok());
    assert!(!idx.is_loaded(), "a load overtaken by trim_memory is not kept");
    drop(idx);
    assert!(dropped.load(SeqCst), "index file released once the preload finished");
}

#[test]
fn trim_memory_returns_while_a_suggest_loads_the_index() {
    let book = Book::open_source(Box::new(MemSource(sample().build())), "s.zim").unwrap();
    let (src, gate, _dropped) = hanging(index_bytes(&book));
    book.attach_index(SearchIndex::from_reader(Box::new(src)).unwrap()).unwrap();
    gate.shut();
    let searcher = {
        let b = book.clone();
        std::thread::spawn(move || b.suggest("cesky krumlov".into(), 3))
    };
    gate.wait_for_hung_read();
    quick("trim_memory during the first suggest", offlinka_core::trim_memory);
    gate.open();
    assert_eq!(searcher.join().unwrap()[0].title, "Český Krumlov");
    assert_eq!(book.suggest("cesky krumlov".into(), 3)[0].title, "Český Krumlov", "reloads after trim");
}

/// Readers check everything they get while the budget swings from 0 to 48 MB, memory is
/// trimmed, the index is preloaded, unloaded and re-attached.
#[test]
fn many_readers_while_budget_and_trim_churn() {
    let bytes = sample().build();
    let book = Book::open_source(Box::new(MemSource(bytes)), "s.zim").unwrap();
    let ix = index_bytes(&book);
    book.attach_index(SearchIndex::from_vec(ix.clone()).unwrap()).unwrap();
    let expect: Vec<(&str, &[u8])> = vec![
        ("Brno", b"<h1>Brno</h1>"),
        ("Praha", b"<h1>Praha</h1>"),
        ("Český_Krumlov", "<h1>Český Krumlov</h1>".as_bytes()),
        ("Pražský_hrad", b"<h1>Hrad</h1>"),
        ("Prag", b"<h1>Praha</h1>"),
    ];
    let video: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
    let stop = Arc::new(AtomicBool::new(false));
    let iters = if cfg!(miri) { 3 } else { 400 };
    let readers = if cfg!(miri) { 2 } else { 8 };
    let churn = {
        let (b, stop, ix) = (book.clone(), stop.clone(), ix.clone());
        std::thread::spawn(move || {
            let mut i = 0u64;
            while !stop.load(SeqCst) {
                offlinka_core::set_cluster_cache_budget([0, 1, 4096, 64 << 10, 1 << 20, 48 << 20][i as usize % 6]);
                if i % 3 == 0 {
                    offlinka_core::trim_memory();
                }
                if i % 5 == 0 {
                    let _ = b.preload_index();
                }
                if i % 7 == 0 {
                    b.attach_index(SearchIndex::from_vec(ix.clone()).unwrap()).unwrap();
                }
                i += 1;
                std::thread::yield_now();
            }
            offlinka_core::set_cluster_cache_budget(48 << 20);
        })
    };
    let mut hs = Vec::new();
    for t in 0..readers {
        let (b, expect, video) = (book.clone(), expect.clone(), video.clone());
        hs.push(std::thread::spawn(move || {
            for i in 0..iters {
                let (p, body) = expect[(t + i) % expect.len()];
                assert_eq!(b.get(p.into()).unwrap().data, body, "{p}");
                let off = (i * 977) % 199_000;
                assert_eq!(b.get_range("video.webm".into(), off as u64, 500).unwrap(), &video[off..off + 500]);
                assert_eq!(b.suggest("cesky krumlov".into(), 3)[0].title, "Český Krumlov");
                assert_eq!(b.suggest("krumlov".into(), 10)[0].path, "Český_Krumlov");
                assert_eq!(b.info().id, "wikipedia_cs_test");
            }
        }));
    }
    let t = Instant::now();
    for h in hs {
        h.join().unwrap();
    }
    stop.store(true, SeqCst);
    churn.join().unwrap();
    assert!(t.elapsed() < Duration::from_secs(if cfg!(miri) { 3600 } else { 120 }));
    assert!(offlinka_core::zim::cluster_cache_bytes() <= 48 << 20);
}

/// The Miri run: the same races, tiny (`cargo +nightly miri test --no-default-features
/// --features zstd-pure --test stress miri_`).
#[test]
fn miri_close_and_trim_race_reads() {
    let book = Book::open_source(Box::new(MemSource(sample().build())), "m.zim").unwrap();
    let n = if cfg!(miri) { 2 } else { 50 };
    let mut hs = Vec::new();
    for t in 0..3 {
        let b = book.clone();
        hs.push(std::thread::spawn(move || {
            for i in 0..n {
                match b.get(["Brno", "Praha", "Prag"][(t + i) % 3].into()) {
                    Ok(r) => assert!(r.data.starts_with(b"<h1>")),
                    Err(e) => assert!(matches!(e, CoreError::Io(_)), "{e:?}"),
                }
                let _ = b.suggest("Pra".into(), 3);
            }
        }));
    }
    offlinka_core::set_cluster_cache_budget(1);
    offlinka_core::trim_memory();
    offlinka_core::set_cluster_cache_budget(48 << 20);
    book.close_file();
    for h in hs {
        h.join().unwrap();
    }
    assert!(matches!(book.get("Brno".into()), Err(CoreError::Io(_))));
}

#[cfg(target_os = "linux")]
fn fds_on(path: &str) -> usize {
    std::fs::read_dir("/proc/self/fd")
        .unwrap()
        .filter_map(|e| std::fs::read_link(e.ok()?.path()).ok())
        .filter(|p| p == std::path::Path::new(path))
        .count()
}

/// The real guides book from a file: readers on 8 threads, `close_file` in the middle.
#[cfg(target_os = "linux")]
#[test]
fn close_file_racing_readers_on_a_real_file() {
    let Some(zim) = real_copy("Telefon/offlinka_cs_prirucky.zim", "race-prirucky.zim") else { return };
    let book = Book::open_path(zim.clone()).unwrap();
    let idx = format!("{zim}.offidx");
    std::fs::write(&idx, index_bytes(&book)).unwrap();
    book.attach_index_path(idx.clone()).unwrap();
    let main = book.info().main_path.unwrap();
    let mut hs = Vec::new();
    for t in 0..8 {
        let (b, main) = (book.clone(), main.clone());
        hs.push(std::thread::spawn(move || {
            for i in 0..400 {
                let r = match (t + i) % 5 {
                    0 => b.get(main.clone()).map(|_| ()),
                    1 => b.get_range(main.clone(), 10, 100).map(|_| ()),
                    2 => b.content_length(main.clone()).map(|_| ()),
                    3 => b.preload_index(),
                    _ => {
                        let _ = (b.suggest("voda".into(), 5), b.random_article(), b.info(), b.illustration(48));
                        Ok(())
                    }
                };
                if let Err(e) = r {
                    assert!(matches!(e, CoreError::Io(_)), "after close: {e:?}");
                }
            }
        }));
    }
    std::thread::sleep(Duration::from_millis(20));
    book.close_file();
    for h in hs {
        h.join().unwrap();
    }
    assert!(matches!(book.get(main), Err(CoreError::Io(_))));
    assert_eq!(fds_on(&zim), 0, "ZIM fd still open after close_file and the last reader");
    assert_eq!(fds_on(&idx), 0, "index fd still open after close_file and the last reader");
}

struct Quiet;
impl offlinka_core::IndexBuildListener for Quiet {
    fn on_progress(&self, _: f64) {}
    fn is_cancelled(&self) -> bool {
        false
    }
}

fn exists(p: &str) -> bool {
    std::path::Path::new(p).exists()
}

#[test]
fn build_index_racing_close_leaves_nothing() {
    let Some(zim) = real_copy("Telefon/offlinka_mul_hry.zim", "race-hry.zim") else { return };
    for delay in [0u64, 1, 3, 10, 30] {
        let book = Book::open_path(zim.clone()).unwrap();
        let out = tmp_path("race.offidx");
        let builder = {
            let (b, o) = (book.clone(), out.clone());
            std::thread::spawn(move || offlinka_core::build_index(b, o, Box::new(Quiet)))
        };
        std::thread::sleep(Duration::from_millis(delay));
        book.close_file();
        let r = builder.join().unwrap();
        assert!(!exists(&format!("{out}.part")), "partial file left (delay {delay})");
        match r {
            Ok(()) => {
                let fresh = Book::open_path(zim.clone()).unwrap();
                fresh.attach_index_path(out.clone()).unwrap();
            }
            Err(e) => {
                assert!(matches!(e, CoreError::Io(_)), "{e:?}");
                assert!(!exists(&out));
            }
        }
        let _ = std::fs::remove_file(&out);
    }
}

/// Several builds of one output path at once (the app's build job restarted, two screens):
/// the file under `out_path` is always absent or complete.
#[test]
fn concurrent_builds_of_one_path_never_expose_a_partial_index() {
    let Some(zim) = real_copy("Telefon/offlinka_mul_hry.zim", "same-path-hry.zim") else { return };
    let book = Book::open_path(zim).unwrap();
    let reference = index_bytes(&book);
    let out = tmp_path("same-path.offidx");
    let done = Arc::new(AtomicBool::new(false));
    let bad = Arc::new(Mutex::new(Vec::<String>::new()));
    let poller = {
        let (out, done, bad, reference) = (out.clone(), done.clone(), bad.clone(), reference.clone());
        std::thread::spawn(move || {
            while !done.load(SeqCst) {
                if let Ok(b) = std::fs::read(&out) {
                    if b != reference {
                        bad.lock().unwrap().push(format!("{} of {} bytes, differs", b.len(), reference.len()));
                    }
                }
            }
        })
    };
    let mut hs = Vec::new();
    for _ in 0..3 {
        let (b, o) = (book.clone(), out.clone());
        hs.push(std::thread::spawn(move || {
            for _ in 0..3 {
                offlinka_core::build_index(b.clone(), o.clone(), Box::new(Quiet)).unwrap();
            }
        }));
    }
    for h in hs {
        h.join().unwrap();
    }
    done.store(true, SeqCst);
    poller.join().unwrap();
    let bad = bad.lock().unwrap();
    assert!(bad.is_empty(), "a half-written index was visible under out_path: {:?}", &bad[..bad.len().min(3)]);
    assert_eq!(std::fs::read(&out).unwrap(), reference);
    let _ = std::fs::remove_file(&out);
}

/// A Kotlin/Swift listener that throws reaches Rust as a panic (UniFFI 0.32): the build
/// stops like a cancel, nothing left on disk, no panic escapes.
#[test]
fn throwing_listener_stops_the_build_cleanly() {
    struct Throws(AtomicU64);
    impl offlinka_core::IndexBuildListener for Throws {
        fn on_progress(&self, _: f64) {
            if self.0.fetch_add(1, SeqCst) == 2 {
                panic!("Callback interface failure: kotlin.IllegalStateException");
            }
        }
        fn is_cancelled(&self) -> bool {
            false
        }
    }
    let zim = tmp_path("throw.zim");
    std::fs::write(&zim, sample().build()).unwrap();
    let book = Book::open_path(zim).unwrap();
    let out = tmp_path("throw.offidx");
    let o = out.clone();
    let r = guarded("build with a throwing listener", 30, move || {
        offlinka_core::build_index(book, o, Box::new(Throws(AtomicU64::new(0))))
    })
    .clean("throwing listener", usize::MAX);
    assert!(matches!(r, Err(CoreError::Cancelled(_))), "{r:?}");
    assert!(!exists(&out) && !exists(&format!("{out}.part")));
}

#[test]
fn library_while_books_close() {
    let lib = Library::new();
    let mut books = Vec::new();
    for i in 0..4 {
        let mut z = sample();
        z.uuid[0] = i;
        let b = Book::open_source(Box::new(MemSource(z.build())), &format!("b{i}.zim")).unwrap();
        lib.add(b.clone());
        books.push(b);
    }
    let mut hs = Vec::new();
    for _ in 0..4 {
        let l = lib.clone();
        hs.push(std::thread::spawn(move || {
            for _ in 0..200 {
                let _ = l.suggest_all("Praha".into(), 20);
                let _ = l.books();
            }
        }));
    }
    for b in &books {
        std::thread::sleep(Duration::from_millis(2));
        b.close_file();
    }
    for h in hs {
        h.join().unwrap();
    }
    assert!(lib.suggest_all("Praha".into(), 20).is_empty());
    assert!(lib.suggest_all("Praha".into(), u32::MAX).is_empty());
}

/// The first `info()` hangs on the drive; after `close_file` the UI's `info()`,
/// `suggest` and `random_article` answer at once instead of waiting behind it.
#[test]
fn calls_after_close_do_not_wait_behind_a_hung_first_load() {
    let (src, gate, _dropped) = hanging(sample().build());
    let b = Book::open_source(Box::new(src), "first.zim").unwrap();
    gate.shut();
    let first = {
        let b = b.clone();
        std::thread::spawn(move || b.info())
    };
    gate.wait_for_hung_read();
    let b2 = b.clone();
    quick("close_file during the first info()", move || b2.close_file());
    let b3 = b.clone();
    quick("info/suggest/random after close", move || {
        assert_eq!(b3.info().title, "first", "minimal record from the file name");
        assert!(b3.suggest("Praha".into(), 5).is_empty());
        assert!(b3.random_article().is_none());
        assert!(b3.illustration(48).is_none());
    });
    gate.open();
    let _ = first.join().unwrap();
}
