//! Suggestions: through the `.offidx` index, or a fallback over the ZIM title listing.

use std::collections::HashSet;
use std::hash::{BuildHasher, Hasher};

use crate::error::Result;
use crate::index::{SearchIndex, CLASS_EXACT, CLASS_PREFIX};
use crate::normalize::normalize;
use crate::zim::{Archive, Kind, TitleList};

#[derive(Debug, Clone)]
pub struct Hit {
    pub class: u8,
    pub title: String,
    pub path: String,
    pub snippet: Option<String>,
}

/// Indexed search: ranked, diacritics-insensitive, redirects deduped by target.
pub fn indexed(a: &Archive, idx: &SearchIndex, query: &str, limit: usize) -> Result<Vec<Hit>> {
    let q = normalize(query);
    if q.is_empty() || limit == 0 {
        return Ok(Vec::new());
    }
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(limit);
    for (class, id) in idx.search(&q, limit) {
        let Some((display, target)) = idx.record(id) else { continue };
        if !seen.insert(target) {
            continue;
        }
        let d = a.dirent(display)?;
        let t = if display == target { d.clone() } else { a.dirent(target)? };
        let snippet = (display != target && t.title() != d.title()).then(|| t.title().to_string());
        out.push(Hit { class, title: d.title().to_string(), path: a.api_path(&t), snippet });
        if out.len() >= limit {
            break;
        }
    }
    Ok(out)
}

fn upper_first(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().chain(c).collect(),
        None => String::new(),
    }
}

fn title_case(s: &str) -> String {
    s.split(' ').map(upper_first).collect::<Vec<_>>().join(" ")
}

/// Without an index: binary search the title-ordered listing for a few case variants
/// of the query (as typed, first letter capital, lower case, title case, upper case).
pub fn fallback(a: &Archive, list: &TitleList, front_only: bool, query: &str, limit: usize) -> Result<Vec<Hit>> {
    let q = query.trim();
    if q.is_empty() || limit == 0 {
        return Ok(Vec::new());
    }
    let ns = a.article_ns();
    let lower = q.to_lowercase();
    let mut variants = vec![q.to_string(), upper_first(q), lower.clone(), title_case(&lower), q.to_uppercase()];
    let mut uniq = HashSet::new();
    variants.retain(|v| uniq.insert(v.clone()));
    let qn = normalize(q);
    let len = a.title_list_len(list);
    let key = |i: u32| -> Result<(u8, String)> {
        let e = a.title_list_get(list, i)?;
        let d = a.dirent(e)?;
        Ok((d.ns, d.title().to_string()))
    };
    let mut cands: Vec<(u8, bool, usize, String, String, Option<String>)> = Vec::new();
    let mut seen = HashSet::new();
    for v in &variants {
        // lower bound of (ns, v) in title order
        let (mut lo, mut hi) = (0u32, len);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let (dns, t) = key(mid)?;
            let less = if front_only { t.as_bytes() < v.as_bytes() } else { (dns, t.as_bytes()) < (ns, v.as_bytes()) };
            if less {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut taken = 0;
        let mut i = lo;
        while i < len && taken < limit * 2 && i - lo < 200 {
            let e = a.title_list_get(list, i)?;
            i += 1;
            let d = a.dirent(e)?;
            if (!front_only && d.ns != ns) || !d.title().starts_with(v.as_str()) {
                break;
            }
            let Ok((t, td)) = a.resolve(e) else { continue };
            if !seen.insert(t) {
                continue;
            }
            taken += 1;
            let title = d.title().to_string();
            let class = if normalize(&title) == qn { CLASS_EXACT } else { CLASS_PREFIX };
            let snippet = (t != e && td.title() != title).then(|| td.title().to_string());
            cands.push((class, d.is_redirect(), title.chars().count(), title, a.api_path(&td), snippet));
        }
    }
    cands.sort_by(|x, y| (x.0, x.1, x.2, &x.3).cmp(&(y.0, y.1, y.2, &y.3)));
    Ok(cands
        .into_iter()
        .take(limit)
        .map(|(class, _, _, title, path, snippet)| Hit { class, title, path, snippet })
        .collect())
}

fn random_u64() -> u64 {
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    h.finish()
}

/// A random HTML article: from the front-article listing when there is one.
pub fn random_article(a: &Archive, list: Option<&TitleList>, front_only: bool) -> Option<String> {
    let ns = a.article_ns();
    let range = a.namespace_range(ns).ok()?;
    for _ in 0..64 {
        let r = random_u64();
        let e = match list {
            Some(l) if front_only && a.title_list_len(l) > 0 => {
                a.title_list_get(l, (r % a.title_list_len(l) as u64) as u32).ok()?
            }
            _ => {
                if range.is_empty() {
                    return None;
                }
                range.start + (r % (range.end - range.start) as u64) as u32
            }
        };
        let Ok((_, d)) = a.resolve(e) else { continue };
        if d.ns == ns && matches!(d.kind, Kind::Content { .. }) && a.mime(&d).starts_with("text/html") {
            return Some(a.api_path(&d));
        }
    }
    None
}
