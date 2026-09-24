package dev.vynkor.agent

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.PairingPayload
import dev.vynkor.agent.agent.ProfileStore

/**
 * Shared pairing flow for every entry point that can produce a payload:
 * in-app QR scans are the trusted channel and apply directly; VIEW-intents
 * from other apps must pass the R-02 confirm dialog first.
 */
object PairingApplier {

    sealed class Decision {
        data class Applied(val profile: HostProfile) : Decision()
        /** Parse failed — [reason] is user-facing (e.g. pairing v1 rejection). */
        data class Rejected(val reason: String) : Decision()
        /** Valid payload from an external source; the confirm dialog is up. */
        data object PendingConfirmation : Decision()
    }

    /**
     * Parses [raw] and applies or stages it. [onApplied] runs after the
     * profile is saved and activated — callers typically start the agent
     * there once permissions are resolved.
     */
    fun handle(
        activity: AppCompatActivity,
        raw: String,
        external: Boolean,
        onApplied: (HostProfile) -> Unit,
    ): Decision {
        val profile = when (val parsed = PairingPayload.parseWithReason(raw)) {
            is PairingPayload.Result.Ok -> parsed.profile
            is PairingPayload.Result.Invalid -> return Decision.Rejected(parsed.reason)
        }
        if (!external) {
            apply(activity, profile)
            onApplied(profile)
            return Decision.Applied(profile)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pair_confirm_title)
            .setMessage(
                activity.getString(R.string.pair_confirm_message, profile.hostUrl, profile.deviceId),
            )
            .setPositiveButton(R.string.pair_confirm_yes) { _, _ ->
                apply(activity, profile)
                onApplied(profile)
            }
            .setNegativeButton(R.string.pair_confirm_no, null)
            .show()
        return Decision.PendingConfirmation
    }

    /**
     * Saves and activates the profile; does not touch the service.
     *
     * Re-pairing the same host+device (fresh token, rotated secret, new cert)
     * updates the existing profile in place — keeping its id (chats, projects
     * and drafts are keyed by it) and its chat/AI choices — instead of adding
     * a duplicate host entry on every scan.
     */
    fun apply(activity: AppCompatActivity, profile: HostProfile) {
        val deviceId = profile.deviceId.ifBlank { DeviceIdentity.deviceId(activity) }
        DeviceIdentity.setDeviceId(activity, deviceId)
        val existing = ProfileStore.list(activity).firstOrNull {
            it.deviceId == deviceId && sameHost(it.hostUrl, profile.hostUrl)
        }
        val merged = existing?.copy(
            name = profile.name.ifBlank { existing.name },
            hostUrl = profile.hostUrl,
            jwtToken = profile.jwtToken,
            deviceSecret = profile.deviceSecret,
            certPem = profile.certPem,
        ) ?: profile.copy(deviceId = deviceId)
        ProfileStore.save(activity, merged)
        ProfileStore.setActive(activity, merged.id)
    }

    /** Host identity for re-pair matching: scheme-less host:port. */
    internal fun sameHost(a: String, b: String): Boolean {
        fun key(u: String) = u.trim().substringAfter("://").substringBefore('/').lowercase()
        return key(a) == key(b)
    }
}
