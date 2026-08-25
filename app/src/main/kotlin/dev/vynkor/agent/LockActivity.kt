package dev.vynkor.agent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import dev.vynkor.agent.AppLock
import dev.vynkor.agent.agent.SecurityStore
import dev.vynkor.agent.databinding.ActivityLockBinding

/**
 * Full-screen lock: shown by the gate whenever the app is locked. Offers the
 * custom PIN (when set) and/or device fingerprint. Back = move to background,
 * not exit; five wrong PINs close the app.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private var failedAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = SecurityStore.get(this)
        if (!settings.enabled) {
            // Stale launch while the feature was being turned off.
            AppLock.unlocked = true
            finish()
            return
        }
        val canFingerprint = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

        binding.pinLayout.visibility =
            if (settings.hasPin) android.view.View.VISIBLE else android.view.View.GONE
        binding.unlockButton.visibility = binding.pinLayout.visibility
        binding.fingerprintButton.visibility =
            if (canFingerprint) android.view.View.VISIBLE else android.view.View.GONE

        binding.unlockButton.setOnClickListener { verifyPin() }
        binding.pinInput.setOnEditorActionListener { _, _, _ ->
            verifyPin(); true
        }
        binding.fingerprintButton.setOnClickListener { launchFingerprint() }

        // No PIN configured → behave like before: prompt for the finger right away.
        if (!settings.hasPin && canFingerprint) {
            launchFingerprint()
        } else if (!settings.hasPin) {
            // Nothing to authenticate with — should not happen (the gate passes
            // through), but never brick the user behind a dead lock screen.
            onSuccess()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private fun verifyPin() {
        val pin = binding.pinInput.text?.toString().orEmpty()
        if (SecurityStore.verify(this, pin)) {
            onSuccess()
            return
        }
        failedAttempts++
        binding.errorText.visibility = android.view.View.VISIBLE
        binding.pinInput.setText("")
        if (failedAttempts >= MAX_ATTEMPTS) {
            Snackbar.make(binding.root, R.string.too_many_attempts, Snackbar.LENGTH_LONG).show()
            finishAffinity()
        }
    }

    private fun launchFingerprint() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                // Negative/cancel just returns to the lock screen; nothing to do.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_unlock_title))
            .setSubtitle(getString(R.string.locked_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun onSuccess() {
        AppLock.unlocked = true
        AppLock.backgroundedAtMs = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        finish()
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
