package dev.vynkor.agent.agent

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persisted app-lock settings + PIN verification.
 *
 * The PIN is never stored: only a per-install random salt and
 * SHA-256(salt || pin). Settings live in their own prefs file so the lock
 * cannot be toggled by clearing the chat/config stores.
 */
object SecurityStore {
    private const val PREFS = "vynkor_security"
    private const val KEY_ENABLED = "lock_enabled"
    private const val KEY_RELOCK_MIN = "relock_minutes"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"

    data class Settings(
        val enabled: Boolean,
        val relockMinutes: Int,
        val pinHash: String?,
        val pinSalt: String?,
    ) {
        val hasPin: Boolean get() = !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()
    }

    fun get(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, true),
            relockMinutes = p.getInt(KEY_RELOCK_MIN, DEFAULT_RELOCK_MINUTES),
            pinHash = p.getString(KEY_PIN_HASH, null),
            pinSalt = p.getString(KEY_PIN_SALT, null),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setRelockMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_RELOCK_MIN, minutes.coerceIn(1, 120)).apply()
    }

    /** Null/blank clears the PIN (fingerprint-only mode). */
    fun setPin(context: Context, plain: String?) {
        val p = prefs(context).edit()
        if (plain.isNullOrBlank()) {
            p.remove(KEY_PIN_HASH).remove(KEY_PIN_SALT)
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            p.putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_PIN_HASH, hash(plain, salt))
        }
        p.apply()
    }

    fun verify(context: Context, plain: String): Boolean {
        val s = get(context)
        if (!s.hasPin) return false
        val salt = try {
            Base64.decode(s.pinSalt!!, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return hash(plain, salt) == s.pinHash
    }

    private fun hash(plain: String, salt: ByteArray): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(salt + plain.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val DEFAULT_RELOCK_MINUTES = 5
}
