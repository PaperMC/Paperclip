use std::path::Path;
use std::process::Command;
use chrono::{DateTime, SubsecRound, Utc};

fn main() {
    println!("cargo::rerun-if-env-changed=CI_BUILD_DATE");

    configure_host();

    let out_dir = std::env::var("OUT_DIR").unwrap();
    let path = Path::new(&out_dir).join("config.rs");

    let base_version = std::env::var("CARGO_PKG_VERSION").unwrap();

    let rev = Command::new("git")
        .args(["rev-parse", "--short=8", "HEAD"])
        .output()
        .unwrap()
        .stdout;
    let rev = String::from_utf8(rev).unwrap();
    let rev = rev.trim();

    let timestamp = std::env::var("CI_BUILD_DATE")
        .map(|d| DateTime::from_timestamp(d.parse().unwrap(), 0).unwrap())
        .unwrap_or(Utc::now());
    let timestamp = timestamp.trunc_subsecs(0).format("%+").to_string();

    let version_text = format!("pub mod config {{ pub const VERSION: &str = \"{base_version} (commit: {rev}) (build: {timestamp})\"; }}\n");
    std::fs::write(path, &version_text).unwrap();
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
