//! Our own ZIM reader, written from the openZIM file format description.
//!
//! Layout: an 80-byte header, a MIME type list, the path pointer list (u64 offsets of
//! directory entries sorted by namespace + path), optionally a title pointer list, the
//! cluster pointer list and the clusters. Content lives in blobs inside clusters; a
//! cluster is uncompressed or xz/zstd compressed and starts with an offset table of
//! u32 (normal) or u64 (extended) offsets.

use std::io::Read;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::cache::ByteLru;
use crate::error::{invalid, CoreError, Result};
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
/// Default budget of the decompressed-cluster cache.
pub const DEFAULT_CLUSTER_CACHE_BYTES: usize = 48 * 1024 * 1024;
const DIRENT_CACHE_ENTRIES: usize = 16 * 1024;

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
        (self.offsets.len().saturating_sub(1)) as u32
    }

    pub fn blob(&self, i: u32) -> Result<&[u8]> {
        let i = i as usize;
        if i + 1 >= self.offsets.len() {
            return Err(invalid(format!("blob {i} out of range")));
        }
        Ok(&self.data[self.offsets[i] as usize..self.offsets[i + 1] as usize])
    }

    pub fn byte_size(&self) -> usize {
        self.data.len() + self.offsets.len() * 8 + 64
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
    src: Box<dyn ReadAt>,
    pub header: Header,
    pub mimes: Vec<String>,
    size: u64,
    clusters_end: u64,
    cluster_cache: Mutex<ByteLru<u32, Arc<Cluster>>>,
    dirent_cache: Mutex<lru::LruCache<u32, Arc<Dirent>>>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Archive {
    pub fn open(src: Box<dyn ReadAt>) -> Result<Archive> {
        Self::open_with_cache(src, DEFAULT_CLUSTER_CACHE_BYTES)
    }

    pub fn open_with_cache(src: Box<dyn ReadAt>, cache_bytes: usize) -> Result<Archive> {
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
            src,
            header,
            mimes,
            size,
            clusters_end,
            cluster_cache: Mutex::new(ByteLru::new(cache_bytes)),
            dirent_cache: Mutex::new(lru::LruCache::new(std::num::NonZeroUsize::new(DIRENT_CACHE_ENTRIES).unwrap())),
        })
    }

    pub fn size(&self) -> u64 {
        self.size
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
        self.src.read_exact_at(buf, offset).map_err(CoreError::from)
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
        if let Some(d) = lock(&self.dirent_cache).get(&idx) {
            return Ok(d.clone());
        }
        let d = Arc::new(self.dirent_at(self.dirent_offset(idx)?)?);
        lock(&self.dirent_cache).put(idx, d.clone());
        Ok(d)
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
        let start = self.read_u64(pos)?;
        let end = if cluster + 1 < self.header.cluster_count { self.read_u64(pos + 8)? } else { self.clusters_end };
        if start >= end || end > self.size {
            return Err(invalid(format!("cluster {cluster} has a bad position")));
        }
        let mut info = [0u8; 1];
        self.read_at(&mut info, start)?;
        Ok(ClusterLoc { start, end, compression: Compression::from_info(info[0]), extended: info[0] & 0x10 != 0 })
    }

    /// Decompressed cluster from the cache, or read + decompress it now.
    pub fn cluster(&self, cluster: u32, loc: &ClusterLoc) -> Result<Arc<Cluster>> {
        if let Some(c) = lock(&self.cluster_cache).get(&cluster) {
            return Ok(c);
        }
        let raw_len = loc.end - loc.start - 1;
        if raw_len > MAX_CLUSTER_BYTES {
            return Err(invalid(format!("cluster {cluster} is implausibly large")));
        }
        let mut raw = vec![0u8; raw_len as usize];
        self.read_at(&mut raw, loc.start + 1)?;
        let data = decompress(loc.compression, raw)?;
        let c = Arc::new(Cluster::parse(data, loc.extended)?);
        let bytes = c.byte_size();
        lock(&self.cluster_cache).put(cluster, c.clone(), bytes);
        Ok(c)
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
                let a = read_off(blob as u64)?;
                let b = read_off(blob as u64 + 1)?;
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
                let mut buf = vec![0u8; n as usize];
                self.read_at(&mut buf, base + offset)?;
                Ok(buf)
            }
            BlobLoc::Mem { cluster, blob } => {
                let b = cluster.blob(*blob)?;
                Ok(b[offset as usize..(offset + n) as usize].to_vec())
            }
        }
    }

    /// Whole content of a content dirent.
    pub fn content(&self, d: &Dirent) -> Result<Vec<u8>> {
        match d.kind {
            Kind::Content { cluster, blob } => {
                let loc = self.blob_loc(cluster, blob)?;
                self.read_blob(&loc, 0, u64::MAX)
            }
            _ => Err(CoreError::NotFound(format!("{} has no content", d.path))),
        }
    }

    /// A title-ordered listing stored as a blob (`X/listing/titleOrdered/v0|v1`).
    pub fn listing_blob(&self, name: &str) -> Result<Option<TitleList>> {
        let Some(idx) = self.find(b'X', name)? else { return Ok(None) };
        let (_, d) = self.resolve(idx)?;
        let Kind::Content { cluster, blob } = d.kind else { return Ok(None) };
        let loc = self.blob_loc(cluster, blob)?;
        let len = loc.len();
        if len % 4 != 0 {
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
        lock(&self.cluster_cache).clear();
        lock(&self.dirent_cache).clear();
    }

    pub fn cluster_cache_bytes(&self) -> usize {
        lock(&self.cluster_cache).bytes()
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

fn decompress(c: Compression, raw: Vec<u8>) -> Result<Vec<u8>> {
    match c {
        Compression::None => Ok(raw),
        Compression::Zstd => {
            let mut dec =
                ruzstd::decoding::StreamingDecoder::new(&raw[..]).map_err(|e| invalid(format!("zstd cluster: {e}")))?;
            let mut out = Vec::with_capacity(raw.len() * 4);
            (&mut dec)
                .take(MAX_CLUSTER_BYTES + 1)
                .read_to_end(&mut out)
                .map_err(|e| invalid(format!("zstd cluster: {e}")))?;
            check_inflated(&out)?;
            Ok(out)
        }
        Compression::Xz => {
            let mut out = Limited(Vec::with_capacity(raw.len() * 4));
            let mut input = std::io::BufReader::new(&raw[..]);
            lzma_rs::xz_decompress(&mut input, &mut out).map_err(|e| invalid(format!("xz cluster: {e}")))?;
            check_inflated(&out.0)?;
            Ok(out.0)
        }
        Compression::Zlib => Err(CoreError::Unsupported("zlib-compressed cluster".into())),
        Compression::Bzip2 => Err(CoreError::Unsupported("bzip2-compressed cluster".into())),
        Compression::Unknown(c) => Err(invalid(format!("unknown cluster compression {c}"))),
    }
}

/// A `Vec` writer that refuses to grow past the cluster limit (xz bombs).
struct Limited(Vec<u8>);

impl std::io::Write for Limited {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if self.0.len() as u64 + buf.len() as u64 > MAX_CLUSTER_BYTES {
            return Err(std::io::Error::other("cluster inflates beyond the size limit"));
        }
        self.0.extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn check_inflated(out: &[u8]) -> Result<()> {
    if out.len() as u64 > MAX_CLUSTER_BYTES {
        return Err(invalid("cluster inflates beyond the size limit"));
    }
    Ok(())
}
