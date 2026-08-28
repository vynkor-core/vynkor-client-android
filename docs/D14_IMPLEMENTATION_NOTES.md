# D-14 Implementation Notes — vynkor Android Device-Agent

Live notes from building and E2E-testing the D-14 Android agent (2026-08-15 → 08-16).
Design docs: kernel repo, `docs/ANDROID_DEVICE_AGENT.md` + `docs/ANDROID_DEVICE_AGENT_RUST_CORE.md`.
This file records what the design docs don't: the build reality, tooling gotchas,
wire-level findings, and the exact test recipe that proved the agent works.

## Status

- **Rust core** (`rust/`, crate `vynkor-agent-core`): implemented, 17 unit tests green,
  `clippy -D warnings` + `fmt --check` clean.
- **Android app** (`app/`): Gradle module builds `app-debug.apk` with 3 ABIs.
- **E2E verified against a live kernel over LAN**: all 7 Tier-1 capabilities
  register from a real phone (`d14-test-phone.geo/.battery/.clipboard/.contacts/
  .notifications/.mic/.speaker`), device shows `state: online` in `/devices`.
- **Not yet done**: Opus codec for mic/speaker (PCM passthrough now), TLS cert
  pinning (webpki-roots now), live connection status in the UI.

---

## Architecture recap (what was built)

```
Kotlin (device I/O)  ── UniFFI ──▶  Rust core (protocol engine)  ── WS ──▶  host kernel
  AudioRecord              AgentConfig/Agent/          CapConn: tokio-tungstenite,
  BatteryManager           5 foreign traits            JWT subprotocol, HKDF MAC
  LocationManager          push paths (mic/notif/      register→ack→session_key
  ClipboardManager         clipboard/geo)              per-cap reconnect loop
  ContactsContract
  NotificationListener
```

- **Rust core = protocol only.** Framing/MAC/proto from `vynkor-wire` 0.2.3 (proto v1.6),
  used verbatim — no reimplementation of crypto.
- **One WS connection per capability**, each registering as `{device_id}.{cap}`
  (the D-14 naming decision; supersedes the D-06 bridge's literal `device.{cap}`).
- **Kotlin implements 5 foreign traits** (`BatteryProvider`, `LocationProvider`,
  `ClipboardProvider`, `ContactsProvider`, `SpeakerSink`); Rust pulls them on demand.
  Mic PCM and notifications are Kotlin→Rust push paths (`push_mic_pcm`,
  `on_notification`, `on_clipboard_change`, `push_geo_update`).

### Module map (rust/src)

| file | role |
|---|---|
| `error.rs` | `AgentError`: Connect/Wire/Ws/Register/Capability/Shutdown |
| `protocol.rs` | `build_frame`/`frame_to_bytes`/`parse_frame` (crc+magic+limit), `arm_mac`/`verify_inbound` (tag covers header **with** `FLAG_MAC_PRESENT` set), `is_kernel_routed` payload classification, `target_str`, `check_payload_size` |
| `transport.rs` | `CapConn`: WS connect (JWT in `Sec-WebSocket-Protocol`), register→ack→`derive_session_key` (HKDF, salt=nonce), split read/write halves, `resolve_ws_url` |
| `agent.rs` | `Agent` (UniFFI object): tokio runtime on a background thread, per-cap reconnect loop (backoff 1s→30s, watch-channel shutdown), inbound dispatch (Ping→Pong, ActionRequest→caps, SessionClose), push paths, provider accessors |
| `caps/mod.rs` | ActionRequest → provider dispatch: battery/geo/clipboard/contacts → JSON `ActionResponse` |
| `caps/audio.rs` | raw-binary audio: host TTS→speaker sink, mic chunk frame builder |
| `ffi.rs` | UniFFI surface: `AgentConfig`, `Agent`, 5 foreign traits, `Location`/`Contact` |

### Android app (app/src/main/kotlin/dev/vynkor/agent)

| file | role |
|---|---|
| `MainActivity.kt` | Onboarding: host URL + device id + JWT + secret, runtime permissions, notification-access deep link |
| `agent/AgentService.kt` | Foreground service (`connectedDevice` type) holding the Agent, wiring providers |
| `agent/AgentHolder.kt` | process-wide handle so `NotificationListener` reaches the push paths |
| `agent/DeviceIdentity.kt` | stable per-install UUID, operator-overridable (must match JWT `sub`) |
| `agent/AgentConfigStore.kt` | SharedPreferences for host_url/jwt/secret |
| `agent/MicCapture.kt` | `AudioRecord` (16 kHz mono s16le) → `push_mic_pcm` |
| `caps/*Impl.kt` | BatteryManager / LocationManager / ClipboardManager / ContactsContract / AudioTrack implementations of the foreign traits |
| `notifications/NotificationListener.kt` | forwards `StatusBarNotification` → `on_notification` |

---

## Wire-level findings (things the design docs left open)

### 1. Frame → WS byte layout is manual, never `write_frame_raw`

`vynkor-wire` has **no** `frame_to_bytes`/`parse_frame` in its public API (the
kernel's copies are `pub(crate)`). Over WS you must serialize manually:

```rust
// outbound: header + payload + optional 32-byte MAC tag
out.extend_from_slice(&serialize_header(frame));
out.extend_from_slice(&frame.payload);
if let Some(tag) = &frame.mac { out.extend_from_slice(tag); }

// inbound: feed the WS binary message through read_frame(&mut cursor)
let mut cursor: &[u8] = &data;
let frame = read_frame(&mut cursor).await?;
```

**Never** call `write_frame_raw` over WS: it auto-zstd-compresses payloads ≥64 KiB,
which the gateway rejects (R5-03). The SDK (`vynkor-sdk-rust/src/client.rs`
`Transport::Ws`) is the canonical reference for this pattern.

### 2. MAC covers the header with `FLAG_MAC_PRESENT` already set

This is the #1 subtle bug source. The tag is computed over the 44-byte header
**as serialized on the wire, including the MAC flag**:

```rust
// OUT (correct): set flag FIRST, then serialize, then compute
frame.flags |= FLAG_MAC_PRESENT;
let header = serialize_header(&frame);       // header now has the flag bit
frame.mac = Some(compute_tag(key, &header, &frame.payload));

// IN (correct): verify BEFORE stripping the flag
let header = serialize_header(&frame);       // frame still has the flag
verify_tag(key, &header, &frame.payload, &tag)?;
frame.flags &= !FLAG_MAC_PRESENT;
frame.mac = None;
```

The first E2E attempt failed exactly here: the Python test rig computed the tag
over a header *without* the flag → kernel logged `frame MAC invalid — dropping
connection`. `verify_inbound`/`arm_mac` in `protocol.rs` encode this contract.

### 3. Register frame is never MAC'd; the ack is not MAC'd either

The register→ack handshake runs **before** the session key exists. Order:
1. send `PluginRegister` (flags 0, no MAC)
2. read `PluginRegisterAck { accepted, session_nonce }` (16 random bytes when the
   host has a `jwt_secret`)
3. `session_key = derive_session_key(jwt_secret, session_nonce, plugin_id)`
   (HKDF-SHA256, salt=nonce, IKM=jwt_secret, info=`vynkor-frame-mac-v1|{plugin_id}`)
4. arm MAC from here on

### 4. JWT `sub` must equal `device_id` (or `plugin_id`)

Kernel rule (D-03, `src/ipc/protocol.rs`): a device-scoped token
(`claims.sub == reg.device_id`) authorizes every plugin of that device. The agent
registers `plugin_id = {device_id}.{cap}` with `device_id = {device_id}`, so the
minted token MUST have `sub == device_id`. Mismatch → `token plugin_id mismatch`.

Consequence: the app needs a **Device ID field** (auto-generated UUID won't match
the operator-minted token). `vyn token mint --device <id>` is the pairing side.

### 5. `manifest: None` is fine

The kernel does `reg.manifest.unwrap_or_default()` — an agent registering with no
manifest gets an empty `PluginManifest` and the JWT claims become the effective
permission set (`manifest.permissions = claims.permissions`). The agent can rely
on the token's `--permissions` + `--ipc-targets` entirely.

### 6. Raw-binary frames skip protobuf decoding

`FLAG_RAW_BINARY` (0x0010) frames carry opaque audio; the router never decodes
them (`is_kernel_routed` returns false for undecodable/raw payloads). The host
gate has `PERMISSION_AUDIO_STREAM` required for raw-binary *send* — include it in
the minted token's `--permissions`.

### 7. `resolve_ws_url` rules

- `ws://`/`wss://` used verbatim; `http(s)://` → `ws(s)://`; bare `host:port` → `ws://` prefix.
- A bare origin (no path) gains the gateway's `/ws` path. The gateway route is
  **`GET /ws`** (`src/api/server.rs`).
- Default ports normalize away in `url::Url::as_str()` (`wss://host:443` → `wss://host`).

### 8. tokio-tungstenite version moved: 0.24 → 0.30

The kernel bridge uses 0.24. The agent pins **0.30** (already in the skeleton).
Two API changes bit during implementation:
- `IntoClientRequest` is implemented for `&str`/`String`/`http::Uri`, **not**
  `url::Url` (0.24 had the `Url` impl). Convert via `url.as_str()`.
- `WsMessage::Binary` takes `prost::bytes::Bytes` (0.30), not `Vec<u8>` — wrap
  with `.into()`.

---

## Android tooling gotchas

### 1. JNA must be the AAR, not the JAR (crash fix)

UniFFI's generated Kotlin calls Rust through JNA. Adding
`net.java.dev.jna:jna:5.15.0` (plain JAR) builds fine but **crashes at runtime**:

```
java.lang.UnsatisfiedLinkError: Native library (com/sun/jna/android-aarch64/
libjnidispatch.so) not found in resource path (.)
```

The JAR's native lib never lands in the APK. Fix: use the **AAR** —
`implementation("net.java.dev.jna:jna:5.15.0@aar")` — which ships
`libjnidispatch.so` per-ABI and gets packaged into `jniLibs`.

### 2. Rust `tracing` logs are invisible on Android without a subscriber

`tracing` events have no default writer. On Android, stderr from the native
thread does **not** surface in logcat (only the process's stdout/stderr that the
runtime re-routes, which is not the Rust thread's). Fix: `tracing-android` 0.2 —
a `tracing_subscriber::Layer` that writes via `__android_log_write`:

```rust
// cfg-gated: android → logcat, host → stderr
let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| "debug".into());
let sub = tracing_subscriber::registry().with(filter);
#[cfg(target_os = "android")]
if let Ok(layer) = tracing_android::layer("vynkor") {
    let _ = sub.with(layer).try_init();
}
#[cfg(not(target_os = "android"))]
let _ = sub.with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr)).try_init();
```

Dependencies: `tracing-android = "0.2"` must be **target-gated** to Android
(`[target.'cfg(target_os = "android")'.dependencies]`) — it links `-llog`, which
doesn't exist on the Linux host and breaks `cargo test`/clippy. Also enable
`tracing-subscriber` features `env-filter`, `registry`, `fmt`.

Trait-conflict note: with `futures_util::{SinkExt, StreamExt}` imported at module
scope, `registry().with(...)` resolves to `SinkExt::with` and fails to compile.
Scope the `futures_util` imports locally inside the async blocks that use them,
and bring `tracing_subscriber::layer::SubscriberExt` + `util::SubscriberInitExt`
into `init_tracing` only.

### 3. The System SDK dir may be root-owned

`/opt/android-sdk` (from the distro/container) is `root:root`; sdkmanager installs
fail with "Failed to read or create install properties file". Create a user-owned
SDK: `sdkmanager --sdk_root=$HOME/.android-sdk ...` and point `ANDROID_HOME` there.
Also **unset `ANDROID_SDK_ROOT`** if it points at the system dir — Gradle errors
on two different SDK paths.

### 4. NDK + Android targets

- `cargo-ndk` (v4.1.2) + NDK `27.2.12479018` + `platforms;android-35` +
  `build-tools;35.0.0` + `platform-tools` (via sdkmanager into `~/.android-sdk`).
- `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`.
- Gradle wrapper 8.9 + AGP 8.7.3 + Kotlin 2.1.0; `uniffi-bindgen` CLI installed as
  `cargo install uniffi --features cli --bin uniffi-bindgen` (0.32.0).

### 5. uniffi-bindgen must run from the crate dir

`uniffi-bindgen generate --library <path.so>` runs `cargo metadata` internally and
fails with "could not find Cargo.toml" when the Gradle working dir is `app/`.
Run it with `workingDir = <repo>/rust`.

### 6. `cargo add` rewrites Cargo.toml destructively

`cargo add tracing-android` dropped `thiserror` (and once `serde_json`) from the
dependency list. After any `cargo add`, diff `Cargo.toml` and re-add anything it
silently removed. (Or edit Cargo.toml by hand.)

---

## E2E test recipe (the part that proved it works)

Environment: host on LAN `192.168.1.157`, phone on the same Wi-Fi
(`192.168.1.128`, Android 13). Kernel built `--release`, run with a config:

```yaml
# /tmp/vyn-d14-config.yaml
port: 8888
log_level: debug
data_dir: /tmp/vyn-d14
jwt_secret: "d14-local-test-secret-change-me-0123456789"  # ≥32 bytes!
tls: false              # plain WS for the LAN test (app can't verify self-signed)
bind: 0.0.0.0           # D-07: host role + auth → binds all interfaces
ws_register_timeout_secs: 15
```

1. **jwt_secret must be ≥32 bytes** — the kernel refuses to boot with a shorter
   HS256 secret (`jwt_secret is 31 bytes, must be at least 32 bytes`).
2. Mint the device token (sub must equal the app's Device ID):
   ```bash
   vyn token --config /tmp/vyn-d14-config.yaml mint \
     --device d14-test-phone \
     --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" \
     --ipc-targets kernel --ttl-seconds 86400
   ```
3. **Firewall gotcha (the big one):** UFW on the host was blocking the WS port.
   Symptoms: phone's TCP connections sat in `SYN_SENT` forever (visible in
   `/proc/net/tcp`), ICMP ping worked, curl from the host worked — so the network
   looked fine while the app could never connect. `ufw status` showed only
   22/25565/19132/24454/41641 allowed. Fix: run the kernel on an allowed port
   (`port: 25565`) or `sudo ufw allow 8888/tcp`.
4. In the app: Host URL `ws://192.168.1.157:25565`, Device ID `d14-test-phone`,
   JWT = minted token, Host jwt_secret = the config secret. (Test rig wrote the
   SharedPreferences directly via `adb shell run-as` + `cp` — the UI text input
   over adb is unreliable for long tokens.)
5. Verify:
   - `adb logcat -s vynkor` → `registered on host plugin_id=d14-test-phone.*` × 7
   - kernel log → `plugin registered` × 7
   - `curl -H "Authorization: Bearer $TOKEN" http://host:25565/devices` →
     `"device_id":"d14-test-phone","state":"online"`

The standalone Python E2E rig (`/tmp/d14-e2e-check.py` during development) does
the same register→MAC→ping/pong roundtrip by hand and is a good protocol oracle.

---

## Known limits / follow-ups (from the design docs + this work)

- **Opus codec**: mic/speaker currently use PCM passthrough
  (`AUDIO_CODEC_PCM_S16LE`); D-12 host STT accepts it. Opus (bandwidth) needs an
  NDK-buildable encoder/decoder (audiopus/libopus or a pure-Rust crate) — deferred.
- **TLS cert pinning**: `tls: false` was used for the LAN test. The app uses
  webpki-roots; pinning the host's served cert (D-07 rule) is a follow-up.
- **UI live status**: the status text updates only in `onResume`; add a periodic
  `isConnected()` poll or callback for a live indicator.
- **`user_id`** is hardcoded `"default"` in the service; single-user deployments
  only until a config field exists.
- **registry capabilities**: `/devices` shows only the *last* registered cap's
  capabilities array (each re-register refreshes the device record, D-03); a
  device-level aggregate is a kernel-side nicety, not an agent bug.
