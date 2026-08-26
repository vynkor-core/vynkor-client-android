package dev.vynkor.agent.agent

import android.content.Context
import dev.vynkor.agent.R
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * App-wide preferences that are neither host profiles nor chat data:
 * theme, accent, chat-behavior toggles and the notification forward filter.
 */
object AppPrefs {
    private const val PREFS = "vynkor_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_TYPEWRITER = "typewriter_enabled"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_MUTED_PACKAGES = "muted_notification_packages"
    private const val KEY_NOTIF_MODE = "service_notification_mode"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val ACCENT_BLUE = "blue"
    const val ACCENT_GREEN = "green"
    const val ACCENT_PURPLE = "purple"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_ROSE = "rose"

    fun theme(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
    }

    fun accent(context: Context): String =
        prefs(context).getString(KEY_ACCENT, ACCENT_BLUE) ?: ACCENT_BLUE

    fun setAccent(context: Context, value: String) {
        prefs(context).edit().putString(KEY_ACCENT, value).apply()
    }

    /** Service-notification detail level: see [MODE_*] constants. */
    const val NOTIF_DETAILED = "detailed"
    const val NOTIF_MINIMAL = "minimal"
    const val NOTIF_HIDDEN = "hidden"

    fun notifMode(context: Context): String =
        prefs(context).getString(KEY_NOTIF_MODE, NOTIF_DETAILED) ?: NOTIF_DETAILED

    fun setNotifMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_NOTIF_MODE, value).apply()
    }

    /** First-launch onboarding shown until a profile exists or user skips. */
    const val KEY_WIZARD_DONE = "setup_wizard_done"

    fun wizardCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIZARD_DONE, false)

    fun setWizardCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_WIZARD_DONE, true).apply()
    }

    /** Theme overlay resource for the chosen accent (blue = base theme). */
    fun accentThemeRes(context: Context): Int = when (accent(context)) {
        ACCENT_GREEN -> R.style.Theme_Vynkor_AccentGreen
        ACCENT_PURPLE -> R.style.Theme_Vynkor_AccentPurple
        ACCENT_ORANGE -> R.style.Theme_Vynkor_AccentOrange
        ACCENT_ROSE -> R.style.Theme_Vynkor_AccentRose
        else -> 0
    }

    /**
     * Call early in every activity's onCreate, before super.onCreate():
     * applies the night mode and the accent overlay.
     */
    fun applyTheme(activity: AppCompatActivity) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(activity)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
        activity.theme.applyStyle(accentThemeRes(activity), true)
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
