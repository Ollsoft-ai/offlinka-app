//! The one error type that crosses the FFI boundary.

/// Errors returned by every fallible core call.
///
/// Flat on purpose: Kotlin and Swift see the variant plus a readable message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
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

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        if e.kind() == std::io::ErrorKind::UnexpectedEof {
            CoreError::InvalidZim(format!("truncated file ({e})"))
        } else {
            CoreError::Io(e.to_string())
        }
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;

pub(crate) fn invalid(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidZim(msg.into())
}
