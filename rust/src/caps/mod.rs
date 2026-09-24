//! Per-capability dispatch: maps an inbound ActionRequest to the Kotlin
//! provider behind that capability and builds the ActionResponse.

use vynkor_wire::proto::vynkor::{envelope, ActionRequest, ActionResponse, ActionStatus, Envelope};

use crate::agent::Agent;

pub mod audio;
pub mod device;

/// Handle one host→device ActionRequest for a capability. The response echoes
/// the request `action_id` so the host router can match it to the caller.
pub fn handle_action_request(agent: &Agent, cap: &str, mut req: ActionRequest) -> Envelope {
    req.action = sub_action(cap, &req);
    let action_id = req.action_id.clone();
    // A Kotlin provider that throws surfaces here as a UniFFI panic. Unwound
    // on the blocking thread it used to drop the reply entirely — the host
    // only saw its own timeout. Turn it into an ActionError instead.
    let resp =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| dispatch(agent, cap, &req)))
            .unwrap_or_else(|panic| {
                let what = panic
                    .downcast_ref::<String>()
                    .map(String::as_str)
                    .or_else(|| panic.downcast_ref::<&str>().copied())
                    .unwrap_or("unknown panic");
                tracing::error!(cap, what, "capability provider failed");
                Err(format!("{cap} provider failed: {what}"))
            });
    let (status, data_json, error) = match resp {
        Ok(json) => (
            ActionStatus::ActionOk,
            serde_json::to_vec(&json).unwrap_or_default(),
            String::new(),
        ),
        Err(e) => (ActionStatus::ActionError, Vec::new(), e),
    };
    Envelope {
        payload: Some(envelope::Payload::ActionResponse(ActionResponse {
            action_id,
            status: status as i32,
            data_json,
            error,
        })),
        ..Default::default()
    }
}

fn dispatch(agent: &Agent, cap: &str, req: &ActionRequest) -> Result<serde_json::Value, String> {
    match cap {
        "battery" => action_battery(agent, req),
        "geo" => action_geo(agent, req),
        "clipboard" => action_clipboard(agent, req),
        "contacts" => action_contacts(agent, req),
        "device" => device::device_info(agent),
        "wifi" => device::wifi(agent, req),
        "bluetooth" => device::bluetooth(agent, req),
        "dnd" => device::dnd(agent, req),
        "ringer" => device::ringer(agent, req),
        "brightness" => device::brightness(agent, req),
        "flashlight" => device::flashlight(agent, req),
        "launcher" => device::launcher(agent, req),
        "sms" => device::sms(agent, req),
        "calls" => device::calls(agent, req),
        "calendar" => device::calendar(agent, req),
        _ => Err(format!("unknown capability `{cap}`")),
    }
}

/// The capability's own verb (`get`, `on`, `read`, …). The kernel routes a
/// host call by its full name — `my-phone.flashlight` — so that is what
/// arrives in `req.action`, and every verb-based capability used to reject
/// it ("unknown flashlight action `my-phone.flashlight`"). Accepted forms:
/// - bare verb (`on`) — already a verb, kept;
/// - `{id}.{cap}` — verb from `params.action` (default: the cap's default);
/// - `{id}.{cap}.{verb}` — verb from the suffix.
pub(crate) fn sub_action(cap: &str, req: &ActionRequest) -> String {
    let action = req.action.as_str();
    if !action.contains('.') {
        return action.to_string();
    }
    let marker = format!(".{cap}.");
    if let Some(idx) = action.find(&marker) {
        return action[idx + marker.len()..].to_string();
    }
    serde_json::from_slice::<serde_json::Value>(&req.params_json)
        .ok()
        .and_then(|v| v.get("action").and_then(|a| a.as_str()).map(String::from))
        .unwrap_or_default()
}

fn action_battery(agent: &Agent, _req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.battery_provider() else {
        return Err("battery provider not registered".into());
    };
    // Sanity-check the raw sensor readout: Android reports
    // Integer.MIN_VALUE when the property is unavailable, which used to reach
    // the host as -214748364.8 °C.
    let raw_temp = p.temperature_c();
    let temperature = if (-40.0..=100.0).contains(&raw_temp) {
        // Sensor resolution is 0.1 °C; f32→f64 otherwise shows 38.099998.
        serde_json::json!((f64::from(raw_temp) * 10.0).round() / 10.0)
    } else {
        serde_json::Value::Null
    };
    Ok(serde_json::json!({
        "level_percent": p.level_percent(),
        "is_charging": p.is_charging(),
        "temperature_c": temperature,
    }))
}

fn action_geo(agent: &Agent, _req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.location_provider() else {
        return Err("location provider not registered".into());
    };
    match p.last_known() {
        Some(loc) => Ok(serde_json::json!({
            "lat": loc.lat,
            "lon": loc.lon,
            "accuracy_m": loc.accuracy_m,
        })),
        // "no fix yet" sent callers waiting for a fix that could never come
        // while location services were switched off.
        None => Err(p
            .unavailable_reason()
            .unwrap_or_else(|| "no location fix yet".into())),
    }
}

fn action_clipboard(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.clipboard_provider() else {
        return Err("clipboard provider not registered".into());
    };
    let action = req.action.as_str();
    if action == "read" || action.is_empty() {
        return Ok(serde_json::json!({ "text": p.read().unwrap_or_default() }));
    }
    if action == "write" {
        let text = serde_json::from_slice::<serde_json::Value>(&req.params_json)
            .ok()
            .and_then(|v| v.get("text").cloned())
            .and_then(|v| v.as_str().map(String::from))
            .ok_or_else(|| "clipboard write requires {\"text\": ...}".to_string())?;
        p.write(text);
        return Ok(serde_json::json!({ "ok": true }));
    }
    Err(format!("unknown clipboard action `{action}`"))
}

/// Hard ceiling regardless of what the host asks for (R-05 payload budget).
const CONTACTS_MAX_LIMIT: u32 = 200;

fn action_contacts(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.contacts_provider() else {
        return Err("contacts provider not registered".into());
    };
    let params = serde_json::from_slice::<serde_json::Value>(&req.params_json)
        .ok()
        .unwrap_or(serde_json::Value::Null);
    let query = params
        .get("query")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();
    let host_limit = params.get("limit").and_then(|v| v.as_u64()).unwrap_or(0);
    // 0 = no preference → ceiling; anything above the ceiling is clamped here
    // so a hostile/buggy request cannot bypass it.
    let limit = device::clamp_limit(host_limit, CONTACTS_MAX_LIMIT);
    let list = p.list(query, limit);
    let json: Vec<serde_json::Value> = list
        .iter()
        .map(|c| {
            serde_json::json!({
                "name": c.name,
                "phones": c.phones,
                "emails": c.emails,
            })
        })
        .collect();
    Ok(serde_json::Value::Array(json))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(action: &str, params: &str) -> ActionRequest {
        ActionRequest {
            action: action.into(),
            params_json: params.as_bytes().to_vec(),
            ..Default::default()
        }
    }

    #[test]
    fn sub_action_from_bare_verb_suffix_or_params() {
        assert_eq!(sub_action("flashlight", &req("on", "{}")), "on");
        assert_eq!(
            sub_action("flashlight", &req("my-phone.flashlight.toggle", "{}")),
            "toggle"
        );
        assert_eq!(
            sub_action(
                "flashlight",
                &req("my-phone.flashlight", r#"{"action":"off"}"#)
            ),
            "off"
        );
        // Full name without a verb → "" = the capability's default verb.
        assert_eq!(
            sub_action("flashlight", &req("my-phone.flashlight", "{}")),
            ""
        );
    }

    struct ThrowingCalls;
    impl crate::ffi::CallsProvider for ThrowingCalls {
        fn recent(&self, _limit: u32) -> Vec<crate::ffi::CallLogEntry> {
            panic!("IllegalArgumentException: Invalid token LIMIT");
        }
    }

    #[test]
    fn throwing_provider_yields_action_error_not_silence() {
        let agent = Agent::new(crate::ffi::AgentConfig {
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
        agent.set_calls(std::sync::Arc::new(ThrowingCalls));
        let mut r = req("test-device.calls", "{}");
        r.action_id = "a1".into();
        let Some(envelope::Payload::ActionResponse(resp)) =
            handle_action_request(&agent, "calls", r).payload
        else {
            panic!("expected an ActionResponse");
        };
        assert_eq!(resp.action_id, "a1");
        assert_eq!(resp.status, ActionStatus::ActionError as i32);
        assert!(resp.error.contains("Invalid token LIMIT"), "{}", resp.error);
    }
}
