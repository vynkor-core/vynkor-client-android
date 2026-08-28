# D-14 Follow-up — chat UX + on-device STT

> **Update 2026-08-24 (R-01/R-11):** локальная диктовка декодирует
> ограниченное окно (префикс + хвост 15 c, лимит сессии 5 мин), а mic→host
> стримится только внутри явной сессии — long-press кнопки микрофона в чате.
> Детали — [D14_HARDENING.md](D14_HARDENING.md).

Notes from the fourth D-14 iteration (2026-08-16). Builds on
`D14_IMPLEMENTATION_NOTES.md` (first E2E pass), `D14_AI_CHAT_AND_SETTINGS.md`
(multi-host + AI chat) and `D14_QR_PAIRING.md` (QR pairing). This pass makes the
chat actually usable — multiple conversations, read-aloud, copy, markdown — and
adds the first leg of the D-12 voice pipeline: **local STT on the device** (per
`docs/ANDROID_DEVICE_AGENT.md`: "STT — on the client (Android), local … Engine:
sherpa-onnx").

## What changed

### Chat data layer (`app/src/main/kotlin/dev/vynkor/agent/agent/`)

| file | change |
|---|---|
| `Chat.kt` | NEW — `ChatMessage(role, content, timestamp)` + `Chat(id, title, createdAt, updatedAt, messages)`. `ChatMessage` moved here from `ChatStore`; `timestamp` has a default so positional callers keep compiling. |
| `ChatStore.kt` | REWRITTEN — multi-chat CRUD (`list`/`load`/`save`/`delete`/`rename`/`autoTitle`), one JSON array per `profileId` under prefs `vynkor_chat`. Migrates the old single-transcript `{role, content}` array into one `Chat` in place. |
| `HostProfile.kt` | `DEFAULT_MODEL_BY_PROVIDER` (`openai→llama3.2`, `anthropic→claude-sonnet-4-5`), `effectiveModel()`, `effectiveBaseUrl()`; new `userId` field (`user_id` JSON key, defaults `"default"`). |
| `AiClient.kt` | uses `effectiveModel()`/`effectiveBaseUrl()` instead of raw fields. |
| `TtsEngine.kt` | NEW — wraps `android.speech.tts.TextToSpeech` (on-device, system locale). |
| `SttEngine.kt` | NEW — process-wide lazy sherpa-onnx `OfflineRecognizer` (see below). |
| `SttRecorder.kt` | NEW — `AudioRecord` 16 kHz mono → `FloatArray` in [-1, 1]. |
| `AgentService.kt` | `userId = profile.userId.ifBlank { "default" }` (was hardcoded). |

### Chat UI (`app/src/main/kotlin/dev/vynkor/agent/`)

| file | change |
|---|---|
| `ChatListActivity.kt` | NEW — chat list (tap open, long-press rename/delete, FAB new chat). |
| `ChatListAdapter.kt` | NEW — title + last-message preview + relative time. |
| `ChatActivity.kt` | refactored to one `Chat` by `EXTRA_CHAT_ID`; auto-title from first user msg; busy/typing indicator; copy + TTS + mic wiring. |
| `ChatAdapter.kt` | copy + speak action buttons per message; Markwon markdown rendering for user/assistant (error stays plain). |
| `MainActivity.kt` | "Chats" button → `ChatListActivity`; active-profile badge; Material3 polish. |
| `ProfileActivity.kt` | prefill default model/base_url/api_key_env; suggested-model chips; `userId` field. |

### Resources

- Material3 light + dark (`values-night/`) palettes, vector icons (`ic_mic`,
  `ic_send`, `ic_copy`, `ic_volume`, `ic_back`, `ic_add`, `ic_chat`, …), chat
  list/message layouts, all strings in `strings.xml`.
- `build.gradle.kts`: `markwon:core`, `files("libs/sherpa-onnx-1.13.5.aar")`,
  `androidResources { noCompress += "onnx" }`.

## Decisions

1. **STT engine = sherpa-onnx, per the design doc** (`ANDROID_DEVICE_AGENT.md`
   decision: "Client STT engine: sherpa-onnx — same engine as the host `stt`
   plugin"). Used the **offline (non-streaming)** recognizer — right for
   push-to-talk dictation; simpler than the streaming path.
2. **Model = `zipformer-ru-int8`** (sherpa-onnx zipformer transducer, int8,
   ~71 MB), the same model the host `stt` plugin loads. It's the smallest
   Russian option we have: the official `sherpa-onnx-small-zipformer-ru-…` is
   f32 and ~105 MB, the multilingual NeMo CTC ~97 MB. Bundled into
   `app/src/main/assets/stt/` (works offline out of the box; no runtime
   download/decompression — the official packs ship as `.tar.bz2`, which has no
   pleasant Android decompressor).
3. **Model type = auto-detect.** The host plugin sets only the `transducer`
   sub-config + `tokens.txt` and leaves `model_type` empty (auto-detected) —
   proven in the D-12 E2E. The Android config matches that exactly (no explicit
   `modelType`), to avoid a runtime load failure we can't test here.
4. **Model loaded via `AssetManager`** (`OfflineRecognizer(assetManager, …)`,
   paths relative to assets root) with `noCompress += "onnx"` so the `.onnx`
   files aren't compressed in the APK.
5. **AAR vendored, not fetched at build time.** sherpa-onnx is **not** on Maven
   Central — it ships as a GitHub release asset. We vendor
   `app/libs/sherpa-onnx-1.13.5.aar` (gitignored; see fetch script).
6. **TTS = Android system engine, not the host.** The design doc's "weak local
   model" TTS was for consistency with the host; for a "read this message
   aloud" button the built-in `TextToSpeech` is simpler and already offline.
7. **Multiple chats per host profile.** The `ai` plugin holds no session state,
   so full history is re-sent per call; chats are keyed by `profileId` (the AI
   is per-host).
8. **Default model per provider** (`effectiveModel()`), so chat works without
   configuring a model id; base_url/`api_key_env` prefilled for new profiles
   (Ollama defaults). The API key itself still never travels.

## Experience / gotchas

1. **sherpa-onnx AAR is ~47 MB and not on Maven.** `com.k2-fsa:sherpa-onnx`
   returns 0 results on Maven Central; the AAR (`v1.13.5`) is a GitHub release
   asset carrying `libonnxruntime.so` + `libsherpa-onnx-jni.so` for 4 ABIs
   (~47 MB total, 35 MB for the 3 ABIs we ship after `abiFilters`). Vendor it;
   don't expect `implementation("com.k2-fsa:…")`.
2. **Model sizes are ~10× what casual docs suggest.** "small zipformer ru" is
   ~105 MB f32, whisper-tiny ~110 MB. The int8 `zipformer-ru-int8` (~71 MB)
   from `vynkor-plugins/models` is the practical Russian choice; its `tokens.txt`
   uses BPE subwords (`▁`-prefixed) and `<blk>`/`<sos/eos>`/`<unk>` — the
   `bpe.model` file is **not** referenced by the transducer config (the host
   ignores it too).
3. **Verify the Kotlin API from `classes.jar`, not docs.** Extracting the AAR
   and `javap`-ing `com.k2fsa.sherpa.onnx.*` gave the authoritative signatures
   (`OfflineRecognizer(assetManager, config)`, `acceptWaveform(FloatArray, Int)`,
   `decode(stream)`, `getResult(stream).text`). Docs drift; the AAR doesn't.
4. **`noCompress += "onnx"`** keeps models readable via the asset file-descriptor
   path (compressed assets can't be memory-mapped).
5. **Model load is slow (seconds) and CPU-bound** — `SttEngine` loads on a
   single-thread executor once (process-wide), `transcribe` must be called on
   `Dispatchers.IO`, callbacks hop back to the main thread via `runOnUiThread`.
6. **APK is large**: ~188 MB debug (3 ABIs of onnxruntime/sherpa + 71 MB model +
   Rust core). Release with minify + ABI splits would cut this; moving the model
   to download-on-first-use is an option if the bundle is too heavy.
7. **`user_id` compile slip** — a nullable `?.trim().ifBlank {}` (needs
   `.orEmpty()` first) surfaced only at `compileDebugKotlin`; the `quick` agent
   didn't compile-check. Lesson: always compile-verify delegated edits, even
   trivial ones.

## Build / verify

```bash
# env: the system SDK (/opt/android-sdk) is root-owned; use the user SDK
env -u ANDROID_SDK_ROOT ANDROID_HOME=$HOME/.android-sdk ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (~188 MB, 3 ABIs)
```

STT assets must be present before building (see `scripts/fetch-stt-assets.sh`):

```bash
scripts/fetch-stt-assets.sh   # AAR + model; both gitignored
```

Verified: `assembleDebug` green; APK contains `assets/stt/{encoder,decoder,
joiner}.onnx + tokens.txt` and `lib/*/libsherpa-onnx-jni.so` +
`libonnxruntime.so` for arm64-v8a / armeabi-v7a / x86_64. STT **not** runtime-
tested on a device (no phone attached) — first mic tap loads the model
lazily (a few seconds).

## Next-session tasks

- [ ] **Runtime-test STT on the MI 6** (`./gradlew installDebug` → tap 🎤 in a
      chat): confirm model loads, transcript lands in the input, and error
      toasts work. This is the one thing compile-checking can't cover.
- [ ] **Opus codec for mic/speaker** (D-12 next leg): replace PCM passthrough
      with Opus (`FLAG_RAW_BINARY` + `AudioStreamChunk`), matching the host
      `stt`/`tts` plugins.
- [ ] **Keystore / EncryptedSharedPreferences** for `jwt_token`/`jwt_secret`/
      `cert_pem` (currently plaintext in `SharedPreferences`).
- [ ] **End-to-end voice pipeline**: mic → local STT → AI → TTS (caps exist;
      only the orchestration is missing).
- [ ] **Streaming `chat_completion`** (needs a kernel primitive; `ai` v1 is
      one-request/one-response).
- [ ] **Edit / regenerate / delete messages**, export transcript.
- [ ] **`user_id` plumbed end-to-end** (D-23 multi-user enforcement is a
      "flip on" once profiles carry it).
- [ ] **D-16 distribution** — signed release APKs + F-Droid metadata.
- [ ] **D-18 ed25519 enrollment** — replace the shared HS256 secret in the
      pairing QR with a per-device enrollment ticket.
- [ ] **Tier-2 capabilities** (camera, sms, screen, sensors, wifi, bluetooth,
      calendar, files, torch, …) as `{device_id}.{cap}`.
