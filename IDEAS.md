# vynkor-client-android — Ideas

> **Это не roadmap, а коллекция идей.** Ничего не привязано к датам.
> Статус проекта: **D-14 alpha**. Rust core + Android app построены, E2E verified
> против живого kernel по LAN. 7 Tier-1 capabilities зарегистрированы и работают.

Цель — превратить прототип в production-ready device-agent, который
хост может использовать для реальных автоматизаций. Хост = "brain",
устройство = "sensors + actuators".

**Distribution:** sideloaded APK. Пользователи собирают сами или скачивают
готовое. Google Play — не primary target.

---

## Tier 1 — Немедленно (Q1 2026)

### 1. Quick Settings Tile — ✅ РЕАЛИЗОВАНО (F1, 2026-08-26)

`AgentTileService`: тап = start/stop агента, состояние ACTIVE/INACTIVE,
подпись Running/Stopped. Запуск из tile сознательно не запрашивает пермиссии
(провайдеры проверяют гранты на каждый вызов).

**Что:** Кнопка запуска/остановки агента в панели быстрого доступа Android
(свайп вниз → tile с иконкой vynkor).

**Зачем:** Сейчас чтобы запустить агента — открыть приложение → дождаться
`autoConnect()`. 3-4 тапа + ожидание. QS Tile даёт **один тап** из любого
экрана.

**Как помогает:**
- Мгновенный toggle без открытия приложения
- Видимый статус (active/inactive) в панели быстрого доступа
- Поведение как у Bluetooth/WiFi тоглов — привычный UX

**Реализация:** `TileService` (Android 7+). При тапе — `AgentService.start()/stop()`.

---

### 2. Battery Doze Resilience

**Что:** Защита foreground service от оптимизатора батареи (Doze, App Standby,
OEM battery optimizers — Samsung/MIUI/Huawei).

**Зачем:** На китайских OEM (Xiaomi, Oppo, Vivo) система убивает фоновые
сервисы агрессивнее stock Android. Пользователь видит "агент отключился" и
не понимает почему.

**Как помогает:**
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` при первом запуске
- Инструкции по отключению battery optimization для MIUI/EMUI
- WorkManager heartbeat для восстановления соединения после kill

**Реализация:** Detect OEM → show custom instructions → request battery bypass.

---

### 3. Biometric Auth

**Что:** Вход в приложение по отпечатку / Face ID + шифрование credentials.

**Зачем:** JWT токен хранится в SharedPreferences открытым текстом. Если
телефон unlocked — любой может открыть приложение и увидеть все credentials.
Physical access = full compromise.

**Как помогает:**
- `BiometricPrompt` (AndroidX) для входа
- `EncryptedSharedPreferences` с `MasterKey` protected biometric
- Без biometric — read-only mode (viewing, не settings)
- Session timeout: N минут без активности → auto-lock

**Реализация:**
- `BiometricGuardedStorage` wrapper для SharedPreferences
- При первом запуске — setup biometric
- При каждом `onResume` — check biometric state

---

### 4. Live Connection Status

**Что:** Реалтайм-индикатор состояния соединения в UI.

**Зачем:** Пользователь заходит в приложение и видит "Connected" — но
соединение упало 5 минут назад. Мёртвый UI = недоверие к продукту.

**Как помогает:**
- `AgentHolder.connectionState` (StateFlow) уже есть
- Polling `isConnected()` каждые 5-10 секунд
- Индикатор меняет цвет в реальном времени (green/red)
- Drawer: статус обновляется live

---

### 5. Certificate Pinning (TLS)

**Что:** Закрепление TLS-сертификата хоста для защиты от MITM.

**Зачем:** Сейчас `tls: false` (LAN тест) или webpki-roots. Self-signed
сертификат = либо не подключается, либо без шифрования. Любой в LAN
может перехватить JWT и frame-MAC.

**Как помогает:**
- При pairing хост передаёт `cert_pem` (уже есть в PairingPayload)
- SSLContext с pinned cert через自定义 TrustManager
- Защита всего трафика от嗅еров

---


### 6. Onboarding / Setup Wizard (NEW)

**Что:** Пошаговый мастер подключения нового устройства к хосту.

**Зачем:** Сейчас pairing = QR scan → manually enter Host URL + Device ID + JWT.
Для нового пользователя это 3-4 шага с копированием. Wizard = guided flow
с валидацией на каждом шаге. Без wizard — 50% пользователей запутаются
в credentials.

**Flow:**

```
Шаг 1: Welcome
  → "Подключите телефон к вашему vynkor kernel"
  → Кнопка: "Начать подключение"

Шаг 2: Host Discovery
  → Автопоиск хоста через mDNS (если в той же сети)
  → Или: ручной ввод Host URL (ws://192.168.1.x:port)
  → Валидация: connectivity check (ping)
  → Вариант: QR code с host URL (сканирует camera)

Шаг 3: Device Identity
  → Ввод Device ID (или auto-generate: "phone-1", "bedroom-phone")
  → Валидация: уникальность в mesh

Шаг 4: Authentication
  → Ввод Device JWT (или host генерирует через QR)
  → Ввод Host jwt_secret (для frame-MAC)
  → Валидация: test connection

Шаг 5: Permissions
  → Список необходимых permissions с объяснением:
    - Location (для geo capability)
    - Notifications (для notification forwarding)
    - Microphone (для voice capabilities)
    - Contacts (для contacts capability)
    - Camera (для flashlight, QR scan)
  → "Все permissions опциональны. Без них — limited mode."
  → Request permissions one-by-one с объяснением зачем

Шаг 6: First Connection
  → "Подключение к хосту..."
  → Показывает registered capabilities
  → "Готово! Ваш телефон подключён как device-agent."

Шаг 7: Profile Setup (опционально)
  → "Настройте профили для автоматизации"
  → Quick setup: Home / Driving / Sleep (based on WiFi + schedule)
  → Или: "Настроить позже" (skip)
```

**Как помогает:**
- Каждый шаг = валидация (не "введите JWT и надейтесь")
- Автопоиск хоста через mDNS (когда mesh будет)
- QR code для быстрого pairing (host генерирует QR с credentials)
- Progressive permissions (не все сразу)
- Quick profile setup (auto-detect home WiFi, suggest driving profile)

**Реализация:**
- `SetupActivity` — step-by-step wizard UI
- `HostDiscovery` — mDNS scan + manual URL
- `QrScanner` — camera scan для host URL / JWT
- `PermissionManager` — guided permission requests
- `FirstConnectionTest` — validate everything works

**Особенности:**
- Можно перезапустить wizard из Settings
- Wizard показывает current status: "Подключено к vynkor на 192.168.1.100"
- "Test connection" button на каждом шаге

---


## Tier 1.5 — Basic Device Controls (NEW)

> **Самые частые команды, которых нет в roadmap.** 80% ежедневных
> автоматизаций — базовые device controls. Без них device agent =
> "glorified clipboard".

### 7. WiFi Control

**Что:** Включение/выключение WiFi, сканирование сетей, подключение.

**Зачем:** Хост говорит "выключи WiFi" — телефон выключает. Сейчас это
3-4 тапа вручную. Автоматизации: "выключи WiFi ночью для экономии батареи".

**Как помогает:**
- `WifiManager.setWifiEnabled(true/false)` (API 29+ через Settings panel)
- `WifiManager.startScan()` → список доступных сетей
- Capability: `device.wifi`

**Реализация:** Новый UniFFI trait `WifiProvider`.

---

### 8. Bluetooth Control

**Что:** Включение/выключение Bluetooth, сканирование, управление pairing.

**Зачем:** "Включи Bluetooth" → одно действие с хоста. Автоматизации:
"когда подключились наушники — включи музыку".

**Как помогает:**
- `BluetoothAdapter.enable()/disable()`
- `BluetoothAdapter.getBondedDevices()` → список paired
- `BluetoothAdapter.startDiscovery()` → nearby devices
- Capability: `device.bluetooth`

**Реализация:** Новый UniFFI trait `BluetoothProvider`.

---

### 9. Do Not Disturb (DND)

**Что:** Включение/выключение DND, настройка правил.

**Зачем:** Хост: "Включи DND" → телефон молчит. Автоматизации:
"во время встречи — DND", "ночью — DND".

**Как помогает:**
- `NotificationManager.setInterruptionFilter()` (API 23+)
- `DNDPolicy`:谁 может звонить (favorites, contacts, nobody)
- Capability: `device.dnd`

**Реализация:** Новый UniFFI trait `DndProvider`.

---

### 10. Ringer Mode

**Что:** Переключение режимов звона: silent / vibrate / normal.

**Зачем:** "Поставь на вибрацию" — одна команда с хоста.

**Как помогает:**
- `AudioManager.setRingerMode(RINGER_MODE_SILENT / VIBRATE / NORMAL)`
- Capability: `device.ringer`

**Реализация:** Новый UniFFI trait `RingerProvider`.

---

### 11. Brightness Control

**Что:** Изменение яркости экрана.

**Зачем:** "Прибавь яркость" / "Уменьшь яркость" / "Автоматическая яркость".
Каждый день × N раз.

**Как помогает:**
- `Settings.System.putInt(SCREEN_BRIGHTNESS, value)` (0-255)
- `Settings.System.putInt(SCREEN_BRIGHTNESS_MODE, AUTO)` / MANUAL
- Capability: `device.brightness`

**Реализация:** Новый UniFFI trait `BrightnessProvider`.

---

### 12. Flashlight Control

**Что:** Включение/выключение фонарика.

**Зачем:** "Включи фонарик" — одной командой. Не нужно искать в шторке.

**Как помогает:**
- `CameraManager.setTorchMode(true/false)` (API 23+)
- Capability: `device.flashlight`

**Реализация:** Новый UniFFI trait `FlashlightProvider`.

---

### 13. Auto-rotate Control

**Что:** Включение/выключение автоматического поворота экрана.

**Зачем:** "Заблокируй поворот" — когда читаешь лёжа.

**Как помогает:**
- `Settings.System.putInt(ACCELEROMETER_ROTATION, 0/1)`
- Capability: `device.autorotate`

---

### 14. Airplane Mode Control

**Что:** Включение/выключение авиарежима.

**Зачем:** Автоматизации: "в полёте → airplane mode on → экономия батареи".

**Как помогает:**
- `Settings.Global.putInt(AIRPLANE_MODE_ON, 0/1)` (требует root или特殊 permissions)
- `ConnectivityManager.sendRequestNetwork()` для управления
- Capability: `device.airplane`

---

### 15. Hotspot Control

**Что:** Включение/выключение мобильного hotspot.

**Зачем:** "Подели интернетом" — одной командой. Автоматизации:
"когда нет WiFi — включи hotspot для других устройств".

**Как помогает:**
- `ConnectivityManager.startTethering()` (API 24+)
- Capability: `device.hotspot`

**Реализация:** Новый UniFFI trait `HotspotProvider`.

---

### 16. Alarm/Timer Management

**Что:** Установка будильников и таймеров с хоста.

**Зачем:** "Поставь будильник на 7:00 завтра" — хост управляет.
Не нужно открывать Clock app.

**Как помогает:**
- `AlarmManager.set()` для будильников
- `CountDownTimer` для таймеров
- Capability: `device.alarm`

**Реализация:** Новый UniFFI trait `AlarmProvider`.

---

## Tier 2 — Высокий приоритет (Q2 2026)

### 17. Home Screen Widgets

**Что:** Набор виджетов для home screen с quick actions.

**Зачем:** Чтобы отправить сообщение — открыть приложение → найти чат.
Виджет = мгновенный доступ к frequently used actions с home screen.

**Типы виджетов:**

| Виджет | Размер | Функция |
|---|---|---|
| Status Widget | 2x1 | Иконка + "Agent: Online/Offline". Tap → ChatActivity. Long press → toggle |
| Quick Chat | 4x1 | Text input + send. Отправка сообщения без открытия приложения |
| Device Status | 2x2 | Battery %, WiFi, connection, mini geo-map. Tap → детали |
| Action Buttons | 4x1 | Toggle buttons: Agent, Flashlight, DND, Record. Material You colors |

**Реализация:** `AppWidgetProvider` + `RemoteViews` + `AppWidgetManager.updateAppWidget()`.

---

### 18. App Launcher Capability

**Что:** Запуск приложений на устройстве по команде с хоста.

**Зачем:** Хост говорит "открой WhatsApp" — телефон открывает. Базовый
building block для voice-интеграций и автоматизаций.

**Как помогает:**
- `PackageManager.getLaunchIntentForPackage()` → `startActivity()`
- Capability: `device.launcher`
- Хост: `{"package": "com.whatsapp"}` → телефон открывает

**Реализация:** Новый UniFFI trait `LauncherProvider` в Rust core.

---

### 19. Notification Actions (Reply/Dismiss)

**Что:** Двусторонний канал: хост может отвечать на уведомления и отклонять их.

**Зачем:** Сейчас `on_notification` — односторонний. Хост видит "SMS от Mom"
но не может ответить. Reply actions превращают уведомления в interaction.

**Как помогает:**
- `NotificationListenerService.getActiveNotifications()` + reply action
- Хост: `{"key": "notif_123", "action": "reply", "text": "привет!"}`
- Устройство: `RemoteInput` для reply

**Реализация:** Добавить reverse channel в существующий NotificationListener.

---

### 20. SMS / Calls Read

**Что:** Чтение SMS-истории и лога звонков.

**Зачем:** AI-ассистенту нужен context: "кто звонил?" → ответ из данных
телефона. Без этого — "я не знаю, проверь телефон".

**Как помогает:**
- `ContentResolver` + `Telephony.Sms.CONTENT_URI` / `CallLog.CONTENT_URI`
- Capabilities: `device.sms`, `device.calls`
- Хост: JSON со списком сообщений/звонков

**Реализация:** Новые UniFFI traits `SmsProvider`, `CallsProvider`.

---

### 21. Clipboard Sync (Bi-directional)

**Что:** Clipboard синхронизация в реальном времени.

**Зачем:** Сейчас clipboard = request/response (хост спрашивает).
Би-directional = скопировал на телефоне → вставил на компьютере.

**Как помогает:**
- `ClipboardManager.OnPrimaryClipChangedListener` (уже есть для push)
- Хост хранит последний clipboard value
- При paste на хосте — берёт из device clipboard

**Реализация:** Push clipboard changes через WebSocket.

---

### 22. Screen Control (Accessibility Service)

**Что:** Скриншоты, tap/swipe жесты, чтение UI-дерева.

**Зачем:** Phone как "remote desktop" — хост видит экран и управляет им.
Remote support, UI automation, мониторинг.

**Как помогает:**
- `AccessibilityService` + `takeScreenshot()` (Android 11+)
- Capabilities: `device.screenshot`, `device.tap`, `device.swipe`
- Хост получает PNG + координаты

**Реализация:** Accessibility Service (требует ручного включения).

---

### 23. Geofencing Automation

**Что:** Хост задаёт гео-заборы. При входе/выходе из зоны — триггер.

**Зачем:** "Прихожу домой → включаю WiFi. Ухожу → выключаю." Сейчас это
Tasker/IFTTT. Device agent = централизованная автоматизация для всех устройств.

**Как помогает:**
- `GeofencingClient` на Android side
- Хост: `{"lat": 55.75, "lng": 37.62, "radius": 100, "action": "enter"}`
- При триггере → event на хост → автоматизация

**Реализация:** Новый capability `device.geofence`.

---

### 24. Battery-Aware Mode

**Что:** Агент снижает частоту опроса capabilities при низком заряде.

**Зачем:** Geo обновляется с фиксированным интервалом. При 10% батареи —
каждый push = 0.5% батареи. Через 20 обновлений — телефон мёртв.

**Как помогает:**
- Battery poll → при < 20%: geo interval ×4, mic stop
- При < 5%: only critical (battery + notifications)
- Хост: `{"type": "power_save", "level": 15}`

**Реализация:** Kotlin-side logic в AgentService.

---

### 25. WiFi Network Management

**Что:** Сканирование WiFi сетей, подключение к конкретной сети, авто-подключение по контексту.

**Зачем:** Автоматизации: "подключись к рабочей WiFi когда пришёл на работу".
"Выключи WiFi когда не нужен."

**Как помогает:**
- `WifiManager.startScan()` → список доступных сетей (SSID, RSSI, security)
- `WifiManager.addNetwork(WifiConfiguration)` → подключение
- Хост: `{"action": "connect", "ssid": "Office_WiFi", "password": "..."}`
- Capability: `device.wifi.scan`, `device.wifi.connect`

**Реализация:** Расширение существующего WifiProvider.

---

### 26. Bluetooth Device Management

**Что:** Управление Bluetooth-устройствами: список paired, сканирование nearby, auto-action on connect.

**Зачем:** Автоматизации: "наушники подключились → включи Spotify".
"Автомобиль Bluetooth → driving mode."

**Как помогает:**
- `BluetoothAdapter.getBondedDevices()` → список paired
- `BluetoothAdapter.startDiscovery()` → nearby devices + RSSI
- Broadcast receiver: `BluetoothDevice.ACTION_ACL_CONNECTED`
- Хост: `{"device": "JBL_Headphones", "on_connect": "play_music"}`
- Capability: `device.bluetooth.scan`, `device.bluetooth.pair`

**Реализация:** Новый UniFFI trait `BluetoothDeviceProvider`.

---

### 27. Contact Write

**Что:** Запись контактов в телефон (не только read из #19).

**Зачем:** Хост: "Сохрани номер Ивана: +79161234567". Автоматизации:
"Получил SMS от нового номера → сохрани contact".

**Как помогает:**
- `ContentResolver.insert(ContactsContract.Contacts.CONTENT_URI, ...)`
- `ContentResolver.insert(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ...)`
- Capability: `device.contacts.write`

**Реализация:** Расширение существующего ContactsProvider.

---

### 28. Calendar Write

**Что:** Создание/редактирование событий календаря (не только read из #35).

**Зачем:** Хост: "Поставь встречу на 15:00 с Иваном в кафе".
AI может планировать расписание.

**Как помогает:**
- `ContentResolver.insert(CalendarContract.Events.CONTENT_URI, ...)`
- `ContentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ...)`
- Capability: `device.calendar.write`

**Реализация:** Расширение существующего CalendarProvider.

---

### 29. File Transfer (Bi-directional)

**Что:** Передача файлов между устройствами (не только clipboard text).

**Зачем:** Clipboard = текст. File transfer = PNG / PDF / ZIP / ANY.
"Отправь скриншот с телефона на ПК". "Загрузи документ с VPS на планшет."

**Как помогает:**
- WebSocket binary frames (уже есть в vynkor-wire)
- Chunked transfer для больших файлов
- Хост: `{"action": "send_file", "path": "/sdcard/photo.jpg", "target": "laptop"}`
- Capability: `device.files.send`, `device.files.receive`

**Реализация:** Новый UniFFI trait `FileTransferProvider`.

---


### 30. App Notification Filter (NEW)

**Что:** Гранулярный контроль какие уведомления пересылать на хост.

**Зачем:** 200 notifications/day → information overload. Сейчас: всё или ничего.
Filter = "только WhatsApp, SMS, Calls. Пропускай Instagram, TikTok, games."

**Фильтры:**

| Filter Type | Пример | Действие |
|---|---|---|
| **Whitelist** | `["com.whatsapp", "com.android.mms"]` | Только эти apps → forward |
| **Blacklist** | `["com.instagram.android", "com.zhiliaoapp.musically"]` | Всё кроме этих → forward |
| **Priority** | `from: "boss" → always forward` | По содержимому/sender |
| **Time-based** | `23:00-07:00 → batch only` | По времени суток |
| **Profile-based** | `driving → urgent only` | Зависит от текущего profile |

**UI:**
- Settings → Notification Filter
- List installed apps с toggle (forward / skip)
- Quick filters: "All", "Important only", "Calls + SMS only", "Custom"
- Per-app settings: "Forward always" / "Forward in urgent only" / "Never forward"

**Реализация:**
- `NotificationFilter` — фильтрует перед forward
- `AppInfoResolver` —олучает app name + icon для UI
- Settings: `SharedPreferences` (filtered apps)
- Capability: расширение `device.notifications`

---



### 31. Quick Actions / Macros (NEW)

**Что:** Один тап = несколько действий. Пользователь создаёт macros.

**Зачем:** "Пришёл домой" = WiFi ON + DND OFF + high-frequency geo + auto-reply OFF.
Сейчас: 4 отдельных команды. Quick Action = **одна кнопка**.

**Built-in macros:**

| Macro | Actions |
|---|---|
| **Home** | WiFi ON, DND OFF, geo 5min, auto-reply OFF, full features |
| **Driving** | WiFi OFF(?), DND ON, geo 30s, auto-reply "За рулём", hands-free |
| **Meeting** | DND ON, geo 15min, auto-reply "На встрече", notifications batch |
| **Sleep** | DND ON, geo 1h, power save, mic OFF, speaker OFF |
| **Work** | WiFi ON (office), DND work-only, geo 15min, notification priority |
| **Gym** | DND ON, geo 5min, auto-reply "В зале", music priority |

**Custom macros:**
```json
{
  "name": "Weekend Morning",
  "icon": "coffee",
  "actions": [
    {"type": "wifi", "value": "on"},
    {"type": "dnd", "value": "off"},
    {"type": "geo_interval", "value": 300},
    {"type": "auto_reply", "value": "off"},
    {"type": "profile", "value": "home"}
  ]
}
```

**UI:**
- Widget: 4x2 с 4 кнопками macros
- QS Tile: long press → macro picker
- Drawer: macro list с drag-to-reorder
- Хост: `{"action": "run_macro", "name": "home"}`

**Реализация:**
- `MacroManager` — хранит и применяет macros
- `MacroWidget` — home screen widget
- `MacroTileService` — QS Tile с quick switch
- Capability: `device.macro`

---



### 32. Host Dashboard (web UI) (NEW)

**Что:** Веб-интерфейс на хосте для управления всеми устройствами.

**Зачем:** Сейчас: `GET /devices` → JSON. Веб-интерфейс = **face of product**.
Пользователь видит все устройства в одном месте, управляет, мониторит.

**Экраны:**

```
┌─────────────────────────────────────────────────────┐
│  vynkor Dashboard                         [Settings]│
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ Phone₁   │  │ Phone₂   │  │ Laptop   │          │
│  │ ● Online │  │ ● Online │  │ ○ Offline│          │
│  │ 🔋 78%   │  │ 🔋 12%   │  │ 🔋 95%   │          │
│  │ 📍 Moscow│  │ 📍 Home  │  │ 📍 Office│          │
│  │ 🚗 Driving│  │ 🏠 Home  │  │ 💼 Office│          │
│  │ ⏱ 2h 15m │  │ ⏱ 5h 30m│  │ ⏱ 8h 00m│          │
│  └──────────┘  └──────────┘  └──────────┘          │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ Actions                                      │    │
│  │ [Set Profile ▼] [Send Message] [Lock/Wipe]  │    │
│  │ [Set Profile ▼] [Config Update] [Screenshot]│    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ Event Log                                    │    │
│  │ 14:30 Phone₁: profile changed home→driving  │    │
│  │ 14:28 Phone₂: incoming call +7..., forwarded│    │
│  │ 14:15 VPS₁: config updated (profiles v5)    │    │
│  │ 14:00 Phone₁: geo push (55.75, 37.62)       │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ Mesh Topology                                │    │
│  │  [Phone₁] ── [Host] ── [Phone₂]            │    │
│  │              │                               │    │
│  │           [VPS₁] ── [VPS₂]                  │    │
│  │              │                               │    │
│  │           [Laptop]                           │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Функции:**
- Device cards: status, battery, geo, profile, uptime
- Actions: set profile, send message, lock/wipe, config update
- Event log: real-time, searchable, filterable
- Mesh topology: visual map of device connections
- Analytics: battery drain rate, geo updates, uptime %
- Settings: manage profiles.json, backup schedules, security policies

**Реализация:**
- Static files: HTML/CSS/JS (Vue/React/Svelte)
- API: WebSocket (real-time) + REST (actions)
- Auth: basic auth или JWT (same as device tokens)
- Port: same as kernel WS port (configurable)

---



### 33. OTA Config Updates (NEW)

**Что:** Хост пушит обновления конфигурации на устройства без обновления APK.

**Зачем:** #44 (Silent Install) = обновление кода (APK). Это **тяжёлое** обновление.
OTA Config = обновление настроек, правил, профилей. **Лёгкое** обновление
без перезапуска приложения.

**Примеры:**
```
Host: {"type": "config_update", "profiles": {新的 profiles.json}}
Device: применяет новые профили → event: "config updated"

Host: {"type": "config_update", "geo_interval": 60}
Device: geo interval changed → event: "geo interval updated"

Host: {"type": "config_update", "auto_reply": {"sms": "Новое сообщение"}}
Device: auto-reply template updated
```

**Что можно обновлять через Config Update:**
- Profiles (rules + settings)
- Geo interval
- Auto-reply templates
- Notification routing rules
- Battery-aware thresholds
- DND policies
- Capability settings (enable/disable)

**Что НЕЛЬЗЯ обновлять через Config Update:**
- APK binary (это #44 Silent Install)
- Rust core code
- UniFFI bindings

**Как помогает:**
- Мгновенное применение без restart
- Graceful: если config invalid → keep old config + error event
- Versioning: `{"config_version": 5}` → device can reject stale configs
- Rollback: host can send previous config version

**Реализация:**
- `ConfigManager` — хранит current config + version
- `ConfigApplier` — применяет new config, validates, falls back on error
- WebSocket message type: `config_update`
- Capability: `device.config`

---



### 34. Remote Lock / Wipe (NEW)

**Что:** Удалённая блокировка и стирание данных с устройства.

**Зачем:** Телефон потерян/украден. Хост может:
- Заблокировать экран (показать сообщение "Позвоните +7...")
- Стереть все данные (factory reset)
- Отследить последнее местоположение
- **Важнее чем Remote Camera (#59)** — practical security, не surveillance

**Уровень 1: Remote Lock**
```kotlin
// DevicePolicyManager.lockNow()
// Показать сообщение на lock screen:
// "This device is managed by vynkor. Call +79161234567"
DevicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
```

**Уровень 2: Remote Wipe**
```kotlin
// DevicePolicyManager.wipeData(0) — factory reset
// Или:擦除UserData() — удалить только пользовательские данные
```

**Уровень 3: Pre-wipe actions**
```
1. Отправить last known geo на хост
2. Включить continuous geo streaming (если ещё жив)
3. Сделать snapshot contacts, SMS, call log → отправить на хост
4. Заблокировать экран с сообщением
5. Через N минут без reconnect → wipe
```

**Как помогает:**
- `DevicePolicyManager` (API 26+): lock / wipe / set password
- Хост: `{"action": "lock", "message": "Позвоните +7..."}`
- Хост: `{"action": "wipe", "delay_minutes": 30}`
- Grace period: 30 мин до wipe (allow recovery if found)

**Реализация:**
- `DeviceAdminReceiver` — register as device admin
- `RemoteLockProvider` — UniFFI trait: `lock()`, `wipe()`, `set_message()`
- Capability: `device.security.lock`, `device.security.wipe`

**Security:**
- Только owner (с biometric) может trigger lock/wipe
- Audit log: все lock/wipe actions логируются
- Confirmation: "Вы уверены? Это удалит ВСЕ данные."

---



## Tier 3 — Средний приоритет (Q3 2026)

### 35. Opus Codec

**Что:** Замена PCM passthrough на Opus для аудио потока.

**Зачем:** PCM 16kHz mono = 320 kbps. Opus = ~24 kbps. **13x экономия
bandwidth**. На мобильном интернете PCM может не проходить.

**Как помогает:**
- audiopus (pure Rust) или libopus через NDK
- Кодек в Rust core, transparent для Kotlin

---

### 36. Voice Wake Word

**Что:** Агент слушает micro и активируется по wake word ("Hey vynkor").

**Зачем:** Сейчас voice command = открыть приложение → нажать microphone.
Wake word = hands-free activation, как Alexa/Google Assistant но на вашем хосте.

**Как помогает:**
- OpenWakeWord (Python) или Porcupine на хост side
- Device mic streams PCM → хост анализирует → wake → activate STT
- Превращает phone в voice endpoint для home automation

---

### 37. Proximity Pairing (NFC/BLE)

**Что:** Pairing через NFC тап или BLE proximity вместо QR-сканирования.

**Зачем:** QR требует camera + ручное сканирование. NFC тап = мгновенно.
Тап телефоном к Raspberry Pi с NFC reader — и готово.

**Как помогает:**
- Хост отправляет pairing payload через BLE advertisement
- Телефон сканирует BLE → auto-pair
- Или: NFC tag с pairing URL → тап → auto-connect

---

### 38. Call Screening + AI Auto-Responder

**Что:** Полноценный автоответчик с AI: телефон отвечает на входящий звонок,
играет приветствие, записывает голос абонента, отправляет на хост для STT,
хост принимает решение и отвечает.

**Зачем:** "Если звонит незнакомый номер — спроси кто и зачем. Если важно —
перенаправь на меня. Если нет — запиши сообщение." Сейчас: игнорировать
или отвечать вручную.

#### Как это работает технически

**Уровень 1: CallScreeningService (Android 10+)**
- Может **фильтровать** звонки (видит caller info, решает block/allow)
- Может **ответить текстовым сообщением** ("Я за рулём, перезвоню")
- НЕ может физически ответить и говорить с абонентом

**Уровень 2: ConnectionService + PhoneAccount (Android 8+)**
- Регистрируешь `PhoneAccount` в системе (как Truecaller, Google Voice)
- When звонит — приложение **отвечает** (answers call)
- Получаешь `Connection` объект → доступ к audio streams
- **Слышишь абонента** через `AudioRecord`
- **Говоришь ему** через `AudioTrack` (pre-recorded или TTS)

**Уровень 3: Полный VoIP pipeline (наша цель)**

```
Входящий звонок
    ↓
ConnectionService отвечает
    ↓
┌─────────────────────────────────────────────────────────┐
│  УСТРОЙСТВО:                                            │
│  1. AudioTrack → play: "Здравствуйте! Это автоответчик. │
│     Скажите пожалуйста кто вы и зачем звоните."          │
│  2. AudioRecord → запись голоса абонента (PCM 16kHz)     │
│  3. Stream PCM → хост через WebSocket (mic capability)   │
└─────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────┐
│  ХОСТ (kernel):                                         │
│  1. STT → "Это Иван Petrov, звоню по поводу встречи    │
│     завтра в 15:00"                                      │
│  2. AI decide → "Это важно, перенаправить на владельца" │
│  3. TTS → "Спасибо, передам. Хозяин перезвонит вам      │
│     через 10 минут"                                      │
│  4. Stream TTS audio → устройство                        │
└─────────────────────────────────────────────────────────┘
    ↓
Устройство: AudioTrack → playback ответа абоненту
    ↓
Завершение звонка + event на хост:
  "Call from +79161234567 (Ivan), duration: 45s, 
   screening: important, forwarded: yes"
```

#### Компоненты для реализации

| Компонент | Android API | Статус |
|---|---|---|
| `ConnectionService` | API 26+ | **Нужно** — отвечает на входящие,管理 call state |
| `PhoneAccount` | API 23+ | **Нужно** — регистрация как call handler |
| `AudioRecord` (abonent → host) | API 21+ | ✅ Есть в `MicCapture` |
| `AudioTrack` (host → abonent) | API 21+ | ✅ Есть в `SpeakerSinkImpl` |
| WebSocket PCM streaming | — | ✅ Есть в mic/speaker capabilities |
| Host-side STT | — | ✅ Работает для mic capability |

#### Что уже есть в vynkor

- `MicCapture` — `AudioRecord` 16kHz mono s16le ✅
- `SpeakerSinkImpl` — `AudioTrack` для playback ✅
- WebSocket streaming — PCM frames в обе стороны ✅
- Host-side STT — уже работает для mic capability ✅

#### Что нужно добавить

- `CallConnectionService` extends `ConnectionService` (new)
- `CallScreeningService` для фильтрации (new, Android 10+)
- `PhoneAccount` registration в `AgentService` (new)
- Audio routing: abonent ↔ mic ↔ speaker ↔ host (new state machine)
- Call state machine: ringing → answered → speaking → ended
- Dynamic greeting: `{"greeting": "Здравствуйте! Это автоответчик {name}."}`

#### Ограничения

- Требует `ANSWER_PHONE_CALLS` + `CALL_PHONE` permissions
- Пользователь должен set app как default dialer ИЛИ grant phone account
- Некоторые OEM (Samsung, Xiaomi) restrict third-party call handling
- Audio routing may vary across devices

#### Dynamic greeting (от хоста)

Хост передаёт greeting с context:
```json
{
  "greeting": "Здравствуйте! Это автоответчик {owner_name}. Скажите пожалуйста кто вы и зачем звоните.",
  "rules": {
    "if_caller_known": "forward_immediately",
    "if_unknown": "screen_and_record",
    "max_duration_seconds": 120
  }
}
```

Хост может менять greeting в зависимости от времени суток, caller ID,
дня недели, контекста (за рулём / на встрече / дома).

---

### 39. Smart Auto-Reply (SMS + Calls + Notifications)

**Что:** Авто-ответы на SMS/calls/уведомления в зависимости от контекста.

**Зачем:** "Если я за рулём — автоответ 'за рулём, перезвоню'. Если
в встрече — 'на встрече, напишите'. Если сплю — 'не беспокоить'."

**Триггеры и ответы:**

| Контекст | Триггер | Авто-ответ |
|---|---|---|
| За рулём | `geo_speed > 30km/h && bluetooth_connected` | "За рулём, перезвоню позже" |
| На встрече | `calendar_event_active && location=office` | "На встрече, напишу после" |
| Ночью | `23:00-07:00` | "Не беспокоить, напишите завтра" |
| В полёте | `airplane_mode` | "В полёте, отвечу после приземления" |
| Низкая батарея | `battery < 10%` | "Мало батареи, краткий ответ" |

**Реализация:**
- Context: `{geo_speed, bluetooth_connected, calendar_event, screen_on, charging, wifi_ssid}`
- Правила на хосте: IF (condition) THEN (auto-reply template)
- `SmsManager.sendTextMessage()` для SMS ответов
- `ConnectionService` для call ответов (см. #32)

---

### 40. Emergency Mode (SOS)

**Что:** Экстренный режим: SOS с location, audio recording, auto-call,
автоматическое уведомление trusted contacts.

**Зачем:** Телефон — device agent. В экстренной ситуации (угроза, ДТП,
потеря сознания) агент автоматически:
1. Отправляет current geo на хост
2. Начинает audio recording (весь звук вокруг)
3. Звонит на emergency number
4. Уведомляет trusted contacts

**Как помогает:**
- Кнопка SOS в приложении (или shake gesture — 3 тряски за 2 сек)
- Автоматическое определение ДТП: `accelerometer + geo急速 deceleration`
- При activated:
  - Continuous geo stream на хост (каждые 5 сек)
  - Audio recording → WebSocket (весь звук)
  - Auto-call на 112/911 с передачей location
  - SMS trusted contacts: "SOS! {name} needs help at {location}"

**Реализация:**
- `SensorManager` для shake detection + accelerometer
- `AudioRecord` для continuous recording
- `TelecomManager` для emergency call
- `SmsManager` для trusted contacts notification
- New capability: `device.emergency`

---

### 41. Expense Tracking (Auto)

**Что:** Автоматический трекинг расходов из SMS/уведомлений банков.

**Зачем:** "Сколько я потратил сегодня?" → агент парсит SMS от банка
("списано 500₽ Starbucks") → хост агрегирует → dashboard.
Без этого: ручной учёт в заметках или Excel.

**Как помогает:**
- Pattern matching на SMS от банков: "списано", "оплачено", "balance", "purchase"
- Извлечение: amount, currency, merchant, category (автоматически)
- Хост: expense log + аналитика по категориям + budget alerts

**Примеры SMS и парсинг:**
```
"Списано 5 234,00 RUB. STARBUCKS MOSCOW. Баланс: 45 000,00 RUB"
→ {amount: 5234.00, currency: "RUB", merchant: "Starbucks", 
   category: "food", balance: 45000.00}

"Оплата 1 500 руб. ТИНЬКОфф МОБИЛЬНЫЙ. Баланс 2 300 руб."
→ {amount: 1500.00, currency: "RUB", merchant: "T-Mobile", 
   category: "telecom", balance: 2300.00}
```

**Dashboard на хосте:**
- Today / This week / This month totals
- By category: food, transport, telecom, entertainment
- Budget alerts: "Превышен лимит на еду: 15 000 / 10 000 ₽"
- Recurring charges detection: "Подписка Spotify 299₽ каждый месяц"

**Реализация:**
- `ContentResolver` + `Telephony.Sms.CONTENT_URI`
- Pattern matching для российских банков (Сбер, Т-Банк, Альфа, ВТБ)
- New capability: `device.expenses`
- Хост: expense analyzer + dashboard

---

### 42. Presence Detection + Automation

**Что:** Определение контекста устройства через Bluetooth/WiFi/GPS и автоматические действия.

**Зачем:** "Когда я пришёл домой — включи WiFi, выключи DND."
"Когда подключился к машине — driving mode."
Контекст = автоматизация без ручных действий.

**Как помогает:**
- `BluetoothAdapter.startDiscovery()` → nearby devices (RSSI → distance)
- `WifiManager.startScan()` → connected network SSID
- `FusedLocationProvider` → GPS location
- Контекст: `{nearby_devices: ["car_bt", "home_wifi"], location: "home", speed: 0}`
- Хост: IF (context) THEN (action)
- Capability: `device.context`

**Правила (примеры):**

| Контекст | Действие |
|---|---|
| Подключён к car Bluetooth | Driving mode: DND, auto-reply, hands-free |
| На home WiFi | Full features: DND off, high-frequency geo |
| На office WiFi | Work mode: notification priority for work apps |
| Все устройства offline | Emergency alert |
| Гео-зона "дома" | Включи WiFi, выключи mobile data |

**Реализация:** Новый UniFFI trait `ContextProvider`. Хост хранит правила и триггерит actions.

---

### 43. Remote Shell / SSH

**Что:** Удалённое выполнение команд на устройствах через device agent.

**Зачем:** С телефона → SSH на VPS → manage server. С планшета → SSH на ПК.
Не нужен отдельный SSH client — всё через device agent.

**Как помогает:**
- Хост: `{"action": "exec", "command": "ls -la", "target": "vps1"}`
- Устройство: `Runtime.getRuntime().exec(command)` (для local)
- Или: WebSocket tunnel → SSH на remote device
- Capability: `device.shell`

**Ограничения:**
- Security: команда выполняется от имени пользователя
- sandbox: ограничить filesystem access
- Logging: все команды логируются на хосте

---

### 44. Cross-device Search

**Что:** Поиск файлов/данных на всех устройствах в mesh одновременно.

**Зачем:** "Где файл budget.xlsx?" → ищет на Phone₁, Phone₂, Tablet, Laptop, VPS.
Не нужно помнить где сохранил.

**Как помогает:**
- Хост: `{"action": "search", "query": "budget.xlsx", "targets": ["all"]}`
- Каждое устройство: `MediaStore` + filesystem search
- Хост: агрегирует результаты → "Найден на Laptop: /Documents/budget.xlsx"
- Capability: `device.search`

**Реализация:** Kernel-side search coordinator. Каждое устройство = indexer.

---

### 45. Notification Priority Router

**Что:** Умная маршрутизация уведомлений: важные → на хост immediately,
неважные → batch digest.

**Зачем:** 200 notifications/day → information overload. Приоритизация:
boss call → forward immediately. Game notification → skip. Newsletter → digest.

**Как помогает:**
- AI classification: important / normal / low / spam
- Правила: `from: boss → forward`, `app: game → skip`
- Digest: "За день: 3 important (boss, wife, delivery), 47 other"

---
### 46. Profiles & Scenarios (NEW)

**Что:** Система профилей — автоматических и ручных — которые меняют
поведение устройства целиком. Один профиль = набор правил: какие capabilities
активны, с какой частотой, какие auto-replies, какой DND level.

**Зачем:** Сейчас каждая автоматизация — отдельное правило. "DND но только ночью"
+ "geo каждые 30 сек но только когда на зарядке" + "auto-reply только за рулём".
Профиль = **один switch** который применяет весь набор. Плюс: контекстная
автоматизация без ручных действий.

---

#### Тип 1: Auto-detected profiles (контекстные)

Устройство само определяет профиль по сенсорам. Без ручного вмешательства.

**Сигналы для детекции:**

| Сигнал | Источник | Что даёт |
|---|---|---|
| GPS speed | `FusedLocationProvider` | Движется / стоит |
| Bluetooth connected | `BluetoothAdapter` | Car audio / headset / nothing |
| WiFi SSID | `WifiManager` | Home / office / public |
| accelerometer + gyro | `SensorManager` | Вибрация = vehicle / статика = на месте |
| Time of day | `Clock` | Ночь / день / рабочее время |
| Calendar events | `CalendarContract` | На встрече / свободен |
| Battery level | `BatteryManager` | Critical / charging / normal |
| Charging state | `BatteryManager` | Заряжается / на батарее |

**Road detection (опционально, для точности):**

Если GPS speed > 20 km/h и нет Bluetooth car → проверяем road proximity:
- **OSM Overpass API** или **HERE Routing API** — "находится ли точка на дороге?"
- Если точка на дороге → вероятно в машине
- Если точка НЕ на дороге (поле, парк) → велосипед / самокат / ошибка

```
GET https://overpass-api.de/api/interpreter?data=[out:json];way(around:50,{lat},{lng})["highway"];out count;
```

Response: `{ "elements": [{ "count": 5 }] }` → 5 дорог в радиусе 50м → на дороге.

**Но:** Road detection = опциональный enhancement. Основная эвристика:
- GPS speed > 30 km/h + accelerometer вибрация + нет home WiFi → **Driving**
- Home WiFi + night time + charge → **Sleeping**
- Office WiFi + working hours → **Office**
- Calendar event active → **Meeting**
- No WiFi + GPS speed < 5 km/h + день → **Walking/Transit**

---

#### Тип 2: Manual profiles (ручные)

Пользователь переключает профиль вручную. Через QS Tile, виджет, или UI.

**Built-in profiles (по умолчанию):**

| Profile | DND | Geo interval | Auto-reply | Notifications | Other |
|---|---|---|---|---|---|
| **Home** | OFF | 5 min | OFF | Forward all | Full features, high frequency |
| **Office** | Work only | 15 min | OFF | Priority only | Low frequency, save battery |
| **Driving** | ON | 30 sec | "За рулём" | Forward urgent only | Hands-free, voice priority |
| **Sleeping** | ON | 30 min | "Не беспокоить" | Digest only | Minimal, save battery |
| **Meeting** | ON | 15 min | "На встрече" | Forward critical only | DND + auto-reply |
| **Power Save** | OFF | 60 min | OFF | Batch only | Minimal everything |
| **Custom N** | configurable | configurable | configurable | configurable | Пользователь настраивает |

**Переключение:**
- QS Tile (один тап)
- Widget на home screen
- UI в приложении (drawer)
- Хост: `{"action": "set_profile", "profile": "driving"}`

---

#### Тип 3: Host-defined profiles (JSON на хосте)

Хост хранит профили как файлы. AI может создавать/modify профили.

**Формат: JSON (recommended)**

```json
{
  "profiles": {
    "driving": {
      "name": "За рулём",
      "icon": "car",
      "priority": 10,
      "detection": {
        "auto": true,
        "rules": [
          {
            "all": [
              {"sensor": "gps_speed", "op": ">", "value": 25},
              {"sensor": "bluetooth", "op": "connected_to", "value": "car_.*"},
              {"sensor": "wifi", "op": "disconnected"}
            ]
          },
          {
            "all": [
              {"sensor": "gps_speed", "op": ">", "value": 30},
              {"sensor": "accelerometer", "op": "vibration_detected"},
              {"sensor": "wifi", "op": "ssid_not", "value": "home_wifi"}
            ]
          }
        ],
        "road_check": true,
        "road_api": "osm_overpass",
        "road_radius_m": 50
      },
      "settings": {
        "dnd": {
          "enabled": true,
          "allow_calls": "favorites",
          "allow_messages": "favorites"
        },
        "geo_interval_seconds": 30,
        "auto_reply": {
          "enabled": true,
          "sms": "За рулём, перезвоню позже",
          "call": "Я за рулём, перезвоню через 10 минут"
        },
        "notifications": {
          "forward": ["urgent", "calls"],
          "batch": ["normal"],
          "skip": ["low", "spam"]
        },
        "capabilities": {
          "mic": {"enabled": false},
          "camera": {"enabled": false},
          "clipboard": {"enabled": true, "interval": 300},
          "speaker": {"enabled": true}
        }
      }
    },

    "home": {
      "name": "Дома",
      "icon": "home",
      "priority": 5,
      "detection": {
        "auto": true,
        "rules": [
          {
            "all": [
              {"sensor": "wifi", "op": "ssid_is", "value": "MyHome_WiFi"},
              {"sensor": "time", "op": "between", "value": "18:00-08:00"}
            ]
          }
        ]
      },
      "settings": {
        "dnd": {"enabled": false},
        "geo_interval_seconds": 300,
        "auto_reply": {"enabled": false},
        "notifications": {"forward": "all"},
        "capabilities": {
          "mic": {"enabled": true},
          "speaker": {"enabled": true},
          "clipboard": {"enabled": true, "interval": 5}
        }
      }
    },

    "meeting": {
      "name": "На встрече",
      "icon": "calendar",
      "priority": 20,
      "detection": {
        "auto": true,
        "rules": [
          {
            "all": [
              {"sensor": "calendar", "op": "event_active"},
              {"sensor": "wifi", "op": "ssid_is", "value": "Office_.*"}
            ]
          }
        ]
      },
      "settings": {
        "dnd": {
          "enabled": true,
          "allow_calls": "nobody",
          "allow_messages": "nobody"
        },
        "geo_interval_seconds": 900,
        "auto_reply": {
          "enabled": true,
          "sms": "На встрече, напишу после",
          "call": "На встрече, перезвоню после окончания"
        },
        "notifications": {
          "forward": ["critical"],
          "skip": ["normal", "low"]
        }
      }
    },

    "sleeping": {
      "name": "Сон",
      "icon": "moon",
      "priority": 15,
      "detection": {
        "auto": true,
        "rules": [
          {
            "all": [
              {"sensor": "time", "op": "between", "value": "23:00-07:00"},
              {"sensor": "charging", "op": "is", "value": true},
              {"sensor": "screen", "op": "off_for", "value": "30m"}
            ]
          }
        ]
      },
      "settings": {
        "dnd": {"enabled": true, "allow_calls": "emergency"},
        "geo_interval_seconds": 3600,
        "auto_reply": {
          "enabled": true,
          "sms": "Не беспокоить, отвечу завтра"
        },
        "notifications": {"forward": "none", "digest": "morning"},
        "capabilities": {
          "mic": {"enabled": false},
          "speaker": {"enabled": false}
        }
      }
    },

    "power_save": {
      "name": "Экономия",
      "icon": "battery",
      "priority": 25,
      "detection": {
        "auto": true,
        "rules": [
          {
            "all": [
              {"sensor": "battery", "op": "<", "value": 15},
              {"sensor": "charging", "op": "is", "value": false}
            ]
          }
        ]
      },
      "settings": {
        "dnd": {"enabled": false},
        "geo_interval_seconds": 3600,
        "auto_reply": {"enabled": false},
        "notifications": {"forward": "none"},
        "capabilities": {
          "mic": {"enabled": false},
          "speaker": {"enabled": false},
          "clipboard": {"enabled": false},
          "contacts": {"enabled": false}
        }
      }
    }
  },

  "defaults": {
    "profile_order": ["power_save", "sleeping", "meeting", "driving", "office", "home"],
    "fallback_profile": "home",
    "transition_cooldown_seconds": 60,
    "require_confirmation_for": ["driving", "meeting"],
    "log_transitions": true
  }
}
```

**Пример использования:**

```
Host загружает profiles.json
    ↓
Host: GET /devices → Phone₁ online
    ↓
Host: POST /devices/phone1/profile {"profile": "driving"}
    ↓
Phone₁: применяет settings из "driving" profile
    ↓
Через 30 мин: GPS speed → 0, WiFi → "MyHome_WiFi"
    ↓
Phone₁: detection rules → "home" profile match
    ↓
Phone₁: transition: driving → home (auto)
    ↓
Host: event: "phone1 profile changed: driving → home"
```

---

#### Priority system

- Higher priority = override lower
- `power_save` (25) > `meeting` (20) > `sleeping` (15) > `driving` (10) > `office` (5) > `home` (0)
- Если несколько rules match → highest priority wins
- Manual override: `{"profile": "driving", "locked": true}` → auto-detection paused

#### Transition cooldown

- Профиль не переключается чаще чем раз в N секунд (default: 60)
- Предотвращает flicker: "home → office → home → office"
- Confirmation required для critical profiles (driving, meeting)

#### Logging

- Все transitions логируются: `{timestamp, from_profile, to_profile, trigger_reason}`
- Host хранит history: "В 14:00 переключился в driving, в 14:30 → home"
- Analytics: "Сколько времени в driving в неделю"

---

#### Реализация

**Kotlin side:**
- `ProfileManager` — хранит текущий profile, detection rules
- `ContextCollector` — собирает все sensor data в Context object
- `ProfileDetector` — сверяет Context с rules, определяет best profile
- `ProfileApplier` — применяет settings (DND, geo interval, auto-reply, capabilities)

**Rust core:**
- `ProfileConfig` — protobuf definition для profile data
- `ProfileService` — UniFFI trait: `get_current_profile()`, `set_profile()`, `list_profiles()`

**Host side:**
- `profiles.json` — хранится на хосте
- `POST /devices/:id/profile` — set profile
- `GET /devices/:id/profile` — get current profile + detection context
- `GET /devices/:id/profile/history` — transition log

#### Detection flow

```
ContextCollector (every 10s):
  → GPS speed, WiFi SSID, BT devices, battery, calendar, accelerometer
  → Build Context object

ProfileDetector:
  → Load profiles.json from host
  → For each profile: evaluate rules against Context
  → Find matching profiles (may be multiple)
  → Select highest priority

ProfileApplier:
  → If new profile != current profile:
    → Check cooldown (60s minimum between transitions)
    → If cooldown passed → apply new profile
    → Send event to host: "profile changed: X → Y (reason: ...)"
  → If same profile → no action

Host:
  → Receives profile change event
  → Updates dashboard
  → Logs to history
  → May trigger additional automations
```

---

#### Example: driving detection step by step

```
1. ContextCollector gathers:
   - GPS speed: 45 km/h
   - WiFi: disconnected
   - Bluetooth: connected to "CarAudio_BT"
   - Accelerometer: vibration detected (road bumps)
   - Time: 14:30 (daytime)
   - Calendar: no active event

2. ProfileDetector evaluates:
   - "driving" rule 1: speed>25 AND bt=car_* AND wifi=disconnected → MATCH
   - "driving" rule 2: speed>30 AND vibration AND wifi!=home → MATCH
   - "office" rule: wifi=office → NO MATCH (wifi disconnected)
   - "meeting" rule: calendar=event → NO MATCH (no event)
   - "sleeping" rule: time=23-07 → NO MATCH (14:30)
   - "power_save" rule: battery<15 → NO MATCH (battery=65%)

3. Result: "driving" (priority 10, only match)

4. ProfileApplier:
   - Current profile: "home" → new: "driving"
   - Cooldown check: last transition was 2h ago → OK
   - Apply driving settings:
     - DND ON (allow calls from favorites)
     - Geo interval: 30s
     - Auto-reply: "За рулём, перезвоню позже"
     - Mic: disabled, Camera: disabled

5. Event to host:
   {"type": "profile_changed", "from": "home", "to": "driving",
    "reason": "speed=45, bt=CarAudio_BT, wifi=disconnected",
    "timestamp": "2026-08-19T14:30:00Z"}
```

#### Example: road detection refinement

```
GPS speed: 35 km/h, no BT connected, WiFi disconnected.
Base heuristic says: "Driving?" (speed > 30, no home WiFi).

But is the user ON A ROAD or riding a bike through a park?

Road check (OSM Overpass):
  GET overpass-api.de → 3 highway segments within 50m → ON ROAD → Driving confirmed.

Alternative: GPS speed 35 km/h, OSM shows 0 roads within 50m.
  → Probably cycling or running → do NOT switch to driving profile.
```

#### Custom profiles (user-created)

Пользователь может создавать свои профили прямо в JSON:

```json
{
  "gym": {
    "name": "В зале",
    "icon": "dumbbell",
    "priority": 12,
    "detection": {
      "auto": true,
      "rules": [
        {
          "all": [
            {"sensor": "wifi", "op": "ssid_is", "value": "GymWiFi"},
            {"sensor": "time", "op": "between", "value": "17:00-21:00"}
          ]
        }
      ]
    },
    "settings": {
      "dnd": {"enabled": true, "allow_calls": "favorites"},
      "geo_interval_seconds": 300,
      "auto_reply": {
        "enabled": true,
        "sms": "В тренажёрном зале, отвечу после тренировки"
      },
      "notifications": {"forward": ["urgent"], "skip": ["normal", "low"]}
    }
  }
}
```

Хост может **создавать профили через AI**: "Создай профиль для когда я в тренажёрном зале" → AI генерирует JSON → применяет.



---


### 47. Quick Actions Widget (NEW)

**Что:** Виджет на home screen с кнопками для быстрых macros.

**Зачем:** Quick Actions (#32) — концепция. Widget = **физическая кнопка**
на home screen. Один тап → macro applied.

**Виджет 4x2:**
```
┌─────────────────────────────────┐
│  🏠 Home   🚗 Drive  📅 Meet   │
│                                 │
│  😴 Sleep   💼 Work   🏋️ Gym  │
└─────────────────────────────────┘
```

- Tap → macro applied → toast "Home mode activated"
- Long press → macro settings / edit
- Visual feedback: active button highlighted
- Material You colors (auto-match wallpaper)

**Реализация:**
- `QuickActionsWidget` extends `AppWidgetProvider`
- `RemoteViews` с кнопками
- `AppWidgetManager.updateAppWidget()` для обновления
- Connectivity: `PendingIntent` → `MacroService`

---

## Примечания к новым ideas

- **Remote Lock/Wipe**: Требует `DEVICE_ADMIN` permission. Пользователь должен grant device admin access.
- **OTA Config**: Не заменяет #44 (Silent Install). Config = настройки, Install = код.
- **Host Dashboard**: Можno хостить на same port как kernel (configurable). Auth = same JWT system.
- **Voice Assistant**: Ограничен commands (flashlight, DND, profile). Не заменяет full voice control (#50).
- **Event History**: SQLite на устройстве = 7 дней. Host = permanent. Privacy = encrypted.
- **Location History**: Privacy-first. Все зашифровано. Доступно только owner.
- **App Notification Filter**: Расширяет существующий #18 (Notification Actions). Не заменяет, а фильтрует.
- **Quick Actions Widget**: Расширяет #16 (Widgets). Дополнительный виджет для macros.




### 48. Event History / Audit Log (NEW)

**Что:** Полный лог всех действий устройства и хоста.

**Зачем:** "Что произошло в 3:00 когда я спал?" → audit log.
Security: "Кто и когда включил remote camera?"
Debugging: "Почемуgeo не обновлялся с 14:00?"

**Формат записи:**
```json
{
  "timestamp": "2026-08-19T14:30:00Z",
  "device": "phone₁",
  "actor": "auto" | "user" | "host",
  "action": "profile_changed",
  "details": {
    "from": "home",
    "to": "driving",
    "reason": "speed=45, bt=CarAudio_BT"
  },
  "result": "success"
}
```

**Типы событий:**
- Profile transitions (auto / manual)
- Capability activations (mic ON, camera ON)
- Incoming/outgoing calls, SMS
- Config updates
- Security events (unknown WiFi, failed auth)
- Geo milestones (entered/left zones)
- Battery events (low, charging, full)
- Connection events (connect, disconnect, reconnect)

**Хранение:**
- SQLite на устройстве (последние 7 дней)
- Push на хост через WebSocket (real-time)
- Host: permanent storage + search + analytics

**Реализация:**
- `EventStore` — SQLite event log
- `EventForwarder` — push events to host
- Host: event index + search + dashboard
- Capability: `device.events`

---



### 49. Device Location History (NEW)

**Что:** История местоположений устройства (не только текущий geo).

**Зачем:** "Где я был сегодня?" → timeline. "Где был телефон вчера в 15:00?"
Heatmap: "частые места." Это **journey log**, не surveillance.

**Что хранится:**
```
{
  "timestamp": "2026-08-19T14:30:00Z",
  "lat": 55.7558,
  "lng": 37.6173,
  "accuracy": 10.0,
  "speed": 45.0,
  "bearing": 180.0,
  "activity": "driving",
  "battery": 78
}
```

**Dashboard (на хосте):**
- Timeline: "10:00 home → 10:30 office → 12:00 lunch → 14:00 meeting"
- Map: route за день (polyline)
- Heatmap: "частые места за неделю/month"
- Analytics: "среднее время в офисе: 6h/day", "расстояние за неделю: 120km"

**Privacy:**
- Зашифровано на устройстве (biometric key)
- Доступно только owner (biometric gate)
- Retention policy: 7 days local,永久 на host (если включено)
- User can: pause tracking, delete history, export

**Реализация:**
- `LocationHistoryStore` — SQLite с geo points
- `LocationHistoryForwarder` — push on significant changes
- Host: Leaflet/Mapbox для визуализации
- Capability: расширение `device.geo`

---




### 50. Voice Assistant Integration (NEW)

**Что:** Google Assistant / Alexa как input channel для device agent.

**Зачем:** "Hey Google, скажи vynkor выключи WiFi" → Google Intent → vynkor → WiFi off.
Превращает phone voice assistant в remote control для device agent.

**Как работает:**

```
Пользователь: "Hey Google, скажи vynkor выключи фонарик"
    ↓
Google Assistant: парсит intent
    ↓
App: команда → vynkor → flashlight off
    ↓
Google: "Готово"
```

**Интеграция:**
- **Android:** `App Actions` + `Shortcuts` для Google Assistant
  - `intent.xml`: определяет команды
  - "Turn on flashlight" → flashlight ON
  - "Set alarm for 7 AM" → alarm set
  - "What's my battery?" → battery status

- **Custom commands (через deep links):**
  ```
  vynkor://command?action=flashlight&value=on
  vynkor://command?action=dnd&value=on
  vynkor://command?action=profile&value=driving
  vynkor://command?action=geo&value=current
  ```

- **Home automation (Home Assistant integration):**
  ```
  Home Assistant: Automation → vynkor API → device action
  "When I arrive home → vynkor turns on WiFi"
  ```

**Как помогает:**
- Hands-free control когда руки заняты
- Интеграция с существующими voice assistants
- Home automation bridge (Home Assistant, OpenHAB)
- Не требует разработки собственного voice recognition

**Реализация:**
- `AssistantIntegration` — register app actions
- `DeepLinkHandler` — process vynkor:// intents
- `HomeAssistantBridge` — API endpoints для HA
- Capability: `device.voice` (extension of existing)

---




## Tier 4 — Долгосрочные (2026+)

### 51. Context-Aware Agent Behavior

**Что:** Агент меняет поведение в зависимости от контекста устройства.

**Зачем:** "Если пользователь в машине → не звонить, только SMS. Если
домой → voice OK." Превращает dumb device в context-aware endpoint.

**Контекст:**
- `{geo_speed, bluetooth_connected, screen_on, charging, wifi_ssid}`
- Хост/AI решает: "за рулём → отложить уведомление"

---

### 52. Mesh Network (revised)

**Что:** Несколько устройств образуют mesh через хост. Каждое устройство — node
в распределённой системе.

**Зачем:** Один телефон = устройство. ПК + ноут + планшет + телефоны + VPS = **distributed computing cluster**.
Каждое устройство делает то, к чему приспособлено.

**Как работает:**

```
┌─────────────────────────────────────────────────────────┐
│                    vynkor MESH                          │
├──────────┬──────────┬──────────┬──────────┬────────────┤
│ Phone₁   │ Phone₂   │ Tablet   │ Laptop   │ VPS₁/VPS₂ │
│ geo+mic  │ camera   │ screen   │ compute  │ compute+AI │
│ sensor   │ speaker  │ draw     │ storage  │ brain      │
└──────────┴──────────┴──────────┴──────────┴────────────┘
```

- Хост видит все устройства через `/devices`
- Каждое устройство может запросить capabilities другого через хост
- Хост как router: `Phone₁ хочет geo от Phone₂ → Host пересылает`

**Use cases:**

| Сценарий | Без mesh | С mesh |
|---|---|---|
| **Distributed compute** | Телефон греется, батарея мертва | VPS делает STT/TTS/LLM, телефон = sensor |
| **Cross-device clipboard** | Ручное копирование | Скопировал на ПК → вставил на планшете |
| **Presence detection** | Нет автоматизации | Bluetooth saw nearby → trigger automations |
| **Redundancy** | Phone₁ упал → нет данных | Phone₂ берёт geo |
| **Cross-device search** | Не помнишь где файл | Ищет на всех устройствах |
| **Remote shell** | Нужен отдельный SSH client | Всё через device agent |
| **Distributed storage** | Файлы на одном устройстве | Доступны с любого |
| **Security monitoring** | Каждый отдельно | Агрегированный dashboard |

**Реализация:** Kernel-side routing table + new API `GET /devices/:id/capabilities/:cap`.

---

### 53. Distributed Task Queue

**Что:** Хост распределяет задачи между устройствами в mesh.

**Зачем:** "Обработай аудиофайл" → VPS делает STT (мощный CPU),
телефон.record() (microphone), планшет.play() (speaker).
Distributed workload = каждое устройство = specialist.

**Как помогает:**
- Хост: `{"task": "stt", "input": "audio.wav", "assign_to": "vps1"}`
- Устройства: capabilities matching → best fit
- Хост: агрегирует results → unified response
- Capability: `device.task`

---

### 54. Silent Install / OTA Updates

**Что:** Хост может обновить APK на устройствах дистанционно.

**Зачем:** Обновить 5 тестовых телефонов = physically подключить каждый
→ `adb install`. Silent install: отправить команду → все обновляются.

**Как помогает:**
- Capability: `device.install`
- Хост отправляет APK binary через WS → `pm install`
- Требует root или device owner

---

### 55. Device Fleet Management

**Что:** Хост группирует устройства и отправляет команды группам.

**Зачем:** "Выключить звук на всех в спальне" или "обновить APK на всех
в office". Сейчас — по одному.

**Как помогает:**
- `POST /devices/group {"name": "bedroom", "devices": ["phone1", "phone2"]}`
- Command routing: group → broadcast
- Веб-интерфейс для management

---

### 56. Encrypted Chat History

**Что:** Шифрование chat history на устройстве.

**Зачем:** `ChatStore` хранит сообщения открытым текстом. Компрометация
телефона = весь chat readable.

**Как помогает:**
- `EncryptedSharedPreferences` для chat storage
- Key: biometric + device salt
- Без biometric → история недоступна

---

### 57. Agent Heartbeat Dashboard

**Что:** Веб-интерфейс (host-side) с heartbeat каждого устройства.

**Зачем:** Хост знает "online/offline" но не историю. Если disconnect
в 3:00 и reconnect в 3:05 — хост не знает что было.

**Как помогает:**
- Heartbeat events → host stores: `{device_id, timestamp, battery, geo, state}`
- Dashboard: timeline, uptime %, battery drain rate

---

### 58. Photo Management

**Что:** Автоматический backup, face recognition, организация фото.

**Зачем:** "Покажи все фото с котом за последний месяц" — сейчас невозможно.
Если phone = device agent, хост может индексировать photo library.

**Как помогает:**
- `MediaStore` + `ContentResolver` для photo enumeration
- Хост: ML face/object recognition → tagging
- Backup через WS (или reference-based: "сделай snapshot photo library")

---

### 59. Digital Wellbeing

**Что:** Трекинг screen time, app usage, notification frequency.

**Зачем:** "Сколько я провёл в Instagram сегодня?" → агент собирает
`UsageStatsManager` → хост показывает dashboard.

**Как помогает:**
- `UsageStatsManager.queryUsageStats()`
- Хост: analytics dashboard, alerts ("Screen time > 4h today")

---

### 60. Accessibility Voice Control

**Что:** Полный voice control для device agent — "открой настройки",
"пролистай вниз", "нажми кнопку отправить".

**Зачем:** Hands-free control для situations когда руки заняты.
Для пользователей с ограниченными возможностями — критично.

**Как помогает:**
- SpeechRecognizer → parse command → AccessibilityService action
- Capability: `device.voice_control`
- Хост: AI парсит natural language → device actions

---

### 61. Ambient Sound Monitoring

**Что:** Агент слушает micro и детектит звуки: glass break, baby cry,
doorbell, smoke alarm.

**Зачем:** "Если разбилось стекло — отправь SOS." "Если плачет ребёнок —
уведомление." Превращает phone в ambient sensor.

**Как помогает:**
- Audio classification (YAMNet или custom model на хосте)
- Device mic streams → host classifies → alert

---

### 62. Data Usage Monitoring

**Что:** Мониторинг мобильного трафика, алерты при приближении к лимиту.

**Зачем:** "Трафик на 90% лимита" → хост уведомляет. Или auto-switch
на WiFi при approach limit.

**Как помогает:**
- `TrafficStats.getTotalRxBytes()` poll
- Хост: threshold alerts + auto-actions

---

### 63. Storage Management

**Что:** Предложения по очистке, дедупликация фото, cleanup caches.

**Зачем:** "На телефоне закончилось место" → агент находит дубликаты,
старые файлы, cleanup suggestions.

**Как помогает:**
- `StatFs` для storage overview
- `MediaStore` для file enumeration
- Хост: analysis + cleanup suggestions

---

### 64. Bluetooth Device Tracking

**Что:** Трекинг Bluetooth-устройств: "Где мои наушники?" (если они в
pairing с телефоном).

**Зачем:** Bluetooth tracker tags (AirTag, Tile, SmartTag) — phone
может быть reader'ом для них.

**Как помогает:**
- `BluetoothAdapter.scan()` → nearby devices + RSSI
- Хост: "наушники в 5м от устройства" (RSSI → distance estimation)

---

### 65. Travel Mode

**Что:** Авто-переключение настроек при пересечении границы.

**Зачем:** Роуминг = дорогой трафик. Travel mode: auto-disable background
sync, switch to offline maps, currency converter reminder.

**Как помогает:**
- Geo: cross border detection
- Actions: disable background sync, enable WiFi-only mode
- Хост: travel context → behavior change

---

### 66. Meeting Mode (Calendar Integration)

**Что:** Автоматический "Do Not Disturb" во время встреч в календаре.

**Зачем:** Забыл включить DND перед встречей → звонок срывает meetings.
Meeting mode: автоматически включает DND, auto-reply "на встрече", после
встречи — summary уведомлений которые пришли.

**Как помогает:**
- `CalendarContract` для чтения calendar events
- При event start: включить DND, активировать auto-reply
- При event end: выключить DND, send summary: "3 missed calls, 12 messages"
- Хост: "Во время встречи в 15:00 звонил Иван (важно)"

---

### 67. Driving Mode (Auto)

**Что:** Автоматическое переключение в режим вождения при начале поездки.

**Зачем:** Включил машину → телефон автоматически: DND, voice guidance
навигации, auto-reply "за рулём", hands-free call answering.

**Как помогает:**
- Триггер: `bluetooth_connected(car) || geo_speed > 30km/h`
- Actions: DND ON, auto-reply ON, navigation voice priority
- При остановке (speed < 5km/h > 5 min): DND OFF, summary

---

### 68. Sleep Mode (Smart Alarm)

**Что:** Интеллектуальный режим сна + умный будильник.

**Зачем:** Обычный будильник звонит в 7:00. Smart alarm: анализирует
sleep phase через movement/light → будит в оптимальное время в окне
6:30-7:30. Плюс: DND, reduced notifications, morning briefing.

**Как помогает:**
- `SensorManager` (accelerometer) для movement detection
- Хост: sleep phase analysis → optimal wake time
- Morning briefing: "Weather: +15°C, Calendar: 3 meetings, Traffic: 45 min"
- Плавное увеличение громкости будильника

---

### 69. Remote Camera (Spy/Security)

**Что:** Хост может активировать камеру устройства и видеть в реальном времени.

**Зачем:** "Если phone lost/stolen — могу посмотреть где он и что вокруг."
Или security: "Посмотри что происходит дома" (если phone на полке).

**Как помогает:**
- `CameraX` + WebSocket streaming
- Хост: live video feed + snapshot capture
- Capability: `device.camera.remote`
- Security: только с biometric auth + encryption

---

### 70. Device Health Monitor

**Что:** Мониторинг "здоровья" устройства: battery health, storage wear,
memory pressure.

**Зачем:** "Мой телефон тормозит" → агент диагностирует: "Battery degraded
to 72%, 95% storage used, 2GB RAM free". Хост может дать рекомендации.

**Как помогает:**
- `BatteryManager` (health, temperature, voltage)
- `StatFs` (storage), `ActivityManager` (memory)
- Хост: health dashboard + alerts ("Battery health < 80% → замена")

---

### 71. App Usage Control (Parental)

**Что:** Контроль времени использования приложений (parental control).

**Зачем:** "Ребёнок провёл 4 часа в TikTok" → автоматическое ограничение
или уведомление родителям.

**Как помогает:**
- `UsageStatsManager` для app tracking
- Хост: rules (max 2h/day for gaming apps)
- При превышении: notification + auto-lock app (AccessibilityService)
- Dashboard: screen time per app

---

### 72. Voice Memo (Always-Running)

**Что:** Всегда запущенный voice recorder для быстрых заметок голосом.

**Зачем:** "Напомни мне..." → нужно открыть приложение → найти memo.
Always-running: просто скажи → записалось → отправлено на хост для STT.

**Как помогает:**
- Lightweight audio buffer (последние 30 сек всегда в памяти)
- Wake phrase: "запомни" → buffer сохраняется + отправляется
- Хост: STT → text memo → сохранение

---

## Новые Tier 4 ideas (mesh + distributed)

### 73. Local DNS / mDNS

**Что:** Автоматическое обнаружение устройств по имени в mesh.

**Зачем:** Не нужно знать IP. `phone1.vynkor.local` → Phone₁.
`laptop.vynkor.local` → Laptop. `vps1.vynkor.local` → VPS₁.

**Как помогает:**
- mDNS (Bonjour/Avahi) для local network
- Kernel: DNS registry + resolve
- Хост: `GET /devices` возвращает names + IPs

---

### 74. Encrypted P2P Chat

**Что:** Чат между устройствами напрямую (не через хост). End-to-end encrypted.

**Зачем:** Phone₁ → Phone₂ напрямую. Без external service. Приватный чат
между своими устройствами.

**Как помогает:**
- E2E encryption: keys на каждом устройстве
- WebSocket P2P через хост (relay) или direct LAN
- Capability: `device.chat`

---

### 75. Distributed File System

**Что:** Все устройства = единое хранилище. Файлы доступны с любого устройства.

**Зачем:** Phone₁ photos → VPS backup. Laptop documents → Tablet access.
Не думаешь "где это сохранено" — оно доступно везде.

**Как помогает:**
- File indexing на каждом устройстве
- Хост: file registry + location tracking
- Lazy sync: файлы синхронизируются по demand
- Capability: `device.fs`

---

### 76. Bandwidth Management

**Что:** Маршрутизация трафика по bandwidth доступности.

**Зачем:** VPS = 1Gbps. Phone = mobile data (limited). Laptop = WiFi (unlimited).
"Большие файлы → через WiFi, не mobile data."

**Как помогает:**
- Network type detection: WiFi / mobile / ethernet
- Хост: routing rules per network type
- Auto-switch: WiFi available → use WiFi for large transfers

---

### 77. Device Grouping by Network

**Что:** Автоматическая группировка устройств по сети.

**Зачем:** "Home" group (Phone₁, Tablet, Laptop) = fast LAN.
"Remote" group (Phone₂, VPS₁, VPS₂) = internet.
Команды → group → broadcast.

**Как помогает:**
- Network type detection + SSID matching
- Хост: auto-group creation
- UI: group-based command routing

---

### 78. Redundancy / Failover

**Что:** Автоматическое переключение на резервное устройство при отказе.

**Зачем:** Phone₁: geo sensor → battery dead. Phone₂ automatically takes over.
Непрерывность данных без ручного вмешательства.

**Как помогает:**
- Хост: health monitoring per device
- Priority list: Phone₁ (primary) → Phone₂ (backup) → Tablet (tertiary)
- Auto-switch при heartbeat timeout

---

### 79. Automated Backups

**Что:** Автоматический backup данных со всех устройств на VPS/хост.

**Зачем:** Phone₁ photos → VPS (ночью, charge + WiFi). Laptop documents → VPS.
Расписание на хосте: backup policy per device.

**Как помогает:**
- Хост: backup scheduler (cron-like)
- Политики: daily/weekly, WiFi-only, charge-only
- Incremental: только новые/изменённые файлы
- Capability: `device.backup`

---

### 80. Security Monitoring (Mesh-wide)

**Что:** Агрегированный мониторинг безопасности across all devices.

**Зачем:** Phone₁: "неизвестная WiFi". VPS₁: "SSH brute force".
Laptop: "unknown process 90% CPU". Хост: unified security dashboard.

**Как помогает:**
- Каждое устройство: security event reporting
- Хост: correlation + alerting
- Actions: auto-block, notify, emergency mode

---

### 81. Mesh Status Dashboard

**Что:** Веб-интерфейс (host-side) с полной визуализацией mesh.

**Зачем:** Видеть все устройства, связи между ними, topology, health,
bandwidth usage. Единое окно для управления cluster'ом.

**Как помогает:**
- Real-time mesh topology map
- Device health cards: battery, storage, CPU, network
- Bandwidth usage per link
- Event log + search
- Command dispatch from dashboard

---

## Tier 2.5 — Infrastructure & UX (NEW)

> **Чего нет в roadmap, но критично для production-ready продукта.**
> Без этих items агент = demos, не tool.

### 82. Offline-First Resilience

**Что:** Агент продолжает работать когда хост недоступен.

**Зачем:** Сейчас всё через WebSocket. Connection lost = агент мёртв.
Реальный мир: phone в лифте, хост перезагружается,ネットワーク flap.
Агент должен **продолжать выполнять** cached команды и queue'ить новые.

**Что должно работать offline:**
- SMS auto-reply (rules кэшированы на device)
- DND toggle (local state)
- Profile switching (local rules)
- Alarm/Timer (local AlarmManager)
- Flashlight, brightness, ringer (local)

**Что НЕ работает offline:**
- Geo push (нужен host)
- Remote commands (нужен host)
- File transfer (нужен host)
- Cross-device search (нужен host)

**Реализация:**
- `OfflineQueue` — queue commands с TTL: `{"command": "dnd_on", "ttl": 3600}`
- `CachedState` — хранит last known values: geo, battery, connection status
- `ConnectionRetry` — exponential backoff: 1s → 2s → 4s → 30s max
- При reconnect: flush queue → send cached state → resume normal

**UI:**
- Drawer: "Offline since 14:30. 3 commands queued."
- При reconnect: "Reconnected. 3 commands executed."

---

### 83. Battery Drain Transparency

**Что:** Пользователь видит сколько батареи тратит агент.

**Зачем:** Агент = foreground service. Он **сам** потребляет батарею.
Без transparency пользователь видит "батарея села" и винит агент.
С transparency: "Агент потратил 3% за 8 часов — это нормально."

**Breakdown:**
| Component | Typical drain | Why |
|---|---|---|
| Geo tracking | 1.2% / 8h | GPS + FusedLocation |
| Mic streaming | 0.8% / 1h | AudioRecord + WebSocket |
| Notification listener | 0.5% / 8h | Passive, но constant |
| Clipboard sync | 0.1% / 8h | Event-driven |
| WebSocket heartbeat | 0.3% / 8h | Keep-alive packets |
| **Total** | **~2.9% / 8h** | **Acceptable** |

**UI:**
```
┌─────────────────────────────────────┐
│  Battery Usage by vynkor            │
│                                     │
│  Last 8 hours: 2.9% total          │
│  ├─ Geo:      1.2% (41%)           │
│  ├─ Mic:      0.8% (28%)           │
│  ├─ WS:       0.3% (10%)           │
│  ├─ Notif:    0.5% (17%)           │
│  └─ Other:    0.1% (4%)            │
│                                     │
│  vs. screen-on: 15% / 8h           │
│  vs. no agent: 0% / 8h             │
│                                     │
│  [Optimize] [Details]              │
└─────────────────────────────────────┘
```

**Реализация:**
- `BatteryTracker` — poll `BatteryManager` каждый час
- Per-component tracking: каждый capability логирует wake locks + CPU time
- SQLite: `battery_usage(timestamp, component, percent, duration_ms)`
- Host: `GET /devices/:id/battery/history` → analytics

---

### 84. Graceful Degradation (Low Battery)

**Что:** Агент автоматически отключает capabilities при критическом заряде.

**Зачем:** #24 (Battery-Aware Mode) = "reduce frequency". Но при 5%
geo каждые 60 сек всё равно убивает батарею. Нужен **hard cutoff**.

**Thresholds:**
| Level | Action |
|---|---|
| **< 20%** | Geo interval ×4, mic stop, speaker stop |
| **< 10%** | Clipboard sync off, contacts off, notifications batch only |
| **< 5%** | **Only critical**: battery + heartbeat. Everything else OFF |
| **< 2%** | Agent sleeps. Only wake on charge. |

**UI:**
- Toast: "vynkor: battery 5%. Geo tracking paused."
- Drawer: warning icon + "Power save mode active"
- Host event: `{"type": "power_save", "level": 5, "disabled": ["geo", "mic", ...]}`

**Реализация:**
- `BatteryGuard` — registered as `BroadcastReceiver` for `ACTION_BATTERY_LOW`
- Threshold check every 5 min (poll) + event-driven (battery change)
- Auto-resume: when charging AND > 20% → restore previous capabilities
- Configurable: host can set custom thresholds per device

---

### 85. Permission Lazy Loading

**Что:** Запрашивать permissions только когда реально нужен, не все сразу.

**Зачем:** Wizard (#6) запрашивает все permissions на шаге 5.
Пользователь видит 6 dialogs подряд → "Allow, Allow, Allow, Skip..."
Lazy = permissions запрашиваются **когда capability впервые используется**.

**Flow:**
```
1. User enables "Geo tracking"
   → "vynkor needs Location permission"
   → [Explain: "Для отправки местоположения хосту"]
   → [Grant] [Skip]
   → If skip: geo = offline-only (last known), no push

2. User enables "Notification forwarding"
   → "vynkor needs Notification Access"
   → [Explain: "Для пересылки уведомлений на хост"]
   → [Open Settings] [Skip]
   → If skip: notifications = disabled

3. User enables "Mic streaming"
   → "vynkor needs Microphone permission"
   → [Explain: "Для передачи голоса на хост"]
   → [Grant] [Skip]
   → If skip: mic = disabled
```

**UI:**
- Settings → Capabilities → each has "Permission needed" badge
- Tap → explanation → grant flow
- Visual: capability card greyed out + "Grant permission" button

**Реализация:**
- `PermissionManager` — wrapper для `ActivityCompat.requestPermissions`
- Per-capability permission mapping:
  - `device.geo` → `ACCESS_FINE_LOCATION`
  - `device.notifications` → `BIND_NOTIFICATION_LISTENER_SERVICE`
  - `device.mic` → `RECORD_AUDIO`
  - `device.speaker` → none (system permission)
  - `device.contacts` → `READ_CONTACTS`
- Permission state: `SharedPreferences` → `{capability: granted/denied}`
- On capability request: check permission → request if needed → handle result

---

### 86. Error Recovery UX

**Что:** Пользователь понимает что пошло не может и как исправить.

**Зачем:** Сейчас errors = silent failures или generic "Connection lost".
Пользователь не знает: это хост упал? Сеть? Permission? Bug?

**Error taxonomy:**
| Error type | User sees | Suggested fix |
|---|---|---|
| **Connection lost** | "Хост недоступен (192.168.1.100:8080)" | "Проверьте что хост включён и в той же сети" |
| **Auth failed** | "JWT token expired или invalid" | "Обновите token в настройках" |
| **Permission denied** | "Location permission отозван" | [Grant permission] |
| **Capability crash** | "Geo tracking упал: SecurityException" | "Перезапустите агент" |
| **Battery critical** | "Батарея 4%. Geo отключён." | "Подключите зарядку" |
| **OEM kill** | "MIUI Battery заблокировал агент" | [Open OEM settings] |
| **Rust core panic** | "Protocol engine crashed" | "Отправьте лог разработчику" |

**UI:**
- Drawer: error banner с dismiss
- Notification: persistent for critical errors
- Settings → Status → error history (last 20)

**Реализация:**
- `ErrorReporter` — centralized error collection
- Error levels: `INFO`, `WARNING`, `ERROR`, `CRITICAL`
- `ErrorNotification` — для critical errors (persistent notification)
- `ErrorLog` — SQLite: `{timestamp, type, message, context, suggested_fix}`
- Host event: `{"type": "error", "level": "critical", "message": "..."}`

---

### 87. Multi-User / Work Profile Support

**Что:** Поддержка multiple users на одном устройстве.

**Зачем:** Семейный планшет (multiple users). Work profile (Android managed).
Guest mode. Сейчас: один owner = один агент.

**Use cases:**
| Scenario | Without multi-user | With multi-user |
|---|---|---|
| **Family tablet** | Only parent controls | Each family member = separate agent |
| **Work phone** | Personal + work mix | Work profile: separate JWT, separate capabilities |
| **Guest mode** | Guest sees owner's data | Guest: limited mode, no access to owner's capabilities |

**Реализация:**
- Android `UserManager` for multi-user detection
- Per-user agent instance (separate service, separate storage)
- Work profile: `DevicePolicyManager` for managed profiles
- UI: account switcher in drawer (like Google account switch)
- Host: `GET /devices` returns per-user entries: `phone1_user1`, `phone1_user2`

**Ограничения:**
- Each user = separate foreground service (battery impact)
- Work profile may restrict some capabilities (DND, notification access)
- Guest mode: read-only (no settings, no capabilities)

---

### 88. Data Retention Policy

**Что:** Правила хранения данных: что, сколько, где.

**Зачем:** #49 (Location History) упоминает privacy, но нет concrete policies.
GDPR-like control: пользователь должен знать и управлять.

**Defaults:**
| Data type | Local retention | Host retention | User can change |
|---|---|---|---|
| **Location history** | 7 days | 90 days | Yes (1-365 days, or forever) |
| **Event log** | 7 days | 365 days | Yes |
| **Chat history** | 30 days | forever | Yes |
| **Battery stats** | 24 hours | 30 days | No |
| **Notification log** | 24 hours | 7 days | Yes |
| **Expense data** | 30 days | forever | Yes |
| **Contact snapshots** | 7 days | 30 days | Yes |

**User controls:**
- Settings → Privacy → Data Retention
- Per-data-type sliders: "Keep for: 1 day / 7 days / 30 days / 1 year / Forever"
- "Delete all my data" button (GDPR Article 17)
- "Export all my data" button (GDPR Article 20)
- Auto-delete notification: "vynkor deleted 30-day-old location data"

**Реализация:**
- `DataRetentionManager` — enforces retention policies
- Cron job: daily cleanup of expired data
- SQLite VACUUM after bulk deletes
- Host: `POST /devices/:id/retention` — configure per-device
- Privacy dashboard: "What data vynkor stores about you"

---

### 89. Performance Budgets

**Что:** Лимиты на потребление ресурсов. Агент не должен убивать батарею/трафик.

**Зачем:** Без budgets агент может потреблять 20% батареи в час.
Пользователь: "vynkor съел батарею" → uninstall.

**Budgets:**
| Resource | Budget | Enforcement |
|---|---|---|
| **Battery drain** | < 5% / 8 hours | Hard limit: disable non-essential at threshold |
| **Network (mobile)** | < 50 MB / day | Pause geo push, batch notifications |
| **Network (WiFi)** | < 500 MB / day | Soft limit: warn at 80% |
| **Storage** | < 200 MB | Auto-cleanup old logs, events, history |
| **CPU** | < 10% average | Throttle background tasks |
| **Memory** | < 100 MB | Kill non-essential background tasks |

**UI:**
- Settings → Performance → "Budget: 3% battery / 8h"
- Current usage vs budget bar
- "Exceeded budget: 6% / 3%. Reduced geo interval."

**Реализация:**
- `PerformanceMonitor` — tracks usage per resource
- Enforcement: `BatteryGuard` (already #84) + `NetworkGuard` + `StorageGuard`
- Host: `GET /devices/:id/performance` → budget status
- Alert: `{"type": "budget_exceeded", "resource": "battery", "used": 6, "budget": 5}`

---

### 90. Testing Strategy

**Что:** План тестирования: unit, integration, E2E, performance.

**Зачем:** Без тестов = regression на каждом change. Агент = critical infrastructure.
Если агент упал — пользователь без remote control.

**Test pyramid:**
```
         ╱╲
        ╱E2E╲         10% — реальный kernel + реальное устройство
       ╱──────╲
      ╱Integration╲   30% — mock kernel + реальные capabilities
     ╱──────────────╲
    ╱   Unit Tests   ╲ 60% — isolated capability providers
   ╱────────────────────╲
```

**Unit tests (Rust):**
- Frame encoding/decoding
- MAC verification
- Capability routing
- Offline queue logic

**Unit tests (Kotlin):**
- Capability providers (mock Android APIs)
- Profile detection rules
- Permission manager
- Error reporter

**Integration tests:**
- Capability → UniFFI → Rust core → WebSocket
- Mock kernel: `MockKernelServer` (local WS server)
- Test: register capability → host requests → device responds

**E2E tests:**
- Real kernel + real device (adb)
- Test: connect → register → host commands → device actions
- Test: connection lost → reconnect → flush queue

**Performance tests:**
- Battery drain measurement (automated)
- Network bandwidth measurement
- Latency: command → response < 100ms (local), < 500ms (remote)
- Memory footprint: < 100MB RSS

**Реализация:**
- `rust/tests/` — Rust integration tests
- `app/src/test/` — Kotlin unit tests
- `app/src/androidTest/` — Android instrumented tests
- `scripts/e2e-test.sh` — E2E test runner
- CI: GitHub Actions → build → test → lint → clippy

---

### 91. Telemetry & Crash Reporting

**Что:** Агент сообщает о себе: crashes, usage, performance.

**Зачем:** Без telemetry не знаю: какие capabilities используются? Где падает?
Какой OEM проблемный? Сколько пользователей active?

**What to collect:**
| Event | Frequency | Privacy |
|---|---|---|
| **Crash** | On crash | Stack trace + device model + Android version |
| **ANR** | On ANR | Stack trace + device state |
| **Capability usage** | Daily | Which capabilities used, count, duration |
| **Connection uptime** | Hourly | Connected/disconnected, reconnect count |
| **Battery drain** | Hourly | Total drain, per-component |
| **Profile transitions** | On transition | From → to, trigger reason |
| **Permission grants** | On grant/revoke | Which permission, which capability affected |

**What NOT to collect:**
- Location data (privacy)
- Notification content (privacy)
- Contact data (privacy)
- Chat messages (privacy)
- Credentials / tokens (security)

**Storage:**
- Local: SQLite (7 days)
- Host: `POST /devices/:id/telemetry` → host aggregates
- Host: analytics dashboard per device

**User control:**
- Settings → Privacy → "Share anonymous usage data"
- Default: OFF (opt-in)
- Can export telemetry data (GDPR)

**Реализация:**
- `TelemetryCollector` — collects events
- `TelemetrySender` — batch send to host (hourly)
- `CrashReporter` — `Thread.setDefaultUncaughtExceptionHandler`
- Host: telemetry endpoint + analytics

---

### 92. Connection Health Monitoring

**Что:** Детальный мониторинг качества соединения.

**Зачем:** "Connected" ≠ "working". Connection can be:
- Connected but high latency (2s roundtrip)
- Connected but packet loss (50%)
- Connected but bandwidth limited (mobile data)
- Connected but unstable (flapping)

**Metrics:**
| Metric | Measurement | Threshold |
|---|---|---|
| **Latency** | Ping/pong WS frames | < 100ms (local), < 500ms (remote) |
| **Packet loss** | Sent vs acked frames | < 5% |
| **Bandwidth** | Bytes/sec | > 10 KB/s (min for geo push) |
| **Stability** | Reconnect count / hour | < 3 |
| **Jitter** | Latency variance | < 50ms |

**UI:**
```
┌─────────────────────────────────────┐
│  Connection Health                  │
│                                     │
│  Status: ● Connected (stable)       │
│  Latency: 45ms (avg)               │
│  Packet loss: 0.2%                  │
│  Bandwidth: 25 KB/s (WiFi)          │
│  Uptime: 99.7% (last 24h)          │
│  Reconnects: 1 (at 03:14)           │
│                                     │
│  [Test Connection] [View History]   │
└─────────────────────────────────────┘
```

**Реализация:**
- `ConnectionMonitor` — tracks WS frame stats
- Ping/pong: every 30s, measure RTT
- Stats: `ConnectionStats{latency, loss, bandwidth, uptime, reconnects}`
- Host: `GET /devices/:id/connection` → health metrics
- Alert: `{"type": "connection_degraded", "metric": "latency", "value": 2500}`

---

### 93. Capability Health Checks

**Что:** Периодическая проверка что каждая capability работает.

**Зачем:** Geo registered but GPS is disabled. Notification listener registered
but user revoked access. Mic registered but AudioRecord fails silently.

**Health check flow:**
```
Every 5 minutes:
  For each registered capability:
    → Run health probe (lightweight)
    → If probe fails:
      → Mark capability as "degraded"
      → Notify host: capability degraded
      → Try auto-fix (re-request permission, restart service)
      → If auto-fix fails: mark as "failed", notify user
```

**Capability probes:**
| Capability | Probe | Expected |
|---|---|---|
| `device.geo` | `LocationManager.getLastKnownLocation()` | Non-null, < 5min old |
| `device.battery` | `BatteryManager.getIntents()` | Non-null |
| `device.notifications` | `NotificationListenerService.getActiveNotifications()` | No exception |
| `device.clipboard` | `ClipboardManager.getPrimaryClip()` | Non-null |
| `device.contacts` | `ContentResolver.query(CONTACTS_URI)` | No exception |
| `device.mic` | `AudioRecord.getState()` | STATE_INITIALIZED |
| `device.speaker` | `AudioTrack.getState()` | STATE_INITIALIZED |

**UI:**
- Settings → Capabilities → each has health indicator (green/yellow/red)
- Tap → "Last check: 2 min ago. Status: OK"
- If degraded: "Geo: GPS disabled. [Enable GPS]"

**Реализация:**
- `CapabilityHealthChecker` — runs probes on schedule
- `CapabilityHealth{status, last_check, probe_result, error_message}`
- Host: `GET /devices/:id/capabilities/:cap/health`
- Auto-fix: re-request permission, restart service, fallback to alternative

---

## Примечания

- **Sideloading primary**: все permissions доступны без Google Play compliance.
  Battery bypass, accessibility, sensitive permissions — без ограничений.

- **OEM battery killers**: Samsung (Device Care), Xiaomi (MIUI Battery),
  Huawei (EMUI), Oppo (ColorOS) — Doze resilience критичен.

- **Rust core**: Tier 1-2 — Kotlin side. Tier 3+ — новые UniFFI traits в
  `rust/src/caps/`. Каждая новая capability = новый trait + handler.

- **Security**: sensitive capabilities (SMS, calls, contacts, screen) —
  encrypted storage + biometric gate. Пользователь должен явно grant access.

- **Voice pipeline**: mic/speaker capabilities уже имеют PCM streaming.
  Call screening (#38) и voice memo (#72) переиспользуют эту infrastructure.

- **Host-side AI**: Many features (expense tracking, call screening,
  notification routing) depend on host-side AI/STT/TTS.

- **Mesh**: Tier 4 mesh = distributed computing cluster для multi-device
  setups. Каждое устройство = specialist (phone=sensor, VPS=compute,
  laptop=storage). Не "10 phones" а "heterogeneous device fleet".

- **Profiles (#46)**: Система профилей = automation без ручных действий.
  Auto-detected + manual + host-defined JSON. Road detection через OSM.

- **Remote Lock/Wipe (#34)**: Требует `DEVICE_ADMIN` permission. Grace period
  30 мин до wipe (allow recovery if found).

- **OTA Config (#33)**: Обновление настроек, не кода. Config = настройки,
  Install (#54) = APK binary. Не заменяют друг друга.

- **Host Dashboard (#32)**: Face of product. Web UI на хосте для управления
  всеми устройствами. Auth = same JWT system.

- **Total ideas**: 93. Tier 1 (6) + Tier 1.5 (10) + Tier 2 (18) + Tier 2.5 (12) + Tier 3 (16) + Tier 4 (31) = production-ready core + infrastructure + advanced features + future vision.

---

*Последнее обновление: 2026-08-19*
