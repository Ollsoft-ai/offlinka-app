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
#[derive(uniffi::Object)]
pub struct Book {
    archive: Arc<Archive>,
    info: BookInfo,
    meta: HashMap<String, u32>,
    titles: Option<TitleList>,
    /// The title list holds only front articles of the article namespace (v1 listing).
    titles_front_only: bool,
    index: RwLock<Option<Arc<SearchIndex>>>,
}

fn file_stem(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    base.strip_suffix(".zim").unwrap_or(base).to_string()
}

fn sanitize_id(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') { c } else { '_' }).collect()
}

impl Book {
    /// Open from any byte source (used by tests and the CLI).
    pub fn open_source(src: Box<dyn ReadAt>, file_name: &str) -> Result<Arc<Book>> {
        let archive = Arc::new(Archive::open(src)?);
        Self::from_archive(archive, file_name)
    }

    fn from_archive(archive: Arc<Archive>, file_name: &str) -> Result<Arc<Book>> {
        let a = &archive;
        // Metadata: all `M/*` entries are adjacent in path order.
        let mut meta = HashMap::new();
        let m = a.namespace_range(b'M')?;
        for i in m.start..m.end.min(m.start + 4096) {
            let d = a.dirent(i)?;
            meta.insert(d.path.clone(), i);
        }
        let text = |k: &str| -> String {
            meta.get(k)
                .and_then(|&i| a.resolve(i).ok())
                .and_then(|(_, d)| a.content(&d).ok())
                .map(|b| String::from_utf8_lossy(&b).trim().to_string())
                .unwrap_or_default()
        };

        let (titles, front_only) = if a.new_scheme() {
            match a.listing_blob("listing/titleOrdered/v1")? {
                Some(l) => (Some(l), true),
                None => (a.listing_blob("listing/titleOrdered/v0")?.or_else(|| a.header_title_list()), false),
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

        let main_idx = if a.new_scheme() { a.find(b'W', "mainPage")? } else { None }.or_else(|| a.header_main_page());
        let main_path = match main_idx {
            Some(i) => a.resolve(i).ok().map(|(_, d)| a.api_path(&d)),
            None => None,
        };

        let name = text("Name");
        let title = text("Title");
        let stem = file_stem(file_name);
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
        Ok(Arc::new(Book { archive, info, meta, titles, titles_front_only: front_only, index: RwLock::new(None) }))
    }

    pub fn archive(&self) -> &Archive {
        &self.archive
    }

    pub fn metadata(&self, name: &str) -> Option<Vec<u8>> {
        let &i = self.meta.get(name)?;
        let (_, d) = self.archive.resolve(i).ok()?;
        self.archive.content(&d).ok()
    }

    pub fn metadata_names(&self) -> Vec<String> {
        let mut v: Vec<String> = self.meta.keys().cloned().collect();
        v.sort();
        v
    }

    pub fn search_index(&self) -> Option<Arc<SearchIndex>> {
        self.index.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn resolve_path(&self, path: &str) -> Result<(u32, Arc<zim::Dirent>)> {
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
        if idx.uuid != self.archive.header.uuid || idx.entry_count != self.archive.entry_count() {
            return Err(CoreError::InvalidZim("the search index belongs to a different ZIM file".into()));
        }
        *self.index.write().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(idx));
        Ok(())
    }

    /// Ranked hits with their match class (for merging across books).
    pub(crate) fn hits(&self, query: &str, limit: usize) -> Vec<search::Hit> {
        let r = match self.search_index() {
            Some(idx) => search::indexed(&self.archive, &idx, query, limit),
            None => match &self.titles {
                Some(l) => search::fallback(&self.archive, l, self.titles_front_only, query, limit),
                None => Ok(Vec::new()),
            },
        };
        r.unwrap_or_default()
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

    pub fn info(&self) -> BookInfo {
        let mut i = self.info.clone();
        i.has_index = self.search_index().is_some();
        i
    }

    /// The book icon (PNG): `Illustration_<size>x<size>@1`, else the nearest size, else the favicon.
    pub fn illustration(&self, size: u32) -> Option<Vec<u8>> {
        if let Some(b) = self.metadata(&format!("Illustration_{size}x{size}@1")) {
            return Some(b);
        }
        let mut sizes: Vec<(u32, &String)> = self
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
        let data = self.archive.read_blob(&loc, 0, u64::MAX)?;
        Ok(Resource { path: self.archive.api_path(&d), mime: self.archive.mime(&d).to_string(), data })
    }

    /// Part of an entry, for media and big files. Clamped to the entry end.
    pub fn get_range(&self, path: String, offset: u64, len: u32) -> std::result::Result<Vec<u8>, CoreError> {
        let (_, loc) = self.blob_of(&path)?;
        self.archive.read_blob(&loc, offset, len as u64)
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
        search::random_article(&self.archive, self.titles.as_ref(), self.titles_front_only)
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
}

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
        let mut books = self.books.lock().unwrap_or_else(|e| e.into_inner());
        let id = book.info.id.clone();
        if let Some(pos) = books.iter().position(|b| b.info.id == id) {
            if book.info.article_count > books[pos].info.article_count {
                books[pos] = book;
            }
        } else {
            books.push(book);
        }
    }

    pub fn books(&self) -> Vec<Arc<Book>> {
        self.books.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Suggestions from every book, merged: by match class (exact, prefix, word), then
    /// Czech Wikipedia first, then the order books were added, then rank inside the book.
    pub fn suggest_all(&self, query: String, limit: u32) -> Vec<LibrarySuggestion> {
        let books = self.books();
        let mut order: Vec<usize> = (0..books.len()).collect();
        order.sort_by_key(|&i| (Self::priority(&books[i].info), i));
        let mut all = Vec::new();
        for (rank, &i) in order.iter().enumerate() {
            for (pos, h) in books[i].hits(&query, limit as usize).into_iter().enumerate() {
                all.push((h.class, rank, pos, books[i].info.id.clone(), h));
            }
        }
        all.sort_by_key(|a| (a.0, a.1, a.2));
        all.into_iter()
            .take(limit as usize)
            .map(|(_, _, _, book_id, h)| LibrarySuggestion { book_id, title: h.title, path: h.path })
            .collect()
    }
}
