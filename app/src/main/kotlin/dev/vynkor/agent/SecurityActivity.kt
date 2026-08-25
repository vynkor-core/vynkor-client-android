package dev.vynkor.agent

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.vynkor.agent.AppLock
import dev.vynkor.agent.agent.SecurityStore
import dev.vynkor.agent.databinding.ActivitySecurityBinding

/**
 * «Безопасность»: master-switch app lock, свой PIN, таймер повторного
 * запроса после фона.
 */
class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }

        val settings = SecurityStore.get(this)
        binding.appLockSwitch.isChecked = settings.enabled
        binding.relockSlider.value = settings.relockMinutes.toFloat()
        binding.relockSlider.setLabelFormatter { "$it min" }
        renderPinState(settings)

        binding.appLockSwitch.setOnCheckedChangeListener { _, checked ->
            SecurityStore.setEnabled(this, checked)
            if (!checked) AppLock.unlocked = true // выключенный лок не держит экран
            renderVisibility(checked)
        }
        binding.savePin.setOnClickListener { savePin() }
        binding.removePin.setOnClickListener {
            SecurityStore.setPin(this, null)
            renderPinState(SecurityStore.get(this))
            snack(R.string.pin_removed)
        }
        binding.relockSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) SecurityStore.setRelockMinutes(this, value.toInt())
        }
    }

    private fun savePin() {
        val pin = binding.pinInput.text?.toString().orEmpty()
        if (pin.length < MIN_PIN_LENGTH) {
            snack(R.string.pin_too_short)
            return
        }
        SecurityStore.setPin(this, pin)
        binding.pinInput.setText("")
        renderPinState(SecurityStore.get(this))
        snack(R.string.pin_saved)
    }

    private fun renderPinState(settings: SecurityStore.Settings) {
        renderVisibility(settings.enabled)
        binding.pinState.setText(
            if (settings.hasPin) R.string.pin_state_set else R.string.pin_state_absent,
        )
        binding.removePin.visibility =
            if (settings.hasPin) View.VISIBLE else View.GONE
    }

    private fun renderVisibility(enabled: Boolean) {
        binding.lockOptions.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private companion object {
        const val MIN_PIN_LENGTH = 4
    }
}
