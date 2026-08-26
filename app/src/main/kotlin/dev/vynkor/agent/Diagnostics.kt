package dev.vynkor.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.EventLog
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.ProjectStore

/**
 * Builds the user-shareable diagnostics report (Settings -> Diagnostics):
 * plain text, secrets never included — only whether credentials are set and
 * how long they are. Everything shown already lives on this device.
 */
object Diagnostics {

    fun build(context: Context): String = buildString {
        val active = ProfileStore.active(context)
        appendLn("vynkor agent ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLn(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        appendLn()

        appendLn("[host]")
        if (active == null) {
            appendLn("no profile")
        } else {
            appendLn("name: ${active.name.ifBlank { context.getString(R.string.unnamed_profile) }}")
            appendLn("url: ${active.hostUrl}")
            appendLn("device_id: ${active.deviceId}")
            appendLn("jwt: ${present(active.jwtToken)}  secret: ${present(active.deviceSecret)}  cert pinned: ${active.certPem.isNotBlank()}")
            appendLn("model: ${active.effectiveModel().ifBlank { "-" }}  agent: ${active.aiAgent.ifBlank { "-" }}")
        }
        appendLn()

        appendLn("[agent]")
        appendLn("service running: ${AgentHolder.agent != null}")
        when (val status = AgentHolder.hostStatus.value) {
            is HostStatus.Unreachable -> appendLn("state: unreachable — ${status.reason}")
            else -> appendLn("state: ${status::class.simpleName?.lowercase()}")
        }
        appendLn()

        appendLn("[permissions]")
        perm(context, "location", Manifest.permission.ACCESS_FINE_LOCATION)?.let { appendLn(it) }
        perm(context, "microphone", Manifest.permission.RECORD_AUDIO)?.let { appendLn(it) }
        perm(context, "contacts", Manifest.permission.READ_CONTACTS)?.let { appendLn(it) }
        perm(context, "camera", Manifest.permission.CAMERA)?.let { appendLn(it) }
        if (Build.VERSION.SDK_INT >= 33) {
            perm(context, "notifications", Manifest.permission.POST_NOTIFICATIONS)?.let { appendLn(it) }
        }
        val dnd = context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted
        appendLn("dnd access: $dnd")
        appendLn("system settings: ${Settings.System.canWrite(context)}")
        appendLn()

        appendLn("[data]")
        val profiles = ProfileStore.list(context)
        appendLn("profiles: ${profiles.size}")
        appendLn("chats: ${profiles.sumOf { ChatStore.list(context, it.id).size }}")
        appendLn("projects: ${profiles.sumOf { ProjectStore.list(context, it.id).size }}")
        appendLn()

        appendLn("[recent events]")
        appendLn(EventLog.dump().ifBlank { "(none)" })
    }

    private fun StringBuilder.appendLn(text: String = "") {
        append(text).append('\n')
    }

    /** Presence + length only — the report must stay secret-free. */
    private fun present(value: String): String =
        if (value.isBlank()) "not set" else "set (${value.length} chars)"

    /** Only declared permissions appear in the report; others are skipped. */
    private fun perm(context: Context, label: String, permission: String): String? {
        if (!isDeclared(context, permission)) return null
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        return "$label: ${if (granted) "granted" else "denied"}"
    }

    private fun isDeclared(context: Context, permission: String): Boolean =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)
}
