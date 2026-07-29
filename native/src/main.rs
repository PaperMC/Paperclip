#![feature(try_blocks)]

pub mod aot;
pub mod args;
pub mod classpath;
pub mod errors;
pub mod jni;
pub mod util;

use crate::aot::{AotCacheAction, AotMeta, check_aot_opt, setup_auto_recording};
use crate::args::split_args;
use crate::classpath::{repo_dir, setup_classpath};
use crate::errors::Error;
use crate::jni::check_java_version;
use crate::util::{JoinHandleRes, classpath_sep, copy_owned};
use ::jni::objects::{JObjectArray, JString};
use ::jni::strings::JNIString;
use ::jni::{AttachConfig, Env, InitArgsBuilder, JNIVersion, JavaVM, jni_sig, jni_str};
use std::ffi::OsString;
use std::path::Path;
use std::sync::Arc;
use std::thread::JoinHandle;

include!(concat!(env!("OUT_DIR"), "/config.rs"));

pub const ONLY_USE_AOT_FAILED_EXIT_CODE: i32 = 33;

fn main() {
    nyquest_preset::register();
    std::process::exit(run());
}

fn run() -> i32 {
    let args: Vec<String> = std::env::args().collect();
    let arg_opts = match split_args(args) {
        Ok(Some(arg_opts)) => arg_opts,
        Ok(None) => return 0,
        Err(Error::Exit(code)) => return code,
        Err(e) => {
            eprintln!("{e}");
            return 1;
        }
    };

    let jar = Path::new(&arg_opts.jar);
    if !jar.exists() {
        eprintln!("Jar file does not exist: {}", jar.display());
        return 1;
    }

    let repo_dir = repo_dir();
    let jvm_args = arg_opts.jvm_args;
    let app_args = arg_opts.app_args;

    let java_home = match java_locator::locate_java_home() {
        Ok(j) => j,
        Err(e) => {
            eprintln!("Failed to locate Java home: {}", e);
            return 1;
        }
    };

    match check_java_version(&java_home) {
        Ok(()) => {}
        Err(Error::Exit(code)) => return code,
        Err(e) => {
            eprintln!("{}", e);
            return 1;
        }
    };

    let (classpath, meta) = match setup_classpath(&repo_dir, jar) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{}", Error::wrap("Failed to setup classpath", e));
            return 1;
        }
    };

    // We either need to start the JVM with -XX:AOTCacheOutput= (record) or -XX:AOTCache= (use)
    let aot_action = match check_aot_opt(
        &repo_dir,
        arg_opts.record,
        &java_home,
        &classpath,
        &jvm_args,
        &app_args,
    ) {
        Ok(a) => a,
        Err(Error::Exit(code)) => return code,
        Err(e) => {
            eprintln!("{}", Error::wrap("Failed to check AOT cache", e));
            return 1;
        }
    };

    if let AotCacheAction::Record { .. } = aot_action {
        // If we're recording, we need to delete any existing AOT cache files
        let meta_file = AotMeta::aot_meta_file(&repo_dir);
        if meta_file.exists() {
            std::fs::remove_file(&meta_file).unwrap();
        }
        let cache_file = AotMeta::aot_cache_file(&repo_dir);
        if cache_file.exists() {
            std::fs::remove_file(&cache_file).unwrap();
        }
    }

    let jvm_args = copy_owned(&jvm_args);
    let app_args = copy_owned(&app_args);
    let jvm_thread = std::thread::spawn(move || {
        let _hold = JvmThreadDrop; // Run drop() on this whenever this thread finishes

        let jvm = match create_jvm(&jvm_args, &classpath, &aot_action) {
            Ok(jvm) => jvm,
            Err(Error::Exit(code)) => return code,
            Err(e) => {
                eprintln!("{}", Error::wrap("Failed to create JVM", e));
                return 1;
            }
        };
        let jvm = Arc::new(jvm);

        if let AotCacheAction::Record { .. } = aot_action {
            println!(
                "Beginning AOT cache recording (This may cause slowdowns while the JVM is recording)..."
            );
        }
        let server_thread = start_jvm_thread(jvm.clone(), &meta.main_class, &app_args);
        let server_thread_res = server_thread.join_res();

        let meta_thread = setup_auto_recording(
            jvm.clone(),
            &java_home,
            &classpath,
            &jvm_args,
            &app_args,
            arg_opts.record,
        );
        let meta_thread_res = meta_thread.map(|h| h.join_res());

        unsafe {
            if let Err(e) = jvm.destroy() {
                eprintln!("Error during JVM shutdown: {:?}", e);
            }
        }
        drop(jvm);

        if let Some(Err(e)) = meta_thread_res {
            eprintln!("Error during AOT recording: {e}");
        }

        match server_thread_res {
            Ok(_) => 0,
            Err(e) => {
                eprintln!("Error during server thread: {e}");
                1
            }
        }
    });

    // Run the native macOS event loop on the main thread
    // This keeps the main thread unblocked and processes AWT window events
    #[cfg(target_os = "macos")]
    {
        use core_foundation::runloop::CFRunLoopRun;
        unsafe {
            // This will block and process UI events until canceled
            CFRunLoopRun();
        }
    }

    jvm_thread.join().unwrap_or(1)
}

struct JvmThreadDrop;
impl Drop for JvmThreadDrop {
    fn drop(&mut self) {
        #[cfg(target_os = "macos")]
        {
            use core_foundation::runloop::{CFRunLoopGetMain, CFRunLoopStop};
            unsafe {
                CFRunLoopStop(CFRunLoopGetMain());
            }
        }
    }
}

fn start_jvm_thread(
    jvm: Arc<JavaVM>,
    main_class: &str,
    app_args: &[String],
) -> JoinHandle<Result<(), Error>> {
    let app_args = app_args
        .iter()
        .map(|s| s.to_string())
        .collect::<Vec<String>>();
    let main_class = main_class.to_string();

    std::thread::spawn(move || {
        jvm.attach_current_thread_with_config(
            || {
                AttachConfig::default()
                    .scoped(true)
                    .thread_name(jni_str!("main"))
            },
            None,
            |env| exec_jvm(env, &main_class, &app_args),
        )
    })
}

fn create_jvm(
    jvm_args: &[String],
    classpath: &[OsString],
    aot_action: &AotCacheAction,
) -> Result<JavaVM, Error> {
    // Exit if user has set `PAPERCLIP_PATCHONLY` env variable to `true`
    if let Some(prop) = std::env::var_os("PAPERCLIP_PATCHONLY")
        && prop.eq_ignore_ascii_case("true")
    {
        return Err(Error::Exit(0));
    }

    init_jvm(jvm_args, classpath, aot_action)
}

fn init_jvm(
    jvm_args: &[String],
    classpath: &[OsString],
    aot_action: &AotCacheAction,
) -> Result<JavaVM, Error> {
    let mut init_args = InitArgsBuilder::new().version(JNIVersion::V21);
    init_args = init_args
        .option("--enable-native-access=ALL-UNNAMED")
        .option("--sun-misc-unsafe-memory-access=allow");

    match aot_action {
        AotCacheAction::Use { aot_cache_file } | AotCacheAction::Record { aot_cache_file } => {
            match aot_cache_file.to_str() {
                Some(p) => match aot_action {
                    AotCacheAction::Use { .. } => {
                        init_args = init_args
                            .option(format!("-XX:AOTCache={p}"))
                            .option("-Xlog:aot*=error")
                    }
                    AotCacheAction::Record { .. } => {
                        init_args = init_args
                            .option(format!("-XX:AOTCacheOutput={p}",))
                            .option("-Xlog:aot*=error")
                    }
                    AotCacheAction::None => unreachable!(),
                },
                None => {
                    return generic!(
                        "Failed to convert AOT cache file path to string: {}",
                        aot_cache_file.display()
                    );
                }
            };
        }
        AotCacheAction::None => {}
    }

    let classpath_text = classpath.join(&classpath_sep());
    let classpath_text = match classpath_text.into_string() {
        Ok(t) => t,
        Err(e) => {
            return generic!("Failed to convert classpath to string: {}", e.display());
        }
    };
    init_args = init_args.option(format!("-Djava.class.path={classpath_text}"));

    for arg in jvm_args {
        init_args = init_args.option(arg);
    }

    let init_args = err! {
        init_args.build()
        => "Failed to initialize JVM"
    }?;
    err! {
        JavaVM::new(init_args)
        => "Failed to start JVM"
    }
}

fn exec_jvm(env: &mut Env, main_class: &str, args: &[String]) -> Result<(), Error> {
    let args_array = l!(JObjectArray::<JString>::new(
        env,
        args.len(),
        JString::null()
    ))?;
    for (i, arg) in args.iter().enumerate() {
        let arg = l!(JString::new(env, arg.clone()))?;
        l!(args_array.set_element(env, i, arg))?;
    }

    let class_name = JNIString::new(main_class.replace(".", "/"));
    let psvm_name = jni_str!("main");
    let psvm_desc = jni_sig!("([Ljava/lang/String;)V");
    l!(env.call_static_method(class_name, psvm_name, psvm_desc, &[(&args_array).into()]))?;

    if env.exception_check() {
        env.exception_describe(); // Prints the stack trace to stderr
        env.exception_clear();
        // Don't return any error here, the error message has already been printed
    }

    Ok(())
}
