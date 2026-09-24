package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentPermissions
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.databinding.ActivitySetupBinding
import dev.vynkor.agent.databinding.ItemWizardPermBinding
import kotlinx.coroutines.launch

/**
 * First-launch wizard (IDEAS #6): welcome → pair via QR or manual form →
 * progressive permissions with explanations → live first connection.
 * Auto-shown by MainActivity while no host profile exists and the wizard
 * was not completed or skipped; every step says what stays optional.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    /** Permission rows of the permissions step, in display order. */
    private val permRows = listOfNotNull(
        // Android 17: without it the host on the LAN is unreachable at all.
        if (Build.VERSION.SDK_INT >= 37) {
            PermRow(
                AgentPermissions.ACCESS_LOCAL_NETWORK,
                R.string.wizard_perm_lan_title,
                R.string.wizard_perm_lan_subtitle,
            )
        } else {
            null
        },
        PermRow(
            Manifest.permission.CAMERA,
            R.string.wizard_perm_camera_title,
            R.string.wizard_perm_camera_subtitle,
        ),
        PermRow(
            Manifest.permission.ACCESS_FINE_LOCATION,
            R.string.wizard_perm_location_title,
            R.string.wizard_perm_location_subtitle,
        ),
        PermRow(
            Manifest.permission.RECORD_AUDIO,
            R.string.wizard_perm_mic_title,
            R.string.wizard_perm_mic_subtitle,
        ),
        PermRow(
            Manifest.permission.READ_CONTACTS,
            R.string.wizard_perm_contacts_title,
            R.string.wizard_perm_contacts_subtitle,
        ),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermRow(
                Manifest.permission.POST_NOTIFICATIONS,
                R.string.wizard_perm_notif_title,
                R.string.wizard_perm_notif_subtitle,
            )
        } else {
            null
        },
    )

    private data class PermRow(val perm: String, val title: Int, val subtitle: Int)

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) return@registerForActivityResult
        when (val decision = PairingApplier.handle(this, result.contents, external = false) {
            onProfileReady()
        }) {
            is PairingApplier.Decision.Applied -> Unit
            is PairingApplier.Decision.PendingConfirmation -> Unit
            is PairingApplier.Decision.Rejected ->
                Snackbar.make(binding.root, decision.reason, Snackbar.LENGTH_LONG).show()
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(scanOptions())
        } else {
            // Camera is only needed for QR scanning — manual entry still works.
            showStep(STEP_MANUAL)
            Snackbar.make(binding.root, R.string.camera_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        renderPermissionRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The wizard only makes sense for a blank slate; otherwise it would
        // fight the normal Hosts management flow.
        if (ProfileStore.list(this).isNotEmpty()) {
            finish()
            return
        }

        binding.wizardScan.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                scanLauncher.launch(scanOptions())
            } else {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        binding.wizardManual.setOnClickListener { showStep(STEP_MANUAL) }
        binding.wizardSkip.setOnClickListener { skip() }

        binding.manualDeviceId.setText(DeviceIdentity.deviceId(this).ifBlank { suggestDeviceId() })
        binding.manualUserId.setText("default")
        binding.manualSave.setOnClickListener { saveManual() }
        binding.manualBack.setOnClickListener { showStep(STEP_WELCOME) }

        binding.permsContinue.setOnClickListener { startConnection() }
        renderPermissionRows()

        binding.connectOpen.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        }
        binding.connectBack.setOnClickListener { showStep(STEP_PERMS) }

        observeConnection()
        showStep(STEP_WELCOME)
    }

    private fun scanOptions() = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(getString(R.string.scan_prompt))
        .setBeepEnabled(false)
        .setOrientationLocked(true)

    private fun suggestDeviceId(): String =
        "phone-" + java.util.UUID.randomUUID().toString().take(4)

    // ------------------------------------------------------------- manual

    private fun saveManual() {
        val profile = HostProfile(
            name = binding.manualName.text?.toString()?.trim().orEmpty(),
            hostUrl = binding.manualHostUrl.text?.toString()?.trim().orEmpty(),
            deviceId = binding.manualDeviceId.text?.toString()?.trim().orEmpty(),
            userId = binding.manualUserId.text?.toString()?.trim().orEmpty().ifBlank { "default" },
            jwtToken = binding.manualJwt.text?.toString()?.trim().orEmpty(),
            deviceSecret = binding.manualSecret.text?.toString()?.trim().orEmpty(),
        )
        if (profile.hostUrl.isBlank()) {
            Snackbar.make(binding.root, R.string.host_url_required, Snackbar.LENGTH_SHORT).show()
            return
        }
        DeviceIdentity.setDeviceId(this, profile.deviceId)
        ProfileStore.save(this, profile)
        ProfileStore.setActive(this, profile.id)
        onProfileReady()
    }

    /** A profile exists from here on — permissions and connection follow. */
    private fun onProfileReady() {
        AppPrefs.setWizardCompleted(this)
        showStep(STEP_PERMS)
    }

    private fun skip() {
        AppPrefs.setWizardCompleted(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wizard_skip_confirm_title)
            .setMessage(R.string.wizard_skip_confirm_body)
            .setPositiveButton(R.string.wizard_skip_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.wizard_skip_no, null)
            .show()
    }

    // -------------------------------------------------------- permissions

    private fun renderPermissionRows() {
        binding.permsRows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        permRows.forEach { row ->
            val item = ItemWizardPermBinding.inflate(inflater, binding.permsRows, false)
            item.permTitle.setText(row.title)
            item.permSubtitle.setText(row.subtitle)
            val granted = ContextCompat.checkSelfPermission(this, row.perm) ==
                PackageManager.PERMISSION_GRANTED
            item.permState.setText(
                if (granted) R.string.wizard_perm_granted else R.string.wizard_perm_missing,
            )
            item.permState.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (granted) R.color.primary else R.color.error,
                ),
            )
            if (!granted) {
                item.root.setOnClickListener { permLauncher.launch(row.perm) }
            } else {
                item.root.isClickable = false
            }
            binding.permsRows.addView(item.root)
        }
    }

    // ------------------------------------------------------------ connect

    private fun startConnection() {
        showStep(STEP_CONNECT)
        if (AgentHolder.agent == null) AgentService.start(this) else AgentService.restartIfRunning(this)
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentHolder.hostStatus.collect { status ->
                    val host = ProfileStore.active(this@SetupActivity)?.name
                        ?.ifBlank { null }
                        ?: getString(R.string.unnamed_profile)
                    val (text, colorRes, success) = when (status) {
                        is HostStatus.Idle ->
                            Triple(getString(R.string.status_disconnected), R.color.on_surface_variant, false)
                        is HostStatus.Connecting ->
                            Triple(getString(R.string.status_connecting), R.color.on_surface_variant, false)
                        is HostStatus.Reconnecting ->
                            Triple(getString(R.string.status_reconnecting), R.color.on_surface_variant, false)
                        is HostStatus.Unreachable ->
                            Triple(getString(R.string.status_unreachable_fmt, status.reason), R.color.error, false)
                        is HostStatus.Connected ->
                            Triple(getString(R.string.service_connected_to, host), R.color.primary, true)
                    }
                    binding.connectStatus.text = text
                    binding.connectStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, colorRes))
                    binding.connectProgress.visibility = if (success) View.GONE else View.VISIBLE
                    binding.connectSuccess.visibility = if (success) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // -------------------------------------------------------------- steps

    private fun showStep(step: Int) {
        binding.wizardFlipper.displayedChild = step
    }

    private companion object {
        const val STEP_WELCOME = 0
        const val STEP_MANUAL = 1
        const val STEP_PERMS = 2
        const val STEP_CONNECT = 3
    }
}
