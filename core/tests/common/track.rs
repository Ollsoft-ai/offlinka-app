//! Test runner pieces for the hardening tests: a global allocator that records the
//! largest single allocation per thread (and refuses absurd ones so a bug cannot eat the
//! shared box), and `guarded`, which runs one operation on a worker thread with a
//! deadline and reports panics, hangs, caught FFI panics and the largest allocation.
//!
//! Include with `#[path = "common/track.rs"] mod track;` (it installs the allocator).
#![allow(dead_code)]

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::time::Duration;

struct Track;

thread_local! {
    static BIG: Cell<usize> = const { Cell::new(0) };
}

/// Allocations above this fail (null), on every thread. An infallible allocation then
/// aborts the test binary: loud, and it never touches gigabytes of the shared box.
pub const REFUSE_ABOVE: usize = 1 << 30;

fn note(n: usize) -> bool {
    let _ = BIG.try_with(|b| {
        if n > b.get() {
            b.set(n)
        }
    });
    n <= REFUSE_ABOVE
}

unsafe impl GlobalAlloc for Track {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        if !note(l.size()) {
            return std::ptr::null_mut();
        }
        unsafe { System.alloc(l) }
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        if !note(l.size()) {
            return std::ptr::null_mut();
        }
        unsafe { System.alloc_zeroed(l) }
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        if !note(n) {
            return std::ptr::null_mut();
        }
        unsafe { System.realloc(p, l, n) }
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        unsafe { System.dealloc(p, l) }
    }
}

#[global_allocator]
static ALLOC: Track = Track;

/// Largest single allocation on this thread since the last reset.
pub fn big() -> usize {
    BIG.with(|b| b.get())
}

pub fn reset_big() {
    BIG.with(|b| b.set(0));
}

pub struct Outcome<T> {
    pub value: Option<T>,
    pub panic: Option<String>,
    /// Panics the core caught at its FFI boundary while running (must stay 0).
    pub caught: u64,
    pub big: usize,
}

impl<T> Outcome<T> {
    /// Fail the test unless the run was clean: no panic (escaped or caught) and no
    /// allocation above `max_alloc`. Returns the value.
    pub fn clean(self, what: &str, max_alloc: usize) -> T {
        if let Some(p) = self.panic {
            panic!("{what}: PANIC: {p}");
        }
        assert_eq!(self.caught, 0, "{what}: the core caught a panic at its FFI boundary (see stderr)");
        assert!(self.big <= max_alloc, "{what}: allocated {} bytes at once (limit {max_alloc})", self.big);
        self.value.unwrap()
    }
}

/// Run `f` on a fresh thread; a run longer than `secs` is a hang and fails the test.
pub fn guarded<T: Send + 'static>(what: &str, secs: u64, f: impl FnOnce() -> T + Send + 'static) -> Outcome<T> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::Builder::new()
        .name(format!("guarded {what}"))
        .spawn(move || {
            reset_big();
            let c0 = offlinka_core::caught_panics();
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
            let caught = offlinka_core::caught_panics() - c0;
            let big = big();
            let o = match r {
                Ok(v) => Outcome { value: Some(v), panic: None, caught, big },
                Err(e) => {
                    let msg = e
                        .downcast_ref::<String>()
                        .cloned()
                        .or_else(|| e.downcast_ref::<&str>().map(|s| s.to_string()))
                        .unwrap_or_else(|| "non-string panic".into());
                    Outcome { value: None, panic: Some(msg), caught, big }
                }
            };
            let _ = tx.send(o);
        })
        .unwrap();
    match rx.recv_timeout(Duration::from_secs(secs)) {
        Ok(o) => o,
        Err(_) => panic!("{what}: HANG (no result after {secs} s)"),
    }
}

/// Error class of a core result, for comparisons.
pub fn class<T>(r: &Result<T, offlinka_core::CoreError>) -> &'static str {
    use offlinka_core::CoreError::*;
    match r {
        Ok(_) => "Ok",
        Err(Io(_)) => "Io",
        Err(InvalidZim(_)) => "InvalidZim",
        Err(NotFound(_)) => "NotFound",
        Err(Unsupported(_)) => "Unsupported",
        Err(Cancelled(_)) => "Cancelled",
    }
}

/// Short stable digest of bytes (FNV-1a) for result comparisons.
pub fn digest(b: &[u8]) -> String {
    let mut h = 0xcbf29ce484222325u64;
    for &x in b {
        h = (h ^ x as u64).wrapping_mul(0x100000001b3);
    }
    format!("{}B#{h:016x}", b.len())
}
