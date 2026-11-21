//! Fault injection for byte sources: a USB drive pulled out mid-read, a dead fd, short
//! reads, EINTR, a device returning zeros. The wrapper simulates `pread` (which may
//! return fewer bytes than asked, or 0 at "end of file") and fills buffers the way
//! `std::os::unix::fs::FileExt::read_exact_at` does, so the core sees exactly what a
//! real `FileSource` would.
#![allow(dead_code)]

use std::io;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::SeqCst};
use std::sync::{Arc, Mutex};

use offlinka_core::reader::ReadAt;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Fail {
    /// `pread` fails with EIO (the device is gone).
    Eio,
    /// `pread` fails with EBADF (the fd died).
    Ebadf,
    /// `pread` returns 0 inside the file (what a vanished FUSE/SAF file can do).
    Zero,
    /// One short read (half of what was asked), then 0 forever.
    ShortThenZero,
    /// Every `pread` returns at most 7 bytes of correct data: must be invisible.
    ShortOk,
    /// Every other `pread` fails with EINTR: must be invisible.
    Interrupted,
    /// The device returns zeros instead of the data (silent corruption).
    Garbage,
}

impl Fail {
    /// Faults the core must hide completely (same results as a clean read).
    pub fn transparent(self) -> bool {
        matches!(self, Fail::ShortOk | Fail::Interrupted)
    }
    /// Faults that make reads fail: the core must answer `CoreError::Io`.
    pub fn is_io(self) -> bool {
        matches!(self, Fail::Eio | Fail::Ebadf | Fail::Zero | Fail::ShortThenZero)
    }
}

pub const ALL_FAILS: [Fail; 7] =
    [Fail::Eio, Fail::Ebadf, Fail::Zero, Fail::ShortThenZero, Fail::ShortOk, Fail::Interrupted, Fail::Garbage];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum When {
    Never,
    /// From the n-th `pread` call on (0-based).
    Call(u64),
    /// Once n bytes have been delivered (the read crossing n is cut short at n).
    Byte(u64),
    /// While [`Ctl::pull`] is set (another thread pulls the drive mid-operation).
    Flag,
    /// Only the n-th call fails (a transient error; the drive is fine again after).
    Only(u64),
}

/// Shared control of one or more faulty sources.
#[derive(Default)]
pub struct Ctl {
    mode: Mutex<Option<(Fail, When)>>,
    pub calls: AtomicU64,
    pub bytes: AtomicU64,
    pulled: AtomicBool,
    short_done: AtomicBool,
}

impl Ctl {
    pub fn new() -> Arc<Ctl> {
        Arc::new(Ctl::default())
    }

    /// Start counting from zero and fail with `fail` once `when` holds.
    pub fn arm(&self, fail: Fail, when: When) {
        *self.mode.lock().unwrap() = Some((fail, when));
        self.calls.store(0, SeqCst);
        self.bytes.store(0, SeqCst);
        self.short_done.store(false, SeqCst);
        self.pulled.store(false, SeqCst);
    }

    /// No more faults (the drive is back / the error was transient); counters keep going.
    pub fn disarm(&self) {
        *self.mode.lock().unwrap() = None;
        self.pulled.store(false, SeqCst);
    }

    pub fn pull(&self) {
        self.pulled.store(true, SeqCst);
    }

    pub fn reset_counters(&self) {
        self.calls.store(0, SeqCst);
        self.bytes.store(0, SeqCst);
    }
}

pub enum Inner {
    Mem(Arc<Vec<u8>>),
    File(std::fs::File, u64),
}

pub struct Faulty {
    pub inner: Inner,
    pub ctl: Arc<Ctl>,
}

impl Faulty {
    pub fn mem(data: Arc<Vec<u8>>, ctl: Arc<Ctl>) -> Faulty {
        Faulty { inner: Inner::Mem(data), ctl }
    }

    pub fn file(path: &str, ctl: Arc<Ctl>) -> Faulty {
        let f = std::fs::File::open(path).unwrap();
        let n = f.metadata().unwrap().len();
        Faulty { inner: Inner::File(f, n), ctl }
    }

    fn raw_pread(&self, buf: &mut [u8], off: u64) -> io::Result<usize> {
        match &self.inner {
            Inner::Mem(d) => {
                let Ok(start) = usize::try_from(off) else { return Ok(0) };
                if start >= d.len() {
                    return Ok(0);
                }
                let n = buf.len().min(d.len() - start);
                buf[..n].copy_from_slice(&d[start..start + n]);
                Ok(n)
            }
            Inner::File(f, _) => {
                use std::os::unix::fs::FileExt;
                f.read_at(buf, off)
            }
        }
    }

    /// One simulated `pread`.
    fn pread(&self, buf: &mut [u8], off: u64) -> io::Result<usize> {
        let call = self.ctl.calls.fetch_add(1, SeqCst);
        let mode = *self.ctl.mode.lock().unwrap();
        let done = self.ctl.bytes.load(SeqCst);
        let mut want = buf.len();
        let active = match mode {
            None => false,
            Some((_, When::Never)) => false,
            Some((_, When::Call(n))) => call >= n,
            Some((_, When::Byte(n))) => {
                if done >= n {
                    true
                } else {
                    want = want.min((n - done) as usize); // cut short at the boundary
                    false
                }
            }
            Some((_, When::Flag)) => self.ctl.pulled.load(SeqCst),
            Some((_, When::Only(n))) => call == n,
        };
        if active {
            match mode.unwrap().0 {
                Fail::Eio => return Err(io::Error::from_raw_os_error(5)),
                Fail::Ebadf => return Err(io::Error::from_raw_os_error(9)),
                Fail::Zero => return Ok(0),
                Fail::ShortThenZero => {
                    if self.ctl.short_done.swap(true, SeqCst) {
                        return Ok(0);
                    }
                    want = (want / 2).max(1);
                }
                Fail::ShortOk => want = want.min(7),
                Fail::Interrupted => {
                    if call % 2 == 0 {
                        return Err(io::ErrorKind::Interrupted.into());
                    }
                }
                Fail::Garbage => {
                    buf.fill(0);
                    return Ok(buf.len());
                }
            }
        }
        let n = self.raw_pread(&mut buf[..want], off)?;
        self.ctl.bytes.fetch_add(n as u64, SeqCst);
        Ok(n)
    }
}

impl ReadAt for Faulty {
    /// Same loop as std's `read_exact_at`: retry short reads and EINTR, 0 = end of file.
    fn read_exact_at(&self, mut buf: &mut [u8], mut offset: u64) -> io::Result<()> {
        while !buf.is_empty() {
            match self.pread(buf, offset) {
                Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "failed to fill whole buffer")),
                Ok(n) => {
                    buf = &mut buf[n..];
                    offset += n as u64;
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(e) => return Err(e),
            }
        }
        Ok(())
    }

    fn size(&self) -> u64 {
        match &self.inner {
            Inner::Mem(d) => d.len() as u64,
            Inner::File(_, n) => *n,
        }
    }
}

/// A source whose declared size is bigger than its bytes: the rest reads as zeros.
/// Lets tests declare multi-GB files (huge cluster and blob sizes) without the disk.
pub struct Sparse {
    pub data: Vec<u8>,
    pub size: u64,
}

impl ReadAt for Sparse {
    fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        let end = offset.checked_add(buf.len() as u64).ok_or(io::ErrorKind::UnexpectedEof)?;
        if end > self.size {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        buf.fill(0);
        if (offset as usize) < self.data.len() {
            let n = (self.data.len() - offset as usize).min(buf.len());
            buf[..n].copy_from_slice(&self.data[offset as usize..offset as usize + n]);
        }
        Ok(())
    }

    fn size(&self) -> u64 {
        self.size
    }
}
