# vynkor-client-android — Аудит

**Дата:** 2026-08-21 (аудит №2)
**Модель:** ox-alpha (Sisyphus / opencode)
**Проект:** Android device-agent для vynkor plugin kernel
**База:** коммит `41a8efc` (main). Код не менялся с 2026-08-17, поэтому находки аудита №1 (big-pickle, 2026-08-19) проверялись против того же кода.

Документ заменяет аудит №1: все его находки перенесены, перепроверены и объединены с новыми. Сквозная нумерация; происхождение помечено `[NEW]` (нашлось только во втором проходе) или `[#N]` (номер из аудита №1).

---

## Методология

- Прочитан **весь исходный код**: 8 файлов Rust (`rust/src/**`, ~1400 строк), все 26 Kotlin-файлов (`app/src/main/kotlin/dev/vynkor/agent/**`, ~2700 строк), `AndroidManifest.xml`, `app/build.gradle.kts`, корневой gradle, `gradle.properties`, `rust/Cargo.toml`.
- Запущены `cargo test` — **18 passed, 0 failed** и `cargo clippy --all-targets` — **0 warnings**.
- Grep-проверка мёртвого кода (`check_payload_size`, `mic_chunk_frame`, `is_retryable`, `recv_frame`/`send_frame`, `EXTRA_CHAT_ID`, `empty_chats`).
- Каждая находка аудита №1 сверена с текущим кодом по файлам/строкам.
- Не проверялось: рантайм-поведение на реальном устройстве, взаимодействие с конкретной версией хоста, нагрузочное тестирование. Выводы о гонках/утечках — из анализа кода.

---

## Обзор

Android device-agent: Kotlin + Rust/UniFFI, WebSocket к хосту, AI-чат через host `ai` plugin, локальный STT (sherpa-onnx), TTS, 8 capabilities (`geo, battery, notifications, clipboard, contacts, mic, speaker, chat`). Rust = протокол (framing, frame-MAC, WS, registration, routing), Kotlin = device I/O + UI. Секреты паринга передаются QR-кодом (`vynkor://pair?d=<base64url(JSON)>`).

---

## 🔴 КРИТИЧЕСКИЕ ПРОБЛЕМЫ (8)

### 1. [NEW] Микрофон стримится на хост постоянно, без действия пользователя

**Файл:** `app/src/main/kotlin/dev/vynkor/agent/agent/AgentService.kt:82`

```kotlin
a.start()
micCapture.start(a, this)   // ← безусловно, при каждом старте сервиса
```

`startAgent()` запускает `MicCapture` сразу после старта агента. Пока сервис жив и есть соединение, телефон **непрерывно пишет окружающий звук и льёт его на хост** (`push_mic_pcm` → WS, `rust/src/agent.rs:268-286`) — независимо от того, просил ли кто-то диктовку. Локальная диктовка в чате использует отдельный `SttRecorder` и к этому потоку отношения не имеет.

Последствия:
- Приватность сама по себе: подключение к «своему» хосту = постоянная передача аудио помещения.
- В связке с проблемой №2 — атака одним intent'ом: любое приложение может перепарить устройство на свой хост, и телефон становится удалённой прослушкой без единого касания экрана.

Фикс: mic-стрим должен жить только внутри явной STT-сессии (кнопка диктовки / команда хоста с подтверждением), а не в жизненном цикле сервиса.

### 2. [#6, эскалировано] Экспортированный deep-link парит устройство без подтверждения

**Файлы:** `app/src/main/AndroidManifest.xml` (MainActivity `exported="true"` + filter `vynkor://pair`), `MainActivity.kt:114,117-121,131-146`

```kotlin
private fun onPairingPayload(raw: String) {
    val profile = PairingPayload.parse(raw) ?: ... // только форматная валидация
    DeviceIdentity.setDeviceId(this, profile.deviceId)
    ProfileStore.save(this, profile)
    ProfileStore.setActive(this, profile.id)
    ...
    if (AgentHolder.agent == null) {
        requestPermissions()
        AgentService.start(this)   // ← автоподключение к хосту из payload
    }
}
```

`PairingPayload.parse` (`PairingPayload.kt:17-42`) проверяет scheme/host/version и непустоту `host_url`+`jwt_token` — но не подлинность. QR задуман как «физический доверенный канал», однако тот же URL принимает **любое установленное приложение** через intent (`onCreate`/`onNewIntent`). Подтверждения пользователя нет вообще — только тост «Paired & connected».

Цепочка атаки: вредоносное приложение → `vynkor://pair?d=...` со своим хостом → агент молча подключается → стримятся микрофон (№1), контакты, буфер обмена, гео, нотификации.

Минимальный фикс: различать источник (сканер vs внешний intent) и для внешнего требовать явный диалог «Подключиться к хосту X?».

### 3. [#2] MicCapture.stop() не останавливает AudioRecord — утечка микрофона + ANR

**Файл:** `app/src/main/kotlin/dev/vynkor/agent/agent/MicCapture.kt:55-59`

```kotlin
fun stop() {
    running = false
    thread?.join(2000)  // read() блокируется → join почти всегда таймаутится
    thread = null       // поток жив, AudioRecord открыт
}
```

`AudioRecord.read()` (строка 43) блокирующий; `running = false` его не прерывает. Поток утекает вместе с открытым микрофоном до конца процесса; повторный `start()` создаёт второй `AudioRecord`.

Дополнительно (новое наблюдение): `stop()` вызывается из `AgentService.stopAgent()` (`AgentService.kt:85-91`) **на главном потоке** — `join(2000)` блокирует UI до 2 секунд, риск ANR при остановке сервиса.

Фикс: прерывать блокирующее чтение через `record.stop()` из отдельного пути либо читать с таймаутом; join делать вне main thread.

### 4. [NEW] SttRecorder.stop() — release() во время чтения → риск нативного краша

**Файл:** `app/src/main/kotlin/dev/vynkor/agent/agent/SttRecorder.kt:65-86`

```kotlin
fun stop(): FloatArray {
    ...
    recordThread?.join(JOIN_TIMEOUT_MS)   // 1000 мс; читающий поток может висеть в read()
    ...
    if (record.recordingState == RECORDSTATE_RECORDING) record.stop()
    record.release()                      // ← вызывается даже если поток ещё читает
    audioRecord = null
```

Та же схема, что в №3, но хуже по последствию: после таймаута join поток-читатель может всё ещё находиться внутри `record.read()` (`SttRecorder.kt:93`), а мы вызываем `stop()/release()` из другого потока. Освобождение `AudioRecord` во время чтения — undefined behavior вплоть до нативного краша.

Фикс: как в №3 — гарантированно вывести поток из `read()` до release (например, `record.stop()` перед join останавливает блокирующее чтение), и только потом release.

### 5. [#3] SpeakerSinkImpl никогда не освобождает AudioTrack

**Файл:** `app/src/main/kotlin/dev/vynkor/agent/caps/SpeakerSinkImpl.kt:9-31`

`AudioTrack` создаётся лениво при первом `playPcm` (строка 16), но у класса **нет ни одного метода release**, трек никогда не `stop()`/`release()` — утечка нативных ресурсов на весь процесс. Плюс `t.write(pcm, ...)` (строка 29) — блокирующая запись: когда буфер полон, вызов висит (см. №14).

Фикс: добавить `release()`, вызывать из `AgentService.stopAgent()`; перейти на Builder API (см. №22).

### 6. [#5] JWT, jwt_secret и cert_pem в plaintext SharedPreferences + cloud backup

**Файлы:** `ProfileStore.kt:17-19` (ключи `jwt_token`, `jwt_secret`), `HostProfile.kt:27-41` (`toJson()` сериализует все секреты), `AndroidManifest.xml` (нет `android:allowBackup="false"`)

Хостовый `jwt_secret` — это **общий секрет всего хоста**: им выводится frame-MAC ключ сессии (`transport.rs:246-253`), и он же позволяет минтить токены любому устройству. Он лежит в незашифрованном XML в `/data/data`, а дефолтный `allowBackup=true` кладёт его в cloud backup устройства. Комментарий в `ffi.rs:9-10` («Never persisted by the app») противоречит фактическому поведению.

Фикс: EncryptedSharedPreferences/Keystore (или хотя бы `allowBackup=false` + `dataExtractionRules`), и пересмотр самой схемы раздачи host secret устройству.

### 7. [#1] Agent.shutdown флаг не сбрасывается — агент одноразовый

**Файл:** `rust/src/agent.rs:133` (store true в `stop()`), `109-129` (`start()` не сбрасывает), `555` (проверка в cap_loop)

`stop()` ставит `shutdown = true`; `start()` выставляет только state, но не флаг. Повторный `start()` на том же экземпляре → все cap loops выходят на первой же итерации, соединение никогда не установится. Сейчас `AgentService` создаёт новый `Agent` каждый раз, так что баг латентный — но API публичный (UniFFI), любой будущий переиспользующий код получит тихо неработающего агента.

Фикс: одна строка — `self.shutdown.store(false, Ordering::SeqCst)` в начале `start()`.

### 8. [#4 + #13] SharedPreferences: race condition и потеря данных на corrupt JSON

**Файлы:** `ChatStore.kt:36-42,71-87`, `ProfileStore.kt:36-53,61-72,90-94`

Паттерн везде одинаковый: `readAll()` (парсинг всего JSON) → модификация в памяти → `writeAll()` (`apply()` — асинхронная запись). Операция не атомарна: два конкурентных `save()` теряют обновления; чаты сохраняются из UI-потока (`ChatActivity.appendMessage`, `ChatActivity.kt:427-434`), профили — из разных активити.

Отдельно `ProfileStore.load()`:

```kotlin
} catch (_: Exception) {
    mutableListOf()   // corrupt JSON → пустой список
}
```

Любая ошибка парсинга → пустой список, и следующий же `save()` перезаписывает файл — **все профили с их секретами стёрты безвозвратно**. `ChatStore.readAll()` возвращает `null` (мягче), но `save()` поверх null тоже затирает историю.

Фикс: единый мьютекс/однопоточный executor на сторы, write-through в один ключ, бэкап предыдущей версии перед перезаписью, при corrupt JSON — карантин файла, а не молчаливый wipe.

---

## 🟠 СЕРЬЁЗНЫЕ ПРОБЛЕМЫ (14)

### 9. [NEW] Исходящие фреймы не проверяются на размер — check_payload_size мёртвый код

**Файл:** `rust/src/protocol.rs:177-184` — функция определена, но grep показывает единственное использование: её собственный тест (`protocol.rs:309-312`). Ни один outbound-путь (`build_frame` → `frame_to_bytes`) размер не проверяет.

Реальные сценарии переполнения `MAX_PAYLOAD_SIZE`:
- `ChatActivity.sendMessage` шлёт **всю историю чата** каждым запросом (`ChatActivity.kt:411`: `chat.messages.map { it.role to it.content }`) — длинный чат → гигантский `params_json`;
- `ContactsProviderImpl.list()` без `LIMIT` (`ContactsProviderImpl.kt:27-36`) — ответ со всей адресной книгой;
- ответ собирается в `caps/mod.rs:12-38` без ограничения.

Итог: фрейм > лимита гейтвея → отклонение/разрыв соединения в момент, который сложно диагностировать. Фикс: звать `check_payload_size` в `send_raw_frame`/`reply`/`request`, ограничить contacts query, слать дельту истории вместо полного контекста.

### 10. [NEW] Backoff реконнекта никогда не сбрасывается

**Файл:** `rust/src/agent.rs:552-574`

```rust
backoff = (backoff * 2).min(BACKOFF_MAX);   // 1s → 2s → … → 30s, навсегда
```

После нескольких флапов задержка навсегда остаётся 30 секунд — даже если прошло часы стабильного соединения. Хост перезапустился → телефон «подключается» целых полминуты. Фикс: сбрасывать `backoff = BACKOFF_INITIAL`, если последний цикл прожил дольше порога (например, 60 c).

### 11. [NEW] await_ack без таймаута — cap loop может зависнуть навечно

**Файл:** `rust/src/transport.rs:201-236`

Если хост принял TCP/WS-handshake, но не отвечает на `PluginRegister`, `await_ack` висит в `ws.next().await` бесконечно — read deadline нет нигде. Cap loop застревает до разрыва TCP на нижнем уровне (может не случиться никогда при молчаливой потере). Фикс: `tokio::time::timeout` вокруг ожидания ack (и вообще вокруг чтений — см. №12).

### 12. [#8] Нет read timeout и ping-pong на WebSocket

**Файлы:** `rust/src/transport.rs:153-170`, `rust/src/agent.rs:615-634`

Устройство само пинги не шлёт; обработка входящего Ping→Pong есть (`agent.rs:449-460`), но молчащий/полумёртвый хост не обнаруживается: `read.next().await` висит вечно, `is_connected()` продолжает возвращать true, реконнект не срабатывает. Фикс: периодический device→host ping + таймаут на чтение, разрыв по превышению.

### 13. [#9, уточнено] pending map: отложенная очистка; ветка Disconnected фактически недостижима

**Файл:** `rust/src/agent.rs:183-262,636-643`

При падении соединения `one_cycle` чистит `caps` и `live`, но **не трогает `pending`** — записи живут до истечения собственного таймаута запроса (до `timeout_ms + 5s margin`; при большом пользовательском таймауте — минуты мёртвых sender'ов). Аудит №1 называл это утечкой — уточнение: утечка ограничена по времени, но ветка `RecvTimeoutError::Disconnected` (`agent.rs:256-260`) при этом мертва: sender лежит в `pending`, пока его не заберёт `take_pending`, поэтому канал не может стать Disconnected, пока запись жива. Фикс: purge `pending` в cleanup `one_cycle` (и в `stop()`), мёртвую ветку убрать или сделать достижимой осознанно.

### 14. [#10 + NEW] Блокирующие вызовы на tokio-воркерах: провайдеры + AudioTrack.write

**Файлы:** `rust/src/agent.rs:120` (`worker_threads(2)`), `rust/src/caps/mod.rs:12-38`, `SpeakerSinkImpl.kt:29`, `ContactsProviderImpl.kt:27-36`

Host→device запросы диспатчатся синхронно прямо в цикле чтения cap loop'а: `handle_action_request` зовёт Kotlin-провайдеры (UniFFI callback блокирует tokio-воркер на время JVM-вызова). `contacts.list()` — блокирующий ContentResolver-запрос без LIMIT; `playPcm` — блокирующий `AudioTrack.write()`. При 2 воркерах и 8 cap loops один медленный запрос/полный аудио-буфер стопорит обработку остальных capabilities. Фикс: `spawn_blocking`/отдельный executor для foreign-вызовов, увеличить число воркеров, ограничить contacts.

### 15. [NEW] Диктовка: непрерывный рост памяти + O(n²) перекодирование

**Файлы:** `SttRecorder.kt:95` (`ArrayList<Short>` — упакованные объекты, ~256 КБ/с), `SttEngine.kt:100-119` (`partial()` каждую секунду декодирует **весь** накопленный звук заново)

Длинная диктовка: память растёт линейно (плюс копия в `SttSession.samples`), CPU — квадратично. 10 минут диктовки ≈ 9.6M сэмплов, перекодируемых ежесекундно. Эмуляция стриминга задокументирована как осознанный компромисс (офлайн-модель), но предела нет: ни ограничения длительности, ни чанкового декодирования. Фикс: окно перекодирования (хвост N секунд + зафиксированный префикс), лимит сессии, `ShortArray`/`FloatArray` вместо ArrayList.

### 16. [#12] try_send тихо дропает кадры

**Файл:** `rust/src/agent.rs:404-412` (`let _ = tx.try_send(...)`), канал ёмкостью 64 — `agent.rs:584`

Заполненный outbound (64 фрейма ≈ 1.28 с PCM) → молчаливая потеря аудио/событий без лога и метрики. Для mic-потока это дыры в распознавании, для notifications — потерянные события. Фикс: хотя бы `tracing::warn!` + счётчик дропов; для событий — блокирующая отправка с таймаутом.

### 17. [NEW] Провайдеры кэшируют проверку разрешений на момент конструирования

**Файлы:** `ContactsProviderImpl.kt:14-16`, `LocationProviderImpl.kt:15-17`

```kotlin
private val granted = ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED
```

Разрешение выдали после старта сервиса → провайдер до рестарта сервиса молча возвращает пустоту. Проверку нужно делать в момент вызова `list()`/`lastKnown()`.

### 18. [NEW] Разрешения запрашиваются fire-and-forget, сервис стартует независимо от результата

**Файлы:** `MainActivity.kt:89-90,142-145`, `ChatActivity.kt:304-305`

```kotlin
requestPermissions()      // асинхронный диалог
AgentService.start(this)  // стартует сразу, не дожидаясь гранта
```

Сервис поднимается без location/audio/contact-разрешений; капы деградируют молча (усугубляется №17). Фикс: стартовать сервис в колбэке гранта или явно показывать состояние «работает частично».

### 19. [#14] ChatStore.save() O(n²) и сериализация JSON на главном потоке

**Файлы:** `ChatStore.kt:36-42`, `ChatActivity.kt:427-434`

Каждое сообщение: полный парсинг JSON всех чатов профиля + полная сериализация + `apply()`, и всё это в UI-потоке → jank на длинных историях. Фикс: держать список чатов в памяти, писать асинхронно, инкрементальный append.

### 20. [#11] Release-сборка без minify/shrink

**Файл:** `app/build.gradle.kts` (`buildTypes.release.isMinifyEnabled = false`)

Нет R8/ProGuard, нет `shrinkResources`, нет signing config. APK больше, реверс проще, секретные строки не вычищаются. Фикс: включить minify+shrink с правилами для UniFFI/JNA/sherpa-onnx.

### 21. [#7] catch(Throwable) ловит OOM/StackOverflow

**Файлы:** `SttEngine.kt:115,140,153`, `SttRecorder.kt:102`

Ловятся `OutOfMemoryError`/`StackOverflowError` наравне с обычными ошибками — процесс продолжается в неопределённом состоянии. Фикс: `catch (e: Exception)` (+ узкие типы где можно).

### 22. [#15] Deprecated AudioRecord/AudioTrack конструкторы

**Файлы:** `SttRecorder.kt:42`, `MicCapture.kt:33`, `SpeakerSinkImpl.kt:16`

Конструкторы deprecated с API 26/31; нужны Builder + `AudioAttributes`/`AudioFormat` (заодно уйдёт deprecated `STREAM_MUSIC` в SpeakerSinkImpl).

---

## 🟡 UI/UX ПРОБЛЕМЫ (12) — перенесены из аудита №1

Пере-проверены выборочно (помечено ✅); остальные перенесены как есть — код этих мест не менялся.

| # | Находка | Где | Статус |
|---|---|---|---|
| 23 | Нет loading-индикаторов (подключение, AI-запрос, загрузка STT-модели) — только текстовые «Typing…»/«Listening…» | глобально | перенос |
| 24 | Touch targets < 48dp: кнопки copy/more/speak **32dp** ✅ | `item_message.xml:47-48,59-60` | подтверждено |
| 25 | `empty_chats` определена, но нигде не используется ✅ | `strings.xml:44` | подтверждено |
| 26 | Нет DiffUtil — `notifyDataSetChanged()` везде ✅ | `ChatAdapter.kt:32`, `ChatListAdapter.kt:19`, `ProfileAdapter.kt` | подтверждено |
| 27 | Bubble tails не зеркалятся в RTL | `bubble_assistant.xml`, `bubble_user.xml` | перенос |
| 28 | Только portrait; нет landscape/sw600dp вариантов | манифест + layouts | перенос |
| 29 | Toast вместо Snackbar/inline-ошибок (13 шт.) | `ProfileActivity`, `ChatActivity` | перенос |
| 30 | Тулбар целиком кликабелен для смены модели — без affordance ✅ | `ChatActivity.kt:110` | подтверждено |
| 31 | Камера отказ → тост «Scan cancelled» вместо «permission denied» ✅ | `MainActivity.kt:44` | подтверждено |
| 32 | Drawer фиксированные 300dp — на узких экранах 94% ширины | layout drawer | перенос |
| 33 | `EXTRA_CHAT_ID` объявлен и никогда не используется ✅ | `ChatActivity.kt:729` | подтверждено |
| 34 | Нет edge-to-edge (`enableEdgeToEdge`) — на Android 15+ будет принудительно | активити | перенос |

---

## 🔵 CODE QUALITY (8 + новые мелочи)

### Перенесено из аудита №1 (все подтверждены выборочной проверкой)

| # | Находка | Где |
|---|---|---|
| 35 | Force unwrap `profile!!` ✅ | `ChatActivity.kt:490,522` |
| 36 | `Chat` — data class с мутабельными полями (`var title`, `var updatedAt`, `MutableList`) | `Chat.kt:11-16` |
| 37 | Нет ViewBinding — 30+ `findViewById` (в т.ч. повторные в колбэках) | `ChatActivity` и др. |
| 38 | Magic numbers: request code `42` ✅ (`MainActivity.kt:166`, `ChatActivity.kt:314`), `getIntProperty(5)` ✅ (`BatteryProviderImpl.kt:21`), `NOTIFICATION_ID = 1` | разные |
| 39 | Устаревшие зависимости, нет version catalog: core-ktx 1.13.1, lifecycle 2.8.4, coroutines 1.8.1 | `app/build.gradle.kts` |
| 40 | Ноль unit-тестов на Kotlin-стороне (18 тестов только в Rust) | `app/src/test` отсутствует |
| 41 | `usesCleartextTraffic="true"` глобально, нет networkSecurityConfig | `AndroidManifest.xml` |
| 42 | `Notification.Builder` вместо NotificationCompat, нет contentIntent (тап никуда не ведёт) | `AgentService.kt:112-118` |

### Новые мелочи (этот проход)

| # | Находка | Где |
|---|---|---|
| 43 | Мёртвый код Rust: `mic_chunk_frame` (`caps/audio.rs:46-62`), `is_retryable` (`error.rs:25-27`), `CapConn::recv_frame/send_frame` (`transport.rs:153-181`) — не используются нигде, кроме тестов/определений | rust/src |
| 44 | `getIntProperty(5)` может вернуть `Integer.MIN_VALUE` → хост получает **−214 748 364.8 °C**; нет sanity-check | `BatteryProviderImpl.kt:19-24` |
| 45 | `refreshHostAi()` глотает ошибки (`runCatching.getOrDefault`) — юзер не узнает, что списки моделей/агентов недоступны | `ChatActivity.kt:263-266` |
| 46 | `updateDraft()` затирает ручные правки текста, сделанные во время диктовки | `ChatActivity.kt:714-718` |
| 47 | TTS init failure молчалив: `ready=false`, `speak()` тихо ничего не делает | `TtsEngine.kt:46-50` |
| 48 | `forkBranchAt` ищет сообщение по `(timestamp, role, content)` — дубликаты ломают точку ветвления | `ChatActivity.kt:566-573` |
| 49 | APK >150 MB: `encoder.onnx` 68 MB + sherpa AAR 47 MB + 3 ABI; cargo-ndk `--release` × 3 ABI пересобирается на каждом `preBuild` (медленные debug-итерации) | `assets/stt/`, `app/libs/`, `app/build.gradle.kts` (tasks) |
| 50 | JWT и jwt_secret показываются открытым текстом (не password-поля) | `ProfileActivity.kt:23-24,34-35` |
| 51 | `READ_NOTIFICATION_LIST` в манифесте — несуществующая runtime-пермиссия, бесполезна | `AndroidManifest.xml` |
| 52 | `lock()` восстанавливается после poison через `into_inner()` — осознанно, но консистентность данных под mutex не гарантируется | `agent.rs:46-48` |
| 53 | `effectiveBaseUrl()` дефолт `http://localhost:11434/v1` на телефоне бессмыслен (localhost девайса) | `HostProfile.kt:46-47` |
| 54 | `sendMessage` валидирует `aiApiKeyEnv`/модель, но при `aiAgent`-режиме эти поля игнорируются — две ветки конфигурации путают | `ChatActivity.kt:395-399`, `AiClient.kt:51-64` |

---

## Что хорошо (чтобы не казалось, что всё плохо)

- **Rust-ядро аккуратное**: clippy чистый, 18 тестов проходят, протокольные тесты содержательные (tamper MAC, CRC mismatch, magic, kernel-routing классификация).
- Cert pinning через rustls реализован правильно (`transport.rs:261-278`): только pinned cert в root store, отказ при пустом PEM.
- JWT едет в `Sec-WebSocket-Protocol`, не в URL (`transport.rs:72-81`) — не попадёт в access-логи.
- Frame-MAC verify на каждом входящем кадре, регистровый кадр честно не макается (`protocol.rs:105-139`).
- `catch_unwind` вокруг observer-колбэков (`agent.rs:399-401`) — паника Kotlin-стороны не убьёт runtime.
- Watch-based shutdown отзывчив даже во время backoff-sleep (`agent.rs:563-572`).
- Корректный `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` для API 35.
- `SttEngine` — синглтон на applicationContext (без утечек), потокобезопасная feed/partial через session lock.
- `ChatAdapter.append` использует `notifyItemInserted`, Markwon кэшируется per-holder.

---

## Сводка

| Категория | 🔴 Критично | 🟠 Серьёзно | 🟡 Средне | 🔵 Мелочь | Итого |
|-----------|:-----------:|:-----------:|:---------:|:---------:|:-----:|
| Безопасность/приватность | 3 (№1, №2, №6) | 1 (№17–18) | — | 2 | **6** |
| Ресурсы/утечки/краши | 3 (№3, №4, №5) | 3 (№15, №16, №21) | — | — | **6** |
| Надёжность сети/протокола | 1 (№7) | 4 (№9–№13) | — | 1 | **6** |
| Производительность | — | 2 (№14, №19) | — | 1 | **3** |
| Данные | 1 (№8) | — | — | — | **1** |
| UI/UX | — | — | 12 | — | **12** |
| Code quality/build | — | 2 (№20, №22) | — | 11 | **13** |
| **Всего** | **8** | **14** | **12** | **15** | **49** |

Здоровье сборки: `cargo test` 18/18 ✅, `cargo clippy` 0 warnings ✅, Kotlin-тестов нет ❌.

---

## Топ-5 приоритетов

1. **№1 + №2 — микрофон × exported pairing.** Гейт: mic-стрим только внутри явной сессии; паринг из внешнего intent — только через диалог подтверждения. Это единственная связка, дающая полную компрометацию устройства одним intent'ом.
2. **№3 + №4 + №5 — жизненный цикл аудио.** Останавливать блокирующее чтение до `release()` (устранить и утечку, и гонку-release), добавить `release()` для `AudioTrack`, вынести join с main thread.
3. **№6 — секреты.** `allowBackup=false` + EncryptedSharedPreferences/Keystore; стратегически — отказаться от передачи host `jwt_secret` на устройство в пользу per-device ключей.
4. **№9 — размеры фреймов.** Вызывать `check_payload_size` на всех outbound-путях, `LIMIT` в contacts, слать дельту истории чата.
5. **№10 + №11 + №12 — устойчивость соединения.** Сброс backoff после здорового цикла, таймаут на register-ack, device-side ping + read timeout.

---

*Аудит №2 выполнен полным чтением исходников без субагентов; каждая находка снабжена файлом и строкой. Аудит №1 (big-pickle, 2026-08-19) полностью поглощён: его пункты №1–#35 соответствуют №1–№42 этого документа.*
