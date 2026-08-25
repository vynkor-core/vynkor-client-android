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
        for (provider in lm.getProviders(true)) {
            val fix = lm.getLastKnownLocation(provider) ?: continue
            return Location(
                lat = fix.latitude,
                lon = fix.longitude,
                accuracyM = fix.accuracy,
            )
        }
        return null
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
