package dev.vynkor.agent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import dev.vynkor.agent.databinding.ActivityProfileBinding
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore

class ProfileActivity : AppCompatActivity() {

    private var editing: HostProfile? = null
    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        val name = binding.name
        val hostUrl = binding.hostUrl
        val deviceId = binding.deviceId
        val userId = binding.userId
        val jwt = binding.jwt
        val secret = binding.secret

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        editing = id?.let { ProfileStore.get(this, it) }

        editing?.let { p ->
            name.setText(p.name)
            hostUrl.setText(p.hostUrl)
            deviceId.setText(p.deviceId)
            userId.setText(p.userId)
            jwt.setText(p.jwtToken)
            secret.setText(p.jwtSecret)
        } ?: run {
            deviceId.setText(DeviceIdentity.deviceId(this))
            userId.setText("default")
        }

        binding.save.setOnClickListener {
            val profile = HostProfile(
                id = editing?.id ?: java.util.UUID.randomUUID().toString(),
                name = name.text?.toString()?.trim().orEmpty(),
                hostUrl = hostUrl.text?.toString()?.trim().orEmpty(),
                deviceId = deviceId.text?.toString()?.trim().orEmpty(),
                jwtToken = jwt.text?.toString()?.trim().orEmpty(),
                jwtSecret = secret.text?.toString()?.trim().orEmpty(),
                userId = userId.text?.toString()?.trim().orEmpty().ifBlank { "default" },
                // AI settings are no longer configured by hand: the host's `ai`
                // plugin is expected to declare its available models (see
                // docs/D14_AI_CHAT_AND_SETTINGS.md). Keep stored values when
                // editing so existing profiles are preserved.
                aiProvider = editing?.aiProvider ?: "openai",
                aiModel = editing?.aiModel.orEmpty(),
                aiBaseUrl = editing?.aiBaseUrl?.ifBlank { DEFAULT_AI_BASE_URL } ?: DEFAULT_AI_BASE_URL,
                aiApiKeyEnv = editing?.aiApiKeyEnv?.ifBlank { DEFAULT_AI_API_KEY_ENV } ?: DEFAULT_AI_API_KEY_ENV,
                aiAgent = editing?.aiAgent.orEmpty(),
            )
            if (profile.hostUrl.isBlank()) {
                snack(R.string.host_url_required)
                return@setOnClickListener
            }
            DeviceIdentity.setDeviceId(this, profile.deviceId)
            ProfileStore.save(this, profile)
            ProfileStore.setActive(this, profile.id)
            finish()
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val DEFAULT_AI_BASE_URL = "http://localhost:11434/v1"
        private const val DEFAULT_AI_API_KEY_ENV = "OLLAMA_API_KEY"
    }
}