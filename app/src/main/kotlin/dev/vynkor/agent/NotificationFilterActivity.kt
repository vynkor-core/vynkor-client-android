package dev.vynkor.agent

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityNotificationFilterBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app notification forward filter: muted apps are NOT sent to the host.
 * Lists launchable apps; everything is forwarded unless muted here.
 */
class NotificationFilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationFilterBinding
    private lateinit var adapter: AppToggleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNotificationFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppToggleAdapter(onToggle = { row, muted ->
            AppPrefs.setMuted(this, row.packageName, muted)
            updateSubtitle()
        })
        binding.apps.layoutManager = LinearLayoutManager(this)
        binding.apps.adapter = adapter

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { loadApps() }
            adapter.submit(rows)
            updateSubtitle()
        }
    }

    private fun loadApps(): List<AppToggleRow> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrDefault(info.packageName)
                AppToggleRow(
                    packageName = info.packageName,
                    label = label,
                    icon = loadIconSafe(pm, info),
                    muted = AppPrefs.isMuted(this, info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadIconSafe(pm: PackageManager, info: android.content.pm.ApplicationInfo): Drawable? =
        runCatching { info.loadIcon(pm) }.getOrNull()

    private fun updateSubtitle() {
        val muted = AppPrefs.mutedPackages(this).size
        binding.subtitle.text =
            if (muted == 0) getString(R.string.notif_filter_all_forwarded)
            else getString(R.string.notif_filter_muted_fmt, muted)
        binding.subtitle.visibility = View.VISIBLE
    }
}
