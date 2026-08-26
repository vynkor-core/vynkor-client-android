# vynkor-client-android

Android device-agent for the vynkor (formerly Veyron) plugin kernel: turns a
phone into a remote device whose capabilities register on a host kernel as
`{device_id}.{cap}` (D-14). Geo, battery, notifications, clipboard, contacts,
mic and speaker are callable from the host over WebSocket.

Design + decisions: kernel repo, `docs/ANDROID_DEVICE_AGENT.md` and
`docs/ANDROID_DEVICE_AGENT_RUST_CORE.md`. Build/experience notes:
`docs/D14_IMPLEMENTATION_NOTES.md`.

## Layout

```
rust/   the protocol engine (vynkor-agent-core): framing, frame-MAC, WS client,
        registration, per-capability routing. Pure protocol — no Android APIs.
app/    the Kotlin/Gradle Android app: foreground service, capability providers
        (BatteryManager, LocationManager, ClipboardManager, ContactsContract,
        AudioRecord/AudioTrack), NotificationListenerService, onboarding UI.
```

Rust = protocol, Kotlin = device I/O, UniFFI is the boundary. The core reuses
`veyron-wire` 0.2.3 (proto v1.6) verbatim — no reimplemented crypto.

## Build

Prereqs: Rust + Android targets, NDK, cargo-ndk, Android SDK (see
`docs/D14_IMPLEMENTATION_NOTES.md` §Tooling).

```bash
# Rust core: host-side unit tests
cd rust && cargo test && cargo clippy --all-targets -- -D warnings

# Android APK (builds the .so via cargo-ndk + generates UniFFI bindings)
export ANDROID_HOME=$HOME/.android-sdk   # unset ANDROID_SDK_ROOT if it differs
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Run against a host

1. Host kernel with `jwt_secret` (≥32 bytes), `tls: false` for a plain-WS LAN
   test (or pin the served cert), `bind: 0.0.0.0`, and the WS port allowed in the
   host firewall (UFW gotcha — see implementation notes).
2. Mint a device token bound to the same id the app will use:
   ```bash
   vyn token --config <host-config> mint \
     --device my-phone \
     --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" \
     --ipc-targets kernel --ttl-seconds 86400
   ```
3. In the app: Host URL `ws://<host-ip>:<port>`, Device ID `my-phone`
   (**must match the JWT `sub`**), Device JWT, Host jwt_secret. Connect.
4. Verify: `adb logcat -s vynkor` shows `registered on host
   plugin_id=my-phone.<cap>` × 7; `GET /devices` on the host lists the device
   `state: online`.

## Capabilities

| cap | direction | provider |
|---|---|---|
| `{id}.battery` | host→device request + push | `BatteryManager` |
| `{id}.geo` | request + device→host push | `FusedLocationProvider`/`LocationManager` |
| `{id}.notifications` | device→host event | `NotificationListenerService` |
| `{id}.clipboard` | both | `ClipboardManager` |
| `{id}.contacts` | host→device request | `ContactsContract` |
| `{id}.mic` | device→host stream | `AudioRecord` → host STT (PCM v1) |
| `{id}.speaker` | host→device stream | `AudioTrack` (PCM v1) |
| `{id}.chat` | device→host requests | AI chat via the host's `ai` plugin |
| `{id}.device` | host→device request | model/locale/screen facts |
| `{id}.wifi` | host→device request | state + scan results |
| `{id}.bluetooth` | host→device request | state + paired devices |
| `{id}.dnd` | host→device get/set | zen mode (policy access) |
| `{id}.ringer` | host→device get/set | normal/silent/vibrate |
| `{id}.brightness` | host→device get/set | Settings.System (WRITE_SETTINGS) |
| `{id}.flashlight` | host→device on/off/toggle | CameraManager torch |
| `{id}.launcher` | host→device list/open | PackageManager |
| `{id}.sms` | host→device request (read-only) | Telephony.Sms (READ_SMS) |
| `{id}.calls` | host→device request (read-only) | CallLog (READ_CALL_LOG) |
| `{id}.calendar` | host→device read/add | CalendarContract |

Request/response schemas, limits and permission mapping for every capability:
[docs/HOST_CAPABILITIES_PROTOCOL.md](docs/HOST_CAPABILITIES_PROTOCOL.md).
Kernel-side work required for the next product milestones (CLI-free pairing,
per-device keys, chat streaming, assistant sessions):
[docs/CLIENT_DRIVEN_KERNEL_TASKS.md](docs/CLIENT_DRIVEN_KERNEL_TASKS.md).
Sensitive grants (SMS/calls/calendar/nearby-devices/brightness/DND) are never
requested at service start — they are opt-in per capability from
**Settings → Capabilities**, and each provider re-checks its grant on every call.

## Chats & projects

Chats can be grouped into **projects** (folders) from the drawer:
create via `+` next to Projects, filter with the chips (`All chats` /
project), new chats inherit the selected project, long-press a chat →
*Move to project*, long-press a chip → rename / **Project files…** / delete
(deleting keeps the chats and moves them to “No project”).

**Project files** are per-project context folders: files added there are
copied into app-private storage and their text contents (bounded) are injected
into every AI request made from that project's chats. **Attachments** work the
same way for single messages: photo/video via the system picker, camera
capture, any file via the document picker; images render as thumbnails in the
bubble and everything is described to the AI in an injected system block.
Backups (Settings → Data) carry chat/attachment *metadata* only — file bytes
stay on the device.

## Quick access

* **Home-screen widget** (3×1): live agent status (accent-colored dot +
  host name) and a Start/Stop button; tap anywhere else opens the chat.
  Follows the in-app accent theme and system dark mode.
* Quick Settings tile “vynkor agent” — one-tap start/stop of the agent.
* Long-press launcher icon → *New chat* shortcut.
* Share-to-app: text shared from other apps lands in the composer.

Licensed under either of [LICENSE-APACHE](LICENSE-APACHE) or
[LICENSE-MIT](LICENSE-MIT), at your option.

## App lock

Off by default. Enabled in **Settings → Security**, it then requires
fingerprint (device PIN as fallback) on cold start before the agent UI is
reachable — it holds host credentials and device data.
Returning after ≥ 5 minutes in background re-locks the app. Devices with no
enrolled authenticator pass through; cancelling the prompt closes the app.
While locked, content is hidden from screenshots/recents (`FLAG_SECURE`).

## Live status & telemetry

The foreground notification mirrors the connection state
(Connecting… / Connected to `<host>` / Reconnecting…). Its detail level is
configurable in Settings → **Service notification**: *Detailed* (status line
+ New chat/Disconnect actions), *Minimal* (title only), or *Hidden*
(silent IMPORTANCE_MIN channel — collapsed to the bottom section, the least
intrusive a foreground-service notice can legally be). Settings and the
drawer show fine-grained status incl. the failure reason
(`Host unreachable — <reason>`, red) while dialing; the Connect button turns
into an honest **Stop** during retries. Battery level and charging
transitions are pushed to the host as `battery_status` events (debounced:
snapshot on connect, Δlevel ≥ 5 %, charging flip) — no polling needed.

