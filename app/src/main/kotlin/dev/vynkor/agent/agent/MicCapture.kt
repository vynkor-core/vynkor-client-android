package dev.vynkor.agent.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Agent
import kotlin.concurrent.thread

/**
 * Streams mic PCM (16 kHz mono s16le) to the agent for host STT.
 *
 * Lifecycle contract: [start]/[stop] are serialized on a monitor, so a stop
 * can never kill a session started after the stop was requested. [stop]
 * unblocks the reader's blocking [AudioRecord.read] by stopping the record
 * from the calling thread *before* joining; the reader thread owns
 * `release()` in its finally block, so the record is never released while a
 * read is in flight.
 */
class MicCapture {
    /** Notified on every chunk handed to the agent (reader thread). Used by
     *  [MicSessionController] to track session activity for its idle timeout. */
    @Volatile
    var onChunkPushed: (() -> Unit)? = null

    private val mutex = Any()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun isRunning(): Boolean = running

    fun start(agent: Agent, context: Context) {
        synchronized(mutex) {
            if (running) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 16_000))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                rec.release()
                return
            }
            running = true
            record = rec
            // The reader captures `rec` locally and owns its stop()+release().
            thread = thread(name = "vynkor-mic") { readLoop(agent, rec) }
        }
    }

    /**
     * Stops the stream. Safe to call when not running. Blocks until the
     * reader thread has exited (bounded); call off the main thread — see
     * [AgentService], which dispatches this to a background executor.
     */
    fun stop() {
        synchronized(mutex) {
            running = false
            // Stopping the record from this thread makes any blocking read()
            // return immediately — this is what lets join() succeed fast.
            try {
                val rec = record
                if (rec != null && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "record.stop() during shutdown failed", e)
            }
            val t = thread ?: return
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (t.isAlive) {
                Log.e(TAG, "mic reader did not exit within ${JOIN_TIMEOUT_MS}ms")
            }
            thread = null
            record = null // released by the reader's finally block
        }
    }

    private fun readLoop(agent: Agent, rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        rec.startRecording()
        val buf = ByteArray(640) // 20 ms at 16 kHz
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                onChunkPushed?.invoke()
                agent.pushMicPcm(buf.copyOf(n))
            }
        } finally {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "record.stop() failed", e)
            }
            rec.release()
        }
    }

    companion object {
        private const val TAG = "MicCapture"
        private const val JOIN_TIMEOUT_MS = 2000L
    }
}
