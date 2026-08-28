package dev.vynkor.agent.caps

import android.content.Context
import android.provider.Settings
import dev.vynkor.agent.BrightnessProvider

/**
 * Screen brightness. Changing values requires the special WRITE_SETTINGS
 * grant ("Modify system settings" screen); without it setters return false
 * and reads still work.
 */
class BrightnessProviderImpl(context: Context) : BrightnessProvider {
    private val ctx = context.applicationContext

    override fun level(): UByte? = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toUByte()
    }.getOrNull()

    override fun auto(): Boolean = runCatching {
        Settings.System.getInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    override fun setLevel(level: UByte): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return runCatching {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level.toInt().coerceIn(0, 255),
            )
        }.getOrDefault(false)
    }

    override fun setAuto(on: Boolean): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return runCatching {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                } else {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                },
            )
        }.getOrDefault(false)
    }
}
