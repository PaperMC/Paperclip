use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;
use zip::ZipArchive;
use zip::result::ZipError;

// Paperclip needs the following files from the Paperclip jar to execute (all in META-INF/):
//   1. patches.list
//   2. versions.list
//   3. libraries.list
//   4. download-context
//   5. main-class
//   6. versions/ (directory)
//   7. libraries/ (directory)
// This script looks for a `./paperclip.jar` file in the project and extracts those pieces from it if present.
// The result is an equivalent Paperclip executable, but as a native binary rather than a jar file.
// If there is no `./paperclip.jar` file available, this will generate empty stubs so the code will still resolve
// properly in an editor, but an error will also be inserted to prevent the project from successfully compiling

fn main() {
    println!("cargo::rerun-if-changed=$CARGO_MANIFEST_DIR/paperclip.jar");

    configure_host();

    let out_dir = std::env::var_os("OUT_DIR").unwrap();
    let out_dir = Path::new(&out_dir);

    let manifest_dir = std::env::var_os("CARGO_MANIFEST_DIR").unwrap();
    let manifest_dir = Path::new(&manifest_dir);
    let jar = manifest_dir.join("paperclip.jar");
    if !jar.is_file() {
        println!("cargo::warning=paperclip.jar not found");
        patches::write_empty_patches(&out_dir);
        files::write_empty_entries(&out_dir, "versions");
        files::write_empty_entries(&out_dir, "libraries");
        config::write_empty_config(&out_dir);
        extract::create_dir(&out_dir, "versions");
        extract::create_dir(&out_dir, "libraries");
        write_error(&out_dir);
        return;
    }

    let jar = match File::open(jar) {
        Ok(file) => file,
        Err(e) => {
            println!("cargo::error=Error opening paperclip.jar: {e}");
            return;
        }
    };
    let mut jar = match ZipArchive::new(jar) {
        Ok(j) => j,
        Err(e) => {
            println!("cargo::error=Error reading paperclip.jar: {e}");
            return;
        }
    };

    // Write config data
    if patches::write_patch_file(&out_dir, &mut jar).is_err() {
        return;
    }
    if files::write_entries_file(&out_dir, &mut jar, "versions").is_err() {
        return;
    }
    if files::write_entries_file(&out_dir, &mut jar, "libraries").is_err() {
        return;
    }
    if config::write_config_file(&out_dir, &mut jar).is_err() {
        return;
    }

    // Extract files
    if extract::extract_to_dir(&mut jar, "versions").is_err() {
        return;
    }
    if extract::extract_to_dir(&mut jar, "libraries").is_err() {
        return;
    }

    // If we built from an invalid paperclip.jar, prevent compilation
    let patches_text = std::fs::read_to_string(out_dir.join("patches.rs")).unwrap();
    let config_text = std::fs::read_to_string(out_dir.join("config.rs")).unwrap();
    if patches_text.contains("[crate::classpath::PatchEntry; 0]") && config_text.contains("None") {
        write_error(&out_dir);
    }
}

#[cfg(not(target_os = "linux"))]
fn configure_host() {}

#[cfg(target_os = "linux")]
fn configure_host() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").expect("CARGO_CFG_TARGET_OS not set");

    if target_os == "linux" {
        pkg_config::Config::new()
            .probe("libcurl")
            .expect("System libcurl development headers are required for Linux builds.");
        pkg_config::Config::new()
            .probe("openssl")
            .expect("System openssl development headers are required for Linux builds.");
    }
}

mod patches {
    use super::*;

    pub struct PatchEntry<'a> {
        location: &'a str,
        original_hash: &'a str,
        patch_hash: &'a str,
        output_hash: &'a str,
        original_path: &'a str,
        patch_path: &'a str,
        output_path: &'a str,
    }

    pub fn write_patch_file(out_dir: &Path, jar: &mut ZipArchive<File>) -> Result<(), ()> {
        let mut patch_list = match jar.by_name("META-INF/patches.list") {
            Ok(p) => p,
            Err(ZipError::FileNotFound) => {
                println!("cargo::warning=paperclip.jar does not include a patch list");
                write_empty_patches(&out_dir);
                return Ok(());
            }
            Err(e) => {
                println!("cargo::error=Error reading paperclip.jar: {e}");
                return Err(());
            }
        };

        let mut patch_lines = String::new();
        if let Err(e) = patch_list.read_to_string(&mut patch_lines) {
            println!("cargo::error=Error reading patch list in paperclip.jar: {e}");
            return Err(());
        }

        let mut patches = Vec::<PatchEntry>::new();
        for line in patch_lines.lines() {
            let parts: Vec<&str> = line.split("\t").collect();
            if parts.len() != 7 {
                println!(
                    "cargo::error=Patch list file in paperclip.jar contains an invalid line: {line}"
                );
                return Err(());
            }

            let location = parts[0];
            let original_hash = parts[1];
            let patch_hash = parts[2];
            let output_hash = parts[3];
            let original_path = parts[4];
            let patch_path = parts[5];
            let output_path = parts[6];

            patches.push(PatchEntry {
                location,
                original_hash,
                patch_hash,
                output_hash,
                original_path,
                patch_path,
                output_path,
            });
        }

        write_patches(&out_dir, &patches);

        Ok(())
    }

    #[rustfmt::skip]
    fn write_patches(out_dir: &Path, patches: &[PatchEntry]) {
        let mut file = File::create(out_dir.join("patches.rs")).unwrap();

        write!(file, "pub mod patches {{\n").unwrap();
        write!(file, "{}pub const PATCHES: [crate::classpath::PatchEntry; {}] = [\n", ind(1), patches.len()).unwrap();

        for entry in patches {
            write!(file, "{}crate::classpath::PatchEntry {{\n", ind(2)).unwrap();
            write!(file, "{}location: \"{}\",\n", ind(3), entry.location).unwrap();
            write!(file,"{}original_hash: hex_literal::hex!(\"{}\"),\n",ind(3),entry.original_hash).unwrap();
            write!(file,"{}patch_hash: hex_literal::hex!(\"{}\"),\n",ind(3),entry.patch_hash).unwrap();
            write!(file,"{}output_hash: hex_literal::hex!(\"{}\"),\n",ind(3),entry.output_hash).unwrap();
            write!(file,"{}original_path: \"{}\",\n",ind(3),entry.original_path).unwrap();
            write!(file, "{}patch_path: \"{}\",\n", ind(3), entry.patch_path).unwrap();
            write!(file, "{}output_path: \"{}\",\n", ind(3), entry.output_path).unwrap();
            write!(file, "{}}},\n", ind(2)).unwrap();
        }

        write!(file, "{}];\n", ind(1)).unwrap();
        write!(file, "}}\n").unwrap();
    }

    #[rustfmt::skip]
    pub fn write_empty_patches(out_dir: &Path) {
        let mut file = File::create(out_dir.join("patches.rs")).unwrap();
        write!(file, "pub mod patches {{\n").unwrap();
        write!(file, "{}pub const PATCHES: [crate::classpath::PatchEntry; 0] = [];\n", ind(1)).unwrap();
        write!(file, "}}\n").unwrap();
    }
}

mod files {
    use super::*;

    pub struct FileEntry<'a> {
        hash: &'a str,
        id: &'a str,
        path: &'a str,
    }

    pub fn write_entries_file(
        out_dir: &Path,
        jar: &mut ZipArchive<File>,
        location: &str,
    ) -> Result<(), ()> {
        let mut entry_list = match jar.by_name(format!("META-INF/{location}.list").as_str()) {
            Ok(p) => p,
            Err(ZipError::FileNotFound) => {
                println!("cargo::warning=paperclip.jar does not include a {location} list");
                write_empty_entries(&out_dir, location);
                return Ok(());
            }
            Err(e) => {
                println!("cargo::error=Error reading paperclip.jar: {e}");
                return Err(());
            }
        };

        let mut entry_lines = String::new();
        if let Err(e) = entry_list.read_to_string(&mut entry_lines) {
            println!("cargo::error=Error reading {location} list in paperclip.jar: {e}");
            return Err(());
        }

        let mut patches = Vec::<FileEntry>::new();
        for line in entry_lines.lines() {
            let parts: Vec<&str> = line.split("\t").collect();
            if parts.len() != 3 {
                println!(
                    "cargo::error={location}.list file in paperclip.jar contains an invalid line: {line}"
                );
                return Err(());
            }

            let hash = parts[0];
            let id = parts[1];
            let path = parts[2];

            patches.push(FileEntry { hash, id, path });
        }

        write_entries(&out_dir, &patches, location);

        Ok(())
    }

    #[rustfmt::skip]
    fn write_entries(out_dir: &Path, patches: &[FileEntry], location: &str) {
        let mut file = File::create(out_dir.join(format!("{location}.rs"))).unwrap();

        write!(file, "pub mod {location} {{\n").unwrap();
        write!(file, "{}pub const {}: [crate::classpath::FileEntry; {}] = [\n", ind(1), location.to_uppercase(), patches.len()).unwrap();

        for entry in patches {
            write!(file, "{}crate::classpath::FileEntry {{\n", ind(2)).unwrap();
            write!(file, "{}hash: hex_literal::hex!(\"{}\"),\n", ind(3), entry.hash).unwrap();
            write!(file,"{}id: \"{}\",\n", ind(3),entry.id).unwrap();
            write!(file,"{}path: \"{}\",\n", ind(3),entry.path).unwrap();
            write!(file, "{}}},\n", ind(2)).unwrap();
        }

        write!(file, "{}];\n", ind(1)).unwrap();
        write!(file, "}}\n").unwrap();
    }

    #[rustfmt::skip]
    pub fn write_empty_entries(out_dir: &Path, location: &str) {
        let mut file = File::create(out_dir.join(format!("{location}.rs"))).unwrap();
        write!(file, "pub mod {location} {{\n").unwrap();
        write!(file, "{}pub const {}: [crate::classpath::FileEntry; 0] = [];\n", ind(1), location.to_uppercase()).unwrap();
        write!(file, "}}\n").unwrap();
    }
}

mod config {
    use super::*;

    #[rustfmt::skip]
    pub fn write_config_file(out_dir: &Path, jar: &mut ZipArchive<File>) -> Result<(), ()> {
        let mut context_line = String::new();
        {
            let mut context_entry = match jar.by_name("META-INF/download-context") {
                Ok(e) => e,
                Err(ZipError::FileNotFound) => {
                    println!("cargo::warning=paperclip.jar does not include a download-context file");
                    write_empty_config(&out_dir);
                    return Ok(());
                }
                Err(e) => {
                    println!("cargo::error=Error reading download-context in paperclip.jar: {e}");
                    return Err(());
                }
            };
            context_entry.read_to_string(&mut context_line).unwrap();
        }
        let context_split: Vec<&str> = context_line.split("\t").collect();
        if context_split.len() != 3 {
            println!("cargo::error=download-context file in paperclip.jar is invalid: {context_line}");
            return Err(());
        }

        let mut main_class_line = String::new();
        {
            let mut main_class_entry = match jar.by_name("META-INF/main-class") {
                Ok(e) => e,
                Err(ZipError::FileNotFound) => {
                    println!("cargo::warning=paperclip.jar does not include a main-class file");
                    write_empty_config(&out_dir);
                    return Ok(());
                }
                Err(e) => {
                    println!("cargo::error=Error reading main-class in paperclip.jar: {e}");
                    return Err(());
                }
            };
            main_class_entry
                .read_to_string(&mut main_class_line)
                .unwrap();
        }

        let mut version = String::new(); // TODO replace with paper-version
        let version_json = match jar.by_name("version.json") {
            Ok(mut version_json_entry) => {
                let mut json_text = String::new();
                version_json_entry.read_to_string(&mut json_text).unwrap();
                let value = serde_json::from_str::<serde_json::Value>(&json_text).unwrap();
                version.push_str(value.as_object().unwrap().get("id").unwrap().as_str().unwrap());
                serde_json::to_string(&value).unwrap()
            }
            _ => "".to_string(),
        };

        let mut file = File::create(out_dir.join("config.rs")).unwrap();

        write!(file, "pub mod config {{\n").unwrap();
        write!(file,"{}pub const CONFIG: crate::classpath::Config = crate::classpath::Config {{\n",ind(1)).unwrap();
        write!(file, "{}download_context: Some(crate::classpath::DownloadContext {{\n", ind(2)).unwrap();
        write!(file, "{}hash: hex_literal::hex!(\"{}\"),\n", ind(3), context_split[0]).unwrap();
        write!(file, "{}url: \"{}\",\n", ind(3), context_split[1]).unwrap();
        write!(file, "{}file_name: \"{}\",\n", ind(3), context_split[2]).unwrap();
        write!(file, "{}}}),\n", ind(2)).unwrap();
        write!(file, "{}main_class: \"{}\",\n", ind(2), main_class_line).unwrap();
        write!(file, "{}version: \"{}\",\n", ind(2), version).unwrap();
        write!(file, "{}version_json: r#\"{}\"#,\n", ind(2), version_json).unwrap();
        write!(file, "{}}};\n", ind(1)).unwrap();
        write!(file, "}}\n").unwrap();

        Ok(())
    }

    #[rustfmt::skip]
    pub fn write_empty_config(out_dir: &Path) {
        let mut file = File::create(out_dir.join("config.rs")).unwrap();

        write!(file, "pub mod config {{\n").unwrap();
        write!(file,"{}pub const CONFIG: crate::classpath::Config = crate::classpath::Config {{\n",ind(1)).unwrap();
        write!(file,"{}download_context: None,\n",ind(2)).unwrap();
        write!(file, "{}main_class: \"\",\n", ind(2)).unwrap();
        write!(file, "{}version: \"\",\n", ind(2)).unwrap();
        write!(file, "{}version_json: \"\",\n", ind(2)).unwrap();
        write!(file, "{}}};\n", ind(1)).unwrap();
        write!(file, "}}\n").unwrap();
    }
}

mod extract {
    use super::*;

    pub fn create_dir(out_dir: &Path, target: &str) {
        let target_dir = out_dir.join(target);
        let _ = std::fs::remove_dir_all(&target_dir);
        std::fs::create_dir_all(&target_dir).unwrap();
    }

    pub fn extract_to_dir(jar: &mut ZipArchive<File>, target: &str) -> Result<(), ()> {
        let prefix = format!("META-INF/{target}/");

        let out_dir = std::env::var_os("OUT_DIR").unwrap();
        let target_dir = Path::new(&out_dir).join(target);

        for i in 0..jar.len() {
            let mut entry = match jar.by_index(i) {
                Ok(e) => e,
                Err(e) => {
                    println!("cargo::error=Error extracting files from paperclip.jar: {e}");
                    return Err(());
                }
            };
            let name = entry.name().to_owned();

            if !name.starts_with(&prefix) {
                continue;
            }

            // Strip the prefix to get the relative structure
            let Some(relative_path) = name.strip_prefix(&prefix) else {
                continue;
            };

            // Skip the root directory entry itself
            if relative_path.is_empty() {
                continue;
            }

            let out_path = target_dir.join(relative_path);
            if !out_path.starts_with(&target_dir) {
                // Something went wrong
                println!(
                    "cargo::error=Invalid zip entry path in paperclip.jar: {}",
                    out_path.display()
                );
                return Err(());
            }

            if entry.is_dir() {
                if let Err(e) = std::fs::create_dir_all(&out_path) {
                    println!("cargo::error=Error creating directory: {e}");
                    return Err(());
                }
            } else {
                if let Some(parent) = out_path.parent() {
                    if let Err(e) = std::fs::create_dir_all(parent) {
                        println!("cargo::error=Error creating directory: {e}");
                        return Err(());
                    }
                }
                let mut out_file = match File::create(&out_path) {
                    Ok(f) => f,
                    Err(e) => {
                        println!("cargo::error=Error creating file: {e}");
                        return Err(());
                    }
                };
                if let Err(e) = std::io::copy(&mut entry, &mut out_file) {
                    println!("cargo::error=Error copying file: {e}");
                    return Err(());
                }
            }
        }

        Ok(())
    }
}

fn write_error(out_dir: &Path) {
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(out_dir.join("config.rs"))
        .unwrap();
    write!(file, "\nconst _: () = compile_error!(\"Paperclip cannot be built without a valid ./paperclip.jar present.\");\n").unwrap();
}

fn ind(c: usize) -> String {
    "    ".repeat(c)
}
