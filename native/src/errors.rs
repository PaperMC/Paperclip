use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("JNI error: {0:?}")]
    Jni(#[from] jni::errors::Error),
    #[error("JVM error: {0:?}")]
    JVM(#[from] jni::JvmError),
    #[error("JVM start error: {0:?}")]
    StartJvm(#[from] jni::errors::StartJvmError),
    #[error("Failed to find Java installation: {0:?}")]
    JavaLoc(#[from] java_locator::errors::JavaLocatorError),
    #[error("IO error: {0:?}")]
    Io(#[from] std::io::Error),
    #[error("Time error: {0:?}")]
    Time(#[from] std::time::SystemTimeError),
    #[error("Download error: {0:?}")]
    Net(#[from] nyquest::Error),
    #[error("Zip error: {0:?}")]
    Zip(#[from] zip::result::ZipError),
    #[error("Parse error: {0:?}")]
    Int(#[from] std::num::ParseIntError),
    #[error("Serialization error: {0:?}")]
    Postcard(#[from] postcard::Error),
    #[error("{msg}\nCaused by: {cause}")]
    Wrapped {
        msg: String,
        #[source]
        cause: Box<Error>,
    },
    #[error("{0}")]
    Generic(String),
    #[error("{file}:{line}: {error}")]
    Loc {
        file: &'static str,
        line: u32,
        #[source]
        error: Box<Error>,
    },
    #[error("Exiting with code {0}")]
    Exit(i32),
}

pub trait ErrorLoc {
    type Output;

    fn loc(self, loc: Loc) -> Self::Output;
}

impl<T, E: Into<Error>> ErrorLoc for Result<T, E> {
    type Output = Result<T, Error>;

    fn loc(self, loc: Loc) -> Self::Output {
        match self {
            Ok(r) => Ok(r),
            Err(e) => Err(Error::loc(loc.file, loc.line, e)),
        }
    }
}

pub struct Loc {
    pub file: &'static str,
    pub line: u32,
}

#[macro_export]
macro_rules! err {
    ($res:expr => $msg:expr) => {
        match $crate::l!($res) {
            Ok(r) => Ok(r),
            Err(e) => Err($crate::errors::Error::wrap($msg, e)),
        }
    };
}

#[macro_export]
macro_rules! l {
    () => {
        $crate::errors::Loc {
            file: file!(),
            line: line!(),
        }
    };
    ($res:expr) => {
        match $res.map_err($crate::errors::Error::from) {
            Ok(r) => Ok(r),
            Err(Error::Loc {
                file: _,
                line: _,
                error,
            }) => Err(Error::loc(file!(), line!(), error)), // Reset loc info
            Err(e) => Err(Error::loc(file!(), line!(), e)),
        }
    };
}

#[macro_export]
macro_rules! generic {
    ($($arg:tt)*) => {
        $crate::l!(Err($crate::errors::Error::generic(format!($($arg)*))))
    };
}

impl Error {
    pub fn wrap(msg: impl Into<String>, cause: impl Into<Self>) -> Self {
        Error::Wrapped {
            msg: msg.into(),
            cause: Box::from(cause.into()),
        }
    }

    pub fn generic(s: impl Into<String>) -> Self {
        Error::Generic(s.into())
    }

    pub fn loc(file: &'static str, line: u32, cause: impl Into<Self>) -> Self {
        Error::Loc {
            file,
            line,
            error: Box::from(cause.into()),
        }
    }
}

impl From<Box<Error>> for Error {
    fn from(value: Box<Error>) -> Self {
        *value
    }
}
