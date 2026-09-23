//! WS transport to the host kernel — one connection per device (multiplexed).
//!
//! Mirrors the D-06 bridge handshake: JWT in `Sec-WebSocket-Protocol`,
//! register → ack → `derive_session_key` → `FLAG_MAC_PRESENT` on every
//! subsequent frame. The connection splits into read/write halves so the
//! inbound dispatch loop and the outbound push-path drain run concurrently.
//! Single WS per device_id multiplexes all capabilities by target suffix.

use std::time::Duration;

use futures_util::stream::{SplitSink, SplitStream};
use futures_util::{SinkExt, StreamExt};
use prost::Message;
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::{connect_async, MaybeTlsStream, WebSocketStream};
use vynkor_wire::mac::derive_session_key;
use vynkor_wire::proto::vynkor::{envelope, DeviceOs, Envelope, PluginRegister, PluginRegisterAck};
use vynkor_wire::PROTOCOL_VERSION;

use crate::error::AgentError;
use crate::protocol::{build_frame, frame_to_bytes, parse_frame};

pub const BACKOFF_INITIAL: Duration = Duration::from_secs(1);
pub const BACKOFF_MAX: Duration = Duration::from_secs(30);

/// R-06: a host that accepts the handshake but never acks the register must
/// not wedge the loop forever on `ws.next()`.
pub const REGISTER_ACK_TIMEOUT: Duration = Duration::from_secs(15);

/// Bound on TCP connect + TLS + WS upgrade. Without it an unroutable address
/// (stale LAN IP, other subnet) or a socket silently blocked by the OS — on
/// Android 17 a missing local-network grant surfaces as exactly such a hang —
/// parks the loop for the kernel's full SYN retry budget (~2 min) with no
/// error for the UI to show.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

type WsStream = WebSocketStream<MaybeTlsStream<TcpStream>>;

/// Registration parameters for the single device connection.
#[derive(Debug, Clone)]
pub struct RegisterParams {
    pub device_id: String,
    pub caps: Vec<String>,
    pub jwt_token: String,
    /// per-device secret issued by the host (E-01); None = no MAC
    pub device_secret: Option<String>,
    /// Host's served TLS cert (PEM) to pin on `wss://` (self-signed). `None` =
    /// webpki-roots verification.
    pub cert_pem: Option<String>,
    pub os_version: String,
    pub arch: String,
    pub user_id: String,
}

impl RegisterParams {
    pub fn plugin_id(&self) -> String {
        self.device_id.clone()
    }
    pub fn caps(&self) -> &[String] {
        &self.caps
    }
}

/// One live WS connection to the host, split after registration. Multiplexes
/// all capabilities over a single socket with one session key derived from
/// device_id.
pub struct DeviceConn {
    read: SplitStream<WsStream>,
    write: SplitSink<WsStream, WsMessage>,
    session_key: Option<[u8; 32]>,
    pub caps: Vec<String>,
}

/// Backwards compat alias: old per-cap name now points to DeviceConn.
pub type CapConn = DeviceConn;

impl DeviceConn {
    /// Connect + register the device with all capabilities at once. Returns
    /// only once the host acked.
    pub async fn connect_and_register(
        host_url: &str,
        params: &RegisterParams,
    ) -> Result<Self, AgentError> {
        let url = resolve_ws_url(host_url)?;
        let mut req = url
            .as_str()
            .into_client_request()
            .map_err(|e| AgentError::Connect(e.to_string()))?;
        let protocol = if params.jwt_token.is_empty() {
            "vynkor".to_string()
        } else {
            format!("vynkor, {}", params.jwt_token)
        };
        let value =
            HeaderValue::from_str(&protocol).map_err(|e| AgentError::Connect(e.to_string()))?;
        req.headers_mut().insert("sec-websocket-protocol", value);

        let connector = match (url.scheme(), &params.cert_pem) {
            ("wss", Some(pem)) => Some(tokio_tungstenite::Connector::Rustls(
                std::sync::Arc::new(pinned_tls_config(pem)?),
            )),
            _ => None,
        };
        let connect = async {
            match connector {
                Some(c) => {
                    tokio_tungstenite::connect_async_tls_with_config(req, None, false, Some(c))
                        .await
                }
                None => connect_async(req).await,
            }
        };
        let (ws, _resp) = tokio::time::timeout(CONNECT_TIMEOUT, connect)
            .await
            .map_err(|_| {
                AgentError::Connect(format!(
                    "no answer from {} within {}s",
                    host_port(&url),
                    CONNECT_TIMEOUT.as_secs()
                ))
            })?
            .map_err(|e| AgentError::Connect(e.to_string()))?;

        let plugin_id = params.plugin_id();
        let caps = params.caps.clone();
        let reg = PluginRegister {
            plugin_id: plugin_id.clone(),
            version: env!("CARGO_PKG_VERSION").to_string(),
            description: format!(
                "vynkor device-agent device {} caps {}",
                plugin_id,
                caps.join(",")
            ),
            manifest: None,
            jwt_token: params.jwt_token.clone(),
            device_id: params.device_id.clone(),
            os: DeviceOs::Android as i32,
            arch: params.arch.clone(),
            os_version: params.os_version.clone(),
            capabilities: caps.clone(),
            protocol_version: PROTOCOL_VERSION.to_string(),
            user_id: params.user_id.clone(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::PluginRegister(reg)),
            ..Default::default()
        };
        let mut payload = Vec::new();
        env.encode(&mut payload).map_err(|e| {
            AgentError::Wire(vynkor_wire::WireError::Internal(format!(
                "encode register: {e}"
            )))
        })?;
        let mut ws = ws;
        ws.send(WsMessage::Binary(
            frame_to_bytes(&build_frame("kernel", 0, payload)).into(),
        ))
        .await
        .map_err(AgentError::from)?;

        let session_key = await_ack(&mut ws, params, &plugin_id).await?;

        let (write, read) = ws.split();
        Ok(DeviceConn {
            read,
            write,
            session_key,
            caps,
        })
    }

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
            AgentError::Wire(vynkor_wire::WireError::Internal(format!(
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
                tracing::warn!(device_id = %params.device_id, caps = ?params.caps, "unexpected frame before register ack");
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
    let key = match &params.device_secret {
        Some(secret) if !ack.session_nonce.is_empty() => Some(derive_session_key(
            secret.as_bytes(),
            &ack.session_nonce,
            plugin_id,
        )),
        _ => None,
    };
    tracing::info!(plugin_id = %plugin_id, caps = ?params.caps, "registered on host");
    Ok(key)
}

/// TLS config that trusts exactly the certificate(s) delivered in the
/// pairing QR. Trust comes from byte-equality with the pinned cert, not from
/// a CA chain or the hostname: the QR is the trusted channel, and the host's
/// LAN IP is DHCP-assigned — checking the name made every paired phone fail
/// ("certificate not valid for name") as soon as the host's address changed.
/// Handshake signatures are still verified against the pinned key.
fn pinned_tls_config(pem: &str) -> Result<rustls::ClientConfig, AgentError> {
    let mut pinned = Vec::new();
    let mut reader = std::io::Cursor::new(pem.as_bytes());
    for cert in rustls_pemfile::certs(&mut reader) {
        pinned.push(cert.map_err(|e| AgentError::Connect(format!("bad cert pem: {e}")))?);
    }
    if pinned.is_empty() {
        return Err(AgentError::Connect(
            "cert_pem contained no certificates".into(),
        ));
    }
    let provider = rustls::crypto::CryptoProvider::get_default()
        .cloned()
        .unwrap_or_else(|| std::sync::Arc::new(rustls::crypto::aws_lc_rs::default_provider()));
    let verifier = PinnedCertVerifier {
        pinned,
        provider: provider.clone(),
    };
    Ok(rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| AgentError::Connect(format!("tls config: {e}")))?
        .dangerous()
        .with_custom_certificate_verifier(std::sync::Arc::new(verifier))
        .with_no_client_auth())
}

#[derive(Debug)]
struct PinnedCertVerifier {
    pinned: Vec<rustls::pki_types::CertificateDer<'static>>,
    provider: std::sync::Arc<rustls::crypto::CryptoProvider>,
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        if self.pinned.iter().any(|c| c.as_ref() == end_entity.as_ref()) {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::UnknownIssuer,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// `host:port` for error messages (never the path/query).
fn host_port(url: &url::Url) -> String {
    let host = url.host_str().unwrap_or("?");
    match url.port_or_known_default() {
        Some(p) => format!("{host}:{p}"),
        None => host.to_string(),
    }
}

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

    const TEST_CERT_PEM: &str = r#"-----BEGIN CERTIFICATE-----
MIIDDTCCAfWgAwIBAgIUFZlUn8Apfm8fzitpbsD2mtRMzcUwDQYJKoZIhvcNAQEL
BQAwFjEUMBIGA1UEAwwLdnlua29yLXRlc3QwHhcNMjYwODI2MTMxMDQ1WhcNMzYw
ODIzMTMxMDQ1WjAWMRQwEgYDVQQDDAt2eW5rb3ItdGVzdDCCASIwDQYJKoZIhvcN
AQEBBQADggEPADCCAQoCggEBAIX/hLMcQ4d1t4SXebm0PqybWRkp2l7Rog8Gy6bP
QSRj1NN390iPGlZgdr6T788OU4acGwWeuRCORY4xu/bIjgHSK+SKFo0wIdtGsCeX
pLddnQD1q7hOOdJon+yDXeD7AIj2q2vS6bGt/LuCUc01I2irwPI57mb/bzHusu8h
ivCuPPaFwCPmc7GFOIFdwrZj8UCEHU1kJHFV0WSDN+VgFrxUld/wbdeeYU+caBly
jrFMLkW1BVV+m/NsvkijvTb4IcRnbTBJ3M2dbPGWdQVRNQkic5glJq4NDF7x4/iX
IF/R1mye/TPhTxEk5SsTJFQNN4VU6jT9GQdRet2+DNdb91ECAwEAAaNTMFEwHQYD
VR0OBBYEFDFl3+meOpu4+nHXNXbtQRPlDQ+PMB8GA1UdIwQYMBaAFDFl3+meOpu4
+nHXNXbtQRPlDQ+PMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEB
AGB/4erHVxIEUmKkR+rArXS8TlH2xHVr3Rl5CpBoy0CXaCxPbUZUcVYErAUjlvlr
C5bPABxQdmNidpdN+sIjNLnjaDfCjQVIXfueAH9FVtiah3BvUDNQ6b+6+GbEBeGc
/9FdLBdnYu60AI/AoZW7Eo5QV8cNB9r4xRWnmXcXFUG6EoDSlG3G05uj+L913WFM
cAuXnNfq5+uYkdKkwmbrz0P+/0gURET529/ycZCqBEzU2PYI48gOiXDSavMvNh7+
yb1Tnq5tizCER4XqSXqd5jIWj06Iijtt3Yo9WbD36qqOiBcU8cxD+LyRxnIGd+Dd
74J6/N4TKgi8tilSDRAEdJk=
-----END CERTIFICATE-----"#;

    #[test]
    fn pinned_tls_accepts_a_valid_cert_pem() {
        let cfg = pinned_tls_config(TEST_CERT_PEM).unwrap();
        let _ = cfg;
    }

    #[test]
    fn pinned_verifier_accepts_only_the_pinned_cert_under_any_name() {
        use rustls::client::danger::ServerCertVerifier;
        let mut reader = std::io::Cursor::new(TEST_CERT_PEM.as_bytes());
        let cert = rustls_pemfile::certs(&mut reader).next().unwrap().unwrap();
        let v = PinnedCertVerifier {
            pinned: vec![cert.clone()],
            provider: std::sync::Arc::new(rustls::crypto::aws_lc_rs::default_provider()),
        };
        let now = rustls::pki_types::UnixTime::now();
        // A DHCP-changed IP the cert never listed must still verify.
        let ip = rustls::pki_types::ServerName::try_from("192.168.31.189").unwrap();
        assert!(v.verify_server_cert(&cert, &[], &ip, &[], now).is_ok());
        let other = rustls::pki_types::CertificateDer::from(vec![0u8; 16]);
        assert!(v.verify_server_cert(&other, &[], &ip, &[], now).is_err());
    }

    #[test]
    fn pinned_tls_rejects_pem_without_certs() {
        let err = pinned_tls_config("not a pem at all").unwrap_err();
        assert!(err.to_string().contains("no certificates"));
    }

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
    fn host_port_uses_scheme_default() {
        let url = resolve_ws_url("wss://host/ws").unwrap();
        assert_eq!(host_port(&url), "host:443");
        let url = resolve_ws_url("ws://10.0.0.2:8888/ws").unwrap();
        assert_eq!(host_port(&url), "10.0.0.2:8888");
    }

    /// A blackholed address must fail within CONNECT_TIMEOUT instead of
    /// hanging for the OS SYN-retry budget. 10.255.255.1 is unroutable in
    /// practice; either a fast OS error or our timeout is acceptable — only
    /// a hang past the bound is a failure.
    #[tokio::test]
    async fn connect_to_blackhole_is_bounded() {
        let p = RegisterParams {
            device_id: "d".into(),
            caps: vec![],
            jwt_token: String::new(),
            device_secret: None,
            cert_pem: None,
            os_version: "17".into(),
            arch: "aarch64".into(),
            user_id: "default".into(),
        };
        let started = std::time::Instant::now();
        let res = DeviceConn::connect_and_register("ws://10.255.255.1:9/ws", &p).await;
        assert!(res.is_err());
        assert!(started.elapsed() < CONNECT_TIMEOUT + Duration::from_secs(2));
    }

    #[test]
    fn register_params_plugin_id_is_device_id() {
        let p = RegisterParams {
            device_id: "phone-abc".into(),
            caps: vec!["geo".into(), "battery".into()],
            jwt_token: String::new(),
            device_secret: None,
            cert_pem: None,
            os_version: "14".into(),
            arch: "aarch64".into(),
            user_id: "default".into(),
        };
        assert_eq!(p.plugin_id(), "phone-abc");
        assert_eq!(p.caps, vec!["geo", "battery"]);
    }

    #[test]
    fn device_conn_single_ws_carry_all_caps() {
        let p = RegisterParams {
            device_id: "dev-xxx".into(),
            caps: vec!["battery".into(), "geo".into(), "clipboard".into()],
            jwt_token: String::new(),
            device_secret: None,
            cert_pem: None,
            os_version: "14".into(),
            arch: "aarch64".into(),
            user_id: "default".into(),
        };
        assert_eq!(p.caps.len(), 3);
        assert_eq!(p.plugin_id(), "dev-xxx");
    }
}
