//! The `.offidx` title search index: built once per book by `offlinka-cli index`,
//! shipped next to the ZIM, read by the apps.
//!
//! Records are (display entry, target entry) pairs, one per article and one per
//! redirect that ends at an article. Record ids are assigned in rank order
//! (articles before redirects, shorter normalised titles first, then shorter titles as
//! written, then alphabetical), so
//! "best" always means "smallest id" and top-k is a min-selection.
//!
//! Two `fst` maps take normalised keys to their ordinal: the full title, and every word
//! suffix of an article title ("okres cesky krumlov" → "cesky krumlov", "krumlov").
//! Ordinals index a CSR posting table (`offsets[o]..offsets[o+1]` in `postings`), so a
//! prefix is one contiguous ordinal range: two `fst` lookups and two reads. Prefixes
//! with more than [`HEAVY_THRESHOLD`] keys have a precomputed top list instead.
//!
//! Unplug safety: only the `fst` maps live in memory; records, postings and top lists
//! are read with `pread`, so a pulled drive gives an I/O error, never a SIGBUS from a
//! memory map. Index files up to 4 MB are read into memory whole.
//!
//! File layout (little endian, sections 8-byte aligned):
//! ```text
//! 0   magic "OFFIDX\0\x01"      8
//! 8   version u32 (4), flags u32
//! 16  ZIM uuid                   16
//! 32  ZIM entry count u32, record count u32
//! 40  section count u32 (10), article record count A u32
//! 48  10 x (offset u64, len u64):
//!     0 title fst (key → ordinal)       1 word fst (key → ordinal)
//!     2 title heavy fst (prefix → off)  3 word heavy fst (prefix → off)
//!     4 records                         5 title offsets [u32; keys+1]
//!     6 title postings [u32]            7 word offsets [u32; keys+1]
//!     8 word postings [u32]             9 heavy lists [u32]: n, ids…
//! ```
//! Records hold ZIM directory-entry *offsets* (one read per title, no path pointer
//! lookup): ids < A are articles, `[u64 dirent offset; A]`; the rest are redirects,
//! `[u64 display offset, u64 target offset]` each. Word-suffix keys come from article
//! titles only.
//! Postings of a key are ascending record ids, one per target.

use std::collections::HashMap;
use std::io::Write;
use std::ops::Range;

use fst::{IntoStreamer, Streamer};

use crate::error::{invalid, CoreError, Result};
use crate::normalize::{normalize, word_starts};
use crate::reader::{FileSource, MemSource, ReadAt};
use crate::zim::{Archive, Kind};

pub const MAGIC: &[u8; 8] = b"OFFIDX\0\x01";
pub const VERSION: u32 = 4;
const SECTIONS: usize = 10;
const HEADER_LEN: usize = 48 + SECTIONS * 16;
/// Index files up to this size are read into memory whole.
const SMALL_INDEX: u64 = 4 << 20;
/// A prefix with more keys than this gets a precomputed top list.
pub const HEAVY_THRESHOLD: usize = 1024;
/// Length of each precomputed top list.
pub const HEAVY_TOP: usize = 64;
/// At most this many word-suffix keys per title.
const MAX_WORD_KEYS: usize = 8;
/// Safety cap for reading a (non-heavy) prefix range.
const RANGE_CAP: u64 = 65_536;
/// Read a range's postings in one go up to this many ids; beyond, per key, best first.
const MAX_POSTINGS_READ: u64 = 16_384;

/// Match class, best first.
pub const CLASS_EXACT: u8 = 0;
pub const CLASS_PREFIX: u8 = 1;
pub const CLASS_WORD_EXACT: u8 = 2;
pub const CLASS_WORD_PREFIX: u8 = 3;

#[derive(Debug, Clone, Copy, Default)]
struct Sec {
    off: u64,
    len: u64,
}

/// One of the two key maps with its posting table and top lists.
struct KeyMap {
    fst: fst::Map<Vec<u8>>,
    heavy: fst::Map<Vec<u8>>,
    offsets: Sec,
    postings: Sec,
}

pub struct SearchIndex {
    src: Box<dyn ReadAt>,
    in_memory: bool,
    pub uuid: [u8; 16],
    pub entry_count: u32,
    pub record_count: u32,
    pub article_records: u32,
    pub byte_len: u64,
    title: KeyMap,
    word: KeyMap,
    records: Sec,
    heavy: Sec,
}

fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes(b[i * 4..i * 4 + 4].try_into().unwrap())
}

fn first_ordinal_ge(map: &fst::Map<Vec<u8>>, key: &[u8]) -> u64 {
    map.range().ge(key).into_stream().next().map(|(_, v)| v).unwrap_or(map.len() as u64)
}

impl SearchIndex {
    /// Open an index file. Small files are read whole; big ones keep the file open
    /// and only their `fst` maps in memory.
    pub fn open(src: FileSource) -> Result<SearchIndex> {
        Self::open_with_limit(src, SMALL_INDEX)
    }

    /// Like [`SearchIndex::open`], reading the file whole only up to `read_whole` bytes.
    pub fn open_with_limit(src: FileSource, read_whole: u64) -> Result<SearchIndex> {
        let size = src.size();
        let in_memory = size <= read_whole;
        let src: Box<dyn ReadAt> = if in_memory {
            let mut v = vec![0u8; size as usize];
            src.read_exact_at(&mut v, 0)?;
            Box::new(MemSource(v))
        } else {
            Box::new(src)
        };
        Self::from_source(src, in_memory)
    }

    pub fn from_vec(v: Vec<u8>) -> Result<SearchIndex> {
        Self::from_source(Box::new(MemSource(v)), true)
    }

    fn from_source(src: Box<dyn ReadAt>, in_memory: bool) -> Result<SearchIndex> {
        let size = src.size();
        if size < HEADER_LEN as u64 {
            return Err(invalid("not an Offlinka search index (.offidx)"));
        }
        let mut b = vec![0u8; HEADER_LEN];
        src.read_exact_at(&mut b, 0)?;
        if &b[0..8] != MAGIC {
            return Err(invalid("not an Offlinka search index (.offidx)"));
        }
        let version = u32_at(&b, 2);
        if version != VERSION {
            return Err(CoreError::Unsupported(format!("search index version {version} (expected {VERSION})")));
        }
        let mut uuid = [0u8; 16];
        uuid.copy_from_slice(&b[16..32]);
        let entry_count = u32_at(&b, 8);
        let record_count = u32_at(&b, 9);
        let article_records = u32_at(&b, 11);
        if article_records > record_count || u32_at(&b, 10) as usize != SECTIONS {
            return Err(invalid("search index: bad header"));
        }
        let mut secs = [Sec::default(); SECTIONS];
        for (i, sec) in secs.iter_mut().enumerate() {
            let o = 48 + i * 16;
            let off = u64::from_le_bytes(b[o..o + 8].try_into().unwrap());
            let len = u64::from_le_bytes(b[o + 8..o + 16].try_into().unwrap());
            if off.checked_add(len).is_none_or(|e| e > size) {
                return Err(invalid("search index truncated"));
            }
            *sec = Sec { off, len };
        }
        let load = |s: Sec| -> Result<fst::Map<Vec<u8>>> {
            let mut v = vec![0u8; s.len as usize];
            src.read_exact_at(&mut v, s.off)?;
            fst::Map::new(v).map_err(|e| invalid(format!("search index: {e}")))
        };
        let need = article_records as u64 * 8 + (record_count - article_records) as u64 * 16;
        if secs[4].len < need {
            return Err(invalid("search index: records truncated"));
        }
        let title = KeyMap { fst: load(secs[0])?, heavy: load(secs[2])?, offsets: secs[5], postings: secs[6] };
        let word = KeyMap { fst: load(secs[1])?, heavy: load(secs[3])?, offsets: secs[7], postings: secs[8] };
        for m in [&title, &word] {
            if m.offsets.len < (m.fst.len() as u64 + 1) * 4 {
                return Err(invalid("search index: offsets truncated"));
            }
        }
        Ok(SearchIndex {
            src,
            in_memory,
            uuid,
            entry_count,
            record_count,
            article_records,
            byte_len: size,
            title,
            word,
            records: secs[4],
            heavy: secs[9],
        })
    }

    /// `n` u32 values starting at u32 position `start` of a section.
    fn read_u32s(&self, s: Sec, start: u64, n: u64) -> Result<Vec<u32>> {
        if n == 0 {
            return Ok(Vec::new());
        }
        if (start + n) * 4 > s.len {
            return Err(invalid("search index: read outside a section"));
        }
        let mut b = vec![0u8; (n * 4) as usize];
        self.src.read_exact_at(&mut b, s.off + start * 4)?;
        Ok(b.chunks_exact(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())).collect())
    }

    /// (display, target) directory-entry offsets of a record (equal for an article).
    pub fn record(&self, id: u32) -> Result<(u64, u64)> {
        let (pos, n) = if id < self.article_records {
            (id as u64 * 8, 8)
        } else if id < self.record_count {
            (self.article_records as u64 * 8 + (id - self.article_records) as u64 * 16, 16)
        } else {
            return Err(invalid("search index: record id out of range"));
        };
        let mut b = [0u8; 16];
        self.src.read_exact_at(&mut b[..n], self.records.off + pos)?;
        let d = u64::from_le_bytes(b[..8].try_into().unwrap());
        let t = if n == 16 { u64::from_le_bytes(b[8..].try_into().unwrap()) } else { d };
        Ok((d, t))
    }

    /// Record ids of the keys with ordinals `lo..hi`.
    fn postings(&self, m: &KeyMap, lo: u64, hi: u64, keep: usize) -> Result<Vec<u32>> {
        if lo >= hi {
            return Ok(Vec::new());
        }
        let offs = self.read_u32s(m.offsets, lo, hi - lo + 1)?;
        if offs.windows(2).any(|w| w[1] < w[0]) {
            return Err(invalid("search index: bad offsets"));
        }
        let (start, end) = (offs[0] as u64, *offs.last().unwrap() as u64);
        let keep = keep as u64;
        if end - start <= MAX_POSTINGS_READ.max(keep) {
            return self.read_u32s(m.postings, start, end - start);
        }
        // Keys with huge posting lists (e.g. the word "rozcestnik"): postings of a key
        // ascend, so its first `keep` ids are its best.
        let mut ids = Vec::new();
        for w in offs.windows(2) {
            let n = (w[1] - w[0]) as u64;
            ids.extend(self.read_u32s(m.postings, w[0] as u64, n.min(keep))?);
        }
        Ok(ids)
    }

    /// Exact key ids and the best `want` prefix ids of one key map.
    fn lookup(&self, m: &KeyMap, q: &str, want: usize) -> Result<(Vec<u32>, Vec<u32>)> {
        let keep = want.max(1) * 4;
        let exact = match m.fst.get(q) {
            Some(o) => self.postings(m, o, o + 1, keep)?,
            None => Vec::new(),
        };
        let prefix = if let Some(off) = m.heavy.get(q) {
            let n = self.read_u32s(self.heavy, off, 1)?[0];
            self.read_u32s(self.heavy, off + 1, n as u64)?
        } else {
            let lo = first_ordinal_ge(&m.fst, q.as_bytes());
            let mut upper = q.as_bytes().to_vec();
            upper.push(0xff); // never inside UTF-8: above every key starting with q
            let hi = first_ordinal_ge(&m.fst, &upper).min(lo + RANGE_CAP);
            let mut ids = self.postings(m, lo, hi, keep)?;
            if ids.len() > keep {
                ids.select_nth_unstable(keep);
                ids.truncate(keep);
            }
            ids.sort_unstable();
            ids
        };
        let mut exact = exact;
        exact.truncate(keep);
        Ok((exact, prefix))
    }

    /// Ranked (class, record id) hits for a normalised query; may contain duplicates
    /// and several records with the same target (the caller dedupes in order).
    pub fn search(&self, q: &str, want: usize) -> Result<Vec<(u8, u32)>> {
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let mut hits = Vec::new();
        let (e, p) = self.lookup(&self.title, q, want)?;
        hits.extend(e.into_iter().map(|id| (CLASS_EXACT, id)));
        hits.extend(p.into_iter().map(|id| (CLASS_PREFIX, id)));
        let (e, p) = self.lookup(&self.word, q, want)?;
        hits.extend(e.into_iter().map(|id| (CLASS_WORD_EXACT, id)));
        hits.extend(p.into_iter().map(|id| (CLASS_WORD_PREFIX, id)));
        hits.sort_unstable();
        Ok(hits)
    }

    pub fn title_keys(&self) -> usize {
        self.title.fst.len()
    }

    pub fn word_keys(&self) -> usize {
        self.word.fst.len()
    }

    pub fn heavy_prefixes(&self) -> usize {
        self.title.heavy.len() + self.word.heavy.len()
    }

    /// Bytes this index keeps in memory (its `fst` maps, or the whole file when small).
    pub fn memory_bytes(&self) -> u64 {
        if self.in_memory {
            return self.byte_len;
        }
        [&self.title, &self.word]
            .iter()
            .map(|m| (m.fst.as_fst().as_bytes().len() + m.heavy.as_fst().as_bytes().len()) as u64)
            .sum()
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
    /// What a phone keeps in RAM for this index (the fst maps).
    pub memory_bytes: u64,
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
    let mut dirent_offs: Vec<u64> = Vec::with_capacity(n);
    // Title length as written: breaks ties between titles that normalise alike
    // ("Ostrava" before "!!! (Ostrava)").
    let mut title_chars: Vec<u16> = Vec::with_capacity(n);
    let mut sc = DirentScanner::new(a);
    let mut done = 0u64;
    pointers(a, range.clone(), &mut |i, off| {
        let d = sc.dirent_at(off)?;
        let k = (i - base) as usize;
        dirent_offs.push(off);
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
        title_chars.push(d.title().chars().count().min(u16::MAX as usize) as u16);
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
            let tc = |r: &Rec| title_chars[(r.display - base) as usize];
            (x.redirect, x.chars, tc(x), key_of(x.display), x.display).cmp(&(
                y.redirect,
                y.chars,
                tc(y),
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
        if id as u64 >= st.articles {
            continue; // redirects: full title only
        }
        let t = sub(&arena, s, l);
        for w in word_starts(t).take(MAX_WORD_KEYS) {
            wkeys.push((s + w as u32, l - w as u32, id as u32));
        }
    }
    drop(spans);

    let mut heavy_lists: Vec<u32> = Vec::new();
    let mut heavy_seen: HashMap<Vec<u32>, u32> = HashMap::new();
    progress(&format!("building title map ({} keys)", tkeys.len()));
    let t = build_map(&arena, &mut tkeys, &targets, &mut heavy_lists, &mut heavy_seen)?;
    drop(tkeys);
    progress(&format!("building word map ({} keys)", wkeys.len()));
    let w = build_map(&arena, &mut wkeys, &targets, &mut heavy_lists, &mut heavy_seen)?;
    drop(wkeys);

    let mut records = Vec::with_capacity(displays.len() * 16);
    for (id, (d, t)) in displays.iter().zip(&targets).enumerate() {
        records.extend_from_slice(&dirent_offs[(d - base) as usize].to_le_bytes());
        if id as u64 >= st.articles {
            records.extend_from_slice(&dirent_offs[(t - base) as usize].to_le_bytes());
        }
    }
    let to_bytes = |v: &[u32]| -> Vec<u8> { v.iter().flat_map(|x| x.to_le_bytes()).collect() };
    let (t_offs, t_post, w_offs, w_post) =
        (to_bytes(&t.offsets), to_bytes(&t.postings), to_bytes(&w.offsets), to_bytes(&w.postings));
    let heavy_b = to_bytes(&heavy_lists);
    let sections: [&[u8]; SECTIONS] =
        [&t.fst, &w.fst, &t.heavy_fst, &w.heavy_fst, &records, &t_offs, &t_post, &w_offs, &w_post, &heavy_b];

    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.extend_from_slice(&VERSION.to_le_bytes());
    header.extend_from_slice(&0u32.to_le_bytes());
    header.extend_from_slice(&a.header.uuid);
    header.extend_from_slice(&a.header.entry_count.to_le_bytes());
    header.extend_from_slice(&(displays.len() as u32).to_le_bytes());
    header.extend_from_slice(&(SECTIONS as u32).to_le_bytes());
    header.extend_from_slice(&(st.articles as u32).to_le_bytes());
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
    st.postings_bytes = (t_offs.len() + t_post.len() + w_offs.len() + w_post.len()) as u64;
    st.memory_bytes = (t.fst.len() + w.fst.len() + t.heavy_fst.len() + w.heavy_fst.len()) as u64;
    st.total_bytes = written;
    Ok(st)
}

struct BuiltMap {
    fst: Vec<u8>,
    heavy_fst: Vec<u8>,
    offsets: Vec<u32>,
    postings: Vec<u32>,
    keys: u64,
    heavy: u64,
}

fn build_map(
    arena: &str,
    keys: &mut [(u32, u32, u32)],
    targets: &[u32],
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

    // key → ordinal; ordinal → offsets[o]..offsets[o+1] in postings
    let mut b = fst::MapBuilder::memory();
    let mut offsets = Vec::with_capacity(uniq.len() + 1);
    let mut postings = Vec::with_capacity(ids.len());
    for u in 0..uniq.len() {
        offsets.push(postings.len() as u32);
        postings.extend_from_slice(list(u));
        b.insert(ukey(u), u as u64).map_err(|e| invalid(format!("fst: {e}")))?;
    }
    offsets.push(postings.len() as u32);
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
        offsets,
        postings,
        heavy_fst: hb.into_inner().map_err(|e| invalid(format!("fst: {e}")))?,
        keys: uniq.len() as u64,
        heavy: hentries.len() as u64,
    })
}
