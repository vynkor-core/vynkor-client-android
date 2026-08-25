//! WS transport to the host kernel — one connection per capability.
//!
//! Mirrors the D-06 bridge handshake: JWT in `Sec-WebSocket-Protocol`,
//! register → ack → `derive_session_key` → `FLAG_MAC_PRESENT` on every
//! subsequent frame. The connection splits into read/write halves so the
//! inbound dispatch loop and the outbound push-path drain run concurrently.

use std::time::Duration;

use futures_util::stream::{SplitSink, SplitStream};
use futures_util::{SinkExt, StreamExt};
use prost::Message;
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::{connect_async, MaybeTlsStream, WebSocketStream};
use veyron_wire::mac::derive_session_key;
use veyron_wire::proto::veyron::{envelope, DeviceOs, Envelope, PluginRegister, PluginRegisterAck};
use veyron_wire::PROTOCOL_VERSION;

use crate::error::AgentError;
use crate::protocol::{build_frame, frame_to_bytes, parse_frame};

pub const BACKOFF_INITIAL: Duration = Duration::from_secs(1);
pub const BACKOFF_MAX: Duration = Duration::from_secs(30);

/// R-06: a host that accepts the handshake but never acks the register must
/// not wedge the cap loop forever on `ws.next()`.
pub const REGISTER_ACK_TIMEOUT: Duration = Duration::from_secs(15);

type WsStream = WebSocketStream<MaybeTlsStream<TcpStream>>;

/// Registration parameters for one capability.
#[derive(Debug, Clone)]
pub struct RegisterParams {
    pub device_id: String,
    pub cap: String,
    pub jwt_token: String,
    pub jwt_secret: Option<String>,
    /// Host's served TLS cert (PEM) to pin on `wss://` (self-signed). `None` =
    /// webpki-roots verification.
    pub cert_pem: Option<String>,
    pub os_version: String,
    pub arch: String,
    pub user_id: String,
}

impl RegisterParams {
    /// `<device_id>.<cap>` — the D-14 naming, globally unique per device.
    pub fn plugin_id(&self) -> String {
        format!("{}.{}", self.device_id, self.cap)
    }
}

/// One live WS connection to the host, split after registration.
pub struct CapConn {
    read: SplitStream<WsStream>,
    write: SplitSink<WsStream, WsMessage>,
    session_key: Option<[u8; 32]>,
}

impl CapConn {
    /// Connect + register one capability. Returns only once the host acked.
    pub async fn connect_and_register(
        host_url: &str,
        params: &RegisterParams,
    ) -> Result<Self, AgentError> {
        let url = resolve_ws_url(host_url)?;
        // tungstenite 0.30 implements IntoClientRequest for &str/String, not
        // url::Url — convert through the string form
        let mut req = url
            .as_str()
            .into_client_request()
            .map_err(|e| AgentError::Connect(e.to_string()))?;
        // same handshake as the SDK/bridge: JWT rides the subprotocol header,
        // never the URL (access-log hygiene)
        let protocol = if params.jwt_token.is_empty() {
            "veyron".to_string()
        } else {
            format!("veyron, {}", params.jwt_token)
        };
        let value =
            HeaderValue::from_str(&protocol).map_err(|e| AgentError::Connect(e.to_string()))?;
        req.headers_mut().insert("sec-websocket-protocol", value);

        let (ws, _resp) = if url.scheme() == "wss" {
            match &params.cert_pem {
                Some(pem) => {
                    let connector = tokio_tungstenite::Connector::Rustls(std::sync::Arc::new(
                        pinned_tls_config(pem)?,
                    ));
                    tokio_tungstenite::connect_async_tls_with_config(
                        req,
                        None,
                        false,
                        Some(connector),
                    )
                    .await
                    .map_err(|e| AgentError::Connect(e.to_string()))?
                }
                None => connect_async(req)
                    .await
                    .map_err(|e| AgentError::Connect(e.to_string()))?,
            }
        } else {
            connect_async(req)
                .await
                .map_err(|e| AgentError::Connect(e.to_string()))?
        };

        let plugin_id = params.plugin_id();
        let reg = PluginRegister {
            plugin_id: plugin_id.clone(),
            version: env!("CARGO_PKG_VERSION").to_string(),
            description: format!("vynkor device-agent capability {}", params.cap),
            manifest: None,
            jwt_token: params.jwt_token.clone(),
            device_id: params.device_id.clone(),
            os: DeviceOs::Android as i32,
            arch: params.arch.clone(),
            os_version: params.os_version.clone(),
            capabilities: vec![params.cap.clone()],
            protocol_version: PROTOCOL_VERSION.to_string(),
            user_id: params.user_id.clone(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::PluginRegister(reg)),
            ..Default::default()
        };
        let mut payload = Vec::new();
        env.encode(&mut payload).map_err(|e| {
            AgentError::Wire(veyron_wire::WireError::Internal(format!(
                "encode register: {e}"
            )))
        })?;
        // register frame is never mac'd (the key doesn't exist yet)
        let mut ws = ws;
        ws.send(WsMessage::Binary(
            frame_to_bytes(&build_frame("kernel", 0, payload)).into(),
        ))
        .await
        .map_err(AgentError::from)?;

        let session_key = await_ack(&mut ws, params, &plugin_id).await?;

        let (write, read) = ws.split();
        Ok(CapConn {
            read,
            write,
            session_key,
        })
    }

    /// Split into the stream halves for concurrent read/write loops. The
    /// session key is Copy, so both halves can verify/arm.
    pub fn into_parts(
        self,
    ) -> (
        SplitStream<WsStream>,
        SplitSink<WsStream, WsMessage>,
        Option<[u8; 32]>,
    ) {
        (self.read, self.write, self.session_key)
    }

    pub fn session_key(&self) -> Option<[u8; 32]> {
        self.session_key
    }
}

/// Read frames until the register ack; arm the session key from its nonce.
async fn await_ack(
    ws: &mut WsStream,
    params: &RegisterParams,
    plugin_id: &str,
) -> Result<Option<[u8; 32]>, AgentError> {
    loop {
        let frame = match tokio::time::timeout(REGISTER_ACK_TIMEOUT, ws.next()).await {
            Err(_elapsed) => {
                return Err(AgentError::Connect(format!(
                    "no register ack within {}s",
                    REGISTER_ACK_TIMEOUT.as_secs()
                )));
            }
            Ok(Some(Ok(WsMessage::Binary(data)))) => parse_frame(&data)?,
            Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => {
                return Err(AgentError::Ws("websocket closed during register".into()));
            }
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(e))) => return Err(AgentError::from(e)),
        };
        let env = Envelope::decode(frame.payload.as_ref()).map_err(|e| {
            AgentError::Wire(veyron_wire::WireError::Internal(format!(
                "decode register ack: {e}"
            )))
        })?;
        match env.payload {
            Some(envelope::Payload::PluginRegisterAck(ack)) => {
                return arm_from_ack(ack, params, plugin_id);
            }
            Some(envelope::Payload::Error(err)) => {
                return Err(AgentError::Register(format!(
                    "{}: {}",
                    err.message, err.details
                )));
            }
            _ => {
                tracing::warn!(cap = %params.cap, "unexpected frame before register ack");
                continue;
            }
        }
    }
}

fn arm_from_ack(
    ack: PluginRegisterAck,
    params: &RegisterParams,
    plugin_id: &str,
) -> Result<Option<[u8; 32]>, AgentError> {
    if !ack.accepted {
        return Err(AgentError::Register(ack.reject_reason));
    }
    let key = match &params.jwt_secret {
        Some(secret) if !ack.session_nonce.is_empty() => Some(derive_session_key(
            secret.as_bytes(),
            &ack.session_nonce,
            plugin_id,
        )),
        _ => None,
    };
    tracing::info!(plugin_id = %plugin_id, "registered on host");
    Ok(key)
}

/// Build a rustls client config that trusts only the pinned host cert (D-07
/// "pin the exact served cert" rule for local clients, carried to the phone via
/// the pairing QR). Parses one or more PEM certs into a root store.
fn pinned_tls_config(pem: &str) -> Result<rustls::ClientConfig, AgentError> {
    let mut roots = rustls::RootCertStore::empty();
    let mut reader = std::io::Cursor::new(pem.as_bytes());
    for cert in rustls_pemfile::certs(&mut reader) {
        let cert = cert.map_err(|e| AgentError::Connect(format!("bad cert pem: {e}")))?;
        roots
            .add(cert)
            .map_err(|e| AgentError::Connect(format!("unusable cert: {e}")))?;
    }
    if roots.is_empty() {
        return Err(AgentError::Connect(
            "cert_pem contained no certificates".into(),
        ));
    }
    Ok(rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth())
}

/// Map a configured host URL onto a ws(s) endpoint. `http(s)://` becomes
/// `ws(s)://`; a bare origin gains the gateway's `/ws` path (D-06 rule).
fn resolve_ws_url(raw: &str) -> Result<url::Url, AgentError> {
    let s = raw.trim();
    let prefixed = if s.starts_with("ws://") || s.starts_with("wss://") {
        s.to_string()
    } else if let Some(rest) = s.strip_prefix("http://") {
        format!("ws://{rest}")
    } else if let Some(rest) = s.strip_prefix("https://") {
        format!("wss://{rest}")
    } else {
        format!("ws://{s}")
    };
    let mut url = url::Url::parse(&prefixed).map_err(|e| AgentError::Connect(e.to_string()))?;
    if url.path().is_empty() || url.path() == "/" {
        url.set_path("/ws");
    }
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_bare_origin_gets_ws_and_path() {
        let url = resolve_ws_url("localhost:8080").unwrap();
        assert_eq!(url.as_str(), "ws://localhost:8080/ws");
    }

    #[test]
    fn resolve_http_becomes_ws() {
        let url = resolve_ws_url("http://host:8080").unwrap();
        assert_eq!(url.as_str(), "ws://host:8080/ws");
    }

    #[test]
    fn resolve_https_becomes_wss() {
        let url = resolve_ws_url("https://host:443").unwrap();
        // the url crate drops the default 443 port for wss
        assert_eq!(url.as_str(), "wss://host/ws");
    }

    #[test]
    fn resolve_explicit_ws_keeps_path() {
        let url = resolve_ws_url("ws://host:8080/socket").unwrap();
        assert_eq!(url.as_str(), "ws://host:8080/socket");
    }

    #[test]
    fn resolve_wss_keeps_path() {
        let url = resolve_ws_url("wss://host/ws").unwrap();
        assert_eq!(url.as_str(), "wss://host/ws");
    }

    #[test]
    fn register_params_plugin_id_uses_device_id() {
        let p = RegisterParams {
            device_id: "phone-abc".into(),
            cap: "geo".into(),
            jwt_token: String::new(),
            jwt_secret: None,
            cert_pem: None,
            os_version: "14".into(),
            arch: "aarch64".into(),
            user_id: "default".into(),
        };
        assert_eq!(p.plugin_id(), "phone-abc.geo");
    }
}
