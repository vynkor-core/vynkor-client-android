//! Per-capability dispatch: maps an inbound ActionRequest to the Kotlin
//! provider behind that capability and builds the ActionResponse.

use veyron_wire::proto::veyron::{envelope, ActionRequest, ActionResponse, ActionStatus, Envelope};

use crate::agent::Agent;

pub mod audio;

/// Handle one host→device ActionRequest for a capability. The response echoes
/// the request `action_id` so the host router can match it to the caller.
pub fn handle_action_request(agent: &Agent, cap: &str, req: ActionRequest) -> Envelope {
    let action_id = req.action_id.clone();
    let resp = match cap {
        "battery" => action_battery(agent, &req),
        "geo" => action_geo(agent, &req),
        "clipboard" => action_clipboard(agent, &req),
        "contacts" => action_contacts(agent, &req),
        _ => Err(format!("unknown capability `{cap}`")),
    };
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

fn action_battery(agent: &Agent, _req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.battery_provider() else {
        return Err("battery provider not registered".into());
    };
    // Sanity-check the raw sensor readout: Android reports
    // Integer.MIN_VALUE when the property is unavailable, which used to reach
    // the host as -214748364.8 °C.
    let raw_temp = p.temperature_c();
    let temperature = if (-40.0..=100.0).contains(&raw_temp) {
        serde_json::json!(raw_temp)
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
        None => Err("no location fix yet".into()),
    }
}

fn action_clipboard(agent: &Agent, req: &ActionRequest) -> Result<serde_json::Value, String> {
    let Some(p) = agent.clipboard_provider() else {
        return Err("clipboard provider not registered".into());
    };
    let action = req.action.as_str();
    if action == "read" {
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
    let host_limit = params.get("limit").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
    // 0 = no preference → ceiling; anything above the ceiling is clamped here
    // so a hostile/buggy request cannot bypass it.
    let limit = if host_limit == 0 || host_limit > CONTACTS_MAX_LIMIT {
        CONTACTS_MAX_LIMIT
    } else {
        host_limit
    };
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
