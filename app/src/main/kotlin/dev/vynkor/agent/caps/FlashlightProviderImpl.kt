package dev.vynkor.agent.caps

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.vynkor.agent.FlashlightProvider

/**
 * Torch control. Android exposes no synchronous torch getter before API 33,
 * so the on/off state is the last value this provider set (documented
 * approximation; `toggle` stays correct because it flips through this state).
 */
class FlashlightProviderImpl(context: Context) : FlashlightProvider {
    private val ctx = context.applicationContext

    @Volatile
    private var torchOn = false

    override fun available(): Boolean = flashId() != null

    override fun isOn(): Boolean = torchOn

    override fun setOn(on: Boolean): Boolean {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val id = flashId() ?: return false
        return runCatching {
            manager.setTorchMode(id, on)
            torchOn = on
            true
        }.getOrDefault(false)
    }

    private fun flashId(): String? {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
    }
}
