use crate::ONLY_USE_AOT_FAILED_EXIT_CODE;
use crate::config::CONFIG;
use crate::errors::Error;
use indoc::indoc;

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ArgOptions<'a> {
    pub jvm_args: Vec<&'a str>,
    pub app_args: Vec<&'a str>,
    pub record: RecordMode,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq, Default)]
pub enum RecordMode {
    #[default]
    Normal,
    Check,
    OnlyUse,
    NoRecord,
    OnlyRecord,
    ForceRecord,
    NoAot,
}

// Split args into (jvm_args, app_args)
// This function handles --version, --help, and other cases
pub fn split_args<'a>(args: &'a Vec<String>) -> Result<Option<ArgOptions<'a>>, Error> {
    // There are 3 categories of args, which this function tries to split organically:
    //  1. paperclip args
    //  2. jvm args
    //  3. app args
    // If a paperclip arg is found, it's handled and we immediately return.
    // jvm args must strictly come before app args. We determine jvm args by looking for -D or -X. If it doesn't
    // match, we assume it's an app arg. If we see another jvm arg after an app arg, we fail.

    let mut record_preference = RecordMode::default();
    let mut jvm_args = Vec::<&'a str>::new();
    let mut app_args = Vec::<&'a str>::new();

    let mut partition_mark = false;
    for arg in &args[1..] {
        match arg.as_str() {
            "-h" | "--help" => {
                if jvm_args.is_empty() && app_args.is_empty() {
                    return print_help(args);
                }
                app_args.push(arg);
            }
            "-v" | "--version" => {
                if jvm_args.is_empty() && app_args.is_empty() {
                    return print_version();
                }
                app_args.push(arg);
            }
            "--version-json" => {
                if jvm_args.is_empty() && app_args.is_empty() {
                    return print_vers_json();
                }
                app_args.push(arg);
            }
            "--check-aot" | "--only-use-aot" | "--no-record" | "--only-record"
            | "--force-record" | "--no-aot" => {
                if jvm_args.is_empty() && app_args.is_empty() {
                    if record_preference != RecordMode::Normal {
                        eprintln!(
                            "--check-aot, --only-use-aot, --no-record, --only-record, \
                            --force-record, and --no-aot may only be specified once"
                        );
                        return Err(Error::Exit(1));
                    }
                    record_preference = match arg.as_str() {
                        "--check-aot" => RecordMode::Check,
                        "--only-use-aot" => RecordMode::OnlyUse,
                        "--no-record" => RecordMode::NoRecord,
                        "--only-record" => RecordMode::OnlyRecord,
                        "--force-record" => RecordMode::ForceRecord,
                        "--no-aot" => RecordMode::NoAot,
                        _ => unreachable!(),
                    };
                } else {
                    app_args.push(arg);
                }
            }
            "--" => {
                if partition_mark {
                    app_args.push(arg);
                } else {
                    partition_mark = true;
                }
            }
            _ => {
                if partition_mark {
                    app_args.push(arg);
                } else if is_jvm_arg(arg) {
                    if !app_args.is_empty() {
                        eprintln!(
                            "JVM arguments must come before application arguments: `{}` comes after `{}`",
                            arg,
                            app_args.last().unwrap()
                        );
                        return Err(Error::Exit(1));
                    }
                    if arg.starts_with("-J") {
                        jvm_args.push(&arg[2..]);
                    } else {
                        jvm_args.push(arg);
                    }
                } else {
                    app_args.push(arg);
                }
            }
        }
    }

    Ok(Some(ArgOptions {
        record: record_preference,
        jvm_args,
        app_args,
    }))
}

fn is_jvm_arg(arg: &str) -> bool {
    arg.starts_with("-D") || arg.starts_with("-X") || arg.starts_with("-J")
}

fn print_help(args: &Vec<String>) -> Result<Option<ArgOptions<'static>>, Error> {
    println!(
        indoc! {"
            paperclip - Execute the Paper server with Project Leyden AOT
            (Ahead-Of-Time) cache management.

            License: MIT

            Usage: {} [aot args] [jvm args] [app args]

            Description:
              Wrapper to launch the Paper server. Arguments are divided into JVM
              arguments (for the Java Virtual Machine) and Application arguments
              (for the Paper server itself).

              JVM arguments must be specified BEFORE any Application arguments.
              If a JVM argument is encountered after Application arguments have
              started, the program will terminate with an error.

            JVM Discovery:
              Paperclip relies on the present of a Java Runtime Environment (JRE)
              to execute the server. Paperclip attemps to find the JRE based on
              the standard JRE discovery mechanisms of the platform its on. The
              'JAVA_HOME' environment variable will always take highest priority
              when selecting from multiple JREs. Otherwise, the location of the
              'java' executable on the 'PATH' will be used.

            AOT Arguments:
              Arguments that control how paperclip handles AOT recording and cache
              management. These must strictly come befre any JVM or app arguments,
              and are mutually exclusive. Only one of the options in this group
              are allowed at a time:

              --check-aot    Checks if a valid AOT cache file exists. If it does,
                             paperclip will immediately exit with exit code 0. If
                             not, paperclip will exit with exit code 1.
              --only-use-aot Require an existing vlid AOT cache to be present. If
                             no valid AOT cache file exists, paperclip will not
                             start, returning exit code {} instead.
              --no-record    Do not record AOT information. If a valid AOT cache
                             file already exists, it will be used. If no valid AOT
                             cache file exists, or the existing cache is out of
                             date, paperclip will ignore it and start without
                             recording a for a new AOT cache.
              --only-record  Only record a new AOT cache. Paperclip will shut down
                             the server immediately once the AOT cache has
                             successfuly been recorded. This option will not
                             re-record over a valid AOT cache.
              --force-record Force paperclip to record a new AOT cache. This
                             option behaves the same as '--only-record', except it
                             will always record a new cache, even if a valid cache
                             already exists.
              --no-aot       Disable all AOT features completely.

            JVM Arguments:
              Arguments passed to the JVM must match one of the following formats:

              -X<arg>        Pass extended arguments to the JVM.
                             (e.g., -Xmx4G, -XX:+UseG1GC)
              -D<prop>       Pass system properties to the JVM.
                             (e.g., -Dcom.mojang.eula.agree=true)
              -J<arg>        Pass any other arbitrary argument to the JVM by
                             prefixing it with '-J'. (e.g., '-J--enable-preview'
                             will pass '--enable-preview' to the JVM)

            Application Arguments:
              Any argument that does not start with -X, -D, or -J is automatically
              assumed to be an Application argument and marks the beginning of the
              [app args] section.

            Special Arguments:
              --             Marks the end of JVM arguments. All arguments following
                             this separator will be treated strictly as Application
                             arguments, even if they start with -X, -D, or -J.
        "},
        args[0], ONLY_USE_AOT_FAILED_EXIT_CODE,
    );

    Ok(None)
}

fn print_version() -> Result<Option<ArgOptions<'static>>, Error> {
    println!("{}", CONFIG.version);
    Ok(None)
}

fn print_vers_json() -> Result<Option<ArgOptions<'static>>, Error> {
    println!("{}", CONFIG.version_json);
    Ok(None)
}
