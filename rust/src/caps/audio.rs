//! Raw-binary audio path (FLAG_RAW_BINARY frames): host TTS → speaker sink,
//! and the mic → host STT direction (push_mic_pcm on the Agent).

use prost::Message;
use vynkor_wire::proto::vynkor::{envelope, AudioCodec, Envelope};

use crate::agent::Agent;

/// Host→device: decode an AudioStreamChunk and hand the PCM to the speaker
/// sink. v1 supports PCM_S16LE passthrough; OPUS decode is a follow-up (the
/// design defers the codec crate choice).
///
/// Runs on a blocking thread: `play_pcm` may park on a full AudioTrack buffer.
pub fn handle_raw_inbound(agent: &Agent, payload: &[u8], cap: &str) {
    if cap != "speaker" {
        tracing::warn!(cap, "raw audio to non-speaker capability, dropping");
        return;
    }
    let Some(sink) = agent.speaker_provider() else {
        tracing::warn!("speaker: no sink registered, dropping audio");
        return;
    };
    let Ok(env) = Envelope::decode(payload) else {
        tracing::warn!("speaker: undecodable raw frame, dropping");
        return;
    };
    let Some(envelope::Payload::AudioStreamChunk(chunk)) = env.payload else {
        tracing::warn!("speaker: raw frame is not an AudioStreamChunk, dropping");
        return;
    };
    tracing::error!(
        codec = %audio_codec_name(chunk.codec),
        sample_rate = chunk.sample_rate,
        data_len = chunk.data.len(),
        "speaker: received chunk"
    );
    match chunk.codec {
        c if c == AudioCodec::PcmS16le as i32 => {
            agent.speaker_push_pcm(chunk.data, chunk.sample_rate, chunk.end_of_stream);
        }
        c if c == AudioCodec::Opus as i32 => {
            tracing::error!(data_len = chunk.data.len(), sample_rate = chunk.sample_rate, "speaker: opus chunk, decoding");
            match agent.decode_opus_cached(chunk.stream_id, &chunk.data, chunk.sample_rate) {
                Ok(pcm) => {
                    tracing::error!(pcm_len = pcm.len(), "speaker: opus decoded, pushing to rtrb");
                    let is_eos = chunk.end_of_stream;
                    let sr = chunk.sample_rate;
                    let res = agent.speaker_push_pcm(pcm, sr, is_eos);
                    if is_eos {
                        agent.opus_decoders.lock().unwrap().remove(&chunk.stream_id);
                    }
                    let _ = res;
                },
                Err(e) => tracing::error!(error = %e, "speaker: opus decode failed"),
            }
        }
        c => tracing::warn!(codec = %audio_codec_name(c), "speaker: unsupported codec, dropping"),
    }
}

pub fn decode_opus_to_pcm(data: &[u8], sample_rate: u32) -> Result<Vec<u8>, String> {
    let sr = if sample_rate == 0 { 24000 } else { sample_rate };
    let mut decoder =
        opus::Decoder::new(sr, opus::Channels::Mono).map_err(|e| format!("opus init: {e}"))?;
    let mut pcm = vec![0i16; 5760];
    let samples = decoder
        .decode(data, &mut pcm, false)
        .map_err(|e| format!("opus decode: {e}"))?;
    pcm.truncate(samples);
    let mut out = Vec::with_capacity(samples * 2);
    for s in pcm {
        out.extend_from_slice(&s.to_le_bytes());
    }
    Ok(out)
}

fn audio_codec_name(codec: i32) -> &'static str {
    match codec {
        c if c == AudioCodec::PcmS16le as i32 => "pcm_s16le",
        c if c == AudioCodec::Opus as i32 => "opus",
        _ => "unknown",
    }
}
