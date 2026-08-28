package dev.vynkor.agent.caps

import android.content.Context
import android.os.Build
import dev.vynkor.agent.DeviceInfo
import dev.vynkor.agent.DeviceInfoProvider
import java.util.Locale

class DeviceInfoProviderImpl(context: Context) : DeviceInfoProvider {
    private val ctx = context.applicationContext

    override fun snapshot(): DeviceInfo {
        val metrics = ctx.resources.displayMetrics
        return DeviceInfo(
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT.toUShort(),
            locale = Locale.getDefault().toLanguageTag(),
            screenWidthPx = metrics.widthPixels.toUInt(),
            screenHeightPx = metrics.heightPixels.toUInt(),
        )
    }
}
