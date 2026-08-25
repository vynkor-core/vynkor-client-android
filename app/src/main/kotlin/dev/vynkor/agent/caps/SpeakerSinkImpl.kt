package dev.vynkor.agent.caps

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.vynkor.agent.SpeakerSink

/**
 * Plays decoded PCM (s16le mono 16 kHz) through [AudioTrack].
 *
 * [playPcm] writes with WRITE_NON_BLOCKING in a loop so a shutdown can always
 * win: [release] flips a flag the write loop checks, then stops and releases
 * the track. [release] is idempotent and must be called when the agent stops
 * (AgentService does this off the main thread).
 */
class SpeakerSinkImpl : SpeakerSink {
    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var released = false

    override fun playPcm(pcm: ByteArray) {
        if (released || pcm.isEmpty()) return
        val t = track ?: createTrack().also { track = it }
        var offset = 0
        while (offset < pcm.size && !released) {
            val written = t.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write failed ($written), dropping ${pcm.size - offset} bytes")
                return
            }
            if (written == 0) {
                // Output buffer full; brief park instead of busy-spinning.
                Thread.sleep(BUFFER_FULL_BACKOFF_MS)
            } else {
                offset += written
            }
        }
    }

    fun release() {
        released = true
        track?.let { t ->
            try {
                t.pause()
                t.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause/flush during release failed", e)
            }
            t.release()
        }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, MIN_BUFFER_BYTES))
            .build()
        t.play()
        return t
    }

    companion object {
        private const val TAG = "SpeakerSinkImpl"
        private const val SAMPLE_RATE = 16_000
        private const val MIN_BUFFER_BYTES = 16_000 // 0.5 s of mono s16le
        private const val BUFFER_FULL_BACKOFF_MS = 10L
    }
}
