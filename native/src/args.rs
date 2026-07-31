use crate::ONLY_USE_AOT_FAILED_EXIT_CODE;
use crate::errors::Error;
use crate::util::ComposingIterator;
use indoc::indoc;
use std::fs::File;
use std::io::{BufRead, BufReader};

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ArgOptions {
    pub jar: String,
    pub jvm_args: Vec<String>,
    pub app_args: Vec<String>,
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
pub fn split_args(args: Vec<String>) -> Result<Option<ArgOptions>, Error> {
    // There are 3 categories of args, which this function tries to split organically:
    //  1. paperclip args
    //  2. jvm args
    //  3. app args
    // If a paperclip arg is found, it's handled and we immediately return.
    // jvm args come before -jar, and app args come after -jar.

    let mut record_preference = RecordMode::default();
    let mut jar: Option<String> = None;
    let mut jvm_args = Vec::<String>::new();
    let mut app_args = Vec::<String>::new();

    let mut args_iter = ComposingIterator::new(Box::new(args.into_iter()));
    let first_arg = args_iter.next().unwrap();

    let mut disable_at_files = false;

    loop {
        let next = args_iter.next();
        if next.is_none() {
            break;
        }
        let arg: String = next.unwrap();

        if !disable_at_files && !args_iter.is_nested() && arg.starts_with('@') {
            let mut nested = Vec::<String>::new();
            args_file(&arg, &mut nested)?;
            args_iter.push_layer(Box::new(nested.into_iter()));
            continue;
        }

        match arg.as_str() {
            "-h" | "--help" => {
                if jvm_args.is_empty() && app_args.is_empty() && jar.is_none() {
                    return print_help(first_arg);
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

                let jar_file = args_iter.next();
                if jar_file.is_none() {
                    eprintln!("-jar requires a jar file argument");
                    return Err(Error::Exit(1));
                }
                jar = Some(jar_file.unwrap());
            }
            "--disable-@files" => disable_at_files = true,
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

fn args_file(arg: &str, target: &mut Vec<String>) -> Result<(), Error> {
    if !arg.starts_with('@') {
        return Ok(());
    }

    if arg.starts_with("@@") {
        target.push(arg[1..].to_string());
        return Ok(());
    }

    let filepath = &arg[1..];
    let file = File::open(filepath)?;
    let metadata = file.metadata()?;
    if metadata.len() > 2_147_483_647 {
        return Err(Error::Generic(format!(
            "Argument file {} exceeds maximum size of 2147483647 bytes",
            filepath
        )));
    }

    let reader = BufReader::new(file);
    let stream = CharStream::new(reader);
    parse_args_file_stream(stream, target)?;

    Ok(())
}

struct CharStream<R: BufRead> {
    reader: R,
    peeked: Vec<char>,
    bytes_read: u64,
}

impl<R: BufRead> CharStream<R> {
    fn new(reader: R) -> Self {
        Self {
            reader,
            peeked: Vec::with_capacity(4),
            bytes_read: 0,
        }
    }

    fn read_one_char(&mut self) -> Result<Option<char>, std::io::Error> {
        let mut first_byte = [0u8; 1];
        if self.reader.read(&mut first_byte)? == 0 {
            return Ok(None);
        }
        self.bytes_read += 1;
        if self.bytes_read > 2_147_483_647 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "Argument file exceeds maximum size of 2147483647 bytes",
            ));
        }

        let b = first_byte[0];
        if b < 0x80 {
            return Ok(Some(b as char));
        }

        let len = if b & 0xE0 == 0xC0 {
            2
        } else if b & 0xF0 == 0xE0 {
            3
        } else if b & 0xF8 == 0xF0 {
            4
        } else {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "Invalid UTF-8 sequence",
            ));
        };

        let mut buf = vec![0u8; len];
        buf[0] = b;
        self.reader.read_exact(&mut buf[1..])?;
        self.bytes_read += (len - 1) as u64;

        let s = std::str::from_utf8(&buf)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;

        Ok(s.chars().next())
    }

    fn peek(&mut self, offset: usize) -> Result<Option<char>, std::io::Error> {
        while self.peeked.len() <= offset {
            match self.read_one_char()? {
                Some(c) => self.peeked.push(c),
                None => return Ok(None),
            }
        }
        Ok(Some(self.peeked[offset]))
    }

    fn next(&mut self) -> Result<Option<char>, std::io::Error> {
        if !self.peeked.is_empty() {
            return Ok(Some(self.peeked.remove(0)));
        }
        self.read_one_char()
    }
}

fn parse_args_file_stream<R: BufRead>(
    mut stream: CharStream<R>,
    target: &mut Vec<String>,
) -> Result<(), Error> {
    let mut current_arg = String::new();
    let mut in_token = false;
    let mut quote_char: Option<char> = None;

    while let Some(ch) = stream.peek(0)? {
        // Line Continuation ('\' followed by EOL)
        if ch == '\\' {
            let next1 = stream.peek(1)?;
            let mut eol_len = 0;
            if next1 == Some('\n') {
                eol_len = 1;
            } else if next1 == Some('\r') {
                let next2 = stream.peek(2)?;
                if next2 == Some('\n') {
                    eol_len = 2;
                } else {
                    eol_len = 1;
                }
            }

            if eol_len > 0 {
                stream.next()?; // consume '\'
                for _ in 0..eol_len {
                    stream.next()?;
                }
                in_token = true;

                // Check if the first character on the new line is '\'
                if stream.peek(0)? == Some('\\') {
                    // Continuation character ('\') at column 1 prevents trimming leading whitespace
                    stream.next()?;
                } else {
                    // Trim leading whitespace on the new line
                    while let Some(c) = stream.peek(0)? {
                        if c == ' ' || c == '\t' || c == '\u{000C}' {
                            stream.next()?;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
        }

        // Escape Sequence ('\' NOT followed by EOL)
        if ch == '\\' {
            stream.next()?; // consume '\'
            if let Some(next_c) = stream.next()? {
                match next_c {
                    'n' => current_arg.push('\n'),
                    'r' => current_arg.push('\r'),
                    't' => current_arg.push('\t'),
                    'f' => current_arg.push('\u{000C}'),
                    '\\' => current_arg.push('\\'),
                    '"' => current_arg.push('"'),
                    '\'' => current_arg.push('\''),
                    '#' => current_arg.push('#'),
                    c => {
                        current_arg.push('\\');
                        current_arg.push(c);
                    }
                }
                in_token = true;
            } else {
                current_arg.push('\\');
                in_token = true;
            }
            continue;
        }

        // Quotes ('"' or '\'')
        if ch == '"' || ch == '\'' {
            stream.next()?; // consume quote char
            if let Some(open_q) = quote_char {
                if open_q == ch {
                    quote_char = None;
                } else {
                    current_arg.push(ch);
                }
            } else {
                quote_char = Some(ch);
            }
            in_token = true;
            continue;
        }

        // EOL ('\n' or '\r')
        if ch == '\n' || ch == '\r' {
            if quote_char.is_some() {
                // Inside open quote, EOL without '\' continuation is discarded
                stream.next()?;
                if ch == '\r' && stream.peek(0)? == Some('\n') {
                    stream.next()?;
                }
            } else {
                if in_token {
                    push_token(current_arg, target);
                    current_arg = String::new();
                    in_token = false;
                }
                stream.next()?;
                if ch == '\r' && stream.peek(0)? == Some('\n') {
                    stream.next()?;
                }
            }
            continue;
        }

        // Comment ('#')
        if ch == '#' && quote_char.is_none() {
            if in_token {
                push_token(current_arg, target);
                current_arg = String::new();
                in_token = false;
            }
            while let Some(c) = stream.peek(0)? {
                if c == '\n' || c == '\r' {
                    break;
                }
                stream.next()?;
            }
            continue;
        }

        // Whitespace (' ', '\t', '\f')
        if ch == ' ' || ch == '\t' || ch == '\u{000C}' {
            stream.next()?;
            if quote_char.is_some() {
                current_arg.push(ch);
                in_token = true;
            } else {
                if in_token {
                    push_token(current_arg, target);
                    current_arg = String::new();
                    in_token = false;
                }
            }
            continue;
        }

        // Regular character
        stream.next()?;
        current_arg.push(ch);
        in_token = true;
    }

    if in_token {
        push_token(current_arg, target);
    }

    Ok(())
}

fn push_token(token: String, target: &mut Vec<String>) {
    let processed = if token.starts_with("@@") {
        token[1..].to_string()
    } else {
        token
    };
    target.push(processed);
}

fn print_help(first_arg: String) -> Result<Option<ArgOptions>, Error> {
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
              Any argument that follows the jar file given to the '-jar' argument is
              passed directly into the server.
        "},
        first_arg, ONLY_USE_AOT_FAILED_EXIT_CODE,
    );

    Ok(None)
}

fn print_version() -> Result<Option<ArgOptions>, Error> {
    println!("{}", crate::config::VERSION);
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn create_temp_arg_file(
        prefix: &str,
        content: &str,
    ) -> (std::path::PathBuf, tempfile_cleanup::Cleanup) {
        let mut path = std::env::temp_dir();
        let filename = format!(
            "paperclip_test_args_{}_{}_{}.txt",
            prefix,
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        path.push(filename);
        let mut file = File::create(&path).unwrap();
        file.write_all(content.as_bytes()).unwrap();
        let cleanup_path = path.clone();
        (path, tempfile_cleanup::Cleanup(cleanup_path))
    }

    mod tempfile_cleanup {
        pub struct Cleanup(pub std::path::PathBuf);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                let _ = std::fs::remove_file(&self.0);
            }
        }
    }

    #[test]
    fn test_non_at_arg() {
        let mut target = Vec::new();
        args_file("foo", &mut target).unwrap();
        assert!(target.is_empty());
    }

    #[test]
    fn test_escaped_at_arg() {
        let mut target = Vec::new();
        args_file("@@foo", &mut target).unwrap();
        assert_eq!(target, vec!["@foo"]);
    }

    #[test]
    fn test_open_quote_example1() {
        let content = "-cp \"lib/\ncool/\napp/\njars";
        let (path, _cleanup) = create_temp_arg_file("ex1", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(target, vec!["-cp", "lib/cool/app/jars"]);
    }

    #[test]
    fn test_escaped_backslash_example2() {
        let content = "-cp \"c:\\\\Program Files (x86)\\\\Java\\\\jre\\\\lib\\\\ext;c:\\\\Program Files\\\\Java\\\\jre9\\\\lib\\\\ext\"";
        let (path, _cleanup) = create_temp_arg_file("ex2", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(
            target,
            vec![
                "-cp",
                "c:\\Program Files (x86)\\Java\\jre\\lib\\ext;c:\\Program Files\\Java\\jre9\\lib\\ext"
            ]
        );
    }

    #[test]
    fn test_line_continuation_example3() {
        let content = "-cp \"/lib/cool app/jars:\\\n/lib/another app/jars\"";
        let (path, _cleanup) = create_temp_arg_file("ex3", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(
            target,
            vec!["-cp", "/lib/cool app/jars:/lib/another app/jars"]
        );
    }

    #[test]
    fn test_line_continuation_column1_example4() {
        let content = "-cp \"/lib/cool\\\n\\ app/jars\"";
        let (path, _cleanup) = create_temp_arg_file("ex4", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(target, vec!["-cp", "/lib/cool app/jars"]);
    }

    #[test]
    fn test_comments_and_disable_at_files() {
        let content = "-Xmx1g\n# This is a comment\n--disable-@files\n-Xmx2g\n@another.txt";
        let (path, _cleanup) = create_temp_arg_file("ex5", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(
            target,
            vec!["-Xmx1g", "--disable-@files", "-Xmx2g", "@another.txt"]
        );
    }

    #[test]
    fn test_single_backslash_path() {
        // Spec line 21: c:\Program" "Files should evaluate to c:\Program Files
        let content = "c:\\Program\" \"Files";
        let (path, _cleanup) = create_temp_arg_file("def1", content);
        let arg_str = format!("@{}", path.to_str().unwrap());
        let mut target = Vec::new();
        args_file(&arg_str, &mut target).unwrap();
        assert_eq!(target, vec!["c:\\Program Files"]);
    }

    #[test]
    fn test_split_args_basic() {
        let args = vec![
            "paperclip".to_string(),
            "-Xmx2G".to_string(),
            "-jar".to_string(),
            "paper.jar".to_string(),
            "--nogui".to_string(),
        ];
        let res = split_args(args).unwrap().unwrap();
        assert_eq!(res.jar, "paper.jar");
        assert_eq!(res.record, RecordMode::Normal);
        assert_eq!(res.jvm_args, vec!["-Xmx2G"]);
        assert_eq!(res.app_args, vec!["--nogui"]);
    }

    #[test]
    fn test_split_args_aot_flag() {
        let args = vec![
            "paperclip".to_string(),
            "--only-use-aot".to_string(),
            "-Xmx1G".to_string(),
            "-jar".to_string(),
            "server.jar".to_string(),
        ];
        let res = split_args(args).unwrap().unwrap();
        assert_eq!(res.jar, "server.jar");
        assert_eq!(res.record, RecordMode::OnlyUse);
        assert_eq!(res.jvm_args, vec!["-Xmx1G"]);
        assert!(res.app_args.is_empty());
    }

    #[test]
    fn test_split_args_with_at_file() {
        let content = "-Xmx4g\n-Dfoo=bar\n-jar\npaper.jar\n--port\n25565";
        let (path, _cleanup) = create_temp_arg_file("split_at", content);
        let args = vec![
            "paperclip".to_string(),
            format!("@{}", path.to_str().unwrap()),
        ];
        let res = split_args(args).unwrap().unwrap();
        assert_eq!(res.jar, "paper.jar");
        assert_eq!(res.jvm_args, vec!["-Xmx4g", "-Dfoo=bar"]);
        assert_eq!(res.app_args, vec!["--port", "25565"]);
    }

    #[test]
    fn test_split_args_disable_at_files() {
        let content1 = "-Xmx1g";
        let content2 = "-Xmx2g";
        let (path1, _c1) = create_temp_arg_file("dis_at1", content1);
        let (path2, _c2) = create_temp_arg_file("dis_at2", content2);
        let arg2_str = format!("@{}", path2.to_str().unwrap());

        let args = vec![
            "paperclip".to_string(),
            format!("@{}", path1.to_str().unwrap()),
            "--disable-@files".to_string(),
            arg2_str.clone(),
            "-jar".to_string(),
            "paper.jar".to_string(),
        ];
        let res = split_args(args).unwrap().unwrap();
        assert_eq!(res.jar, "paper.jar");
        assert_eq!(res.jvm_args, vec!["-Xmx1g".to_string(), arg2_str]);
    }

    #[test]
    fn test_split_args_no_jar_error() {
        let args = vec!["paperclip".to_string(), "-Xmx1g".to_string()];
        assert!(split_args(args).is_err());
    }

    #[test]
    fn test_split_args_duplicate_jar_error() {
        let args = vec![
            "paperclip".to_string(),
            "-jar".to_string(),
            "a.jar".to_string(),
            "-jar".to_string(),
            "b.jar".to_string(),
        ];
        assert!(split_args(args).is_err());
    }
}
