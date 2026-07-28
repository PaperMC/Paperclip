use crate::args::RecordMode;
use crate::errors::{Error, ErrorLoc};
use crate::jni::{java_bin, jni_attach_thread};
use crate::util::{copy_owned, create_directory, file_hash};
use crate::{ONLY_USE_AOT_FAILED_EXIT_CODE, err, generic, l, null};
use jni::objects::{JObject, JObjectArray, JString};
use jni::{Env, JValue, JavaVM, ScopeToken, jni_sig, jni_str};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime};

const AOT_CACHE_FILE: &str = "paper.aot";
const AOT_CACHE_META: &str = "paper.aot.meta";

#[derive(Serialize, Deserialize, Debug, Eq, PartialEq)]
struct Fingerprint {
    hash: [u8; 32],
    timestamp: u128,
}

#[derive(Serialize, Deserialize, Debug, Eq, PartialEq)]
pub struct AotMeta {
    jvm_ident: String,
    classpath: HashMap<OsString, Fingerprint>,
    jvm_args: Vec<String>,
    app_args: Vec<String>,
    aot_cache_hash: [u8; 32],
}

impl AotMeta {
    pub fn build(
        java_home: &str,
        classpath: &[OsString],
        jvm_args: &[String],
        app_args: &[String],
        aot_cache_file: &Path,
    ) -> Result<Self, Error> {
        let java_home = Path::new(java_home);
        let java = java_bin(java_home);

        let output = err! {
            Command::new(&java).arg("-Xinternalversion").output()
            => format!("Failed to execute 'java -Xinternalversion' command ({})", java.display())
        }?;
        let jvm_ident = String::from_utf8_lossy(&output.stdout).trim().to_string();

        let mut classpath_hashed = HashMap::<OsString, Fingerprint>::new();
        for path in classpath {
            let file = Path::new(path);
            if !file.exists() {
                return generic!(
                    "Cannot compute AOT info: classpath entry does not exist: {}",
                    path.display()
                );
            }

            let meta = err! {
                std::fs::metadata(path)
                => format!("Failed to read file metadata for {}", path.display())
            }?;
            let modified_time = err! {
                try {
                    meta
                        .modified().loc(l!())?
                        .duration_since(SystemTime::UNIX_EPOCH).loc(l!())?
                        .as_millis()
                }
                => format!("Failed to read file modification time for {}", path.display())
            }?;
            let hash = l!(file_hash(file))?;
            classpath_hashed.insert(
                path.clone(),
                Fingerprint {
                    hash,
                    timestamp: modified_time,
                },
            );
        }

        let aot_cache_hash = l!(file_hash(aot_cache_file))?;

        Ok(AotMeta {
            jvm_ident,
            classpath: classpath_hashed,
            jvm_args: jvm_args.iter().map(|s| s.to_string()).collect(),
            app_args: app_args.iter().map(|s| s.to_string()).collect(),
            aot_cache_hash,
        })
    }

    pub fn aot_files(dir: &Path) -> (PathBuf, PathBuf) {
        (AotMeta::aot_cache_file(dir), AotMeta::aot_meta_file(dir))
    }
    pub fn aot_cache_file(dir: &Path) -> PathBuf {
        dir.join(".paper").join("cache").join(AOT_CACHE_FILE)
    }

    pub fn aot_meta_file(dir: &Path) -> PathBuf {
        dir.join(".paper").join("cache").join(AOT_CACHE_META)
    }

    pub fn read(aot_meta_file: &Path) -> Result<Self, Error> {
        let bytes = err! {
            std::fs::read(aot_meta_file)
            => format!("Failed to read AOT meta file: {}", aot_meta_file.display())
        }?;
        let meta = err! {
            postcard::from_bytes::<Self>(&bytes)
            => format!("Failed to parse AOT meta file: {}", aot_meta_file.display())
        }?;
        Ok(meta)
    }

    pub fn write(&self, aot_meta_file: &Path) -> Result<(), Error> {
        let bytes = Vec::new();
        let bytes = err! {
            postcard::to_extend(&self, bytes)
            => "Failed to serialize AOT meta file"
        }?;
        if let Some(parent) = aot_meta_file.parent() {
            create_directory(parent)?;
        }
        let tmp_out = aot_meta_file.with_file_name("paper.aot.meta.tmp");
        err! {
            std::fs::write(&tmp_out, &bytes)
            => format!("Failed to write AOT meta file: {}", tmp_out.display())
        }?;
        err! {
            std::fs::rename(&tmp_out, aot_meta_file)
            => format!("Failed to rename AOT meta file: {} -> {}", tmp_out.display(), aot_meta_file.display())
        }?;

        Ok(())
    }
}

pub enum AotCacheAction {
    Use { aot_cache_file: PathBuf },
    Record { aot_cache_file: PathBuf },
    None,
}
impl AotCacheAction {
    pub fn use_cache(aot_cache_file: PathBuf) -> Self {
        Self::Use { aot_cache_file }
    }

    pub fn record(aot_cache_file: PathBuf) -> Self {
        Self::Record { aot_cache_file }
    }
    pub fn none() -> Self {
        Self::None
    }
}

pub fn check_aot_opt<'a>(
    repo_dir: &Path,
    mode: RecordMode,
    java_home: &str,
    classpath: &[OsString],
    jvm_args: &[&str],
    app_args: &[&str],
) -> Result<AotCacheAction, Error> {
    if mode == RecordMode::NoAot {
        return Ok(AotCacheAction::none());
    }

    let (aot_cache_file, aot_meta_file) = AotMeta::aot_files(&repo_dir);
    if let Some(parent) = aot_cache_file.parent() {
        create_directory(parent)?;
    }

    if !aot_cache_file.exists() || !aot_meta_file.exists() {
        return match mode {
            RecordMode::Normal | RecordMode::OnlyRecord | RecordMode::ForceRecord => {
                Ok(AotCacheAction::record(aot_cache_file))
            }
            RecordMode::Check => Err(Error::Exit(1)),
            RecordMode::OnlyUse => {
                eprintln!("No AOT cache found");
                Err(Error::Exit(ONLY_USE_AOT_FAILED_EXIT_CODE))
            }
            RecordMode::NoRecord => Ok(AotCacheAction::none()),
            RecordMode::NoAot => unreachable!(),
        };
    }

    let jvm_args = copy_owned(jvm_args);
    let app_args = copy_owned(app_args);
    let current_meta = l!(AotMeta::build(
        &java_home,
        classpath,
        &jvm_args,
        &app_args,
        &aot_cache_file
    ))?;
    let saved_meta = l!(AotMeta::read(&aot_meta_file))?;

    match mode {
        RecordMode::Normal => {
            if current_meta == saved_meta {
                Ok(AotCacheAction::use_cache(aot_cache_file))
            } else {
                Ok(AotCacheAction::record(aot_cache_file))
            }
        }
        RecordMode::Check => Err(Error::Exit(if current_meta == saved_meta { 0 } else { 1 })),
        RecordMode::OnlyUse => {
            if current_meta != saved_meta {
                eprintln!("AOT cache is invalid");
                Err(Error::Exit(ONLY_USE_AOT_FAILED_EXIT_CODE))
            } else {
                Ok(AotCacheAction::use_cache(aot_cache_file))
            }
        }
        RecordMode::NoRecord => {
            if current_meta != saved_meta {
                Ok(AotCacheAction::none())
            } else {
                Ok(AotCacheAction::use_cache(aot_cache_file))
            }
        }
        RecordMode::OnlyRecord => {
            if current_meta == saved_meta {
                Err(Error::Exit(0))
            } else {
                Ok(AotCacheAction::record(aot_cache_file))
            }
        }
        RecordMode::ForceRecord => Ok(AotCacheAction::record(aot_cache_file)),
        RecordMode::NoAot => unreachable!(),
    }
}

pub fn setup_auto_recording(
    jvm: Arc<JavaVM>,
    java_home: &str,
    classpath: &[OsString],
    jvm_args: &[String],
    app_args: &[String],
    mode: RecordMode,
) -> Option<JoinHandle<Result<(), Error>>> {
    let cwd = match std::env::current_dir() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("Failed to find current directory: {}", e);
            return None;
        }
    };
    let aot_meta_file = AotMeta::aot_meta_file(&cwd);

    if aot_meta_file.exists() {
        return None;
    }

    // Make copies to pass to the other thread
    let jvm_cache_checker = jvm.clone();
    let java_home = java_home.to_string();
    let classpath = classpath.iter().cloned().collect::<Vec<OsString>>();
    let jvm_args = copy_owned(jvm_args);
    let app_args = copy_owned(app_args);

    let meta_thread_handle = std::thread::spawn(move || {
        let watchdog_thread = jni_str!("org/spigotmc/WatchdogThread");
        let has_started_field = jni_str!("hasStarted");
        let has_started_sig = jni_sig!("Z");

        loop {
            std::thread::sleep(Duration::from_millis(100));
            // We don't stay attached, so if the server stops early, `jvm.destroy()` won't be deadlocked waiting
            // for us to release the last non-daemon thread.

            let mut scope = ScopeToken::default();
            let mut guard = l!(jni_attach_thread(
                &jvm_cache_checker,
                &mut scope,
                jni_str!("aot-cache-checker"),
            ))?;
            let env = guard.borrow_env_mut();

            // While we are waiting, we need to make sure the `main` and `Server thread` threads are still running.
            // If both threads are gone, that means the server has crashed.
            if !check_threads_are_running(env).loc(l!())? {
                return Ok(());
            }

            let has_started = env
                .get_static_field(&watchdog_thread, &has_started_field, &has_started_sig)
                .loc(l!())?
                .z();
            match has_started {
                Ok(true) => break,
                Ok(false) => continue,
                Err(jni::errors::Error::JavaException) => {
                    let _ = env.exception_clear(); // ignore it
                    continue;
                }
                Err(e) => return l!(Err(Error::from(e))),
            };
        }

        // Server has completed starting

        let ended_recording = end_aot_recording(&jvm_cache_checker);
        let ended_recording = match ended_recording {
            Ok(r) => r,
            Err(e) => {
                return l!(Err(Error::wrap("Failed to end AOT cache recording", e)));
            }
        };

        if ended_recording {
            // Recording is done, we need to write the meta file
            let aot_cache_file = AotMeta::aot_cache_file(&cwd);
            if !aot_cache_file.exists() {
                return generic!("No AOT cache file found after recording!");
            }

            let meta = match AotMeta::build(
                &java_home,
                &classpath,
                &jvm_args,
                &app_args,
                &aot_cache_file,
            ) {
                Ok(m) => m,
                Err(e) => {
                    return l!(Err(Error::wrap("Failed to generate AOT cache meta", e)));
                }
            };

            if let Err(e) = meta.write(&aot_meta_file) {
                return l!(Err(Error::wrap("Failed to write AOT cache meta", e)));
            };
        }

        // For only record, stop the server
        match mode {
            RecordMode::OnlyRecord | RecordMode::ForceRecord => {
                let _: Result<(), Error> = jvm_cache_checker.attach_current_thread(|env| {
                    let minecraft_server = env
                        .get_static_field(
                            jni_str!("net/minecraft/server/MinecraftServer"),
                            jni_str!("SERVER"),
                            jni_sig!("Lnet/minecraft/server/MinecraftServer;"),
                        )
                        .loc(l!())?
                        .into_object()
                        .loc(l!())?;
                    if minecraft_server.is_null() {
                        return Ok(());
                    }
                    env.call_method(
                        minecraft_server,
                        jni_str!("halt"),
                        jni_sig!("(Z)V"),
                        &[JValue::Bool(true)],
                    )
                    .loc(l!())?;
                    Ok(())
                });
            }
            _ => {}
        }

        Ok(())
    });
    Some(meta_thread_handle)
}

fn end_aot_recording(jvm: &JavaVM) -> Result<bool, Error> {
    let mut scope = ScopeToken::default();
    let mut guard = l!(jni_attach_thread(
        jvm,
        &mut scope,
        jni_str!("aot-cache-checker")
    ))?;
    let env = guard.borrow_env_mut();

    let aot_cache_bean_class =
        l!(env.find_class(jni_str!("jdk/management/HotSpotAOTCacheMXBean")))?;
    // Trigger record
    let aot_cache_bean = env
        .call_static_method(
            jni_str!("java/lang/management/ManagementFactory"),
            jni_str!("getPlatformMXBean"),
            jni_sig!("(Ljava/lang/Class;)Ljava/lang/management/PlatformManagedObject;"),
            &[JValue::Object(&aot_cache_bean_class)],
        )
        .loc(l!())?
        .into_object()
        .loc(l!())?;
    if aot_cache_bean.is_null() {
        return null!("ManagementFactory", "getThreadMXBean()");
    }

    let ended = env
        .call_method(
            &aot_cache_bean,
            jni_str!("endRecording"),
            jni_sig!("()Z"),
            &[],
        )
        .loc(l!())?
        .z()
        .loc(l!())?;
    Ok(ended)
}

// Threading

fn check_threads_are_running(env: &mut Env) -> Result<bool, Error> {
    let thread_mx_bean = env
        .call_static_method(
            jni_str!("java/lang/management/ManagementFactory"),
            jni_str!("getThreadMXBean"),
            jni_sig!("()Ljava/lang/management/ThreadMXBean;"),
            &[],
        )
        .loc(l!())?
        .into_object()
        .loc(l!())?;
    if thread_mx_bean.is_null() {
        return null!("ManagementFactory", "getThreadMXBean()");
    }

    let all_threads = env
        .call_method(
            &thread_mx_bean,
            jni_str!("dumpAllThreads"),
            jni_sig!("(ZZI)[Ljava/lang/management/ThreadInfo;"),
            &[JValue::Bool(false), JValue::Bool(false), JValue::Int(0)],
        )
        .loc(l!())?
        .into_object()
        .loc(l!())?;
    let all_threads = l!(env.cast_local::<JObjectArray>(all_threads))?;
    let len = l!(all_threads.len(env))?;

    if len == 0 {
        return Ok(false);
    }

    let terminated = env
        .get_static_field(
            jni_str!("java/lang/Thread$State"),
            jni_str!("TERMINATED"),
            jni_sig!("Ljava/lang/Thread$State;"),
        )
        .loc(l!())?
        .into_object()
        .loc(l!())?;

    for i in 0..len {
        let thread_info = l!(all_threads.get_element(env, i))?;
        if thread_info.is_null() {
            continue;
        }

        let thread_name = l!(thread_name(env, &thread_info))?;
        if thread_name != "Server thread" {
            continue;
        }

        let thread_state = env
            .call_method(
                thread_info,
                jni_str!("getThreadState"),
                jni_sig!("()Ljava/lang/Thread$State;"),
                &[],
            )
            .loc(l!())?
            .into_object()
            .loc(l!())?;
        if thread_state.is_null() {
            return null!("ThreadInfo", "getThreadState()");
        }

        let is_terminated = l!(env.is_same_object(&terminated, &thread_state))?;
        if !is_terminated {
            // At least one thread is still running
            return Ok(true);
        }
    }

    Ok(false)
}

fn thread_name(env: &mut Env, thread_info: &JObject) -> Result<String, Error> {
    let thread_name = env
        .call_method(
            thread_info,
            jni_str!("getThreadName"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )
        .loc(l!())?
        .into_object()
        .loc(l!())?;
    if thread_name.is_null() {
        return null!("ThreadInfo", "getThreadName()");
    }
    let thread_name = l!(env.cast_local::<JString>(thread_name))?;
    let thread_name = l!(thread_name.mutf8_chars(env))?;
    let thread_name = thread_name.to_str();
    Ok(thread_name.to_string())
}
