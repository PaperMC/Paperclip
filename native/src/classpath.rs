use crate::errors::{Error, ErrorLoc};
use crate::util::{
    bytes_matches_hash, create_file, extract_zip_entry, file_matches_hash, find_zip_entry,
    open_zip, parse_hex_named, read_zip_entry, read_zip_entry_text, require_zip_entry,
};
use crate::{err, generic, l};
use qbsdiff::Bspatch;
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

pub fn setup_classpath(
    dir: &Path,
    jar_file: &Path,
) -> Result<(Vec<OsString>, PaperclipMeta), Error> {
    let mut paperclip_jar = open_zip(jar_file)?;
    let meta = extract_metadata(&mut paperclip_jar)?;

    if !meta.patches.is_empty() && meta.download_context.is_none() {
        return generic!(
            "Patches found without a corresponding original-url in {}",
            jar_file.display()
        );
    }

    let base_file = if let Some(download_context) = &meta.download_context {
        Some(err! {
            download_context.download(dir)
            => format!("Failed to download file: {}", download_context.file_name)
        }?)
    } else {
        None
    };

    let mut classpath = extract_and_apply_patches(dir, &mut paperclip_jar, base_file, &meta)?;
    let mut res = Vec::with_capacity(classpath.versions.len() + classpath.libraries.len());
    res.append(&mut classpath.versions);
    res.append(&mut classpath.libraries);
    Ok((res, meta))
}

pub struct PaperclipMeta {
    pub patches: Vec<PatchEntry>,
    pub versions: Vec<FileEntry>,
    pub libraries: Vec<FileEntry>,
    pub download_context: Option<DownloadContext>,
    pub main_class: String,
}

pub fn extract_metadata(jar: &mut ZipArchive<File>) -> Result<PaperclipMeta, Error> {
    let patches_list = {
        let entry = l!(find_zip_entry(jar, "META-INF/patches.list"))?;
        if let Some(mut entry) = entry {
            Some(l!(read_zip_entry_text(&mut entry))?)
        } else {
            None
        }
    };
    let versions_list = {
        let mut entry = require_zip_entry(jar, "META-INF/versions.list")?;
        l!(read_zip_entry_text(&mut entry))?
    };
    let libraries_list = {
        let mut entry = require_zip_entry(jar, "META-INF/libraries.list")?;
        l!(read_zip_entry_text(&mut entry))?
    };
    let download_context = {
        let entry = l!(find_zip_entry(jar, "META-INF/download-context"))?;
        if let Some(mut entry) = entry {
            Some(l!(read_zip_entry_text(&mut entry))?)
        } else {
            None
        }
    };
    let main_class = {
        let mut entry = require_zip_entry(jar, "META-INF/main-class")?;
        l!(read_zip_entry_text(&mut entry))?
    };

    let patches_list = if let Some(patches_list) = patches_list {
        Some(l!(PatchEntry::parse(&patches_list))?)
    } else {
        None
    };

    let versions_list = l!(FileEntry::parse("versions.list", &versions_list))?;
    let libraries_list = l!(FileEntry::parse("libraries.list", &libraries_list))?;

    let download_context = if let Some(download_context) = download_context {
        Some(l!(DownloadContext::parse(&download_context))?)
    } else {
        None
    };

    Ok(PaperclipMeta {
        patches: patches_list.unwrap_or_default(),
        versions: versions_list,
        libraries: libraries_list,
        download_context,
        main_class,
    })
}

fn extract_and_apply_patches<P: AsRef<Path> + Debug>(
    dir: &Path,
    paperlcip_jar: &mut ZipArchive<File>,
    original_jar_file: Option<P>,
    meta: &PaperclipMeta,
) -> Result<Classpath, Error> {
    let mut original_jar = err! {
        try {
            original_jar_file
                .as_ref()
                .map(|j| ZipArchive::new(File::open(j)?) )
                .transpose().loc(l!())?
        } => format!("Failed to open jar: {:?}", original_jar_file)
    }?;

    let mut classpath = Classpath {
        versions: vec![],
        libraries: vec![],
    };
    err! {
        try {
            extract_files(
                meta,
                Location::Versions,
                dir,
                paperlcip_jar,
                &mut original_jar,
                &meta.versions,
                &mut classpath,
            )?
        } => "Failed to extract versions"
    }?;
    err! {
        try {
            extract_files(
                meta,
                Location::Libraries,
                dir,
                paperlcip_jar,
                &mut original_jar,
                &meta.libraries,
                &mut classpath,
            )?
        } => "Failed to extract libraries"
    }?;

    err! { apply_patches(meta, dir, paperlcip_jar, &mut original_jar, &mut classpath) =>
        "Failed to apply patches"
    }?;

    Ok(classpath)
}

fn extract_files(
    meta: &PaperclipMeta,
    location: Location,
    dir: &Path,
    paperclip_jar: &mut ZipArchive<File>,
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
        err! {
            entry.extract(FileEntryArgs {
                meta,
                location,
                target_base_path: &target_path,
                paperclip_jar,
                original_jar,
                jar_base_path: &jar_path,
                classpath
            })
            => format!("Failed to extract files from {}/{}: {}/{}", jar_path, entry.path, target_path.display(), entry.path)
        }?;
    }

    Ok(())
}

fn apply_patches(
    meta: &PaperclipMeta,
    dir: &Path,
    paperclip_jar: &mut ZipArchive<File>,
    original_jar: &mut Option<ZipArchive<File>>,
    classpath: &mut Classpath,
) -> Result<(), Error> {
    if meta.patches.is_empty() {
        return Ok(());
    }
    if original_jar.is_none() {
        return generic!("Patches provided without patch target");
    }
    let original_jar = original_jar.as_mut().unwrap();

    let mut announced = false;
    for patch_entry in &meta.patches {
        err! {
            try {
                announced |= patch_entry.apply_patch(dir, paperclip_jar, original_jar, announced, classpath)?
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
}

#[derive(Debug)]
pub struct FileEntry {
    pub hash: [u8; 32],
    pub id: String,
    pub path: String,
}

struct FileEntryArgs<'a> {
    meta: &'a PaperclipMeta,
    location: Location,
    target_base_path: &'a Path,
    paperclip_jar: &'a mut ZipArchive<File>,
    original_jar: &'a mut Option<ZipArchive<File>>,
    jar_base_path: &'a str,
    classpath: &'a mut Classpath,
}

impl FileEntry {
    fn extract(&self, args: FileEntryArgs) -> Result<(), Error> {
        for patch in &args.meta.patches {
            if patch.location == args.location.name() && patch.output_path == self.path {
                // This file will be created from a patch
                return Ok(());
            }
        }

        let output_path = args.target_base_path.join(&self.path);
        if output_path.exists() && file_matches_hash(&output_path, &self.hash)? {
            args.classpath
                .push(args.location, output_path.into_os_string());
            return Ok(());
        }

        // The file may either be in the paperclip jar, or the original jar
        let entry_path = format!("{}/{}", args.jar_base_path, self.path);

        if let Some(mut entry) = find_zip_entry(args.paperclip_jar, &entry_path)? {
            err! {
                extract_zip_entry(&mut entry, &entry_path, &output_path)
                => format!("Failed to extract file from paperclip jar: {}/{}", args.jar_base_path, self.path)
            }?
        } else {
            if let Some(original_jar) = args.original_jar {
                if let Some(mut entry) = find_zip_entry(original_jar, &entry_path)? {
                    err! {
                        extract_zip_entry(&mut entry, &entry_path, &output_path)
                        => format!("Failed to extract file from original jar: {}/{}", args.jar_base_path, self.path)
                    }?
                } else {
                    return generic!(
                        "{} not found in either paperclip jar or original jar",
                        self.path
                    );
                }
            } else {
                return generic!(
                    "{} not found in paperclip jar, and no original jar provided",
                    self.path
                );
            }
        }

        if !file_matches_hash(&output_path, &self.hash).loc(l!())? {
            return generic!("Hash check failed for extracted file {}", self.path);
        }

        args.classpath
            .push(args.location, output_path.into_os_string());
        Ok(())
    }

    pub fn parse(file: &str, text: &str) -> Result<Vec<Self>, Error> {
        let mut entries = Vec::<FileEntry>::new();
        for line in text.lines() {
            let parts: Vec<&str> = line.split("\t").collect();
            if parts.len() != 3 {
                return generic!("Invalid line in {file}: {line}");
            }

            let hash = l!(parse_hex_named("hash", parts[0]))?;
            let id = parts[1];
            let path = parts[2];

            entries.push(FileEntry {
                hash,
                id: id.to_string(),
                path: path.to_string(),
            });
        }

        Ok(entries)
    }
}

#[derive(Debug)]
pub struct PatchEntry {
    pub location: String,
    pub original_hash: [u8; 32],
    pub patch_hash: [u8; 32],
    pub output_hash: [u8; 32],
    pub original_path: String,
    pub patch_path: String,
    pub output_path: String,
}

impl PatchEntry {
    fn apply_patch(
        &self,
        repo_dir: &Path,
        paperclip_jar: &mut ZipArchive<File>,
        original_jar: &mut ZipArchive<File>,
        announced: bool,
        classpath: &mut Classpath,
    ) -> Result<bool, Error> {
        let location = Location::from_name(&self.location);
        let jar_path = format!("META-INF/{}/{}", self.location, self.original_path);
        let output_file = repo_dir.join(&self.location).join(&self.output_path);

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

        // Get and verify patch data is correct
        let patch_jar_path = format!("META-INF/{}/{}", self.location, self.patch_path);
        let mut patch_entry = match find_zip_entry(paperclip_jar, &patch_jar_path)? {
            Some(entry) => entry,
            None => {
                return generic!("Patch file not found in paperclip jar: {}", &patch_jar_path);
            }
        };
        let patch_data = l!(read_zip_entry(&mut patch_entry))?;

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

    fn parse(text: &str) -> Result<Vec<Self>, Error> {
        let mut patches = Vec::<PatchEntry>::new();
        for line in text.lines() {
            let parts: Vec<&str> = line.split("\t").collect();
            if parts.len() != 7 {
                return generic!("Invalid line in patches.list: {}", line);
            }

            let location = parts[0];
            let original_hash = l!(parse_hex_named("original_hash", parts[1]))?;
            let patch_hash = l!(parse_hex_named("patch_hash", parts[2]))?;
            let output_hash = l!(parse_hex_named("output_hash", parts[3]))?;
            let original_path = parts[4];
            let patch_path = parts[5];
            let output_path = parts[6];

            patches.push(PatchEntry {
                location: location.to_string(),
                original_hash,
                patch_hash,
                output_hash,
                original_path: original_path.to_string(),
                patch_path: patch_path.to_string(),
                output_path: output_path.to_string(),
            });
        }

        Ok(patches)
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
    pub url: String,
    pub file_name: String,
}

impl DownloadContext {
    pub fn download(&self, repo_dir: &Path) -> Result<PathBuf, Error> {
        let target_file = err! { self.create_output_target(repo_dir) =>
            format!("Failed to create directory: {}", repo_dir.display())
        }?;
        if target_file.exists() && file_matches_hash(&target_file, &self.hash)? {
            return Ok(target_file);
        }

        println!("Downloading {}", self.file_name);

        let mut output_file = create_file(&target_file).loc(l!())?;
        err! {
            try {
                let mut downloader = nyquest::blocking::get(self.url.to_string())?.into_read();
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
        let target_path = repo_dir.join(".paper").join("cache").join(&self.file_name);
        if let Some(parent) = target_path.parent() {
            err! { std::fs::create_dir_all(parent) =>
                format!("Failed to create output directory {}", parent.display())
            }?;
        }
        Ok(target_path)
    }

    fn parse(text: &str) -> Result<Self, Error> {
        let parts: Vec<&str> = text.split("\t").collect();
        if parts.len() != 3 {
            return generic!("Invalid download-context: {}", text);
        }

        let hash = l!(parse_hex_named("hash", parts[0]))?;
        let url = parts[1];
        let file_name = parts[2];

        Ok(DownloadContext {
            hash,
            url: url.to_string(),
            file_name: file_name.to_string(),
        })
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
