//! Random-access byte sources: a file (path or raw fd, read with `pread`) or memory.

use std::fs::File;
use std::io;

/// Positional reads; must be safe to call from several threads at once.
pub trait ReadAt: Send + Sync {
    /// Fill `buf` completely with the bytes at `offset`.
    /// Fails with `UnexpectedEof` when the source ends first.
    fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> io::Result<()>;
    /// Total size in bytes.
    fn size(&self) -> u64;
}

/// A file read with `pread` (no shared cursor, so no lock).
pub struct FileSource {
    file: File,
    size: u64,
}

impl FileSource {
    pub fn open(path: &str) -> io::Result<Self> {
        Self::from_file(File::open(path)?)
    }

    /// Take ownership of a raw file descriptor (e.g. Android `ParcelFileDescriptor.detachFd()`).
    /// The fd is closed when the source is dropped, also when this call fails.
    #[cfg(unix)]
    pub fn from_fd(fd: i32) -> io::Result<Self> {
        use std::os::unix::io::FromRawFd;
        if fd < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("invalid fd {fd}")));
        }
        // SAFETY: the caller hands us a detached fd it no longer uses; we own and close it.
        let file = unsafe { File::from_raw_fd(fd) };
        Self::from_file(file)
    }

    #[cfg(not(unix))]
    pub fn from_fd(fd: i32) -> io::Result<Self> {
        Err(io::Error::new(io::ErrorKind::Unsupported, format!("raw fd {fd} only on unix")))
    }

    pub fn from_file(file: File) -> io::Result<Self> {
        let size = file.metadata()?.len();
        Ok(FileSource { file, size })
    }

    pub fn file(&self) -> &File {
        &self.file
    }
}

impl ReadAt for FileSource {
    #[cfg(unix)]
    fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        use std::os::unix::fs::FileExt;
        self.file.read_exact_at(buf, offset)
    }

    #[cfg(windows)]
    fn read_exact_at(&self, mut buf: &mut [u8], mut offset: u64) -> io::Result<()> {
        use std::os::windows::fs::FileExt;
        while !buf.is_empty() {
            match self.file.seek_read(buf, offset) {
                Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
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
        self.size
    }
}

/// An in-memory source (tests, small files).
pub struct MemSource(pub Vec<u8>);

impl ReadAt for MemSource {
    fn read_exact_at(&self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        let start = usize::try_from(offset).map_err(|_| io::Error::from(io::ErrorKind::UnexpectedEof))?;
        let end = start.checked_add(buf.len()).ok_or(io::ErrorKind::UnexpectedEof)?;
        let src = self.0.get(start..end).ok_or(io::ErrorKind::UnexpectedEof)?;
        buf.copy_from_slice(src);
        Ok(())
    }

    fn size(&self) -> u64 {
        self.0.len() as u64
    }
}
