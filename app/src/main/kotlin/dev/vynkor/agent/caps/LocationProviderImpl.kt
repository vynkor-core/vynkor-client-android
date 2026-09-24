package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Location
import dev.vynkor.agent.LocationProvider

/**
 * Reads the last known location (fast, cache-only). Slow fixes arrive via
 * Agent.pushGeoUpdate from the caller.
 *
 * R-10: the permission is checked on every [lastKnown] call, not cached at
 * construction — a grant or revocation takes effect without a restart.
 */
class LocationProviderImpl(context: Context) : LocationProvider {
    private val ctx = context.applicationContext
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun lastKnown(): Location? {
        if (!isGranted()) return null
        // Newest fix across providers — the first provider's cache could be
        // an hours-old passive fix while GPS/network held a fresh one.
        val fix = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?: return null
        return Location(
            lat = fix.latitude,
            lon = fix.longitude,
            accuracyM = fix.accuracy,
        )
    }

    override fun unavailableReason(): String? = when {
        !isGranted() -> "location permission not granted on the device"
        !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm) -> "location services are turned off on the device"
        else -> null
    }

    /** Approximate-only grants are valid too (Android 12+ lets users pick them). */
    private fun isGranted(): Boolean = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}
