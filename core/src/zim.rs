//! Our own ZIM reader, written from the openZIM file format description.
//!
//! Layout: an 80-byte header, a MIME type list, the path pointer list (u64 offsets of
//! directory entries sorted by namespace + path), optionally a title pointer list, the
//! cluster pointer list and the clusters. Content lives in blobs inside clusters; a
//! cluster is uncompressed or xz/zstd compressed and starts with an offset table of
//! u32 (normal) or u64 (extended) offsets.

use std::cell::Cell;
use std::collections::HashMap;
use std::io::{BufRead, Read};
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, RwLock};

use crate::cache::ByteLru;
use crate::error::{invalid, no_memory, zeroed, CoreError, Result};
use crate::reader::ReadAt;

pub const ZIM_MAGIC: u32 = 72_173_914;
pub const HEADER_LEN: usize = 80;
const NO_PAGE: u32 = u32::MAX;
const MIME_REDIRECT: u16 = 0xffff;
const MIME_LINKTARGET: u16 = 0xfffe;
const MIME_DELETED: u16 = 0xfffd;
/// Redirect chains longer than this are treated as broken.
pub const MAX_REDIRECTS: usize = 10;
/// Refuse to inflate a single cluster beyond this (corrupt or hostile file).
const MAX_CLUSTER_BYTES: u64 = 256 * 1024 * 1024;
/// `get()` refuses entries larger than this; media goes through `get_range`.
pub const MAX_GET_BYTES: u64 = 256 * 1024 * 1024;
/// Metadata values (texts, illustrations, favicon) larger than this are corrupt.
pub const MAX_META_BYTES: u64 = 16 * 1024 * 1024;
/// Compressed cluster bytes are read in pieces of at most this (one read for ordinary
/// clusters; a corrupt cluster size costs no more than this before the decoder fails).
const READ_CHUNK: usize = 4 * 1024 * 1024;
/// Largest first guess of an inflated cluster's size when its frame does not declare it.
const INFLATE_HINT_MAX: usize = 8 * 1024 * 1024;
/// xz clusters (old ZIMs) may inflate to at most this. lzma-rs keeps a whole xz block
/// in memory, about twice over and growing by doubling, so this stays well under
/// `MAX_CLUSTER_BYTES`; real xz clusters are about 1 MB.
const MAX_XZ_CLUSTER_BYTES: u64 = 64 * 1024 * 1024;
/// Default budget of the decompressed-cluster cache, shared by all open books.
pub const DEFAULT_CLUSTER_CACHE_BYTES: usize = 48 * 1024 * 1024;
/// Directory entries cached, shared by all open books (~150 B each).
const DIRENT_CACHE_ENTRIES: usize = 32 * 1024;

/// Process-wide caches: one memory budget for every open book.
struct SharedCaches {
    clusters: Mutex<ByteLru<(u64, u32), Arc<Cluster>>>,
    dirents: Mutex<lru::LruCache<(u64, u32), Arc<Dirent>>>,
}

fn shared() -> &'static SharedCaches {
    static C: OnceLock<SharedCaches> = OnceLock::new();
    C.get_or_init(|| SharedCaches {
        clusters: Mutex::new(ByteLru::new(DEFAULT_CLUSTER_CACHE_BYTES)),
        dirents: Mutex::new(lru::LruCache::new(std::num::NonZeroUsize::new(DIRENT_CACHE_ENTRIES).unwrap())),
    })
}

/// Drop every cached cluster and directory entry of every book.
pub fn trim_caches() {
    lock(&shared().clusters).clear();
    lock(&shared().dirents).clear();
}

/// Set the shared decompressed-cluster budget (bytes); evicts down to it right away.
pub fn set_cluster_cache_budget(bytes: usize) {
    lock(&shared().clusters).set_budget(bytes);
}

/// Bytes currently held by the shared cluster cache.
pub fn cluster_cache_bytes() -> usize {
    lock(&shared().clusters).bytes()
}

static NEXT_ARCHIVE_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone)]
pub struct Header {
    pub major: u16,
    pub minor: u16,
    pub uuid: [u8; 16],
    pub entry_count: u32,
    pub cluster_count: u32,
    pub path_ptr_pos: u64,
    pub title_ptr_pos: u64,
    pub cluster_ptr_pos: u64,
    pub mime_list_pos: u64,
    pub main_page: u32,
    pub layout_page: u32,
    pub checksum_pos: u64,
}

impl Header {
    pub fn parse(b: &[u8; HEADER_LEN]) -> Result<Header> {
        let u16_at = |o: usize| u16::from_le_bytes([b[o], b[o + 1]]);
        let u32_at = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().unwrap());
        let u64_at = |o: usize| u64::from_le_bytes(b[o..o + 8].try_into().unwrap());
        if u32_at(0) != ZIM_MAGIC {
            return Err(invalid("not a ZIM file (bad magic number)"));
        }
        let mut uuid = [0u8; 16];
        uuid.copy_from_slice(&b[8..24]);
        let h = Header {
            major: u16_at(4),
            minor: u16_at(6),
            uuid,
            entry_count: u32_at(24),
            cluster_count: u32_at(28),
            path_ptr_pos: u64_at(32),
            title_ptr_pos: u64_at(40),
            cluster_ptr_pos: u64_at(48),
            mime_list_pos: u64_at(56),
            main_page: u32_at(64),
            layout_page: u32_at(68),
            checksum_pos: u64_at(72),
        };
        if !(5..=6).contains(&h.major) {
            return Err(CoreError::Unsupported(format!("ZIM major version {}", h.major)));
        }
        Ok(h)
    }

    /// New namespace scheme (`C` content, `M` metadata, `W` well-known, `X` indexes).
    pub fn new_namespace_scheme(&self) -> bool {
        self.major >= 6 && self.minor >= 1
    }

    pub fn has_checksum(&self) -> bool {
        self.mime_list_pos >= HEADER_LEN as u64 && self.checksum_pos != 0
    }

    pub fn uuid_hex(&self) -> String {
        self.uuid.iter().map(|b| format!("{b:02x}")).collect()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Content {
        cluster: u32,
        blob: u32,
    },
    Redirect {
        target: u32,
    },
    /// Obsolete link targets and deleted entries.
    Other,
}

#[derive(Debug, Clone)]
pub struct Dirent {
    pub mime: u16,
    pub ns: u8,
    pub path: String,
    pub title: String,
    pub kind: Kind,
}

impl Dirent {
    /// The title, or the path when the title is empty (as the spec says).
    pub fn title(&self) -> &str {
        if self.title.is_empty() {
            &self.path
        } else {
            &self.title
        }
    }

    pub fn is_redirect(&self) -> bool {
        matches!(self.kind, Kind::Redirect { .. })
    }

    /// Parse a directory entry from `buf`. `Ok(None)` means: strings not terminated yet,
    /// read more bytes.
    pub fn parse(buf: &[u8]) -> Result<Option<Dirent>> {
        if buf.len() < 8 {
            return Ok(None);
        }
        let mime = u16::from_le_bytes([buf[0], buf[1]]);
        let param_len = buf[2] as usize;
        let ns = buf[3];
        let u32_at = |o: usize| u32::from_le_bytes(buf[o..o + 4].try_into().unwrap());
        let (kind, fixed) = match mime {
            MIME_REDIRECT => {
                if buf.len() < 12 {
                    return Ok(None);
                }
                (Kind::Redirect { target: u32_at(8) }, 12)
            }
            MIME_LINKTARGET | MIME_DELETED => (Kind::Other, 8),
            _ => {
                if buf.len() < 16 {
                    return Ok(None);
                }
                (Kind::Content { cluster: u32_at(8), blob: u32_at(12) }, 16)
            }
        };
        let rest = &buf[fixed..];
        let Some(p_end) = rest.iter().position(|&c| c == 0) else { return Ok(None) };
        let Some(t_len) = rest[p_end + 1..].iter().position(|&c| c == 0) else { return Ok(None) };
        let t_end = p_end + 1 + t_len;
        if rest.len() < t_end + 1 + param_len {
            return Ok(None);
        }
        Ok(Some(Dirent {
            mime,
            ns,
            path: String::from_utf8_lossy(&rest[..p_end]).into_owned(),
            title: String::from_utf8_lossy(&rest[p_end + 1..t_end]).into_owned(),
            kind,
        }))
    }
}

/// A decompressed cluster: data plus the blob offset table (relative to `data`).
pub struct Cluster {
    data: Vec<u8>,
    offsets: Vec<u64>,
}

impl Cluster {
    fn parse(data: Vec<u8>, extended: bool) -> Result<Cluster> {
        let osz = if extended { 8 } else { 4 };
        let read_off = |d: &[u8], i: usize| -> Option<u64> {
            let s = d.get(i * osz..i * osz + osz)?;
            Some(if extended {
                u64::from_le_bytes(s.try_into().ok()?)
            } else {
                u32::from_le_bytes(s.try_into().ok()?) as u64
            })
        };
        let first = read_off(&data, 0).ok_or_else(|| invalid("cluster too short"))?;
        if first == 0 || first % osz as u64 != 0 || first > data.len() as u64 {
            return Err(invalid("bad cluster offset table"));
        }
        let n = (first / osz as u64) as usize;
        let mut offsets = Vec::with_capacity(n);
        let mut prev = first;
        for i in 0..n {
            let o = read_off(&data, i).ok_or_else(|| invalid("cluster offset table truncated"))?;
            if o < prev || o > data.len() as u64 {
                return Err(invalid("cluster offsets out of range"));
            }
            prev = o;
            offsets.push(o);
        }
        Ok(Cluster { data, offsets })
    }

    pub fn blob_count(&self) -> u32 {
        self.offsets.len().saturating_sub(1).min(u32::MAX as usize) as u32
    }

    pub fn blob(&self, i: u32) -> Result<&[u8]> {
        // No `i + 1` here: with a 32-bit usize a corrupt `u32::MAX` would wrap to 0.
        if i >= self.blob_count() {
            return Err(invalid(format!("blob {i} out of range")));
        }
        let i = i as usize;
        Ok(&self.data[self.offsets[i] as usize..self.offsets[i + 1] as usize])
    }

    /// Memory held (allocated capacity, not just length) for the cache budget.
    pub fn byte_size(&self) -> usize {
        self.data.capacity() + self.offsets.capacity() * 8 + 64
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compression {
    None,
    Zlib,
    Bzip2,
    Xz,
    Zstd,
    Unknown(u8),
}

impl Compression {
    fn from_info(info: u8) -> Compression {
        match info & 0x0f {
            0 | 1 => Compression::None,
            2 => Compression::Zlib,
            3 => Compression::Bzip2,
            4 => Compression::Xz,
            5 => Compression::Zstd,
            c => Compression::Unknown(c),
        }
    }
}

/// Where a cluster sits in the file.
#[derive(Debug, Clone, Copy)]
pub struct ClusterLoc {
    pub start: u64,
    pub end: u64,
    pub compression: Compression,
    pub extended: bool,
}

/// Where a blob sits: either directly in the file (uncompressed cluster) or in a cached cluster.
pub enum BlobLoc {
    File { offset: u64, len: u64 },
    Mem { cluster: Arc<Cluster>, blob: u32 },
}

impl BlobLoc {
    pub fn len(&self) -> u64 {
        match self {
            BlobLoc::File { len, .. } => *len,
            BlobLoc::Mem { cluster, blob } => cluster.blob(*blob).map(|b| b.len() as u64).unwrap_or(0),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// A title-ordered list of entry indices.
pub enum TitleList {
    /// u32 entries stored directly in the file at `base`.
    File {
        base: u64,
        count: u32,
    },
    Mem(Vec<u32>),
}

pub struct Archive {
    /// `None` once closed: every later read fails with `CoreError::Io`. A read clones the
    /// `Arc` and drops the lock before reading, so closing never waits for a read (a
    /// hung USB read must not block `close_file` on the UI thread); the file itself is
    /// closed when the last read in flight finishes.
    src: RwLock<Option<Arc<dyn ReadAt>>>,
    /// Key of this archive in the shared caches.
    id: u64,
    pub header: Header,
    pub mimes: Vec<String>,
    size: u64,
    clusters_end: u64,
    /// Striped locks so parallel requests for one cluster inflate it once.
    loading: [Mutex<()>; 16],
}

impl Drop for Archive {
    fn drop(&mut self) {
        self.purge_caches();
    }
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Archive {
    /// Open: reads the 80-byte header and the MIME list, nothing else (constant time).
    pub fn open(src: Box<dyn ReadAt>) -> Result<Archive> {
        let size = src.size();
        if size < HEADER_LEN as u64 {
            return Err(invalid("file too small for a ZIM header"));
        }
        let mut hb = [0u8; HEADER_LEN];
        src.read_exact_at(&mut hb, 0)?;
        let header = Header::parse(&hb)?;
        let within = |pos: u64, len: u64| pos.checked_add(len).is_some_and(|e| e <= size);
        if !within(header.path_ptr_pos, header.entry_count as u64 * 8) {
            return Err(invalid("path pointer list outside the file (truncated?)"));
        }
        if !within(header.cluster_ptr_pos, header.cluster_count as u64 * 8) {
            return Err(invalid("cluster pointer list outside the file (truncated?)"));
        }
        if header.mime_list_pos >= size {
            return Err(invalid("MIME list outside the file"));
        }
        let clusters_end = if header.has_checksum() {
            if header.checksum_pos.checked_add(16).is_none_or(|e| e > size) {
                return Err(invalid("checksum position outside the file (truncated?)"));
            }
            header.checksum_pos
        } else {
            size
        };
        let mimes = read_mime_list(src.as_ref(), header.mime_list_pos, size)?;
        Ok(Archive {
            src: RwLock::new(Some(Arc::from(src))),
            id: NEXT_ARCHIVE_ID.fetch_add(1, Ordering::Relaxed),
            header,
            mimes,
            size,
            clusters_end,
            loading: Default::default(),
        })
    }

    /// Close the file now. The archive stays usable as an object; every read from now
    /// on fails with `CoreError::Io`. Never waits for reads in flight: they finish (or
    /// fail) on their own and the fd is released when the last of them is done.
    pub fn close(&self) {
        let src = self.src.write().unwrap_or_else(|e| e.into_inner()).take();
        drop(src); // closes the fd unless a read in flight still holds it
        self.purge_caches();
    }

    /// The open source, or `Io` once closed.
    fn source(&self) -> Result<Arc<dyn ReadAt>> {
        self.src
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .ok_or_else(|| CoreError::Io("the book is closed".into()))
    }

    pub fn is_closed(&self) -> bool {
        self.src.read().unwrap_or_else(|e| e.into_inner()).is_none()
    }

    /// Remove this archive's entries from the shared caches.
    pub fn purge_caches(&self) {
        let id = self.id;
        lock(&shared().clusters).retain(|k| k.0 != id);
        let mut d = lock(&shared().dirents);
        let keys: Vec<(u64, u32)> = d.iter().map(|(k, _)| *k).filter(|k| k.0 == id).collect();
        for k in keys {
            d.pop(&k);
        }
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    /// Bytes the shared cluster cache holds for this archive (tests: the cache is
    /// process-wide, so other books' entries must not count).
    pub fn cached_cluster_bytes(&self) -> usize {
        lock(&shared().clusters).bytes_where(|k| k.0 == self.id)
    }

    pub fn entry_count(&self) -> u32 {
        self.header.entry_count
    }

    pub fn new_scheme(&self) -> bool {
        self.header.new_namespace_scheme()
    }

    /// Namespace that holds articles: `C` in the new scheme, `A` in the old one.
    pub fn article_ns(&self) -> u8 {
        if self.new_scheme() {
            b'C'
        } else {
            b'A'
        }
    }

    pub fn mime(&self, d: &Dirent) -> &str {
        self.mimes.get(d.mime as usize).map(String::as_str).unwrap_or("application/octet-stream")
    }

    pub fn read_at(&self, buf: &mut [u8], offset: u64) -> Result<()> {
        if offset.checked_add(buf.len() as u64).is_none_or(|e| e > self.size) {
            return Err(invalid("read beyond the end of the file (truncated?)"));
        }
        // Bounds are checked against the size seen at open, so an end of file from the
        // source means the file vanished (drive pulled): `Io`, see `CoreError::from`.
        self.source()?.read_exact_at(buf, offset).map_err(CoreError::from)
    }

    fn read_u64(&self, offset: u64) -> Result<u64> {
        let mut b = [0u8; 8];
        self.read_at(&mut b, offset)?;
        Ok(u64::from_le_bytes(b))
    }

    pub fn dirent_offset(&self, idx: u32) -> Result<u64> {
        if idx >= self.header.entry_count {
            return Err(invalid(format!("entry index {idx} out of range")));
        }
        self.read_u64(self.header.path_ptr_pos + idx as u64 * 8)
    }

    /// Parse the directory entry stored at `offset`.
    pub fn dirent_at(&self, offset: u64) -> Result<Dirent> {
        if offset >= self.size {
            return Err(invalid("directory entry pointer outside the file"));
        }
        let mut want = 512u64;
        loop {
            let len = want.min(self.size - offset) as usize;
            let mut buf = vec![0u8; len];
            self.read_at(&mut buf, offset)?;
            if let Some(d) = Dirent::parse(&buf)? {
                return Ok(d);
            }
            if len as u64 >= self.size - offset || want >= 1 << 20 {
                return Err(invalid("unterminated directory entry"));
            }
            want *= 8;
        }
    }

    /// Directory entry by its index in the path pointer list (cached).
    pub fn dirent(&self, idx: u32) -> Result<Arc<Dirent>> {
        if let Some(d) = lock(&shared().dirents).get(&(self.id, idx)) {
            return Ok(d.clone());
        }
        let d = Arc::new(self.dirent_at(self.dirent_offset(idx)?)?);
        lock(&shared().dirents).put((self.id, idx), d.clone());
        Ok(d)
    }

    /// The last up to `max` entries in path order, read with three I/Os (pointers,
    /// one window of directory entries). In both namespace schemes `M` (metadata), `W`
    /// and `X` sort last, so this finds them without a binary search. Cached.
    pub fn tail_entries(&self, max: u32) -> Result<Vec<(u32, Arc<Dirent>)>> {
        let n = self.header.entry_count;
        let first = n.saturating_sub(max);
        let count = n - first;
        if count == 0 {
            return Ok(Vec::new());
        }
        let mut pb = vec![0u8; count as usize * 8];
        self.read_at(&mut pb, self.header.path_ptr_pos + first as u64 * 8)?;
        let ptrs: Vec<u64> = pb.chunks_exact(8).map(|c| u64::from_le_bytes(c.try_into().unwrap())).collect();
        let lo = *ptrs.iter().min().unwrap();
        let hi = *ptrs.iter().max().unwrap();
        let window = if hi >= lo && hi - lo < 256 * 1024 && lo < self.size {
            let len = (hi - lo + 4096).min(self.size - lo) as usize;
            let mut w = vec![0u8; len];
            self.read_at(&mut w, lo)?;
            Some(w)
        } else {
            None
        };
        let mut out = Vec::with_capacity(count as usize);
        for (i, &p) in ptrs.iter().enumerate() {
            let idx = first + i as u32;
            let parsed = match &window {
                Some(w) if p >= lo && ((p - lo) as usize) < w.len() => Dirent::parse(&w[(p - lo) as usize..])?,
                _ => None,
            };
            let d = Arc::new(match parsed {
                Some(d) => d,
                None => self.dirent_at(p)?,
            });
            lock(&shared().dirents).put((self.id, idx), d.clone());
            out.push((idx, d));
        }
        Ok(out)
    }

    /// First index whose (namespace, path) is >= the given key.
    pub fn lower_bound(&self, ns: u8, path: &str) -> Result<u32> {
        let (mut lo, mut hi) = (0u32, self.header.entry_count);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let d = self.dirent(mid)?;
            if (d.ns, d.path.as_bytes()) < (ns, path.as_bytes()) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        Ok(lo)
    }

    /// Index of the entry with exactly this namespace and path.
    pub fn find(&self, ns: u8, path: &str) -> Result<Option<u32>> {
        let i = self.lower_bound(ns, path)?;
        if i < self.header.entry_count {
            let d = self.dirent(i)?;
            if d.ns == ns && d.path == path {
                return Ok(Some(i));
            }
        }
        Ok(None)
    }

    /// Range of entry indices in a namespace.
    pub fn namespace_range(&self, ns: u8) -> Result<std::ops::Range<u32>> {
        let start = self.lower_bound(ns, "")?;
        let end = if ns == u8::MAX { self.header.entry_count } else { self.lower_bound(ns + 1, "")? };
        Ok(start..end)
    }

    /// Follow redirects (at most [`MAX_REDIRECTS`]) to a content entry.
    pub fn resolve(&self, idx: u32) -> Result<(u32, Arc<Dirent>)> {
        let mut cur = idx;
        for _ in 0..=MAX_REDIRECTS {
            let d = self.dirent(cur)?;
            match d.kind {
                Kind::Redirect { target } => cur = target,
                Kind::Content { .. } => return Ok((cur, d)),
                Kind::Other => {
                    return Err(CoreError::NotFound(format!("entry {} is deleted or a link target", d.path)))
                }
            }
        }
        Err(invalid(format!("redirect loop or chain longer than {MAX_REDIRECTS} at entry {idx}")))
    }

    pub fn cluster_loc(&self, cluster: u32) -> Result<ClusterLoc> {
        if cluster >= self.header.cluster_count {
            return Err(invalid(format!("cluster {cluster} out of range")));
        }
        let pos = self.header.cluster_ptr_pos + cluster as u64 * 8;
        let (start, end) = if cluster + 1 < self.header.cluster_count {
            let mut b = [0u8; 16];
            self.read_at(&mut b, pos)?;
            (u64::from_le_bytes(b[..8].try_into().unwrap()), u64::from_le_bytes(b[8..].try_into().unwrap()))
        } else {
            (self.read_u64(pos)?, self.clusters_end)
        };
        if start >= end || end > self.size {
            return Err(invalid(format!("cluster {cluster} has a bad position")));
        }
        let mut info = [0u8; 1];
        self.read_at(&mut info, start)?;
        Ok(ClusterLoc { start, end, compression: Compression::from_info(info[0]), extended: info[0] & 0x10 != 0 })
    }

    /// Decompressed cluster from the cache, or read + decompress it now.
    pub fn cluster(&self, cluster: u32, loc: &ClusterLoc) -> Result<Arc<Cluster>> {
        if let Some(c) = lock(&shared().clusters).get(&(self.id, cluster)) {
            return Ok(c);
        }
        let _loading = lock(&self.loading[cluster as usize % 16]);
        if let Some(c) = lock(&shared().clusters).get(&(self.id, cluster)) {
            return Ok(c); // another thread just inflated it
        }
        let data = self.inflate(cluster, loc)?;
        let c = Arc::new(Cluster::parse(data, loc.extended)?);
        let bytes = c.byte_size();
        lock(&shared().clusters).put((self.id, cluster), c.clone(), bytes);
        if self.is_closed() {
            // closed while inflating: do not keep its clusters around
            lock(&shared().clusters).retain(|k| *k != (self.id, cluster));
        }
        Ok(c)
    }

    /// Read and inflate a whole cluster, streaming from the file: memory is bounded by
    /// what the data really inflates to (at most `MAX_CLUSTER_BYTES`), never by a size
    /// the file merely declares, and allocation failures are errors, not aborts.
    fn inflate(&self, cluster: u32, loc: &ClusterLoc) -> Result<Vec<u8>> {
        let raw_len = loc.end - loc.start - 1;
        if raw_len > MAX_CLUSTER_BYTES {
            return Err(invalid(format!("cluster {cluster} is implausibly large")));
        }
        let io = Rc::new(Cell::new(None));
        let reader = || {
            let range = RangeReader { a: self, pos: loc.start + 1, end: loc.end, failed: io.clone() };
            std::io::BufReader::with_capacity((raw_len as usize).clamp(1, READ_CHUNK), range)
        };
        let what = format!("cluster {cluster}");
        let guess = (raw_len as usize).saturating_mul(4).min(INFLATE_HINT_MAX);
        match loc.compression {
            Compression::None => read_bounded(reader(), MAX_CLUSTER_BYTES, raw_len as usize, &io, &what),
            Compression::Zstd => {
                let mut input = reader();
                let declared = input.fill_buf().ok().and_then(zstd_content_size);
                let hint = declared.filter(|&n| n <= MAX_CLUSTER_BYTES).map(|n| n as usize).unwrap_or(guess);
                read_bounded(zstd_reader(input)?, MAX_CLUSTER_BYTES, hint, &io, &what)
            }
            Compression::Xz => {
                // lzma-rs inflates a whole xz block into its own unbounded buffer before
                // our size-limited writer sees a byte, so an xz bomb is caught first, from
                // the LZMA2 chunk headers, without decoding.
                if raw_len > MAX_XZ_CLUSTER_BYTES + (1 << 20) {
                    return Err(CoreError::Unsupported(format!(
                        "xz {what} is {raw_len} bytes (limit {MAX_XZ_CLUSTER_BYTES})"
                    )));
                }
                let mut raw = zeroed(raw_len, &what)?;
                self.read_at(&mut raw, loc.start + 1)?;
                match xz_unpacked_bound(&raw) {
                    None => return Err(invalid(format!("xz {what}: not a readable xz stream"))),
                    Some(n) if n > MAX_CLUSTER_BYTES => {
                        return Err(invalid(format!("{what} inflates beyond {MAX_CLUSTER_BYTES} bytes")))
                    }
                    Some(n) if n > MAX_XZ_CLUSTER_BYTES => {
                        return Err(CoreError::Unsupported(format!(
                            "xz {what} inflates to {n} bytes (limit {MAX_XZ_CLUSTER_BYTES})"
                        )))
                    }
                    Some(_) => {}
                }
                let mut out = Limited { v: Vec::new(), oom: false };
                let _ = out.v.try_reserve_exact(guess);
                match lzma_rs::xz_decompress(&mut &raw[..], &mut out) {
                    Ok(()) => Ok(out.v),
                    Err(_) if out.oom => Err(no_memory(&what, out.v.len() as u64)),
                    Err(e) => Err(invalid(format!("xz {what}: {e}"))),
                }
            }
            Compression::Zlib => Err(CoreError::Unsupported("zlib-compressed cluster".into())),
            Compression::Bzip2 => Err(CoreError::Unsupported("bzip2-compressed cluster".into())),
            Compression::Unknown(c) => Err(invalid(format!("unknown cluster compression {c}"))),
        }
    }

    /// A sequential reader over a cluster's (decoded) data, for uncompressed and zstd
    /// clusters, plus its offset table. Reads only as far as the caller reads. The table
    /// is read in pieces, so a corrupt first offset costs no more memory than the data
    /// really behind it, and it may not promise more blobs than the archive has entries.
    fn cluster_stream(&self, cluster: u32, loc: &ClusterLoc) -> Result<ClusterStream<'_>> {
        let osz: usize = if loc.extended { 8 } else { 4 };
        let io = Rc::new(Cell::new(None));
        let range = RangeReader { a: self, pos: loc.start + 1, end: loc.end, failed: io.clone() };
        let r: Box<dyn Read + '_> = match loc.compression {
            Compression::None => Box::new(range),
            Compression::Zstd => zstd_reader(std::io::BufReader::with_capacity(32 * 1024, range))?,
            _ => return Err(CoreError::Unsupported("streamed read of this cluster type".into())),
        };
        let mut s = ClusterStream { r, offs: Vec::new(), io, cluster };
        let le = |c: &[u8]| -> u64 {
            if osz == 8 {
                u64::from_le_bytes(c.try_into().unwrap())
            } else {
                u32::from_le_bytes(c.try_into().unwrap()) as u64
            }
        };
        let first = le(&s.read_vec(osz as u64)?);
        let bound = if loc.compression == Compression::None { loc.end - loc.start - 1 } else { MAX_CLUSTER_BYTES };
        let most_blobs = (self.header.entry_count as u64 + 1) * osz as u64;
        if first == 0 || first % osz as u64 != 0 || first > bound || first > most_blobs {
            return Err(invalid(format!("bad offset table in cluster {cluster}")));
        }
        let mut offs = vec![first];
        let mut left = first - osz as u64;
        while left > 0 {
            let n = left.min(64 * 1024);
            offs.extend(s.read_vec(n)?.chunks_exact(osz).map(le));
            left -= n;
        }
        if offs.windows(2).any(|w| w[1] < w[0]) || *offs.last().unwrap() > bound {
            return Err(invalid(format!("bad offset table in cluster {cluster}")));
        }
        s.offs = offs;
        Ok(s)
    }

    /// Sizes of every blob in a cluster, reading as little as possible: the offset table
    /// of an uncompressed cluster, only the first decoded bytes of a zstd cluster (one
    /// zstd block, ~30-60 KB read), the whole cluster for xz. Used for the popularity
    /// signal of the search index.
    pub fn blob_sizes(&self, cluster: u32) -> Result<Vec<u64>> {
        let loc = self.cluster_loc(cluster)?;
        match loc.compression {
            Compression::None | Compression::Zstd => {
                let s = self.cluster_stream(cluster, &loc)?;
                Ok(s.offs.windows(2).map(|w| w[1] - w[0]).collect())
            }
            Compression::Xz => {
                let c = self.cluster(cluster, &loc)?;
                Ok((0..c.blob_count()).map(|i| c.blob(i).map(|b| b.len() as u64).unwrap_or(0)).collect())
            }
            Compression::Zlib | Compression::Bzip2 => Err(CoreError::Unsupported("zlib/bzip2 cluster".into())),
            Compression::Unknown(c) => Err(invalid(format!("unknown cluster compression {c}"))),
        }
    }

    /// Contents of several small entries of one cluster (metadata) with one partial
    /// decode: blobs ending within `limit` decoded bytes of a zstd cluster are read without
    /// inflating or caching the whole cluster; others are read normally. Blobs over
    /// `MAX_META_BYTES` are left out (corrupt metadata).
    pub fn small_blobs(&self, cluster: u32, blobs: &[u32], limit: u64) -> Result<HashMap<u32, Vec<u8>>> {
        let mut out = HashMap::new();
        let loc = self.cluster_loc(cluster)?;
        let cached = lock(&shared().clusters).get(&(self.id, cluster));
        if loc.compression == Compression::Zstd && cached.is_none() {
            let mut s = self.cluster_stream(cluster, &loc)?;
            let n = s.offs.len() - 1;
            let mut want: Vec<u32> = blobs.iter().copied().filter(|&b| (b as usize) < n).collect();
            want.sort_unstable();
            want.dedup();
            let mut pos = s.offs[0];
            for b in want {
                let (start, end) = (s.offs[b as usize], s.offs[b as usize + 1]);
                if end > limit {
                    break;
                }
                s.skip(start - pos)?;
                out.insert(b, s.read_vec(end - start)?);
                pos = end;
            }
        }
        for &b in blobs {
            if let std::collections::hash_map::Entry::Vacant(e) = out.entry(b) {
                let l = self.blob_loc(cluster, b)?;
                if l.len() <= MAX_META_BYTES {
                    e.insert(self.read_blob(&l, 0, u64::MAX)?);
                }
            }
        }
        Ok(out)
    }

    /// Content of a small entry near the start of its cluster (metadata) without
    /// inflating or caching the whole cluster: decodes only up to the blob's end when
    /// that is within `limit` decoded bytes, else reads the cluster normally. At most
    /// `MAX_META_BYTES`.
    pub fn content_small(&self, d: &Dirent, limit: u64) -> Result<Vec<u8>> {
        let Kind::Content { cluster, blob } = d.kind else {
            return Err(CoreError::NotFound(format!("{} has no content", d.path)));
        };
        let loc = self.cluster_loc(cluster)?;
        let cached = lock(&shared().clusters).get(&(self.id, cluster));
        if loc.compression == Compression::Zstd && cached.is_none() {
            let mut s = self.cluster_stream(cluster, &loc)?;
            let b = blob as usize;
            if b >= s.offs.len() - 1 {
                return Err(invalid(format!("blob {blob} out of range in cluster {cluster}")));
            }
            let (first, start, end) = (s.offs[0], s.offs[b], s.offs[b + 1]);
            if end <= limit {
                s.skip(start - first)?;
                return s.read_vec(end - start);
            }
        }
        self.content_max(d, MAX_META_BYTES)
    }

    /// Locate a blob without reading its bytes (uncompressed clusters stay on disk).
    pub fn blob_loc(&self, cluster: u32, blob: u32) -> Result<BlobLoc> {
        let loc = self.cluster_loc(cluster)?;
        match loc.compression {
            Compression::None => {
                let osz: u64 = if loc.extended { 8 } else { 4 };
                let data_start = loc.start + 1;
                let data_len = loc.end - data_start;
                let read_off = |i: u64| -> Result<u64> {
                    let mut b = [0u8; 8];
                    self.read_at(&mut b[..osz as usize], data_start + i * osz)?;
                    Ok(u64::from_le_bytes(b))
                };
                let first = read_off(0)?;
                if first == 0 || first % osz != 0 || first > data_len {
                    return Err(invalid(format!("bad offset table in cluster {cluster}")));
                }
                let count = first / osz - 1;
                if blob as u64 >= count {
                    return Err(invalid(format!("blob {blob} out of range in cluster {cluster}")));
                }
                let mut pair = [0u8; 16];
                self.read_at(&mut pair[..2 * osz as usize], data_start + blob as u64 * osz)?;
                let (a, b) = if osz == 8 {
                    (
                        u64::from_le_bytes(pair[..8].try_into().unwrap()),
                        u64::from_le_bytes(pair[8..].try_into().unwrap()),
                    )
                } else {
                    (
                        u32::from_le_bytes(pair[..4].try_into().unwrap()) as u64,
                        u32::from_le_bytes(pair[4..8].try_into().unwrap()) as u64,
                    )
                };
                if a > b || b > data_len {
                    return Err(invalid(format!("bad blob bounds in cluster {cluster}")));
                }
                Ok(BlobLoc::File { offset: data_start + a, len: b - a })
            }
            Compression::Xz | Compression::Zstd => {
                let c = self.cluster(cluster, &loc)?;
                c.blob(blob)?;
                Ok(BlobLoc::Mem { cluster: c, blob })
            }
            Compression::Zlib => Err(CoreError::Unsupported("zlib-compressed cluster".into())),
            Compression::Bzip2 => Err(CoreError::Unsupported("bzip2-compressed cluster".into())),
            Compression::Unknown(c) => Err(invalid(format!("unknown cluster compression {c}"))),
        }
    }

    /// Read `len` bytes at `offset` inside a blob (clamped to the blob end).
    pub fn read_blob(&self, loc: &BlobLoc, offset: u64, len: u64) -> Result<Vec<u8>> {
        let total = loc.len();
        if offset >= total {
            return Ok(Vec::new());
        }
        let n = len.min(total - offset);
        match loc {
            BlobLoc::File { offset: base, .. } => {
                let mut buf = zeroed(n, "entry")?;
                self.read_at(&mut buf, base + offset)?;
                Ok(buf)
            }
            BlobLoc::Mem { cluster, blob } => {
                let b = cluster.blob(*blob)?;
                Ok(b[offset as usize..(offset + n) as usize].to_vec())
            }
        }
    }

    /// Whole content of a content dirent (at most `MAX_GET_BYTES`).
    pub fn content(&self, d: &Dirent) -> Result<Vec<u8>> {
        self.content_max(d, MAX_GET_BYTES)
    }

    /// Whole content of a content dirent; `Unsupported` when it is bigger than `max`.
    pub fn content_max(&self, d: &Dirent, max: u64) -> Result<Vec<u8>> {
        match d.kind {
            Kind::Content { cluster, blob } => {
                let loc = self.blob_loc(cluster, blob)?;
                if loc.len() > max {
                    return Err(CoreError::Unsupported(format!("{} is {} bytes (limit {max})", d.path, loc.len())));
                }
                self.read_blob(&loc, 0, u64::MAX)
            }
            _ => Err(CoreError::NotFound(format!("{} has no content", d.path))),
        }
    }

    /// A title-ordered listing stored as a blob (`X/listing/titleOrdered/v0|v1`).
    pub fn listing_blob(&self, name: &str) -> Result<Option<TitleList>> {
        match self.find(b'X', name)? {
            Some(idx) => self.listing_at(idx),
            None => Ok(None),
        }
    }

    /// The title listing stored in entry `idx`.
    pub fn listing_at(&self, idx: u32) -> Result<Option<TitleList>> {
        let name = format!("#{idx}");
        let (_, d) = self.resolve(idx)?;
        let Kind::Content { cluster, blob } = d.kind else { return Ok(None) };
        let loc = self.blob_loc(cluster, blob)?;
        let len = loc.len();
        if len % 4 != 0 || len / 4 > self.header.entry_count as u64 {
            return Err(invalid(format!("listing {name} has a bad size")));
        }
        Ok(Some(match loc {
            BlobLoc::File { offset, len } => TitleList::File { base: offset, count: (len / 4) as u32 },
            BlobLoc::Mem { .. } => {
                let bytes = self.read_blob(&loc, 0, len)?;
                TitleList::Mem(bytes.chunks_exact(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())).collect())
            }
        }))
    }

    /// The classic title pointer list from the header, if the file has one.
    pub fn header_title_list(&self) -> Option<TitleList> {
        let pos = self.header.title_ptr_pos;
        let n = self.header.entry_count as u64;
        if pos == u64::MAX || pos == 0 || pos.checked_add(n * 4).is_none_or(|e| e > self.size) {
            return None;
        }
        Some(TitleList::File { base: pos, count: self.header.entry_count })
    }

    pub fn title_list_len(&self, l: &TitleList) -> u32 {
        match l {
            TitleList::File { count, .. } => *count,
            TitleList::Mem(v) => v.len() as u32,
        }
    }

    pub fn title_list_get(&self, l: &TitleList, i: u32) -> Result<u32> {
        match l {
            TitleList::File { base, count } => {
                if i >= *count {
                    return Err(invalid("title list index out of range"));
                }
                let mut b = [0u8; 4];
                self.read_at(&mut b, base + i as u64 * 4)?;
                Ok(u32::from_le_bytes(b))
            }
            TitleList::Mem(v) => v.get(i as usize).copied().ok_or_else(|| invalid("title list index out of range")),
        }
    }

    /// Read a contiguous run of a title list (for scans).
    pub fn title_list_slice(&self, l: &TitleList, start: u32, n: u32) -> Result<Vec<u32>> {
        let len = self.title_list_len(l);
        let start = start.min(len);
        let n = n.min(len - start);
        match l {
            TitleList::File { base, .. } => {
                let mut b = vec![0u8; n as usize * 4];
                self.read_at(&mut b, base + start as u64 * 4)?;
                Ok(b.chunks_exact(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())).collect())
            }
            TitleList::Mem(v) => Ok(v[start as usize..(start + n) as usize].to_vec()),
        }
    }

    /// Entry index of the main page as stored in the header.
    pub fn header_main_page(&self) -> Option<u32> {
        let m = self.header.main_page;
        (m != NO_PAGE && m < self.header.entry_count).then_some(m)
    }

    /// Path as the apps use it: plain path for the article namespace of the new
    /// scheme, `<ns>/<path>` otherwise.
    pub fn api_path(&self, d: &Dirent) -> String {
        if self.new_scheme() && d.ns == b'C' {
            d.path.clone()
        } else {
            format!("{}/{}", d.ns as char, d.path)
        }
    }

    /// Find an entry by an API path (see [`Archive::api_path`]).
    pub fn find_api_path(&self, path: &str) -> Result<Option<u32>> {
        let path = path.strip_prefix('/').unwrap_or(path);
        if self.new_scheme() {
            if let Some(i) = self.find(b'C', path)? {
                return Ok(Some(i));
            }
        }
        let b = path.as_bytes();
        if b.len() >= 2 && b[1] == b'/' && b[0].is_ascii() {
            if let Some(i) = self.find(b[0], &path[2..])? {
                return Ok(Some(i));
            }
        }
        if !self.new_scheme() {
            return self.find(b'A', path);
        }
        Ok(None)
    }

    pub fn clear_caches(&self) {
        self.purge_caches();
    }
}

fn read_mime_list(src: &dyn ReadAt, pos: u64, size: u64) -> Result<Vec<String>> {
    let mut mimes = Vec::new();
    let mut buf = Vec::new();
    let mut chunk = 4096u64;
    loop {
        let len = chunk.min(size - pos) as usize;
        buf.resize(len, 0);
        src.read_exact_at(&mut buf, pos)?;
        mimes.clear();
        let mut i = 0;
        let mut done = false;
        while let Some(j) = buf[i..].iter().position(|&c| c == 0) {
            if j == 0 {
                done = true;
                break;
            }
            mimes.push(String::from_utf8_lossy(&buf[i..i + j]).into_owned());
            i += j + 1;
        }
        if done {
            return Ok(mimes);
        }
        if len as u64 >= size - pos || chunk >= 1 << 20 {
            return Err(invalid("unterminated MIME type list"));
        }
        chunk *= 4;
    }
}

/// Reads a byte range of the archive sequentially with `pread`. A failed read is kept
/// in `failed` so the caller reports it as itself (`Io`), not as a decoder error.
struct RangeReader<'a> {
    a: &'a Archive,
    pos: u64,
    end: u64,
    failed: Rc<Cell<Option<CoreError>>>,
}

impl Read for RangeReader<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = (buf.len() as u64).min(self.end.saturating_sub(self.pos)) as usize;
        if n == 0 {
            return Ok(0);
        }
        if let Err(e) = self.a.read_at(&mut buf[..n], self.pos) {
            let msg = e.to_string();
            self.failed.set(Some(e));
            return Err(std::io::Error::other(msg));
        }
        self.pos += n as u64;
        Ok(n)
    }
}

/// A decoded cluster read sequentially, with its offset table.
struct ClusterStream<'a> {
    r: Box<dyn Read + 'a>,
    offs: Vec<u64>,
    io: Rc<Cell<Option<CoreError>>>,
    cluster: u32,
}

impl ClusterStream<'_> {
    fn fail(&self, e: std::io::Error) -> CoreError {
        self.io.take().unwrap_or_else(|| invalid(format!("cluster {}: {e}", self.cluster)))
    }

    fn read_vec(&mut self, n: u64) -> Result<Vec<u8>> {
        let mut v = zeroed(n, "cluster data")?;
        let r = self.r.read_exact(&mut v);
        r.map_err(|e| self.fail(e))?;
        Ok(v)
    }

    fn skip(&mut self, n: u64) -> Result<()> {
        let r = std::io::copy(&mut (&mut self.r).take(n), &mut std::io::sink());
        let got = r.map_err(|e| self.fail(e))?;
        if got < n {
            return Err(invalid(format!("cluster {}: data ends early", self.cluster)));
        }
        Ok(())
    }
}

/// Read a stream to its end, at most `limit` bytes (more is an error). Grows from
/// `hint` with fallible allocations; an exact hint means one allocation.
fn read_bounded(
    mut r: impl Read,
    limit: u64,
    hint: usize,
    io: &Cell<Option<CoreError>>,
    what: &str,
) -> Result<Vec<u8>> {
    let max = usize::try_from(limit).unwrap_or(usize::MAX);
    let fail = |e: std::io::Error| io.take().unwrap_or_else(|| invalid(format!("{what}: {e}")));
    let read = |r: &mut dyn Read, b: &mut [u8]| loop {
        match r.read(b) {
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            x => break x,
        }
    };
    let mut buf: Vec<u8> = Vec::new();
    let mut len = 0usize;
    let mut next = hint.clamp(1, max);
    loop {
        if len == buf.len() {
            let mut probe = [0u8; 256];
            let mut extra = 0;
            if len > 0 {
                // Full: look for the end before growing (an exact hint never grows).
                extra = read(&mut r, &mut probe).map_err(fail)?;
                if extra == 0 {
                    break;
                }
            }
            if len + extra > max {
                return Err(invalid(format!("{what} inflates beyond {limit} bytes")));
            }
            let grow = next.max(extra).min(max - len).max(extra);
            buf.try_reserve_exact(grow).map_err(|_| no_memory(what, (len + grow) as u64))?;
            buf.resize(len + grow, 0);
            buf[len..len + extra].copy_from_slice(&probe[..extra]);
            len += extra;
            next = buf.len();
            continue;
        }
        match read(&mut r, &mut buf[len..]).map_err(fail)? {
            0 => break,
            n => len += n,
        }
    }
    buf.truncate(len);
    if buf.capacity() - len > len / 8 + 4096 {
        buf.shrink_to_fit();
    }
    Ok(buf)
}

#[cfg(feature = "zstd-c")]
fn zstd_reader<'a, R: std::io::BufRead + 'a>(r: R) -> Result<Box<dyn Read + 'a>> {
    Ok(Box::new(zstd::stream::read::Decoder::with_buffer(r).map_err(|e| invalid(format!("zstd cluster: {e}")))?))
}

#[cfg(all(feature = "zstd-pure", not(feature = "zstd-c")))]
fn zstd_reader<'a, R: std::io::BufRead + 'a>(r: R) -> Result<Box<dyn Read + 'a>> {
    Ok(Box::new(ruzstd::decoding::StreamingDecoder::new(r).map_err(|e| invalid(format!("zstd cluster: {e}")))?))
}

/// Content size declared in a zstd frame header, if any (a size hint only).
#[cfg(feature = "zstd-c")]
fn zstd_content_size(head: &[u8]) -> Option<u64> {
    zstd::zstd_safe::get_frame_content_size(head).ok().flatten()
}

#[cfg(all(feature = "zstd-pure", not(feature = "zstd-c")))]
fn zstd_content_size(_head: &[u8]) -> Option<u64> {
    None
}

#[cfg(not(any(feature = "zstd-c", feature = "zstd-pure")))]
compile_error!("enable feature zstd-c (default) or zstd-pure");

/// The most an xz stream inflates to, from its block and LZMA2 chunk headers alone (no
/// decoding): every chunk declares its unpacked size and lzma-rs stops each chunk there
/// (overshooting by at most one 273-byte match). `None`: not a structure lzma-rs reads.
fn xz_unpacked_bound(x: &[u8]) -> Option<u64> {
    const MAGIC: [u8; 6] = [0xfd, b'7', b'z', b'X', b'Z', 0];
    if x.len() < 12 || x[..6] != MAGIC {
        return None;
    }
    let check = match x[7] & 0x0f {
        0 => 0,
        1 => 4,
        4 => 8,
        10 => 32,
        _ => return None,
    };
    let be16 = |p: usize| -> Option<u64> { Some(u16::from_be_bytes([*x.get(p)?, *x.get(p.checked_add(1)?)?]) as u64) };
    let mut pos = 12usize;
    let mut total = 0u64;
    loop {
        let h = *x.get(pos)?;
        if h == 0 {
            return Some(total); // the index: no more blocks
        }
        let block = pos;
        pos = pos.checked_add((h as usize + 1) * 4)?;
        loop {
            let c = *x.get(pos)?;
            match c {
                0 => {
                    pos += 1;
                    break;
                }
                1 | 2 => {
                    let n = be16(pos + 1)? + 1;
                    total += n;
                    pos = pos.checked_add(3 + n as usize)?;
                }
                0x80..=0xff => {
                    let unpacked = ((((c & 0x1f) as u64) << 16) | be16(pos + 1)?) + 1;
                    let packed = be16(pos + 3)? + 1;
                    total += unpacked + 273;
                    pos = pos.checked_add(5 + (c >= 0xc0) as usize + packed as usize)?;
                }
                _ => return None,
            }
            if total > MAX_CLUSTER_BYTES {
                return Some(total); // a bomb: no need to read further
            }
        }
        pos = pos.checked_add((4 - (pos - block) % 4) % 4)?.checked_add(check)?;
    }
}

/// A `Vec` writer that refuses to grow past the cluster limit (xz bombs) and fails
/// instead of aborting when memory runs out.
struct Limited {
    v: Vec<u8>,
    oom: bool,
}

impl std::io::Write for Limited {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let len = self.v.len();
        if len as u64 + buf.len() as u64 > MAX_CLUSTER_BYTES {
            return Err(std::io::Error::other("cluster inflates beyond the size limit"));
        }
        if self.v.capacity() - len < buf.len() {
            let grow = len.max(buf.len()).min(MAX_CLUSTER_BYTES as usize - len).max(buf.len());
            if self.v.try_reserve_exact(grow).is_err() {
                self.oom = true;
                return Err(std::io::ErrorKind::OutOfMemory.into());
            }
        }
        self.v.extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
