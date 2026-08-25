package dev.vynkor.agent.agent

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * App-wide preferences that are neither host profiles nor chat data:
 * theme, chat-behavior toggles and the notification forward filter.
 */
object AppPrefs {
    private const val PREFS = "vynkor_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_TYPEWRITER = "typewriter_enabled"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_MUTED_PACKAGES = "muted_notification_packages"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun theme(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }

    /** Call early in every activity's onCreate, before super.onCreate(). */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(context)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    fun typewriterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TYPEWRITER, true)

    fun setTypewriterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TYPEWRITER, enabled).apply()
    }

    fun hapticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    /**
     * Packages whose notifications are NOT forwarded to the host.
     * Empty set = forward everything.
     */
    fun mutedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED_PACKAGES, emptySet()) ?: emptySet()

    fun isMuted(context: Context, packageName: String): Boolean =
        packageName in mutedPackages(context)

    /** SharedPreferences string sets must be copied before mutation. */
    fun setMuted(context: Context, packageName: String, muted: Boolean) {
        val updated = mutedPackages(context).toMutableSet()
        if (muted) updated.add(packageName) else updated.remove(packageName)
        prefs(context).edit().putStringSet(KEY_MUTED_PACKAGES, updated).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
