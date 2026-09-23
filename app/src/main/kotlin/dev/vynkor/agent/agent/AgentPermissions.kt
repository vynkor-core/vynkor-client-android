package dev.vynkor.agent.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime grants the agent asks for before its service starts (R-10), in one
 * place instead of a copy per entry screen.
 *
 * Android 17 (API 37): a LAN host is only reachable with
 * `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group). Without it the socket is
 * silently black-holed — the connect just times out — so the service start
 * must request it and the UI must name it when a connect fails.
 */
object AgentPermissions {

    /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, spelled out for older compile SDKs. */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** First API level that enforces the local-network grant for our target. */
    private const val LOCAL_NETWORK_API = 37

    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        // Not a runtime permission below 33: checkSelfPermission reports it
        // denied forever and the request is a no-op.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_API) add(ACCESS_LOCAL_NETWORK)
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /** False only where the platform enforces the grant and it is absent. */
    fun hasLocalNetwork(context: Context): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_API ||
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts the agent right away when nothing is missing; otherwise asks and
     * returns true — the caller then starts the service from its
     * onRequestPermissionsResult for [requestCode].
     */
    fun requestIfNeeded(activity: Activity, requestCode: Int): Boolean {
        val missing = missing(activity)
        if (missing.isEmpty()) return false
        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        return true
    }
}
