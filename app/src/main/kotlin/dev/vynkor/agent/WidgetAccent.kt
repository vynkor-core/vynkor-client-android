package dev.vynkor.agent

import android.content.Context
import androidx.core.content.ContextCompat
import dev.vynkor.agent.agent.AppPrefs

/** Accent color that follows the Appearance -> Accent preference. */
object WidgetAccent {
    fun color(context: Context): Int = when (AppPrefs.accent(context)) {
        AppPrefs.ACCENT_GREEN -> ContextCompat.getColor(context, R.color.acc_green_primary)
        AppPrefs.ACCENT_PURPLE -> ContextCompat.getColor(context, R.color.acc_purple_primary)
        AppPrefs.ACCENT_ORANGE -> ContextCompat.getColor(context, R.color.acc_orange_primary)
        AppPrefs.ACCENT_ROSE -> ContextCompat.getColor(context, R.color.acc_rose_primary)
        else -> ContextCompat.getColor(context, R.color.primary)
    }
}
