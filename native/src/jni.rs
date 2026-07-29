use crate::errors::Error;
use crate::{err, generic, l};
use jni::objects::{JPrimitiveArray, TypeArray};
use jni::strings::JNIStr;
use jni::{AttachConfig, AttachGuard, Env, JavaVM, ScopeToken};
use std::path::{Path, PathBuf};
use std::process::Command;

pub fn jni_attach_thread<'scope>(
    jvm: &JavaVM,
    scope: &'scope mut ScopeToken,
    thread_name: &JNIStr,
) -> jni::errors::Result<AttachGuard<'scope>> {
    unsafe {
        jvm.attach_current_thread_guard(
            || {
                AttachConfig::default()
                    .scoped(true)
                    .thread_name(thread_name)
            },
            scope,
        )
    }
}

pub fn as_primitive_array<'a: 'b, 'b, T: TypeArray>(
    env: &mut Env<'a>,
    array: &[T],
) -> Result<JPrimitiveArray<'b, T>, Error> {
    let java_array = l!(JPrimitiveArray::<T>::new(env, array.len()))?;
    l!(java_array.set_region(env, 0, array))?;
    Ok(java_array)
}

#[macro_export]
macro_rules! null {
    ($class:literal, $method:literal) => {
        $crate::generic!("{}::{} returned 'null'", $class, $method)
    };
}

pub fn java_bin(base: &Path) -> PathBuf {
    let mut path = base.to_path_buf();
    path.push("bin");
    path.push("java");
    #[cfg(windows)]
    {
        path.set_extension("exe");
    }
    path
}

pub fn check_java_version(java_home: &str) -> Result<(), Error> {
    let java_home = Path::new(java_home);
    let java = java_bin(java_home);

    let version_text = err! {
        Command::new(&java).arg("-version").output()
        => format!("Failed to execute 'java -version' command ({})", java.display())
    }?
    .stderr;
    let version_text = String::from_utf8_lossy(&version_text);
    let version_line = match version_text.lines().next() {
        Some(l) => l,
        None => {
            return generic!("Failed to parse 'java -version' (no output)");
        }
    };

    let start = match version_line.chars().position(|c| c == '"') {
        Some(i) => i + 1, // skip the quote
        None => {
            return generic!("Failed to parse 'java -version' (no version number found)");
        }
    };

    let version_line = &version_line[start..];
    let version = match version_line.chars().position(|c| c == '"') {
        Some(i) => &version_line[..i],
        None => return generic!("Failed to parse 'java -version' (no version number found)"),
    };

    let version_parts = version.split('.').take(3).collect::<Vec<&str>>();
    let major_version = l!(version_part(&version_parts, 0))?;
    let minor_version = l!(version_part(&version_parts, 1))?;
    let patch_version = l!(version_part(&version_parts, 2))?;

    let version_err = "Unsupported Java version: Java 25.0.4 or higher is required, found: ";

    // We require >25.0.4
    if major_version.is_none() || major_version.unwrap() < 25 {
        let mut err_msg = format!(
            "{}{}",
            version_err,
            major_version
                .map(|v| v.to_string())
                .unwrap_or_else(|| "unknown".to_string())
        );
        if let (Some(minor_version), Some(patch_version)) = (minor_version, patch_version) {
            err_msg.push_str(&format!(".{}.{}", minor_version, patch_version));
        }
        eprintln!("{err_msg}");
        return Err(Error::Exit(1));
    }
    let major_version = major_version.unwrap();
    if minor_version.is_none() || patch_version.is_none() {
        // We can't verify it's 25.0.4, but we tried
        return Ok(());
    }
    let minor_version = minor_version.unwrap();
    let patch_version = patch_version.unwrap();
    if minor_version > 0 || patch_version >= 4 {
        return Ok(());
    }

    eprintln!(
        "{}{}.{}.{}",
        version_err, major_version, minor_version, patch_version
    );
    Err(Error::Exit(1))
}

fn version_part(parts: &[&str], index: usize) -> Result<Option<u16>, Error> {
    if parts.len() <= index {
        return Ok(None);
    }
    let num = match parts[index].parse::<u16>() {
        Ok(v) => v,
        Err(e) => {
            return l!(Err(Error::wrap(
                format!(
                    "Failed to parse 'java -version' (invalid version number): {}",
                    parts[index]
                ),
                e,
            )));
        }
    };
    Ok(Some(num))
}
