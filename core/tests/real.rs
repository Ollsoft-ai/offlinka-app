//! Integration tests against the real drive ZIMs (read-only).
//!
//! Drive folder: `$OFFLINKA_DRIVE` or `/srv/offlinka/drive/release/2026-11.3/files`.
//! Missing files skip the test (with a note on stderr).
//! The big ones run with `cargo test --profile host -- --ignored` (optimised, no LTO).

mod common;

use std::sync::Arc;
use std::time::Instant;

use offlinka_core::reader::FileSource;
use offlinka_core::zim::{Archive, Compression, Kind};
use offlinka_core::{Book, CoreError, Library};

fn drive() -> String {
    std::env::var("OFFLINKA_DRIVE").unwrap_or_else(|_| "/srv/offlinka/drive/release/2026-11.3/files".into())
}

fn zim(rel: &str) -> Option<String> {
    let p = format!("{}/{rel}", drive());
    if std::path::Path::new(&p).exists() {
        Some(p)
    } else {
        eprintln!("skipping: {p} not found");
        None
    }
}

const PRIRUCKY: &str = "Knihovna/offlinka_cs_prirucky.zim";
const HRY: &str = "Knihovna/offlinka_mul_hry.zim";
const CS_MINI: &str = "Telefon/wikipedia_cs_all_mini-offlinka_2026-09.zim";
const SK_MINI: &str = "Telefon/wikipedia_sk_all_mini-offlinka_2026-08.zim";
const CS_MAXI: &str = "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim";
const WIKIBOOKS: &str = "Knihovna/wikibooks_cs_all_maxi-offlinka_2026-10.zim";

/// Read every entry of a small book and check the redirect/content invariants.
fn read_everything(path: &str) {
    let a = Archive::open(Box::new(FileSource::open(path).unwrap())).unwrap();
    let mut seen = std::collections::HashSet::new();
    for c in 0..a.header.cluster_count {
        seen.insert(std::mem::discriminant(&a.cluster_loc(c).unwrap().compression));
    }
    assert!(seen.contains(&std::mem::discriminant(&Compression::Zstd)));
    assert!(seen.contains(&std::mem::discriminant(&Compression::None)));
    let mut prev: Option<(u8, String)> = None;
    for i in 0..a.entry_count() {
        let d = a.dirent(i).unwrap();
        if let Some(p) = &prev {
            assert!((p.0, p.1.as_bytes()) < (d.ns, d.path.as_bytes()), "path order at {i}");
        }
        prev = Some((d.ns, d.path.clone()));
        match d.kind {
            Kind::Content { .. } => {
                a.content(&d).unwrap();
            }
            Kind::Redirect { .. } => {
                let (_, t) = a.resolve(i).unwrap();
                assert!(matches!(t.kind, Kind::Content { .. }));
            }
            Kind::Other => {}
        }
        assert_eq!(a.find(d.ns, &d.path).unwrap(), Some(i));
    }
}

fn check_book_basics(path: &str) -> Arc<Book> {
    let b = Book::open_path(path.into()).unwrap();
    let i = b.info();
    assert!(!i.title.is_empty());
    assert!(!i.language.is_empty());
    assert!(!i.id.is_empty() && i.id.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c)));
    assert!(i.article_count > 0, "{i:?}");
    let main = i.main_path.clone().expect("main page");
    let r = b.get(main.clone()).unwrap();
    assert!(r.mime.starts_with("text/html"), "{}", r.mime);
    assert!(!r.data.is_empty());
    let png = b.illustration(48).expect("illustration");
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
    assert_eq!(b.content_length(main.clone()).unwrap(), r.data.len() as u64);
    assert_eq!(b.get_range(main.clone(), 0, 64).unwrap(), &r.data[..64.min(r.data.len())]);
    assert!(matches!(b.get("Tohle_určitě_neexistuje_123".into()), Err(CoreError::NotFound(_))));
    let rnd = b.random_article().expect("random article");
    assert!(b.get(rnd).unwrap().mime.starts_with("text/html"));
    b
}

fn build_index(zim: &str, name: &str) -> String {
    let a = Archive::open(Box::new(FileSource::open(zim).unwrap())).unwrap();
    let out = common::tmp_path(name);
    let mut f = std::io::BufWriter::new(std::fs::File::create(&out).unwrap());
    offlinka_core::index::build(&a, &mut f, &mut |m| eprintln!("{name}: {m}")).unwrap();
    out
}

#[test]
fn prirucky_everything() {
    let Some(p) = zim(PRIRUCKY) else { return };
    read_everything(&p);
    let b = check_book_basics(&p);
    assert_eq!(b.info().language, "ces");
    let idx = build_index(&p, "prirucky.offidx");
    let n = b.suggest("prvni".into(), 5).len();
    b.attach_index_path(idx).unwrap();
    let s = b.suggest("prvni pomoc".into(), 5);
    assert!(!s.is_empty(), "first-aid guide found without diacritics");
    assert!(s[0].title.to_lowercase().contains("první pomoc"), "{s:?}");
    assert!(b.suggest("prvni".into(), 5).len() >= n);
}

#[test]
fn hry_everything() {
    let Some(p) = zim(HRY) else { return };
    read_everything(&p);
    check_book_basics(&p);
}

#[test]
fn wikibooks_basics_and_index() {
    let Some(p) = zim(WIKIBOOKS) else { return };
    let b = check_book_basics(&p);
    let idx = build_index(&p, "wikibooks.offidx");
    b.attach_index_path(idx).unwrap();
    let hits = b.suggest("kurz programovani".into(), 10);
    assert!(hits[0].title.starts_with("Kurz programování"), "{hits:?}");
    for s in &hits {
        assert!(b.get(s.path.clone()).is_ok(), "{s:?}");
    }
}

#[test]
fn cs_mini_open_and_read() {
    let Some(p) = zim(CS_MINI) else { return };
    let t = Instant::now();
    let b = check_book_basics(&p);
    eprintln!("cs mini open + main page + checks: {:?}", t.elapsed());
    let i = b.info();
    assert_eq!(i.language, "ces");
    assert!(i.article_count > 300_000, "{}", i.article_count);
    let praha = b.get("Praha".into()).unwrap();
    assert!(String::from_utf8_lossy(&praha.data).contains("Praha"));
    // Fallback suggestions (no index): case variants of a plain prefix.
    let s = b.suggest("praha".into(), 5);
    assert_eq!(s[0].title, "Praha", "{s:?}");
    // Several threads at once.
    let paths = ["Brno", "Ostrava", "Plzeň", "Olomouc", "Liberec", "Praha", "Karel_IV.", "Voda"];
    let hs: Vec<_> = (0..8)
        .map(|t| {
            let b = b.clone();
            std::thread::spawn(move || {
                for k in 0..20 {
                    let p = paths[(t + k) % paths.len()];
                    let r = b.get(p.into()).unwrap_or_else(|e| panic!("{p}: {e}"));
                    assert!(r.mime.starts_with("text/html"));
                }
            })
        })
        .collect();
    for h in hs {
        h.join().unwrap();
    }
}

#[test]
fn library_over_real_books() {
    let (Some(cs), Some(pr)) = (zim(CS_MINI), zim(PRIRUCKY)) else { return };
    let lib = Library::new();
    lib.add(Book::open_path(pr.clone()).unwrap());
    lib.add(Book::open_path(cs).unwrap());
    lib.add(Book::open_path(pr).unwrap());
    assert_eq!(lib.books().len(), 2, "same book twice is added once");
    let s = lib.suggest_all("Praha".into(), 5);
    assert!(s[0].book_id.starts_with("wikipedia_cs"), "{s:?}");
}

#[test]
#[ignore]
fn cs_mini_index_quality() {
    let Some(p) = zim(CS_MINI) else { return };
    let b = Book::open_path(p.clone()).unwrap();
    let idx = build_index(&p, "cs_mini.offidx");
    b.attach_index_path(idx).unwrap();
    let first = |q: &str| b.suggest(q.into(), 10).first().map(|s| s.title.clone()).unwrap_or_default();
    assert_eq!(first("cesky krumlov"), "Český Krumlov");
    assert_eq!(first("praha"), "Praha");
    assert_eq!(first("PRAHA"), "Praha");
    assert_eq!(first("brno"), "Brno");
}

#[test]
#[ignore]
fn sk_mini_open() {
    let Some(p) = zim(SK_MINI) else { return };
    let b = check_book_basics(&p);
    assert_eq!(b.info().language, "slk");
}

#[test]
#[ignore]
fn cs_maxi_open_main_and_search() {
    let Some(p) = zim(CS_MAXI) else { return };
    let t = Instant::now();
    let b = Book::open_path(p.clone()).unwrap();
    let main = b.info().main_path.unwrap();
    b.get(main).unwrap();
    eprintln!("cs maxi: open + main page {:?}", t.elapsed());
    let idx = std::env::var("OFFLINKA_CS_MAXI_INDEX").unwrap_or_else(|_| build_index(&p, "cs_maxi.offidx"));
    b.attach_index_path(idx).unwrap();
    let s = b.suggest("cesky krumlov".into(), 10);
    assert_eq!(s[0].title, "Český Krumlov");
    let mut worst = std::time::Duration::ZERO;
    for q in ["cesky krumlov", "praha", "druha svetova valka", "seznam kulturnich pamatek", "a", "s"] {
        for n in 1..=q.len() {
            let t = Instant::now();
            b.suggest(q[..n].into(), 20);
            worst = worst.max(t.elapsed());
        }
    }
    eprintln!("cs maxi worst keystroke {worst:?}");
    if !cfg!(debug_assertions) {
        assert!(worst.as_millis() < 100, "{worst:?}");
    }
}
