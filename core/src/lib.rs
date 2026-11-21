//! Offlinka core: our own ZIM reader, a title search index and the UniFFI API the
//! Android and iOS apps call. The API surface is the contract in `docs/SPEC.md`.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};

pub mod cache;
pub mod error;
pub mod index;
pub mod normalize;
pub mod reader;
mod search;
pub mod zim;

pub use error::CoreError;
use error::Result;
use index::SearchIndex;
use reader::{FileSource, ReadAt};
use zim::{Archive, Kind, TitleList};

uniffi::setup_scaffolding!();

/// Version of the core library (crate version).
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// What the apps show about a book.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BookInfo {
    /// Stable id: the ZIM `Name` metadata (e.g. `wikipedia_cs_all`), else the file name
    /// without extension. Same id for the maxi and mini edition of one wiki.
    pub id: String,
    /// `Name` metadata (may be empty).
    pub name: String,
    pub title: String,
    pub description: String,
    /// ISO 639-3 code(s) from the metadata, e.g. `ces`, `slk`, `eng`.
    pub language: String,
    pub creator: String,
    pub publisher: String,
    pub date: String,
    pub article_count: u64,
    pub media_count: u64,
    /// Path of the main page (pass it to `get`), if the book has one.
    pub main_path: Option<String>,
    pub size_bytes: u64,
    pub has_index: bool,
}

/// A resolved entry with its content.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Resource {
    /// Path after following redirects (differs from the requested path for a redirect).
    pub path: String,
    pub mime: String,
    pub data: Vec<u8>,
}

/// A search suggestion inside one book.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Suggestion {
    /// The title that matched (for a redirect: the redirect's own title).
    pub title: String,
    /// Path to open (redirects already resolved to their target).
    pub path: String,
    /// For a redirect: the title of the article it opens. Otherwise `None`.
    pub snippet: Option<String>,
}

/// A merged suggestion across the books of a library.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LibrarySuggestion {
    pub book_id: String,
    pub title: String,
    pub path: String,
}

/// One ZIM file. Thread-safe; share it freely.
///
/// Opening reads only the header and the MIME list; metadata, the main page and the
/// title listing are looked up on first use (`info`, `suggest`, ...) with a handful of
/// reads, independent of the book's size.
#[derive(uniffi::Object)]
pub struct Book {
    archive: Arc<Archive>,
    file_name: String,
    lazy: Mutex<Option<Arc<Lazy>>>,
    index: RwLock<Option<Arc<SearchIndex>>>,
}

/// What `info()` and the fallback search need, read on first use.
struct Lazy {
    info: BookInfo,
    meta: HashMap<String, u32>,
    titles: Option<TitleList>,
    /// The title list holds only front articles of the article namespace (v1 listing).
    titles_front_only: bool,
}

fn file_stem(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    base.strip_suffix(".zim").unwrap_or(base).to_string()
}

fn sanitize_id(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') { c } else { '_' }).collect()
}

/// Metadata values are small and sit early in their cluster: decode at most this much.
const META_DECODE_LIMIT: u64 = 1 << 20;
/// `M`, `W` and `X` entries are looked for among this many last entries.
const TAIL_ENTRIES: u32 = 512;

impl Book {
    /// Open from any byte source (used by tests and the CLI).
    pub fn open_source(src: Box<dyn ReadAt>, file_name: &str) -> Result<Arc<Book>> {
        let archive = Arc::new(Archive::open(src)?);
        Ok(Arc::new(Book {
            archive,
            file_name: file_name.to_string(),
            lazy: Mutex::new(None),
            index: RwLock::new(None),
        }))
    }

    /// Metadata, main page, listing: computed once (a failed attempt is retried later).
    fn lazy(&self) -> Result<Arc<Lazy>> {
        let mut g = self.lazy.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(l) = g.as_ref() {
            return Ok(l.clone());
        }
        let l = Arc::new(self.load_lazy()?);
        *g = Some(l.clone());
        Ok(l)
    }

    fn load_lazy(&self) -> Result<Lazy> {
        let a = &self.archive;
        // M (metadata), W (well-known) and X (listings) sort last in path order.
        let tail = a.tail_entries(TAIL_ENTRIES)?;
        let mut meta = HashMap::new();
        let mut well_known = HashMap::new();
        // The window must start before the M namespace to hold all of it.
        let complete = tail.len() as u32 == a.entry_count() || tail.first().is_some_and(|(_, d)| d.ns < b'M');
        if complete {
            for (i, d) in &tail {
                match d.ns {
                    b'M' => {
                        meta.insert(d.path.clone(), *i);
                    }
                    b'W' | b'X' => {
                        well_known.insert((d.ns, d.path.clone()), *i);
                    }
                    _ => {}
                }
            }
        } else {
            // Unusually many metadata entries: fall back to binary searches.
            let m = a.namespace_range(b'M')?;
            for i in m.start..m.end.min(m.start + 4096) {
                meta.insert(a.dirent(i)?.path.clone(), i);
            }
            for (ns, p) in [(b'W', "mainPage"), (b'X', "listing/titleOrdered/v1"), (b'X', "listing/titleOrdered/v0")] {
                if let Some(i) = a.find(ns, p)? {
                    well_known.insert((ns, p.to_string()), i);
                }
            }
        }
        // Text metadata, read cluster by cluster with one partial decode each.
        let mut values: HashMap<&str, String> = HashMap::new();
        {
            let names = ["Name", "Title", "Description", "Language", "Creator", "Publisher", "Date", "Counter"];
            let mut by_cluster: HashMap<u32, Vec<(&str, u32)>> = HashMap::new();
            for n in names {
                if let Some(Ok((_, d))) = meta.get(n).map(|&i| a.resolve(i)) {
                    if let Kind::Content { cluster, blob } = d.kind {
                        by_cluster.entry(cluster).or_default().push((n, blob));
                    }
                }
            }
            let mut clusters: Vec<_> = by_cluster.into_iter().collect();
            clusters.sort_unstable_by_key(|c| c.0);
            for (cluster, items) in clusters {
                let blobs: Vec<u32> = items.iter().map(|x| x.1).collect();
                if let Ok(got) = a.small_blobs(cluster, &blobs, META_DECODE_LIMIT) {
                    for (n, b) in items {
                        if let Some(v) = got.get(&b) {
                            values.insert(n, String::from_utf8_lossy(v).trim().to_string());
                        }
                    }
                }
            }
        }
        let text = |k: &str| -> String { values.get(k).cloned().unwrap_or_default() };
        let listing = |name: &str| -> Result<Option<TitleList>> {
            match well_known.get(&(b'X', name.to_string())) {
                Some(&i) => a.listing_at(i),
                None => Ok(None),
            }
        };

        let (titles, front_only) = if a.new_scheme() {
            match listing("listing/titleOrdered/v1")? {
                Some(l) => (Some(l), true),
                None => (listing("listing/titleOrdered/v0")?.or_else(|| a.header_title_list()), false),
            }
        } else {
            (a.header_title_list(), false)
        };

        let counter = text("Counter");
        let mut media = 0u64;
        let mut html = None::<u64>;
        for part in counter.split(';') {
            if let Some((mime, n)) = part.split_once('=') {
                let n: u64 = n.trim().parse().unwrap_or(0);
                let mime = mime.trim();
                if mime.starts_with("image/") || mime.starts_with("video/") || mime.starts_with("audio/") {
                    media += n;
                } else if mime.starts_with("text/html") {
                    *html.get_or_insert(0) += n;
                }
            }
        }
        // HTML pages from the counter (mwoffliner's v1 listing also holds redirects),
        // else the front-article listing, else every entry of the article namespace.
        let article_count = match (html, &titles, front_only) {
            (Some(n), _, _) => n,
            (None, Some(l), true) => a.title_list_len(l) as u64,
            _ => {
                let r = a.namespace_range(a.article_ns())?;
                (r.end - r.start) as u64
            }
        };

        let main_idx = well_known.get(&(b'W', "mainPage".to_string())).copied().or_else(|| a.header_main_page());
        let main_path = match main_idx {
            Some(i) => a.resolve(i).ok().map(|(_, d)| a.api_path(&d)),
            None => None,
        };

        let name = text("Name");
        let title = text("Title");
        let stem = file_stem(&self.file_name);
        let info = BookInfo {
            id: sanitize_id(if name.is_empty() { &stem } else { &name }),
            title: if title.is_empty() { stem.clone() } else { title },
            name,
            description: text("Description"),
            language: text("Language"),
            creator: text("Creator"),
            publisher: text("Publisher"),
            date: text("Date"),
            article_count,
            media_count: media,
            main_path,
            size_bytes: a.size(),
            has_index: false,
        };
        Ok(Lazy { info, meta, titles, titles_front_only: front_only })
    }

    /// Info without failing: a minimal record from the file name when metadata cannot be
    /// read (closed or corrupt book).
    fn info_or_default(&self) -> BookInfo {
        match self.lazy() {
            Ok(l) => l.info.clone(),
            Err(_) => {
                let stem = file_stem(&self.file_name);
                BookInfo {
                    id: sanitize_id(&stem),
                    name: String::new(),
                    title: stem,
                    description: String::new(),
                    language: String::new(),
                    creator: String::new(),
                    publisher: String::new(),
                    date: String::new(),
                    article_count: 0,
                    media_count: 0,
                    main_path: None,
                    size_bytes: self.archive.size(),
                    has_index: false,
                }
            }
        }
    }

    pub fn archive(&self) -> &Archive {
        &self.archive
    }

    pub fn metadata(&self, name: &str) -> Option<Vec<u8>> {
        let l = self.lazy().ok()?;
        let &i = l.meta.get(name)?;
        let (_, d) = self.archive.resolve(i).ok()?;
        self.archive.content_small(&d, META_DECODE_LIMIT).ok()
    }

    pub fn metadata_names(&self) -> Vec<String> {
        let Ok(l) = self.lazy() else { return Vec::new() };
        let mut v: Vec<String> = l.meta.keys().cloned().collect();
        v.sort();
        v
    }

    pub fn search_index(&self) -> Option<Arc<SearchIndex>> {
        self.index.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn resolve_path(&self, path: &str) -> Result<(u32, Arc<zim::Dirent>)> {
        self.check_open()?;
        let idx = self.archive.find_api_path(path)?.ok_or_else(|| CoreError::NotFound(path.to_string()))?;
        self.archive.resolve(idx)
    }

    fn blob_of(&self, path: &str) -> Result<(Arc<zim::Dirent>, zim::BlobLoc)> {
        let (_, d) = self.resolve_path(path)?;
        match d.kind {
            Kind::Content { cluster, blob } => {
                let loc = self.archive.blob_loc(cluster, blob)?;
                Ok((d, loc))
            }
            _ => Err(CoreError::NotFound(path.to_string())),
        }
    }

    fn attach(&self, idx: SearchIndex) -> Result<()> {
        self.check_open()?;
        if idx.uuid != self.archive.header.uuid || idx.entry_count != self.archive.entry_count() {
            return Err(CoreError::InvalidZim("the search index belongs to a different ZIM file".into()));
        }
        let idx = Arc::new(idx);
        index::register(&idx);
        *self.index.write().unwrap_or_else(|e| e.into_inner()) = Some(idx);
        Ok(())
    }

    fn check_open(&self) -> Result<()> {
        if self.archive.is_closed() {
            Err(CoreError::Io("the book is closed".into()))
        } else {
            Ok(())
        }
    }

    /// Ranked candidates with their match class (titles not read yet for indexed books).
    pub(crate) fn candidates(&self, query: &str, limit: usize) -> Vec<search::Cand> {
        match self.search_index() {
            Some(idx) => match search::indexed(&idx, query, limit) {
                Ok(v) => v,
                Err(_) => self.fallback_candidates(query, limit), // index unreadable: listing
            },
            None => self.fallback_candidates(query, limit),
        }
    }

    fn fallback_candidates(&self, query: &str, limit: usize) -> Vec<search::Cand> {
        let Ok(lazy) = self.lazy() else { return Vec::new() };
        match &lazy.titles {
            Some(l) => search::fallback(&self.archive, l, lazy.titles_front_only, query, limit)
                .map(|v| v.into_iter().map(search::Cand::Ready).collect())
                .unwrap_or_default(),
            None => Vec::new(),
        }
    }

    fn hits(&self, query: &str, limit: usize) -> Vec<search::Hit> {
        self.candidates(query, limit).into_iter().filter_map(|c| search::resolve(&self.archive, c).ok()).collect()
    }
}

#[uniffi::export]
impl Book {
    /// Open a ZIM file by path (iOS, desktop, tests).
    #[uniffi::constructor]
    pub fn open_path(path: String) -> std::result::Result<Arc<Book>, CoreError> {
        let src = FileSource::open(&path).map_err(|e| CoreError::Io(format!("{path}: {e}")))?;
        Self::open_source(Box::new(src), &path)
    }

    /// Open from a detached file descriptor (Android). The core owns the fd and closes
    /// it when the book is dropped, or right away when opening fails.
    #[uniffi::constructor]
    pub fn open_fd(fd: i32, name: String) -> std::result::Result<Arc<Book>, CoreError> {
        let src = FileSource::from_fd(fd).map_err(|e| CoreError::Io(format!("fd {fd} ({name}): {e}")))?;
        Self::open_source(Box::new(src), &name)
    }

    /// Book metadata. The first call reads it (a few small reads); later calls are free.
    /// Never fails: a closed or unreadable book gives a minimal record (title = file name).
    pub fn info(&self) -> BookInfo {
        let mut i = self.info_or_default();
        i.has_index = self.search_index().is_some();
        i
    }

    /// The book icon (PNG): `Illustration_<size>x<size>@1`, else the nearest size, else the favicon.
    pub fn illustration(&self, size: u32) -> Option<Vec<u8>> {
        if let Some(b) = self.metadata(&format!("Illustration_{size}x{size}@1")) {
            return Some(b);
        }
        let lazy = self.lazy().ok()?;
        let mut sizes: Vec<(u32, &String)> = lazy
            .meta
            .keys()
            .filter_map(|k| {
                let rest = k.strip_prefix("Illustration_")?;
                let (w, tail) = rest.split_once('x')?;
                let h = tail.split('@').next()?;
                if tail.ends_with("@1") && w == h {
                    Some((w.parse::<u32>().ok()?, k))
                } else {
                    None
                }
            })
            .collect();
        sizes.sort();
        let pick = sizes.iter().find(|(s, _)| *s >= size).or(sizes.last());
        if let Some((_, k)) = pick {
            if let Some(b) = self.metadata(k) {
                return Some(b);
            }
        }
        for (ns, p) in [(b'W', "favicon"), (b'-', "favicon"), (b'I', "favicon.png")] {
            if let Ok(Some(i)) = self.archive.find(ns, p) {
                if let Ok((_, d)) = self.archive.resolve(i) {
                    if let Ok(b) = self.archive.content(&d) {
                        return Some(b);
                    }
                }
            }
        }
        None
    }

    /// Whole entry (redirects followed). `NotFound` when missing.
    pub fn get(&self, path: String) -> std::result::Result<Resource, CoreError> {
        let (d, loc) = self.blob_of(&path)?;
        if loc.len() > zim::MAX_GET_BYTES {
            return Err(CoreError::Unsupported(format!("{path} is {} bytes; read it with get_range", loc.len())));
        }
        let data = self.archive.read_blob(&loc, 0, u64::MAX)?;
        Ok(Resource { path: self.archive.api_path(&d), mime: self.archive.mime(&d).to_string(), data })
    }

    /// Part of an entry, for media and big files. Clamped to the entry end (and to 256 MB).
    pub fn get_range(&self, path: String, offset: u64, len: u32) -> std::result::Result<Vec<u8>, CoreError> {
        let (_, loc) = self.blob_of(&path)?;
        self.archive.read_blob(&loc, offset, (len as u64).min(zim::MAX_GET_BYTES))
    }

    /// Size of an entry in bytes (redirects followed).
    pub fn content_length(&self, path: String) -> std::result::Result<u64, CoreError> {
        let (_, loc) = self.blob_of(&path)?;
        Ok(loc.len())
    }

    /// Title suggestions. Uses the attached `.offidx` index (diacritics-insensitive,
    /// ranked), else the title listing of the ZIM (case variants of a plain prefix).
    pub fn suggest(&self, query: String, limit: u32) -> Vec<Suggestion> {
        self.hits(&query, limit as usize)
            .into_iter()
            .map(|h| Suggestion { title: h.title, path: h.path, snippet: h.snippet })
            .collect()
    }

    /// Path of a random article, if the book has articles.
    pub fn random_article(&self) -> Option<String> {
        let lazy = self.lazy().ok()?;
        search::random_article(&self.archive, lazy.titles.as_ref(), lazy.titles_front_only)
    }

    /// Attach a search index built by `offlinka-cli index`.
    pub fn attach_index_path(&self, path: String) -> std::result::Result<(), CoreError> {
        let src = FileSource::open(&path).map_err(|e| CoreError::Io(format!("{path}: {e}")))?;
        self.attach(SearchIndex::open(src)?)
    }

    /// Attach a search index from a detached fd (owned and closed by the core).
    pub fn attach_index_fd(&self, fd: i32) -> std::result::Result<(), CoreError> {
        let src = FileSource::from_fd(fd).map_err(|e| CoreError::Io(format!("index fd {fd}: {e}")))?;
        self.attach(SearchIndex::open(src)?)
    }

    /// Close the ZIM file (and the attached index) now, e.g. when the drive is unplugged
    /// or the book is removed. The object stays valid and thread-safe: calls running in
    /// other threads finish, and every later call fails with `CoreError::Io` (`info()`
    /// keeps its cached value, `suggest` returns nothing). Prefer this over Kotlin's
    /// `close()`/`destroy()` while other threads may still use the book: after
    /// `destroy()` the generated Kotlin throws `IllegalStateException` before Rust is
    /// reached.
    pub fn close_file(&self) {
        *self.index.write().unwrap_or_else(|e| e.into_inner()) = None;
        self.archive.close();
    }

    /// Load the attached index into memory now. Attaching only reads the header; the
    /// in-memory part (cs maxi 16 MB, en top1m 76 MB) otherwise loads on the first
    /// `suggest`. Call it on a background thread right after attaching. No index: Ok.
    pub fn preload_index(&self) -> std::result::Result<(), CoreError> {
        match self.search_index() {
            Some(idx) => idx.preload(),
            None => Ok(()),
        }
    }
}

/// Free memory on demand (Android `onTrimMemory`, iOS memory warning): drops the
/// decompressed clusters and directory entries cached for all books and unloads the
/// in-memory part of every attached search index (it reloads on the next suggest).
#[uniffi::export]
pub fn trim_memory() {
    zim::trim_caches();
    index::unload_all();
}

/// Budget in bytes of the decompressed-cluster cache shared by all open books
/// (default 48 MB). Takes effect immediately.
#[uniffi::export]
pub fn set_cluster_cache_budget(bytes: u64) {
    zim::set_cluster_cache_budget(bytes.min(usize::MAX as u64) as usize);
}

/// Progress and cancellation for [`build_index`], implemented by the app.
#[uniffi::export(callback_interface)]
pub trait IndexBuildListener: Send + Sync {
    /// Overall fraction done, 0.0..=1.0, increasing (at most every 0.2 % or 250 ms).
    fn on_progress(&self, fraction: f64);
    /// Asked with every progress call; return true to stop. `build_index` then fails
    /// with `CoreError::Cancelled` and leaves no file behind.
    fn is_cancelled(&self) -> bool;
}

struct ListenerObserver(Box<dyn IndexBuildListener>);

impl index::BuildObserver for ListenerObserver {
    fn progress(&mut self, fraction: f64) {
        self.0.on_progress(fraction);
    }

    fn cancelled(&mut self) -> bool {
        self.0.is_cancelled()
    }
}

/// Build the search index of a book on the device and write it to `out_path` (app
/// storage, never the drive). Byte-identical to `offlinka-cli index`. Blocking: run it
/// on a background thread. Reads the ZIM sequentially (directory entries, then one zstd
/// block per article cluster). Writes `<out_path>.part` and renames it when complete;
/// on error or cancel nothing is left behind. Does not attach the index: call
/// `attach_index_path(out_path)` afterwards.
#[uniffi::export]
pub fn build_index(
    book: Arc<Book>,
    out_path: String,
    listener: Box<dyn IndexBuildListener>,
) -> std::result::Result<(), CoreError> {
    book.check_open()?;
    let mut obs = ListenerObserver(listener);
    write_index_file(&book.archive, &out_path, &mut obs).map(|_| ())
}

/// Build an index into `out_path` via `<out_path>.part` (shared by the API and the CLI).
pub fn write_index_file(
    archive: &Archive,
    out_path: &str,
    obs: &mut dyn index::BuildObserver,
) -> Result<index::BuildStats> {
    let tmp = format!("{out_path}.part");
    let run = |obs: &mut dyn index::BuildObserver| -> Result<index::BuildStats> {
        let io = |e: std::io::Error| CoreError::Io(format!("{tmp}: {e}"));
        let f = std::fs::File::create(&tmp).map_err(io)?;
        let mut w = std::io::BufWriter::with_capacity(1 << 20, f);
        let st = index::build(archive, &mut w, obs)?;
        let f = w.into_inner().map_err(|e| io(e.into_error()))?;
        f.sync_all().map_err(io)?;
        drop(f);
        std::fs::rename(&tmp, out_path).map_err(io)?;
        Ok(st)
    };
    let r = run(obs);
    if r.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    r
}

/// Score handicap of books other than the Czech Wikipedia in `Library::suggest_all`.
const OTHER_BOOK_COST: f32 = 2.0;

/// A set of books searched together.
#[derive(uniffi::Object)]
pub struct Library {
    books: Mutex<Vec<Arc<Book>>>,
}

impl Library {
    fn priority(info: &BookInfo) -> u32 {
        let wiki = info.id.starts_with("wikipedia") || info.name.starts_with("wikipedia");
        let lang = info.language.split(',').next().unwrap_or("").trim();
        match (wiki, lang) {
            (true, "ces" | "cs" | "cze") => 0,
            _ => 1,
        }
    }
}

#[uniffi::export]
impl Library {
    #[uniffi::constructor]
    pub fn new() -> Arc<Library> {
        Arc::new(Library { books: Mutex::new(Vec::new()) })
    }

    /// Add a book. A book with the same id replaces the one already added only when it
    /// has more articles (so the maxi edition wins over the mini one).
    pub fn add(&self, book: Arc<Book>) {
        let info = book.info_or_default();
        let mut books = self.books.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(pos) = books.iter().position(|b| b.info_or_default().id == info.id) {
            if info.article_count > books[pos].info_or_default().article_count {
                books[pos] = book;
            }
        } else {
            books.push(book);
        }
    }

    pub fn books(&self) -> Vec<Arc<Book>> {
        self.books.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Suggestions from every book, merged by score (match tier + popularity, see
    /// `index.rs`); books other than the Czech Wikipedia get a small handicap so it comes
    /// first among equals, then the order books were added, then rank inside the book.
    pub fn suggest_all(&self, query: String, limit: u32) -> Vec<LibrarySuggestion> {
        let books = self.books();
        let infos: Vec<BookInfo> = books.iter().map(|b| b.info_or_default()).collect();
        let mut order: Vec<usize> = (0..books.len()).collect();
        order.sort_by_key(|&i| (Self::priority(&infos[i]), i));
        let limit = limit as usize;
        let qn = normalize::normalize(&query);
        // Ranked raw hits of every book: index records are only read for the hits that
        // can still make it into the merged list.
        enum Item {
            Rec(index::IndexHit),
            Ready(search::Hit),
        }
        let mut all = Vec::new();
        let indexes: Vec<Option<Arc<SearchIndex>>> = books.iter().map(|b| b.search_index()).collect();
        for (rank, &i) in order.iter().enumerate() {
            let bias = if Self::priority(&infos[i]) == 0 { 0.0 } else { OTHER_BOOK_COST };
            match &indexes[i] {
                Some(idx) if !qn.is_empty() && limit > 0 => {
                    for (pos, h) in idx.search(&qn, limit).unwrap_or_default().into_iter().enumerate() {
                        all.push((h.score + bias, rank, pos, i, Item::Rec(h)));
                    }
                }
                Some(_) => {}
                None => {
                    for (pos, c) in books[i].candidates(&query, limit).into_iter().enumerate() {
                        if let search::Cand::Ready(h) = c {
                            all.push((h.score + bias, rank, pos, i, Item::Ready(h)));
                        }
                    }
                }
            }
        }
        all.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(&b.2)));
        let mut seen = std::collections::HashSet::new();
        let mut out = Vec::with_capacity(limit);
        for (_, _, _, i, item) in all {
            if out.len() >= limit {
                break;
            }
            let hit = match item {
                Item::Ready(h) => Some(h),
                Item::Rec(h) => {
                    let Some(Ok((display, target))) = indexes[i].as_ref().map(|x| x.record(h.id)) else { continue };
                    if !seen.insert((i, target)) {
                        continue;
                    }
                    let c = search::Cand::Rec { score: h.score, display, target };
                    search::resolve(&books[i].archive, c).ok()
                }
            };
            if let Some(h) = hit {
                out.push(LibrarySuggestion { book_id: infos[i].id.clone(), title: h.title, path: h.path });
            }
        }
        out
    }
}
