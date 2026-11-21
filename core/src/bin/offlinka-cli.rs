//! offlinka-cli: manual checks, the drive build's index step, and benchmarks.

use std::io::Write;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant};

use offlinka_core::index;
use offlinka_core::reader::FileSource;
use offlinka_core::zim::{Archive, Kind};
use offlinka_core::{Book, Library};

const USAGE: &str = "offlinka-cli <command> ...
  info    <zim> [--index F]                 book info and metadata
  ls      <zim> [--ns C] [--prefix P] [--limit N]   entries in path order
  get     <zim> <path> [-o FILE]            entry content (redirects followed)
  suggest <zim>... <query> [--index F] [--limit N]  suggestions (several zims: library merge)
  index   <zim> -o <FILE.offidx>            build the title search index
  bench   <zim> [--index F] [--queries FILE] keystroke latency of suggest, open + main page time
  bench-lib <zim>... [--index-dir D] [--queries FILE]  keystroke latency of Library.suggest_all
  (every command: --index-dir D looks for <stem>.offidx in D; default: next to the ZIM)
  verify  <zim>                             decompress every cluster, read every entry
  inflate <zim> [--count N]                 cluster decompression speed (N clusters, default 200)";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

struct Opts {
    pos: Vec<String>,
    flags: std::collections::HashMap<String, String>,
}

fn parse(args: &[String]) -> Opts {
    let mut pos = Vec::new();
    let mut flags = std::collections::HashMap::new();
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if (a.starts_with("--") || a == "-o") && i + 1 < args.len() {
            flags.insert(a.trim_start_matches('-').to_string(), args[i + 1].clone());
            i += 2;
        } else {
            pos.push(a.clone());
            i += 1;
        }
    }
    Opts { pos, flags }
}

type R = Result<(), Box<dyn std::error::Error>>;

/// Open a book and attach its index: `--index F` (or `none`), else `<stem>.offidx` from
/// `--index-dir D` or next to the ZIM.
fn open_book(path: &str, o: &Opts) -> Result<Arc<Book>, Box<dyn std::error::Error>> {
    let b = Book::open_path(path.to_string())?;
    let stem = path.strip_suffix(".zim").unwrap_or(path);
    let auto = match o.flags.get("index-dir") {
        Some(d) => format!("{d}/{}.offidx", stem.rsplit('/').next().unwrap_or(stem)),
        None => format!("{stem}.offidx"),
    };
    match o.flags.get("index") {
        Some(i) if i == "none" => {}
        Some(i) => b.attach_index_path(i.clone())?,
        None => {
            if std::path::Path::new(&auto).exists() {
                b.attach_index_path(auto)?;
            }
        }
    }
    Ok(b)
}

fn read_queries(o: &Opts) -> Result<Vec<String>, Box<dyn std::error::Error>> {
    Ok(match o.flags.get("queries") {
        Some(f) => std::fs::read_to_string(f)?.lines().filter(|l| !l.trim().is_empty()).map(String::from).collect(),
        None => [
            "cesky krumlov",
            "praha",
            "Karel IV",
            "druha svetova valka",
            "brno",
            "mozart",
            "tatry",
            "voda",
            "a",
            "s",
            "seznam",
            "jan",
            "kosice",
            "bratislava",
            "einstein",
            "chemie",
            "1",
            "ostrava",
            "pes",
            "fotosynteza",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect(),
    })
}

/// Time `f` on every prefix of every query; print p50/p95/p99/max.
fn keystrokes(queries: &[String], mut f: impl FnMut(&str)) {
    let mut times: Vec<(Duration, String)> = Vec::new();
    for q in queries {
        let chars: Vec<char> = q.chars().collect();
        for n in 1..=chars.len() {
            let p: String = chars[..n].iter().collect();
            let t = Instant::now();
            f(&p);
            times.push((t.elapsed(), p));
        }
    }
    times.sort();
    let pct = |p: f64| times[((times.len() - 1) as f64 * p) as usize].0;
    println!(
        "{} keystrokes: p50 {:?}  p95 {:?}  p99 {:?}  max {:?} ({})",
        times.len(),
        pct(0.5),
        pct(0.95),
        pct(0.99),
        times.last().unwrap().0,
        times.last().unwrap().1
    );
}

fn run(args: &[String]) -> R {
    let Some(cmd) = args.first() else {
        println!("{USAGE}");
        return Ok(());
    };
    let o = parse(&args[1..]);
    let need = |n: usize| -> Result<(), Box<dyn std::error::Error>> {
        if o.pos.len() < n {
            Err(format!("missing arguments\n{USAGE}").into())
        } else {
            Ok(())
        }
    };
    match cmd.as_str() {
        "info" => {
            need(1)?;
            let t = Instant::now();
            let b = open_book(&o.pos[0], &o)?;
            let open = t.elapsed();
            let i = b.info();
            let a = b.archive();
            println!("{i:#?}");
            println!("open: {open:?}");
            println!(
                "zim v{}.{} uuid {} entries {} clusters {} mimes {}",
                a.header.major,
                a.header.minor,
                a.header.uuid_hex(),
                a.header.entry_count,
                a.header.cluster_count,
                a.mimes.len()
            );
            for n in b.metadata_names() {
                let v = b.metadata(&n).unwrap_or_default();
                if n.starts_with("Illustration") {
                    println!("M/{n}: {} bytes", v.len());
                } else {
                    let s = String::from_utf8_lossy(&v);
                    let s: String = s.chars().take(120).collect();
                    println!("M/{n}: {s}");
                }
            }
            if let Some(ix) = b.search_index() {
                println!(
                    "index: {} bytes ({} in memory), {} records, {} title keys, {} word keys, {} heavy prefixes",
                    ix.byte_len,
                    ix.memory_bytes(),
                    ix.record_count,
                    ix.title_keys(),
                    ix.word_keys(),
                    ix.heavy_prefixes()
                );
            }
        }
        "ls" => {
            need(1)?;
            let a = Archive::open(Box::new(FileSource::open(&o.pos[0])?))?;
            let ns = o.flags.get("ns").and_then(|s| s.bytes().next());
            let prefix = o.flags.get("prefix").cloned().unwrap_or_default();
            let limit: usize = o.flags.get("limit").map(|s| s.parse()).transpose()?.unwrap_or(50);
            let start = match ns {
                Some(n) => a.lower_bound(n, &prefix)?,
                None => 0,
            };
            let mut out = std::io::stdout().lock();
            for i in start..a.entry_count() {
                if i - start >= limit as u32 {
                    break;
                }
                let d = a.dirent(i)?;
                if ns.is_some_and(|n| n != d.ns) || !d.path.starts_with(&prefix) {
                    break;
                }
                let what = match d.kind {
                    Kind::Content { cluster, blob } => format!("{} c{cluster}/b{blob}", a.mime(&d)),
                    Kind::Redirect { target } => {
                        format!("-> #{target} {}", a.dirent(target).map(|t| t.path.clone()).unwrap_or_default())
                    }
                    Kind::Other => "(other)".into(),
                };
                writeln!(out, "#{i}\t{}/{}\t{}\t{}", d.ns as char, d.path, d.title, what)?;
            }
        }
        "get" => {
            need(2)?;
            let b = Book::open_path(o.pos[0].clone())?;
            let r = b.get(o.pos[1].clone())?;
            eprintln!("{} {} ({} bytes)", r.path, r.mime, r.data.len());
            match o.flags.get("o") {
                Some(f) => std::fs::write(f, &r.data)?,
                None => std::io::stdout().write_all(&r.data)?,
            }
        }
        "suggest" => {
            need(2)?;
            let limit: u32 = o.flags.get("limit").map(|s| s.parse()).transpose()?.unwrap_or(10);
            let (zims, q) = o.pos.split_at(o.pos.len() - 1);
            let q = &q[0];
            if zims.len() == 1 {
                let b = open_book(&zims[0], &o)?;
                let t = Instant::now();
                let s = b.suggest(q.clone(), limit);
                let el = t.elapsed();
                for x in &s {
                    match &x.snippet {
                        Some(sn) => println!("{}  ->  {}  [{}]", x.title, sn, x.path),
                        None => println!("{}  [{}]", x.title, x.path),
                    }
                }
                eprintln!("{} suggestions in {el:?} (index: {})", s.len(), b.info().has_index);
            } else {
                let lib = Library::new();
                for z in zims {
                    lib.add(open_book(z, &o)?);
                }
                let t = Instant::now();
                let s = lib.suggest_all(q.clone(), limit);
                let el = t.elapsed();
                for x in &s {
                    println!("{:<28} {}  [{}]", x.book_id, x.title, x.path);
                }
                eprintln!("{} suggestions in {el:?}", s.len());
            }
        }
        "index" => {
            need(1)?;
            let out = o.flags.get("o").ok_or("missing -o <file.offidx>")?;
            let t = Instant::now();
            let a = Archive::open(Box::new(FileSource::open(&o.pos[0])?))?;
            let tmp = format!("{out}.tmp");
            let mut w = std::io::BufWriter::new(std::fs::File::create(&tmp)?);
            let st = index::build(&a, &mut w, &mut |m| eprintln!("[{:>6.1}s] {m}", t.elapsed().as_secs_f64()))?;
            drop(w);
            std::fs::rename(&tmp, out)?;
            println!("{st:#?}");
            println!("built {} in {:.1}s", out, t.elapsed().as_secs_f64());
        }
        "bench" => {
            need(1)?;
            let t = Instant::now();
            let b = open_book(&o.pos[0], &o)?;
            let open = t.elapsed();
            let t = Instant::now();
            let main = b.info().main_path.map(|p| b.get(p).map(|r| r.data.len()));
            let main_t = t.elapsed();
            println!("open {open:?}, main page {main_t:?} ({main:?}), index: {}", b.info().has_index);
            let queries = read_queries(&o)?;
            keystrokes(&queries, |p| {
                std::hint::black_box(b.suggest(p.to_string(), 20));
            });
            for q in queries.iter().take(6) {
                let s = b.suggest(q.clone(), 5);
                println!("  {q:>22} => {}", s.iter().map(|x| x.title.as_str()).collect::<Vec<_>>().join(" | "));
            }
        }
        "bench-lib" => {
            need(1)?;
            let t = Instant::now();
            let lib = Library::new();
            for z in &o.pos {
                lib.add(open_book(z, &o)?);
            }
            let indexed = lib.books().iter().filter(|b| b.info().has_index).count();
            println!("library: {} books ({indexed} indexed), opened in {:?}", lib.books().len(), t.elapsed());
            let queries = read_queries(&o)?;
            keystrokes(&queries, |p| {
                std::hint::black_box(lib.suggest_all(p.to_string(), 20));
            });
            for q in queries.iter().take(4) {
                let s = lib.suggest_all(q.clone(), 6);
                println!(
                    "  {q:>22} => {}",
                    s.iter().map(|x| format!("{} [{}]", x.title, x.book_id)).collect::<Vec<_>>().join(" | ")
                );
            }
        }
        "inflate" => {
            need(1)?;
            let a = Archive::open_with_cache(Box::new(FileSource::open(&o.pos[0])?), 0)?;
            let count: u32 = o.flags.get("count").map(|s| s.parse()).transpose()?.unwrap_or(200);
            let n = a.header.cluster_count;
            let step = (n / count.max(1)).max(1);
            let (mut raw, mut out, mut t_total) = (0u64, 0u64, Duration::ZERO);
            let mut done = 0;
            for c in (0..n).step_by(step as usize) {
                let loc = a.cluster_loc(c)?;
                if loc.compression == offlinka_core::zim::Compression::None {
                    continue;
                }
                let mut buf = vec![0u8; (loc.end - loc.start - 1) as usize];
                a.read_at(&mut buf, loc.start + 1)?; // warm the page cache, not timed
                let t = Instant::now();
                let cl = a.cluster(c, &loc)?;
                t_total += t.elapsed();
                raw += buf.len() as u64;
                out += (0..cl.blob_count()).map(|i| cl.blob(i).map(|b| b.len() as u64).unwrap_or(0)).sum::<u64>();
                done += 1;
            }
            let mb = |b: u64| b as f64 / 1e6;
            println!(
                "{done} clusters: {:.1} MB -> {:.1} MB in {:?}: {:.0} MB/s out, {:.1} ms per cluster (cache off)",
                mb(raw),
                mb(out),
                t_total,
                mb(out) / t_total.as_secs_f64(),
                t_total.as_secs_f64() * 1000.0 / done.max(1) as f64
            );
        }
        "verify" => {
            need(1)?;
            let a = Archive::open(Box::new(FileSource::open(&o.pos[0])?))?;
            let t = Instant::now();
            let mut bytes = 0u64;
            for c in 0..a.header.cluster_count {
                let loc = a.cluster_loc(c)?;
                if loc.compression != offlinka_core::zim::Compression::None {
                    let cl = a.cluster(c, &loc)?;
                    bytes += (0..cl.blob_count()).map(|i| cl.blob(i).map(|b| b.len() as u64).unwrap_or(0)).sum::<u64>();
                }
            }
            let mut redirects = 0;
            for i in 0..a.entry_count() {
                let d = a.dirent(i)?;
                match d.kind {
                    Kind::Content { cluster, blob } => {
                        a.blob_loc(cluster, blob)?;
                    }
                    Kind::Redirect { .. } => {
                        a.resolve(i)?;
                        redirects += 1;
                    }
                    Kind::Other => {}
                }
            }
            println!(
                "ok: {} entries ({redirects} redirects), {} clusters, {bytes} bytes inflated, {:?}",
                a.entry_count(),
                a.header.cluster_count,
                t.elapsed()
            );
        }
        _ => {
            println!("{USAGE}");
            return Err(format!("unknown command {cmd}").into());
        }
    }
    Ok(())
}
