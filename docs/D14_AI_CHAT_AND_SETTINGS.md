# D-14 Follow-up — AI chat, multi-host settings, live status

Notes from the second D-14 iteration (2026-08-16). Builds on
`D14_IMPLEMENTATION_NOTES.md` (the first E2E-verified pass). This pass adds:

1. **Outbound action requests** — the agent was response-only; now it can call
   host plugins (the `ai` plugin's `chat_completion` in particular).
2. **AI chat screen** — send a message, get a completion, multi-turn history.
3. **Multi-host profiles** — save several kernels (host URL + JWT + secret +
   device id + per-host AI settings) and switch between them.
4. **Live connection status** — a Rust→Kotlin callback instead of polling.

## What changed

### Rust core (`rust/`)

| file | change |
|---|---|
| `ffi.rs` | `ActionReplyStatus` enum, `ActionReply` record, `AgentObserver` foreign trait |
| `agent.rs` | `request()` outbound call, pending-map response correlation, `ActionResponse` inbound dispatch, `notify_state` observer firing, `chat` capability |

- `Agent::request(target, action, params_json, timeout_ms) -> ActionReply` is a
  **blocking** call meant for a background thread (never the Android main
  thread). It generates an `action_id`, registers a `std::sync::mpsc::Sender`
  in a pending map, sends the `ActionRequest` over the `chat` connection
  (target `"kernel"` — the kernel routes by action name to the provider), and
  blocks on `recv_timeout` for the correlated `ActionResponse`.
- `dispatch_inbound` now resolves inbound `ActionResponse` frames against the
  pending map (matched by `action_id`) and completes the waiter.
- `AgentObserver::on_state_changed(connected)` fires on the 0↔1 live-connection
  transitions, so the UI shows a live indicator without polling.

### Kotlin app (`app/`)

| file | role |
|---|---|
| `agent/HostProfile.kt` | profile data class (id, name, host_url, device_id, jwt, secret, ai_provider, ai_model, ai_base_url, ai_api_key_env) |
| `agent/ProfileStore.kt` | JSON array in SharedPreferences, active-profile id, legacy single-config migration |
| `agent/AiClient.kt` | builds `chat_completion` params, calls `Agent.request`, unwraps `{content, stop_reason, usage}` |
| `agent/ChatStore.kt` | per-profile chat transcript persistence |
| `agent/AgentService.kt` | reads the active profile, registers 8 caps incl. `chat`, wires `AgentObserver` → `AgentHolder.connectionState` |
| `MainActivity.kt` | profile list (RecyclerView), live status, Connect/Disconnect, Chat button, FAB |
| `ProfileActivity.kt` | add/edit form incl. AI fields |
| `ChatActivity.kt` | chat UI, persisted multi-turn history |
| `ProfileAdapter.kt` / `ChatAdapter.kt` | list adapters |

Deps added: appcompat, material (Material3), recyclerview,
lifecycle-runtime-ktx, kotlinx-coroutines-android.

## Design decisions

1. **Blocking `request()` over `std::sync::mpsc`, not `tokio::oneshot`.**
   `request()` is called from a Kotlin background thread via JNA, i.e. *not* on
   the tokio runtime — there is no runtime handle to `block_on`, and a
   `tokio::oneshot::Receiver` can't be awaited off-runtime. An unbounded
   `std::sync::mpsc` channel is `Send` (storable in the pending map), its
   `Sender::send` never blocks, and `Receiver::recv_timeout` gives the blocking
   wait. The timeout is `timeout_ms + 5 s` margin so the kernel's own terminal
   `ACTION_TIMEOUT` response can arrive instead of the device racing it.
2. **A dedicated `chat` capability.** The `ActionResponse` is routed back to
   the connection that sent the request, so outbound traffic needs a stable
   carrier. `{device_id}.chat` is that carrier and also gives the host a
   stable whole-device address. It registers with no host→device actions of
   its own.
3. **AI config is per-host, not global.** `provider`/`model`/`base_url`/
   `api_key_env` are properties of the host's `ai` plugin deployment (its
   allowlist, its models), so they live on the profile. The API key itself
   never travels — only the env-var name (`api_key_env`), read by the `ai`
   process at call time.
4. **Observer callback, not polling.** `AgentObserver` is a `with_foreign`
   trait (same as the provider traits); the Kotlin side sets
   `AgentHolder.connectionState.value = connected`, which the UI collects.
   The callback runs on the runtime thread and must not block — the Kotlin
   impl only writes a `StateFlow`.
5. **Text chat only for now.** Voice (mic→STT→AI→TTS) is deferred; the
   mic/speaker capabilities already exist, only the pipeline is missing.

## E2E recipe (what actually ran)

Environment: host `192.168.1.157`, phone (MI 6, Android 13) on the same
Wi-Fi, Ollama (`llama3.2:3b`) running locally on the host.

1. **Kernel config** (`/tmp/vyn-e2e/config.yaml`): `jwt_secret` (≥32 bytes),
   `tls: false` (app can't verify self-signed over LAN), `bind: 0.0.0.0`,
   `port: 25565` (UFW-allowed), `plugins_dir` with `ai` + `network`.

2. **`ai`/`network` plugins were STALE** — binaries pinned `vynkor-wire 0.2.0`
   / `0.2.2`, i.e. the pre-M9 `ActionStatus` numbering where `ACTION_OK == 0`.
   The kernel (proto v1.6) reads `0` as `ACTION_UNKNOWN`, so every
   `chat_completion` came back `status=0`. Fix: `cargo update` (→
   `vynkor-sdk 0.1.6`, `vynkor-wire 0.2.3`) + rebuild + reinstall the
   binaries. See the `vynkor-plugins` PR that commits the lock bump.

3. **Auth for local plugins.** With `jwt_secret` set, *every* register must
   present a JWT — including the local UDS plugins, which read
   `VYN_JWT_SECRET` + `VYN_JWT_TOKEN` from their env. So mint three
   tokens and set the env:
   - device: `vyn token mint --device d14-test-phone --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" --ipc-targets kernel`
   - `ai`: `--device ai --permissions "PERMISSION_NETWORK"` (T-19: it calls `network`'s gated `http_request`)
   - `network`: `--device network --permissions "PERMISSION_NETWORK,PERMISSION_EVENT_PUBLISH"`
   - plugin env: `VYN_JWT_SECRET=<secret>`, `VYN_JWT_TOKEN=<token>`,
     `AI_PLUGIN_ALLOWED_KEY_ENVS=OLLAMA_API_KEY,…`, `OLLAMA_API_KEY=dummy`,
     `NETWORK_PLUGIN_ALLOWED_HOSTS=localhost,127.0.0.1`.

4. **App config via `run-as`** (typing long tokens over adb is unreliable):
   write the profile JSON into
   `shared_prefs/vynkor_config.xml` (`profiles` array + `active_profile`).

5. Verify: `adb logcat -s vynkor` shows 8 × `registered on host
   plugin_id=d14-test-phone.*` (incl. `.chat`); `/plugins` lists `ai`,
   `network` and all device caps; the chat returns a real Ollama completion;
   the status flips to Connected (observer) and history survives a restart.

## Findings (beyond this repo)

1. **`ai`/`network` binaries were built against the pre-M9 proto** (lock at
   `vynkor-wire 0.2.0`/`0.2.2`). Symptom: `status=0` (UNKNOWN) responses.
   Fixed by the lock bump committed in `vynkor-plugins`.
2. **`ai` rejects an empty `api_key_env`** (`handler.rs`: `environment variable
   … is not set`), contradicting its README ("empty key is fine for Ollama").
   Worked around with `OLLAMA_API_KEY=dummy`; the plugin should either allow an
   empty allowlisted key (the openai adapter already omits the header then) or
   the README should drop the Ollama empty-key claim.

## Follow-ups (deferred)

- **`ai` plugin model discovery — IMPLEMENTED (ai-plugin v0.3, 2026-08-16).**
  The manual per-profile AI section was removed from the app; the `ai` plugin
  now keeps a SQLite store (`<data_dir>/plugins/ai/ai.db`) with models,
  agent profiles and token usage. Models come from `AI_PLUGIN_MODELS`
  (declared, required for Anthropic) or `AI_PLUGIN_DISCOVERY`
  (auto-pulled from Ollama `/api/tags` / OpenAI `/models` at startup and via
  the `refresh_models` action). Agents (`AI_PLUGIN_AGENTS`) are named
  profiles with model + system prompt + goal + description. The client calls
  `list_models`/`list_agents` after connecting and names models/agents by
  id; provider/base_url/api_key_env no longer travel from the phone.
  Analytics: every completion records input/output tokens
  (`usage` table); `usage_stats` aggregates by model/agent. Requires a
  kernel that grants `VYN_DATA_DIR` (vynkor: supervisor `set_data_dir`).
- Voice pipeline (mic→STT→AI→TTS) — caps exist, pipeline missing.
- TLS cert pinning (still `tls: false` for LAN tests).
- `user_id` per profile (hardcoded `"default"`).
- Streaming `chat_completion` (needs a kernel primitive; `ai` v1 is
  one-request/one-response).
- A "test connection" button that pings without a full service start.
