package dev.vynkor.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityCapPermissionsBinding
import dev.vynkor.agent.databinding.ItemSettingsRowBinding

/**
 * Capabilities-permissions screen: sensitive runtime grants are requested
 * here, on demand — never at service start (the start-time set stays minimal).
 * Each row shows the live grant state; special grants (WRITE_SETTINGS,
 * DND policy) deep-link into their system screens.
 */
class CapPermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCapPermissionsBinding

    private data class Row(
        val titleRes: Int,
        val subtitleRes: Int,
        val permissions: List<String>,
        val minSdk: Int = 26,
        /** Non-null → row opens a system screen instead of a runtime dialog. */
        val specialIntent: ((Context) -> Intent)? = null,
    )

    private val rows = listOf(
        // Android 17: the host link itself (NEARBY_DEVICES group).
        Row(
            R.string.wizard_perm_lan_title, R.string.wizard_perm_lan_subtitle,
            listOf(dev.vynkor.agent.agent.AgentPermissions.ACCESS_LOCAL_NETWORK),
            minSdk = 37,
        ),
        Row(
            R.string.perm_location_title, R.string.perm_location_subtitle,
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        ),
        Row(
            R.string.perm_contacts_title, R.string.perm_contacts_subtitle,
            listOf(Manifest.permission.READ_CONTACTS),
        ),
        Row(
            R.string.perm_mic_title, R.string.perm_mic_subtitle,
            listOf(Manifest.permission.RECORD_AUDIO),
        ),
        Row(
            R.string.perm_sms_title, R.string.perm_sms_subtitle,
            listOf(Manifest.permission.READ_SMS),
        ),
        Row(
            R.string.perm_calls_title, R.string.perm_calls_subtitle,
            listOf(Manifest.permission.READ_CALL_LOG),
        ),
        Row(
            R.string.perm_calendar_title, R.string.perm_calendar_subtitle,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        ),
        Row(
            R.string.perm_bluetooth_title, R.string.perm_bluetooth_subtitle,
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            minSdk = Build.VERSION_CODES.S,
        ),
        Row(
            R.string.perm_write_settings_title, R.string.perm_write_settings_subtitle,
            emptyList(),
            specialIntent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).let { i ->
                i.data = Uri.fromParts("package", it.packageName, null)
                i
            } },
        ),
        Row(
            R.string.perm_dnd_title, R.string.perm_dnd_subtitle,
            emptyList(),
            specialIntent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
        ),
    )

    private var pendingRow: Int? = null

    private val visibleRowIndexes = mutableListOf<Int>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingRow = null
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCapPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }
        buildRows()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildRows() {
        visibleRowIndexes.clear()
        rows.forEachIndexed { index, row ->
            if (Build.VERSION.SDK_INT < row.minSdk) return@forEachIndexed
            visibleRowIndexes.add(index)
            val item = ItemSettingsRowBinding.inflate(layoutInflater, binding.rows, false)
            item.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            item.rowIcon.setImageResource(R.drawable.ic_lock)
            item.rowTitle.setText(row.titleRes)
            item.rowSubtitle.setText(row.subtitleRes)
            item.rowSubtitle.visibility = android.view.View.VISIBLE
            item.root.setOnClickListener { onRowTap(index) }
            binding.rows.addView(item.root)
        }
    }

    private fun onRowTap(index: Int) {
        val row = rows[index]
        row.specialIntent?.let { maker ->
            startActivity(maker(this))
            return
        }
        val missing = row.permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_already_granted_title)
                .setMessage(getString(R.string.perm_already_granted_body))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        pendingRow = index
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refresh() {
        visibleRowIndexes.forEachIndexed { childPos, rowIndex ->
            val row = rows[rowIndex]
            val item = ItemSettingsRowBinding.bind(binding.rows.getChildAt(childPos))
            val granted = row.permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            val on = granted || specialGranted(rowIndex)
            item.rowSubtitle.text = getString(
                R.string.perm_row_subtitle_fmt,
                getString(row.subtitleRes),
                getString(if (on) R.string.perm_on else R.string.perm_off),
            )
            item.rowSubtitle.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.connected else R.color.unreachable),
            )
        }
    }

    private fun specialGranted(index: Int): Boolean = when (index) {
        7 -> Settings.System.canWrite(this)
        8 -> (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
            ?.isNotificationPolicyAccessGranted == true
        else -> false
    }
}
