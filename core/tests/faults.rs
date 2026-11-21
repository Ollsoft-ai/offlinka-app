//! Fault injection: every public operation against a byte source that fails like a
//! pulled USB drive (EIO, EBADF, end of file inside the file, a short read then nothing),
//! one that is merely slow to deliver (short reads, EINTR: must be invisible) and one
//! that returns zeros. Faults start at every read call and at byte offsets across the
//! operation. Each run must finish without a panic or a hang, without a big allocation,
//! with `CoreError::Io` (or the correct result) for I/O faults, and the book must give
//! the correct result again once the fault is gone (no cached damage).

mod common;
#[path = "common/track.rs"]
mod track;

use std::sync::Arc;

use common::faults::{Ctl, Fail, Faulty, When, ALL_FAILS};
use common::{sample, tmp_path};
use offlinka_core::index::SearchIndex;
use offlinka_core::{Book, CoreError, Library};
use track::{class, digest, guarded};

const MAX_ALLOC: usize = 16 << 20;

fn drive() -> String {
    std::env::var("OFFLINKA_DRIVE").unwrap_or_else(|_| "/srv/offlinka/drive/release/2026-11.3/files".into())
}

fn real(rel: &str) -> Option<String> {
    let p = format!("{}/{rel}", drive());
    if std::path::Path::new(&p).exists() {
        Some(p)
    } else {
        eprintln!("skipping: {p} not found");
        None
    }
}

#[derive(Clone, Debug)]
enum Op {
    Info,
    Illustration,
    Get(String),
    Range(String),
    Len(String),
    Suggest(String),
    Random,
    Missing,
}

impl Op {
    fn throws(&self) -> bool {
        matches!(self, Op::Get(_) | Op::Range(_) | Op::Len(_) | Op::Missing)
    }

    /// (result class, summary).
    fn run(&self, b: &Book) -> (String, String) {
        fn res<T>(r: Result<T, CoreError>, f: impl FnOnce(T) -> String) -> (String, String) {
            let c = class(&r).to_string();
            match r {
                Ok(v) => (c, f(v)),
                Err(e) => (c, e.to_string()),
            }
        }
        match self {
            Op::Info => ("Ok".into(), format!("{:?}", b.info())),
            Op::Illustration => ("Ok".into(), b.illustration(48).map(|v| digest(&v)).unwrap_or_default()),
            Op::Get(p) => res(b.get(p.clone()), |r| format!("{} {} {}", r.path, r.mime, digest(&r.data))),
            Op::Range(p) => res(b.get_range(p.clone(), 3, 1000), |v| digest(&v)),
            Op::Len(p) => res(b.content_length(p.clone()), |n| n.to_string()),
            Op::Suggest(q) => ("Ok".into(), format!("{:?}", b.suggest(q.clone(), 10))),
            Op::Random => ("Ok".into(), b.random_article().is_some().to_string()),
            Op::Missing => res(b.get("Tohle/neexistuje".into()), |r| r.path),
        }
    }
}

/// A few paths of every kind: main page, a redirect, compressed and uncompressed blobs.
fn pick_paths(b: &Book) -> Vec<String> {
    let a = b.archive();
    let mut out: Vec<String> = b.info().main_path.into_iter().collect();
    let mut kinds = std::collections::HashSet::new();
    for i in 0..a.entry_count().min(5000) {
        let d = a.dirent(i).unwrap();
        if d.ns != a.article_ns() {
            continue;
        }
        let kind =
            if d.is_redirect() { "redirect".to_string() } else { a.mime(&d).split('/').next().unwrap().to_string() };
        if kinds.insert(kind) {
            out.push(a.api_path(&d));
        }
        if out.len() >= 6 {
            break;
        }
    }
    out
}

fn ops_for(b: &Book, queries: &[&str]) -> Vec<Op> {
    let mut ops = vec![Op::Info, Op::Illustration, Op::Random, Op::Missing];
    for p in pick_paths(b) {
        ops.extend([Op::Get(p.clone()), Op::Range(p.clone()), Op::Len(p)]);
    }
    ops.extend(queries.iter().map(|q| Op::Suggest(q.to_string())));
    ops
}

/// Fault points: every call (up to 40 spread over the run) and 8 byte offsets.
fn points(calls: u64, bytes: u64) -> Vec<When> {
    let mut v: Vec<When> = if calls <= 40 {
        (0..=calls).map(When::Call).collect()
    } else {
        (0..40).map(|i| When::Call(i * calls / 40)).chain([When::Call(calls)]).collect()
    };
    v.extend((0..8).map(|i| When::Byte(i * bytes / 8 + i)));
    v
}

fn open_on(data: &Arc<Vec<u8>>, ctl: &Arc<Ctl>, name: &str) -> Arc<Book> {
    Book::open_source(Box::new(Faulty::mem(data.clone(), ctl.clone())), name).unwrap()
}

/// Sweep every op over every fault and fault point. `index`: attach this clean index first.
fn sweep_ops(name: &str, data: Arc<Vec<u8>>, index: Option<Arc<Vec<u8>>>, queries: &[&str]) {
    let fresh = |ctl: &Arc<Ctl>| {
        let b = open_on(&data, ctl, name);
        if let Some(ix) = &index {
            b.attach_index(SearchIndex::from_vec(ix.to_vec()).unwrap()).unwrap();
        }
        b
    };
    let probe = fresh(&Ctl::new());
    let ops = ops_for(&probe, queries);
    let mut runs = 0;
    for op in ops {
        let ctl = Ctl::new();
        let b = fresh(&ctl);
        ctl.reset_counters();
        let reference = op.run(&b);
        let (calls, bytes) = (ctl.calls.load(SeqCst), ctl.bytes.load(SeqCst));
        for fail in ALL_FAILS {
            let mut whens = if fail.transparent() { vec![When::Call(0)] } else { points(calls, bytes) };
            if fail == Fail::Eio {
                whens.extend((0..calls.min(40)).map(When::Only)); // transient errors
            }
            for when in whens {
                runs += 1;
                let what = format!("{name} {op:?} {fail:?} {when:?}");
                let ctl = Ctl::new();
                let b = fresh(&ctl);
                ctl.arm(fail, when);
                let (op2, b2, ctl2) = (op.clone(), b.clone(), ctl.clone());
                let (got, again) = guarded(&what, 30, move || {
                    let got = op2.run(&b2);
                    ctl2.disarm();
                    (got, op2.run(&b2))
                })
                .clean(&what, MAX_ALLOC);
                if fail.transparent() {
                    assert_eq!(got, reference, "{what}: short reads / EINTR must be invisible");
                } else if fail.is_io() && op.throws() && got != reference {
                    assert_eq!(got.0, "Io", "{what}: an I/O fault must give CoreError::Io, got {got:?}");
                }
                if fail != Fail::Garbage {
                    assert_eq!(again, reference, "{what}: wrong result once the fault is gone (cached damage)");
                }
            }
        }
    }
    eprintln!("{name}: {runs} faulted runs");
}

/// Opening under faults: `Io` or a working book, never anything else.
fn sweep_open(name: &str, data: Arc<Vec<u8>>) {
    let reference = format!("{:?}", open_on(&data, &Ctl::new(), name).info());
    for fail in ALL_FAILS {
        for when in [0, 1, 2, 3].map(When::Call).into_iter().chain([0, 40, 80, 81, 200, 4000].map(When::Byte)) {
            let what = format!("{name} open {fail:?} {when:?}");
            let ctl = Ctl::new();
            ctl.arm(fail, when);
            let (d, c, n) = (data.clone(), ctl.clone(), name.to_string());
            let r = guarded(&what, 30, move || {
                let r = Book::open_source(Box::new(Faulty::mem(d, c.clone())), &n);
                c.disarm();
                r.map(|b| format!("{:?}", b.info()))
            })
            .clean(&what, MAX_ALLOC);
            match (fail, r) {
                (_, Ok(info)) if fail != Fail::Garbage => assert_eq!(info, reference, "{what}"),
                (f, Err(e)) if f.is_io() => assert!(matches!(e, CoreError::Io(_)), "{what}: {e:?}"),
                (f, Err(e)) if f.transparent() => panic!("{what}: {e:?}"),
                _ => {}
            }
        }
    }
}

struct NoCancel;
impl offlinka_core::IndexBuildListener for NoCancel {
    fn on_progress(&self, _: f64) {}
    fn is_cancelled(&self) -> bool {
        false
    }
}

fn exists(p: &str) -> bool {
    std::path::Path::new(p).exists()
}

/// `build_index` under faults: an exact index or `Io` with no file at all.
fn sweep_build(name: &str, data: Arc<Vec<u8>>) {
    let reference_path = tmp_path(&format!("{name}-ref.offidx"));
    let ctl = Ctl::new();
    let b = open_on(&data, &ctl, name);
    ctl.reset_counters();
    offlinka_core::build_index(b, reference_path.clone(), Box::new(NoCancel)).unwrap();
    let reference = std::fs::read(&reference_path).unwrap();
    let _ = std::fs::remove_file(&reference_path);
    let (calls, bytes) = (ctl.calls.load(SeqCst), ctl.bytes.load(SeqCst));
    let mut runs = 0;
    for fail in ALL_FAILS {
        let whens = if fail.transparent() { vec![When::Call(0)] } else { points(calls, bytes) };
        for when in whens {
            runs += 1;
            let what = format!("{name} build_index {fail:?} {when:?}");
            let out = tmp_path(&format!("{name}-faulted.offidx"));
            let ctl = Ctl::new();
            let b = open_on(&data, &ctl, name);
            ctl.arm(fail, when);
            let (b2, o2) = (b.clone(), out.clone());
            let r = guarded(&what, 60, move || offlinka_core::build_index(b2, o2, Box::new(NoCancel)))
                .clean(&what, MAX_ALLOC + (reference.len() * 4));
            assert!(!exists(&format!("{out}.part")), "{what}: partial file left behind");
            match &r {
                Ok(()) if fail != Fail::Garbage => {
                    assert!(
                        std::fs::read(&out).unwrap() == reference,
                        "{what}: built an index that differs from a clean build"
                    )
                }
                Ok(()) => {}
                Err(e) => {
                    assert!(!exists(&out), "{what}: failed ({e:?}) but left {out}");
                    if fail.is_io() {
                        assert!(matches!(e, CoreError::Io(_)), "{what}: an I/O fault must give Io, got {e:?}");
                    }
                    assert!(!fail.transparent(), "{what}: {e:?}");
                }
            }
            ctl.disarm();
            let _ = std::fs::remove_file(&out);
            if fail != Fail::Garbage {
                offlinka_core::build_index(b, out.clone(), Box::new(NoCancel)).unwrap();
                assert!(std::fs::read(&out).unwrap() == reference, "{what}: rebuild after the fault differs");
            }
            let _ = std::fs::remove_file(&out);
        }
    }
    eprintln!("{name}: {runs} faulted index builds");
}

/// Attaching and preloading an index whose file fails.
fn sweep_index(name: &str, data: Arc<Vec<u8>>, queries: &[&str]) {
    let idx = {
        let b = open_on(&data, &Ctl::new(), name);
        let mut cur = std::io::Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
        Arc::new(cur.into_inner())
    };
    let book = open_on(&data, &Ctl::new(), name);
    let ask = |b: &Book| queries.iter().map(|q| format!("{:?}", b.suggest(q.to_string(), 10))).collect::<Vec<_>>();
    book.attach_index(SearchIndex::from_vec(idx.to_vec()).unwrap()).unwrap();
    let reference = ask(&book);
    // reads of a lazily loaded index: header, then the maps on preload
    let ctl = Ctl::new();
    let probe = SearchIndex::from_reader(Box::new(Faulty::mem(idx.clone(), ctl.clone()))).unwrap();
    book.attach_index(probe).unwrap();
    book.preload_index().unwrap();
    let (calls, bytes) = (ctl.calls.load(SeqCst), ctl.bytes.load(SeqCst));
    for fail in ALL_FAILS {
        let whens = if fail.transparent() { vec![When::Call(0)] } else { points(calls, bytes) };
        for when in whens {
            let what = format!("{name} index {fail:?} {when:?}");
            let ctl = Ctl::new();
            ctl.arm(fail, when);
            let (book, idx, c, qs) =
                (book.clone(), idx.clone(), ctl.clone(), queries.iter().map(|s| s.to_string()).collect::<Vec<_>>());
            let (opened, preload, again) = guarded(&what, 30, move || {
                let opened = SearchIndex::from_reader(Box::new(Faulty::mem(idx.clone(), c.clone())));
                let opened_class = class(&opened).to_string();
                let mut preload = "skipped".to_string();
                match opened {
                    Ok(s) => {
                        book.attach_index(s).unwrap();
                        preload = class(&book.preload_index()).to_string();
                        for q in &qs {
                            let _ = book.suggest(q.clone(), 10);
                        }
                        c.disarm();
                    }
                    Err(_) => {
                        c.disarm();
                        book.attach_index(SearchIndex::from_reader(Box::new(Faulty::mem(idx, c))).unwrap()).unwrap();
                    }
                }
                let again: Vec<String> = qs.iter().map(|q| format!("{:?}", book.suggest(q.clone(), 10))).collect();
                (opened_class, preload, again)
            })
            .clean(&what, MAX_ALLOC);
            if fail.is_io() {
                assert!(["Ok", "Io"].contains(&opened.as_str()), "{what}: open index gave {opened}");
                assert!(["Ok", "Io", "skipped"].contains(&preload.as_str()), "{what}: preload gave {preload}");
            }
            if fail.transparent() {
                assert_eq!((opened.as_str(), preload.as_str()), ("Ok", "Ok"), "{what}");
            }
            if fail != Fail::Garbage {
                assert_eq!(again, reference, "{what}: suggestions wrong after the fault is gone");
            }
        }
    }
}

/// `Library.suggest_all` over books that fail.
fn sweep_library(name: &str, data: Arc<Vec<u8>>, query: &str) {
    let mk = |ctl: &Arc<Ctl>| {
        let lib = Library::new();
        lib.add(open_on(&data, ctl, name));
        lib
    };
    let reference = format!("{:?}", mk(&Ctl::new()).suggest_all(query.into(), 10));
    for fail in [Fail::Eio, Fail::Zero, Fail::Garbage, Fail::ShortOk] {
        for n in 0..30 {
            let what = format!("{name} suggest_all {fail:?} call {n}");
            let ctl = Ctl::new();
            let lib = mk(&ctl);
            ctl.arm(fail, When::Call(n));
            let (c, q) = (ctl.clone(), query.to_string());
            let (got, again) = guarded(&what, 30, move || {
                let got = format!("{:?}", lib.suggest_all(q.clone(), 10));
                c.disarm();
                (got, format!("{:?}", lib.suggest_all(q, 10)))
            })
            .clean(&what, MAX_ALLOC);
            if fail.transparent() {
                assert_eq!(got, reference, "{what}");
            }
            if fail != Fail::Garbage {
                assert_eq!(again, reference, "{what}");
            }
        }
    }
}

use std::sync::atomic::Ordering::SeqCst;

fn sample_data() -> Arc<Vec<u8>> {
    Arc::new(sample().build())
}

const SAMPLE_Q: &[&str] = &["Praha", "praž", "Krumlov", "zzz"];

#[test]
fn faults_open() {
    sweep_open("sample", sample_data());
    if let Some(p) = real("Telefon/offlinka_cs_prirucky.zim") {
        sweep_open("prirucky", Arc::new(std::fs::read(p).unwrap()));
    }
}

#[test]
fn faults_ops_sample() {
    sweep_ops("sample", sample_data(), None, SAMPLE_Q);
}

#[test]
fn faults_ops_sample_indexed() {
    let data = sample_data();
    let b = open_on(&data, &Ctl::new(), "sample");
    let mut cur = std::io::Cursor::new(Vec::new());
    offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
    sweep_ops("sample+index", data, Some(Arc::new(cur.into_inner())), &["cesky krumlov", "pra", "k"]);
}

#[test]
fn faults_ops_prirucky() {
    let Some(p) = real("Telefon/offlinka_cs_prirucky.zim") else { return };
    sweep_ops("prirucky", Arc::new(std::fs::read(p).unwrap()), None, &["Prvn", "voda", "o"]);
}

#[test]
fn faults_ops_hry() {
    let Some(p) = real("Telefon/offlinka_mul_hry.zim") else { return };
    sweep_ops("hry", Arc::new(std::fs::read(p).unwrap()), None, &["S", "2048"]);
}

#[test]
fn faults_build_index() {
    sweep_build("sample", sample_data());
    if let Some(p) = real("Telefon/offlinka_cs_prirucky.zim") {
        sweep_build("prirucky", Arc::new(std::fs::read(p).unwrap()));
    }
    if let Some(p) = real("Telefon/offlinka_mul_hry.zim") {
        sweep_build("hry", Arc::new(std::fs::read(p).unwrap()));
    }
}

#[test]
fn faults_attach_and_preload_index() {
    sweep_index("sample", sample_data(), &["cesky krumlov", "pra", "k", "kun"]);
    if let Some(p) = real("Telefon/offlinka_cs_prirucky.zim") {
        sweep_index("prirucky", Arc::new(std::fs::read(p).unwrap()), &["prvni pomoc", "v", "voda"]);
    }
}

#[test]
fn faults_library() {
    sweep_library("sample", sample_data(), "Praha");
}

/// The phone editions of Wikipedia straight from the drive files (pread, ~1 GB): first
/// metadata, the main page and a search, with the drive failing at every early read.
#[test]
fn faults_on_real_minis() {
    for rel in
        ["Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim", "Telefon/wikipedia_sk_all_mini-offlinka_2026-08.zim"]
    {
        let Some(path) = real(rel) else { continue };
        let clean = Book::open_path(path.clone()).unwrap();
        let info = format!("{:?}", clean.info());
        let main = clean.info().main_path.unwrap();
        let page = digest(&clean.get(main.clone()).unwrap().data);
        let hits = format!("{:?}", clean.suggest("Pra".into(), 10));
        for fail in [Fail::Eio, Fail::Zero, Fail::ShortThenZero, Fail::ShortOk] {
            for n in [0u64, 1, 2, 3, 5, 8, 13, 21, 34] {
                let what = format!("{rel} {fail:?} call {n}");
                let ctl = Ctl::new();
                let b = Book::open_source(Box::new(Faulty::file(&path, ctl.clone())), &path).unwrap();
                ctl.arm(fail, When::Call(n));
                let (c, m) = (ctl.clone(), main.clone());
                let (got, again) = guarded(&what, 60, move || {
                    let got = (
                        format!("{:?}", b.info()),
                        class(&b.get(m.clone())).to_string(),
                        format!("{:?}", b.suggest("Pra".into(), 10)),
                    );
                    c.disarm();
                    let again = (
                        format!("{:?}", b.info()),
                        digest(&b.get(m).unwrap().data),
                        format!("{:?}", b.suggest("Pra".into(), 10)),
                    );
                    (got, again)
                })
                .clean(&what, 64 << 20);
                if fail.transparent() {
                    assert_eq!(got.0, info, "{what}");
                }
                if fail.is_io() {
                    assert!(["Ok", "Io"].contains(&got.1.as_str()), "{what}: get gave {}", got.1);
                }
                assert_eq!(again, (info.clone(), page.clone(), hits.clone()), "{what}: wrong after the fault is gone");
            }
        }
    }
}
