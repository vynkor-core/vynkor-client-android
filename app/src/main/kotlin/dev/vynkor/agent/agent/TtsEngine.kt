package dev.vynkor.agent.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TtsEngine(context: Context) : TextToSpeech.OnInitListener {
    // Must precede `tts`: TextToSpeech can invoke onInit synchronously inside
    // its constructor (observed on devices with a failing/absent TTS
    // engine), and onInit touches this handler.
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    /** Invoked on the main thread when playback finishes (or errors out). */
    var onDone: (() -> Unit)? = null

    /** Invoked on the main thread when the engine failed to initialize. */
    var onInitFailed: (() -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = fireDone()
            override fun onError(utteranceId: String?) = fireDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) = fireDone()
        })
    }

    fun isReady(): Boolean = ready

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun isSpeaking(): Boolean = tts.isSpeaking

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        onDone = null
        ready = false
        tts.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.ERROR) {
            main.post { onInitFailed?.invoke() }
            return
        }
        ready = true
        tts.language = Locale.getDefault()
    }

    private fun fireDone() {
        val cb = onDone ?: return
        main.post { cb() }
    }
}
