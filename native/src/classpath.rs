use crate::config::CONFIG;
use crate::errors::{Error, ErrorLoc};
use crate::libraries::LIBRARIES;
use crate::patches::PATCHES;
use crate::util::{bytes_matches_hash, create_file, extract_zip_entry, file_matches_hash};
use crate::versions::VERSIONS;
use crate::{LibrariesAssets, VersionsAssets, err, generic, l};
use qbsdiff::Bspatch;
use std::borrow::Cow;
use std::ffi::OsString;
use std::fmt::Debug;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use zip::ZipArchive;
use zip::result::ZipError;

pub fn repo_dir() -> PathBuf {
    let repo_dir = std::env::var_os("PAPERCLIP_BUNDLER_REPO_DIR");
    let repo_dir = repo_dir.as_ref().map(Path::new).unwrap_or(Path::new(""));
    repo_dir.to_owned()
}

pub fn setup_classpath(dir: &Path) -> Result<Vec<OsString>, Error> {
    // This _should_ be guarded at compile time by the code `build.rs` generates,
    // so it should be impossible for this assertion to fail.
    assert!(
        PATCHES.len() == 0 || CONFIG.download_context.is_some(),
        "patches found without a corresponding original-url"
    );

    let base_file = if let Some(download_context) = CONFIG.download_context {
        Some(err! { download_context.download(&dir) =>
            "Failed to download file"
        }?)
    } else {
        None
    };

    let mut classpath = extract_and_apply_patches(&dir, base_file)?;
    let mut res = Vec::with_capacity(classpath.versions.len() + classpath.libraries.len());
    res.append(&mut classpath.versions);
    res.append(&mut classpath.libraries);
    Ok(res)
}

fn extract_and_apply_patches<P: AsRef<Path> + Debug>(
    dir: &Path,
    original_jar: Option<P>,
) -> Result<Classpath, Error> {
    let mut jar = err! {
        try {
            original_jar
                .as_ref()
                .map(|j| ZipArchive::new(File::open(j)?) )
                .transpose().loc(l!())?
        } => format!("Failed to open jar: {:?}", original_jar)
    }?;

    let mut classpath = Classpath {
        versions: vec![],
        libraries: vec![],
    };
    err! {
        try {
            extract_files(
                Location::Versions,
                dir,
                &mut jar,
                &VERSIONS,
                &mut classpath,
            )?
        } => "Failed to extract versions"
    }?;
    err! {
        try {
            extract_files(
                Location::Libraries,
                dir,
                &mut jar,
                &LIBRARIES,
                &mut classpath,
            )?
        } => "Failed to extract libraries"
    }?;

    err! { apply_patches(dir, &mut jar, &mut classpath) =>
        "Failed to apply patches"
    }?;

    Ok(classpath)
}

fn extract_files(
    location: Location,
    dir: &Path,
    original_jar: &mut Option<ZipArchive<File>>,
    entries: &[FileEntry],
    classpath: &mut Classpath,
) -> Result<(), Error> {
    if entries.is_empty() {
        return Ok(());
    }

    let jar_path = format!("META-INF/{}", location.name());
    let target_path = dir.join(location.name());
    for entry in entries {
        err! { entry.extract(location, &target_path, original_jar, &jar_path, classpath) =>
            format!("Failed to extract files from {}/{}: {}/{}", jar_path, entry.path, target_path.display(), entry.path)
        }?;
    }

    Ok(())
}

fn apply_patches(
    dir: &Path,
    original_jar: &mut Option<ZipArchive<File>>,
    classpath: &mut Classpath,
) -> Result<(), Error> {
    if PATCHES.is_empty() {
        return Ok(());
    }
    if original_jar.is_none() {
        return generic!("Patches provided without patch target");
    }
    let original_jar = original_jar.as_mut().unwrap();

    let mut announced = false;
    for patch_entry in PATCHES {
        err! {
            try {
                announced |= patch_entry.apply_patch(dir, original_jar, announced, classpath)?
            } => format!("Failed to apply patch: {}/{}", patch_entry.location, patch_entry.patch_path)
        }?;
    }

    Ok(())
}

#[derive(Copy, Clone)]
enum Location {
    Versions,
    Libraries,
}

impl Location {
    fn from_name(name: &str) -> Self {
        match name {
            "versions" => Self::Versions,
            "libraries" => Self::Libraries,
            _ => panic!("Invalid location: {name}"),
        }
    }

    fn name(&self) -> &'static str {
        match self {
            Self::Versions => "versions",
            Self::Libraries => "libraries",
        }
    }

    fn resolve(&self, file: &str) -> Option<Cow<'static, [u8]>> {
        match self {
            Self::Versions => VersionsAssets::get(file).map(|f| f.data),
            Self::Libraries => LibrariesAssets::get(file).map(|f| f.data),
        }
    }
}

#[derive(Debug)]
pub struct FileEntry {
    pub hash: [u8; 32],
    pub id: &'static str,
    pub path: &'static str,
}

impl FileEntry {
    fn extract(
        &self,
        location: Location,
        target_base_path: &Path,
        original_jar: &mut Option<ZipArchive<File>>,
        jar_base_path: &str,
        classpath: &mut Classpath,
    ) -> Result<(), Error> {
        for patch in PATCHES {
            if patch.location == location.name() && patch.output_path == self.path {
                // This file will be created from a patch
                return Ok(());
            }
        }

        let output_path = target_base_path.join(self.path);
        if output_path.exists() && file_matches_hash(&output_path, &self.hash)? {
            classpath.push(location, output_path.into_os_string());
            return Ok(());
        }

        match location.resolve(self.path) {
            Some(d) => {
                if let Some(parent) = output_path.parent() {
                    err! {
                        std::fs::create_dir_all(parent)
                        => format!("Failed to create directory: {}", parent.display())
                    }?;
                }
                err! {
                    std::fs::write(&output_path, &d)
                    => format!("Failed to write file: {}", output_path.display())
                }?
            }
            None => {
                // This file is not in our binary, but may be in the original jar
                let original_jar = match original_jar {
                    Some(original_jar) => original_jar,
                    None => {
                        // no original jar was provided (we are not running in patcher mode)
                        // This is an invalid situation
                        return generic!(
                            "{} not found in our binary, and no original jar provided",
                            self.path
                        );
                    }
                };

                let entry_path = format!("{}/{}", jar_base_path, self.path);
                err! {
                    extract_zip_entry(original_jar, entry_path.as_str(), &output_path)
                    => format!("Failed to extract file from original jar: {entry_path}")
                }?;
            }
        }

        if !file_matches_hash(&output_path, &self.hash).loc(l!())? {
            return generic!("Hash check failed for extracted file {}", self.path);
        }

        classpath.push(location, output_path.into_os_string());
        Ok(())
    }
}

#[derive(Debug)]
pub struct PatchEntry {
    pub location: &'static str,
    pub original_hash: [u8; 32],
    pub patch_hash: [u8; 32],
    pub output_hash: [u8; 32],
    pub original_path: &'static str,
    pub patch_path: &'static str,
    pub output_path: &'static str,
}

impl PatchEntry {
    fn apply_patch(
        &self,
        repo_dir: &Path,
        original_jar: &mut ZipArchive<File>,
        announced: bool,
        classpath: &mut Classpath,
    ) -> Result<bool, Error> {
        let location = Location::from_name(self.location);
        let jar_path = format!("META-INF/{}/{}", self.location, self.original_path);
        let output_file = repo_dir.join(&self.location).join(self.output_path);

        // Short-cut if the patch is already applied
        if output_file.exists() && file_matches_hash(&output_file, &self.output_hash).loc(l!())? {
            classpath.push(location, output_file.into_os_string());
            return Ok(false);
        }

        if !announced {
            println!("Applying patches");
        }

        let mut jar_entry = match original_jar.by_name(jar_path.as_str()) {
            Ok(entry) => entry,
            Err(ZipError::FileNotFound) => {
                return generic!("Input file not found in original jar {jar_path}");
            }
            Err(e) => return l!(Err(Error::from(e))),
        };

        let mut input_file_data = Vec::<u8>::new();
        l!(jar_entry.read_to_end(&mut input_file_data))?;

        if !bytes_matches_hash(&input_file_data, &self.original_hash) {
            return generic!("Hash check of input file failed for {jar_path}");
        }

        // Get and verity patch data is correct
        let patch_data = location.resolve(self.patch_path);
        if patch_data.is_none() {
            return generic!("Patch file not found: {}", self.patch_path);
        }
        let patch_data = patch_data.unwrap();

        if !bytes_matches_hash(&patch_data, &self.patch_hash) {
            return generic!("Hash check of patch file failed for {}", self.patch_path);
        }

        err! {
            try {
                if let Some(parent) = output_file.parent() {
                    err! {
                        std::fs::create_dir_all(parent)
                        => format!("Failed to create directory: {}", parent.display())
                    }?;
                }
                let mut target_file = create_file(&output_file).loc(l!())?;

                let patcher = Bspatch::new(&patch_data).loc(l!())?;
                patcher.apply(&input_file_data, &mut target_file).loc(l!())?;
            } => "Error executing bsdiff patch"
        }?;

        if !file_matches_hash(&output_file, &self.output_hash).loc(l!())? {
            return generic!("Patch not applied correctly for {}", self.output_path);
        }

        classpath.push(location, output_file.into_os_string());
        Ok(true)
    }
}

#[derive(Debug)]
pub struct Config {
    pub download_context: Option<DownloadContext>,
    pub main_class: &'static str,
    pub version: &'static str,
    pub version_json: &'static str,
}

#[derive(Debug)]
pub struct DownloadContext {
    pub hash: [u8; 32],
    pub url: &'static str,
    pub file_name: &'static str,
}

impl DownloadContext {
    pub fn download(&self, repo_dir: &Path) -> Result<PathBuf, Error> {
        let target_file = err! { self.create_output_target(&repo_dir) =>
            format!("Failed to create directory: {}", repo_dir.display())
        }?;
        if target_file.exists() && file_matches_hash(&target_file, &self.hash)? {
            return Ok(target_file);
        }

        println!("Downloading {}", self.file_name);

        let mut output_file = create_file(&target_file).loc(l!())?;
        err! {
            try {
                let mut downloader = nyquest::blocking::get(self.url)?.into_read();
                std::io::copy(&mut downloader, &mut output_file).map_err(Into::into)?
            } => format!("Failed to download: {}", self.file_name)
        }?;

        if !file_matches_hash(&target_file, &self.hash).loc(l!())? {
            return generic!(
                "Hash check failed for downloaded file {}",
                target_file.display()
            );
        }

        Ok(target_file)
    }

    fn create_output_target(&self, repo_dir: &Path) -> Result<PathBuf, Error> {
        let target_path = repo_dir.join(".paper").join("cache").join(self.file_name);
        if let Some(parent) = target_path.parent() {
            err! { std::fs::create_dir_all(parent) =>
                format!("Failed to create output directory {}", parent.display())
            }?;
        }
        Ok(target_path)
    }
}

#[derive(Debug)]
struct Classpath {
    versions: Vec<OsString>,
    libraries: Vec<OsString>,
}

impl Classpath {
    fn push(&mut self, location: Location, file_name: OsString) {
        match location {
            Location::Versions => &mut self.versions,
            Location::Libraries => &mut self.libraries,
        }
        .push(file_name);
    }
}
