//! UniFFI surface — the Kotlin <-> Rust boundary.
//!
//! Kotlin implements the foreign traits (device I/O); the `Agent` object
//! (agent.rs) is Rust-owned and exported there. This file holds the plain
//! data records and the foreign traits — the contract both sides compile
//! against. No protocol logic here.

/// Connection config. `device_secret` is the per-device credential issued by
/// the host at pair time (E-01) — never the host master `jwt_secret` — and is
/// the frame-MAC key input. On the device it is stored only inside the app's
/// encrypted profile store (Android Keystore, AES-GCM) and excluded from cloud
/// backups.
#[derive(uniffi::Record)]
pub struct AgentConfig {
    /// Host kernel WS endpoint, e.g. `wss://host:port/ws`.
    pub host_url: String,
    /// Device JWT (`sub = device_id`, restricted claims).
    pub jwt_token: String,
    /// Per-device secret issued by the host (E-01), for frame-MAC derivation.
    pub device_secret: String,
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
    ReachabilityFailed { reason: String },
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
    /// Why [last_known] has nothing (permission missing, location services
    /// off); None when a fix simply has not arrived yet.
    fn unavailable_reason(&self) -> Option<String>;
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
    /// Append a chunk of PCM (s16le mono 24 kHz) to the sink's accumulator.
    /// The sink decides when to play the accumulated buffer.
    fn append_pcm(&self, pcm: Vec<u8>, end_of_stream: bool);
    /// Force playback of whatever has accumulated so far, regardless of EOS.
    /// Used by tests and shutdown paths.
    fn flush(&self);
}

// ---------- extended device controls (Tier-1.5 capabilities) ----------

/// Static device facts behind `device.info`.
#[derive(uniffi::Record)]
pub struct DeviceInfo {
    pub model: String,
    pub manufacturer: String,
    pub brand: String,
    pub android_release: String,
    pub sdk_int: u16,
    pub locale: String,
    pub screen_width_px: u32,
    pub screen_height_px: u32,
}

#[uniffi::export(with_foreign)]
pub trait DeviceInfoProvider: Send + Sync {
    fn snapshot(&self) -> DeviceInfo;
}

/// Wi-Fi state + scan results behind `device.wifi`.
#[derive(uniffi::Record)]
pub struct WifiStatus {
    pub enabled: bool,
    /// Connected network SSID (quoted form stripped); empty when not connected.
    pub ssid: String,
    pub ip: String,
    pub link_speed_mbps: i32,
}

#[derive(uniffi::Record)]
pub struct WifiNetwork {
    pub ssid: String,
    pub bssid: String,
    /// Signal strength in dBm (negative; closer to 0 = stronger).
    pub rssi: i32,
    pub secure: bool,
}

#[uniffi::export(with_foreign)]
pub trait WifiProvider: Send + Sync {
    fn status(&self) -> Option<WifiStatus>;
    /// Fresh or last-known scan results; empty list = nothing visible.
    fn scan(&self) -> Vec<WifiNetwork>;
}

/// Bluetooth state + paired devices behind `device.bluetooth`.
#[derive(uniffi::Record)]
pub struct BluetoothStatus {
    pub enabled: bool,
    pub adapter_name: String,
}

#[derive(uniffi::Record)]
pub struct PairedBluetoothDevice {
    pub name: String,
    pub address: String,
    pub connected: bool,
}

#[uniffi::export(with_foreign)]
pub trait BluetoothProvider: Send + Sync {
    fn status(&self) -> Option<BluetoothStatus>;
    fn paired(&self) -> Vec<PairedBluetoothDevice>;
}

/// Do-not-disturb control behind `device.dnd`. Filter names:
/// "off" | "priority" | "alarms" | "none" (total silence) | "unknown".
#[uniffi::export(with_foreign)]
pub trait DndProvider: Send + Sync {
    fn filter(&self) -> String;
    /// Returns false when the device refuses (no policy access granted).
    fn set_filter(&self, mode: String) -> bool;
}

/// Ringer mode behind `device.ringer`: "normal" | "silent" | "vibrate".
#[uniffi::export(with_foreign)]
pub trait RingerProvider: Send + Sync {
    fn mode(&self) -> String;
    fn set_mode(&self, mode: String) -> bool;
}

/// Screen brightness behind `device.brightness`. Level is the raw Android
/// 0..=255 scale; auto = adaptive brightness.
#[uniffi::export(with_foreign)]
pub trait BrightnessProvider: Send + Sync {
    fn level(&self) -> Option<u8>;
    fn auto(&self) -> bool;
    fn set_level(&self, level: u8) -> bool;
    fn set_auto(&self, on: bool) -> bool;
}

/// Torch behind `device.flashlight`.
#[uniffi::export(with_foreign)]
pub trait FlashlightProvider: Send + Sync {
    fn available(&self) -> bool;
    fn is_on(&self) -> bool;
    fn set_on(&self, on: bool) -> bool;
}

/// An installed, launchable app.
#[derive(uniffi::Record)]
pub struct AppEntry {
    pub package_name: String,
    pub app_name: String,
}

/// App listing + launch behind `device.launcher`.
#[uniffi::export(with_foreign)]
pub trait LauncherProvider: Send + Sync {
    fn apps(&self) -> Vec<AppEntry>;
    /// Bring the app to the foreground; false = unknown package / no intent.
    fn launch(&self, package_name: String) -> bool;
}

/// One SMS message from the device inbox.
#[derive(uniffi::Record)]
pub struct SmsMessage {
    pub sender: String,
    pub body: String,
    pub timestamp_ms: i64,
}

/// Recent SMS behind `device.sms` — read-only, host-limited rows.
#[uniffi::export(with_foreign)]
pub trait SmsProvider: Send + Sync {
    fn inbox(&self, query: String, limit: u32) -> Vec<SmsMessage>;
}

/// One call-log entry.
#[derive(uniffi::Record)]
pub struct CallLogEntry {
    pub number: String,
    pub name: String,
    /// "incoming" | "outgoing" | "missed" | "rejected" | "other"
    pub call_type: String,
    pub timestamp_ms: i64,
    pub duration_s: u32,
}

/// Recent calls behind `device.calls` — read-only.
#[uniffi::export(with_foreign)]
pub trait CallsProvider: Send + Sync {
    fn recent(&self, limit: u32) -> Vec<CallLogEntry>;
}

/// A calendar occurrence.
#[derive(uniffi::Record)]
pub struct CalendarEvent {
    pub title: String,
    pub description: String,
    pub location: String,
    pub start_ms: i64,
    pub end_ms: i64,
    pub calendar_name: String,
}

#[derive(uniffi::Enum)]
pub enum CalendarWriteResult {
    /// Event inserted; carries its row id.
    Added { event_id: i64 },
    /// Provider refused: permission missing or the insert failed.
    Failed { reason: String },
}

/// Calendar behind `device.calendar` — read upcoming events, add new ones.
#[uniffi::export(with_foreign)]
pub trait CalendarProvider: Send + Sync {
    /// Events starting within the next `days_ahead` days, soonest first.
    fn upcoming(&self, days_ahead: u32, limit: u32) -> Vec<CalendarEvent>;
    fn add_event(
        &self,
        title: String,
        description: String,
        location: String,
        start_ms: i64,
        end_ms: i64,
    ) -> CalendarWriteResult;
}
