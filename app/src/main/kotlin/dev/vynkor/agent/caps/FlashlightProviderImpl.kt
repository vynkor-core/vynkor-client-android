package dev.vynkor.agent.caps

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.vynkor.agent.FlashlightProvider

/**
 * Torch control. Android exposes no synchronous torch getter before API 33,
 * so the on/off state is the last value set by any instance in this process
 * (shared companion state — widget broadcasts and host requests agree).
 */
class FlashlightProviderImpl(context: Context) : FlashlightProvider {

    override fun available(): Boolean = flashId() != null

    override fun isOn(): Boolean = torchOn

    fun toggle(): Boolean = setOn(!torchOn)

    override fun setOn(on: Boolean): Boolean {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val id = flashId() ?: return false
        return runCatching {
            manager.setTorchMode(id, on)
            torchOn = on
            true
        }.getOrDefault(false)
    }

    private val ctx = context.applicationContext

    private fun flashId(): String? {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
    }

    private companion object {
        @Volatile
        var torchOn: Boolean = false
    }
}
