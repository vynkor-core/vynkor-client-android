package dev.vynkor.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.ProjectFilesStore
import dev.vynkor.agent.databinding.ActivityProjectFilesBinding

/**
 * Per-project file folder: files added here are copied into the app store and
 * injected as AI context into every chat of this project. Tap = preview via
 * an external viewer; trash icon or long-press = remove.
 */
class ProjectFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectFilesBinding
    private var profileId: String = ""
    private var projectId: String = ""

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            var added = 0
            uris.forEach { uri ->
                takePersistableGrant(uri)
                if (ProjectFilesStore.add(this, profileId, projectId, uri, null, null) != null) {
                    added++
                }
            }
            if (added == 0) snackText(getString(R.string.attachment_failed_copy_generic))
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProjectFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        binding.title.text = getString(
            R.string.project_files_title_fmt,
            projectName.ifBlank { getString(R.string.projects_title) },
        )

        binding.back.setOnClickListener { finish() }
        binding.addFiles.setOnClickListener { picker.launch(arrayOf("*/*")) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun takePersistableGrant(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun refresh() {
        val files = ProjectFilesStore.list(this, profileId, projectId)
        binding.hint.text =
            if (files.isEmpty()) getString(R.string.project_files_empty)
            else getString(R.string.context_included_note_fmt, files.size)
        binding.rows.removeAllViews()
        files.forEach { file -> binding.rows.addView(rowFor(file)) }
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun rowFor(file: ProjectFilesStore.ProjectFile): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (64 * density).toInt()
            setBackgroundResource(selectableBackground())
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { openViewer(file) }
            setOnLongClickListener { confirmDelete(file); true }
        }

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        meta.addView(
            TextView(this).apply {
                text = file.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
        )
        meta.addView(
            TextView(this).apply {
                text =
                    "${ProjectFilesStore.typeLabel(file.mime)} · ${ProjectFilesStore.humanSize(file.sizeBytes)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(ContextCompat.getColor(this@ProjectFilesActivity, R.color.on_surface_variant))
            },
        )
        row.addView(meta)

        row.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = getString(R.string.project_files_delete)
                background = null
                imageTintList =
                    ContextCompat.getColorStateList(this@ProjectFilesActivity, R.color.on_surface_variant)
                setOnClickListener { confirmDelete(file) }
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                    .apply { gravity = Gravity.CENTER_VERTICAL }
            },
        )
        return row
    }

    private fun openViewer(file: ProjectFilesStore.ProjectFile) {
        val target = ProjectFilesStore.fileFor(this, profileId, projectId, file)
        if (!target.exists()) {
            snack(R.string.file_view_failed)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(view) }.onFailure { snack(R.string.file_view_failed) }
    }

    private fun confirmDelete(file: ProjectFilesStore.ProjectFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.project_files_delete)
            .setMessage(getString(R.string.project_files_delete_confirm_fmt, file.name))
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                ProjectFilesStore.remove(this, profileId, projectId, file.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun snackText(text: String) {
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_PROJECT_NAME = "project_name"

        fun start(
            context: android.content.Context,
            profileId: String,
            projectId: String,
            projectName: String,
        ) {
            context.startActivity(
                Intent(context, ProjectFilesActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROJECT_ID, projectId)
                    .putExtra(EXTRA_PROJECT_NAME, projectName),
            )
        }
    }
}
