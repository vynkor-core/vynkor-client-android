package dev.vynkor.agent

import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.vynkor.agent.AppLock
import dev.vynkor.agent.agent.SecurityStore
import java.util.concurrent.atomic.AtomicBoolean

internal object AppLock {
    @Volatile
    var unlocked = false

    /** Last [SystemClock.elapsedRealtime] the whole app went to background. */
    @Volatile
    var backgroundedAtMs: Long? = null
}

private val backgroundTrackerRegistered = AtomicBoolean(false)

/**
 * Tracks app-level background transitions once per process. App-level (not
 * activity-level) is deliberate: opening the lock screen itself must not
 * count as «going to background», or a manual Lock would instantly arm the
 * relock timer behind it.
 */
private fun registerBackgroundTracker() {
    if (!backgroundTrackerRegistered.compareAndSet(false, true)) return
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            AppLock.backgroundedAtMs = SystemClock.elapsedRealtime()
        }
    })
}

/**
 * Call from the launcher's onStart. Resolves the lock state from
 * [SecurityStore] settings and, when locked, forwards to [LockActivity]:
 *
 * - master switch off → always unlocked;
 * - backgrounded ≥ `relockMinutes` → locked again;
 * - no biometrics enrolled AND no custom PIN → pass through (never brick);
 * - otherwise LockActivity takes over (it owns FLAG_SECURE and the prompts).
 */
fun AppCompatActivity.applyBiometricGate() {
    registerBackgroundTracker()

    val settings = SecurityStore.get(this)
    if (!settings.enabled) {
        AppLock.unlocked = true
        return
    }
    val bgAt = AppLock.backgroundedAtMs
    if (bgAt != null && SystemClock.elapsedRealtime() - bgAt >= settings.relockMinutes * 60_000L) {
        AppLock.unlocked = false
    }
    if (AppLock.unlocked) return

    val canFingerprint = BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    if (!canFingerprint && !settings.hasPin) {
        // Nothing to authenticate with — never brick the user.
        AppLock.unlocked = true
        return
    }

    startActivity(Intent(this, LockActivity::class.java))
}
