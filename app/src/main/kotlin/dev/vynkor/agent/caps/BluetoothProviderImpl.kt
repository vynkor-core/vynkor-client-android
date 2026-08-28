package dev.vynkor.agent.caps

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.vynkor.agent.BluetoothProvider
import dev.vynkor.agent.BluetoothStatus
import dev.vynkor.agent.PairedBluetoothDevice

/**
 * Bluetooth state + bonded devices. On API 31+ results require the runtime
 * BLUETOOTH_CONNECT grant — checked per call (R-10). The `connected` flag is
 * a best-effort GATT union; per-profile (A2DP/HFP) connection state is not
 * publicly exposed per-device.
 */
class BluetoothProviderImpl(context: Context) : BluetoothProvider {
    private val ctx = context.applicationContext

    override fun status(): BluetoothStatus? {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = manager.adapter ?: return BluetoothStatus(enabled = false, adapterName = "")
        return BluetoothStatus(
            enabled = adapter.isEnabled,
            adapterName = if (canRead()) (adapter.name ?: "").orEmpty() else "",
        )
    }

    override fun paired(): List<PairedBluetoothDevice> {
        if (!canRead()) return emptyList()
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val connected = runCatching {
            manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT).orEmpty().toSet()
        }.getOrDefault(emptySet<android.bluetooth.BluetoothDevice>())
        return adapter.bondedDevices.orEmpty()
            .map { device ->
                PairedBluetoothDevice(
                    name = device.name.orEmpty(),
                    address = device.address.orEmpty(),
                    connected = connected.any { it.address == device.address },
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun canRead(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
