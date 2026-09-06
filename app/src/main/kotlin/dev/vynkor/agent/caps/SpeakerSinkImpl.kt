package dev.vynkor.agent.caps

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.vynkor.agent.Agent
import dev.vynkor.agent.SpeakerSink
import java.util.concurrent.atomic.AtomicLong

class SpeakerSinkImpl : SpeakerSink {
    @Volatile
    private var released = false

    private val workerThread = HandlerThread("SpeakerSinkWorker-rtrb").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    @Volatile
    private var track: AudioTrack? = null
    @Volatile
    private var playing: Boolean = false
    private var bytesWritten: Long = 0L
    private var playbackStartMs: Long = 0L
    private var startHead: Int = 0
    @Volatile
    private var agentRef: Agent? = null
    private var currentRate: Int = SAMPLE_RATE

    fun attachAgent(agent: Agent) {
        agentRef = agent
        Log.i(TAG, "attached to Agent rtrb")
    }

    override fun appendPcm(pcm: ByteArray, endOfStream: Boolean) {
        val ag = agentRef
        if (ag != null) {
            ag.speakerPushPcm(pcm, 24000u, endOfStream)
            return
        }
        Log.w(TAG, "appendPcm without agent, dropping ${pcm.size}")
    }

    override fun flush() {
        agentRef?.let { it.speakerPushPcm(ByteArray(0), it.speakerSampleRateGet(), true) }
    }

    fun release() {
        released = true
        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            try { track?.stop() } catch (_: IllegalStateException) {}
            try { track?.release() } catch (_: IllegalStateException) {}
            track = null
        }
        workerThread.quitSafely()
    }

    init {
        workerHandler.post(::pollingLoop)
    }

    private fun pollingLoop() {
        while (!released) {
            val ag = agentRef
            if (ag == null) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                continue
            }
            val sr = ag.speakerSampleRateGet().toInt().let { if (it == 0) SAMPLE_RATE else it }
            val t = ensureTrack(sr)
            val pending = ag.speakerPendingBytesGet()
            if (!playing) {
                val shouldStart = pending >= PREFILL_BYTES.toULong() || (ag.speakerEosGet() && pending > 0uL)
                if (!shouldStart) {
                    try { Thread.sleep(IDLE_SLEEP_MS) } catch (_: InterruptedException) { break }
                    continue
                }
                try {
                    t.play()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "play() failed", e)
                    try { t.release() } catch (_: IllegalStateException) {}
                    track = null
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                playing = true
                bytesWritten = 0L
                playbackStartMs = System.currentTimeMillis()
                startHead = try { t.playbackHeadPosition } catch (_: Exception) { 0 }
                Log.i(TAG, "rtrb playback started, buffered=$pending prefill=$PREFILL_BYTES startHead=$startHead")
            }
            val chunk = try { ag.speakerPopPcm(MIN_WRITE_BYTES.toULong()).toByteArray() } catch (e: Exception) { Log.w(TAG, "pop failed", e); ByteArray(0) }
            if (chunk.isEmpty()) {
                if (ag.speakerEosGet()) {
                    val rem = ag.speakerPendingBytesGet()
                    if (rem == 0uL) {
                        waitForDrain(t)
                        val dur = System.currentTimeMillis() - playbackStartMs
                        Log.i(TAG, "rtrb playback complete, $bytesWritten bytes over ${dur}ms")
                        playing = false
                        bytesWritten = 0L
                        try { ag.speakerClear() } catch (_: Exception) {}
                        continue
                    }
                }
                try { Thread.sleep(IDLE_SLEEP_MS) } catch (_: InterruptedException) { break }
                continue
            }
            var off = 0
            while (off < chunk.size) {
                val w = try { t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING) } catch (e: IllegalStateException) { Log.w(TAG, "write IllegalState", e); try { t.release() } catch (_: IllegalStateException) {}; track = null; playing = false; -1 }
                if (w < 0) break
                off += w
            }
            bytesWritten += chunk.size
            Log.i(TAG, "rtrb wrote ${chunk.size} pending=${ag.speakerPendingBytesGet()} eos=${ag.speakerEosGet()}")
        }
    }

    private fun waitForDrain(t: AudioTrack) {
        val rate = currentRate
        val framesWritten = bytesWritten / 2
        val durMs = (framesWritten * 1000L) / rate
        val deadline = System.currentTimeMillis() + durMs + 1200
        while (System.currentTimeMillis() < deadline) {
            val pos = try { t.playbackHeadPosition } catch (_: IllegalStateException) { return }
            val delta = pos - startHead
            if (delta >= 0 && delta >= framesWritten) {
                Log.i(TAG, "rtrb drain ok: head=$pos startHead=$startHead delta=$delta written=$framesWritten rate=$rate")
                return
            }
            if (delta < 0) {
                Log.w(TAG, "rtrb head wrapped: pos=$pos startHead=$startHead")
                return
            }
            try { Thread.sleep(30) } catch (_: InterruptedException) { return }
        }
        val pos = try { t.playbackHeadPosition } catch (_: Exception) { -1 }
        Log.w(TAG, "rtrb drain timeout: $bytesWritten bytes frames=$framesWritten head=$pos startHead=$startHead rate=$rate")
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let {
            if (currentRate == sampleRate) return it
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
            track = null
            Log.i(TAG, "sample rate changed, recreating track $currentRate -> $sampleRate")
        }
        currentRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 4, PREFILL_BYTES * 2 + 8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()
        t.setVolume(AudioTrack.getMaxVolume())
        track = t
        Log.i(TAG, "rtrb AudioTrack created: sr=$sampleRate minBuf=$minBuf buf=$bufSize")
        return t
    }

    private fun ByteArray.toByteArray(): ByteArray = this

    companion object {
        private const val TAG = "SpeakerSinkImpl-rtrb"
        private const val SAMPLE_RATE = 24_000
        private const val PREFILL_BYTES = 24_000 * 2 * 2
        private const val MIN_WRITE_BYTES = 8192
        private const val IDLE_SLEEP_MS = 10L
    }
}
