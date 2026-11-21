//! The `.offidx` title search index: built by `offlinka-cli index` for the drive, or by
//! the apps themselves (`build_index`) for drives sold without one. Both produce the
//! same bytes.
//!
//! **Records** are (shown entry, target entry) pairs, one per article and one per
//! redirect that ends at an article. Each record has a static score (lower is better):
//! `LEN_COST * normalised title length + REDIRECT_COST * redirect - POP_WEIGHT *
//! log2(size of the target article) - INLINK_WEIGHT * log2(1 + redirects to it)`, quantised to a level. Record ids: articles first,
//! then redirects, each part in level order (ties: shorter title as written, then
//! alphabetical); "best" inside one match tier is the smallest (level, id).
//!
//! **Query score** = `TIER_COST[tier] + static score`; tiers are exact title, title
//! prefix, exact word suffix, word-suffix prefix. A much more popular article can thus
//! beat a weaker tier ("masaryk" → "Tomáš Garrigue Masaryk" before the stub "Masaryk").
//!
//! Two `fst` maps take normalised keys to their ordinal: the full title, and every word
//! suffix of an article title ("okres cesky krumlov" → "cesky krumlov", "krumlov").
//! Ordinals index a CSR posting table (`offsets[o]..offsets[o+1]` in `postings`), so a
//! prefix is one contiguous ordinal range: two `fst` lookups and two reads. Prefixes
//! with more than [`HEAVY_THRESHOLD`] keys have a precomputed top list instead.
//!
//! **Memory and unplug safety:** opening an index reads only its header; the `fst` maps
//! are loaded on first use (or `preload`). Records, postings and top lists are read
//! with `pread`, so a pulled drive gives an I/O error, never a SIGBUS from a memory map.
//! Index files up to 4 MB are read into memory whole.
//!
//! File layout (little endian, sections 8-byte aligned, in any order):
//! ```text
//! 0   magic "OFFIDX\0\x01"      8
//! 8   version u32 (5), flags u32
//! 16  ZIM uuid                   16
//! 32  ZIM entry count u32, record count u32
//! 40  section count u32 (11), article record count A u32
//! 48  11 x (offset u64, len u64):
//!     0 title fst (key → ordinal)       1 word fst (key → ordinal)
//!     2 title heavy fst (prefix → off)  3 word heavy fst (prefix → off)
//!     4 records                         5 title offsets [u32; keys+1]
//!     6 title postings [u32]            7 word offsets [u32; keys+1]
//!     8 word postings [u32]             9 heavy lists [u32]: n, ids…
//!     10 score levels [(u32 first id, u32 level)] ascending
//! ```
//! Records hold ZIM directory-entry *offsets* (one read per title): ids < A are
//! articles, `[u64 dirent offset; A]`; the rest are redirects, `[u64 display offset,
//! u64 target offset]` each. Postings of a key are ascending record ids, one per target.
//! Word-suffix keys come from article titles only.

use std::collections::{HashMap, HashSet};
use std::io::{Seek, SeekFrom, Write};
use std::ops::Range;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use fst::{IntoStreamer, Streamer};

use crate::error::{invalid, zeroed, CoreError, Result};
use crate::normalize::{normalize, word_starts};
use crate::reader::{FileSource, MemSource, ReadAt};
use crate::zim::{Archive, Kind};

pub const MAGIC: &[u8; 8] = b"OFFIDX\0\x01";
pub const VERSION: u32 = 5;
const SECTIONS: usize = 11;
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

/// Match tiers, best first.
pub const CLASS_EXACT: u8 = 0;
pub const CLASS_PREFIX: u8 = 1;
pub const CLASS_WORD_EXACT: u8 = 2;
pub const CLASS_WORD_PREFIX: u8 = 3;

// ---- ranking (units: one unit = a factor 2 in article size) ----
/// Query-time cost of each tier, added to the record's static score.
pub const TIER_COST: [f32; 4] = [0.0, 2.0, 3.0, 5.0];
/// Static score: per character of the normalised title.
pub(crate) const LEN_COST: f64 = 0.12;
/// Static score: a redirect (alias) instead of the article's own title.
const REDIRECT_COST: f64 = 1.0;
/// Static score: per doubling of the target article's size.
const POP_WEIGHT: f64 = 1.0;
/// Static score: per doubling of (1 + redirects pointing at the target).
const INLINK_WEIGHT: f64 = 0.5;
/// Articles smaller than this count as this size (stubs, empty fixtures).
const MIN_POP_BYTES: u64 = 1024;
/// Static score of a hit without an index (title listing fallback): an average article.
pub const FALLBACK_STATIC: f32 = -12.0;
const LEVEL_SCALE: f64 = 16.0;
const LEVEL_BIAS: f64 = 64.0;

/// Static score of a record (lower is better).
fn static_score(norm_chars: u32, redirect: bool, target_size: u64, inlinks: u32) -> f64 {
    let pop = (target_size.max(MIN_POP_BYTES) as f64).log2();
    LEN_COST * norm_chars as f64 + if redirect { REDIRECT_COST } else { 0.0 }
        - POP_WEIGHT * pop
        - INLINK_WEIGHT * (1.0 + inlinks as f64).log2()
}

fn level_of(score: f64) -> u32 {
    ((score + LEVEL_BIAS) * LEVEL_SCALE).round().clamp(0.0, u16::MAX as f64) as u32
}

fn score_of(level: u32) -> f32 {
    (level as f64 / LEVEL_SCALE - LEVEL_BIAS) as f32
}

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

/// The parts of an index kept in memory, loaded on first use.
struct Maps {
    title: KeyMap,
    word: KeyMap,
    /// (first record id, level) runs, ascending ids.
    levels: Vec<(u32, u32)>,
}

impl Maps {
    fn level(&self, id: u32) -> u32 {
        let i = self.levels.partition_point(|&(first, _)| first <= id);
        if i == 0 {
            0
        } else {
            self.levels[i - 1].1
        }
    }

    fn score(&self, id: u32) -> f32 {
        score_of(self.level(id))
    }
}

/// A ranked index hit.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct IndexHit {
    pub score: f32,
    pub class: u8,
    pub id: u32,
}

pub struct SearchIndex {
    src: Box<dyn ReadAt>,
    in_memory: bool,
    pub uuid: [u8; 16],
    pub entry_count: u32,
    pub record_count: u32,
    pub article_records: u32,
    pub byte_len: u64,
    secs: [Sec; SECTIONS],
    /// Held only to look at or swap the loaded maps, never during I/O, so `unload`
    /// (memory pressure, UI thread) never waits for a load in progress.
    maps: Mutex<Option<Arc<Maps>>>,
    /// Serialises loading (concurrent first searches wait for one load).
    loading: Mutex<()>,
    /// Bumped by `unload`; a load that started earlier is not kept.
    generation: std::sync::atomic::AtomicU64,
}

/// Every live index, so memory trimming can unload them.
fn registry() -> &'static Mutex<Vec<std::sync::Weak<SearchIndex>>> {
    static R: std::sync::OnceLock<Mutex<Vec<std::sync::Weak<SearchIndex>>>> = std::sync::OnceLock::new();
    R.get_or_init(|| Mutex::new(Vec::new()))
}

/// Remember an attached index for [`unload_all`].
pub fn register(idx: &Arc<SearchIndex>) {
    let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
    r.retain(|w| w.strong_count() > 0);
    r.push(Arc::downgrade(idx));
}

/// Unload the in-memory part of every live index (memory pressure).
pub fn unload_all() {
    let r = registry().lock().unwrap_or_else(|e| e.into_inner());
    for idx in r.iter().filter_map(|w| w.upgrade()) {
        idx.unload();
    }
}

fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes(b[i * 4..i * 4 + 4].try_into().unwrap())
}

fn first_ordinal_ge(map: &fst::Map<Vec<u8>>, key: &[u8]) -> u64 {
    map.range().ge(key).into_stream().next().map(|(_, v)| v).unwrap_or(map.len() as u64)
}

/// An `fst` map from untrusted bytes. The `fst` crate does no integrity check of its
/// own and panics (or worse) on corrupt input, and on 32-bit targets it panics outright
/// on a root address above 4 GB. So: check the trailer ourselves, then the checksum
/// every map we write carries (fst format 3, crc32c over all bytes).
fn checked_fst(v: Vec<u8>) -> Result<fst::Map<Vec<u8>>> {
    let bad = || invalid("search index: corrupt word map");
    let n = v.len() as u64;
    if n < 36 || u64::from_le_bytes(v[..8].try_into().unwrap()) != 3 {
        return Err(bad());
    }
    let root = u64::from_le_bytes(v[v.len() - 12..v.len() - 4].try_into().unwrap());
    if !(root == 0 && n == 36 || root.checked_add(21) == Some(n)) {
        return Err(bad());
    }
    let m = fst::Map::new(v).map_err(|e| invalid(format!("search index: {e}")))?;
    m.as_fst().verify().map_err(|_| bad())?;
    Ok(m)
}

impl SearchIndex {
    /// Open an index file: reads and checks the header only. Small files are read whole;
    /// big ones keep the file open, and their `fst` maps load on first use.
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

    /// An index over any byte source, read with positional reads like a big file (tests:
    /// fault injection).
    pub fn from_reader(src: Box<dyn ReadAt>) -> Result<SearchIndex> {
        Self::from_source(src, false)
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
        let need = article_records as u64 * 8 + (record_count - article_records) as u64 * 16;
        if secs[4].len < need {
            return Err(invalid("search index: records truncated"));
        }
        Ok(SearchIndex {
            src,
            in_memory,
            uuid,
            entry_count,
            record_count,
            article_records,
            byte_len: size,
            secs,
            maps: Mutex::new(None),
            loading: Mutex::new(()),
            generation: Default::default(),
        })
    }

    /// The in-memory part, loading it on first use (concurrent callers wait for one load).
    fn maps(&self) -> Result<Arc<Maps>> {
        use std::sync::atomic::Ordering::SeqCst;
        if let Some(m) = self.maps.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            return Ok(m.clone());
        }
        let _one = self.loading.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(m) = self.maps.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            return Ok(m.clone()); // loaded while we waited
        }
        let gen = self.generation.load(SeqCst);
        let m = Arc::new(self.load_maps()?);
        let mut g = self.maps.lock().unwrap_or_else(|e| e.into_inner());
        if self.generation.load(SeqCst) == gen {
            *g = Some(m.clone()); // not trimmed meanwhile: keep it
        }
        Ok(m)
    }

    fn load_maps(&self) -> Result<Maps> {
        let load = |s: Sec| -> Result<fst::Map<Vec<u8>>> {
            let mut v = zeroed(s.len, "search index")?;
            self.src.read_exact_at(&mut v, s.off)?;
            checked_fst(v)
        };
        let s = &self.secs;
        let title = KeyMap { fst: load(s[0])?, heavy: load(s[2])?, offsets: s[5], postings: s[6] };
        let word = KeyMap { fst: load(s[1])?, heavy: load(s[3])?, offsets: s[7], postings: s[8] };
        for m in [&title, &word] {
            if m.offsets.len < (m.fst.len() as u64 + 1) * 4 {
                return Err(invalid("search index: offsets truncated"));
            }
        }
        let raw = self.read_u32s(s[10], 0, s[10].len / 4)?;
        let levels: Vec<(u32, u32)> = raw.chunks_exact(2).map(|c| (c[0], c[1])).collect();
        if levels.windows(2).any(|w| w[1].0 <= w[0].0) {
            return Err(invalid("search index: bad score levels"));
        }
        Ok(Maps { title, word, levels })
    }

    /// Drop the in-memory part; it loads again on the next search. Never waits for a
    /// load in progress (that load is then not kept).
    pub fn unload(&self) {
        self.generation.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let old = self.maps.lock().unwrap_or_else(|e| e.into_inner()).take();
        drop(old); // freed outside the lock
    }

    /// Load the in-memory part now (call it off the UI thread after attaching).
    pub fn preload(&self) -> Result<()> {
        self.maps().map(|_| ())
    }

    pub fn is_loaded(&self) -> bool {
        self.maps.lock().unwrap_or_else(|e| e.into_inner()).is_some()
    }

    /// `n` u32 values starting at u32 position `start` of a section.
    fn read_u32s(&self, s: Sec, start: u64, n: u64) -> Result<Vec<u32>> {
        if n == 0 {
            return Ok(Vec::new());
        }
        // Positions come from the file (fst values, counts): no arithmetic may wrap.
        let end = start.checked_add(n).and_then(|e| e.checked_mul(4));
        if end.is_none_or(|e| e > s.len) {
            return Err(invalid("search index: read outside a section"));
        }
        let mut b = zeroed(n * 4, "search index")?;
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
        self.src.read_exact_at(&mut b[..n], self.secs[4].off + pos)?;
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

    /// Exact key ids and the best prefix ids of one key map.
    fn lookup(&self, maps: &Maps, m: &KeyMap, q: &str, keep: usize) -> Result<(Vec<u32>, Vec<u32>)> {
        let mut exact = match m.fst.get(q) {
            Some(o) => self.postings(m, o, o + 1, keep)?,
            None => Vec::new(),
        };
        exact.truncate(keep);
        let prefix = if let Some(off) = m.heavy.get(q) {
            let n = self.read_u32s(self.secs[9], off, 1)?[0];
            self.read_u32s(self.secs[9], off.saturating_add(1), n as u64)?
        } else {
            let lo = first_ordinal_ge(&m.fst, q.as_bytes());
            let mut upper = q.as_bytes().to_vec();
            upper.push(0xff); // never inside UTF-8: above every key starting with q
            let hi = first_ordinal_ge(&m.fst, &upper).min(lo.saturating_add(RANGE_CAP));
            let mut ids = self.postings(m, lo, hi, keep)?;
            if ids.len() > keep {
                ids.select_nth_unstable_by_key(keep, |&id| (maps.level(id), id));
                ids.truncate(keep);
            }
            ids
        };
        Ok((exact, prefix))
    }

    /// Ranked hits for a normalised query, best first; may contain duplicates and several
    /// records with the same target (the caller dedupes in order).
    pub fn search(&self, q: &str, want: usize) -> Result<Vec<IndexHit>> {
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let m = self.maps()?;
        let keep = want.clamp(1, 1 << 16) * 4;
        let mut hits = Vec::new();
        for (map, classes) in
            [(&m.title, [CLASS_EXACT, CLASS_PREFIX]), (&m.word, [CLASS_WORD_EXACT, CLASS_WORD_PREFIX])]
        {
            let (e, p) = self.lookup(&m, map, q, keep)?;
            for (class, ids) in [(classes[0], e), (classes[1], p)] {
                hits.extend(ids.into_iter().map(|id| IndexHit {
                    score: TIER_COST[class as usize] + m.score(id),
                    class,
                    id,
                }));
            }
        }
        hits.sort_by(|a, b| a.score.total_cmp(&b.score).then(a.class.cmp(&b.class)).then(a.id.cmp(&b.id)));
        Ok(hits)
    }

    pub fn title_keys(&self) -> usize {
        self.maps().map(|m| m.title.fst.len()).unwrap_or(0)
    }

    pub fn word_keys(&self) -> usize {
        self.maps().map(|m| m.word.fst.len()).unwrap_or(0)
    }

    pub fn heavy_prefixes(&self) -> usize {
        self.maps().map(|m| m.title.heavy.len() + m.word.heavy.len()).unwrap_or(0)
    }

    /// Bytes this index keeps in memory once loaded (its `fst` maps and score levels, or
    /// the whole file when small).
    pub fn memory_bytes(&self) -> u64 {
        if self.in_memory {
            return self.byte_len;
        }
        [0, 1, 2, 3, 10].iter().map(|&i| self.secs[i].len).sum()
    }
}

// ---------------------------------------------------------------- building

#[derive(Debug, Default, Clone)]
pub struct BuildStats {
    pub entries_scanned: u64,
    pub articles: u64,
    pub redirects: u64,
    pub records: u64,
    pub clusters_sized: u64,
    pub title_keys: u64,
    pub word_keys: u64,
    pub heavy_prefixes: u64,
    pub title_fst_bytes: u64,
    pub word_fst_bytes: u64,
    pub heavy_bytes: u64,
    pub records_bytes: u64,
    pub postings_bytes: u64,
    /// What a phone keeps in RAM for this index once loaded (fst maps + score levels).
    pub memory_bytes: u64,
    pub total_bytes: u64,
}

/// Receives build progress; `cancelled` is polled often.
pub trait BuildObserver {
    fn log(&mut self, _msg: &str) {}
    /// Overall fraction done, 0.0..=1.0, increasing.
    fn progress(&mut self, _fraction: f64) {}
    fn cancelled(&mut self) -> bool {
        false
    }
}

/// A plain closure observes log lines only.
impl<F: FnMut(&str)> BuildObserver for F {
    fn log(&mut self, msg: &str) {
        self(msg)
    }
}

/// Rate-limits progress callbacks and turns a cancel into an error.
struct Reporter<'a> {
    obs: &'a mut dyn BuildObserver,
    last_fraction: f64,
    last_time: Instant,
}

impl Reporter<'_> {
    fn log(&mut self, msg: &str) {
        self.obs.log(msg);
    }

    /// Report `fraction`; error out when cancelled.
    fn at(&mut self, fraction: f64) -> Result<()> {
        if fraction - self.last_fraction >= 0.002 || self.last_time.elapsed() >= Duration::from_millis(250) {
            self.last_fraction = fraction;
            self.last_time = Instant::now();
            self.obs.progress(fraction.clamp(0.0, 1.0));
            if self.obs.cancelled() {
                return Err(CoreError::Cancelled("index build cancelled".into()));
            }
        }
        Ok(())
    }
}

/// Reads dirents of a contiguous index range with large sequential reads.
struct DirentScanner<'a> {
    a: &'a Archive,
    win: Vec<u8>,
    win_start: u64,
}

impl<'a> DirentScanner<'a> {
    const WIN: u64 = 1 << 20;

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
        if off >= self.a.size() {
            return Err(invalid("directory entry pointer outside the file"));
        }
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
const K_OTHER: u8 = 0;
const K_ARTICLE: u8 = 1;
const K_CONTENT: u8 = 2;
const K_REDIRECT: u8 = 3;

fn sub(arena: &str, start: u32, len: u32) -> &str {
    &arena[start as usize..(start + len) as usize]
}

/// Anything the index can be written to.
pub trait WriteSeek: Write + Seek {}
impl<T: Write + Seek> WriteSeek for T {}

/// Writes 8-byte aligned sections and remembers where each one went.
struct SectionWriter<'a> {
    out: &'a mut dyn WriteSeek,
    pos: u64,
    secs: [Sec; SECTIONS],
}

impl SectionWriter<'_> {
    fn align(&mut self) -> Result<()> {
        let pad = self.pos.div_ceil(8) * 8 - self.pos;
        self.out.write_all(&[0u8; 8][..pad as usize])?;
        self.pos += pad;
        Ok(())
    }

    fn bytes(&mut self, i: usize, b: &[u8]) -> Result<()> {
        self.align()?;
        self.out.write_all(b)?;
        self.secs[i] = Sec { off: self.pos, len: b.len() as u64 };
        self.pos += b.len() as u64;
        Ok(())
    }

    fn u32s(&mut self, i: usize, v: &[u32]) -> Result<()> {
        self.align()?;
        let start = self.pos;
        for chunk in v.chunks(1 << 14) {
            let b: Vec<u8> = chunk.iter().flat_map(|x| x.to_le_bytes()).collect();
            self.out.write_all(&b)?;
        }
        self.pos += v.len() as u64 * 4;
        self.secs[i] = Sec { off: start, len: self.pos - start };
        Ok(())
    }

    /// Build an fst straight into the output.
    fn fst<'k>(&mut self, i: usize, items: impl Iterator<Item = (&'k [u8], u64)>) -> Result<()> {
        self.align()?;
        let start = self.pos;
        let mut counter = Counter { out: &mut *self.out, n: 0 };
        let mut b = fst::MapBuilder::new(&mut counter).map_err(|e| invalid(format!("fst: {e}")))?;
        for (k, v) in items {
            b.insert(k, v).map_err(|e| invalid(format!("fst: {e}")))?;
        }
        b.finish().map_err(|e| invalid(format!("fst: {e}")))?;
        self.pos += counter.n;
        self.secs[i] = Sec { off: start, len: counter.n };
        Ok(())
    }
}

struct Counter<'a> {
    out: &'a mut dyn WriteSeek,
    n: u64,
}

impl Write for Counter<'_> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.out.write(buf)?;
        self.n += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.out.flush()
    }
}

/// Build the index for an archive and write it to `out` (which must start empty at
/// position 0). Deterministic: the same ZIM always gives the same bytes.
pub fn build(a: &Archive, out: &mut dyn WriteSeek, obs: &mut dyn BuildObserver) -> Result<BuildStats> {
    let mut rep = Reporter { obs, last_fraction: -1.0, last_time: Instant::now() };
    rep.at(0.0)?;
    let mut st = BuildStats::default();
    let ns = a.article_ns();
    let range = a.namespace_range(ns)?;
    let base = range.start;
    let n = (range.end - range.start) as usize;
    rep.log(&format!("article namespace: {n} entries"));

    // Front articles from the v1 listing when present, else HTML content entries.
    // A corrupt front-article listing is ignored (HTML entries are used instead).
    let v1 = match a.new_scheme().then(|| a.listing_blob("listing/titleOrdered/v1")) {
        Some(Ok(l)) => l,
        Some(Err(e @ CoreError::Io(_))) => return Err(e),
        _ => None,
    };
    let mut kind = vec![K_OTHER; n];
    let use_v1 = v1.is_some();
    if let Some(l) = &v1 {
        let len = a.title_list_len(l);
        for i in (0..len).step_by(1 << 16) {
            for e in a.title_list_slice(l, i, 1 << 16)? {
                if e >= range.start && e < range.end {
                    kind[(e - base) as usize] = K_ARTICLE; // provisional: front article
                }
            }
        }
    }

    // Pass 1 (0-40 %): every dirent of the namespace in path order (sequential reads).
    // Titles are kept only for articles and redirects.
    let mut redirect_to = vec![NOT_REDIRECT; n];
    let mut dirent_off: Vec<u64> = vec![0; n];
    let mut spans: Vec<(u32, u32)> = vec![(0, 0); n];
    let mut title_chars: Vec<u16> = vec![0; n];
    let mut blobs: Vec<(u32, u32, u32)> = Vec::new(); // (cluster, blob, k) of articles
    let mut arena = String::new();
    let mut sc = DirentScanner::new(a);
    let mut done = 0u64;
    pointers(a, range.clone(), &mut |i, off| {
        let d = sc.dirent_at(off)?;
        let k = (i - base) as usize;
        dirent_off[k] = off;
        let keep = match d.kind {
            Kind::Content { cluster, blob } => {
                let article = if use_v1 { kind[k] == K_ARTICLE } else { a.mime(&d).starts_with("text/html") };
                kind[k] = if article { K_ARTICLE } else { K_CONTENT };
                if article {
                    blobs.push((cluster, blob, k as u32));
                }
                article
            }
            Kind::Redirect { target } => {
                kind[k] = K_REDIRECT;
                redirect_to[k] = target;
                true
            }
            Kind::Other => {
                kind[k] = K_OTHER;
                false
            }
        };
        if keep {
            let norm = normalize(d.title());
            if arena.len() + norm.len() > u32::MAX as usize {
                return Err(CoreError::Unsupported("titles too large for the index".into()));
            }
            spans[k] = (arena.len() as u32, norm.len() as u32);
            arena.push_str(&norm);
            title_chars[k] = d.title().chars().count().min(u16::MAX as usize) as u16;
        }
        done += 1;
        if done % 4096 == 0 {
            rep.at(0.40 * done as f64 / n.max(1) as f64)?;
        }
        if done % 500_000 == 0 {
            rep.log(&format!("scanned {done}/{n} entries"));
        }
        Ok(())
    })?;
    st.entries_scanned = done;
    drop(sc);

    // Pass 2 (40-80 %): article sizes, cluster by cluster in file order; only the offset
    // table of each cluster is read (one zstd block).
    blobs.sort_unstable();
    let mut size: Vec<u32> = vec![0; n];
    let clusters = blobs.chunk_by(|x, y| x.0 == y.0).count().max(1);
    for (ci, group) in blobs.chunk_by(|x, y| x.0 == y.0).enumerate() {
        match a.blob_sizes(group[0].0) {
            Ok(sizes) => {
                for &(_, blob, k) in group {
                    size[k as usize] = sizes.get(blob as usize).copied().unwrap_or(0).min(u32::MAX as u64) as u32;
                }
            }
            // A drive pulled mid-build must not leave a complete-looking index with every
            // later size missing: stop. A corrupt cluster only loses its sizes.
            Err(e @ CoreError::Io(_)) => return Err(e),
            Err(_) => {}
        }
        st.clusters_sized += 1;
        rep.at(0.40 + 0.40 * (ci + 1) as f64 / clusters as f64)?;
    }
    drop(blobs);
    rep.log(&format!("sized articles in {} clusters", st.clusters_sized));

    // Records: articles, and redirects whose chain ends at an article.
    struct Rec {
        display: u32,
        target: u32,
        level: u32,
    }
    let mut recs: Vec<Rec> = Vec::new();
    // Redirects pointing at each article: a second popularity signal (alternative names
    // and misspellings exist for well-known topics).
    let mut inlinks: Vec<u32> = vec![0; n];
    for k in 0..n {
        let (_, l) = spans[k];
        if l == 0 {
            continue;
        }
        if kind[k] == K_ARTICLE {
            recs.push(Rec { display: base + k as u32, target: base + k as u32, level: 0 });
            st.articles += 1;
        } else if kind[k] == K_REDIRECT {
            let mut t = redirect_to[k];
            let mut ok = false;
            for _ in 0..crate::zim::MAX_REDIRECTS {
                if t < range.start || t >= range.end {
                    break;
                }
                let tk = (t - base) as usize;
                if kind[tk] == K_REDIRECT {
                    t = redirect_to[tk];
                    continue;
                }
                ok = kind[tk] == K_ARTICLE;
                break;
            }
            if ok {
                inlinks[(t - base) as usize] += 1;
                recs.push(Rec { display: base + k as u32, target: t, level: 0 });
                st.redirects += 1;
            }
        }
    }
    for r in recs.iter_mut() {
        let (s, l) = spans[(r.display - base) as usize];
        let chars = sub(&arena, s, l).chars().count() as u32;
        let t = (r.target - base) as usize;
        r.level = level_of(static_score(chars, r.display != r.target, size[t] as u64, inlinks[t]));
    }
    drop(inlinks);
    drop(redirect_to);
    drop(size);
    // Articles first (record layout), each part in score order.
    {
        let key_of = |display: u32| {
            let (s, l) = spans[(display - base) as usize];
            sub(&arena, s, l)
        };
        let tc = |d: u32| title_chars[(d - base) as usize];
        recs.sort_unstable_by(|x, y| {
            let xr = x.display != x.target;
            let yr = y.display != y.target;
            (x.level, xr, tc(x.display), key_of(x.display), x.display).cmp(&(
                y.level,
                yr,
                tc(y.display),
                key_of(y.display),
                y.display,
            ))
        });
    }
    st.records = recs.len() as u64;
    rep.log(&format!("{} records ({} articles, {} redirects)", recs.len(), st.articles, st.redirects));
    rep.at(0.81)?;

    let mut w = SectionWriter { out, pos: 0, secs: [Sec::default(); SECTIONS] };
    w.out.write_all(&[0u8; HEADER_LEN])?;
    w.pos = HEADER_LEN as u64;

    // Records section and score levels. Ids: articles 0..A in score order, then
    // redirects A..R in score order. Levels are monotone within each part, so "best"
    // means smallest (level, id); the level table is run-length coded over ids.
    let (arts, reds): (Vec<&Rec>, Vec<&Rec>) = recs.iter().partition(|r| r.display == r.target);
    let mut records = Vec::with_capacity(arts.len() * 8 + reds.len() * 16);
    for r in &arts {
        records.extend_from_slice(&dirent_off[(r.display - base) as usize].to_le_bytes());
    }
    for r in &reds {
        records.extend_from_slice(&dirent_off[(r.display - base) as usize].to_le_bytes());
        records.extend_from_slice(&dirent_off[(r.target - base) as usize].to_le_bytes());
    }
    w.bytes(4, &records)?;
    st.records_bytes = records.len() as u64;
    drop(records);
    drop(dirent_off);
    let ordered: Vec<&Rec> = arts.iter().chain(reds.iter()).copied().collect();
    let mut levels: Vec<u32> = Vec::new();
    for (id, r) in ordered.iter().enumerate() {
        if levels.len() < 2 || levels[levels.len() - 1] != r.level {
            levels.push(id as u32);
            levels.push(r.level);
        }
    }
    let displays: Vec<u32> = ordered.iter().map(|r| r.display).collect();
    let targets: Vec<u32> = ordered.iter().map(|r| r.target).collect();
    drop(ordered);
    drop(arts);
    drop(reds);
    drop(recs);
    drop(title_chars);
    drop(kind);
    w.u32s(10, &levels)?;
    let level_of_id: Vec<u32> = expand_levels(&levels, displays.len());

    // Keys: (arena start, len, record id).
    let mut tkeys: Vec<(u32, u32, u32)> = Vec::with_capacity(displays.len());
    for (id, &d) in displays.iter().enumerate() {
        let (s, l) = spans[(d - base) as usize];
        tkeys.push((s, l, id as u32));
    }
    let mut heavy_lists: Vec<u32> = Vec::new();
    let mut heavy_seen: HashMap<Vec<u32>, u32> = HashMap::new();
    rep.log(&format!("building title map ({} keys)", tkeys.len()));
    let t =
        build_map(&arena, &mut tkeys, &targets, &level_of_id, &mut heavy_lists, &mut heavy_seen, &mut w, [0, 2, 5, 6])?;
    drop(tkeys);
    rep.at(0.91)?;

    let mut wkeys: Vec<(u32, u32, u32)> = Vec::new();
    for (id, &d) in displays.iter().enumerate() {
        if id as u64 >= st.articles {
            break; // redirects: full title only
        }
        let (s, l) = spans[(d - base) as usize];
        let t = sub(&arena, s, l);
        for ws in word_starts(t).take(MAX_WORD_KEYS) {
            wkeys.push((s + ws as u32, l - ws as u32, id as u32));
        }
    }
    drop(spans);
    rep.log(&format!("building word map ({} keys)", wkeys.len()));
    let wm =
        build_map(&arena, &mut wkeys, &targets, &level_of_id, &mut heavy_lists, &mut heavy_seen, &mut w, [1, 3, 7, 8])?;
    drop(wkeys);
    rep.at(0.98)?;
    w.u32s(9, &heavy_lists)?;
    w.align()?;
    let total = w.pos;
    let secs = w.secs;

    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.extend_from_slice(&VERSION.to_le_bytes());
    header.extend_from_slice(&0u32.to_le_bytes());
    header.extend_from_slice(&a.header.uuid);
    header.extend_from_slice(&a.header.entry_count.to_le_bytes());
    header.extend_from_slice(&(displays.len() as u32).to_le_bytes());
    header.extend_from_slice(&(SECTIONS as u32).to_le_bytes());
    header.extend_from_slice(&(st.articles as u32).to_le_bytes());
    for s in &secs {
        header.extend_from_slice(&s.off.to_le_bytes());
        header.extend_from_slice(&s.len.to_le_bytes());
    }
    out.seek(SeekFrom::Start(0))?;
    out.write_all(&header)?;
    out.seek(SeekFrom::Start(total))?;
    out.flush()?;

    st.title_keys = t.keys;
    st.word_keys = wm.keys;
    st.heavy_prefixes = t.heavy + wm.heavy;
    st.title_fst_bytes = secs[0].len;
    st.word_fst_bytes = secs[1].len;
    st.heavy_bytes = secs[2].len + secs[3].len + secs[9].len;
    st.postings_bytes = secs[5].len + secs[6].len + secs[7].len + secs[8].len;
    st.memory_bytes = [0, 1, 2, 3, 10].iter().map(|&i| secs[i].len).sum();
    st.total_bytes = total;
    rep.at(1.0)?;
    Ok(st)
}

/// Level of every record id from the (first id, level) table.
fn expand_levels(table: &[u32], n: usize) -> Vec<u32> {
    let mut v = vec![0u32; n];
    let pairs: Vec<(u32, u32)> = table.chunks_exact(2).map(|c| (c[0], c[1])).collect();
    for (i, &(first, level)) in pairs.iter().enumerate() {
        let end = pairs.get(i + 1).map(|p| p.0 as usize).unwrap_or(n);
        for x in &mut v[first as usize..end] {
            *x = level;
        }
    }
    v
}

struct BuiltMap {
    keys: u64,
    heavy: u64,
}

/// Build one key map into the output. `sec` = [fst, heavy fst, offsets, postings].
#[allow(clippy::too_many_arguments)]
fn build_map(
    arena: &str,
    keys: &mut [(u32, u32, u32)],
    targets: &[u32],
    level_of_id: &[u32],
    heavy_lists: &mut Vec<u32>,
    heavy_seen: &mut HashMap<Vec<u32>, u32>,
    w: &mut SectionWriter,
    sec: [usize; 4],
) -> Result<BuiltMap> {
    // Rank order inside a key / prefix = (level, id): ids are not score-ordered across
    // the article and redirect parts, so compare levels first.
    let rank = |id: u32| (level_of_id[id as usize], id);
    keys.sort_unstable_by(|x, y| {
        sub(arena, x.0, x.1).as_bytes().cmp(sub(arena, y.0, y.1).as_bytes()).then(rank(x.2).cmp(&rank(y.2)))
    });

    // Unique keys with their posting lists (best first, one per target).
    let mut uniq: Vec<(u32, u32)> = Vec::new();
    let mut lists: Vec<(u32, u32)> = Vec::new();
    let mut ids: Vec<u32> = Vec::new();
    let mut seen: HashSet<u32> = HashSet::new();
    let mut i = 0;
    while i < keys.len() {
        let mut j = i + 1;
        while j < keys.len() && sub(arena, keys[j].0, keys[j].1) == sub(arena, keys[i].0, keys[i].1) {
            j += 1;
        }
        let start = ids.len();
        seen.clear();
        for k in &keys[i..j] {
            if seen.insert(targets[k.2 as usize]) {
                ids.push(k.2);
            }
        }
        uniq.push((keys[i].0, keys[i].1));
        lists.push((start as u32, (ids.len() - start) as u32));
        i = j;
    }
    let ukey = |u: usize| sub(arena, uniq[u].0, uniq[u].1);
    let list = |u: usize| &ids[lists[u].0 as usize..(lists[u].0 + lists[u].1) as usize];

    // key → ordinal, streamed into the output; ordinal → offsets[o]..offsets[o+1].
    w.fst(sec[0], (0..uniq.len()).map(|u| (ukey(u).as_bytes(), u as u64)))?;
    let mut offsets = Vec::with_capacity(uniq.len() + 1);
    let mut acc = 0u32;
    for l in &lists {
        offsets.push(acc);
        acc += l.1;
    }
    offsets.push(acc);
    w.u32s(sec[2], &offsets)?;
    drop(offsets);
    w.u32s(sec[3], &ids)?;

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
                if top.len() == HEAVY_TOP && rank(id) >= rank(*top.last().unwrap()) {
                    break; // a key's postings are best first
                }
                let t = targets[id as usize];
                if let Some(pos) = top.iter().position(|&x| targets[x as usize] == t) {
                    if rank(top[pos]) <= rank(id) {
                        continue;
                    }
                    top.remove(pos);
                }
                let at = top.partition_point(|&x| rank(x) < rank(id));
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
    w.fst(sec[1], hentries.iter().map(|(p, off)| (p.as_bytes(), *off as u64)))?;
    Ok(BuiltMap { keys: uniq.len() as u64, heavy: hentries.len() as u64 })
}
