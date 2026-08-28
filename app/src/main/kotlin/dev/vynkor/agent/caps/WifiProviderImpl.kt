package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.vynkor.agent.WifiNetwork
import dev.vynkor.agent.WifiProvider
import dev.vynkor.agent.WifiStatus

/**
 * Wi-Fi state + scan results. R-10: location permission is re-checked on
 * every call — since API 26 SSID/scan visibility requires it, and a grant
 * made after service start must take effect without a restart.
 */
class WifiProviderImpl(context: Context) : WifiProvider {
    private val ctx = context.applicationContext

    override fun status(): WifiStatus? {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        if (!wifi.isWifiEnabled) return WifiStatus(
            enabled = false, ssid = "", ip = "", linkSpeedMbps = 0,
        )
        val info = wifi.connectionInfo ?: return WifiStatus(
            enabled = true, ssid = "", ip = "", linkSpeedMbps = 0,
        )
        // "<unknown ssid>" is what the framework reports without a location grant.
        val ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
        val knownSsid = if (locationGranted() && ssid != WifiManager.UNKNOWN_SSID) ssid else ""
        val ip = info.ipAddress
        val ipText = if (ip != 0) {
            "%d.%d.%d.%d".format(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
        } else ""
        return WifiStatus(
            enabled = true,
            ssid = knownSsid,
            ip = ipText,
            linkSpeedMbps = info.linkSpeed,
        )
    }

    override fun scan(): List<WifiNetwork> {
        if (!locationGranted()) return emptyList()
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return wifi.scanResults.orEmpty()
            .sortedByDescending { it.level }
            .take(SCAN_LIMIT)
            .map { ap ->
                WifiNetwork(
                    ssid = ap.SSID.orEmpty().ifBlank { "(hidden)" },
                    bssid = ap.BSSID.orEmpty(),
                    rssi = ap.level,
                    secure = ap.capabilities?.isNotEmpty() == true &&
                        !ap.capabilities.contains("OPEN"),
                )
            }
    }

    private fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SCAN_LIMIT = 50
    }
}
