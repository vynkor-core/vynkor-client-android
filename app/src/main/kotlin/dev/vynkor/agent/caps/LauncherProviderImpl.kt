package dev.vynkor.agent.caps

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.vynkor.agent.AppEntry
import dev.vynkor.agent.LauncherProvider

/**
 * App listing + launch. The launcher-intent `<queries>` declaration in the
 * manifest keeps package visibility working on API 30+.
 */
class LauncherProviderImpl(context: Context) : LauncherProvider {
    private val ctx = context.applicationContext

    override fun apps(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { AppEntry(packageName = it.packageName, appName = pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    override fun launch(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == ctx.packageName) return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Settings deep link used by the permissions screen for special grants. */
    companion object {
        fun appDetailsSettingsIntent(context: Context): Intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    }
}
