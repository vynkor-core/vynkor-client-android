package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.SecurityStore
import dev.vynkor.agent.databinding.ActivityMainBinding
import dev.vynkor.agent.databinding.DialogChatBehaviorBinding
import dev.vynkor.agent.databinding.ItemSettingsRowBinding
import kotlinx.coroutines.launch

/**
 * Settings hub (ui-reference style): connection card on top, grouped rows
 * below — Hosts / Security / About. Hosts management and Security live on
 * their own screens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingServiceStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }

        bindRow(binding.rowHosts, R.drawable.ic_hosts, R.string.hosts_title)
            .setOnClickListener { startActivity(Intent(this, HostsActivity::class.java)) }

        bindRow(binding.rowNotifications, R.drawable.ic_notifications, R.string.notifications_title)
            .setOnClickListener { startActivity(Intent(this, NotificationFilterActivity::class.java)) }

        bindRow(binding.rowAppearance, R.drawable.ic_palette, R.string.appearance_title)
            .setOnClickListener { showAppearanceDialog() }

        bindRow(binding.rowChat, R.drawable.ic_tune, R.string.chat_behavior_title)
            .setOnClickListener { showChatBehaviorDialog() }

        bindRow(binding.rowSecurity, R.drawable.ic_lock, R.string.security_title)
            .setOnClickListener { startActivity(Intent(this, SecurityActivity::class.java)) }

        bindRow(binding.rowAbout, R.drawable.ic_info, R.string.about_row)
            .setOnClickListener { showAbout() }

        binding.connect.setOnClickListener {
            if (AgentHolder.agent != null) {
                AgentService.stop(this)
            } else {
                startServiceAfterPermissions()
            }
        }

        lifecycleScope.launch {
            AgentHolder.hostStatus.collect { st ->
                val (textRes, colorRes) = when (st) {
                    is HostStatus.Connected ->
                        R.string.status_connected to R.color.connected
                    is HostStatus.Connecting ->
                        R.string.status_connecting to R.color.connecting
                    is HostStatus.Reconnecting ->
                        R.string.status_reconnecting to R.color.connecting
                    is HostStatus.Unreachable ->
                        R.string.status_unreachable_fmt to R.color.unreachable
                    is HostStatus.Idle ->
                        R.string.status_disconnected to R.color.disconnected
                }
                binding.statusDot.setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
                binding.statusText.text =
                    if (st is HostStatus.Unreachable) getString(textRes, st.reason)
                    else getString(textRes)
                // Honest button: while the agent is dialing (Connecting/
                // Reconnecting/Unreachable) the same tap means STOP retrying,
                // not "connect" — the old label hid that.
                binding.connect.setText(
                    when (st) {
                        is HostStatus.Connected -> R.string.disconnect_button
                        is HostStatus.Idle -> R.string.connect_button
                        else -> R.string.stop_button
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val active = ProfileStore.active(this)
        binding.activeName.text =
            active?.name?.ifBlank { getString(R.string.unnamed_profile) } ?: getString(R.string.no_profile)
        binding.activeHost.text = active?.hostUrl ?: ""
        binding.rowHosts.rowSubtitle.text = getString(R.string.hosts_count_fmt, ProfileStore.list(this).size)
        binding.rowNotifications.rowSubtitle.text = notificationSubtitle()
        binding.rowAppearance.rowSubtitle.text = themeSubtitle()
        binding.rowChat.rowSubtitle.text = chatBehaviorSubtitle()
        binding.rowSecurity.rowSubtitle.text = securitySubtitle()
    }

    private fun notificationSubtitle(): String {
        val muted = AppPrefs.mutedPackages(this).size
        return if (muted == 0) getString(R.string.notif_filter_all_forwarded)
        else getString(R.string.notif_filter_muted_fmt, muted)
    }

    private fun themeSubtitle(): String = getString(
        when (AppPrefs.theme(this)) {
            AppPrefs.THEME_LIGHT -> R.string.theme_light
            AppPrefs.THEME_DARK -> R.string.theme_dark
            else -> R.string.theme_system
        },
    )

    private fun chatBehaviorSubtitle(): String {
        val enabled = buildList {
            if (AppPrefs.typewriterEnabled(this@MainActivity)) add(getString(R.string.chat_behavior_typewriter_short))
            if (AppPrefs.hapticsEnabled(this@MainActivity)) add(getString(R.string.chat_behavior_haptics_short))
        }
        return if (enabled.isEmpty()) getString(R.string.chat_behavior_all_off)
        else enabled.joinToString(", ")
    }

    private fun showAppearanceDialog() {
        val values = arrayOf(AppPrefs.THEME_SYSTEM, AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK)
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_title)
            .setSingleChoiceItems(labels, values.indexOf(AppPrefs.theme(this))) { dialog, which ->
                dialog.dismiss()
                AppPrefs.setTheme(this, values[which])
                // Default-night-mode change recreates the activity itself.
                AppPrefs.applyTheme(this)
                refresh()
            }
            .show()
    }

    private fun showChatBehaviorDialog() {
        val binding = DialogChatBehaviorBinding.inflate(layoutInflater)
        binding.typewriterToggle.isChecked = AppPrefs.typewriterEnabled(this)
        binding.hapticsToggle.isChecked = AppPrefs.hapticsEnabled(this)
        binding.typewriterToggle.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setTypewriterEnabled(this, checked)
            refresh()
        }
        binding.hapticsToggle.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setHapticsEnabled(this, checked)
            refresh()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_behavior_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun securitySubtitle(): String {
        val s = SecurityStore.get(this)
        return getString(if (s.hasPin) R.string.security_subtitle_pin else R.string.security_subtitle_bio)
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun bindRow(row: ItemSettingsRowBinding, iconRes: Int, titleRes: Int): View {
        row.rowIcon.setImageResource(iconRes)
        row.rowIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary)
        row.rowTitle.setText(titleRes)
        return row.root
    }

    /**
     * R-10: the service starts only after the permission dialog has been
     * resolved (not fire-and-forget alongside it).
     */
    private fun startServiceAfterPermissions() {
        val missing = PERMISSIONS.filter {
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

    companion object {
        private const val REQUEST_CODE_PERMS = 42

        internal val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
