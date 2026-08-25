# D-14 Follow-up — QR pairing (`vyn device connect`)

> **Update 2026-08-24 (R-02/R-03):** QR из встроенного сканера применяется
> как раньше, но тот же `vynkor://pair` от чужого приложения теперь требует
> явного подтверждения («Connect to new host?»), а профиль хранится
> зашифрованным (AndroidKeyStore) и исключён из cloud-backup. Детали —
> [D14_HARDENING.md](D14_HARDENING.md).

Notes from the third D-14 iteration (2026-08-16). Builds on
`D14_IMPLEMENTATION_NOTES.md` (first E2E pass) and
`D14_AI_CHAT_AND_SETTINGS.md` (multi-host + AI chat). This pass replaces the
4-field manual onboarding (host URL + device id + JWT + secret — which the
first pass had to inject via `run-as`, because typing long tokens over adb is
unreliable) with a QR scan: the host renders a QR, the phone scans it, the
profile fills itself and connects.

## The flow

```
host $ vyn device connect --host <ip|tailnet> [--device <id>]
        │  mints per-device JWT + reads served TLS cert (when tls: true)
        ▼
   QR (terminal UTF-8 / SVG)   +   vynkor://pair?d=<base64url(JSON)>
        │  phone scans (or opens the link)
        ▼
   app parses → saves HostProfile → sets active → auto-connects
```

Payload JSON (base64url, no padding) carried in `vynkor://pair?d=...`:

```jsonc
{ "v": 1, "name": "...", "host_url": "wss://...:port/ws",
  "device_id": "...", "jwt_token": "...", "jwt_secret": "...",
  "cert_pem": "-----BEGIN CERTIFICATE-----…" }   // present only when tls: true
```

## Design decisions

1. **Host-driven (Flow A).** The QR is unidirectional: the host generates the
   device id, mints the token, and the phone adopts the identity from the QR
   (`DeviceIdentity.setDeviceId`). The two-way alternative (phone shows its own
   QR, host scans, token returns out-of-band) needs an enrollment protocol —
   that's D-18 (ed25519), deferred. The payload is versioned (`v`) so the
   schema can migrate.
2. **URI, not raw JSON.** `vynkor://pair` is a deep link: scannable as a QR,
   openable as a link, and `adb`-testable without a camera
   (`adb shell am start -a android.intent.action.VIEW -d "vynkor://pair?d=…"`).
3. **Full served cert (PEM) in the QR.** The QR is a physical trusted channel,
   so it can carry the self-signed cert (rcgen ECDSA, ~600 B PEM) that the
   phone would otherwise reject. The Rust core pins it (rustls `RootCertStore`
   seeded with the PEM) → `wss://` works with self-signed without `tls: false`.
   This closes the "TLS cert pinning" follow-up from the first pass.
4. **ZXing Embedded** for scanning (offline, no Play Services); the deep link
   and the scan feed the same `PairingPayload.parse` path.

## Security notes (the sharp edges)

- **The QR carries the host's MASTER `jwt_secret`.** The frame-MAC key is
  `derive_session_key(jwt_secret, nonce, plugin_id)`, so the agent must hold
  the shared HS256 secret — there is no per-device MAC key until D-18. The
  command prints a loud warning; show the QR only to the device being paired.
- **Never loopback.** A phone scanning `localhost`/`127.0.0.1` would dial
  itself. The command warns on loopback and refuses to auto-select a loopback
  LAN address.
- **Reachability is the operator's choice.** LAN IP works only on the same
  Wi-Fi; a Tailscale name/100.x works from anywhere and is E2E-encrypted
  (§19 overlay). A bare `--host` (no port) gains the config `port`.
- **Secrets still plaintext on device.** `jwt_token`/`jwt_secret`/`cert_pem`
  live in SharedPreferences unencrypted — Keystore/EncryptedSharedPreferences
  is a follow-up.

## Command

`--config` sits on the `device` subcommand (same as `vyn token --config … mint`).

```bash
# existing device (matches the app's "local-host" profile: id + secret must agree)
vyn device --config /tmp/veyron-e2e/config.yaml connect \
  --device d14-test-phone --host 192.168.1.157 \
  --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" \
  --ipc-targets kernel --qr-out /tmp/pair.svg

# brand-new device (id auto-generated, or --device my-new-phone)
vyn device --config /tmp/veyron-e2e/config.yaml connect \
  --host 100.64.0.2 \
  --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" \
  --ipc-targets kernel --qr-out /tmp/pair.svg
```

Flags: `--device` (JWT sub), `--name`, `--host` (advertise URL), `--permissions`,
`--ipc-targets`, `--ttl-seconds`, `--aud`, `--qr-out` (SVG, opens in a browser).

## Implementation map

**Kernel (`veyron`):**
`src/cli/device.rs` (new — resolve advertise URL, mint token, read
`effective_tls_cert_path`, build payload, render QR), `cli/mod.rs` + `main.rs`
(wire the `Device` subcommand), `Cargo.toml` (`qrcode`, `base64`).

**Agent core (`vynkor-client-android/rust/`):**
`ffi.rs` (`AgentConfig += cert_pem`), `transport.rs` (`RegisterParams +=
cert_pem`; `pinned_tls_config` rustls connector used on `wss://` when present),
`agent.rs` (thread `cert_pem`), `Cargo.toml` (`rustls`, `rustls-pemfile`).

**App (`app/`):**
`PairingPayload.kt` (new parser), `HostProfile += certPem`, `MainActivity.kt`
(Scan QR button, `ScanContract`, CAMERA permission, deep-link `onNewIntent`),
`AgentService.kt` (pass `cert_pem`), `AndroidManifest.xml` (CAMERA +
`vynkor://pair` intent-filter), ZXing Embedded dep.

## Verified

- Kernel: 6 new unit tests (URL normalization — bare-host port, explicit port,
  full URL, loopback warn), clippy/fmt clean. Full suite green serially; 2
  autoload tests flake under parallel `cargo test` from a port collision —
  pre-existing, not this change.
- Agent core: 18 tests, clippy/fmt clean; cargo-ndk cross-compiled all 3 ABIs.
- E2E (device MI 6 via adb): deep link → profile saved (`host_url`, `device_id`,
  `jwt`, `secret`, `cert_pem`), active, service started. `tls: true` payload
  carries the 603-byte cert PEM. Physical camera scan + live-connect against a
  running kernel remain manual.

## Follow-ups (deferred)

- Keystore/EncryptedSharedPreferences for profile secrets.
- `usesCleartextTraffic="true"` → network-security-config.
- `user_id` per profile (still hardcoded `"default"`).
- D-18 ed25519 enrollment → QR carries an enrollment ticket, not the master secret.
