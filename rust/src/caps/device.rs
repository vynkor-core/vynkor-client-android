//! Handlers for the extended device-control capabilities (info, wifi,
//! bluetooth, dnd, ringer, brightness, flashlight, launcher, sms, calls,
//! calendar). Each handler maps one host→device ActionRequest onto a Kotlin
//! provider trait and returns the JSON body for the ActionResponse.

use veyron_wire::proto::veyron::ActionRequest;

use crate::agent::Agent;

/// Hard ceilings regardless of what the host asks for (payload budget, R-05).
pub const WIFI_SCAN_MAX_LIMIT: u32 = 50;
pub const LAUNCHER_MAX_LIMIT: u32 = 200;
pub const SMS_MAX_LIMIT: u32 = 100;
pub const CALLS_MAX_LIMIT: u32 = 100;
pub const CALENDAR_MAX_LIMIT: u32 = 100;
pub const CALENDAR_MAX_DAYS_AHEAD: u32 = 31;

/// 0 or out-of-ceiling → ceiling; anything inside passes through.
pub(crate) fn clamp_limit(host_limit: u64, max: u32) -> u32 {
    if host_limit == 0 || host_limit > max as u64 {
        max
    } else {
        host_limit as u32
    }
}

fn params(req: &ActionRequest) -> serde_json::Value {
    serde_json::from_slice(&req.params_json).unwrap_or(serde_json::Value::Null)
}

fn str_param(p: &serde_json::Value, key: &str) -> String {
    p.get(key)
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string()
}

fn u64_param(p: &serde_json::Value, key: &str) -> u64 {
    p.get(key).and_then(|v| v.as_u64()).unwrap_or(0)
}

fn i64_param(p: &serde_json::Value, key: &str) -> i64 {
    p.get(key).and_then(|v| v.as_i64()).unwrap_or(0)
}

// ---------------------------------------------------------------- device.info

pub fn device_info(agent: &Agent) -> Result<serde_json::Value, String> {
    let Some(p) = agent.device_info_provider() else {
        return Err("device info provider not registered".into());
    };
    let info = p.snapshot();
    Ok(serde_json::json!({
        "model": info.model,
        "manufacturer": info.manufacturer,
        "brand": info.brand,
        "android_release": info.android_release,
        "sdk_int": info.sdk_int,
        "locale": info.locale,
        "screen_width_px": info.screen_width_px,
        "screen_height_px": info.screen_height_px,
    }))
}

// ------------------------------------------------------------------- wifi

pub fn wifi(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.wifi_provider() else {
        return Err("wifi provider not registered".into());
    };
    match req.action.as_str() {
        "status" | "" => match p.status() {
            Some(s) => Ok(serde_json::json!({
                "enabled": s.enabled,
                "ssid": s.ssid,
                "ip": s.ip,
                "link_speed_mbps": s.link_speed_mbps,
            })),
            None => Ok(serde_json::json!({ "enabled": false })),
        },
        "scan" => {
            let networks = p.scan();
            let list: Vec<serde_json::Value> = networks
                .into_iter()
                .take(clamp_limit(u64::MAX, WIFI_SCAN_MAX_LIMIT) as usize)
                .map(|n| {
                    serde_json::json!({
                        "ssid": n.ssid,
                        "bssid": n.bssid,
                        "rssi": n.rssi,
                        "secure": n.secure,
                    })
                })
                .collect();
            Ok(serde_json::Value::Array(list))
        }
        other => Err(format!("unknown wifi action `{other}`")),
    }
}

// ---------------------------------------------------------------- bluetooth

pub fn bluetooth(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.bluetooth_provider() else {
        return Err("bluetooth provider not registered".into());
    };
    match req.action.as_str() {
        "status" | "" => match p.status() {
            Some(s) => Ok(serde_json::json!({
                "enabled": s.enabled,
                "adapter_name": s.adapter_name,
            })),
            None => Ok(serde_json::json!({ "enabled": false })),
        },
        "paired" => Ok(serde_json::Value::Array(
            p.paired()
                .into_iter()
                .map(|d| {
                    serde_json::json!({
                        "name": d.name,
                        "address": d.address,
                        "connected": d.connected,
                    })
                })
                .collect(),
        )),
        other => Err(format!("unknown bluetooth action `{other}`")),
    }
}

// ---------------------------------------------------------------- dnd/ringer

pub fn dnd(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.dnd_provider() else {
        return Err("dnd provider not registered".into());
    };
    match req.action.as_str() {
        "get" | "" => Ok(serde_json::json!({ "filter": p.filter() })),
        "set" => {
            let mode = str_param(&params(req), "mode");
            if mode.is_empty() {
                return Err(r#"dnd set requires {"mode": "..."}"#.into());
            }
            if !p.set_filter(mode.clone()) {
                return Err(format!("failed to set dnd filter `{mode}` (no policy access?)"));
            }
            Ok(serde_json::json!({ "filter": p.filter() }))
        }
        other => Err(format!("unknown dnd action `{other}`")),
    }
}

pub fn ringer(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.ringer_provider() else {
        return Err("ringer provider not registered".into());
    };
    match req.action.as_str() {
        "get" | "" => Ok(serde_json::json!({ "mode": p.mode() })),
        "set" => {
            let mode = str_param(&params(req), "mode");
            if !p.set_mode(mode.clone()) {
                return Err(format!("failed to set ringer mode `{mode}`"));
            }
            Ok(serde_json::json!({ "mode": p.mode() }))
        }
        other => Err(format!("unknown ringer action `{other}`")),
    }
}

// ---------------------------------------------------------------- brightness

pub fn brightness(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.brightness_provider() else {
        return Err("brightness provider not registered".into());
    };
    match req.action.as_str() {
        "get" | "" => Ok(serde_json::json!({
            "level": p.level(),
            "auto": p.auto(),
        })),
        "set" => {
            let p_params = params(req);
            if let Some(auto) = p_params.get("auto").and_then(|v| v.as_bool()) {
                if !p.set_auto(auto) {
                    return Err("failed to change adaptive brightness (WRITE_SETTINGS denied?)".into());
                }
            }
            if let Some(level) = p_params.get("level").and_then(|v| v.as_u64()) {
                if level > 255 {
                    return Err("brightness level must be 0..=255".into());
                }
                if !p.set_level(level as u8) {
                    return Err("failed to set brightness (WRITE_SETTINGS denied?)".into());
                }
            }
            if p_params.get("level").is_none() && p_params.get("auto").is_none() {
                return Err(r#"brightness set requires {"level": 0..255} and/or {"auto": bool}"#.into());
            }
            Ok(serde_json::json!({
                "level": p.level(),
                "auto": p.auto(),
            }))
        }
        other => Err(format!("unknown brightness action `{other}`")),
    }
}

// ---------------------------------------------------------------- flashlight

pub fn flashlight(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.flashlight_provider() else {
        return Err("flashlight provider not registered".into());
    };
    if !p.available() && req.action != "get" {
        return Err("no flash unit on this device".into());
    }
    match req.action.as_str() {
        "get" | "" => Ok(serde_json::json!({
            "available": p.available(),
            "on": p.is_on(),
        })),
        "on" => set_torch(p.as_ref(), true),
        "off" => set_torch(p.as_ref(), false),
        "toggle" => set_torch(p.as_ref(), !p.is_on()),
        other => Err(format!("unknown flashlight action `{other}`")),
    }
}

fn set_torch(
    p: &dyn crate::ffi::FlashlightProvider,
    on: bool,
) -> Result<serde_json::Value, String> {
    if !p.set_on(on) {
        return Err("failed to switch torch".into());
    }
    Ok(serde_json::json!({ "on": on }))
}

// ---------------------------------------------------------------- launcher

pub fn launcher(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.launcher_provider() else {
        return Err("launcher provider not registered".into());
    };
    match req.action.as_str() {
        "list" => {
            let limit = clamp_limit(u64_param(&params(req), "limit"), LAUNCHER_MAX_LIMIT);
            let apps: Vec<serde_json::Value> = p
                .apps()
                .into_iter()
                .take(limit as usize)
                .map(|a| serde_json::json!({ "package": a.package_name, "name": a.app_name }))
                .collect();
            Ok(serde_json::Value::Array(apps))
        }
        "open" => {
            let package = str_param(&params(req), "package");
            if package.is_empty() {
                return Err(r#"launcher open requires {"package": "..."}"#.into());
            }
            if !p.launch(package.clone()) {
                return Err(format!("could not open `{package}`"));
            }
            Ok(serde_json::json!({ "ok": true }))
        }
        other => Err(format!("unknown launcher action `{other}`")),
    }
}

// ---------------------------------------------------------------- sms

pub fn sms(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.sms_provider() else {
        return Err("sms provider not registered".into());
    };
    if req.action.as_str() != "inbox" && !req.action.is_empty() {
        return Err(format!("unknown sms action `{}`", req.action));
    }
    let p_params = params(req);
    let query = str_param(&p_params, "query");
    let limit = clamp_limit(u64_param(&p_params, "limit"), SMS_MAX_LIMIT);
    let messages: Vec<serde_json::Value> = p
        .inbox(query, limit)
        .into_iter()
        .map(|m| {
            serde_json::json!({
                "sender": m.sender,
                "body": m.body,
                "timestamp_ms": m.timestamp_ms,
            })
        })
        .collect();
    Ok(serde_json::Value::Array(messages))
}

// ---------------------------------------------------------------- calls

pub fn calls(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.calls_provider() else {
        return Err("calls provider not registered".into());
    };
    if req.action.as_str() != "recent" && !req.action.is_empty() {
        return Err(format!("unknown calls action `{}`", req.action));
    }
    let limit = clamp_limit(u64_param(&params(req), "limit"), CALLS_MAX_LIMIT);
    let entries: Vec<serde_json::Value> = p
        .recent(limit)
        .into_iter()
        .map(|c| {
            serde_json::json!({
                "number": c.number,
                "name": c.name,
                "type": c.call_type,
                "timestamp_ms": c.timestamp_ms,
                "duration_s": c.duration_s,
            })
        })
        .collect();
    Ok(serde_json::Value::Array(entries))
}

// ---------------------------------------------------------------- calendar

pub fn calendar(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.calendar_provider() else {
        return Err("calendar provider not registered".into());
    };
    match req.action.as_str() {
        "upcoming" | "" => {
            let p_params = params(req);
            let days = clamp_limit(u64_param(&p_params, "days"), CALENDAR_MAX_DAYS_AHEAD);
            let limit = clamp_limit(u64_param(&p_params, "limit"), CALENDAR_MAX_LIMIT);
            let events: Vec<serde_json::Value> = p
                .upcoming(days.max(1), limit)
                .into_iter()
                .map(|e| {
                    serde_json::json!({
                        "title": e.title,
                        "description": e.description,
                        "location": e.location,
                        "start_ms": e.start_ms,
                        "end_ms": e.end_ms,
                        "calendar": e.calendar_name,
                    })
                })
                .collect();
            Ok(serde_json::Value::Array(events))
        }
        "add" => {
            let p_params = params(req);
            let title = str_param(&p_params, "title");
            if title.is_empty() {
                return Err(r#"calendar add requires {"title": "..."}"#.into());
            }
            let start = i64_param(&p_params, "start_ms");
            let end = i64_param(&p_params, "end_ms");
            if end > 0 && end < start {
                return Err("event end is before its start".into());
            }
            match p.add_event(
                title,
                str_param(&p_params, "description"),
                str_param(&p_params, "location"),
                start,
                if end > 0 { end } else { start },
            ) {
                crate::ffi::CalendarWriteResult::Added { event_id } => {
                    Ok(serde_json::json!({ "ok": true, "event_id": event_id }))
                }
                crate::ffi::CalendarWriteResult::Failed { reason } => Err(reason),
            }
        }
        other => Err(format!("unknown calendar action `{other}`")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi::{
        AppEntry, BluetoothStatus, CalendarWriteResult, CallLogEntry, DeviceInfo,
        PairedBluetoothDevice, SmsMessage, WifiNetwork, WifiStatus,
    };
    use crate::protocol::is_kernel_routed;
    use std::sync::Mutex;
    use veyron_wire::proto::veyron::envelope;

    fn agent_with(register: impl FnOnce(&crate::agent::Agent)) -> crate::agent::Agent {
        let agent = crate::agent::Agent::new(crate::ffi::AgentConfig {
            host_url: "ws://127.0.0.1:9".into(),
            jwt_token: String::new(),
            device_secret: String::new(),
            cert_pem: String::new(),
            device_id: "test-device".into(),
            capabilities: Vec::new(),
            os_version: "14".into(),
            arch: "x86_64".into(),
            user_id: "default".into(),
        });
        register(&agent);
        agent
    }

    fn request(cap_action: &str, params: serde_json::Value) -> ActionRequest {
        ActionRequest {
            action_id: "act-test".into(),
            action: cap_action.into(),
            params_json: serde_json::to_vec(&params).unwrap_or_default(),
            timeout_ms: 1000,
            streaming: false,
            caller_plugin_id: String::new(),
        }
    }

    fn dispatch(agent: &crate::agent::Agent, cap: &str, req: ActionRequest) -> (i32, String) {
        let env = super::super::handle_action_request(agent, cap, req);
        match env.payload {
            Some(envelope::Payload::ActionResponse(r)) => {
                let data = if r.data_json.is_empty() {
                    String::new()
                } else {
                    String::from_utf8_lossy(&r.data_json).into_owned()
                };
                (r.status, if r.error.is_empty() { data } else { r.error })
            }
            other => panic!("expected ActionResponse, got {other:?}"),
        }
    }

    const OK: i32 = veyron_wire::proto::veyron::ActionStatus::ActionOk as i32;
    const ERR: i32 = veyron_wire::proto::veyron::ActionStatus::ActionError as i32;

    #[test]
    fn clamp_limit_zero_and_over_ceiling_resolve_to_max() {
        assert_eq!(clamp_limit(0, 100), 100);
        assert_eq!(clamp_limit(500, 100), 100);
        assert_eq!(clamp_limit(42, 100), 42);
        assert_eq!(clamp_limit(u64::MAX, 50), 50);
    }

    #[test]
    fn missing_provider_is_a_typed_error_not_a_panic() {
        let agent = agent_with(|_| {});
        for cap in [
            "device", "wifi", "bluetooth", "dnd", "ringer", "brightness",
            "flashlight", "launcher", "sms", "calls", "calendar",
        ] {
            let (status, msg) = dispatch(&agent, cap, request("", serde_json::json!({})));
            assert_eq!(status, ERR, "cap `{cap}`");
            assert!(msg.contains("not registered"), "cap `{cap}`: {msg}");
        }
    }

    #[test]
    fn unknown_capability_is_rejected() {
        let agent = agent_with(|_| {});
        let (status, msg) = dispatch(&agent, "teleport", request("go", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("unknown capability"));
    }

    // ------------------------------------------------------------ fakes

    struct FakeInfo;
    impl crate::ffi::DeviceInfoProvider for FakeInfo {
        fn snapshot(&self) -> DeviceInfo {
            DeviceInfo {
                model: "MI 6".into(),
                manufacturer: "Xiaomi".into(),
                brand: "xiaomi".into(),
                android_release: "14".into(),
                sdk_int: 34,
                locale: "ru-RU".into(),
                screen_width_px: 1080,
                screen_height_px: 1920,
            }
        }
    }

    struct FakeWifi {
        scan_calls: Mutex<usize>,
    }
    impl FakeWifi {
        fn new() -> Self {
            Self { scan_calls: Mutex::new(0) }
        }
    }
    impl crate::ffi::WifiProvider for FakeWifi {
        fn status(&self) -> Option<WifiStatus> {
            Some(WifiStatus {
                enabled: true,
                ssid: "Home".into(),
                ip: "192.168.1.2".into(),
                link_speed_mbps: 866,
            })
        }
        fn scan(&self) -> Vec<WifiNetwork> {
            *self.scan_calls.lock().unwrap() += 1;
            (0..60)
                .map(|i| WifiNetwork {
                    ssid: format!("net-{i}"),
                    bssid: format!("00:00:00:00:{i:02}:00"),
                    rssi: -40 - i,
                    secure: true,
                })
                .collect()
        }
    }

    struct FakeBt;
    impl crate::ffi::BluetoothProvider for FakeBt {
        fn status(&self) -> Option<BluetoothStatus> {
            Some(BluetoothStatus { enabled: true, adapter_name: "phone".into() })
        }
        fn paired(&self) -> Vec<PairedBluetoothDevice> {
            vec![PairedBluetoothDevice {
                name: "JBL".into(),
                address: "AA:BB".into(),
                connected: true,
            }]
        }
    }

    struct FakeDnd {
        set_to: Mutex<Option<String>>,
    }
    impl crate::ffi::DndProvider for FakeDnd {
        fn filter(&self) -> String {
            self.set_to.lock().unwrap().clone().unwrap_or_else(|| "off".into())
        }
        fn set_filter(&self, mode: String) -> bool {
            *self.set_to.lock().unwrap() = Some(mode);
            true
        }
    }

    struct RefusingDnd;
    impl crate::ffi::DndProvider for RefusingDnd {
        fn filter(&self) -> String {
            "off".into()
        }
        fn set_filter(&self, _mode: String) -> bool {
            false
        }
    }

    struct FakeRinger {
        current: Mutex<String>,
    }
    impl crate::ffi::RingerProvider for FakeRinger {
        fn mode(&self) -> String {
            self.current.lock().unwrap().clone()
        }
        fn set_mode(&self, mode: String) -> bool {
            matches!(mode.as_str(), "normal" | "silent" | "vibrate")
                .then(|| *self.current.lock().unwrap() = mode)
                .is_some()
        }
    }

    struct FakeBrightness {
        level: Mutex<u8>,
        auto: Mutex<bool>,
    }
    impl crate::ffi::BrightnessProvider for FakeBrightness {
        fn level(&self) -> Option<u8> {
            Some(*self.level.lock().unwrap())
        }
        fn auto(&self) -> bool {
            *self.auto.lock().unwrap()
        }
        fn set_level(&self, level: u8) -> bool {
            *self.level.lock().unwrap() = level;
            true
        }
        fn set_auto(&self, on: bool) -> bool {
            *self.auto.lock().unwrap() = on;
            true
        }
    }

    struct FakeTorch {
        on: Mutex<bool>,
    }
    impl crate::ffi::FlashlightProvider for FakeTorch {
        fn available(&self) -> bool {
            true
        }
        fn is_on(&self) -> bool {
            *self.on.lock().unwrap()
        }
        fn set_on(&self, on: bool) -> bool {
            *self.on.lock().unwrap() = on;
            true
        }
    }

    struct NoFlash;
    impl crate::ffi::FlashlightProvider for NoFlash {
        fn available(&self) -> bool {
            false
        }
        fn is_on(&self) -> bool {
            false
        }
        fn set_on(&self, _on: bool) -> bool {
            false
        }
    }

    struct FakeLauncher {
        last_opened: Mutex<Option<String>>,
    }
    impl crate::ffi::LauncherProvider for FakeLauncher {
        fn apps(&self) -> Vec<AppEntry> {
            (0..300)
                .map(|i| AppEntry {
                    package_name: format!("app.{i}"),
                    app_name: format!("App {i}"),
                })
                .collect()
        }
        fn launch(&self, package_name: String) -> bool {
            *self.last_opened.lock().unwrap() = Some(package_name);
            true
        }
    }

    struct FakeSms;
    impl crate::ffi::SmsProvider for FakeSms {
        fn inbox(&self, query: String, limit: u32) -> Vec<SmsMessage> {
            (0..limit.min(5))
                .filter(|i| query.is_empty() || format!("body-{i}").contains(&query))
                .map(|i| SmsMessage {
                    sender: format!("+700{i}"),
                    body: format!("body-{i}"),
                    timestamp_ms: 1_000 + i as i64,
                })
                .collect()
        }
    }

    struct FakeCalls;
    impl crate::ffi::CallsProvider for FakeCalls {
        fn recent(&self, limit: u32) -> Vec<CallLogEntry> {
            (0..limit.min(3))
                .map(|i| CallLogEntry {
                    number: "+79160000000".into(),
                    name: format!("Caller {i}"),
                    call_type: "incoming".into(),
                    timestamp_ms: 2_000 + i as i64,
                    duration_s: 30,
                })
                .collect()
        }
    }

    struct FakeCalendar {
        added: Mutex<Vec<String>>,
    }
    impl crate::ffi::CalendarProvider for FakeCalendar {
        fn upcoming(&self, _days: u32, limit: u32) -> Vec<crate::ffi::CalendarEvent> {
            (0..limit.min(2))
                .map(|i| crate::ffi::CalendarEvent {
                    title: format!("Event {i}"),
                    description: String::new(),
                    location: String::new(),
                    start_ms: 10_000 + i as i64,
                    end_ms: 11_000 + i as i64,
                    calendar_name: "Personal".into(),
                })
                .collect()
        }
        fn add_event(
            &self,
            title: String,
            _description: String,
            _location: String,
            _start_ms: i64,
            _end_ms: i64,
        ) -> CalendarWriteResult {
            self.added.lock().unwrap().push(title);
            CalendarWriteResult::Added { event_id: 77 }
        }
    }

    // ---------------------------------------------------------- tests

    #[test]
    fn device_info_roundtrips_all_fields() {
        let agent = agent_with(|a| a.set_device_info(std::sync::Arc::new(FakeInfo)));
        let (status, body) = dispatch(&agent, "device", request("", serde_json::json!({})));
        assert_eq!(status, OK);
        let v: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(v["model"], "MI 6");
        assert_eq!(v["sdk_int"], 34);
        assert_eq!(v["screen_width_px"], 1080);
    }

    #[test]
    fn wifi_scan_is_capped_at_ceiling() {
        let wifi = std::sync::Arc::new(FakeWifi::new());
        let arc_for_check = std::sync::Arc::clone(&wifi);
        let agent = agent_with(|a| a.set_wifi(wifi));
        let (status, body) = dispatch(&agent, "wifi", request("scan", serde_json::json!({})));
        assert_eq!(status, OK);
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), WIFI_SCAN_MAX_LIMIT as usize);
        assert!(*arc_for_check.scan_calls.lock().unwrap() == 1);
    }

    #[test]
    fn wifi_status_defaults_when_no_fix() {
        struct NoneWifi;
        impl crate::ffi::WifiProvider for NoneWifi {
            fn status(&self) -> Option<WifiStatus> {
                None
            }
            fn scan(&self) -> Vec<WifiNetwork> {
                vec![]
            }
        }
        let agent = agent_with(|a| a.set_wifi(std::sync::Arc::new(NoneWifi)));
        let (status, body) = dispatch(&agent, "wifi", request("status", serde_json::json!({})));
        assert_eq!(status, OK);
        assert_eq!(body, r#"{"enabled":false}"#);
    }

    #[test]
    fn unknown_wifi_action_is_typed_error() {
        let agent = agent_with(|a| a.set_wifi(std::sync::Arc::new(FakeWifi::new())));
        let (status, msg) = dispatch(&agent, "wifi", request("explode", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("unknown wifi action"));
    }

    #[test]
    fn bluetooth_status_and_paired() {
        let agent = agent_with(|a| a.set_bluetooth(std::sync::Arc::new(FakeBt)));
        let (_, body) = dispatch(&agent, "bluetooth", request("status", serde_json::json!({})));
        assert!(body.contains("\"adapter_name\":\"phone\""));
        let (_, body) = dispatch(&agent, "bluetooth", request("paired", serde_json::json!({})));
        assert!(body.contains("JBL"));
    }

    #[test]
    fn dnd_set_updates_filter_and_requires_mode() {
        let dnd = std::sync::Arc::new(FakeDnd { set_to: Mutex::new(None) });
        let agent = agent_with(|a| a.set_dnd(dnd));
        let (status, _) =
            dispatch(&agent, "dnd", request("set", serde_json::json!({ "mode": "priority" })));
        assert_eq!(status, OK);
        let (_, body) = dispatch(&agent, "dnd", request("get", serde_json::json!({})));
        assert_eq!(body, r#"{"filter":"priority"}"#);

        let (status, msg) = dispatch(&agent, "dnd", request("set", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("requires"));
    }

    #[test]
    fn dnd_refusal_surfaces_as_error() {
        let agent = agent_with(|a| a.set_dnd(std::sync::Arc::new(RefusingDnd)));
        let (status, msg) =
            dispatch(&agent, "dnd", request("set", serde_json::json!({ "mode": "none" })));
        assert_eq!(status, ERR);
        assert!(msg.contains("policy access"));
    }

    #[test]
    fn ringer_rejects_invalid_mode() {
        let agent =
            agent_with(|a| a.set_ringer(std::sync::Arc::new(FakeRinger { current: Mutex::new("normal".into()) })));
        let (status, _) =
            dispatch(&agent, "ringer", request("set", serde_json::json!({ "mode": "loud" })));
        assert_eq!(status, ERR, "invalid mode must not silently succeed");
        let (status, body) =
            dispatch(&agent, "ringer", request("set", serde_json::json!({ "mode": "vibrate" })));
        assert_eq!(status, OK);
        assert_eq!(body, r#"{"mode":"vibrate"}"#);
    }

    #[test]
    fn brightness_set_validates_range_and_updates_state() {
        let agent = agent_with(|a| {
            a.set_brightness(std::sync::Arc::new(FakeBrightness {
                level: Mutex::new(120),
                auto: Mutex::new(false),
            }))
        });
        let (status, msg) =
            dispatch(&agent, "brightness", request("set", serde_json::json!({ "level": 999 })));
        assert_eq!(status, ERR);
        assert!(msg.contains("0..=255"));

        let (status, body) = dispatch(
            &agent,
            "brightness",
            request("set", serde_json::json!({ "level": 42, "auto": true })),
        );
        assert_eq!(status, OK);
        assert_eq!(body, r#"{"auto":true,"level":42}"#);

        let (status, msg) = dispatch(&agent, "brightness", request("set", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("requires"));
    }

    #[test]
    fn flashlight_toggle_flips_state() {
        let agent = agent_with(|a| a.set_flashlight(std::sync::Arc::new(FakeTorch { on: Mutex::new(false) })));
        let (status, body) = dispatch(&agent, "flashlight", request("toggle", serde_json::json!({})));
        assert_eq!(status, OK);
        assert_eq!(body, r#"{"on":true}"#);
        let (_, body) = dispatch(&agent, "flashlight", request("get", serde_json::json!({})));
        assert_eq!(body, r#"{"available":true,"on":true}"#);
    }

    #[test]
    fn flashlight_absent_unit_refuses_mutations_but_answers_get() {
        let agent = agent_with(|a| a.set_flashlight(std::sync::Arc::new(NoFlash)));
        let (status, msg) = dispatch(&agent, "flashlight", request("on", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("no flash unit"));
        let (status, body) = dispatch(&agent, "flashlight", request("get", serde_json::json!({})));
        assert_eq!(status, OK);
        assert_eq!(body, r#"{"available":false,"on":false}"#);
    }

    #[test]
    fn launcher_list_is_capped_and_open_needs_package() {
        let launcher = std::sync::Arc::new(FakeLauncher { last_opened: Mutex::new(None) });
        let provider: std::sync::Arc<dyn crate::ffi::LauncherProvider> = launcher.clone();
        let agent = agent_with(|a| a.set_launcher(provider));

        let (status, body) =
            dispatch(&agent, "launcher", request("list", serde_json::json!({ "limit": 5000 })));
        assert_eq!(status, OK);
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), LAUNCHER_MAX_LIMIT as usize);

        let (status, msg) = dispatch(&agent, "launcher", request("open", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("requires"));

        let (status, body) = dispatch(
            &agent,
            "launcher",
            request("open", serde_json::json!({ "package": "com.whatsapp" })),
        );
        assert_eq!(status, OK);
        assert_eq!(
            launcher.last_opened.lock().unwrap().as_deref(),
            Some("com.whatsapp")
        );
        assert_eq!(body, r#"{"ok":true}"#);
    }

    #[test]
    fn sms_inbox_clamps_limit_and_applies_query() {
        let agent = agent_with(|a| a.set_sms(std::sync::Arc::new(FakeSms)));
        let (status, body) =
            dispatch(&agent, "sms", request("inbox", serde_json::json!({ "query": "body-1" })));
        assert_eq!(status, OK);
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), 1);
        assert_eq!(arr[0]["sender"], "+7001");

        let (_, body) = dispatch(&agent, "sms", request("inbox", serde_json::json!({})));
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), 5, "fake provider only has 5 rows; limit clamped to ceiling");
    }

    #[test]
    fn calls_recent_clamps_limit() {
        let agent = agent_with(|a| a.set_calls(std::sync::Arc::new(FakeCalls)));
        let (_, body) =
            dispatch(&agent, "calls", request("recent", serde_json::json!({ "limit": 99 })));
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), 3);
        assert_eq!(arr[0]["type"], "incoming");
    }

    #[test]
    fn calendar_upcoming_and_add_roundtrip() {
        let fake = std::sync::Arc::new(FakeCalendar { added: Mutex::new(Vec::new()) });
        let cal: std::sync::Arc<dyn crate::ffi::CalendarProvider> = fake.clone();
        let agent = agent_with(|a| a.set_calendar(cal));

        let (status, body) = dispatch(&agent, "calendar", request("upcoming", serde_json::json!({})));
        assert_eq!(status, OK);
        let arr: Vec<serde_json::Value> = serde_json::from_str(&body).unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["calendar"], "Personal");

        let (status, body) = dispatch(
            &agent,
            "calendar",
            request(
                "add",
                serde_json::json!({
                    "title": "Standup",
                    "start_ms": 123,
                    "end_ms": 456,
                    "description": "daily",
                    "location": "office"
                }),
            ),
        );
        assert_eq!(status, OK);
        assert!(body.contains(r#""event_id":77"#));
        assert_eq!(*fake.added.lock().unwrap(), vec!["Standup".to_string()]);
    }

    #[test]
    fn calendar_add_validates_title_and_time_order() {
        let agent = agent_with(|a| {
            a.set_calendar(std::sync::Arc::new(FakeCalendar { added: Mutex::new(Vec::new()) }))
        });
        let (status, msg) = dispatch(&agent, "calendar", request("add", serde_json::json!({})));
        assert_eq!(status, ERR);
        assert!(msg.contains("title"));

        let (status, msg) = dispatch(
            &agent,
            "calendar",
            request(
                "add",
                serde_json::json!({ "title": "x", "start_ms": 200, "end_ms": 100 }),
            ),
        );
        assert_eq!(status, ERR);
        assert!(msg.contains("before its start"));
    }

    #[test]
    fn responses_are_kernel_routed_envelopes() {
        use prost::Message as _;
        let agent = agent_with(|a| a.set_ringer(std::sync::Arc::new(FakeRinger { current: Mutex::new("normal".into()) })));
        let env = super::super::handle_action_request(&agent, "ringer", request("get", serde_json::json!({})));
        assert!(matches!(env.payload, Some(envelope::Payload::ActionResponse(_))));
        let mut payload = Vec::new();
        env.encode(&mut payload).unwrap();
        let frame = crate::protocol::build_frame("kernel", 0, payload);
        assert!(is_kernel_routed(&frame));
    }
}
