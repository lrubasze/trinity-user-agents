//! Native `log` sink for the embedded light client.
//!
//! smoldot emits through `log::logger()` (see `smoldot_light::platform::default`),
//! and a Rust staticlib carries its own copy of the global logger the `log` crate
//! state. Every mobile shell links the provider as a separate staticlib, so it
//! cannot make smoldot output visible by installing a bridge on its own side.
//! The logger has to be set inside this library. Without
//! it the sync, peer and networking detail from smoldot is discarded and a light client
//! that never produces blocks looks identical to one that is merely slow.
//!
//! Off unless `TRUAPI_PROVIDER_LOG` names a level, so a shipping app pays
//! nothing. Plaintext to stderr, or to logcat on Android, where an app's stderr
//! goes nowhere: never log secret material.

use std::io::Write as _;
use std::sync::Once;

static INSTALL: Once = Once::new();

struct StderrLogger;

impl log::Log for StderrLogger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::max_level()
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // One write per record. Interleaved fragments from the worker
        // threads would be unreadable.
        let line = format!(
            "[provider] {:<5} {}: {}\n",
            record.level(),
            record.target(),
            record.args()
        );
        write_line(record.level(), &line);
    }

    fn flush(&self) {
        let _ = std::io::stderr().flush();
    }
}

/// Installs the stderr logger from `TRUAPI_PROVIDER_LOG`, once. Unset or
/// unrecognised leaves logging off.
pub fn init_from_env() {
    INSTALL.call_once(|| {
        let level = match std::env::var("TRUAPI_PROVIDER_LOG")
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase()
            .as_str()
        {
            "trace" => log::LevelFilter::Trace,
            "debug" => log::LevelFilter::Debug,
            "info" => log::LevelFilter::Info,
            "warn" => log::LevelFilter::Warn,
            "error" => log::LevelFilter::Error,
            _ => log::LevelFilter::Off,
        };
        if level == log::LevelFilter::Off {
            return;
        }
        if log::set_boxed_logger(Box::new(StderrLogger)).is_ok() {
            log::set_max_level(level);
            write_line(
                log::Level::Info,
                &format!("[provider] logging installed at {level}\n"),
            );
        }
    });
}

#[cfg(not(target_os = "android"))]
fn write_line(_level: log::Level, line: &str) {
    let _ = std::io::stderr().write_all(line.as_bytes());
}

#[cfg(target_os = "android")]
fn write_line(level: log::Level, line: &str) {
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(
            priority: core::ffi::c_int,
            tag: *const core::ffi::c_char,
            text: *const core::ffi::c_char,
        ) -> core::ffi::c_int;
    }

    // android/log.h priorities.
    let priority = match level {
        log::Level::Trace => 2,
        log::Level::Debug => 3,
        log::Level::Info => 4,
        log::Level::Warn => 5,
        log::Level::Error => 6,
    };
    let Ok(text) = std::ffi::CString::new(line.trim_end().replace('\0', " ")) else {
        return;
    };
    // SAFETY: both pointers are NUL-terminated strings that outlive the call.
    unsafe {
        __android_log_write(priority, c"truapi-provider".as_ptr(), text.as_ptr());
    }
}
