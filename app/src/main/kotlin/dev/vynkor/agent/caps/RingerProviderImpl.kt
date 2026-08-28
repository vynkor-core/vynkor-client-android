package dev.vynkor.agent.caps

import android.content.Context
import android.media.AudioManager
import dev.vynkor.agent.RingerProvider

class RingerProviderImpl(context: Context) : RingerProvider {
    private val ctx = context.applicationContext

    override fun mode(): String = when (audio()?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown"
    }

    override fun setMode(mode: String): Boolean {
        val am = audio() ?: return false
        val target = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> return false
        }
        return runCatching {
            am.ringerMode = target
            // Verify rather than trust: some OEMs silently ignore changes.
            mode() == mode
        }.getOrDefault(false)
    }

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
