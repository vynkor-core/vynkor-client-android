//! UniFFI surface — the Kotlin <-> Rust boundary.
//!
//! Kotlin implements the foreign traits (device I/O); the `Agent` object
//! (agent.rs) is Rust-owned and exported there. This file holds the plain
//! data records and the foreign traits — the contract both sides compile
//! against. No protocol logic here.

/// Connection config. `jwt_secret` is the host kernel's `jwt_secret` value —
/// needed to derive the per-session frame-MAC key (same rule as the D-06
/// bridge). On the device it is stored only inside the app's encrypted
/// profile store (Android Keystore, AES-GCM) and excluded from cloud backups.
#[derive(uniffi::Record)]
pub struct AgentConfig {
    /// Host kernel WS endpoint, e.g. `wss://host:port/ws`.
    pub host_url: String,
    /// Device JWT (`sub = device_id`, restricted claims).
    pub jwt_token: String,
    /// Host's `jwt_secret`, for frame-MAC key derivation.
    pub jwt_secret: String,
    /// Host's served TLS cert (PEM) to pin when `host_url` is `wss://` and the
    /// cert is self-signed. Empty = verify against webpki-roots only.
    pub cert_pem: String,
    /// Stable per-install UUID.
    pub device_id: String,
    /// Capabilities to register, e.g.
    /// `["geo", "battery", "notifications", "clipboard", "contacts", "mic", "speaker"]`.
    pub capabilities: Vec<String>,
    /// Android OS version reported to the host, e.g. "14".
    pub os_version: String,
    /// CPU arch reported to the host, e.g. "aarch64".
    pub arch: String,
    /// Host user this device belongs to; defaults to "default".
    pub user_id: String,
}

/// A location fix.
#[derive(uniffi::Record)]
pub struct Location {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f32,
}

/// A contact record.
#[derive(uniffi::Record)]
pub struct Contact {
    pub name: String,
    pub phones: Vec<String>,
    pub emails: Vec<String>,
}

/// Outcome of an outbound [`Agent::request`] call, mirroring the kernel's
/// `ActionStatus` (plus a local timeout when the host never answers).
#[derive(uniffi::Enum)]
pub enum ActionReplyStatus {
    Ok,
    Error,
    Timeout,
    PermissionDenied,
    NotFound,
    QuotaExceeded,
    StreamBackpressure,
    /// The request couldn't even be sent (no live connection, encode failure).
    Local,
}

/// Reply to an outbound action request: the terminal `ActionResponse`
/// (status + `data_json` + `error`) correlated back to the caller.
#[derive(uniffi::Record)]
pub struct ActionReply {
    pub status: ActionReplyStatus,
    pub data_json: Vec<u8>,
    pub error: String,
}

/// Fine-grained connection progress for the UI, emitted between the coarse
/// connected/disconnected transitions of [AgentObserver::on_state_changed].
#[derive(uniffi::Enum)]
pub enum ConnectionStatus {
    /// start() ran; cap loops are dialing.
    Connecting,
    /// A reconnect attempt failed while nothing is live — carries the OS/
    /// transport reason (e.g. "No route to host", "Connection refused").
    ReachabilityFailed {
        reason: String,
    },
}

/// Kotlin-implemented observer the core notifies on connection-state changes
/// so the UI can show a live indicator without polling.
#[uniffi::export(with_foreign)]
pub trait AgentObserver: Send + Sync {
    /// Called when the agent transitions between "at least one capability
    /// connection live" and "none live". Runs on the agent's runtime thread —
    /// implementations must not block (post to the main thread if needed).
    fn on_state_changed(&self, connected: bool);

    /// Progress detail between transitions; may fire often (once per failed
    /// cap attempt) — implementations should conflate.
    fn on_status(&self, status: ConnectionStatus);
}

// ---------- foreign traits: Kotlin implements, Rust pulls ----------

/// Backend for `device.battery` — read by Rust on a host request.
#[uniffi::export(with_foreign)]
pub trait BatteryProvider: Send + Sync {
    fn level_percent(&self) -> u8;
    fn is_charging(&self) -> bool;
    fn temperature_c(&self) -> f32;
}

/// Backend for `device.geo` — Rust calls `last_known` on a host request;
/// slow fixes arrive via `Agent::push_geo_update` instead.
#[uniffi::export(with_foreign)]
pub trait LocationProvider: Send + Sync {
    fn last_known(&self) -> Option<Location>;
}

/// Backend for `device.clipboard` — read + write.
#[uniffi::export(with_foreign)]
pub trait ClipboardProvider: Send + Sync {
    fn read(&self) -> Option<String>;
    fn write(&self, text: String);
}

/// Backend for `device.contacts` — query-filtered list. `limit` comes from
/// the host's request (0 = provider default); the Rust dispatcher hard-caps
/// it, and the provider must not return more rows than asked.
#[uniffi::export(with_foreign)]
pub trait ContactsProvider: Send + Sync {
    fn list(&self, query: String, limit: u32) -> Vec<Contact>;
}

/// Output for `device.speaker` — Rust hands decoded PCM (s16le mono, 16 kHz)
/// to Kotlin's AudioTrack.
#[uniffi::export(with_foreign)]
pub trait SpeakerSink: Send + Sync {
    fn play_pcm(&self, pcm: Vec<u8>);
}
