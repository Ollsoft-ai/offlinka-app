//! A tiny ZIM writer for test fixtures: every compression type, normal and extended
//! cluster offsets, redirects, metadata, title listings.
#![allow(dead_code)]

use std::collections::BTreeMap;

pub mod faults;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Comp {
    None,
    Xz,
    Zstd,
    /// Deprecated zlib: written as an info byte + raw bytes (the reader refuses it).
    Zlib,
}

#[derive(Clone, Debug)]
pub enum Body {
    Content { mime: String, data: Vec<u8>, cluster: usize },
    Redirect { ns: u8, path: String },
}

#[derive(Clone, Debug)]
pub struct Entry {
    pub ns: u8,
    pub path: String,
    pub title: String,
    pub body: Body,
    pub front: bool,
}

pub struct ZimBuilder {
    pub entries: Vec<Entry>,
    /// (compression, extended offsets) per cluster id.
    pub clusters: Vec<(Comp, bool)>,
    pub main_page: Option<(u8, String)>,
    pub minor: u16,
    pub uuid: [u8; 16],
    /// Clusters written verbatim (info byte included) instead of from their blobs.
    pub raw_clusters: BTreeMap<usize, Vec<u8>>,
}

impl ZimBuilder {
    pub fn new() -> Self {
        ZimBuilder {
            entries: Vec::new(),
            clusters: Vec::new(),
            main_page: None,
            minor: 1,
            uuid: *b"offlinka-test-01",
            raw_clusters: BTreeMap::new(),
        }
    }

    pub fn cluster(&mut self, comp: Comp, extended: bool) -> usize {
        self.clusters.push((comp, extended));
        self.clusters.len() - 1
    }

    pub fn content(&mut self, ns: u8, path: &str, title: &str, mime: &str, data: &[u8], cluster: usize) -> &mut Self {
        let front = ns == b'C' && mime.starts_with("text/html");
        self.entries.push(Entry {
            ns,
            path: path.into(),
            title: title.into(),
            body: Body::Content { mime: mime.into(), data: data.to_vec(), cluster },
            front,
        });
        self
    }

    pub fn article(&mut self, path: &str, title: &str, html: &str, cluster: usize) -> &mut Self {
        self.content(b'C', path, title, "text/html", html.as_bytes(), cluster)
    }

    pub fn redirect(&mut self, ns: u8, path: &str, title: &str, to_ns: u8, to: &str) -> &mut Self {
        self.entries.push(Entry {
            ns,
            path: path.into(),
            title: title.into(),
            body: Body::Redirect { ns: to_ns, path: to.into() },
            front: false,
        });
        self
    }

    pub fn meta(&mut self, name: &str, value: &[u8], cluster: usize) -> &mut Self {
        let mime = if name.starts_with("Illustration") { "image/png" } else { "text/plain" };
        self.content(b'M', name, "", mime, value, cluster)
    }

    /// Serialise. Adds `X/listing/titleOrdered/v0` and `v1` in an uncompressed cluster.
    pub fn build(&self) -> Vec<u8> {
        let mut entries = self.entries.clone();
        let listing_cluster = self.clusters.len();
        let mut clusters = self.clusters.clone();
        clusters.push((Comp::None, false));
        let n_total = entries.len() + 2;
        let n_front = entries.iter().filter(|e| e.front).count();
        for (name, n) in [("listing/titleOrdered/v0", n_total), ("listing/titleOrdered/v1", n_front)] {
            entries.push(Entry {
                ns: b'X',
                path: name.into(),
                title: String::new(),
                body: Body::Content {
                    mime: "application/octet-stream+zimlisting".into(),
                    data: vec![0; n * 4],
                    cluster: listing_cluster,
                },
                front: false,
            });
        }
        // Path order.
        entries.sort_by(|a, b| (a.ns, a.path.as_bytes()).cmp(&(b.ns, b.path.as_bytes())));
        let index_of: BTreeMap<(u8, String), u32> =
            entries.iter().enumerate().map(|(i, e)| ((e.ns, e.path.clone()), i as u32)).collect();
        let title = |e: &Entry| if e.title.is_empty() { e.path.clone() } else { e.title.clone() };
        let mut by_title: Vec<u32> = (0..entries.len() as u32).collect();
        by_title.sort_by(|&a, &b| {
            let (ea, eb) = (&entries[a as usize], &entries[b as usize]);
            (ea.ns, title(ea)).cmp(&(eb.ns, title(eb)))
        });
        let mut front: Vec<u32> = by_title.iter().copied().filter(|&i| entries[i as usize].front).collect();
        front.sort_by(|&a, &b| title(&entries[a as usize]).cmp(&title(&entries[b as usize])));
        let le = |v: &[u32]| v.iter().flat_map(|x| x.to_le_bytes()).collect::<Vec<u8>>();
        for e in entries.iter_mut() {
            if e.ns == b'X' {
                if let Body::Content { data, .. } = &mut e.body {
                    *data = if e.path.ends_with("v0") { le(&by_title) } else { le(&front) };
                }
            }
        }

        // MIME list.
        let mut mimes: Vec<String> = Vec::new();
        for e in &entries {
            if let Body::Content { mime, .. } = &e.body {
                if !mimes.contains(mime) {
                    mimes.push(mime.clone());
                }
            }
        }
        // Blobs per cluster.
        let mut blobs: Vec<Vec<Vec<u8>>> = vec![Vec::new(); clusters.len()];
        let mut blob_of: Vec<(u32, u32)> = vec![(0, 0); entries.len()];
        for (i, e) in entries.iter().enumerate() {
            if let Body::Content { data, cluster, .. } = &e.body {
                blob_of[i] = (*cluster as u32, blobs[*cluster].len() as u32);
                blobs[*cluster].push(data.clone());
            }
        }
        // Dirents.
        let mut dirents: Vec<Vec<u8>> = Vec::new();
        for (i, e) in entries.iter().enumerate() {
            let mut d = Vec::new();
            match &e.body {
                Body::Content { mime, .. } => {
                    let m = mimes.iter().position(|x| x == mime).unwrap() as u16;
                    d.extend_from_slice(&m.to_le_bytes());
                    d.push(0);
                    d.push(e.ns);
                    d.extend_from_slice(&0u32.to_le_bytes());
                    d.extend_from_slice(&blob_of[i].0.to_le_bytes());
                    d.extend_from_slice(&blob_of[i].1.to_le_bytes());
                }
                Body::Redirect { ns, path } => {
                    d.extend_from_slice(&0xffffu16.to_le_bytes());
                    d.push(0);
                    d.push(e.ns);
                    d.extend_from_slice(&0u32.to_le_bytes());
                    let t = index_of.get(&(*ns, path.clone())).copied().unwrap_or(u32::MAX - 1);
                    d.extend_from_slice(&t.to_le_bytes());
                }
            }
            d.extend_from_slice(e.path.as_bytes());
            d.push(0);
            d.extend_from_slice(e.title.as_bytes());
            d.push(0);
            dirents.push(d);
        }
        // Cluster bytes.
        let mut cluster_bytes: Vec<Vec<u8>> = Vec::new();
        for (c, (comp, ext)) in clusters.iter().enumerate() {
            let osz = if *ext { 8 } else { 4 };
            let bl = &blobs[c];
            let mut body = Vec::new();
            let mut off = ((bl.len() + 1) * osz) as u64;
            let mut offs = vec![off];
            for b in bl {
                off += b.len() as u64;
                offs.push(off);
            }
            for o in offs {
                if *ext {
                    body.extend_from_slice(&o.to_le_bytes());
                } else {
                    body.extend_from_slice(&(o as u32).to_le_bytes());
                }
            }
            for b in bl {
                body.extend_from_slice(b);
            }
            let ext_bit = if *ext { 0x10 } else { 0 };
            if let Some(raw) = self.raw_clusters.get(&c) {
                cluster_bytes.push(raw.clone());
                continue;
            }
            let mut out = Vec::new();
            match comp {
                Comp::None => {
                    out.push(1 | ext_bit);
                    out.extend_from_slice(&body);
                }
                Comp::Zstd => {
                    out.push(5 | ext_bit);
                    out.extend_from_slice(&ruzstd::encoding::compress_to_vec(
                        &body[..],
                        ruzstd::encoding::CompressionLevel::Fastest,
                    ));
                }
                Comp::Zlib => {
                    out.push(2 | ext_bit);
                    out.extend_from_slice(&body);
                }
                Comp::Xz => {
                    out.push(4 | ext_bit);
                    let mut x = Vec::new();
                    lzma_rs::xz_compress(&mut &body[..], &mut x).unwrap();
                    out.extend_from_slice(&x);
                }
            }
            cluster_bytes.push(out);
        }

        // Layout: header | mimes | dirents | path ptrs | cluster ptrs | clusters | checksum
        let mut f = vec![0u8; 80];
        let mime_pos = f.len() as u64;
        for m in &mimes {
            f.extend_from_slice(m.as_bytes());
            f.push(0);
        }
        f.push(0);
        let mut dirent_pos = Vec::new();
        for d in &dirents {
            dirent_pos.push(f.len() as u64);
            f.extend_from_slice(d);
        }
        let path_ptr_pos = f.len() as u64;
        for p in &dirent_pos {
            f.extend_from_slice(&p.to_le_bytes());
        }
        let cluster_ptr_pos = f.len() as u64;
        let mut cpos = cluster_ptr_pos + clusters.len() as u64 * 8;
        for c in &cluster_bytes {
            f.extend_from_slice(&cpos.to_le_bytes());
            cpos += c.len() as u64;
        }
        for c in &cluster_bytes {
            f.extend_from_slice(c);
        }
        let checksum_pos = f.len() as u64;
        f.extend_from_slice(&[0u8; 16]);

        let main = match &self.main_page {
            Some(k) => index_of[k],
            None => u32::MAX,
        };
        let mut h = Vec::new();
        h.extend_from_slice(&72_173_914u32.to_le_bytes());
        h.extend_from_slice(&6u16.to_le_bytes());
        h.extend_from_slice(&self.minor.to_le_bytes());
        h.extend_from_slice(&self.uuid);
        h.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        h.extend_from_slice(&(clusters.len() as u32).to_le_bytes());
        h.extend_from_slice(&path_ptr_pos.to_le_bytes());
        h.extend_from_slice(&u64::MAX.to_le_bytes());
        h.extend_from_slice(&cluster_ptr_pos.to_le_bytes());
        h.extend_from_slice(&mime_pos.to_le_bytes());
        h.extend_from_slice(&main.to_le_bytes());
        h.extend_from_slice(&u32::MAX.to_le_bytes());
        h.extend_from_slice(&checksum_pos.to_le_bytes());
        f[..80].copy_from_slice(&h);
        f
    }
}

/// A sample book: Czech titles, redirects, every cluster kind, metadata, main page.
pub fn sample() -> ZimBuilder {
    let mut z = ZimBuilder::new();
    let c_none = z.cluster(Comp::None, false);
    let c_zstd = z.cluster(Comp::Zstd, false);
    let c_xz = z.cluster(Comp::Xz, false);
    let c_zstd_ext = z.cluster(Comp::Zstd, true);
    let c_none_ext = z.cluster(Comp::None, true);
    let c_meta = z.cluster(Comp::Zstd, false);
    z.article("Český_Krumlov", "Český Krumlov", "<h1>Český Krumlov</h1>", c_zstd)
        .article("Okres_Český_Krumlov", "Okres Český Krumlov", "<h1>Okres</h1>", c_zstd)
        .article("Praha", "Praha", "<h1>Praha</h1>", c_xz)
        .article("Praha_(rozcestník)", "Praha (rozcestník)", "<h1>Rozcestník</h1>", c_xz)
        .article("Pražský_hrad", "Pražský hrad", "<h1>Hrad</h1>", c_zstd_ext)
        .article("Brno", "Brno", "<h1>Brno</h1>", c_none)
        .article("Krumlovský_zámek", "Krumlovský zámek", "<h1>Zámek</h1>", c_none_ext)
        .article("Žluťoučký_kůň", "Žluťoučký kůň", "<p>kůň</p>", c_zstd)
        .article("Hlavní_strana", "Hlavní strana", "<h1>Vítejte</h1>", c_none)
        .redirect(b'C', "Prag", "Prag", b'C', "Praha")
        .redirect(b'C', "Hlavní_město_Praha", "Hlavní město Praha", b'C', "Praha")
        .redirect(b'C', "Krumlov", "Krumlov", b'C', "Český_Krumlov")
        .redirect(b'C', "Cesky_Krumlov", "Cesky Krumlov", b'C', "Český_Krumlov")
        .redirect(b'C', "Chain1", "Chain1", b'C', "Chain2")
        .redirect(b'C', "Chain2", "Chain2", b'C', "Brno")
        .redirect(b'C', "Loop1", "Loop1", b'C', "Loop2")
        .redirect(b'C', "Loop2", "Loop2", b'C', "Loop1")
        .content(b'C', "img/logo.png", "", "image/png", b"\x89PNG fake", c_none)
        .content(
            b'C',
            "video.webm",
            "",
            "video/webm",
            &(0..200_000u32).map(|i| (i % 251) as u8).collect::<Vec<_>>(),
            c_none_ext,
        )
        .content(b'C', "style.css", "", "text/css", b"body{}", c_zstd)
        .meta("Title", "Ukázková kniha".as_bytes(), c_meta)
        .meta("Description", "Testovací ZIM".as_bytes(), c_meta)
        .meta("Language", b"ces", c_meta)
        .meta("Name", b"wikipedia_cs_test", c_meta)
        .meta("Creator", b"Wikipedie", c_meta)
        .meta("Publisher", b"Offlinka", c_meta)
        .meta("Date", b"2026-11-21", c_meta)
        .meta("Counter", b"text/html=9;image/png=1;video/webm=1;text/css=1", c_meta)
        .meta("Illustration_48x48@1", b"\x89PNG48", c_meta)
        .meta("Illustration_96x96@1", b"\x89PNG96", c_meta);
    z.redirect(b'W', "mainPage", "", b'C', "Hlavní_strana");
    z
}

pub fn tmp_path(name: &str) -> String {
    let dir = std::path::Path::new(env!("CARGO_TARGET_TMPDIR"));
    std::fs::create_dir_all(dir).unwrap();
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = N.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    dir.join(format!("{}-{n}-{}", std::process::id(), name)).to_string_lossy().into_owned()
}

/// A zstd frame from raw (type 0) and RLE (type 1) blocks: `(type, bytes, size)`; for
/// RLE `bytes` is the one repeated byte and `size` the regenerated size (<= 128 KiB).
/// Lets tests write zstd bombs of any size in a few bytes. `fcs` = declared content size.
pub fn zstd_frame(blocks: &[(u8, Vec<u8>, u32)], fcs: Option<u64>) -> Vec<u8> {
    let mut f = vec![0x28, 0xb5, 0x2f, 0xfd];
    match fcs {
        None => f.extend_from_slice(&[0x00, 0x38]), // window 128 KiB
        Some(n) => {
            f.extend_from_slice(&[0xc0, 0x38]);
            f.extend_from_slice(&n.to_le_bytes());
        }
    }
    for (i, (ty, bytes, size)) in blocks.iter().enumerate() {
        let last = (i + 1 == blocks.len()) as u32;
        let size = if *ty == 0 { bytes.len() as u32 } else { *size };
        let h = last | (*ty as u32) << 1 | size << 3;
        f.extend_from_slice(&h.to_le_bytes()[..3]);
        f.extend_from_slice(if *ty == 0 { bytes } else { &bytes[..1] });
    }
    f
}

/// RLE blocks regenerating `total` bytes of `byte`.
pub fn rle_blocks(byte: u8, total: u64) -> Vec<(u8, Vec<u8>, u32)> {
    let mut v = Vec::new();
    let mut left = total;
    while left > 0 {
        let n = left.min(128 * 1024) as u32;
        v.push((1, vec![byte], n));
        left -= n as u64;
    }
    v
}
