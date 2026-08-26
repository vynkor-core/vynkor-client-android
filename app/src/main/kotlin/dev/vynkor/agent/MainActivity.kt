package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.ChatBackup
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.SecurityStore
import dev.vynkor.agent.databinding.ActivityMainBinding
import dev.vynkor.agent.databinding.ItemSettingsRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings hub (ui-reference style): connection card on top, grouped rows
 * below — Hosts / Security / About. Hosts management and Security live on
 * their own screens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingServiceStart = false

    private val mainScanLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        if (result.contents == null) return@registerForActivityResult
        when (val decision = PairingApplier.handle(this, result.contents, external = false) { _ ->
            Toast.makeText(
                this,
                getString(R.string.paired_and_connected_short),
                Toast.LENGTH_SHORT,
            ).show()
            startServiceAfterPermissions()
            refresh()
        }) {
            is PairingApplier.Decision.Applied -> Unit
            is PairingApplier.Decision.PendingConfirmation -> Unit
            is PairingApplier.Decision.Rejected ->
                Toast.makeText(this, decision.reason, Toast.LENGTH_LONG).show()
        }
    }

    private val mainCameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            mainScanLauncher.launch(scanOptions())
        } else {
            Snackbar.make(
                findViewById(android.R.id.content),
                R.string.camera_denied,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * First launch with no host profile → onboarding wizard (IDEAS #6).
     * Skipped permanently once a profile exists or the user opted out.
     */
    private fun maybeLaunchWizard() {
        if (!AppPrefs.wizardCompleted(this) && ProfileStore.list(this).isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun scanOptions() = com.journeyapps.barcodescanner.ScanOptions()
        .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
        .setPrompt(getString(R.string.scan_prompt))
        .setBeepEnabled(false)
        .setOrientationLocked(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        maybeLaunchWizard()
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

        bindRow(binding.rowCaps, R.drawable.ic_lock, R.string.caps_permissions_title)
            .setOnClickListener { startActivity(Intent(this, CapPermissionsActivity::class.java)) }

        bindRow(binding.rowAppearance, R.drawable.ic_palette, R.string.appearance_title)
            .setOnClickListener { showAppearanceDialog() }

        bindRow(binding.rowLanguage, R.drawable.ic_hosts, R.string.language_title)
            .setOnClickListener { showLanguageDialog() }
        binding.rowLanguage.rowSubtitle.text = languageSubtitle()

        bindRow(binding.rowNotifLook, R.drawable.ic_notifications, R.string.notif_look_title)
            .setOnClickListener { showNotificationModeDialog() }
        binding.rowNotifLook.rowSubtitle.text = notifModeSubtitle()

        bindRow(binding.rowChat, R.drawable.ic_tune, R.string.chat_behavior_title)
            .setOnClickListener { startActivity(Intent(this, ChatSettingsActivity::class.java)) }

        bindRow(binding.rowSecurity, R.drawable.ic_lock, R.string.security_title)
            .setOnClickListener { startActivity(Intent(this, SecurityActivity::class.java)) }

        bindRow(binding.rowData, R.drawable.ic_data, R.string.data_title)
            .setOnClickListener { showDataDialog() }

        bindRow(binding.rowAbout, R.drawable.ic_info, R.string.about_row)
            .setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        binding.scanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                mainScanLauncher.launch(scanOptions())
            } else {
                mainCameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }

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
        binding.rowCaps.rowSubtitle.text = capsSubtitle()
        binding.rowAppearance.rowSubtitle.text = themeSubtitle()
        binding.rowChat.rowSubtitle.text = chatBehaviorSubtitle()
        binding.rowSecurity.rowSubtitle.text = securitySubtitle()
    }

    private fun notificationSubtitle(): String {
        val muted = AppPrefs.mutedPackages(this).size
        return if (muted == 0) getString(R.string.notif_filter_all_forwarded)
        else getString(R.string.notif_filter_muted_fmt, muted)
    }

    private fun capsSubtitle(): String {
        val runtimeCaps = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
        )
        val on = runtimeCaps.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        return getString(R.string.caps_subtitle_fmt, on, runtimeCaps.size)
    }

    /** Current in-app language or "system default". */
    private fun languageSubtitle(): String {
        val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        return if (appLocales.isEmpty) {
            getString(R.string.language_system)
        } else {
            // Native name ("Русский"), matching how OS pickers label locales.
            appLocales[0]?.getDisplayName(appLocales[0])
                ?: appLocales.toLanguageTags()
        }
    }

    /**
     * Per-app language via AppCompatDelegate (native on API 33+, backported
     * below via autoStoreLocales). Changing recreates all activities.
     */
    private fun showLanguageDialog() {
        val tags = listOf("", "en", "ru")
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_russian),
        )
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentTag = current.toLanguageTags()
        val checked = when {
            currentTag.startsWith("ru") -> 2
            currentTag.startsWith("en") -> 1
            else -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val target = if (tags[which].isEmpty()) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(tags[which])
                }
                if (target != current) {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(target)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun notifModeSubtitle(): String = getString(
        when (AppPrefs.notifMode(this)) {
            AppPrefs.NOTIF_MINIMAL -> R.string.notif_mode_minimal
            AppPrefs.NOTIF_HIDDEN -> R.string.notif_mode_hidden
            else -> R.string.notif_mode_detailed
        },
    )

    /** Detailed / minimal / hidden — applies to the running service at once. */
    private fun showNotificationModeDialog() {
        val modes = listOf(AppPrefs.NOTIF_DETAILED, AppPrefs.NOTIF_MINIMAL, AppPrefs.NOTIF_HIDDEN)
        val labels = modes.map { getString(notifLabel(it)) }.toTypedArray()
        val checked = modes.indexOf(AppPrefs.notifMode(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notif_look_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppPrefs.setNotifMode(this, modes[which])
                binding.rowNotifLook.rowSubtitle.text = notifModeSubtitle()
                dev.vynkor.agent.agent.AgentService.refreshNotification(this)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun notifLabel(mode: String): Int = when (mode) {
        AppPrefs.NOTIF_MINIMAL -> R.string.notif_mode_minimal
        AppPrefs.NOTIF_HIDDEN -> R.string.notif_mode_hidden
        else -> R.string.notif_mode_detailed
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
        val themes = arrayOf(AppPrefs.THEME_SYSTEM, AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK)
        val themeLabels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        val accents = arrayOf(
            AppPrefs.ACCENT_BLUE,
            AppPrefs.ACCENT_GREEN,
            AppPrefs.ACCENT_PURPLE,
            AppPrefs.ACCENT_ORANGE,
            AppPrefs.ACCENT_ROSE,
        )
        val accentLabels = arrayOf(
            getString(R.string.accent_blue),
            getString(R.string.accent_green),
            getString(R.string.accent_purple),
            getString(R.string.accent_orange),
            getString(R.string.accent_rose),
        )

        val container = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val themeTitle = android.widget.TextView(this).apply {
            setText(R.string.appearance_theme_section)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding((24 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        container.addView(themeTitle)
        val themeGroup = android.widget.RadioGroup(this)
        themes.forEachIndexed { i, value ->
            themeGroup.addView(android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = themeLabels[i]
                isChecked = AppPrefs.theme(this@MainActivity) == value
                setOnClickListener {
                    if (AppPrefs.theme(this@MainActivity) != value) {
                        AppPrefs.setTheme(this@MainActivity, value)
                        AppPrefs.applyTheme(this@MainActivity)
                        recreate()
                    }
                }
            })
        }
        container.addView(themeGroup)

        val accentTitle = android.widget.TextView(this).apply {
            setText(R.string.appearance_accent_section)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding((24 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        container.addView(accentTitle)
        val accentRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val accentColors = intArrayOf(
            R.color.primary,
            R.color.acc_green_primary,
            R.color.acc_purple_primary,
            R.color.acc_orange_primary,
            R.color.acc_rose_primary,
        )
        accents.forEachIndexed { i, value ->
            val selected = AppPrefs.accent(this) == value
            accentRow.addView(
                android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * resources.displayMetrics.density).toInt(),
                        (36 * resources.displayMetrics.density).toInt(),
                    ).apply {
                        marginEnd = (10 * resources.displayMetrics.density).toInt()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(
                            androidx.core.content.ContextCompat.getColor(
                                this@MainActivity,
                                accentColors[i],
                            ),
                        )
                        setStroke(
                            (if (selected) 6 else 2) * resources.displayMetrics.density.toInt().coerceAtLeast(1),
                            android.graphics.Color.WHITE,
                        )
                    }
                    contentDescription = accentLabels[i]
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (AppPrefs.accent(this@MainActivity) != value) {
                            AppPrefs.setAccent(this@MainActivity, value)
                            recreate()
                        }
                    }
                },
            )
        }
        container.addView(accentRow)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }


    // ---------------------------------------------------------------- data

    @Volatile
    private var pendingExportJson: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            Snackbar.make(
                findViewById(android.R.id.content),
                getString(if (ok) R.string.data_saved else R.string.data_export_failed),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) readBackupAndRestore(uri)
    }

    private fun showDataDialog() {
        val options = arrayOf(
            getString(R.string.data_export),
            getString(R.string.data_import),
            getString(R.string.data_clear_history),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.data_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startExport()
                    1 -> importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    2 -> confirmClearHistory()
                }
            }
            .show()
    }

    private fun startExport() {
        askPassword { password ->
            lifecycleScope.launch {
                pendingExportJson = withContext(Dispatchers.IO) {
                    runCatching {
                        ChatBackup.encryptToJson(ChatBackup.buildJson(this@MainActivity), password.toCharArray())
                    }.getOrNull()
                }
                if (pendingExportJson == null) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.data_export_failed,
                        Snackbar.LENGTH_LONG,
                    ).show()
                } else {
                    val stamp = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmm",
                        java.util.Locale.US,
                    ).format(java.util.Date())
                    exportLauncher.launch("vynkor-backup-$stamp.json")
                }
            }
        }
    }

    private fun readBackupAndRestore(uri: android.net.Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(R.string.data_import_bad_file)
                return@launch
            }
            askPassword { password ->
                lifecycleScope.launch {
                    val decrypted = withContext(Dispatchers.IO) {
                        ChatBackup.decryptToJson(text, password.toCharArray())
                    }
                    if (decrypted == null) {
                        toast(R.string.data_import_wrong_password)
                        return@launch
                    }
                    val stats = ChatBackup.peekStats(decrypted) ?: run {
                        toast(R.string.data_import_bad_file)
                        return@launch
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.data_import)
                        .setMessage(
                            getString(
                                R.string.data_restore_confirm_fmt,
                                stats.profiles,
                                stats.chats,
                                stats.projects,
                            ),
                        )
                        .setPositiveButton(R.string.replace_button) { _, _ ->
                            lifecycleScope.launch {
                                val done = withContext(Dispatchers.IO) {
                                    ChatBackup.restore(this@MainActivity, decrypted) != null
                                }
                                if (!done) {
                                    toast(R.string.data_import_bad_file)
                                    return@launch
                                }
                                // New profile set — drop the live connection.
                                AgentService.stop(this@MainActivity)
                                refresh()
                                toast(R.string.data_restored)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun confirmClearHistory() {
        val profiles = ProfileStore.list(this)
        val chats = profiles.sumOf { ChatStore.list(this, it.id).size }
        if (chats == 0) {
            toast(R.string.data_history_empty)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.data_clear_history)
            .setMessage(getString(R.string.data_clear_confirm_fmt, chats))
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                profiles.forEach { ChatStore.clear(this, it.id) }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askPassword(onOk: (String) -> Unit) {
        val input = EditText(this)
        input.hint = getString(R.string.password_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad / 2, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_title)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.length >= MIN_BACKUP_PASSWORD) onOk(input.text.toString())
                else toast(R.string.data_short_password)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun securitySubtitle(): String {
        val s = SecurityStore.get(this)
        return getString(if (s.hasPin) R.string.security_subtitle_pin else R.string.security_subtitle_bio)
    }


    private fun bindRow(row: ItemSettingsRowBinding, iconRes: Int, titleRes: Int): View {
        row.rowIcon.setImageResource(iconRes)
        row.rowIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                row.root,
                com.google.android.material.R.attr.colorPrimary,
            ),
        )
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
        private const val MIN_BACKUP_PASSWORD = 4

        internal val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
