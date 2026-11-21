//! Corruption fuzzing, deterministic so it runs in CI: structure-aware mutations of ZIM
//! files (header, MIME list, path pointers, directory entries, cluster pointers, cluster
//! info bytes, offset tables and compressed streams, redirect targets, truncation) and
//! of `.offidx` indexes (header, section table, fst maps, postings, top lists, score
//! levels), plus hand-made zstd/xz bombs, multi-GB declared sizes and redirect loops.
//! Every case runs every public operation (and an index build) on a worker thread and
//! must end without a panic, a hang or a big allocation.
//!
//! `OFFLINKA_FUZZ_ROUNDS=<n>` multiplies the rounds (default 1). `core/fuzz/` has the
//! same targets for cargo-fuzz.

mod common;
#[path = "common/track.rs"]
mod track;

use std::io::Cursor;
use std::sync::Arc;

use common::faults::Sparse;
use common::{rle_blocks, sample, zstd_frame, Comp, ZimBuilder};
use offlinka_core::index::SearchIndex;
use offlinka_core::reader::{MemSource, ReadAt};
use offlinka_core::{Book, CoreError, Library};
use track::guarded;

/// Biggest single allocation allowed while exercising a small corrupt file.
const SMALL: usize = 32 << 20;
/// A cluster may inflate to 256 MiB; a bomb may cost that much, plus a little.
const CLUSTER_LIMIT: usize = 256 << 20;

fn rounds(base: usize) -> usize {
    base * std::env::var("OFFLINKA_FUZZ_ROUNDS").ok().and_then(|v| v.parse().ok()).unwrap_or(1)
}

fn drive_file(rel: &str) -> Option<Vec<u8>> {
    let p = format!(
        "{}/{rel}",
        std::env::var("OFFLINKA_DRIVE").unwrap_or_else(|_| "/srv/offlinka/drive/release/2026-11.3/files".into())
    );
    std::fs::read(&p).map_err(|_| eprintln!("skipping: {p} not found")).ok()
}

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        if n == 0 {
            0
        } else {
            self.next() % n
        }
    }
    fn pick<T: Copy>(&mut self, v: &[T]) -> T {
        v[self.below(v.len() as u64) as usize]
    }
}

fn get_le(b: &[u8], off: usize, w: usize) -> u64 {
    let mut x = [0u8; 8];
    if off + w <= b.len() {
        x[..w].copy_from_slice(&b[off..off + w]);
    }
    u64::from_le_bytes(x)
}

fn put_le(b: &mut [u8], off: usize, w: usize, v: u64) {
    if off + w <= b.len() {
        b[off..off + w].copy_from_slice(&v.to_le_bytes()[..w]);
    }
}

/// Values that break parsers: zero, one, the edges of the file and of the integer type,
/// neighbours of the original value, counts of the archive.
fn interesting(r: &mut Rng, orig: u64, w: usize, size: u64, counts: &[u64]) -> u64 {
    let c = r.pick(counts);
    let v = match w {
        8 => r.pick(&[
            0,
            1,
            7,
            8,
            79,
            80,
            size - 1,
            size,
            size + 1,
            u32::MAX as u64,
            1 << 32,
            u64::MAX,
            u64::MAX - 7,
            i64::MAX as u64,
            orig.wrapping_add(1),
            orig.wrapping_sub(1),
            orig.wrapping_mul(2),
            orig ^ (1 << (r.0 % 64)),
            r.0,
        ]),
        4 => r.pick(&[
            0,
            1,
            2,
            c.wrapping_sub(1),
            c,
            c + 1,
            0x7fff_ffff,
            0x8000_0000,
            0xffff_fffe,
            0xffff_ffff,
            orig.wrapping_add(1),
            orig.wrapping_sub(1),
            orig ^ (1 << (r.0 % 32)),
            r.0,
        ]),
        2 => r.pick(&[0, 1, 0xffff, 0xfffe, 0xfffd, c, 0x7fff, orig ^ 1, r.0]),
        _ => r.pick(&[0, 0xff, b'C' as u64, b'M' as u64, b'W' as u64, b'X' as u64, b'A' as u64, b'-' as u64, r.0]),
    };
    let _ = r.next();
    v & if w == 8 { u64::MAX } else { (1u64 << (w * 8)) - 1 }
}

/// Where things are in a ZIM file.
struct Layout {
    size: u64,
    mime_pos: usize,
    path_ptr: usize,
    entries: u32,
    cluster_ptr: usize,
    clusters: u32,
    dirents: Vec<u64>,
    cluster_pos: Vec<u64>,
}

fn layout(b: &[u8]) -> Layout {
    let entries = get_le(b, 24, 4) as u32;
    let clusters = get_le(b, 28, 4) as u32;
    let path_ptr = get_le(b, 32, 8) as usize;
    let cluster_ptr = get_le(b, 48, 8) as usize;
    Layout {
        size: b.len() as u64,
        mime_pos: get_le(b, 56, 8) as usize,
        path_ptr,
        entries,
        cluster_ptr,
        clusters,
        dirents: (0..entries as usize).map(|i| get_le(b, path_ptr + i * 8, 8)).collect(),
        cluster_pos: (0..clusters as usize).map(|i| get_le(b, cluster_ptr + i * 8, 8)).collect(),
    }
}

const HEADER_FIELDS: [(usize, usize); 11] =
    [(4, 2), (6, 2), (24, 4), (28, 4), (32, 8), (40, 8), (48, 8), (56, 8), (64, 4), (68, 4), (72, 8)];

/// One structure-aware mutation of a ZIM.
fn mutate_zim(r: &mut Rng, base: &[u8], l: &Layout) -> (String, Vec<u8>) {
    let mut b = base.to_vec();
    let counts = [l.entries as u64, l.clusters as u64, 1];
    let size = l.size;
    let ndir = l.dirents.len().max(1) as u64;
    let ncl = l.cluster_pos.len().max(1) as u64;
    let kind = r.below(14);
    let desc = match kind {
        0 => {
            let (o, w) = r.pick(&HEADER_FIELDS);
            let v = interesting(r, get_le(&b, o, w), w, size, &counts);
            put_le(&mut b, o, w, v);
            format!("header[{o}] = {v:#x}")
        }
        1 => {
            let i = r.below(ndir) as usize;
            let o = l.path_ptr + i * 8;
            let v = interesting(r, get_le(&b, o, 8), 8, size, &counts);
            put_le(&mut b, o, 8, v);
            format!("path pointer {i} = {v:#x}")
        }
        2 => {
            let i = r.below(ndir) as usize;
            let d = *l.dirents.get(i).unwrap_or(&0) as usize;
            let (fo, w) = r.pick(&[(0, 2), (2, 1), (3, 1), (8, 4), (12, 4)]);
            let v = interesting(r, get_le(&b, d + fo, w), w, size, &counts);
            put_le(&mut b, d + fo, w, v);
            format!("dirent {i} field {fo} = {v:#x}")
        }
        3 => {
            let i = r.below(ncl) as usize;
            let o = l.cluster_ptr + i * 8;
            let v = interesting(r, get_le(&b, o, 8), 8, size, &counts);
            put_le(&mut b, o, 8, v);
            format!("cluster pointer {i} = {v:#x}")
        }
        4 => {
            let i = r.below(ncl) as usize;
            let o = *l.cluster_pos.get(i).unwrap_or(&0) as usize;
            let v = r.pick(&[0u64, 1, 2, 3, 4, 5, 6, 0x11, 0x14, 0x15, 0x1f, 0xff]);
            put_le(&mut b, o, 1, v);
            format!("cluster {i} info = {v:#x}")
        }
        5 => {
            let i = r.below(ncl) as usize;
            let o = *l.cluster_pos.get(i).unwrap_or(&0) as usize + 1 + 4 * r.below(4) as usize;
            let v = interesting(r, get_le(&b, o, 4), 4, size, &counts);
            put_le(&mut b, o, 4, v);
            format!("cluster {i} table word at {o} = {v:#x}")
        }
        6 => {
            let i = r.below(ncl) as usize;
            let start = *l.cluster_pos.get(i).unwrap_or(&0) as usize;
            let end = l.cluster_pos.get(i + 1).map(|&e| e as usize).unwrap_or(b.len()).min(b.len());
            let n = 1 + r.below(4);
            for _ in 0..n {
                let o = start + 1 + r.below(end.saturating_sub(start + 1) as u64) as usize;
                if o < b.len() {
                    b[o] ^= 1 << r.below(8);
                }
            }
            format!("cluster {i}: {n} bit flips in its data")
        }
        7 => {
            let o = l.mime_pos + r.below(64) as usize;
            let v = r.pick(&[b'x' as u64, 0, 0xff]);
            put_le(&mut b, o, 1, v);
            format!("MIME list byte {o} = {v:#x}")
        }
        8 => {
            let marks =
                [80, l.mime_pos, l.path_ptr, l.cluster_ptr, *l.cluster_pos.last().unwrap_or(&0) as usize, b.len() - 16];
            let cut = (r.pick(&marks) as i64 + r.pick(&[-9i64, -1, 0, 1, 7])).clamp(0, b.len() as i64 - 1) as usize;
            b.truncate(cut);
            format!("truncated at {cut}")
        }
        9 => {
            let n = 1 + r.below(8);
            for _ in 0..n {
                let o = r.below(b.len() as u64) as usize;
                b[o] = r.next() as u8;
            }
            format!("{n} random bytes")
        }
        10 => {
            let d = *l.dirents.get(r.below(ndir) as usize).unwrap_or(&0) as usize;
            for x in b.iter_mut().skip(d + 8).take(600) {
                if *x == 0 {
                    *x = b'x';
                }
            }
            format!("dirent at {d}: strings unterminated")
        }
        11 => {
            let (i, j) = (r.below(ndir) as usize, r.below(ndir) as usize);
            let v = get_le(&b, l.path_ptr + i * 8, 8);
            put_le(&mut b, l.path_ptr + j * 8, 8, v);
            format!("path pointer {j} = pointer {i} (duplicate, unsorted)")
        }
        12 => {
            let i = r.below(ndir) as usize;
            let d = *l.dirents.get(i).unwrap_or(&0) as usize;
            let t = r.pick(&[i as u64, (i as u64 + 1) % ndir, u32::MAX as u64, l.entries as u64]);
            put_le(&mut b, d, 2, 0xffff); // make it a redirect
            put_le(&mut b, d + 8, 4, t);
            format!("dirent {i} redirects to {t}")
        }
        _ => {
            let o = r.below(b.len() as u64) as usize;
            let n = (64 + r.below(4096) as usize).min(b.len() - o);
            b[o..o + n].fill(0);
            format!("{n} zero bytes at {o}")
        }
    };
    (desc, b)
}

const QUERIES: [&str; 7] = ["P", "pra", "Praha", "cesky krumlov", "ž", "t", "test 1"];

/// Every public operation, an index build and a library search on one (corrupt) ZIM.
fn exercise(src: Box<dyn ReadAt>, paths: Arc<Vec<String>>) {
    let Ok(b) = Book::open_source(src, "m.zim") else { return };
    let _ = b.info();
    let _ = b.illustration(48);
    let _ = b.illustration(4096);
    for p in paths.iter() {
        let _ = b.get(p.clone());
        let _ = b.get_range(p.clone(), 1, 300);
        let _ = b.get_range(p.clone(), u64::MAX - 5, u32::MAX);
        let _ = b.content_length(p.clone());
    }
    for q in QUERIES {
        let _ = b.suggest(q.into(), 10);
    }
    let _ = b.suggest("pra".into(), u32::MAX);
    let _ = b.random_article();
    let mut cur = Cursor::new(Vec::new());
    if offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).is_ok() {
        if let Ok(ix) = SearchIndex::from_vec(cur.into_inner()) {
            if b.attach_index(ix).is_ok() {
                let _ = b.preload_index();
                for q in QUERIES {
                    let _ = b.suggest(q.into(), 10);
                }
            }
        }
    }
    let lib = Library::new();
    lib.add(b.clone());
    let _ = lib.suggest_all("pra".into(), 10);
    b.close_file();
    let _ = b.get("Praha".into());
}

fn all_paths(bytes: &[u8], max: usize) -> Vec<String> {
    let b = Book::open_source(Box::new(MemSource(bytes.to_vec())), "x.zim").unwrap();
    let a = b.archive();
    let step = (a.entry_count() as usize / max).max(1);
    (0..a.entry_count()).step_by(step).filter_map(|i| a.dirent(i).ok().map(|d| a.api_path(&d))).collect()
}

fn fuzz_zim(name: &str, base: Vec<u8>, n: usize, seed: u64) {
    let l = layout(&base);
    let paths = Arc::new(all_paths(&base, 12));
    let mut r = Rng(seed);
    for k in 0..n {
        let (desc, bytes) = mutate_zim(&mut r, &base, &l);
        let what = format!("{name} #{k}: {desc}");
        let p = paths.clone();
        guarded(&what, 60, move || exercise(Box::new(MemSource(bytes)), p)).clean(&what, SMALL);
    }
}

#[test]
fn fuzz_sample_zim() {
    fuzz_zim("sample", sample().build(), rounds(3000), 0x9e3779b97f4a7c15);
}

#[test]
fn fuzz_prirucky_zim() {
    let Some(b) = drive_file("Telefon/offlinka_cs_prirucky.zim") else { return };
    fuzz_zim("prirucky", b, rounds(300), 0x51ed2701);
}

#[test]
fn fuzz_hry_zim() {
    let Some(b) = drive_file("Telefon/offlinka_mul_hry.zim") else { return };
    fuzz_zim("hry", b, rounds(150), 0xfeed_beef);
}

#[test]
fn truncated_everywhere() {
    for (name, base) in
        [("sample", Some(sample().build())), ("prirucky", drive_file("Telefon/offlinka_cs_prirucky.zim"))]
    {
        let Some(base) = base else { continue };
        let paths = Arc::new(all_paths(&base, 8));
        let l = layout(&base);
        let mut cuts: Vec<usize> = (0..base.len()).step_by(base.len() / 60 + 1).collect();
        for m in [80, l.mime_pos, l.path_ptr, l.cluster_ptr]
            .into_iter()
            .chain(l.dirents.iter().take(20).map(|&d| d as usize))
        {
            cuts.extend([m.saturating_sub(1), m, m + 1]);
        }
        cuts.extend(l.cluster_pos.iter().flat_map(|&c| [c as usize, c as usize + 1, c as usize + 5]));
        for cut in cuts.into_iter().filter(|&c| c < base.len()) {
            let what = format!("{name} cut at {cut}");
            let (bytes, p) = (base[..cut].to_vec(), paths.clone());
            guarded(&what, 60, move || exercise(Box::new(MemSource(bytes)), p)).clean(&what, SMALL);
        }
    }
}

// ------------------------------------------------------------------ the .offidx index

fn index_of(zim: &[u8]) -> Vec<u8> {
    let b = Book::open_source(Box::new(MemSource(zim.to_vec())), "i.zim").unwrap();
    let mut cur = Cursor::new(Vec::new());
    offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
    cur.into_inner()
}

/// A book with > 1024 keys under one prefix, so its index has heavy top lists.
fn heavy_zim() -> Vec<u8> {
    let mut z = ZimBuilder::new();
    let c = z.cluster(Comp::Zstd, false);
    for i in 0..1500 {
        let t = format!("Test {i:04}");
        z.article(&t.replace(' ', "_"), &t, "<p>x</p>", c);
    }
    z.redirect(b'C', "Tes", "Tes", b'C', "Test_0007");
    z.build()
}

fn mutate_index(r: &mut Rng, base: &[u8]) -> (String, Vec<u8>) {
    let mut b = base.to_vec();
    let size = b.len() as u64;
    let sec = |b: &[u8], i: usize| (get_le(b, 48 + i * 16, 8) as usize, get_le(b, 56 + i * 16, 8) as usize);
    let counts = [get_le(&b, 36, 4), get_le(&b, 44, 4), get_le(&b, 32, 4)];
    let kind = r.below(10);
    let desc = match kind {
        0 => {
            let o = r.pick(&[8usize, 12, 32, 36, 40, 44]);
            let v = interesting(r, get_le(&b, o, 4), 4, size, &counts);
            put_le(&mut b, o, 4, v);
            format!("header[{o}] = {v:#x}")
        }
        1 => {
            let o = 48 + r.below(11 * 2) as usize * 8;
            let v = interesting(r, get_le(&b, o, 8), 8, size, &counts);
            put_le(&mut b, o, 8, v);
            format!("section table word {o} = {v:#x}")
        }
        2 | 3 => {
            let i = r.below(if kind == 2 { 4 } else { 11 }) as usize;
            let (off, len) = sec(&b, i);
            let n = 1 + r.below(4);
            for _ in 0..n {
                let o = off + r.below(len as u64) as usize;
                if o < b.len() {
                    b[o] ^= 1 << r.below(8);
                }
            }
            format!("section {i}: {n} bit flips")
        }
        4 => {
            let i = r.below(4) as usize;
            let (off, len) = sec(&b, i);
            let o = off + len.saturating_sub(1 + r.below(24) as usize);
            put_le(&mut b, o, 1, r.next());
            format!("fst {i} trailer byte {o}")
        }
        5 => {
            let i = r.pick(&[5usize, 6, 7, 8, 9, 10]);
            let (off, len) = sec(&b, i);
            let o = off + (r.below((len / 4).max(1) as u64) as usize) * 4;
            let v = interesting(r, get_le(&b, o, 4), 4, size, &counts);
            put_le(&mut b, o, 4, v);
            format!("section {i} u32 at {o} = {v:#x}")
        }
        6 => {
            let cut = r.below(b.len() as u64) as usize;
            b.truncate(cut);
            format!("truncated at {cut}")
        }
        7 => {
            let n = 1 + r.below(8);
            for _ in 0..n {
                let o = r.below(b.len() as u64) as usize;
                b[o] = r.next() as u8;
            }
            format!("{n} random bytes")
        }
        8 => {
            let (i, j) = (r.below(11) as usize, r.below(11) as usize);
            let (a, c) = (b[48 + i * 16..64 + i * 16].to_vec(), b[48 + j * 16..64 + j * 16].to_vec());
            b[48 + i * 16..64 + i * 16].copy_from_slice(&c);
            b[48 + j * 16..64 + j * 16].copy_from_slice(&a);
            format!("sections {i} and {j} swapped")
        }
        _ => {
            let (off, len) = sec(&b, 9);
            let o = off + (r.below((len / 4).max(1) as u64) as usize) * 4;
            put_le(&mut b, o, 4, r.pick(&[0xffff_ffffu64, 0x4000_0000, 1 << 20, 65]));
            format!("heavy list count at {o}")
        }
    };
    (desc, b)
}

fn search_with(zim: Arc<Vec<u8>>, idx: Vec<u8>) {
    let b = Book::open_source(Box::new(MemSource(zim.to_vec())), "i.zim").unwrap();
    for ix in [SearchIndex::from_vec(idx.clone()), SearchIndex::from_reader(Box::new(MemSource(idx)))] {
        let Ok(ix) = ix else { continue };
        if b.attach_index(ix).is_err() {
            continue;
        }
        let _ = b.preload_index();
        for q in QUERIES.iter().chain(&["tes", "test 0", "test 09", "x"]) {
            let _ = b.suggest(q.to_string(), 20);
        }
        let lib = Library::new();
        lib.add(b.clone());
        let _ = lib.suggest_all("test".into(), 20);
        offlinka_core::trim_memory();
        let _ = b.suggest("te".into(), 5);
    }
}

fn fuzz_index(name: &str, zim: Vec<u8>, n: usize, seed: u64) {
    let base = index_of(&zim);
    let zim = Arc::new(zim);
    let mut r = Rng(seed);
    for k in 0..n {
        let (desc, idx) = mutate_index(&mut r, &base);
        let what = format!("{name} index #{k}: {desc}");
        let z = zim.clone();
        guarded(&what, 60, move || search_with(z, idx)).clean(&what, SMALL);
    }
}

#[test]
fn fuzz_sample_index() {
    fuzz_index("sample", sample().build(), rounds(1500), 0x1234_5678);
}

#[test]
fn fuzz_heavy_index() {
    fuzz_index("heavy", heavy_zim(), rounds(800), 0x0bad_cafe);
}

#[test]
fn fuzz_prirucky_index() {
    let Some(b) = drive_file("Telefon/offlinka_cs_prirucky.zim") else { return };
    fuzz_index("prirucky", b, rounds(300), 0x7777);
}

#[test]
fn index_of_another_book() {
    let zim = sample().build();
    let idx = index_of(&zim);
    // a different book: refused
    let mut other = sample();
    other.uuid = *b"some-other-book!";
    let b = Book::open_source(Box::new(MemSource(other.build())), "o.zim").unwrap();
    assert!(matches!(b.attach_index(SearchIndex::from_vec(idx.clone()).unwrap()), Err(CoreError::InvalidZim(_))));
    if let Some(p) = drive_file("Telefon/offlinka_cs_prirucky.zim") {
        let pb = Book::open_source(Box::new(MemSource(p)), "p.zim").unwrap();
        assert!(matches!(pb.attach_index(SearchIndex::from_vec(idx.clone()).unwrap()), Err(CoreError::InvalidZim(_))));
    }
    // same uuid and entry count but other content (a rebuilt ZIM that kept its uuid):
    // the records point at the wrong entries; answers are junk, never a panic
    let mut twin = ZimBuilder::new();
    twin.uuid = sample().uuid;
    let c = twin.cluster(Comp::Zstd, false);
    let n = layout(&zim).entries as usize - 2; // the builder adds two listings
    for i in 0..n {
        twin.article(&format!("Jiny_{i}"), &format!("Jiný článek {i}"), "<p>x</p>", c);
    }
    let twin = twin.build();
    assert_eq!(layout(&twin).entries, layout(&zim).entries);
    let what = "index of a twin book";
    guarded(what, 60, move || search_with(Arc::new(twin), idx)).clean(what, SMALL);
}

// ------------------------------------------------------------------ bombs and huge sizes

/// One article in a cluster written verbatim.
fn zim_with_raw_cluster(raw: Vec<u8>) -> Vec<u8> {
    let mut z = sample();
    let c = z.cluster(Comp::None, false); // placeholder, replaced by `raw`
    z.article("Bomba", "Bomba", "", c);
    z.content(b'C', "Bomba2", "", "text/css", b"", c);
    z.raw_clusters.insert(c, raw);
    z.build()
}

fn zstd_cluster(blocks: Vec<(u8, Vec<u8>, u32)>, fcs: Option<u64>) -> Vec<u8> {
    let mut raw = vec![5u8];
    raw.extend(zstd_frame(&blocks, fcs));
    raw
}

/// Run all operations on a bomb ZIM: errors only, memory bounded by the cluster limit.
fn defuse(what: &str, zim: Vec<u8>, max_alloc: usize) {
    let w = what.to_string();
    guarded(what, 300, move || {
        let b = Book::open_source(Box::new(MemSource(zim)), "bomb.zim").unwrap();
        for p in ["Bomba", "Bomba2"] {
            let r = b.get(p.into());
            assert!(matches!(r, Err(CoreError::InvalidZim(_))), "{w}: get {p} = {:?}", r.map(|r| r.data.len()));
            let _ = b.content_length(p.into());
            let _ = b.get_range(p.into(), 0, 10);
        }
        assert_eq!(b.get("Brno".into()).unwrap().data, b"<h1>Brno</h1>", "{w}: the rest still works");
        let mut cur = Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
        let _ = b.suggest("bomba".into(), 5);
    })
    .clean(what, max_alloc);
}

#[test]
fn zstd_bomb_is_refused_within_the_cluster_limit() {
    // offset table of 2 blobs claiming 300 MB, then 300 MB of zeros in 4-byte RLE blocks
    let n: u64 = 300 << 20;
    let mut table = Vec::new();
    for o in [12u32, 12 + n as u32, 12 + n as u32] {
        table.extend_from_slice(&o.to_le_bytes());
    }
    let mut blocks = vec![(0u8, table, 0u32)];
    blocks.extend(rle_blocks(0, n));
    defuse("zstd bomb", zim_with_raw_cluster(zstd_cluster(blocks, None)), CLUSTER_LIMIT + (1 << 20));
}

#[test]
fn zstd_offset_table_bomb_costs_no_memory() {
    // first offset 0x0ffffffc: a "64M blob" table, backed by 300 MB of zeros
    let mut blocks = vec![(0u8, 0x0fff_fffcu32.to_le_bytes().to_vec(), 0u32)];
    blocks.extend(rle_blocks(0, 300 << 20));
    let zim = zim_with_raw_cluster(zstd_cluster(blocks, None));
    // the index build reads only offset tables (blob sizes) and metadata streams
    guarded("offset table bomb", 300, move || {
        let b = Book::open_source(Box::new(MemSource(zim)), "bomb.zim").unwrap();
        let mut cur = Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
        let _ = b.info();
    })
    .clean("offset table bomb", SMALL);
}

#[test]
fn zstd_lying_content_sizes() {
    for fcs in [Some(1u64 << 40), Some(u64::MAX - 1), Some(3)] {
        let mut table = Vec::new();
        for o in [12u32, 12 + 4096, 12 + 4096] {
            table.extend_from_slice(&o.to_le_bytes());
        }
        let mut blocks = vec![(0u8, table, 0u32)];
        blocks.extend(rle_blocks(b'a', 4096));
        let zim = zim_with_raw_cluster(zstd_cluster(blocks, fcs));
        let what = format!("zstd frame declaring {fcs:?} bytes");
        guarded(&what, 60, move || {
            let b = Book::open_source(Box::new(MemSource(zim)), "fcs.zim").unwrap();
            let _ = b.get("Bomba".into());
            let _ = b.get("Bomba2".into());
        })
        .clean(&what, SMALL);
    }
}

#[test]
fn xz_bomb_is_refused_within_the_cluster_limit() {
    use std::io::Write;
    use std::process::{Command, Stdio};
    let Ok(mut child) =
        Command::new("xz").args(["-0", "-c", "-T1"]).stdin(Stdio::piped()).stdout(Stdio::piped()).spawn()
    else {
        eprintln!("skipping: no xz command");
        return;
    };
    let mut stdin = child.stdin.take().unwrap();
    let writer = std::thread::spawn(move || {
        let n: u64 = 300 << 20;
        for o in [12u32, 12 + n as u32, 12 + n as u32] {
            stdin.write_all(&o.to_le_bytes()).unwrap();
        }
        let zeros = vec![0u8; 1 << 20];
        for _ in 0..300 {
            stdin.write_all(&zeros).unwrap();
        }
    });
    let out = child.wait_with_output().unwrap();
    writer.join().unwrap();
    let mut raw = vec![4u8];
    raw.extend(out.stdout);
    defuse("xz bomb", zim_with_raw_cluster(raw), SMALL);
}

/// The sample with its last (listing) cluster stretched to the end of a sparse file of
/// `extra` more bytes: no checksum, so the last cluster ends at the end of the file.
fn stretched(extra: u64, patch: impl FnOnce(&mut Vec<u8>, &Layout)) -> Sparse {
    let mut b = sample().build();
    put_le(&mut b, 72, 8, 0); // no checksum
    let l = layout(&b);
    patch(&mut b, &l);
    let size = b.len() as u64 + extra;
    Sparse { data: b, size }
}

fn dirent_named(b: &[u8], l: &Layout, path: &str) -> usize {
    let want = [path.as_bytes(), &[0]].concat();
    l.dirents.iter().map(|&d| d as usize).find(|&d| b[d + 16..].starts_with(&want)).unwrap()
}

#[test]
fn huge_declared_blob_is_never_allocated() {
    // The v1 listing blob (uncompressed, last cluster) declared 900 MB long, and the
    // 48 px illustration pointed at it: metadata, listing and illustration must not
    // allocate it.
    let src = stretched(900 << 20, |b, l| {
        let last = *l.cluster_pos.last().unwrap() as usize;
        let end_of_v1 = last + 1 + 8; // table [start, end v0, end v1]
        let v = get_le(b, end_of_v1, 4) + (900 << 20) - 64;
        put_le(b, end_of_v1, 4, v);
        let ill = dirent_named(b, l, "Illustration_48x48@1");
        put_le(b, ill + 8, 4, l.clusters as u64 - 1);
        put_le(b, ill + 12, 4, 1);
    });
    guarded("huge blob", 120, move || {
        let b = Book::open_source(Box::new(src), "huge.zim").unwrap();
        let i = b.info();
        assert_eq!(i.title, "Ukázková kniha");
        let _ = b.illustration(48);
        assert!(b.get("X/listing/titleOrdered/v1".into()).is_err(), "get() refuses > 256 MB");
        assert_eq!(b.get_range("X/listing/titleOrdered/v1".into(), 0, 16).unwrap().len(), 16);
        let _ = b.suggest("Praha".into(), 10);
        let _ = b.random_article();
        let mut cur = Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
    })
    .clean("huge declared blob", SMALL);
}

#[test]
fn huge_compressed_cluster_is_not_read_whole() {
    // The last cluster turned into a 150 MB "zstd" cluster of zeros.
    let src = stretched(150 << 20, |b, l| {
        put_le(b, *l.cluster_pos.last().unwrap() as usize, 1, 5);
    });
    guarded("huge zstd cluster", 120, move || {
        let b = Book::open_source(Box::new(src), "huge.zim").unwrap();
        let _ = b.info();
        assert!(b.get("X/listing/titleOrdered/v1".into()).is_err());
        let _ = b.suggest("Praha".into(), 10);
        assert_eq!(b.get("Brno".into()).unwrap().data, b"<h1>Brno</h1>");
    })
    .clean("huge compressed cluster", SMALL);
}

#[test]
fn redirect_loops_and_dead_ends() {
    let mut z = sample();
    let c = z.cluster(Comp::None, false);
    z.redirect(b'C', "Self", "Self", b'C', "Self")
        .redirect(b'C', "Nowhere", "Nowhere", b'C', "Does_not_exist")
        .article("Konec", "Konec", "<p>konec</p>", c);
    for i in 0..11 {
        let to = if i == 10 { "Konec".to_string() } else { format!("Chain_{:02}", i + 1) };
        z.redirect(b'C', &format!("Chain_{i:02}"), &format!("Chain {i:02}"), b'C', &to);
    }
    let zim = z.build();
    guarded("redirects", 60, move || {
        let b = Book::open_source(Box::new(MemSource(zim.clone())), "r.zim").unwrap();
        for p in ["Self", "Loop1", "Loop2", "Nowhere", "Chain_00"] {
            assert!(b.get(p.into()).is_err(), "{p}");
        }
        assert_eq!(b.get("Chain_01".into()).unwrap().path, "Konec", "10 hops is fine");
        let mut cur = Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
        b.attach_index(SearchIndex::from_vec(cur.into_inner()).unwrap()).unwrap();
        for q in ["self", "loop", "chain", "nowhere", "c"] {
            for s in b.suggest(q.into(), 50) {
                assert!(b.get(s.path.clone()).is_ok(), "suggested {s:?} does not open");
            }
        }
        for _ in 0..50 {
            if let Some(p) = b.random_article() {
                assert!(b.get(p).is_ok());
            }
        }
    })
    .clean("redirects", SMALL);
}

#[test]
fn absurd_limits() {
    let zim = sample().build();
    guarded("absurd limits", 60, move || {
        let b = Book::open_source(Box::new(MemSource(zim.clone())), "l.zim").unwrap();
        assert!(!b.suggest("Pra".into(), u32::MAX).is_empty());
        let lib = Library::new();
        lib.add(b.clone());
        assert!(!lib.suggest_all("Pra".into(), u32::MAX).is_empty());
        let mut cur = Cursor::new(Vec::new());
        offlinka_core::index::build(b.archive(), &mut cur, &mut |_: &str| {}).unwrap();
        b.attach_index(SearchIndex::from_vec(cur.into_inner()).unwrap()).unwrap();
        assert!(!b.suggest("Pra".into(), u32::MAX).is_empty());
        assert!(!lib.suggest_all("Pra".into(), u32::MAX).is_empty());
        assert_eq!(b.get_range("video.webm".into(), 0, u32::MAX).unwrap().len(), 200_000);
    })
    .clean("absurd limits", SMALL);
}

/// Seeds for cargo-fuzz (`core/fuzz/`): a small book without the big video, its index,
/// and the heavy-prefix index.
#[test]
#[ignore]
fn write_fuzz_corpus() {
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/fuzz/corpus");
    for d in ["zim", "offidx"] {
        std::fs::create_dir_all(format!("{dir}/{d}")).unwrap();
    }
    let mut z = sample();
    z.entries.retain(|e| e.path != "video.webm");
    let book = z.build();
    std::fs::write(format!("{dir}/book.zim"), &book).unwrap();
    std::fs::write(format!("{dir}/zim/book.zim"), &book).unwrap();
    std::fs::write(format!("{dir}/offidx/book.offidx"), index_of(&book)).unwrap();
    std::fs::write(format!("{dir}/offidx/heavy.offidx"), index_of(&heavy_zim())).unwrap();
}

/// Entries naming blob `u32::MAX` (or one past the end) in every cluster kind:
/// `InvalidZim`, never a panic. With a 32-bit `usize` (armeabi-v7a) `blob + 1` used to
/// wrap; run it there through Miri: `cargo +nightly-2026-10-02 miri test
/// --no-default-features --features zstd-pure --target i686-unknown-linux-gnu --test fuzz miri_`.
#[test]
fn miri_blob_index_out_of_range_in_every_cluster_kind() {
    let base = sample().build();
    let l = layout(&base);
    // none, zstd, xz, zstd extended, none extended, zstd metadata
    for path in ["Brno", "Český_Krumlov", "Praha", "Pražský_hrad", "Krumlovský_zámek", "Title"] {
        for blob in [u32::MAX, u32::MAX - 1, 0x8000_0000, 64] {
            let mut b = base.clone();
            let d = dirent_named(&b, &l, path);
            put_le(&mut b, d + 12, 4, blob as u64);
            let book = Book::open_source(Box::new(MemSource(b)), "blob.zim").unwrap();
            let p = if path == "Title" { "M/Title".to_string() } else { path.to_string() };
            assert!(matches!(book.get(p.clone()), Err(CoreError::InvalidZim(_))), "{path} blob {blob}");
            assert!(book.content_length(p.clone()).is_err());
            assert!(book.get_range(p, 0, 10).is_err());
            let _ = book.info();
            assert_eq!(offlinka_core::caught_panics(), 0, "{path} blob {blob}: panic caught at the FFI boundary");
        }
    }
}

/// An honest but big xz cluster (old ZIMs): refused as too big for a phone instead of
/// letting lzma-rs hold it in memory several times over.
#[test]
fn big_xz_cluster_is_unsupported_not_decoded() {
    use std::io::Write;
    use std::process::{Command, Stdio};
    let Ok(mut child) =
        Command::new("xz").args(["-0", "-c", "-T1"]).stdin(Stdio::piped()).stdout(Stdio::piped()).spawn()
    else {
        eprintln!("skipping: no xz command");
        return;
    };
    let mut stdin = child.stdin.take().unwrap();
    let n: u64 = 100 << 20;
    let writer = std::thread::spawn(move || {
        for o in [12u32, 12 + n as u32, 12 + n as u32] {
            stdin.write_all(&o.to_le_bytes()).unwrap();
        }
        let text: Vec<u8> = (0..1u32 << 20).map(|i| b"abcdefgh"[(i % 7) as usize]).collect();
        for _ in 0..100 {
            stdin.write_all(&text).unwrap();
        }
    });
    let out = child.wait_with_output().unwrap();
    writer.join().unwrap();
    let mut raw = vec![4u8];
    raw.extend(out.stdout);
    let zim = zim_with_raw_cluster(raw);
    guarded("100 MB xz cluster", 120, move || {
        let b = Book::open_source(Box::new(MemSource(zim)), "xz.zim").unwrap();
        assert!(matches!(b.get("Bomba".into()), Err(CoreError::Unsupported(_))));
        assert_eq!(b.get("Praha".into()).unwrap().data, b"<h1>Praha</h1>", "small xz clusters still work");
    })
    .clean("100 MB xz cluster", SMALL);
}
