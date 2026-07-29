use crate::errors::{Error, ErrorLoc};
use crate::{err, generic, l};
use digest_io::IoWrapper;
use sha2::{Digest, Sha256};
use std::ffi::OsString;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::thread::JoinHandle;
use zip::ZipArchive;
use zip::read::ZipFile;
use zip::result::ZipError;

pub fn copy_owned<S: ToString>(slice: &[S]) -> Vec<String> {
    let mut res = Vec::with_capacity(slice.len());
    for item in slice {
        res.push(item.to_string());
    }
    res
}

pub fn parse_hex(s: &str) -> Result<[u8; 32], Error> {
    let decoded = l!(hex::decode(s))?;
    if decoded.len() != 32 {
        return generic!("Invalid hex string (not 32 bytes long): {s}");
    }
    let mut res = [0u8; 32];
    res.copy_from_slice(&decoded);
    Ok(res)
}

pub fn parse_hex_named(name: &str, s: &str) -> Result<[u8; 32], Error> {
    err! {
        parse_hex(s)
        => format!("Failed to parse hex string '{name}': {s}")
    }
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
    entry: &mut ZipFile<File>,
    internal_path: &str,
    destination: &Path,
) -> Result<(), Error> {
    if entry.is_dir() {
        create_directory(destination).loc(l!())?;
        return Ok(());
    }

    if let Some(parent) = destination.parent()
        && !parent.exists()
    {
        create_directory(parent).loc(l!())?;
    }

    let mut out_file = create_file(destination).loc(l!())?;
    err! {
        std::io::copy(entry, &mut out_file)
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

pub struct ComposingIterator<I> {
    base: Box<dyn Iterator<Item = I>>,
    layer: Option<Box<dyn Iterator<Item = I>>>,
}

impl<T> ComposingIterator<T> {
    pub fn new(base: Box<dyn Iterator<Item = T>>) -> Self {
        Self { base, layer: None }
    }

    pub fn push_layer(&mut self, layer: Box<dyn Iterator<Item = T>>) {
        self.layer = Some(layer);
    }

    pub fn is_nested(&self) -> bool {
        self.layer.is_some()
    }
}

impl<I> Iterator for ComposingIterator<I> {
    type Item = I;

    fn next(&mut self) -> Option<Self::Item> {
        if let Some(layer) = &mut self.layer {
            match layer.next() {
                Some(n) => return Some(n),
                None => self.layer = None,
            }
        }
        self.base.next()
    }
}

// File operations

pub fn create_directory(path: &Path) -> Result<(), Error> {
    err! {
        std::fs::create_dir_all(path)
        => format!("Failed to create directory: {}", path.display())
    }
}

pub fn write_file(path: &Path, data: &[u8]) -> Result<(), Error> {
    err! {
        std::fs::write(path, data)
        => format!("Failed to write file: {}", path.display())
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

pub fn open_zip(path: &Path) -> Result<ZipArchive<File>, Error> {
    err! {
        ZipArchive::new(open_file(path).loc(l!())?)
        => format!("Failed to open zip: {}", path.display())
    }
}

pub fn find_zip_entry<'a>(
    archive: &'a mut ZipArchive<File>,
    name: &str,
) -> Result<Option<ZipFile<'a, File>>, Error> {
    let entry = archive.by_name(name);
    err! {
        match entry {
            Ok(entry) => Ok::<Option<ZipFile<'_, File>>, Error>(Some(entry)),
            Err(ZipError::FileNotFound) => Ok(None),
            Err(e) => return l!(Err(Error::from(e))),
        }
        => format!("Failed to find entry in zip: {}", name)
    }
}

pub fn require_zip_entry<'a>(
    archive: &'a mut ZipArchive<File>,
    name: &str,
) -> Result<ZipFile<'a, File>, Error> {
    match find_zip_entry(archive, name)? {
        Some(entry) => Ok(entry),
        None => generic!("Failed to find entry in zip: {name}"),
    }
}

pub fn read_zip_entry(entry: &mut ZipFile<File>) -> Result<Vec<u8>, Error> {
    let mut data = Vec::new();
    err! {
        entry.read_to_end(&mut data)
        => format!("Failed to read entry: {}", entry.name())
    }?;
    Ok(data)
}

pub fn read_zip_entry_text(entry: &mut ZipFile<File>) -> Result<String, Error> {
    let data = l!(read_zip_entry(entry))?;
    err! {
        String::from_utf8(data)
        => format!("Invalid UTF-8 in {}", entry.name())
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
