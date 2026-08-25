# Host-side protocol — device capabilities (client implementation reference)

> This document describes **what the HOST (kernel) must support** for every
> capability the Android agent registers. The client side (this repo) is fully
> implemented; anything marked ⚠️ requires matching behavior on the host to be
> useful end-to-end.
>
> Wire format: `veyron-wire` / proto v1.6 (`Envelope`, `ActionRequest`,
> `ActionResponse`, `EventPublish`) over per-capability WebSocket sessions,
> frame-MAC keyed off the per-device `device_secret` (pairing payload v2).
>
> Registration: each capability connects as plugin id `{device_id}.{cap}` with
> target routing `kernel` for kernel-routed envelopes.

## Capability list

The agent registers these caps on start (see `AgentService.startAgent`):

| # | cap | direction | Android backend |
|---|-----|-----------|-----------------|
| 1 | `{id}.geo` | request + push event | FusedLocation / LocationManager |
| 2 | `{id}.battery` | request + push event | BatteryManager |
| 3 | `{id}.notifications` | push event | NotificationListenerService |
| 4 | `{id}.clipboard` | request (read/write) + push event | ClipboardManager |
| 5 | `{id}.contacts` | request | ContactsContract |
| 6 | `{id}.mic` | stream (device→host PCM) | AudioRecord (explicit session only) |
| 7 | `{id}.speaker` | stream (host→device PCM) | AudioTrack |
| 8 | `{id}.chat` | outbound requests from device | AiClient → host `ai` plugin |
| 9 | `{id}.device` | request | static device facts |
| 10 | `{id}.wifi` | request | WifiManager |
| 11 | `{id}.bluetooth` | request | BluetoothAdapter |
| 12 | `{id}.dnd` | request (get/set) | NotificationManager zen mode |
| 13 | `{id}.ringer` | request (get/set) | AudioManager |
| 14 | `{id}.brightness` | request (get/set) | Settings.System |
| 15 | `{id}.flashlight` | request (get/set/toggle) | CameraManager torch |
| 16 | `{id}.launcher` | request (list/open) | PackageManager |
| 17 | `{id}.sms` | request | Telephony.Sms (READ_SMS) |
| 18 | `{id}.calls` | request | CallLog (READ_CALL_LOG) |
| 19 | `{id}.calendar` | request (upcoming/add) | CalendarContract |

Every host→device request is an `ActionRequest` with `action_id` echoed back in
`ActionResponse`. Errors arrive as `status = ACTION_ERROR` with a human-readable
`error`; missing permission on the phone degrades to empty results or an error
string — the host must treat both as "capability unavailable right now".

---

## Tier-1 capabilities (pre-existing)

### `{id}.battery`

Request params: none. Response:

```json
{ "level_percent": 78, "is_charging": false, "temperature_c": 29.0 }
```

`temperature_c` is `null` when the sensor value is outside −40…+100 °C.

Push events (no request): `event_type: "battery_status"` with
`{"level_percent", "charging"}` — debounced: snapshot on connect, Δ ≥ 5 %,
charging flip.

### `{id}.geo`

Request params: none. Response:

```json
{ "lat": 41.31, "lon": 69.24, "accuracy_m": 12.5 }
```

Error `"no location fix yet"` until the first fix. Push events:
`event_type: "geo_update"` with the same shape.

### `{id}.clipboard`

Actions:

- `read` (default) → `{"text": "..."}`
- `write` with `{"text": "..."}` → `{"ok": true}`

Push events: `event_type: "clipboard_changed"` with `{"text"}`.

### `{id}.contacts`

Params: `{"query": "iva", "limit": 50}`. Both optional; `limit ≤ 200` enforced
on the device regardless of what the host asks. Response: array of

```json
{ "name": "Ivan", "phones": ["+7916..."], "emails": [] }
```

### `{id}.mic` / `{id}.speaker`

PCM s16le mono 16 kHz, codec tag `AUDIO_CODEC_PCM_S16LE`. Mic streams only
inside an explicit session started from the phone UI (privacy invariant R-01);
the host cannot start it remotely.

---

## Chat (`{id}.chat`) — device-originated requests

The phone sends `ActionRequest`s targeted at `kernel` with these action names.
⚠️ The host's `ai` plugin owns them.

### `chat_completion`

```json
{
  // either agent_id OR provider/model/api_key_env:
  "agent_id": "assistant",          // optional
  "provider": "openai",             // when no agent_id
  "base_url": "https://...",        // optional override
  "model": "claude-sonnet-5",
  "api_key_env": "OPENAI_API_KEY",  // name only; key never travels
  "messages": [
    {"role": "system",    "content": "..."},   // injected context block, see below
    {"role": "user",      "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "max_tokens": 1024,
  "timeout_ms": 30000
}
```

Response data_json:

```json
{
  "content": "…",
  "stop_reason": "end_turn",
  "usage": {"input_tokens": 123, "output_tokens": 45}
}
```

⚠️ Host requirement: accept a leading `system` message. The app injects one
when the chat belongs to a project with files or carries attachments — the
block starts with “You are assisting inside the vynkor agent.” and contains
project name, attached file names/types and bounded inline text contents
(≤ 24 000 chars total). Passing it through as-is is enough; no special
handling needed.

### `list_models` / `list_agents`

No params. Response: arrays of model/agent descriptors
(`id`, `provider`, `base_url`, `api_key_env`, `is_default` /
`id`, `name`, `model_id`, `system_prompt`, `goal`, `description`, `is_default`).

---

## Extended device controls

### `{id}.device`

No action name required. Response:

```json
{
  "model": "MI 6", "manufacturer": "Xiaomi", "brand": "xiaomi",
  "android_release": "14", "sdk_int": 34, "locale": "ru-RU",
  "screen_width_px": 1080, "screen_height_px": 1920
}
```

### `{id}.wifi`

| action | params | response |
|--------|--------|----------|
| `status` (default) | — | `{"enabled", "ssid", "ip", "link_speed_mbps"}`; SSID empty without location grant |
| `scan` | — | array of `{"ssid", "bssid", "rssi", "secure"}`, max **50**, strongest first; `[]` without location grant |

### `{id}.bluetooth`

| action | params | response |
|--------|--------|----------|
| `status` | — | `{"enabled", "adapter_name"}` |
| `paired` | — | `[{"name", "address", "connected"}]`; `[]` without BLUETOOTH_CONNECT (API 31+) |

### `{id}.dnd`

| action | params | response |
|--------|--------|----------|
| `get` | — | `{"filter": "off\|"priority"\|"alarms"\|"none"\|"unknown"}` |
| `set` | `{"mode": "<same enum>"}` | updated `{"filter"}`; ACTION_ERROR if no notification-policy access |

### `{id}.ringer`

| action | params | response |
|--------|--------|----------|
| `get` | — | `{"mode": "normal\|"silent"\|"vibrate"}` |
| `set` | `{"mode": "…"}` | updated `{"mode"}`; error if OEM ignored the change |

### `{id}.brightness`

| action | params | response |
|--------|--------|----------|
| `get` | — | `{"level": 0–255 \| null, "auto": bool}` |
| `set` | `{"level"?: 0–255, "auto"?: bool}` (≥1 required) | updated state; error if WRITE_SETTINGS not granted |

### `{id}.flashlight`

| action | params | response |
|--------|--------|----------|
| `get` | — | `{"available": bool, "on": bool}` |
| `on` / `off` / `toggle` | — | `{"on": bool}`; error on devices without a flash unit |

### `{id}.launcher`

| action | params | response |
|--------|--------|----------|
| `list` | `{"limit"?: ≤200}` | `[{"package", "name"}]` sorted by name |
| `open` | `{"package": "com.x.y"}` | `{"ok": true}`; error if unknown/refused |

### `{id}.sms` (sensitive)

Action `inbox` (default). Params: `{"query"?: "text", "limit"?: ≤100}` —
substring match over body/sender, newest first. Response: array of

```json
{ "sender": "+7916...", "body": "...", "timestamp_ms": 1756000000000 }
```

`[]` when READ_SMS is not granted.

### `{id}.calls` (sensitive)

Action `recent` (default). Params: `{"limit"?: ≤100}`. Response: array of

```json
{ "number": "+7…", "name": "Ivan", "type": "incoming|outgoing|missed|rejected|other",
  "timestamp_ms": …, "duration_s": 45 }
```

`[]` when READ_CALL_LOG is not granted.

### `{id}.calendar` (sensitive)

| action | params | response |
|--------|--------|----------|
| `upcoming` (default) | `{"days"?: ≤31, "limit"?: ≤100}` | `[{"title","description","location","start_ms","end_ms","calendar"}]` soonest first |
| `add` | `{"title"!, "description"?, "location"?, "start_ms"!, "end_ms"?}` | `{"ok": true, "event_id": <row id>}`; end defaults to start; errors on missing WRITE_CALENDAR / bad time order |

---

## What the host does NOT need to implement

* Project files & attachments context injection happens entirely client-side;
  the host sees only ordinary `chat_completion` calls with an optional system
  message (see above).
* Pairing payload v2 issuance is documented in `RFC_E01_PER_DEVICE_KEYS.md`
  (`vynkor://pair?z=1&d=<deflate+base64url(JSON)>`, fields `v=2`, `host_url`,
  `device_id`, `jwt_token`, `device_secret`, `cert_pem`, `name`).
* Connection resilience (ping/read-deadline/backoff-reset) is device-side.

## Recommended host-side UX hooks (future work, not required)

1. `GET /devices` should surface the new caps so dashboards can render them
   (`my-phone.dnd`, `my-phone.flashlight`, …).
2. An "actions" API mapping natural language → `ActionRequest` per table above.
3. Event subscriptions for `battery_status` / `geo_update` /
   `clipboard_changed` already flow as standard `EventPublish` frames.
