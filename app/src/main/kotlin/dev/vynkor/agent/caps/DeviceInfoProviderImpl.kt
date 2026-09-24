package dev.vynkor.agent.caps

import android.content.Context
import android.os.Build
import dev.vynkor.agent.DeviceInfo
import dev.vynkor.agent.DeviceInfoProvider
import java.util.Locale

class DeviceInfoProviderImpl(context: Context) : DeviceInfoProvider {
    private val ctx = context.applicationContext

    override fun snapshot(): DeviceInfo {
        // Physical screen size: an app context's displayMetrics shrink to the
        // app window (split screen, freeform) and exclude system bars.
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = ctx.getSystemService(android.view.WindowManager::class.java)
                .maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        return DeviceInfo(
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT.toUShort(),
            locale = Locale.getDefault().toLanguageTag(),
            screenWidthPx = w.toUInt(),
            screenHeightPx = h.toUInt(),
        )
    }
}
