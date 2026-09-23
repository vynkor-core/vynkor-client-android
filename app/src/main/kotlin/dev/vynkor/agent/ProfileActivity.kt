package dev.vynkor.agent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityProfileBinding
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore

class ProfileActivity : AppCompatActivity() {

    private var editing: HostProfile? = null
    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
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
            secret.setText(p.deviceSecret)
        } ?: run {
            deviceId.setText(DeviceIdentity.deviceId(this))
            userId.setText("default")
        }

        binding.save.setOnClickListener {
            // copy() keeps every field this form does not show — the pinned
            // TLS cert above all: rebuilding the profile field by field
            // dropped cert_pem, and a wss:// host stopped verifying after any
            // edit. AI/chat choices are made in the chat's model picker.
            val profile = (editing ?: HostProfile()).copy(
                name = name.text?.toString()?.trim().orEmpty(),
                hostUrl = hostUrl.text?.toString()?.trim().orEmpty(),
                deviceId = deviceId.text?.toString()?.trim().orEmpty(),
                jwtToken = jwt.text?.toString()?.trim().orEmpty(),
                deviceSecret = secret.text?.toString()?.trim().orEmpty(),
                userId = userId.text?.toString()?.trim().orEmpty().ifBlank { "default" },
            )
            if (profile.hostUrl.isBlank()) {
                snack(R.string.host_url_required)
                return@setOnClickListener
            }
            DeviceIdentity.setDeviceId(this, profile.deviceId)
            ProfileStore.save(this, profile)
            ProfileStore.setActive(this, profile.id)
            AgentService.restartIfRunning(this)
            finish()
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}