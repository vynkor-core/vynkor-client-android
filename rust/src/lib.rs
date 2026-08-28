//! vynkor Android device-agent — Rust core.
//!
//! Protocol engine (framing, frame-MAC, WS, registration) behind a UniFFI
//! boundary; Kotlin owns Android device I/O (mic/speaker, battery, location,
//! clipboard, contacts, notifications).
//!
//! Design: vynkor kernel repo, `docs/ANDROID_DEVICE_AGENT.md` +
//! `docs/ANDROID_DEVICE_AGENT_RUST_CORE.md`.

uniffi::setup_scaffolding!();

pub mod agent;
pub mod caps;
pub mod error;
pub mod ffi;
pub mod protocol;
pub mod transport;
