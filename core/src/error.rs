//! The one error type that crosses the FFI boundary.

/// Errors returned by every fallible core call.
///
/// Flat on purpose: Kotlin and Swift see the variant plus a readable message.
#[derive(Debug, Clone, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    /// Reading the file failed (unplugged drive, bad fd, permission).
    #[error("I/O error: {0}")]
    Io(String),
    /// The file is not a ZIM, is truncated or corrupt, or an index does not belong to the book.
    #[error("invalid ZIM: {0}")]
    InvalidZim(String),
    /// No entry with that path (or no such metadata / index hit).
    #[error("not found: {0}")]
    NotFound(String),
    /// A valid but unsupported feature (zlib/bzip2 clusters, old index version).
    #[error("unsupported: {0}")]
    Unsupported(String),
    /// The caller cancelled a long operation (`build_index`).
    #[error("cancelled: {0}")]
    Cancelled(String),
}

/// A failed read or write of a file is `Io`. Truncated files are caught earlier by the
/// bounds checks against the size seen at open, so an end of file *inside* that size
/// means the file vanished under us (drive pulled, fd revoked): also `Io`.
/// An `io::Error` that wraps a `CoreError` (decoder streams) gives that error back.
impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        match e.downcast::<CoreError>() {
            Ok(c) => c,
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                CoreError::Io(format!("the file ended early, was the drive removed? ({e})"))
            }
            Err(e) => CoreError::Io(e.to_string()),
        }
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;

pub(crate) fn invalid(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidZim(msg.into())
}

/// Out of memory for a buffer the file asks for (a corrupt size, or a phone low on RAM):
/// an error instead of the abort an infallible allocation would cause.
pub(crate) fn no_memory(what: &str, n: u64) -> CoreError {
    CoreError::Unsupported(format!("{what}: not enough memory for {n} bytes"))
}

/// A zero-filled buffer of `n` bytes, allocated fallibly.
pub(crate) fn zeroed(n: u64, what: &str) -> Result<Vec<u8>> {
    let len = usize::try_from(n).map_err(|_| no_memory(what, n))?;
    let mut v = Vec::new();
    v.try_reserve_exact(len).map_err(|_| no_memory(what, n))?;
    v.resize(len, 0);
    Ok(v)
}
