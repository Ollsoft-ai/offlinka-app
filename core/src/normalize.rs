//! Search normalisation: lowercase, NFD with combining marks removed, a few letters
//! that do not decompose folded by hand, apostrophes dropped, every other
//! non-alphanumeric character folded to a single space.
//!
//! Czech and Slovak: á č ď é ě í ĺ ľ ň ó ô ŕ ř š ť ú ů ý ž ä all decompose under NFD,
//! so "Český Krumlov", "cesky krumlov" and "ČESKÝ KRUMLOV" give the same key.

use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;

/// Normalise a title or a query to its search key.
pub fn normalize(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut space = false;
    for c in s.nfd() {
        if is_combining_mark(c) {
            continue;
        }
        if is_apostrophe(c) {
            continue;
        }
        if c.is_alphanumeric() {
            if space && !out.is_empty() {
                out.push(' ');
            }
            space = false;
            for l in c.to_lowercase() {
                if is_combining_mark(l) {
                    continue;
                }
                match fold(l) {
                    Some(f) => out.push_str(f),
                    None => out.push(l),
                }
            }
        } else {
            space = true;
        }
    }
    out
}

fn is_apostrophe(c: char) -> bool {
    matches!(c, '\'' | '\u{2019}' | '\u{2018}' | '\u{02bc}' | '`' | '\u{00b4}')
}

/// Letters NFD leaves alone.
fn fold(c: char) -> Option<&'static str> {
    Some(match c {
        'ł' => "l",
        'ø' => "o",
        'đ' | 'ð' => "d",
        'ß' => "ss",
        'æ' => "ae",
        'œ' => "oe",
        'þ' => "th",
        'ı' => "i",
        'ħ' => "h",
        'ŀ' => "l",
        'ŧ' => "t",
        _ => return None,
    })
}

/// Byte offsets where words start (after the first word), for word-prefix keys.
pub fn word_starts(norm: &str) -> impl Iterator<Item = usize> + '_ {
    norm.char_indices().filter(|&(_, c)| c == ' ').map(|(i, _)| i + 1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn czech_and_slovak() {
        assert_eq!(normalize("Český Krumlov"), "cesky krumlov");
        assert_eq!(normalize("ČESKÝ  KRUMLOV"), "cesky krumlov");
        assert_eq!(normalize("Žluťoučký kůň úpěl ďábelské ódy"), "zlutoucky kun upel dabelske ody");
        assert_eq!(normalize("Ľubľana, Ŕ, ô, ä, ĺ"), "lublana r o a l");
        assert_eq!(normalize("Chrudim"), "chrudim");
    }

    #[test]
    fn punctuation_and_specials() {
        assert_eq!(normalize("Merkur (planeta)"), "merkur planeta");
        assert_eq!(normalize("  J. R. R. Tolkien "), "j r r tolkien");
        assert_eq!(normalize("O'Brien"), "obrien");
        assert_eq!(normalize("Łódź"), "lodz");
        assert_eq!(normalize("Straße"), "strasse");
        assert_eq!(normalize("1. světová válka"), "1 svetova valka");
        assert_eq!(normalize("Rock'n'roll"), "rocknroll");
        assert_eq!(normalize("İstanbul"), "istanbul");
        assert_eq!(normalize("Ωmega"), "ωmega");
        assert_eq!(normalize("!!!"), "");
    }

    #[test]
    fn word_starts_work() {
        let n = normalize("Okres Český Krumlov");
        let w: Vec<&str> = word_starts(&n).map(|i| &n[i..]).collect();
        assert_eq!(w, vec!["cesky krumlov", "krumlov"]);
    }
}
