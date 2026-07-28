use crate::ONLY_USE_AOT_FAILED_EXIT_CODE;
use crate::errors::Error;
use indoc::indoc;

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ArgOptions<'a> {
    pub jar: &'a str,
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
    // jvm args come before -jar, and app args come after -jar.

    let mut record_preference = RecordMode::default();
    let mut jar: Option<&'a str> = None;
    let mut jvm_args = Vec::<&'a str>::new();
    let mut app_args = Vec::<&'a str>::new();

    let args_iter = &mut args[1..].iter();
    loop {
        let next = args_iter.next();
        if next.is_none() {
            break;
        }
        let arg = next.unwrap();

        match arg.as_str() {
            "-h" | "--help" => {
                if jvm_args.is_empty() && app_args.is_empty() && jar.is_none() {
                    return print_help(args);
                }
                app_args.push(arg);
            }
            "-v" | "--version" => {
                if jvm_args.is_empty() && app_args.is_empty() && jar.is_none() {
                    return print_version();
                }
                app_args.push(arg);
            }
            "--check-aot" | "--only-use-aot" | "--no-record" | "--only-record"
            | "--force-record" | "--no-aot" => {
                if jvm_args.is_empty() && app_args.is_empty() && jar.is_none() {
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
            "-jar" | "--jar" => {
                if jar.is_some() {
                    eprintln!("-jar may only be specified once");
                    return Err(Error::Exit(1));
                }

                let jar_file =args_iter.next();
                if jar_file.is_none() {
                    eprintln!("-jar requires a jar file argument");
                    return Err(Error::Exit(1));
                }
                jar = Some(jar_file.unwrap());
            }
            _ => {
                if jar.is_none() {
                    jvm_args.push(arg);
                } else {
                    app_args.push(arg);
                }
            }
        }
    }

    if jar.is_none() {
        eprintln!("-jar <paper_jar> argument must be provided");
        return Err(Error::Exit(1));
    }

    Ok(Some(ArgOptions {
        jar: jar.unwrap(),
        record: record_preference,
        jvm_args,
        app_args,
    }))
}

fn print_help(args: &Vec<String>) -> Result<Option<ArgOptions<'static>>, Error> {
    println!(
        indoc! {"
            paperclip - Execute the Paper server with Project Leyden AOT
            (Ahead-Of-Time) cache management.

            License: MIT

            Usage: {} [aot args] [jvm args] -jar <paper_jar> [app args]

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

            -jar <paper_jar> The path to the Paper server jar file. This argument is
                             required. The jar file must be a valid Paperclip jar.

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
              Any argument that preceeds '-jar' after the AOT arguments is passed
              directly into the JVM. This includes -Xmx, -XX:+UseG1GC, etc. as well
              as others like -D<prop> for setting system properties.

            Application Arguments:
              Any argument tha follows the jar file given to the '-jar' argument is
              passed directly into the server.
        "},
        args[0], ONLY_USE_AOT_FAILED_EXIT_CODE,
    );

    Ok(None)
}

fn print_version() -> Result<Option<ArgOptions<'static>>, Error> {
    println!("{}", crate::config::VERSION);
    Ok(None)
}
