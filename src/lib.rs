//! std-only segmented binary codec for synthetic market quotes.

pub mod crc32;
pub mod decoder;
pub mod directory;
pub mod encoder;
pub mod error;
pub mod header;
pub mod oracle;
pub mod query;
pub mod segment;
pub mod varint;

pub use decoder::Decoder;
pub use encoder::Encoder;
pub use error::{CodecError, Result};
pub use header::FileHeader;
pub use segment::Quote;
