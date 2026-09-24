package dev.vynkor.agent.agent

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.ArrayList

/**
 * Minimal 16 kHz mono PCM recorder for local dictation. [start] opens an
 * [AudioRecord] and drains it on a background thread into a growable buffer;
 * [stop] stops, releases and returns the recorded samples as a FloatArray
 * in [-1, 1]. Tolerates [stop] when not recording.
 */
class SttRecorder {

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var onChunk: ((FloatArray) -> Unit)? = null

    // Growable primitive buffer (R-11: no boxed Short per sample). Guarded by
    // [bufferLock]; reset on start, drained by [snapshotAndClear].
    private val bufferLock = Any()
    private var buffer = ShortArray(BUFFER_INITIAL)
    private var bufferSize = 0

    fun isRecording(): Boolean = recording

    /**
     * Starts recording. Every read chunk is converted to a FloatArray in
     * [-1, 1] and passed to [onChunk] on the recorder thread (used by the
     * streaming STT path); all samples are still buffered so [stop] can
     * return the full recording.
     */
    fun start(onChunk: ((FloatArray) -> Unit)? = null) {
        if (recording) return
        this.onChunk = onChunk
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recordBytes = maxOf(minBuf * 2, 8192)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(recordBytes)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }
        synchronized(bufferLock) { bufferSize = 0 }
        audioRecord = record
        recording = true
        recordThread = Thread({ readLoop(record) }, "stt-recorder").apply { start() }
    }

    /**
     * Stops and releases the recorder. Returns the recorded s16le samples
     * converted to FloatArray in [-1, 1]. Returns an empty array when the
     * recorder was not running.
     */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        onChunk = null
        val record = audioRecord ?: return FloatArray(0)
        // Stop the record from THIS thread first: that unblocks the reader's
        // blocking read(), so join() returns promptly and release() below can
        // never race an in-flight read (releasing mid-read is UB / native crash).
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "record.stop() during shutdown failed", e)
        }
        val thread = recordThread
        if (thread != null) {
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                // Pathological: reader still inside read(). Leaking the record
                // beats releasing it under a live native reader.
                Log.e(TAG, "stt reader did not exit; leaking AudioRecord instead of mid-read release")
                audioRecord = null
                recordThread = null
                return snapshotAndClear()
            }
        }
        recordThread = null
        record.release()
        audioRecord = null
        return snapshotAndClear()
    }

    private fun snapshotAndClear(): FloatArray = synchronized(bufferLock) {
        val result = FloatArray(bufferSize) { i -> buffer[i] / 32768.0f }
        bufferSize = 0
        result
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(record.bufferSizeInFrames)
        try {
            // Inside the try: with the mic held by another app this throws,
            // and an uncaught exception here took the whole process down.
            record.startRecording()
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                synchronized(bufferLock) {
                    appendLocked(buffer, read)
                }
                onChunk?.invoke(
                    FloatArray(read) { i -> buffer[i] / 32768.0f },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "mic read failed", e)
        }
    }

    /** Caller holds [bufferLock]. Grows the storage geometrically. */
    private fun appendLocked(chunk: ShortArray, count: Int) {
        if (bufferSize + count > buffer.size) {
            var newSize = buffer.size
            while (bufferSize + count > newSize) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
        System.arraycopy(chunk, 0, buffer, bufferSize, count)
        bufferSize += count
    }

    companion object {
        private const val TAG = "SttRecorder"
        private const val SAMPLE_RATE = 16000
        private const val JOIN_TIMEOUT_MS = 1000L
        private const val BUFFER_INITIAL = 1 shl 17 // 128k shorts ≈ 8 s @16 kHz
    }
}
