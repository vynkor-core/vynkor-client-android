package dev.vynkor.agent

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.vynkor.agent.agent.AiAgent
import dev.vynkor.agent.agent.AiClient
import dev.vynkor.agent.agent.AiPresets
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.databinding.ActivityChatSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat behavior + AI target settings as a proper screen (was a dialog):
 * model and agent rows write into the active host profile, the switches
 * cover reply animation and haptics.
 */
class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatSettingsBinding
    private var profile: HostProfile? = null
    private var hostModels: List<dev.vynkor.agent.agent.AiModel> = emptyList()
    private var hostAgents: List<AiAgent> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        binding.back.setOnClickListener { finish() }
        profile = ProfileStore.active(this)

        binding.typewriterToggle.isChecked = AppPrefs.typewriterEnabled(this)
        binding.hapticsToggle.isChecked = AppPrefs.hapticsEnabled(this)
        binding.typewriterToggle.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setTypewriterEnabled(this, checked)
        }
        binding.hapticsToggle.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setHapticsEnabled(this, checked)
        }

        binding.rowModel.setOnClickListener { showModelPicker() }
        binding.rowAgent.setOnClickListener { showAgentPicker() }
        refreshValues()
        loadHostAiLists()
    }

    override fun onResume() {
        super.onResume()
        profile = ProfileStore.active(this) ?: profile
        refreshValues()
    }

    private fun refreshValues() {
        val active = profile ?: run {
            binding.modelValue.text = getString(R.string.no_profile)
            binding.agentValue.text = getString(R.string.no_profile)
            return
        }
        val model = active.effectiveModel().ifBlank {
            hostModels.firstOrNull { it.isDefault }?.id.orEmpty()
        }
        binding.modelValue.text =
            model.ifBlank { getString(R.string.model_not_set) }
        val agentId = active.aiAgent
        val agentName = hostAgents.firstOrNull { it.id == agentId }?.name
        binding.agentValue.text = when {
            agentName != null -> agentName
            agentId.isNotBlank() -> agentId
            else -> getString(R.string.agent_none)
        }
    }

    private fun loadHostAiLists() {
        val agent = AgentHolder.agent ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiClient.listModels(agent) to AiClient.listAgents(agent) }
            }
            result.onSuccess { (models, agents) ->
                if (models.isNotEmpty()) hostModels = models
                if (agents.isNotEmpty()) hostAgents = agents
                refreshValues()
            }
        }
    }

    private fun showModelPicker() {
        val active = profile ?: run { snack(R.string.not_connected); return }
        val models = hostModels.map { it.id }
            .ifEmpty { AiPresets.modelsFor(active.aiProvider) }
            .ifEmpty { listOf(active.effectiveModel()) }
        val labels = models + getString(R.string.custom_model)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_picker_title)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                models.indexOf(active.effectiveModel()),
            ) { dialog, which ->
                dialog.dismiss()
                if (which < models.size) {
                    saveModel(models[which])
                } else {
                    customModelDialog()
                }
            }
            .show()
    }

    private fun customModelDialog() {
        val active = profile ?: return
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.ai_model_hint)
            setText(active.aiModel)
            setSelection(text.length)
        }
        val holder = android.widget.FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_model)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveModel(input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveModel(modelId: String) {
        if (modelId.isBlank()) return
        val active = profile ?: return
        val updated = active.copy(aiModel = modelId)
        profile = updated
        ProfileStore.save(this, updated)
        refreshValues()
        Toast.makeText(this, getString(R.string.model_switched, modelId), Toast.LENGTH_SHORT).show()
    }

    private fun showAgentPicker() {
        if (hostAgents.isEmpty()) {
            snack(R.string.agents_unavailable_hint)
            return
        }
        val active = profile ?: return
        val labels = mutableListOf(getString(R.string.agent_none))
        labels.addAll(hostAgents.map { it.name })
        val ids = mutableListOf<String>("")
        ids.addAll(hostAgents.map { it.id })
        val current = ids.indexOf(active.aiAgent).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                val updated = active.copy(aiAgent = ids[which])
                profile = updated
                ProfileStore.save(this, updated)
                refreshValues()
            }
            .show()
    }

    private fun snack(messageRes: Int) {
        Snackbar.make(findViewById(android.R.id.content), messageRes, Snackbar.LENGTH_SHORT).show()
    }
}
