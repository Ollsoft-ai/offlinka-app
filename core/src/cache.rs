//! LRU cache bounded by bytes instead of entry count.

use std::hash::Hash;

pub struct ByteLru<K: Hash + Eq, V: Clone> {
    inner: lru::LruCache<K, (V, usize)>,
    bytes: usize,
    budget: usize,
}

impl<K: Hash + Eq, V: Clone> ByteLru<K, V> {
    pub fn new(budget: usize) -> Self {
        ByteLru { inner: lru::LruCache::unbounded(), bytes: 0, budget }
    }

    pub fn get(&mut self, k: &K) -> Option<V> {
        self.inner.get(k).map(|(v, _)| v.clone())
    }

    /// Insert; values larger than the whole budget are not kept.
    pub fn put(&mut self, k: K, v: V, size: usize) {
        if size > self.budget {
            return;
        }
        if let Some((_, old)) = self.inner.put(k, (v, size)) {
            self.bytes -= old;
        }
        self.bytes += size;
        while self.bytes > self.budget {
            match self.inner.pop_lru() {
                Some((_, (_, s))) => self.bytes -= s,
                None => break,
            }
        }
    }

    /// Keep only entries whose key passes `keep`.
    pub fn retain(&mut self, keep: impl Fn(&K) -> bool)
    where
        K: Clone,
    {
        let gone: Vec<K> = self.inner.iter().filter(|(k, _)| !keep(k)).map(|(k, _)| k.clone()).collect();
        for k in gone {
            if let Some((_, s)) = self.inner.pop(&k) {
                self.bytes -= s;
            }
        }
    }

    /// Change the budget, evicting least recently used entries down to it.
    pub fn set_budget(&mut self, budget: usize) {
        self.budget = budget;
        while self.bytes > self.budget {
            match self.inner.pop_lru() {
                Some((_, (_, s))) => self.bytes -= s,
                None => break,
            }
        }
    }

    pub fn clear(&mut self) {
        self.inner.clear();
        self.bytes = 0;
    }

    pub fn bytes(&self) -> usize {
        self.bytes
    }

    /// Bytes held by the entries whose key passes `keep`.
    pub fn bytes_where(&self, keep: impl Fn(&K) -> bool) -> usize {
        self.inner.iter().filter(|(k, _)| keep(k)).map(|(_, (_, s))| *s).sum()
    }

    pub fn len(&self) -> usize {
        self.inner.len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evicts_by_bytes() {
        let mut c = ByteLru::new(100);
        c.put(1, "a", 40);
        c.put(2, "b", 40);
        assert_eq!(c.get(&1), Some("a"));
        c.put(3, "c", 40); // evicts 2 (least recently used)
        assert_eq!(c.get(&2), None);
        assert_eq!(c.get(&1), Some("a"));
        assert_eq!(c.bytes(), 80);
        c.put(4, "huge", 1000);
        assert_eq!(c.get(&4), None);
        c.put(1, "a2", 10);
        assert_eq!(c.bytes(), 50);
        assert_eq!(c.len(), 2);
    }
}
