//! The `.offidx` title search index: built once per book by `offlinka-cli index`,
//! shipped next to the ZIM, memory-mapped by the apps.
//!
//! Records are (display entry, target entry) pairs, one per article and one per
//! redirect that ends at an article. Record ids are assigned in rank order
//! (articles before redirects, shorter normalised titles first, then alphabetical), so
//! "best" always means "smallest id" and top-k is a min-selection.
//!
//! Two `fst` maps point normalised keys at records: the full title, and every word
//! suffix of the title ("okres cesky krumlov" → "cesky krumlov", "krumlov"). A third
//! and fourth `fst` hold precomputed top lists for "heavy" prefixes (more than
//! [`HEAVY_THRESHOLD`] keys), so a one-letter query never streams a huge range.
//!
//! File layout (little endian, sections 8-byte aligned):
//! ```text
//! 0   magic "OFFIDX\0\x01"      8
//! 8   version u32, flags u32
//! 16  ZIM uuid                   16
//! 32  ZIM entry count u32, record count u32
//! 40  section count u32 (7), reserved u32
//! 48  7 x (offset u64, len u64): title fst, word fst, title heavy fst, word heavy fst,
//!     records [u32 display, u32 target], postings [u32], heavy lists [u32]
//! ```
//! fst values: `id << 1` for a single record, `(offset << 1) | 1` for a posting list
//! `postings[offset] = n, ids...`. Heavy fst values: offset into heavy lists, `n, ids...`.

use std::collections::HashMap;
use std::io::Write;
use std::ops::Range;
use std::sync::Arc;

use fst::{Automaton, IntoStreamer, Streamer};

use crate::error::{invalid, CoreError, Result};
use crate::normalize::{normalize, word_starts};
use crate::reader::FileSource;
use crate::zim::{Archive, Kind};

pub const MAGIC: &[u8; 8] = b"OFFIDX\0\x01";
pub const VERSION: u32 = 1;
const HEADER_LEN: usize = 48 + 7 * 16;
/// A prefix with more keys than this gets a precomputed top list.
pub const HEAVY_THRESHOLD: usize = 1024;
/// Length of each precomputed top list.
pub const HEAVY_TOP: usize = 64;
/// At most this many word-suffix keys per title.
const MAX_WORD_KEYS: usize = 8;
/// Safety cap for streaming a (non-heavy) prefix range.
const STREAM_CAP: usize = 50_000;

/// Match class, best first.
pub const CLASS_EXACT: u8 = 0;
pub const CLASS_PREFIX: u8 = 1;
pub const CLASS_WORD_EXACT: u8 = 2;
pub const CLASS_WORD_PREFIX: u8 = 3;

enum Bytes {
    Mmap(memmap2::Mmap),
    Vec(Vec<u8>),
}

impl AsRef<[u8]> for Bytes {
    fn as_ref(&self) -> &[u8] {
        match self {
            Bytes::Mmap(m) => m,
            Bytes::Vec(v) => v,
        }
    }
}

/// A section of the shared index bytes (lets `fst::Map` own its data).
#[derive(Clone)]
pub struct Section {
    data: Arc<Bytes>,
    range: Range<usize>,
}

impl AsRef<[u8]> for Section {
    fn as_ref(&self) -> &[u8] {
        &self.data.as_ref().as_ref()[self.range.clone()]
    }
}

pub struct SearchIndex {
    pub uuid: [u8; 16],
    pub entry_count: u32,
    pub record_count: u32,
    pub byte_len: u64,
    title: fst::Map<Section>,
    word: fst::Map<Section>,
    title_heavy: fst::Map<Section>,
    word_heavy: fst::Map<Section>,
    records: Section,
    postings: Section,
    heavy: Section,
}

fn u32_at(b: &[u8], i: usize) -> Option<u32> {
    b.get(i * 4..i * 4 + 4).map(|s| u32::from_le_bytes(s.try_into().unwrap()))
}

impl SearchIndex {
    /// Open an index file (memory-mapped when possible).
    pub fn open(src: FileSource) -> Result<SearchIndex> {
        // SAFETY: the index files are read-only edition data; nobody truncates them while mapped.
        let bytes = match unsafe { memmap2::Mmap::map(src.file()) } {
            Ok(m) => Bytes::Mmap(m),
            Err(_) => {
                use crate::reader::ReadAt;
                let mut v = vec![0u8; src.size() as usize];
                src.read_exact_at(&mut v, 0)?;
                Bytes::Vec(v)
            }
        };
        Self::from_bytes(bytes)
    }

    pub fn from_vec(v: Vec<u8>) -> Result<SearchIndex> {
        Self::from_bytes(Bytes::Vec(v))
    }

    fn from_bytes(bytes: Bytes) -> Result<SearchIndex> {
        let data = Arc::new(bytes);
        let b: &[u8] = data.as_ref().as_ref();
        if b.len() < HEADER_LEN || &b[0..8] != MAGIC {
            return Err(invalid("not an Offlinka search index (.offidx)"));
        }
        let version = u32_at(b, 2).unwrap();
        if version != VERSION {
            return Err(CoreError::Unsupported(format!("search index version {version}")));
        }
        let mut uuid = [0u8; 16];
        uuid.copy_from_slice(&b[16..32]);
        let entry_count = u32_at(b, 8).unwrap();
        let record_count = u32_at(b, 9).unwrap();
        if u32_at(b, 10).unwrap() != 7 {
            return Err(invalid("search index: bad section table"));
        }
        let mut secs = Vec::with_capacity(7);
        for i in 0..7 {
            let o = 48 + i * 16;
            let off = u64::from_le_bytes(b[o..o + 8].try_into().unwrap());
            let len = u64::from_le_bytes(b[o + 8..o + 16].try_into().unwrap());
            let end = off
                .checked_add(len)
                .filter(|&e| e <= b.len() as u64)
                .ok_or_else(|| invalid("search index truncated"))?;
            secs.push(Section { data: data.clone(), range: off as usize..end as usize });
        }
        let map = |s: &Section| fst::Map::new(s.clone()).map_err(|e| invalid(format!("search index: {e}")));
        let idx = SearchIndex {
            uuid,
            entry_count,
            record_count,
            byte_len: b.len() as u64,
            title: map(&secs[0])?,
            word: map(&secs[1])?,
            title_heavy: map(&secs[2])?,
            word_heavy: map(&secs[3])?,
            records: secs[4].clone(),
            postings: secs[5].clone(),
            heavy: secs[6].clone(),
        };
        if (idx.records.as_ref().len() as u64) < idx.record_count as u64 * 8 {
            return Err(invalid("search index: records truncated"));
        }
        Ok(idx)
    }

    /// (display entry, target entry) of a record.
    pub fn record(&self, id: u32) -> Option<(u32, u32)> {
        let r = self.records.as_ref();
        Some((u32_at(r, id as usize * 2)?, u32_at(r, id as usize * 2 + 1)?))
    }

    fn push_value(&self, v: u64, out: &mut Vec<u32>) {
        if v & 1 == 0 {
            out.push((v >> 1) as u32);
        } else {
            let p = self.postings.as_ref();
            let off = (v >> 1) as usize;
            let n = u32_at(p, off).unwrap_or(0) as usize;
            out.extend((0..n).filter_map(|i| u32_at(p, off + 1 + i)));
        }
    }

    fn heavy_list(&self, v: u64, out: &mut Vec<u32>) {
        let h = self.heavy.as_ref();
        let off = v as usize;
        let n = u32_at(h, off).unwrap_or(0) as usize;
        out.extend((0..n).filter_map(|i| u32_at(h, off + 1 + i)));
    }

    /// Exact key ids and the best `want` prefix ids for one map.
    fn lookup(&self, map: &fst::Map<Section>, heavy: &fst::Map<Section>, q: &str, want: usize) -> (Vec<u32>, Vec<u32>) {
        let mut exact = Vec::new();
        if let Some(v) = map.get(q) {
            self.push_value(v, &mut exact);
        }
        let mut prefix = Vec::new();
        if let Some(v) = heavy.get(q) {
            self.heavy_list(v, &mut prefix);
        } else {
            let aut = fst::automaton::Str::new(q).starts_with();
            let mut s = map.search(aut).into_stream();
            let mut n = 0;
            while let Some((_, v)) = s.next() {
                self.push_value(v, &mut prefix);
                n += 1;
                if n >= STREAM_CAP {
                    break;
                }
            }
            let keep = want.max(1) * 4;
            if prefix.len() > keep {
                prefix.select_nth_unstable(keep);
                prefix.truncate(keep);
            }
            prefix.sort_unstable();
        }
        (exact, prefix)
    }

    /// Ranked (class, record id) hits for a normalised query; may contain duplicates
    /// and several records with the same target (the caller dedupes in order).
    pub fn search(&self, q: &str, want: usize) -> Vec<(u8, u32)> {
        if q.is_empty() {
            return Vec::new();
        }
        let mut hits = Vec::new();
        let (e, p) = self.lookup(&self.title, &self.title_heavy, q, want);
        hits.extend(e.into_iter().map(|id| (CLASS_EXACT, id)));
        hits.extend(p.into_iter().map(|id| (CLASS_PREFIX, id)));
        let (e, p) = self.lookup(&self.word, &self.word_heavy, q, want);
        hits.extend(e.into_iter().map(|id| (CLASS_WORD_EXACT, id)));
        hits.extend(p.into_iter().map(|id| (CLASS_WORD_PREFIX, id)));
        hits.sort_unstable();
        hits
    }

    pub fn title_keys(&self) -> usize {
        self.title.len()
    }

    pub fn word_keys(&self) -> usize {
        self.word.len()
    }

    pub fn heavy_prefixes(&self) -> usize {
        self.title_heavy.len() + self.word_heavy.len()
    }
}

// ---------------------------------------------------------------- building

#[derive(Debug, Default, Clone)]
pub struct BuildStats {
    pub entries_scanned: u64,
    pub articles: u64,
    pub redirects: u64,
    pub records: u64,
    pub title_keys: u64,
    pub word_keys: u64,
    pub heavy_prefixes: u64,
    pub title_fst_bytes: u64,
    pub word_fst_bytes: u64,
    pub heavy_bytes: u64,
    pub records_bytes: u64,
    pub postings_bytes: u64,
    pub total_bytes: u64,
}

/// Reads dirents of a contiguous index range with large sequential reads.
struct DirentScanner<'a> {
    a: &'a Archive,
    win: Vec<u8>,
    win_start: u64,
}

impl<'a> DirentScanner<'a> {
    const WIN: u64 = 4 << 20;

    fn new(a: &'a Archive) -> Self {
        DirentScanner { a, win: Vec::new(), win_start: 0 }
    }

    fn dirent_at(&mut self, off: u64) -> Result<crate::zim::Dirent> {
        let in_win = off >= self.win_start && off < self.win_start + self.win.len() as u64;
        if in_win {
            if let Some(d) = crate::zim::Dirent::parse(&self.win[(off - self.win_start) as usize..])? {
                return Ok(d);
            }
        }
        // Dirents are normally stored in path order: read big windows going forward,
        // small ones when the layout jumps around.
        let forward = self.win.is_empty() || (off >= self.win_start && off < self.win_start + 2 * Self::WIN);
        let want = if forward { Self::WIN } else { 4096 };
        let len = want.min(self.a.size() - off) as usize;
        self.win.resize(len, 0);
        self.a.read_at(&mut self.win, off)?;
        self.win_start = off;
        match crate::zim::Dirent::parse(&self.win)? {
            Some(d) => Ok(d),
            None => self.a.dirent_at(off),
        }
    }
}

/// Read the path pointers of `range` in chunks.
fn pointers(a: &Archive, range: Range<u32>, f: &mut dyn FnMut(u32, u64) -> Result<()>) -> Result<()> {
    const CHUNK: u32 = 1 << 16;
    let mut i = range.start;
    while i < range.end {
        let n = CHUNK.min(range.end - i);
        let mut b = vec![0u8; n as usize * 8];
        a.read_at(&mut b, a.header.path_ptr_pos + i as u64 * 8)?;
        for (k, c) in b.chunks_exact(8).enumerate() {
            f(i + k as u32, u64::from_le_bytes(c.try_into().unwrap()))?;
        }
        i += n;
    }
    Ok(())
}

const NOT_REDIRECT: u32 = u32::MAX;

fn sub(arena: &str, start: u32, len: u32) -> &str {
    &arena[start as usize..(start + len) as usize]
}

/// Build the index for an archive and write it to `out`.
pub fn build(a: &Archive, out: &mut dyn Write, progress: &mut dyn FnMut(&str)) -> Result<BuildStats> {
    let mut st = BuildStats::default();
    let ns = a.article_ns();
    let range = a.namespace_range(ns)?;
    let base = range.start;
    let n = (range.end - range.start) as usize;
    progress(&format!("article namespace: {n} entries"));

    // Front articles from the v1 listing when present, else HTML content entries.
    let v1 = if a.new_scheme() { a.listing_blob("listing/titleOrdered/v1")? } else { None };
    let mut is_article = vec![false; n];
    let use_v1 = v1.is_some();
    if let Some(l) = &v1 {
        let len = a.title_list_len(l);
        let mut i = 0;
        while i < len {
            for e in a.title_list_slice(l, i, 1 << 16)? {
                if e >= range.start && e < range.end {
                    is_article[(e - base) as usize] = true;
                }
            }
            i += 1 << 16;
        }
    }

    // Pass 1: every dirent of the namespace, normalised title into an arena.
    let mut redirect_to = vec![NOT_REDIRECT; n];
    let mut is_content = vec![false; n];
    let mut arena = String::new();
    let mut spans: Vec<(u32, u32)> = Vec::with_capacity(n);
    let mut sc = DirentScanner::new(a);
    let mut done = 0u64;
    pointers(a, range.clone(), &mut |i, off| {
        let d = sc.dirent_at(off)?;
        let k = (i - base) as usize;
        match d.kind {
            Kind::Content { .. } => {
                is_content[k] = true;
                if !use_v1 && a.mime(&d).starts_with("text/html") {
                    is_article[k] = true;
                }
            }
            Kind::Redirect { target } => redirect_to[k] = target,
            Kind::Other => {}
        }
        let norm = normalize(d.title());
        let start = arena.len() as u32;
        arena.push_str(&norm);
        spans.push((start, norm.len() as u32));
        done += 1;
        if done % 500_000 == 0 {
            progress(&format!("scanned {done}/{n} entries"));
        }
        Ok(())
    })?;
    st.entries_scanned = done;
    if arena.len() > u32::MAX as usize {
        return Err(CoreError::Unsupported("titles too large for the index".into()));
    }

    // Records: articles, and redirects whose chain ends at an article.
    struct Rec {
        display: u32,
        target: u32,
        redirect: bool,
        chars: u32,
    }
    let mut recs: Vec<Rec> = Vec::new();
    for k in 0..n {
        let (s, l) = spans[k];
        if l == 0 {
            continue;
        }
        let chars = arena[s as usize..(s + l) as usize].chars().count() as u32;
        if is_content[k] && is_article[k] {
            recs.push(Rec { display: base + k as u32, target: base + k as u32, redirect: false, chars });
            st.articles += 1;
        } else if redirect_to[k] != NOT_REDIRECT {
            let mut t = redirect_to[k];
            let mut ok = false;
            for _ in 0..crate::zim::MAX_REDIRECTS {
                if t < range.start || t >= range.end {
                    break;
                }
                let tk = (t - base) as usize;
                if redirect_to[tk] != NOT_REDIRECT {
                    t = redirect_to[tk];
                    continue;
                }
                ok = is_content[tk] && is_article[tk];
                break;
            }
            if ok {
                recs.push(Rec { display: base + k as u32, target: t, redirect: true, chars });
                st.redirects += 1;
            }
        }
    }
    {
        let key_of = |display: u32| {
            let (s, l) = spans[(display - base) as usize];
            sub(&arena, s, l)
        };
        recs.sort_unstable_by(|x, y| {
            (x.redirect, x.chars, key_of(x.display), x.display).cmp(&(
                y.redirect,
                y.chars,
                key_of(y.display),
                y.display,
            ))
        });
    }
    st.records = recs.len() as u64;
    progress(&format!("{} records ({} articles, {} redirects)", recs.len(), st.articles, st.redirects));
    let displays: Vec<u32> = recs.iter().map(|r| r.display).collect();
    let targets: Vec<u32> = recs.iter().map(|r| r.target).collect();
    drop(recs);

    // Keys: (arena start, len, record id).
    let mut tkeys: Vec<(u32, u32, u32)> = Vec::with_capacity(displays.len());
    let mut wkeys: Vec<(u32, u32, u32)> = Vec::with_capacity(displays.len());
    for (id, &d) in displays.iter().enumerate() {
        let (s, l) = spans[(d - base) as usize];
        tkeys.push((s, l, id as u32));
        let t = sub(&arena, s, l);
        for w in word_starts(t).take(MAX_WORD_KEYS) {
            wkeys.push((s + w as u32, l - w as u32, id as u32));
        }
    }
    drop(spans);

    let mut postings: Vec<u32> = Vec::new();
    let mut heavy_lists: Vec<u32> = Vec::new();
    let mut heavy_seen: HashMap<Vec<u32>, u32> = HashMap::new();
    progress(&format!("building title map ({} keys)", tkeys.len()));
    let t = build_map(&arena, &mut tkeys, &targets, &mut postings, &mut heavy_lists, &mut heavy_seen)?;
    drop(tkeys);
    progress(&format!("building word map ({} keys)", wkeys.len()));
    let w = build_map(&arena, &mut wkeys, &targets, &mut postings, &mut heavy_lists, &mut heavy_seen)?;
    drop(wkeys);

    let mut records = Vec::with_capacity(displays.len() * 8);
    for (d, t) in displays.iter().zip(&targets) {
        records.extend_from_slice(&d.to_le_bytes());
        records.extend_from_slice(&t.to_le_bytes());
    }
    let to_bytes = |v: &[u32]| -> Vec<u8> { v.iter().flat_map(|x| x.to_le_bytes()).collect() };
    let postings_b = to_bytes(&postings);
    let heavy_b = to_bytes(&heavy_lists);
    let sections: [&[u8]; 7] = [&t.fst, &w.fst, &t.heavy_fst, &w.heavy_fst, &records, &postings_b, &heavy_b];

    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.extend_from_slice(&VERSION.to_le_bytes());
    header.extend_from_slice(&0u32.to_le_bytes());
    header.extend_from_slice(&a.header.uuid);
    header.extend_from_slice(&a.header.entry_count.to_le_bytes());
    header.extend_from_slice(&(displays.len() as u32).to_le_bytes());
    header.extend_from_slice(&7u32.to_le_bytes());
    header.extend_from_slice(&0u32.to_le_bytes());
    let mut pos = HEADER_LEN as u64;
    for s in &sections {
        pos = pos.div_ceil(8) * 8;
        header.extend_from_slice(&pos.to_le_bytes());
        header.extend_from_slice(&(s.len() as u64).to_le_bytes());
        pos += s.len() as u64;
    }
    out.write_all(&header)?;
    let mut written = HEADER_LEN as u64;
    for s in &sections {
        let pad = written.div_ceil(8) * 8 - written;
        out.write_all(&[0u8; 8][..pad as usize])?;
        out.write_all(s)?;
        written += pad + s.len() as u64;
    }
    out.flush()?;

    st.title_keys = t.keys;
    st.word_keys = w.keys;
    st.heavy_prefixes = t.heavy + w.heavy;
    st.title_fst_bytes = t.fst.len() as u64;
    st.word_fst_bytes = w.fst.len() as u64;
    st.heavy_bytes = (t.heavy_fst.len() + w.heavy_fst.len() + heavy_b.len()) as u64;
    st.records_bytes = records.len() as u64;
    st.postings_bytes = postings_b.len() as u64;
    st.total_bytes = written;
    Ok(st)
}

struct BuiltMap {
    fst: Vec<u8>,
    heavy_fst: Vec<u8>,
    keys: u64,
    heavy: u64,
}

fn build_map(
    arena: &str,
    keys: &mut [(u32, u32, u32)],
    targets: &[u32],
    postings: &mut Vec<u32>,
    heavy_lists: &mut Vec<u32>,
    heavy_seen: &mut HashMap<Vec<u32>, u32>,
) -> Result<BuiltMap> {
    keys.sort_unstable_by(|x, y| {
        sub(arena, x.0, x.1).as_bytes().cmp(sub(arena, y.0, y.1).as_bytes()).then(x.2.cmp(&y.2))
    });

    // Unique keys with their posting lists (ascending ids, one per target).
    let mut uniq: Vec<(u32, u32)> = Vec::new();
    let mut lists: Vec<(u32, u32)> = Vec::new();
    let mut ids: Vec<u32> = Vec::new();
    let mut i = 0;
    while i < keys.len() {
        let mut j = i + 1;
        while j < keys.len() && sub(arena, keys[j].0, keys[j].1) == sub(arena, keys[i].0, keys[i].1) {
            j += 1;
        }
        let start = ids.len();
        for k in &keys[i..j] {
            let t = targets[k.2 as usize];
            if !ids[start..].iter().any(|&x| targets[x as usize] == t) {
                ids.push(k.2);
            }
        }
        uniq.push((keys[i].0, keys[i].1));
        lists.push((start as u32, (ids.len() - start) as u32));
        i = j;
    }
    let ukey = |u: usize| sub(arena, uniq[u].0, uniq[u].1);
    let list = |u: usize| &ids[lists[u].0 as usize..(lists[u].0 + lists[u].1) as usize];

    let mut b = fst::MapBuilder::memory();
    for u in 0..uniq.len() {
        let l = list(u);
        let v = if l.len() == 1 {
            (l[0] as u64) << 1
        } else {
            let off = postings.len() as u64;
            postings.push(l.len() as u32);
            postings.extend_from_slice(l);
            (off << 1) | 1
        };
        b.insert(ukey(u), v).map_err(|e| invalid(format!("fst: {e}")))?;
    }
    let fst_bytes = b.into_inner().map_err(|e| invalid(format!("fst: {e}")))?;

    // Heavy prefixes: groups of consecutive keys sharing a char-boundary prefix.
    let mut heavy: Vec<(usize, usize, usize)> = Vec::new(); // (first key, prefix len, end key)
    let mut stack: Vec<(usize, usize)> = Vec::new(); // (prefix len, first key)
    for u in 0..uniq.len() {
        let k = ukey(u);
        let lcp = if u == 0 { 0 } else { ukey(u - 1).bytes().zip(k.bytes()).take_while(|(x, y)| x == y).count() };
        while let Some(&(l, s)) = stack.last() {
            if l <= lcp {
                break;
            }
            stack.pop();
            if u - s > HEAVY_THRESHOLD {
                heavy.push((s, l, u));
            }
        }
        let top = stack.last().map(|x| x.0).unwrap_or(0);
        for p in top + 1..=k.len() {
            if k.is_char_boundary(p) {
                stack.push((p, u));
            }
        }
    }
    while let Some((l, s)) = stack.pop() {
        if uniq.len() - s > HEAVY_THRESHOLD {
            heavy.push((s, l, uniq.len()));
        }
    }
    let mut hentries: Vec<(&str, u32)> = Vec::with_capacity(heavy.len());
    for &(s, l, e) in &heavy {
        let mut top: Vec<u32> = Vec::with_capacity(HEAVY_TOP + 1);
        for u in s..e {
            for &id in list(u) {
                if top.len() == HEAVY_TOP && id >= *top.last().unwrap() {
                    break; // ids within a key ascend
                }
                let t = targets[id as usize];
                if let Some(pos) = top.iter().position(|&x| targets[x as usize] == t) {
                    if top[pos] <= id {
                        continue;
                    }
                    top.remove(pos);
                }
                let at = top.partition_point(|&x| x < id);
                top.insert(at, id);
                top.truncate(HEAVY_TOP);
            }
        }
        let off = match heavy_seen.get(&top) {
            Some(&o) => o,
            None => {
                let o = heavy_lists.len() as u32;
                heavy_lists.push(top.len() as u32);
                heavy_lists.extend_from_slice(&top);
                heavy_seen.insert(top, o);
                o
            }
        };
        hentries.push((&ukey(s)[..l], off));
    }
    hentries.sort_unstable();
    let mut hb = fst::MapBuilder::memory();
    for (p, off) in &hentries {
        hb.insert(p, *off as u64).map_err(|e| invalid(format!("fst: {e}")))?;
    }
    Ok(BuiltMap {
        fst: fst_bytes,
        heavy_fst: hb.into_inner().map_err(|e| invalid(format!("fst: {e}")))?,
        keys: uniq.len() as u64,
        heavy: hentries.len() as u64,
    })
}
