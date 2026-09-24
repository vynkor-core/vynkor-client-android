//! Raw-binary audio path (FLAG_RAW_BINARY frames): host TTS → speaker sink,
//! and the mic → host STT direction (push_mic_pcm on the Agent).

use prost::Message;
use vynkor_wire::proto::vynkor::{envelope, AudioCodec, AudioStreamChunk, Envelope};

use crate::agent::Agent;

/// Host→device raw-binary frame: unwrap the AudioStreamChunk and play it.
///
/// Runs on a blocking thread (opus decode + ring push).
pub fn handle_raw_inbound(agent: &Agent, payload: &[u8], cap: &str) {
    if cap != "speaker" {
        tracing::warn!(cap, "raw audio to non-speaker capability, dropping");
        return;
    }
    let Ok(env) = Envelope::decode(payload) else {
        tracing::warn!("speaker: undecodable raw frame, dropping");
        return;
    };
    let Some(envelope::Payload::AudioStreamChunk(chunk)) = env.payload else {
        tracing::warn!("speaker: raw frame is not an AudioStreamChunk, dropping");
        return;
    };
    handle_chunk(agent, chunk);
}

/// One speaker chunk, from either delivery path (raw-binary frame or a
/// kernel-routed envelope): PCM_S16LE passes through, OPUS is decoded with
/// the per-stream cached decoder; the PCM lands in the ring the Kotlin
/// AudioTrack thread drains.
pub fn handle_chunk(agent: &Agent, chunk: AudioStreamChunk) {
    if agent.speaker_provider().is_none() {
        tracing::warn!("speaker: no sink registered, dropping audio");
        return;
    }
    tracing::trace!(
        codec = %audio_codec_name(chunk.codec),
        sample_rate = chunk.sample_rate,
        data_len = chunk.data.len(),
        eos = chunk.end_of_stream,
        "speaker: chunk"
    );
    match chunk.codec {
        c if c == AudioCodec::PcmS16le as i32 => {
            agent.speaker_push_pcm(chunk.data, chunk.sample_rate, chunk.end_of_stream);
        }
        c if c == AudioCodec::Opus as i32 => {
            match agent.decode_opus_cached(chunk.stream_id, &chunk.data, chunk.sample_rate) {
                Ok(pcm) => {
                    agent.speaker_push_pcm(pcm, chunk.sample_rate, chunk.end_of_stream);
                }
                Err(e) => tracing::warn!(error = %e, "speaker: opus decode failed"),
            }
            if chunk.end_of_stream {
                agent.drop_opus_decoder(chunk.stream_id);
            }
        }
        c => tracing::warn!(codec = %audio_codec_name(c), "speaker: unsupported codec, dropping"),
    }
}

fn audio_codec_name(codec: i32) -> &'static str {
    match codec {
        c if c == AudioCodec::PcmS16le as i32 => "pcm_s16le",
        c if c == AudioCodec::Opus as i32 => "opus",
        _ => "unknown",
    }
}
