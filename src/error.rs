#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineError {
    DuplicateEventId(String),
    DuplicateCorrection(String),
    DateOrder(String),
    InvalidRatio(String),
    ArithmeticOverflow(String),
    MissingParent(String),
    InvalidInput(String),
}

pub type Result<T> = std::result::Result<T, EngineError>;

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl std::error::Error for EngineError {}
