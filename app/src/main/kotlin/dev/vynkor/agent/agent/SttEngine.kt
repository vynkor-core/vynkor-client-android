package dev.vynkor.agent.agent

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide, lazy on-device speech-to-text engine backed by sherpa-onnx.
 *
 * The bundled `zipformer-ru-int8` model is **offline-only** — its metadata
 * lacks the streaming `encoder_dims`, so `OnlineRecognizer` rejects it. "Live"
 * dictation is therefore emulated: audio lands in an [SttSession] `pending`
 * buffer and [partial] re-decodes only that bounded tail (R-11), prepending
 * the text already frozen into `committedText`. When the tail outgrows
 * [WINDOW_SAMPLES], its overflow is decoded once, folded into the committed
 * prefix and dropped — so per-tick CPU and session memory stay constant no
 * matter how long the user talks. [finish] decodes the remaining tail once
 * more for the best final text and frees the session.
 *
 * The recognizer construction is slow (seconds), so [ensureLoaded] performs
 * it exactly once on a background loader thread. The `onReady` callback is
 * always invoked on that loader thread once loading settles (success or
 * failure) — callers must check [isReady] and hop to the main thread for any
 * UI work. All decode methods are blocking and CPU-bound: call them on
 * `Dispatchers.IO`, never the main thread.
 */
class SttEngine private constructor(context: Context) {

    private val assets = context.assets
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stt-model-loader")
    }
    private val lock = Any()
    private val pendingCallbacks = ArrayDeque<() -> Unit>()
    private var loading = false

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    fun isReady(): Boolean = recognizer != null

    /**
     * Loads the recognizer on a background thread if not yet loaded. Invokes
     * [onReady] on the loader thread once loading settles. If the model is
     * already loaded the callback is still invoked on a background thread.
     */
    fun ensureLoaded(onReady: (() -> Unit)? = null) {
        if (isReady()) {
            if (onReady != null) loader.execute { onReady() }
            return
        }
        var start = false
        synchronized(lock) {
            if (isReady()) {
                if (onReady != null) loader.execute { onReady() }
            } else {
                if (onReady != null) pendingCallbacks.add(onReady)
                if (!loading) {
                    loading = true
                    start = true
                }
            }
        }
        if (start) loader.execute { loadModel() }
    }

    /** Opens a new dictation session. Null when the model is not loaded. */
    fun newSession(): SttSession? = if (isReady()) SttSession() else null

    /** Appends a chunk of 16 kHz mono samples in [-1, 1]. Cheap, any thread. */
    fun feed(session: SttSession, chunk: FloatArray) {
        if (chunk.isEmpty()) return
        synchronized(session.lock) {
            if (session.finished) return
            for (s in chunk) session.pending.add(s)
        }
    }

    /**
     * Decodes the bounded pending tail and returns the current transcript
     * (grows as the user keeps talking). Commits overflow to the prefix when
     * the tail exceeds [WINDOW_SAMPLES]. CPU-bound: call on Dispatchers.IO.
     *
     * Single-caller by design (the dictation coroutine ticks sequentially);
     * concurrent [partial]/[finish] on one session is unsupported.
     */
    fun partial(session: SttSession): String {
        var snapshot = FloatArray(0)
        var commitHead: FloatArray? = null
        var splitAt = 0
        synchronized(session.lock) {
            if (session.pending.isEmpty()) return session.committedText
            snapshot = session.pending.toFloatArray()
            if (!session.finished && snapshot.size > WINDOW_SAMPLES) {
                splitAt = snapshot.size - WINDOW_SAMPLES
                commitHead = snapshot.copyOf(splitAt)
                // trim now so memory stays bounded even if decode is slow
                session.pending.subList(0, splitAt).clear()
            }
        }
        commitHead?.let { head ->
            val headText = decode(head)
            synchronized(session.lock) { session.committedText += headText }
        }
        val tail = if (splitAt > 0) snapshot.copyOfRange(splitAt, snapshot.size) else snapshot
        val tailText = decode(tail)
        return synchronized(session.lock) { session.committedText } + tailText
    }

    /**
     * Marks the session done, returns the final transcript and frees the
     * accumulated audio. The remaining tail is decoded whole, once, for the
     * best possible final text. CPU-bound: call on Dispatchers.IO.
     */
    fun finish(session: SttSession): String {
        val tail: FloatArray
        val committed: String
        synchronized(session.lock) {
            session.finished = true
            tail = session.pending.toFloatArray()
            session.pending.clear()
            committed = session.committedText
        }
        return committed + decode(tail)
    }

    private fun decode(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        if (samples.isEmpty()) return ""
        return try {
            val stream: OfflineStream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe failed", e)
            ""
        }
    }

    private fun loadModel() {
        var callbacks: List<() -> Unit> = emptyList()
        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "stt/encoder.onnx",
                        decoder = "stt/decoder.onnx",
                        joiner = "stt/joiner.onnx",
                    ),
                    tokens = "stt/tokens.txt",
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assetManager = assets, config = config)
            Log.i(TAG, "offline recognizer loaded")
        } catch (e: Exception) {
            Log.e(TAG, "failed to load STT model", e)
            recognizer = null
        } finally {
            synchronized(lock) {
                loading = false
                callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
            }
        }
        for (callback in callbacks) {
            try {
                callback()
            } catch (e: Exception) {
                Log.w(TAG, "ensureLoaded callback failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "SttEngine"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 2

        /** Tail re-decoded on every partial tick: bounds per-tick CPU. */
        private const val WINDOW_SAMPLES = 15 * SAMPLE_RATE

        @Volatile
        private var instance: SttEngine? = null

        fun get(context: Context): SttEngine =
            instance ?: synchronized(this) {
                instance ?: SttEngine(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * One dictation session: a frozen text prefix plus the bounded audio tail
 * that is still being re-decoded. Fed from the recorder thread, decoded on an
 * IO thread; [finished] stops further accumulation after [SttEngine.finish].
 */
class SttSession internal constructor() {
    internal val lock = Any()
    internal var committedText = ""
    internal val pending = ArrayList<Float>(SAMPLE_PREALLOC)
    internal var finished = false
}

private const val SAMPLE_PREALLOC = 16_000 * 30
