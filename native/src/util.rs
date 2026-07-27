use crate::errors::{Error, ErrorLoc};
use crate::{err, generic, l};
use digest_io::IoWrapper;
use sha2::{Digest, Sha256};
use std::ffi::OsString;
use std::fs::File;
use std::path::Path;
use std::thread::JoinHandle;
use zip::ZipArchive;

pub fn copy_owned<S: ToString>(slice: &[S]) -> Vec<String> {
    let mut res = Vec::with_capacity(slice.len());
    for item in slice {
        res.push(item.to_string());
    }
    res
}

pub fn file_hash(path: &Path) -> Result<[u8; 32], Error> {
    err! {
        try {
            let mut file = open_file(path).loc(l!())?;
            let mut digest = IoWrapper(Sha256::new());

            std::io::copy(&mut file, &mut digest).loc(l!())?;
            let mut hash: [u8; 32] = [0u8; 32];
            hash.copy_from_slice(&digest.0.finalize().0);
            Ok(hash)
        } => format!("Failed to hash file {}", path.display())
    }?
}

pub fn file_matches_hash(path: &Path, expected_hash: &[u8]) -> Result<bool, Error> {
    err! {
        try {
            let mut file = open_file(path).loc(l!())?;
            let mut digest = IoWrapper(Sha256::new());

            std::io::copy(&mut file, &mut digest).loc(l!())?;
            let actual_hash = digest.0.finalize();

            Ok(expected_hash == actual_hash.0)
        } => "Failed to hash file"
    }?
}

pub fn bytes_matches_hash(data: &[u8], expected_hash: &[u8]) -> bool {
    let mut digest = Sha256::new();
    digest.update(data);

    let actual_hash = digest.finalize();
    expected_hash == actual_hash.0
}

pub fn extract_zip_entry(
    archive: &mut ZipArchive<File>,
    internal_path: &str,
    destination: &Path,
) -> Result<(), Error> {
    let mut zip_file = err! {
        archive.by_name(internal_path)
        => format!("Failed to find entry in zip: {}", internal_path)
    }?;

    if zip_file.is_dir() {
        create_directory(destination).loc(l!())?;
        return Ok(());
    }

    if let Some(parent) = destination.parent() {
        if !parent.exists() {
            create_directory(parent).loc(l!())?;
        }
    }

    let mut out_file = create_file(destination).loc(l!())?;
    err! {
        std::io::copy(&mut zip_file, &mut out_file)
        => format!("Failed to extract {} from zip to {}", internal_path, destination.display())
    }?;

    Ok(())
}

pub trait JoinHandleRes {
    type Output;
    fn join_res(self) -> Self::Output;
}

impl<T> JoinHandleRes for JoinHandle<Result<T, Error>> {
    type Output = Result<T, Error>;

    fn join_res(self) -> Self::Output {
        self.join().unwrap_or_else(|_| generic!("Thread error"))
    }
}

pub fn create_directory(path: &Path) -> Result<(), Error> {
    err! {
        std::fs::create_dir_all(path)
        => format!("Failed to create directory: {}", path.display())
    }
}

pub fn create_file(path: &Path) -> Result<File, Error> {
    err! {
        File::create(path)
        => format!("Failed to create file: {}", path.display())
    }
}

pub fn open_file(path: &Path) -> Result<File, Error> {
    err! {
        File::open(path)
        => format!("Failed to open file: {}", path.display())
    }
}

#[cfg(not(target_os = "windows"))]
pub fn classpath_sep() -> OsString {
    ":".into()
}
#[cfg(target_os = "windows")]
pub fn classpath_sep() -> OsString {
    ";".into()
}
