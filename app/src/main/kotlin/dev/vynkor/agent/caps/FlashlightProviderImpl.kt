package dev.vynkor.agent.caps

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.vynkor.agent.FlashlightProvider

/**
 * Torch control. There is no synchronous torch getter, so the state comes
 * from a process-wide TorchCallback — it also sees the torch being switched
 * from the quick-settings tile or another app, which the old "last value we
 * set" bookkeeping missed (toggle then did the opposite of what was asked).
 */
class FlashlightProviderImpl(context: Context) : FlashlightProvider {
    init {
        registerTorchWatcher(context.applicationContext)
    }

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

        private val watcherRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

        fun registerTorchWatcher(appContext: Context) {
            if (!watcherRegistered.compareAndSet(false, true)) return
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            manager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchOn = enabled
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
    }
}
