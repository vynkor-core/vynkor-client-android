//! Frame build/parse, MAC arm/verify, and kernel-routed classification.
//!
//! Reuses `vynkor_wire` verbatim for the wire types. WS byte handling is
//! manual (`serialize_header` + payload + optional tag) — `write_frame_raw`
//! would auto-zstd payloads ≥64 KiB, which the WS gateway rejects (R5-03).

use prost::Message;
use vynkor_wire::framing::{serialize_header, FLAG_MAC_PRESENT, FLAG_RAW_BINARY, MAX_PAYLOAD_SIZE};
use vynkor_wire::mac::{compute_tag, verify_tag};
use vynkor_wire::proto::vynkor::{envelope, Envelope};

use crate::error::AgentError;

pub use vynkor_wire::framing::Frame;

pub const MAGIC: u16 = 0x5652;
pub const HEADER_SIZE: usize = 44;
pub const MAC_TAG_LEN: usize = 32;

/// Build a frame with payload crc. Target is truncated/padded to 32 bytes.
pub fn build_frame(target: &str, flags: u16, payload: Vec<u8>) -> Frame {
    let mut t = [0u8; 32];
    let bytes = target.as_bytes();
    let n = bytes.len().min(32);
    t[..n].copy_from_slice(&bytes[..n]);
    Frame {
        magic: MAGIC,
        flags,
        length: payload.len() as u32,
        target: t,
        crc32: crc32fast::hash(&payload),
        payload: payload.into(),
        mac: None,
    }
}

/// Serialize a frame as one WS binary message: header + payload + tag.
pub fn frame_to_bytes(frame: &Frame) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_SIZE + frame.payload.len() + MAC_TAG_LEN);
    out.extend_from_slice(&serialize_header(frame));
    out.extend_from_slice(&frame.payload);
    if let Some(tag) = &frame.mac {
        out.extend_from_slice(tag);
    }
    out
}

/// Parse a WS binary message back into a frame. Verifies magic + length only;
/// crc/MAC verification is the caller's job (`verify_inbound`).
pub fn parse_frame(bytes: &[u8]) -> Result<Frame, AgentError> {
    if bytes.len() < HEADER_SIZE {
        return Err(AgentError::Wire(vynkor_wire::WireError::Internal(
            "frame shorter than header".into(),
        )));
    }
    let magic = u16::from_be_bytes([bytes[0], bytes[1]]);
    if magic != MAGIC {
        return Err(AgentError::Wire(vynkor_wire::WireError::FrameMagicMismatch));
    }
    let flags = u16::from_be_bytes([bytes[2], bytes[3]]);
    let length = u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    let mut target = [0u8; 32];
    target.copy_from_slice(&bytes[8..40]);
    let crc32 = u32::from_be_bytes([bytes[40], bytes[41], bytes[42], bytes[43]]);

    let mac = if flags & FLAG_MAC_PRESENT != 0 {
        if bytes.len() < HEADER_SIZE + MAC_TAG_LEN {
            return Err(AgentError::Wire(vynkor_wire::WireError::Internal(
                "mac'd frame shorter than header+tag".into(),
            )));
        }
        let mut tag = [0u8; MAC_TAG_LEN];
        let end = HEADER_SIZE + length as usize;
        if end + MAC_TAG_LEN > bytes.len() {
            return Err(AgentError::Wire(vynkor_wire::WireError::Internal(
                "mac'd frame payload overruns message".into(),
            )));
        }
        tag.copy_from_slice(&bytes[end..end + MAC_TAG_LEN]);
        Some(tag)
    } else {
        None
    };

    let end = HEADER_SIZE + length as usize;
    if end > bytes.len() {
        return Err(AgentError::Wire(vynkor_wire::WireError::Internal(
            "frame payload overruns message".into(),
        )));
    }
    Ok(Frame {
        magic,
        flags,
        length,
        target,
        crc32,
        payload: bytes[HEADER_SIZE..end].to_vec().into(),
        mac,
    })
}

/// Verify crc + mac of an inbound frame and return a frame with the mac
/// stripped and `FLAG_MAC_PRESENT` cleared (the tag covered the header with
/// the flag set, so verification happens before stripping).
pub fn verify_inbound(frame: &mut Frame, key: Option<&[u8; 32]>) -> Result<(), AgentError> {
    let computed_crc = crc32fast::hash(&frame.payload);
    if computed_crc != frame.crc32 {
        return Err(AgentError::Wire(vynkor_wire::WireError::FrameCrcMismatch));
    }
    if frame.flags & FLAG_MAC_PRESENT != 0 {
        let key = key.ok_or_else(|| {
            AgentError::Wire(vynkor_wire::WireError::Internal(
                "mac'd frame before session key armed".into(),
            ))
        })?;
        let header = serialize_header(frame);
        let tag = frame.mac.ok_or_else(|| {
            AgentError::Wire(vynkor_wire::WireError::Internal(
                "mac flag set without tag".into(),
            ))
        })?;
        if !verify_tag(key, &header, &frame.payload, &tag) {
            return Err(AgentError::Wire(vynkor_wire::WireError::Internal(
                "frame mac invalid".into(),
            )));
        }
        frame.flags &= !FLAG_MAC_PRESENT;
        frame.mac = None;
    }
    Ok(())
}

/// Set `FLAG_MAC_PRESENT` and compute the tag over header+payload (header is
/// serialized with the flag already set — the wire contract).
pub fn arm_mac(frame: &mut Frame, key: &[u8; 32]) {
    frame.flags |= FLAG_MAC_PRESENT;
    let header = serialize_header(frame);
    frame.mac = Some(compute_tag(key, &header, &frame.payload));
}

/// Payload classes the host kernel routes to "kernel" (everything else is
/// device traffic). Mirrors the bridge's `is_kernel_routed` — a raw-binary
/// or undecodable payload is device traffic.
pub fn is_kernel_routed(frame: &Frame) -> bool {
    if frame.flags & FLAG_RAW_BINARY != 0 {
        return false;
    }
    let Ok(env) = Envelope::decode(frame.payload.as_ref()) else {
        return false;
    };
    matches!(
        env.payload,
        Some(envelope::Payload::ActionRequest(_))
            | Some(envelope::Payload::ActionRequestChunk(_))
            | Some(envelope::Payload::ActionResponse(_))
            | Some(envelope::Payload::ActionResponseChunk(_))
            | Some(envelope::Payload::SessionClose(_))
            | Some(envelope::Payload::ActionStreamAbort(_))
            | Some(envelope::Payload::Ping(_))
            | Some(envelope::Payload::Pong(_))
            | Some(envelope::Payload::Error(_))
            | Some(envelope::Payload::EventPublishAck(_))
            | Some(envelope::Payload::KernelCommandAck(_))
            | Some(envelope::Payload::EventAck(_))
            | Some(envelope::Payload::PluginRegisterAck(_))
    )
}

/// Format a frame's 32-byte target as a string (empty when not UTF-8).
pub fn target_str(frame: &Frame) -> String {
    let end = frame.target.iter().position(|&b| b == 0).unwrap_or(32);
    String::from_utf8_lossy(&frame.target[..end]).into_owned()
}

/// Reject oversized payloads before they hit the wire (WS has no 1 MiB frame
/// guard of its own beyond the gateway's own limit).
pub fn check_payload_size(payload: &[u8]) -> Result<(), AgentError> {
    if payload.len() > MAX_PAYLOAD_SIZE {
        return Err(AgentError::Wire(vynkor_wire::WireError::PayloadTooLarge(
            payload.len(),
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    #[test]
    fn frame_roundtrip_via_ws_bytes() {
        let frame = build_frame("device-1.geo", FLAG_RAW_BINARY, vec![1, 2, 3, 4]);
        let bytes = frame_to_bytes(&frame);
        let parsed = parse_frame(&bytes).unwrap();
        assert_eq!(parsed.magic, MAGIC);
        assert_eq!(parsed.flags, FLAG_RAW_BINARY);
        assert_eq!(parsed.length, 4);
        assert_eq!(parsed.target, frame.target);
        assert_eq!(parsed.crc32, frame.crc32);
        assert_eq!(parsed.payload.as_ref(), &[1, 2, 3, 4]);
        assert!(parsed.mac.is_none());
    }

    #[test]
    fn parse_frame_rejects_bad_magic() {
        let mut bytes = frame_to_bytes(&build_frame("kernel", 0, vec![0]));
        bytes[0] = 0x00;
        bytes[1] = 0x01;
        assert!(matches!(
            parse_frame(&bytes),
            Err(AgentError::Wire(vynkor_wire::WireError::FrameMagicMismatch))
        ));
    }

    #[test]
    fn parse_frame_rejects_short_message() {
        assert!(parse_frame(&[0u8; 10]).is_err());
    }

    #[test]
    fn mac_arm_and_verify_roundtrip() {
        let key = [7u8; 32];
        let mut frame = build_frame("device-1.battery", 0, b"payload".to_vec());
        arm_mac(&mut frame, &key);
        assert_eq!(frame.flags & FLAG_MAC_PRESENT, FLAG_MAC_PRESENT);
        assert!(frame.mac.is_some());

        let bytes = frame_to_bytes(&frame);
        let mut parsed = parse_frame(&bytes).unwrap();
        verify_inbound(&mut parsed, Some(&key)).unwrap();
        assert_eq!(parsed.flags & FLAG_MAC_PRESENT, 0);
        assert!(parsed.mac.is_none());
        assert_eq!(parsed.payload.as_ref(), b"payload");
    }

    #[test]
    fn verify_rejects_tampered_payload() {
        let key = [7u8; 32];
        let mut frame = build_frame("kernel", 0, b"payload".to_vec());
        arm_mac(&mut frame, &key);
        let mut bytes = frame_to_bytes(&frame);
        bytes[60] ^= 0xff; // inside the payload
        let mut parsed = parse_frame(&bytes).unwrap();
        assert!(verify_inbound(&mut parsed, Some(&key)).is_err());
    }

    #[test]
    fn verify_rejects_crc_mismatch() {
        let mut frame = build_frame("kernel", 0, b"payload".to_vec());
        frame.crc32 ^= 0xdead_beef;
        assert!(verify_inbound(&mut frame, None).is_err());
    }

    #[test]
    fn mac_verify_without_key_fails() {
        let key = [7u8; 32];
        let mut frame = build_frame("kernel", 0, b"x".to_vec());
        arm_mac(&mut frame, &key);
        let bytes = frame_to_bytes(&frame);
        let mut parsed = parse_frame(&bytes).unwrap();
        assert!(verify_inbound(&mut parsed, None).is_err());
    }

    #[test]
    fn kernel_routed_classifies_envelopes() {
        let action = Envelope {
            payload: Some(envelope::Payload::ActionRequest(
                vynkor_wire::proto::vynkor::ActionRequest::default(),
            )),
            ..Default::default()
        };
        let mut payload = Vec::new();
        action.encode(&mut payload).unwrap();
        let frame = build_frame("kernel", 0, payload);
        assert!(is_kernel_routed(&frame));

        let ping = Envelope {
            payload: Some(envelope::Payload::Ping(vynkor_wire::proto::vynkor::Ping {
                timestamp: 1,
            })),
            ..Default::default()
        };
        let mut payload = Vec::new();
        ping.encode(&mut payload).unwrap();
        assert!(is_kernel_routed(&build_frame("kernel", 0, payload)));

        // raw-binary audio is device traffic regardless of payload
        let mut payload = Vec::new();
        action.encode(&mut payload).unwrap();
        let raw = build_frame("device-1.speaker", FLAG_RAW_BINARY, payload);
        assert!(!is_kernel_routed(&raw));

        // garbage payload -> device traffic
        assert!(!is_kernel_routed(&build_frame(
            "kernel",
            0,
            vec![0xff, 0xfe]
        )));
    }

    #[test]
    fn target_str_handles_padding() {
        let frame = build_frame("device-1.geo", 0, vec![]);
        assert_eq!(target_str(&frame), "device-1.geo");
    }

    #[test]
    fn check_payload_size_bounds() {
        assert!(check_payload_size(&[0u8; 100]).is_ok());
        assert!(check_payload_size(&[0u8; MAX_PAYLOAD_SIZE]).is_ok());
        assert!(check_payload_size(&vec![0u8; MAX_PAYLOAD_SIZE + 1]).is_err());
    }

    #[test]
    fn build_frame_truncates_long_target() {
        let long = "a".repeat(64);
        let frame = build_frame(&long, 0, vec![]);
        assert_eq!(frame.target.len(), 32);
        assert_eq!(&frame.target[..], &long.as_bytes()[..32]);
    }
}
