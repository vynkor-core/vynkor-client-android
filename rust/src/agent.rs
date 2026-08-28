//! The `Agent` object — Kotlin's handle on the Rust core.
//!
//! Owns a dedicated tokio runtime on a background thread, one reconnect loop
//! per capability (each a `CapConn` to the host), the capability-provider
//! slots, and the Kotlin→Rust push paths. No Android APIs here — Kotlin
//! implements the foreign traits (ffi.rs), Rust runs the protocol.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{RecvTimeoutError, Sender as StdSender};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use prost::Message;
use tokio::sync::{mpsc, watch};
use vynkor_wire::framing::FLAG_RAW_BINARY;
use vynkor_wire::proto::vynkor::{envelope, ActionRequest, ActionStatus, Envelope};

use crate::caps;
use crate::error::AgentError;
use crate::ffi::{
    ActionReply, ActionReplyStatus, AgentConfig, AgentObserver, BatteryProvider,
    BluetoothProvider, BrightnessProvider, CalendarProvider, CallsProvider, ClipboardProvider,
    ConnectionStatus, ContactsProvider, DeviceInfoProvider, DndProvider, FlashlightProvider,
    LauncherProvider, Location, LocationProvider, RingerProvider, SmsProvider, SpeakerSink,
    WifiProvider,
};
use crate::protocol::{build_frame, check_payload_size, is_kernel_routed, target_str, Frame};
use crate::transport::{CapConn, RegisterParams, BACKOFF_INITIAL, BACKOFF_MAX};

/// Capability that carries outbound request/response traffic (target "kernel",
/// routed by action name). It has no host→device actions of its own.
pub const CHAT_CAP: &str = "chat";

/// Extra headroom over a request's timeout so the kernel's own terminal
/// `ACTION_TIMEOUT` response can arrive instead of the device racing it.
const REQUEST_TIMEOUT_MARGIN: Duration = Duration::from_secs(5);

/// Device→host liveness ping cadence; a host that sends nothing for two
/// intervals is treated as dead (half-open TCP detection).
const PING_INTERVAL: Duration = Duration::from_secs(20);

/// Read deadline: no inbound frame for this long ⇒ tear the connection down
/// and reconnect, instead of hanging on a silently-lost TCP peer forever.
const READ_DEADLINE: Duration = Duration::from_secs(40);

/// A capability session that survived at least this long was healthy; its end
/// resets the reconnect backoff instead of leaving it parked at the maximum.
const BACKOFF_RESET_AFTER: Duration = Duration::from_secs(60);

/// Lifecycle state; `is_connected()` is the only part visible to Kotlin.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentState {
    Stopped,
    Starting,
    Connected,
    Disconnected,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| {
        // №52: recovery from a poisoned lock is deliberate (a panicking
        // observer must not brick the agent), but data consistency under the
        // mutex is no longer guaranteed — say so in the log.
        tracing::warn!("poisoned lock recovered; guarded state may be inconsistent");
        p.into_inner()
    })
}

/// Frames the push paths queue onto a live connection; the per-cap write loop
/// drains the channel and MACs each frame with the session key.
#[derive(Debug)]
pub struct Outbound {
    pub frame: Frame,
}

/// Rust-owned agent: created by Kotlin, then capability providers (foreign
/// traits) are registered and the lifecycle is driven from Kotlin.
#[derive(uniffi::Object)]
pub struct Agent {
    config: AgentConfig,
    state: Mutex<AgentState>,
    shutdown: AtomicBool,
    stop_tx: Mutex<Option<watch::Sender<bool>>>,
    runtime: Mutex<Option<JoinHandle<()>>>,
    battery: Mutex<Option<Arc<dyn BatteryProvider>>>,
    location: Mutex<Option<Arc<dyn LocationProvider>>>,
    clipboard: Mutex<Option<Arc<dyn ClipboardProvider>>>,
    contacts: Mutex<Option<Arc<dyn ContactsProvider>>>,
    speaker: Mutex<Option<Arc<dyn SpeakerSink>>>,
    device_info: Mutex<Option<Arc<dyn DeviceInfoProvider>>>,
    wifi: Mutex<Option<Arc<dyn WifiProvider>>>,
    bluetooth: Mutex<Option<Arc<dyn BluetoothProvider>>>,
    dnd: Mutex<Option<Arc<dyn DndProvider>>>,
    ringer: Mutex<Option<Arc<dyn RingerProvider>>>,
    brightness: Mutex<Option<Arc<dyn BrightnessProvider>>>,
    flashlight: Mutex<Option<Arc<dyn FlashlightProvider>>>,
    launcher: Mutex<Option<Arc<dyn LauncherProvider>>>,
    sms: Mutex<Option<Arc<dyn SmsProvider>>>,
    calls: Mutex<Option<Arc<dyn CallsProvider>>>,
    calendar: Mutex<Option<Arc<dyn CalendarProvider>>>,
    /// live outbound channels per capability, for the push paths
    caps: Mutex<HashMap<String, mpsc::Sender<Outbound>>>,
    live: AtomicUsize,
    /// in-flight outbound requests, keyed by action_id; the inbound dispatch
    /// loop resolves these when the correlated ActionResponse arrives
    pending: Mutex<HashMap<String, StdSender<ActionReply>>>,
    action_seq: AtomicU64,
    /// outbound frames dropped because a per-cap queue was full or an
    /// oversized payload was rejected; logged and reset at session teardown
    dropped_outbound: AtomicU64,
    observer: Mutex<Option<Arc<dyn AgentObserver>>>,
}

#[uniffi::export]
impl Agent {
    #[uniffi::constructor]
    pub fn new(config: AgentConfig) -> Self {
        init_tracing();
        Self {
            config,
            state: Mutex::new(AgentState::Stopped),
            shutdown: AtomicBool::new(false),
            stop_tx: Mutex::new(None),
            runtime: Mutex::new(None),
            battery: Mutex::new(None),
            location: Mutex::new(None),
            clipboard: Mutex::new(None),
            contacts: Mutex::new(None),
            speaker: Mutex::new(None),
            device_info: Mutex::new(None),
            wifi: Mutex::new(None),
            bluetooth: Mutex::new(None),
            dnd: Mutex::new(None),
            ringer: Mutex::new(None),
            brightness: Mutex::new(None),
            flashlight: Mutex::new(None),
            launcher: Mutex::new(None),
            sms: Mutex::new(None),
            calls: Mutex::new(None),
            calendar: Mutex::new(None),
            caps: Mutex::new(HashMap::new()),
            live: AtomicUsize::new(0),
            pending: Mutex::new(HashMap::new()),
            action_seq: AtomicU64::new(0),
            dropped_outbound: AtomicU64::new(0),
            observer: Mutex::new(None),
        }
    }

    // ---- lifecycle ----

    /// Connect to the host: spawn the tokio runtime + one reconnect loop per
    /// capability on a background thread. Returns immediately.
    pub fn start(self: Arc<Self>) {
        if *lock(&self.state) != AgentState::Stopped {
            tracing::warn!("agent already running");
            return;
        }
        // R-07: stop() latches this flag; without resetting it a restarted
        // agent would exit every cap loop on its first iteration.
        self.shutdown.store(false, Ordering::SeqCst);
        // Register the stop channel BEFORE spawning: stop() right after
        // start() must always find a sender, or join() would hang forever.
        let (stop_tx, stop_rx) = watch::channel(false);
        *lock(&self.stop_tx) = Some(stop_tx);
        let me = Arc::clone(&self);
        let handle = std::thread::Builder::new()
            .name("vynkor-agent".into())
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .worker_threads(4) // R-08: headroom so one slow foreign call can't starve the rest
                    .thread_name("vynkor-agent-rt")
                    .build()
                    .expect("tokio runtime");
                rt.block_on(me.run(stop_rx));
            })
            .expect("spawn agent thread");
        *lock(&self.runtime) = Some(handle);
        *lock(&self.state) = AgentState::Starting;
        self.notify_status(ConnectionStatus::Connecting);
    }

    /// Gracefully stop all connections and the runtime thread.
    pub fn stop(&self) {
        self.shutdown.store(true, Ordering::SeqCst);
        if let Some(tx) = lock(&self.stop_tx).take() {
            let _ = tx.send(true);
        }
        if let Some(handle) = lock(&self.runtime).take() {
            let _ = handle.join();
        }
        self.purge_pending();
        *lock(&self.state) = AgentState::Stopped;
    }

    pub fn is_connected(&self) -> bool {
        self.live.load(Ordering::Relaxed) > 0
    }

    /// True once `start()` has been called (regardless of connection state).
    pub fn is_started(&self) -> bool {
        *lock(&self.state) != AgentState::Stopped
    }

    // ---- capability providers (Kotlin implements, Rust pulls) ----

    pub fn set_battery(&self, p: Arc<dyn BatteryProvider>) {
        *lock(&self.battery) = Some(p);
    }

    pub fn set_location(&self, p: Arc<dyn LocationProvider>) {
        *lock(&self.location) = Some(p);
    }

    pub fn set_clipboard(&self, p: Arc<dyn ClipboardProvider>) {
        *lock(&self.clipboard) = Some(p);
    }

    pub fn set_contacts(&self, p: Arc<dyn ContactsProvider>) {
        *lock(&self.contacts) = Some(p);
    }

    pub fn set_speaker(&self, p: Arc<dyn SpeakerSink>) {
        *lock(&self.speaker) = Some(p);
    }

    pub fn set_device_info(&self, p: Arc<dyn DeviceInfoProvider>) {
        *lock(&self.device_info) = Some(p);
    }

    pub fn set_wifi(&self, p: Arc<dyn WifiProvider>) {
        *lock(&self.wifi) = Some(p);
    }

    pub fn set_bluetooth(&self, p: Arc<dyn BluetoothProvider>) {
        *lock(&self.bluetooth) = Some(p);
    }

    pub fn set_dnd(&self, p: Arc<dyn DndProvider>) {
        *lock(&self.dnd) = Some(p);
    }

    pub fn set_ringer(&self, p: Arc<dyn RingerProvider>) {
        *lock(&self.ringer) = Some(p);
    }

    pub fn set_brightness(&self, p: Arc<dyn BrightnessProvider>) {
        *lock(&self.brightness) = Some(p);
    }

    pub fn set_flashlight(&self, p: Arc<dyn FlashlightProvider>) {
        *lock(&self.flashlight) = Some(p);
    }

    pub fn set_launcher(&self, p: Arc<dyn LauncherProvider>) {
        *lock(&self.launcher) = Some(p);
    }

    pub fn set_sms(&self, p: Arc<dyn SmsProvider>) {
        *lock(&self.sms) = Some(p);
    }

    pub fn set_calls(&self, p: Arc<dyn CallsProvider>) {
        *lock(&self.calls) = Some(p);
    }

    pub fn set_calendar(&self, p: Arc<dyn CalendarProvider>) {
        *lock(&self.calendar) = Some(p);
    }

    pub fn set_observer(&self, o: Arc<dyn AgentObserver>) {
        *lock(&self.observer) = Some(o);
    }

    /// Send an outbound `ActionRequest` to `target` ("kernel" for action-name
    /// routing, or a specific plugin id) and block until the correlated
    /// `ActionResponse` arrives or `timeout_ms` elapses. Call from a
    /// background thread — never the Android main thread (the reply can take
    /// tens of seconds).
    pub fn request(
        &self,
        target: String,
        action: String,
        params_json: Vec<u8>,
        timeout_ms: u32,
    ) -> ActionReply {
        let action_id = self.next_action_id();
        let (tx, rx) = std::sync::mpsc::channel::<ActionReply>();
        {
            let mut pending = lock(&self.pending);
            pending.insert(action_id.clone(), tx);
        }

        let req = ActionRequest {
            action_id: action_id.clone(),
            action,
            params_json,
            timeout_ms,
            streaming: false,
            caller_plugin_id: String::new(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::ActionRequest(req)),
            ..Default::default()
        };
        let mut payload = Vec::new();
        if env.encode(&mut payload).is_err() {
            self.take_pending(&action_id);
            return ActionReply {
                status: ActionReplyStatus::Local,
                data_json: Vec::new(),
                error: "encode request".into(),
            };
        }
        // R-05: an oversized frame would be rejected by the gateway mid-flight
        // and tear the connection down; fail locally instead.
        if let Err(e) = check_payload_size(&payload) {
            self.take_pending(&action_id);
            return ActionReply {
                status: ActionReplyStatus::Local,
                data_json: Vec::new(),
                error: format!("request rejected: {e}"),
            };
        }

        let Some(out) = self.live_channel(CHAT_CAP) else {
            self.take_pending(&action_id);
            return ActionReply {
                status: ActionReplyStatus::Local,
                data_json: Vec::new(),
                error: "no live chat connection to host".into(),
            };
        };
        if out
            .try_send(Outbound {
                frame: build_frame(&target, 0, payload),
            })
            .is_err()
        {
            self.take_pending(&action_id);
            return ActionReply {
                status: ActionReplyStatus::Local,
                data_json: Vec::new(),
                error: "send queue full".into(),
            };
        }

        let timeout = if timeout_ms == 0 {
            Duration::from_secs(30) + REQUEST_TIMEOUT_MARGIN
        } else {
            Duration::from_millis(timeout_ms as u64) + REQUEST_TIMEOUT_MARGIN
        };
        match rx.recv_timeout(timeout) {
            Ok(reply) => reply,
            Err(RecvTimeoutError::Timeout) => {
                self.take_pending(&action_id);
                ActionReply {
                    status: ActionReplyStatus::Timeout,
                    data_json: Vec::new(),
                    error: "request timed out".into(),
                }
            }
            Err(RecvTimeoutError::Disconnected) => ActionReply {
                status: ActionReplyStatus::Local,
                data_json: Vec::new(),
                error: "connection dropped".into(),
            },
        }
    }

    // ---- Kotlin -> Rust push paths (event-driven capabilities) ----

    /// PCM from the mic (Kotlin `AudioRecord`, 16 kHz mono s16le). v1: PCM
    /// passthrough (`AUDIO_CODEC_PCM_S16LE`); Opus encode is a follow-up.
    pub fn push_mic_pcm(&self, pcm: Vec<u8>) {
        let Some(tx) = self.live_channel("mic") else {
            tracing::warn!("mic: no live connection, dropping {} bytes", pcm.len());
            return;
        };
        let chunk = vynkor_wire::proto::vynkor::AudioStreamChunk {
            stream_id: 0,
            codec: vynkor_wire::proto::vynkor::AudioCodec::PcmS16le as i32,
            sample_rate: 16_000,
            channels: 1,
            data: pcm,
            end_of_stream: false,
        };
        let env = Envelope {
            payload: Some(envelope::Payload::AudioStreamChunk(chunk)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "stt");
    }

    /// A notification arrived (Kotlin `NotificationListenerService`) — publish
    /// it as a device event on the host.
    pub fn on_notification(&self, app: String, title: String, body: String) {
        let Some(tx) = self.live_channel("notifications") else {
            tracing::warn!("notifications: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({ "app": app, "title": title, "body": body });
        let ev = vynkor_wire::proto::vynkor::EventPublish {
            event_type: "notification".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }

    /// The clipboard changed on the device — publish as a device event.
    pub fn on_clipboard_change(&self, text: String) {
        let Some(tx) = self.live_channel("clipboard") else {
            tracing::warn!("clipboard: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({ "text": text });
        let ev = vynkor_wire::proto::vynkor::EventPublish {
            event_type: "clipboard_changed".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }

    /// Battery level/charging changed (Kotlin debounces the raw
    /// ACTION_BATTERY_CHANGED spam) — publish as a device event so the host
    /// sees telemetry without polling `device.battery`.
    pub fn push_battery_status(&self, level_percent: u8, charging: bool) {
        let Some(tx) = self.live_channel("battery") else {
            tracing::warn!("battery: no live connection, dropping event");
            return;
        };
        let payload = serde_json::json!({
            "level_percent": level_percent,
            "charging": charging,
        });
        let ev = vynkor_wire::proto::vynkor::EventPublish {
            event_type: "battery_status".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }

    /// A slow location fix is ready (push path) — publish as a device event.
    pub fn push_geo_update(&self, loc: Location) {
        let Some(tx) = self.live_channel("geo") else {
            tracing::warn!("geo: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({
            "lat": loc.lat,
            "lon": loc.lon,
            "accuracy_m": loc.accuracy_m,
        });
        let ev = vynkor_wire::proto::vynkor::EventPublish {
            event_type: "geo_update".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }
}

impl Agent {
    fn register_params(&self, cap: &str) -> RegisterParams {
        RegisterParams {
            device_id: self.config.device_id.clone(),
            cap: cap.to_string(),
            jwt_token: self.config.jwt_token.clone(),
            device_secret: (!self.config.device_secret.is_empty())
                .then(|| self.config.device_secret.clone()),
            cert_pem: (!self.config.cert_pem.is_empty()).then(|| self.config.cert_pem.clone()),
            os_version: self.config.os_version.clone(),
            arch: self.config.arch.clone(),
            user_id: self.config.user_id.clone(),
        }
    }

    async fn run(self: Arc<Self>, mut stop_rx: watch::Receiver<bool>) {
        for cap in self.config.capabilities.clone() {
            spawn_cap_loop(Arc::clone(&self), cap, stop_rx.clone());
        }
        // keep the runtime alive until stop(); cap loops exit via the watch
        while !*stop_rx.borrow() {
            if stop_rx.changed().await.is_err() {
                break;
            }
        }
    }

    fn live_channel(&self, cap: &str) -> Option<mpsc::Sender<Outbound>> {
        lock(&self.caps).get(cap).cloned()
    }

    fn next_action_id(&self) -> String {
        let seq = self.action_seq.fetch_add(1, Ordering::Relaxed);
        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        format!("act-{ts}-{seq}")
    }

    fn take_pending(&self, action_id: &str) -> Option<StdSender<ActionReply>> {
        lock(&self.pending).remove(action_id)
    }

    fn notify_state(&self, connected: bool) {
        let Some(observer) = lock(&self.observer).clone() else {
            return;
        };
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            observer.on_state_changed(connected);
        }));
    }

    fn notify_status(&self, status: ConnectionStatus) {
        let Some(observer) = lock(&self.observer).clone() else {
            return;
        };
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            observer.on_status(status);
        }));
    }

    /// Queue one frame onto a live cap connection, accounting drops.
    /// Returns false when the frame was not queued. Never blocks: a full
    /// queue drops the frame (R-09) instead of stalling the push path.
    fn enqueue_outbound(&self, tx: &mpsc::Sender<Outbound>, frame: Frame) -> bool {
        if let Err(e) = check_payload_size(&frame.payload) {
            let total = self.dropped_outbound.fetch_add(1, Ordering::Relaxed) + 1;
            tracing::warn!(total_dropped = total, error = %e, "outbound payload too large, dropping");
            return false;
        }
        match tx.try_send(Outbound { frame }) {
            Ok(()) => true,
            Err(e) => {
                let total = self.dropped_outbound.fetch_add(1, Ordering::Relaxed) + 1;
                tracing::warn!(total_dropped = total, error = %e, "outbound queue full, dropping frame");
                false
            }
        }
    }

    /// Drop all in-flight request waiters. Called when the connection dies
    /// and on stop(): blocked `request()` callers wake up immediately with
    /// `Disconnected` instead of waiting out their full timeout.
    fn purge_pending(&self) {
        let mut pending = lock(&self.pending);
        if pending.is_empty() {
            return;
        }
        let n = pending.len();
        pending.clear();
        tracing::info!(purged = n, "pending requests released");
    }

    fn send_raw_frame(&self, tx: &mpsc::Sender<Outbound>, env: Envelope, target: &str) {
        let mut payload = Vec::new();
        if env.encode(&mut payload).is_err() {
            tracing::error!(target, "encode push frame");
            return;
        }
        let frame = build_frame(target, 0, payload);
        self.enqueue_outbound(tx, frame);
    }

    // ---- provider accessors (caps dispatch) ----

    pub(crate) fn battery_provider(&self) -> Option<Arc<dyn BatteryProvider>> {
        lock(&self.battery).clone()
    }

    pub(crate) fn location_provider(&self) -> Option<Arc<dyn LocationProvider>> {
        lock(&self.location).clone()
    }

    pub(crate) fn clipboard_provider(&self) -> Option<Arc<dyn ClipboardProvider>> {
        lock(&self.clipboard).clone()
    }

    pub(crate) fn contacts_provider(&self) -> Option<Arc<dyn ContactsProvider>> {
        lock(&self.contacts).clone()
    }

    pub(crate) fn speaker_provider(&self) -> Option<Arc<dyn SpeakerSink>> {
        lock(&self.speaker).clone()
    }

    pub(crate) fn device_info_provider(&self) -> Option<Arc<dyn DeviceInfoProvider>> {
        lock(&self.device_info).clone()
    }

    pub(crate) fn wifi_provider(&self) -> Option<Arc<dyn WifiProvider>> {
        lock(&self.wifi).clone()
    }

    pub(crate) fn bluetooth_provider(&self) -> Option<Arc<dyn BluetoothProvider>> {
        lock(&self.bluetooth).clone()
    }

    pub(crate) fn dnd_provider(&self) -> Option<Arc<dyn DndProvider>> {
        lock(&self.dnd).clone()
    }

    pub(crate) fn ringer_provider(&self) -> Option<Arc<dyn RingerProvider>> {
        lock(&self.ringer).clone()
    }

    pub(crate) fn brightness_provider(&self) -> Option<Arc<dyn BrightnessProvider>> {
        lock(&self.brightness).clone()
    }

    pub(crate) fn flashlight_provider(&self) -> Option<Arc<dyn FlashlightProvider>> {
        lock(&self.flashlight).clone()
    }

    pub(crate) fn launcher_provider(&self) -> Option<Arc<dyn LauncherProvider>> {
        lock(&self.launcher).clone()
    }

    pub(crate) fn sms_provider(&self) -> Option<Arc<dyn SmsProvider>> {
        lock(&self.sms).clone()
    }

    pub(crate) fn calls_provider(&self) -> Option<Arc<dyn CallsProvider>> {
        lock(&self.calls).clone()
    }

    pub(crate) fn calendar_provider(&self) -> Option<Arc<dyn CalendarProvider>> {
        lock(&self.calendar).clone()
    }

    // ---- inbound dispatch ----

    /// Handle one host→device frame on a capability connection. Provider
    /// calls (UniFFI → JVM) and speaker playback run on blocking threads so a
    /// slow capability can never stall this read loop (R-08).
    async fn dispatch_inbound(
        self: &Arc<Self>,
        frame: &Frame,
        cap: &str,
        out: &mpsc::Sender<Outbound>,
    ) {
        if frame.flags & FLAG_RAW_BINARY != 0 {
            let agent = Arc::clone(self);
            let payload = frame.payload.as_ref().to_vec();
            let cap_owned = cap.to_string();
            tokio::task::spawn_blocking(move || {
                crate::caps::audio::handle_raw_inbound(&agent, &payload, &cap_owned);
            });
            return;
        }
        let Ok(env) = Envelope::decode(frame.payload.as_ref()) else {
            tracing::warn!(cap, target = %target_str(frame), "undecodable inbound frame");
            return;
        };
        match env.payload {
            Some(envelope::Payload::Ping(p)) => {
                let pong = vynkor_wire::proto::vynkor::Pong {
                    original_timestamp: p.timestamp,
                    ..Default::default()
                };
                let resp = Envelope {
                    message_id: env.message_id,
                    payload: Some(envelope::Payload::Pong(pong)),
                    ..Default::default()
                };
                self.reply(out, resp);
            }
            Some(envelope::Payload::ActionRequest(req)) => {
                let agent = Arc::clone(self);
                let out = out.clone();
                let cap_name = cap.to_string();
                tokio::task::spawn_blocking(move || {
                    let resp_env = caps::handle_action_request(&agent, &cap_name, req);
                    agent.reply(&out, resp_env);
                });
            }
            Some(envelope::Payload::ActionResponse(resp)) => {
                if let Some(tx) = self.take_pending(&resp.action_id) {
                    tracing::info!(
                        action_id = %resp.action_id,
                        status = resp.status,
                        data_len = resp.data_json.len(),
                        error = %resp.error,
                        "action response resolved"
                    );
                    let _ = tx.send(ActionReply {
                        status: map_action_status(resp.status),
                        data_json: resp.data_json,
                        error: resp.error,
                    });
                } else {
                    tracing::warn!(
                        action_id = %resp.action_id,
                        "unsolicited action response, dropping"
                    );
                }
            }
            Some(envelope::Payload::SessionClose(_)) => {
                tracing::info!(cap, "host closed session");
            }
            _ => {
                tracing::trace!(cap, target = %target_str(frame), "unhandled inbound frame");
            }
        }
    }

    fn reply(&self, out: &mpsc::Sender<Outbound>, env: Envelope) {
        let mut payload = Vec::new();
        if env.encode(&mut payload).is_err() {
            return;
        }
        let frame = build_frame("kernel", 0, payload);
        self.enqueue_outbound(out, frame);
    }
}

fn spawn_cap_loop(agent: Arc<Agent>, cap: String, stop_rx: watch::Receiver<bool>) {
    tokio::spawn(async move {
        cap_loop(agent, cap, stop_rx).await;
    });
}

fn map_action_status(status: i32) -> ActionReplyStatus {
    match status {
        s if s == ActionStatus::ActionOk as i32 => ActionReplyStatus::Ok,
        s if s == ActionStatus::ActionError as i32 => ActionReplyStatus::Error,
        s if s == ActionStatus::ActionTimeout as i32 => ActionReplyStatus::Timeout,
        s if s == ActionStatus::ActionPermissionDeny as i32 => ActionReplyStatus::PermissionDenied,
        s if s == ActionStatus::ActionNotFound as i32 => ActionReplyStatus::NotFound,
        s if s == ActionStatus::ActionQuotaExceeded as i32 => ActionReplyStatus::QuotaExceeded,
        s if s == ActionStatus::ActionStreamBackpressure as i32 => {
            ActionReplyStatus::StreamBackpressure
        }
        _ => ActionReplyStatus::Error,
    }
}

/// Install the tracing subscriber once (no-op on repeat calls) so protocol
/// logs reach logcat. Android has no default tracing writer; tracing-android
/// routes events to `__android_log_write` (visible via `adb logcat`). On the
/// host the events go to stderr instead.
fn init_tracing() {
    use std::sync::Once;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("debug"));
        let subscriber = tracing_subscriber::registry().with(filter);
        #[cfg(target_os = "android")]
        if let Ok(layer) = tracing_android::layer("vynkor") {
            let _ = subscriber.with(layer).try_init();
        }
        #[cfg(not(target_os = "android"))]
        let _ = subscriber
            .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr))
            .try_init();
    });
}

/// One capability's reconnect loop: connect → register → read/write until the
/// connection dies or the agent shuts down, then backoff and retry. A session
/// that lived ≥ [BACKOFF_RESET_AFTER] counts as healthy and resets the backoff
/// (R-06: no more permanent 30 s stalls after a burst of flaps).
async fn cap_loop(agent: Arc<Agent>, cap: String, mut stop_rx: watch::Receiver<bool>) {
    let mut backoff = BACKOFF_INITIAL;
    loop {
        if agent.shutdown.load(Ordering::SeqCst) || *stop_rx.borrow() {
            return;
        }
        let started = tokio::time::Instant::now();
        match one_cycle(agent.clone(), cap.clone()).await {
            Ok(()) => tracing::info!(cap, "connection closed"),
            Err(AgentError::Shutdown) => return,
            Err(e) => {
                tracing::warn!(cap, error = %e, "connection failed");
                agent.notify_status(ConnectionStatus::ReachabilityFailed {
                    reason: e.to_string(),
                });
            }
        }
        backoff = next_backoff(backoff, started.elapsed());
        // poll shutdown during the backoff sleep so stop() is responsive
        let deadline = tokio::time::Instant::now() + backoff;
        loop {
            tokio::select! {
                _ = tokio::time::sleep_until(deadline) => break,
                changed = stop_rx.changed() => {
                    if changed.is_err() || *stop_rx.borrow() { return; }
                }
            }
        }
    }
}

/// Pure decision helper: how long to wait before the next reconnect attempt.
fn next_backoff(current: Duration, session_lived: Duration) -> Duration {
    if session_lived >= BACKOFF_RESET_AFTER {
        BACKOFF_INITIAL
    } else {
        (current * 2).min(BACKOFF_MAX)
    }
}

/// One connect-register-loop pass for a capability.
async fn one_cycle(agent: Arc<Agent>, cap: String) -> Result<(), AgentError> {
    let params = agent.register_params(&cap);
    let conn = CapConn::connect_and_register(&agent.config.host_url, &params).await?;
    let session_key = conn.session_key();
    let (mut read, mut write, _) = conn.into_parts();

    let (out_tx, out_rx) = mpsc::channel::<Outbound>(64);
    {
        let mut caps = lock(&agent.caps);
        caps.insert(cap.clone(), out_tx.clone());
    }
    agent.dropped_outbound.store(0, Ordering::Relaxed);
    let prev_live = agent.live.fetch_add(1, Ordering::Relaxed);
    *lock(&agent.state) = AgentState::Connected;
    if prev_live == 0 {
        agent.notify_state(true);
    }

    // write loop: drains push-path + reply frames, MACs, sends
    let write_task = tokio::spawn(async move {
        use futures_util::SinkExt;
        let mut rx = out_rx;
        while let Some(Outbound { mut frame }) = rx.recv().await {
            if let Some(key) = &session_key {
                crate::protocol::arm_mac(&mut frame, key);
            }
            if write
                .send(tokio_tungstenite::tungstenite::Message::Binary(
                    crate::protocol::frame_to_bytes(&frame).into(),
                ))
                .await
                .is_err()
            {
                break;
            }
        }
    });

    // device→host liveness ping (R-06): keeps half-open TCP detectable by the
    // read deadline even when neither side has user traffic
    let ping_task = spawn_ping_task(out_tx.clone());

    let result = loop {
        use futures_util::StreamExt;
        let msg = match tokio::time::timeout(READ_DEADLINE, read.next()).await {
            Err(_elapsed) => break Err(AgentError::Ws("read deadline exceeded".into())),
            Ok(m) => m,
        };
        let frame = match msg {
            Some(Ok(tokio_tungstenite::tungstenite::Message::Binary(data))) => {
                let mut f = match crate::protocol::parse_frame(&data) {
                    Ok(f) => f,
                    Err(e) => break Err(e),
                };
                if let Err(e) = crate::protocol::verify_inbound(&mut f, session_key.as_ref()) {
                    break Err(e);
                }
                f
            }
            Some(Ok(tokio_tungstenite::tungstenite::Message::Close(_))) | None => {
                break Err(AgentError::Ws("websocket closed".into()));
            }
            Some(Ok(_)) => continue,
            Some(Err(e)) => break Err(AgentError::from(e)),
        };
        if is_kernel_routed(&frame) {
            agent.dispatch_inbound(&frame, &cap, &out_tx).await;
        } else {
            tracing::trace!(cap, target = %target_str(&frame), "device-traffic frame");
        }
    };

    write_task.abort();
    ping_task.abort();
    let prev_live = agent.live.fetch_sub(1, Ordering::Relaxed);
    lock(&agent.caps).remove(&cap);
    agent.purge_pending();
    let dropped = agent.dropped_outbound.swap(0, Ordering::Relaxed);
    if dropped > 0 {
        tracing::warn!(cap, dropped, "outbound frames dropped during session");
    }
    if prev_live == 1 {
        *lock(&agent.state) = AgentState::Disconnected;
        agent.notify_state(false);
    }
    result
}

/// Periodic Ping envelopes onto a live connection; exits when the outbound
/// queue dies (session over) and never blocks on a full queue.
fn spawn_ping_task(out_tx: mpsc::Sender<Outbound>) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut ticker = tokio::time::interval(PING_INTERVAL);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        ticker.tick().await; // Interval fires immediately once; skip that
        loop {
            ticker.tick().await;
            let ts = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            let env = Envelope {
                payload: Some(envelope::Payload::Ping(vynkor_wire::proto::vynkor::Ping {
                    timestamp: ts,
                })),
                ..Default::default()
            };
            let mut payload = Vec::new();
            if env.encode(&mut payload).is_err() {
                continue;
            }
            match out_tx.try_send(Outbound {
                frame: build_frame("kernel", 0, payload),
            }) {
                Ok(()) => {}
                Err(mpsc::error::TrySendError::Full(_)) => continue,
                Err(mpsc::error::TrySendError::Closed(_)) => return,
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use vynkor_wire::framing::MAX_PAYLOAD_SIZE;
    use vynkor_wire::proto::vynkor::ActionStatus as Status;

    fn test_config() -> AgentConfig {
        AgentConfig {
            host_url: "ws://127.0.0.1:9".into(),
            jwt_token: String::new(),
            device_secret: String::new(),
            cert_pem: String::new(),
            device_id: "test-device".into(),
            capabilities: Vec::new(),
            os_version: "14".into(),
            arch: "x86_64".into(),
            user_id: "default".into(),
        }
    }

    #[test]
    fn restart_after_stop_resets_shutdown_flag() {
        let agent = Arc::new(Agent::new(test_config()));
        Arc::clone(&agent).start();
        agent.stop();
        assert!(agent.shutdown.load(Ordering::SeqCst));
        assert!(matches!(*lock(&agent.state), AgentState::Stopped));

        Arc::clone(&agent).start();
        assert!(
            !agent.shutdown.load(Ordering::SeqCst),
            "start() must clear the latched shutdown flag (R-07)"
        );
        agent.stop();
        assert!(matches!(*lock(&agent.state), AgentState::Stopped));
    }

    #[test]
    fn purge_pending_releases_waiters() {
        let agent = Arc::new(Agent::new(test_config()));
        let (tx, rx) = std::sync::mpsc::channel::<ActionReply>();
        lock(&agent.pending).insert("act-1".into(), tx);

        agent.purge_pending();

        assert!(lock(&agent.pending).is_empty());
        // sender dropped → the blocked request() wakes up as Disconnected
        match rx.recv_timeout(Duration::from_millis(100)) {
            Err(RecvTimeoutError::Disconnected) => {}
            Err(RecvTimeoutError::Timeout) => panic!("sender still alive after purge"),
            Ok(_) => panic!("unexpected reply after purge"),
        }
    }

    #[test]
    fn backoff_grows_on_failures_and_resets_after_healthy_session() {
        let mut b = BACKOFF_INITIAL;
        for _ in 0..10 {
            b = next_backoff(b, Duration::ZERO);
        }
        assert_eq!(b, BACKOFF_MAX, "failures must cap at BACKOFF_MAX");

        // a long-lived session resets to initial
        assert_eq!(next_backoff(b, BACKOFF_RESET_AFTER), BACKOFF_INITIAL);
        // a short-lived session keeps growing
        assert_eq!(
            next_backoff(BACKOFF_INITIAL, Duration::from_secs(1)),
            Duration::from_secs(2)
        );
    }

    #[test]
    fn enqueue_rejects_oversized_payload_and_counts_drop() {
        let agent = Agent::new(test_config());
        let (tx, rx) = mpsc::channel::<Outbound>(1);
        drop(rx); // closed → try_send fails
        let big = build_frame("kernel", 0, vec![0u8; MAX_PAYLOAD_SIZE + 1]);
        assert!(!agent.enqueue_outbound(&tx, big));
        assert_eq!(agent.dropped_outbound.load(Ordering::Relaxed), 1);
    }

    #[test]
    fn maps_action_statuses() {
        assert!(matches!(
            map_action_status(Status::ActionOk as i32),
            ActionReplyStatus::Ok
        ));
        assert!(matches!(
            map_action_status(Status::ActionError as i32),
            ActionReplyStatus::Error
        ));
        assert!(matches!(
            map_action_status(Status::ActionTimeout as i32),
            ActionReplyStatus::Timeout
        ));
        assert!(matches!(
            map_action_status(Status::ActionPermissionDeny as i32),
            ActionReplyStatus::PermissionDenied
        ));
        assert!(matches!(
            map_action_status(Status::ActionNotFound as i32),
            ActionReplyStatus::NotFound
        ));
        assert!(matches!(
            map_action_status(Status::ActionQuotaExceeded as i32),
            ActionReplyStatus::QuotaExceeded
        ));
        assert!(matches!(
            map_action_status(Status::ActionStreamBackpressure as i32),
            ActionReplyStatus::StreamBackpressure
        ));
        assert!(matches!(map_action_status(999), ActionReplyStatus::Error));
    }
}
