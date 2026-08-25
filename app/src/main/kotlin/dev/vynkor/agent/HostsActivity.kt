package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.PairingPayload
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityHostsBinding

/**
 * Hosts management: the profile list, QR pairing and manual add/edit.
 * Split out of Settings so Settings stays a clean section list (ui-reference).
 */
class HostsActivity : AppCompatActivity() {

    private lateinit var adapter: ProfileAdapter
    private lateinit var binding: ActivityHostsBinding
    private var pendingServiceStart = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onPairingPayload(result.contents!!, external = false)
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchScanner()
            } else {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.camera_denied,
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.open_settings) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                            Uri.fromParts("package", packageName, null),
                        ),
                    )
                }.show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHostsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }
        binding.scan.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                launchScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        adapter = ProfileAdapter(
            onSelect = { profile ->
                ProfileStore.setActive(this, profile.id)
                refresh()
            },
            onEdit = { profile ->
                startActivity(
                    Intent(this, ProfileActivity::class.java)
                        .putExtra(ProfileActivity.EXTRA_PROFILE_ID, profile.id)
                )
            },
            onDelete = { profile ->
                ProfileStore.delete(this, profile.id)
                refresh()
            },
        )
        binding.profiles.layoutManager = LinearLayoutManager(this)
        binding.profiles.adapter = adapter

        binding.addProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        handlePairIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    // external vynkor://pair links (camera tap, browser, adb) land here;
    // R-02: they go through the confirm dialog before anything applies
    private fun handlePairIntent(i: Intent?) {
        val raw = i?.dataString ?: return
        if (!raw.startsWith("${PairingPayload.SCHEME}://")) return
        i.data = null // re-delivery guard: apply once per intent
        onPairingPayload(raw, external = true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true),
        )
    }

    /**
     * R-02: an in-app QR scan is the trusted physical channel and applies
     * directly. Any other entry point (VIEW intent from another app) must be
     * confirmed by the user before anything is saved or connected.
     */
    private fun onPairingPayload(raw: String, external: Boolean) {
        val profile = when (val result = PairingPayload.parseWithReason(raw)) {
            is PairingPayload.Result.Ok -> result.profile
            is PairingPayload.Result.Invalid -> {
                // E-01: v1 payloads carry the host master secret — the reason
                // says exactly that instead of a generic "invalid"
                Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                return
            }
        }
        if (!external) {
            applyPairing(profile)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_confirm_title)
            .setMessage(getString(R.string.pair_confirm_message, profile.hostUrl, profile.deviceId))
            .setPositiveButton(R.string.pair_confirm_yes) { _, _ -> applyPairing(profile) }
            .setNegativeButton(R.string.pair_confirm_no, null)
            .show()
    }

    private fun applyPairing(profile: HostProfile) {
        DeviceIdentity.setDeviceId(this, profile.deviceId)
        ProfileStore.save(this, profile)
        ProfileStore.setActive(this, profile.id)
        refresh()
        Toast.makeText(this, getString(R.string.paired_and_connected, profile.name), Toast.LENGTH_SHORT).show()
        if (AgentHolder.agent == null) {
            startServiceAfterPermissions()
        }
    }

    /**
     * R-10: the service starts only after the permission dialog has been
     * resolved (not fire-and-forget alongside it).
     */
    private fun startServiceAfterPermissions() {
        val missing = MainActivity.PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            AgentService.start(this)
            return
        }
        pendingServiceStart = true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMS && pendingServiceStart) {
            pendingServiceStart = false
            AgentService.start(this)
        }
    }

    private fun refresh() {
        val active = ProfileStore.active(this)
        adapter.submit(ProfileStore.list(this), active?.id)
    }

    companion object {
        private const val REQUEST_CODE_PERMS = 43
    }
}
