package dev.vynkor.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.vynkor.agent.agent.AiContext
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.databinding.ActivityChatBinding
import dev.vynkor.agent.databinding.ItemProjectRowBinding
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AiAgent
import dev.vynkor.agent.agent.AiClient
import dev.vynkor.agent.agent.AiException
import dev.vynkor.agent.agent.AiModel
import dev.vynkor.agent.agent.AiPresets
import dev.vynkor.agent.agent.Attachment
import dev.vynkor.agent.agent.AttachmentStore
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.agent.ChatMessage
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.Project
import dev.vynkor.agent.agent.ProjectFilesStore
import dev.vynkor.agent.agent.ProjectStore
import dev.vynkor.agent.agent.SttEngine
import dev.vynkor.agent.agent.SttRecorder
import dev.vynkor.agent.agent.SttSession
import dev.vynkor.agent.agent.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var drawerAdapter: ChatListAdapter
    private lateinit var drawer: DrawerLayout
    private lateinit var binding: ActivityChatBinding
    private var profile: HostProfile? = null
    private lateinit var chat: Chat
    private var busy = false

    /** Set by the composer Stop button; the pending reply is then dropped. */
    @Volatile
    private var generationAborted = false
    private var tts: TtsEngine? = null

    private var hostModels: List<AiModel> = emptyList()
    private var hostAgents: List<AiAgent> = emptyList()

    private val recorder = SttRecorder()
    private var sttPending = false

    private val pendingAttachments = mutableListOf<Attachment>()

    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { addPendingAttachment(it, null, null) }
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris?.forEach { uri -> addPendingAttachment(uri, null, null) }
        }

    private var pendingCameraFile: java.io.File? = null

    private var pendingAutoAction: String? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCameraCapture() else snack(R.string.camera_denied)
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = pendingCameraFile
            pendingCameraFile = null
            if (ok && file != null && file.length() > 0) {
                addPendingAttachment(
                    Uri.fromFile(file),
                    displayName = "photo_${System.currentTimeMillis() / 1000}.jpg",
                    mimeHint = "image/jpeg",
                )
                runCatching { file.delete() }
            }
        }

    /** Set when the host-stream flow asked for RECORD_AUDIO and is waiting. */
    @Volatile
    private var pendingHostStream = false

    /** Set when [autoConnect] asked for permissions and waits for the result. */
    private var pendingServiceStart = false

    // Live-dictation state (offline model, emulated streaming).
    @Volatile
    private var sttSession: SttSession? = null
    private var partialJob: Job? = null
    private var sttDraftPrefix = ""

    /** Last draft the dictation loop wrote; detects manual edits (R-24). */
    @Volatile
    private var lastGeneratedDraft: String? = null

    /** Progressive reveal of the latest assistant reply (display-only). */
    private var typingJob: Job? = null

    /**
     * Unsent composer text per chat id — switching conversations no longer
     * loses what was typed. Bounded: oldest drafts evicted beyond the cap.
     */
    private val drafts = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > DRAFT_CACHE_LIMIT
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingHostStream) {
                    pendingHostStream = false
                    toggleHostStream()
                } else {
                    startDictation()
                }
            } else {
                pendingHostStream = false
                snack(R.string.mic_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetPadding()

        profile = ProfileStore.active(this)
        chat = Chat()
        pendingAutoAction = intent?.getStringExtra(EXTRA_AUTO_ACTION)

        drawer = binding.drawer
        // R-20 (№32): fixed 300dp was ~94% of a narrow screen — cap at 80%.
        val panel = binding.drawerPanel
        panel.layoutParams.width = minOf(
            (resources.displayMetrics.widthPixels * 8) / 10,
            (300 * resources.displayMetrics.density).toInt(),
        )

        // Opening the drawer (chats/settings) hides the keyboard right away.
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                ViewCompat.getWindowInsetsController(window.decorView)?.hide(WindowInsetsCompat.Type.ime())
            }
        })

        val toolbar = binding.toolbar
        refreshTitle()
        refreshModelChip()
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.setOnClickListener { showModelPicker() }

        val list = binding.messages
        val input = binding.input

        adapter = ChatAdapter(
            onUserLongPress = { message, anchor -> showUserMessageMenu(message, anchor) },
            onCopy = { copyMessage(it) },
            onMore = { message, anchor -> showAssistantMoreMenu(message, anchor) },
            onSpeak = { toggleSpeak(it) },
            onTypingTap = { skipTypewriter() },
            onAttachmentTap = { chatId, attachment -> openAttachment(chatId, attachment) },
        )
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        // Typewriter ticks rebind via payload; change cross-fades would flicker.
        (list.itemAnimator as? DefaultItemAnimator)?.apply {
            supportsChangeAnimations = false
            addDuration = 200
            changeDuration = 0
        }
        adapter.submit(chat.messages)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateScrollDownButton()
            }
        })
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                list.post { updateScrollDownButton() }
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                list.post { updateScrollDownButton() }
            }
        })
        binding.scrollDown.setOnClickListener {
            if (adapter.itemCount > 0) list.smoothScrollToPosition(adapter.itemCount - 1)
        }

        // Single composer action slot: mic when empty/dictating, send when
        // there is text to send. Same position, icon swaps.
        binding.composerAction.setOnClickListener {
            if (busy) {
                abortGeneration()
                return@setOnClickListener
            }
            val hasText = input.text?.isNotBlank() == true
            val hasAttachments = pendingAttachments.isNotEmpty()
            val dictating = recorder.isRecording() || sttPending || AgentHolder.micStreaming.value
            when {
                (hasText || hasAttachments) && !dictating -> sendMessage(input)
                else -> toggleDictation(input)
            }
        }
        binding.composerAction.setOnLongClickListener {
            pendingHostStream = false
            toggleHostStream()
            true
        }
        input.doAfterTextChanged { updateComposerButtons() }

        binding.attachButton.setOnClickListener { showAttachMenu() }

        binding.setUpHost.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.newChat.setOnClickListener {
            loadChat(null)
            drawer.closeDrawers()
        }
        projectsExpanded = AppPrefs.projectsExpanded(this)
        binding.addProject.setOnClickListener { showNewProjectDialog() }
        binding.projectsHeader.setOnClickListener { toggleProjects() }
        binding.settings.setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.lockNow.setOnClickListener {
            // Manual lock: drop the session flag and show the lock screen.
            AppLock.unlocked = false
            drawer.closeDrawers()
            startActivity(Intent(this, LockActivity::class.java))
        }

        val drawerChats = binding.drawerChats
        drawerAdapter = ChatListAdapter(
            onOpen = { chat, matchId -> openChatFromDrawer(chat, matchId) },
            onLongPress = { showChatMenu(it) },
        )
        drawerChats.layoutManager = LinearLayoutManager(this)
        drawerChats.adapter = drawerAdapter
        ChatSwipe.attach(
            drawerChats,
            chatAt = { pos ->
                (drawerAdapter.currentList.getOrNull(pos) as? DrawerItem.ChatEntry)?.row?.chat
            },
            onPin = { target ->
                profile?.let {
                    ChatStore.setPinned(this, it.id, target.id, !target.pinned)
                }
                refreshChatList()
            },
            onDeleteAsk = { confirmDelete(it) },
        )

        // Drawer search: title + message contents, composed with the
        // selected project chip inside refreshChatList().
        binding.drawerSearch.doAfterTextChanged { text ->
            searchQuery = text?.toString().orEmpty()
            refreshChatList()
        }
        binding.drawerSearch.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            false
        }

        updateHostState()
        refreshChatList()
        updateWelcome()
        if (intent?.getBooleanExtra(EXTRA_NEW_CHAT, false) == true &&
            savedInstanceState == null
        ) {
            chat = Chat()
            adapter.submit(chat.messages)
            refreshTitle()
            updateWelcome()
        }
        restoreState(savedInstanceState)
        intent?.getStringExtra(EXTRA_CHAT_ID)?.let { chatId ->
            profile?.let { p -> ChatStore.load(this, p.id, chatId) }?.let { loadChat(it) }
        }
        applySharedText(savedInstanceState == null)
        autoConnect()

        val welcomeChips = binding.welcomeChips
        listOf(
            R.string.welcome_suggest_1,
            R.string.welcome_suggest_2,
            R.string.welcome_suggest_3,
        ).forEach { res ->
            welcomeChips.addView(
                Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Suggestion)).apply {
                    text = getString(res)
                    isCheckable = false
                    setOnClickListener {
                        input.setText(text)
                        input.setSelection(input.text?.length ?: 0)
                    }
                }
            )
        }

        lifecycleScope.launch {
            AgentHolder.hostStatus.collect { st ->
                val colorRes = when (st) {
                    is HostStatus.Connected -> R.color.connected
                    is HostStatus.Connecting -> R.color.connecting
                    is HostStatus.Reconnecting -> R.color.connecting
                    is HostStatus.Unreachable -> R.color.unreachable
                    is HostStatus.Idle -> R.color.disconnected
                }
                binding.drawerStatusDot.setTextColor(ContextCompat.getColor(this@ChatActivity, colorRes))
                binding.drawerStatus.text =
                    if (st is HostStatus.Unreachable) getString(R.string.status_unreachable_fmt, st.reason.take(48))
                    else getString(
                        when (st) {
                            is HostStatus.Connected -> R.string.status_connected
                            is HostStatus.Connecting -> R.string.status_connecting
                            is HostStatus.Reconnecting -> R.string.status_reconnecting
                            else -> R.string.status_disconnected
                        }
                    )
                updateToolbarProgress()
                if (st is HostStatus.Connected) refreshHostAi()
            }
        }

        lifecycleScope.launch {
            AgentHolder.micStreaming.collect { streaming ->
                val listeningView = binding.listening
                if (streaming) {
                    listeningView.setText(R.string.mic_host_listening)
                    listeningView.visibility = View.VISIBLE
                } else if (!recorder.isRecording() && !sttPending) {
                    listeningView.visibility = View.GONE
                }
                updateComposerButtons()
            }
        }

        val engine = TtsEngine(this)
        engine.onDone = {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideTtsPill()
                adapter.setSpeaking(null)
                // Conversation mode: reply was read aloud — listen again.
                if (AppPrefs.convMode(this)) {
                    val idle = !busy &&
                        AgentHolder.agent != null &&
                        !recorder.isRecording() &&
                        !sttPending &&
                        !AgentHolder.micStreaming.value
                    if (idle) startDictation()
                }
            }
        }
        engine.onInitFailed = {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                snack(R.string.tts_unavailable)
            }
        }
        tts = engine
        binding.ttsStop.setOnClickListener { stopSpeaking() }
    }

    override fun onStart() {
        super.onStart()
        // App entry gate: fingerprint/device credential on cold start AND
        // after >= configured minutes in background (auto-relock).
        applyBiometricGate()
    }

    override fun onResume() {
        super.onResume()
        tryRunAutoAction()
        val current = ProfileStore.active(this)
        if (current?.id != profile?.id) {
            profile = current
            // Different host — its chats have nothing to do with the query.
            searchQuery = ""
            binding.drawerSearch.setText("")
            loadChat(null)
        }
        refreshModelChip()
        refreshAgentChip()
        updateHostState()
        refreshChatList()
        updateWelcome()
        autoConnect()
    }

    /** R-18: rotation recreates the activity — keep the open chat + draft. */
    private fun restoreState(state: Bundle?) {
        state?.getString(STATE_CHAT_ID)?.let { chatId ->
            profile?.let { p -> ChatStore.load(this, p.id, chatId) }?.let { loadChat(it) }
        }
        state?.getString(STATE_DRAFT)?.let { draft ->
            binding.input.setText(draft)
            binding.input.setSelection(draft.length)
        }
        state?.getString(STATE_SEARCH)?.let { query ->
            // setText fires the watcher → refreshChatList() with the query.
            binding.drawerSearch.setText(query)
            binding.drawerSearch.setSelection(query.length)
        }
    }

    /** Share-into-app: text shared from other apps lands in the composer.
     *  Cold start only — a recreated activity must keep its restored draft. */
    private fun applySharedText(coldStart: Boolean) {
        if (!coldStart || intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (shared.isEmpty()) return
        binding.input.setText(shared)
        binding.input.setSelection(shared.length)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT, binding.input.text?.toString())
        if (chat.messages.isNotEmpty()) outState.putString(STATE_CHAT_ID, chat.id)
        if (searchQuery.isNotBlank()) outState.putString(STATE_SEARCH, searchQuery)
    }

    override fun onDestroy() {
        super.onDestroy()
        skipTypewriter()
        partialJob?.cancel()
        partialJob = null
        sttSession = null
        discardPendingAttachments()
        if (recorder.isRecording()) {
            recorder.stop()
        }
        sttPending = false
        if (AgentHolder.micStreaming.value) {
            // Leaving the chat ends the user's explicit streaming context.
            Thread({ AgentHolder.micSession?.stopSession("chat closed") }, "mic-session-stop")
                .apply { isDaemon = true }
                .start()
        }
        tts?.shutdown()
        tts = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NEW_CHAT, false)) {
            loadChat(null)
        }
        intent.getStringExtra(EXTRA_AUTO_ACTION)?.let { pendingAutoAction = it }
        tryRunAutoAction()
    }

    /**
     * Widget tiles land here; the action fires only after the biometric gate
     * (AppLock.unlocked) so mic/camera never run behind the lock screen.
     */
    private fun tryRunAutoAction() {
        if (!AppLock.unlocked) return
        val action = pendingAutoAction ?: return
        pendingAutoAction = null
        when (action) {
            AUTO_VOICE -> toggleDictation(binding.input)
            AUTO_CAMERA -> ensureCameraPermissionThenCapture()
        }
    }

    private fun refreshTitle() {
        binding.toolbar.title =
            chat.title.ifBlank { getString(R.string.new_chat) }
    }

    private fun refreshModelChip() {
        val active = profile
        val model = active?.effectiveModel()?.takeIf { it.isNotBlank() }
            ?: hostModels.firstOrNull { it.isDefault }?.id
            ?: ""
        binding.toolbar.subtitle = model.ifBlank { null }
    }

    private fun refreshAgentChip() {
        if (hostAgents.isEmpty()) return
        val agentId = profile?.aiAgent.orEmpty()
        val name = hostAgents.firstOrNull { it.id == agentId }?.name
            ?: hostAgents.firstOrNull { it.isDefault }?.name
            ?: agentId.ifBlank { getString(R.string.agent_fallback) }
        val model = profile?.effectiveModel().orEmpty()
        binding.toolbar.subtitle =
            if (model.isBlank()) name else "$model · $name"
    }

    /** Pull the host's model/agent lists (list_models/list_agents). */
    private fun refreshHostAi() {
        val agent = AgentHolder.agent ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiClient.listModels(agent) to AiClient.listAgents(agent) }
            }
            result.onSuccess { (models, agents) ->
                if (models.isNotEmpty()) hostModels = models
                if (agents.isNotEmpty()) hostAgents = agents
                refreshModelChip()
                refreshAgentChip()
            }.onFailure { e ->
                // R-24: silent getOrDefault left the user staring at stale/empty
                // lists with no hint why.
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.host_ai_unavailable, e.message ?: ""),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.retry) { refreshHostAi() }.show()
            }
        }
    }

    private fun updateHostState() {
        val hasProfile = profile != null
        binding.emptyState.visibility =
            if (hasProfile) View.GONE else View.VISIBLE
        binding.composerCard.visibility =
            if (hasProfile) View.VISIBLE else View.GONE
        if (!hasProfile) {
            binding.typing.visibility = View.GONE
            binding.listening.visibility = View.GONE
        }
    }

    private fun refreshChatList() {
        val active = profile
        binding.drawerProfileName.text =
            if (active == null) getString(R.string.no_profile)
            else active.name.ifBlank { getString(R.string.unnamed_profile) }
        binding.drawerProfileName.setOnClickListener { showHostSwitcher() }
        renderProjectRows()
        val query = searchQuery.trim()
        val filtered = if (active == null || query.isEmpty()) {
            if (active == null) emptyList() else ChatStore.list(this, active.id)
        } else {
            ChatStore.search(this, active.id, query)
        }
        binding.searchEmpty.visibility =
            if (query.isNotEmpty() && filtered.isEmpty()) View.VISIBLE else View.GONE
        drawerAdapter.submit(filtered, query = query.ifEmpty { null })
    }

    /**
     * Tap the profile name in the drawer: pick another paired host without
     * leaving the chat. Switching stops the old agent and starts a new one
     * bound to the selected profile.
     */
    private fun showHostSwitcher() {
        val profiles = ProfileStore.list(this)
        if (profiles.isEmpty()) {
            startActivity(Intent(this, HostsActivity::class.java))
            return
        }
        val activeId = profile?.id
        val labels = mutableListOf(getString(R.string.hosts_manage_row)).also { list ->
            profiles.forEach { list += it.name.ifBlank { getString(R.string.unnamed_profile) } }
        }
        val ids = mutableListOf<String?>("manage").also { list ->
            profiles.forEach { list += it.id }
        }
        val checked = ids.indexOf(activeId).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hosts_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when (val id = ids[which]) {
                    "manage" -> startActivity(Intent(this, HostsActivity::class.java))
                    null -> Unit
                    else -> {
                        if (id != activeId) switchHost(id)
                    }
                }
            }
            .show()
    }

    private fun switchHost(profileId: String) {
        ProfileStore.setActive(this, profileId)
        profile = ProfileStore.active(this)
        AgentService.stop(this)
        searchQuery = ""
        binding.drawerSearch.setText("")
        loadChat(null)
        refreshModelChip()
        refreshAgentChip()
        updateHostState()
        refreshChatList()
        autoConnect()
    }

    // ------------------------------------------------------------- projects

    /** Drawer chat-search text; blank = search off (plain list). */
    private var searchQuery: String = ""

    /** Message to scroll to + pulse after the next loadChat (search open). */
    private var pendingHighlightMessageId: String? = null

    /** Projects section fold state survives restarts; read in onCreate. */
    private var projectsExpanded: Boolean = true

    private fun toggleProjects() {
        projectsExpanded = !projectsExpanded
        AppPrefs.setProjectsExpanded(this, projectsExpanded)
        renderProjectRows()
    }

    private fun renderProjectRows() {
        val active = profile
        val projects = if (active == null) emptyList() else ProjectStore.list(this, active.id)
        val inflater = LayoutInflater.from(this)
        binding.projectsTitle.text = getString(R.string.projects_title)
        binding.projectsCount.text = if (projects.isEmpty()) "" else getString(
            R.string.projects_count_fmt,
            projects.size,
        )
        binding.projectsArrow.animate().rotation(if (projectsExpanded) 180f else 0f).setDuration(150).start()
        binding.projectRows.visibility = if (projectsExpanded) View.VISIBLE else View.GONE
        binding.projectRows.removeAllViews()
        projects.forEach { project ->
            val row = ItemProjectRowBinding.inflate(inflater, binding.projectRows, false)
            row.projectName.text = project.name.ifBlank { getString(R.string.unnamed_profile) }
            val chats = ChatStore.list(this, active?.id.orEmpty()).count { it.projectId == project.id }
            row.projectCount.text = chats.toString()
            row.root.setOnClickListener {
                startActivity(ProjectChatsActivity.intent(this, active?.id.orEmpty(), project.id, project.name))
            }
            row.root.setOnLongClickListener { showProjectMenu(project); true }
            binding.projectRows.addView(row.root)
        }
    }

    /** Long-press on a project row: files / rename / delete. */
    private fun showProjectMenu(project: dev.vynkor.agent.agent.Project) {
        val active = profile ?: return
        val options = arrayOf(
            getString(R.string.project_files_menu),
            getString(R.string.rename_chat),
            getString(R.string.delete_chat),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(project.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ProjectFilesActivity.start(this, active.id, project.id, project.name)
                    1 -> {
                        val input = EditText(this)
                        input.hint = getString(R.string.project_name_hint)
                        input.setText(project.name)
                        val holder = FrameLayout(this)
                        val pad = (24 * resources.displayMetrics.density).toInt()
                        holder.setPadding(pad, pad, pad, 0)
                        holder.addView(input)
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.rename_project)
                            .setView(holder)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotBlank()) {
                                    ProjectStore.rename(this, active.id, project.id, name)
                                    refreshChatList()
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_project)
                        .setMessage(R.string.delete_project_confirm)
                        .setPositiveButton(R.string.delete_chat) { _, _ ->
                            ProjectStore.delete(this, active.id, project.id)
                            ProjectFilesStore.deleteProjectDir(this, active.id, project.id)
                            ChatStore.clearProject(this, active.id, project.id)
                            refreshChatList()
                            refreshTitle()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showNewProjectDialog() {
        val active = profile ?: return
        val input = EditText(this)
        input.hint = getString(R.string.project_name_hint)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_project)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    ProjectStore.save(this, active.id, Project(name = name))
                    projectsExpanded = true
                    AppPrefs.setProjectsExpanded(this, true)
                    refreshChatList()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateWelcome() {
        val show = profile != null && chat.messages.isEmpty()
        binding.welcomeState.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.welcomeGreeting.text = "${greetingText()}, ${profile?.name?.ifBlank { null } ?: "vynkor"}"
        }
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return getString(
            when (hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                in 18..22 -> R.string.greeting_evening
                else -> R.string.greeting_night
            },
        )
    }
    private fun showAttachMenu() {
        val popup = PopupMenu(this, binding.attachButton)
        popup.menu.add(0, 1, 0, R.string.attach_camera_photo)
        popup.menu.add(0, 2, 1, R.string.attach_photo)
        popup.menu.add(0, 3, 2, R.string.attach_file)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    ensureCameraPermissionThenCapture()
                    true
                }
                2 -> {
                    mediaPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                    true
                }
                3 -> {
                    filePicker.launch(arrayOf("*/*"))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * The manifest declares CAMERA, so ACTION_IMAGE_CAPTURE needs the runtime
     * grant on modern Android even though the system camera app does the work.
     */
    private fun ensureCameraPermissionThenCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCapture() {
        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
        val target = java.io.File(dir, "pending_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = target
        val ok = runCatching {
            cameraLauncher.launch(
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    target,
                ),
            )
        }
        if (ok.isFailure) {
            pendingCameraFile = null
            snack(R.string.camera_capture_failed)
        }
    }

    private fun addPendingAttachment(uri: Uri, displayName: String?, mimeHint: String?) {
        val chatId = chat.id
        val attachment = AttachmentStore.copyIn(this, chatId, uri, displayName, mimeHint)
        if (attachment == null) {
            snack(R.string.attachment_failed_copy_generic)
            return
        }
        pendingAttachments.add(attachment)
        renderPendingAttachmentChips()
    }

    private fun renderPendingAttachmentChips() {
        val group: ChipGroup = binding.pendingAttachments
        group.removeAllViews()
        pendingAttachments.forEach { attachment ->
            val chip = Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Input))
            chip.text = "${attachment.name} · ${attachment.humanSize()}"
            chip.isCloseIconVisible = true
            chip.closeIconContentDescription = getString(R.string.attachment_remove)
            chip.setOnCloseIconClickListener {
                AttachmentStore.deleteAll(this, chat.id, listOf(attachment))
                pendingAttachments.remove(attachment)
                renderPendingAttachmentChips()
            }
            group.addView(chip)
        }
        updateComposerButtons()
    }

    private fun clearPendingAttachmentChips() {
        pendingAttachments.clear()
        binding.pendingAttachments.removeAllViews()
        updateComposerButtons()
    }

    private fun discardPendingAttachments() {
        AttachmentStore.deleteAll(this, chat.id, pendingAttachments.toList())
        clearPendingAttachmentChips()
    }

    private fun openAttachment(chatId: String, attachment: Attachment) {
        val intent = AttachmentStore.viewIntent(this, chatId, attachment)
        if (intent == null) {
            snack(R.string.file_view_failed)
            return
        }
        runCatching { startActivity(intent) }.onFailure { snack(R.string.file_view_failed) }
    }

    /** Tries to reach the last active host as soon as the app opens. */
    private fun autoConnect() {
        if (profile != null && AgentHolder.agent == null) {
            startServiceAfterPermissions()
        }
    }

    /**
     * R-10: the service starts only after the permission dialog has been
     * resolved (not fire-and-forget alongside it). Providers re-check grants
     * per call, so a partially-granted set degrades gracefully.
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

    private fun loadChat(loaded: Chat?) {
        skipTypewriter()
        drafts[chat.id] = binding.input.text?.toString().orEmpty()
        discardPendingAttachments()
        // A fresh chat lands in the default inbox ("No project"); project
        // chats are created from the project screen.
        chat = loaded ?: Chat()
        adapter.chatId = chat.id
        adapter.submit(chat.messages)
        refreshTitle()
        updateWelcome()
        drawer.closeDrawers()
        val draft = drafts[chat.id].orEmpty()
        if (binding.input.text?.toString() != draft) {
            binding.input.setText(draft)
            binding.input.setSelection(draft.length)
        }
        consumePendingHighlight()
    }

    /** Opens a chat from search results, aiming at the matched message. */
    private fun openChatFromDrawer(target: Chat, scrollToMessageId: String?) {
        pendingHighlightMessageId = scrollToMessageId
        loadChat(target)
    }

    private fun consumePendingHighlight() {
        val id = pendingHighlightMessageId ?: return
        pendingHighlightMessageId = null
        val list = binding.messages
        list.post {
            val idx = adapter.currentList.indexOfFirst { it.id == id }
            if (idx < 0) return@post
            (list.layoutManager as? LinearLayoutManager)?.scrollToPosition(idx)
            list.post {
                val vh = list.findViewHolderForAdapterPosition(idx) ?: return@post
                vh.itemView.animate()
                    .scaleX(PULSE_SCALE)
                    .scaleY(PULSE_SCALE)
                    .setDuration(150)
                    .withEndAction {
                        vh.itemView.animate().scaleX(1f).scaleY(1f).duration = 250
                    }
            }
        }
    }

    private fun showChatMenu(target: Chat) {
        val options = arrayOf(
            getString(if (target.pinned) R.string.unpin_chat else R.string.pin_chat),
            getString(R.string.rename_chat),
            getString(R.string.chat_duplicate),
            getString(R.string.move_to_project),
            getString(R.string.export_chat),
            getString(R.string.delete_chat),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(target.title.ifBlank { getString(R.string.new_chat) })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        profile?.let {
                            ChatStore.setPinned(this, it.id, target.id, !target.pinned)
                        }
                        refreshChatList()
                    }
                    1 -> renameChat(target)
                    2 -> duplicateChat(target)
                    3 -> moveToProjectDialog(target)
                    4 -> exportChat(target)
                    5 -> confirmDelete(target)
                }
            }
            .show()
    }

    /** Twin of [target]: same project and content, fresh ids everywhere. */
    private fun duplicateChat(target: Chat) {
        val active = profile ?: return
        if (ChatStore.cloneChat(this, active.id, target.id) != null) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                R.string.chat_duplicated,
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
            ).show()
        }
        refreshChatList()
    }

    private fun exportChat(target: Chat) {
        val title = target.title.ifBlank { getString(R.string.new_chat) }
        val markdown = buildString {
            append("# ").appendLine(title)
            target.messages.forEach { message ->
                appendLine()
                val who = when (message.role) {
                    "user" -> getString(R.string.export_role_user)
                    "error" -> getString(R.string.export_role_error)
                    else -> getString(R.string.export_role_ai)
                }
                appendLine("**$who:**")
                appendLine()
                appendLine(message.content)
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_chat)))
    }

    private fun moveToProjectDialog(target: Chat) {
        val active = profile ?: return
        val projects = ProjectStore.list(this, active.id)
        val labels = mutableListOf(getString(R.string.no_project_item))
        labels.addAll(projects.map { it.name })
        val ids = mutableListOf<String?>(null)
        ids.addAll(projects.map { it.id })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.move_to_project)
            .setItems(labels.toTypedArray()) { _, which ->
                ChatStore.moveToProject(this, active.id, target.id, ids[which])
                if (target.id == chat.id) chat = chat.copy(projectId = ids[which].orEmpty())
                refreshChatList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameChat(target: Chat) {
        val input = EditText(this)
        input.hint = getString(R.string.rename_chat_hint)
        input.setText(target.title)
        input.setSelection(input.text.length)
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_chat)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                profile?.let { ChatStore.rename(this, it.id, target.id, title) }
                if (target.id == chat.id) {
                    chat = chat.copy(title = title)
                    refreshTitle()
                }
                refreshChatList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(target: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(R.string.delete_chat_confirm)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                profile?.let { ChatStore.delete(this, it.id, target.id) }
                if (target.id == chat.id) {
                    loadChat(null)
                }
                refreshChatList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ send

    private fun sendMessage(input: EditText) {
        val text = input.text?.toString()?.trim().orEmpty()
        if (busy) return
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        skipTypewriter()
        input.setText("")

        aiConfigErrorOrNull()?.let {
            appendMessage(ChatMessage("error", it))
            return
        }

        val attachments = pendingAttachments.toList()
        clearPendingAttachmentChips()
        appendMessage(ChatMessage("user", text, attachments = attachments))
        hapticTick()
        if (chat.title.isBlank()) {
            chat = ChatStore.autoTitle(chat)
            profile?.let { ChatStore.save(this, it.id, chat) }
            refreshChatList()
            refreshTitle()
        }

        requestCompletion()
    }

    /** Null when a completion request may go out, error text otherwise. */
    private fun aiConfigErrorOrNull(): String? {
        val active = profile ?: return getString(R.string.not_connected)
        if (AgentHolder.agent == null) return getString(R.string.not_connected)
        val useAgent = active.aiAgent.isNotBlank()
        return if (!useAgent && (active.effectiveModel().isBlank() || active.aiApiKeyEnv.isBlank())) {
            getString(R.string.ai_not_configured)
        } else {
            null
        }
    }

    private fun requestCompletion() {
        if (busy) return
        val active = profile ?: return
        val agent = AgentHolder.agent ?: return

        busy = true
        generationAborted = false
        setBusyUi(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // R-05: send a bounded history window — a long chat must not
                // blow the frame payload budget with every message.
                val history = chat.messages.takeLast(HISTORY_WINDOW)
                    .map { it.role to it.content }
                val contextBlock = buildContextBlock(active.id)
                val messages = if (contextBlock != null) {
                    listOf("system" to contextBlock) + history
                } else {
                    history
                }
                runCatching { AiClient.chat(agent, active, messages) }
            }
            if (generationAborted) {
                // User stopped waiting; the answer is dropped on the floor.
                busy = false
                setBusyUi(false)
                return@launch
            }
            result.onSuccess { reply ->
                val replyMessage = ChatMessage("assistant", reply.content)
                appendMessage(replyMessage)
                typewriterReveal(replyMessage)
            }.onFailure { e ->
                val message = when (e) {
                    is AiException -> e.message ?: getString(R.string.ai_error)
                    else -> e.message ?: getString(R.string.ai_error)
                }
                appendMessage(ChatMessage("error", message))
            }
            busy = false
            setBusyUi(false)
        }
    }

    /**
     * Composer Stop: unlock the UI immediately and drop the reply when it
     * eventually arrives. The request itself still completes host-side —
     * a real cancel needs the kernel-side chat.cancel (see
     * docs/CLIENT_DRIVEN_KERNEL_TASKS.md).
     */
    private fun abortGeneration() {
        if (!busy) return
        generationAborted = true
        busy = false
        setBusyUi(false)
        snack(R.string.generation_stopped)
    }

    /** System-role block: project name + project files + last user attachments. */
    private fun buildContextBlock(profileId: String): String? {
        val projectId = chat.projectId.takeIf { it.isNotBlank() }
        val sources = mutableListOf<AiContext.Source>()
        sources += ProjectFilesStore.contextSources(this, profileId, projectId)
        chat.messages.lastOrNull { it.role == "user" && it.attachments.isNotEmpty() }
            ?.attachments
            ?.forEach { attachment ->
                sources += AiContext.Source(
                    name = attachment.name,
                    typeLabel = attachmentTypeLabel(attachment),
                    sizeLabel = attachment.humanSize(),
                    textContent = AttachmentStore.readTextForContext(this, chat.id, attachment),
                )
            }
        val projectName = projectId?.let { pid ->
            ProjectStore.list(this, profileId).firstOrNull { it.id == pid }?.name
        }
        return AiContext.buildBlock(projectName, sources)
    }

    private fun attachmentTypeLabel(attachment: Attachment): String = when {
        attachment.isImage -> "photo"
        attachment.isVideo -> "video"
        else -> "file"
    }

    private fun appendMessage(message: ChatMessage) {
        val updated = chat.copy(
            messages = chat.messages + message,
            updatedAt = System.currentTimeMillis(),
        )
        chat = updated
        adapter.append(message)
        profile?.let { ChatStore.save(this, it.id, updated) }
        updateWelcome()
        refreshChatList()
    }

    // -------------------------------------------------- message operations

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        skipTypewriter()
        val updated = chat.copy(
            messages = transform(chat.messages),
            updatedAt = System.currentTimeMillis(),
        )
        chat = updated
        adapter.submit(updated.messages)
        profile?.let { ChatStore.save(this, it.id, updated) }
        updateWelcome()
        refreshChatList()
    }

    private fun editMessage(message: ChatMessage) {
        val input = EditText(this)
        input.hint = getString(R.string.edit_message_hint)
        input.setText(message.content)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_message)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                stopSpeaking()
                // timestamp bump so DiffUtil's content check sees the change
                updateMessages { list ->
                    list.map {
                        if (it.id == message.id) {
                            it.copy(content = text, timestamp = System.currentTimeMillis())
                        } else {
                            it
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteMessage(message: ChatMessage) {
        stopSpeaking()
        updateMessages { list -> list.filterNot { it.id == message.id } }
    }

    /** Drops the reply and everything after it, then re-asks with the rest. */
    private fun regenerateAt(message: ChatMessage) {
        if (busy || message.role != "assistant") return
        aiConfigErrorOrNull()?.let {
            appendMessage(ChatMessage("error", it))
            return
        }
        stopSpeaking()
        val idx = chat.messages.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        updateMessages { it.take(idx) }
        requestCompletion()
    }

    private fun typewriterReveal(message: ChatMessage) {
        if (message.role != "assistant") return
        if (!AppPrefs.typewriterEnabled(this)) return
        typingJob?.cancel()
        adapter.startTyping(message)
        val step = maxOf(1, message.content.length / TYPEWRITER_TICKS)
        typingJob = lifecycleScope.launch {
            while (isActive) {
                delay(TYPEWRITER_INTERVAL_MS)
                if (adapter.stepTyping(message.id, step)) break
            }
            adapter.finishTyping()
            typingJob = null
        }
    }

    private fun skipTypewriter() {
        typingJob?.cancel()
        typingJob = null
        adapter.finishTyping()
    }

    private fun setBusyUi(b: Boolean) {
        binding.typing.visibility = if (b) View.VISIBLE else View.GONE
        updateToolbarProgress()
        updateComposerButtons()
    }

    /** R-14: one bar covers both long operations — AI request and connecting. */
    private fun updateToolbarProgress() {
        val connecting = AgentHolder.agent != null && !AgentHolder.connectionState.value
        binding.toolbarProgress.visibility =
            if (busy || connecting) View.VISIBLE else View.GONE
    }

    private fun updateScrollDownButton() {
        val lm = binding.messages.layoutManager as? LinearLayoutManager
        val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        binding.scrollDown.visibility =
            if (adapter.itemCount > 0 && last != RecyclerView.NO_POSITION &&
                last < adapter.itemCount - 1
            ) View.VISIBLE else View.GONE
    }

    private fun hapticTick() {
        if (AppPrefs.hapticsEnabled(this)) {
            binding.composerAction.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Single composer action (ui-reference: send swaps with mic in one slot).
     * Text present and idle → filled send; otherwise mic (red while listening).
     */
    private fun updateComposerButtons() {
        val hasText = binding.input.text?.isNotBlank() == true
        val hasAttachments = pendingAttachments.isNotEmpty()
        val dictating = recorder.isRecording() || sttPending || AgentHolder.micStreaming.value
        val action = binding.composerAction
        if (busy) {
            action.setIconResource(R.drawable.ic_stop)
            action.setIconTintResource(R.color.on_primary)
            action.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        binding.composerCard,
                        com.google.android.material.R.attr.colorError,
                    ),
                )
            action.contentDescription = getString(R.string.stop_button)
            return
        }
        if ((hasText || hasAttachments) && !dictating) {
            action.setIconResource(R.drawable.ic_send)
            action.setIconTintResource(R.color.on_primary)
            action.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        binding.composerCard,
                        com.google.android.material.R.attr.colorPrimary,
                    ),
                )
            action.contentDescription = getString(R.string.send_button)
        } else {
            action.setIconResource(R.drawable.ic_mic)
            if (dictating) {
                action.setIconTintResource(R.color.error)
            } else {
                action.iconTint = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        action,
                        com.google.android.material.R.attr.colorPrimary,
                    ),
                )
            }
            action.backgroundTintList = null
            action.contentDescription = getString(R.string.mic_button)
        }
    }

    // ----------------------------------------------------- model switcher

    private fun showModelPicker() {
        val active = profile ?: return
        val models = hostModels.map { it.id }
            .ifEmpty { AiPresets.modelsFor(active.aiProvider) }
            .ifEmpty { listOf(active.effectiveModel()) }
        val labels = models + getString(R.string.custom_model)
        val current = active.effectiveModel()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), models.indexOf(current)) { dialog, which ->
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
        val input = EditText(this).apply {
            hint = getString(R.string.ai_model_hint)
            setText(active.aiModel)
            setSelection(text.length)
        }
        val holder = FrameLayout(this).apply {
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
        refreshModelChip()
        Toast.makeText(this, getString(R.string.model_switched, modelId), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------- message actions

    private fun showUserMessageMenu(message: ChatMessage, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.copy_message)
        popup.menu.add(0, 2, 1, R.string.speak_message)
        popup.menu.add(0, 3, 2, R.string.edit_message)
        popup.menu.add(0, 4, 3, R.string.delete_message)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    copyMessage(message)
                    true
                }
                2 -> {
                    toggleSpeak(message)
                    true
                }
                3 -> {
                    editMessage(message)
                    true
                }
                4 -> {
                    deleteMessage(message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAssistantMoreMenu(message: ChatMessage, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.message_fork)
        popup.menu.add(0, 2, 1, R.string.regenerate_reply)
        popup.menu.add(0, 3, 2, R.string.delete_message)
        // Future plugin actions — reserved, disabled for now.
        popup.menu.add(0, 4, 3, R.string.message_gmail_draft).isEnabled = false
        popup.menu.add(0, 5, 4, R.string.message_export_docs).isEnabled = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    forkBranchAt(message)
                    true
                }
                2 -> {
                    regenerateAt(message)
                    true
                }
                3 -> {
                    deleteMessage(message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun forkBranchAt(message: ChatMessage) {
        val active = profile ?: return
        val idx = chat.messages.indexOfFirst { it.id == message.id }
        if (idx < 0) return
        val branch = ChatStore.autoTitle(
            Chat(messages = chat.messages.take(idx + 1)),
        )
        ChatStore.save(this, active.id, branch)
        loadChat(branch)
        Toast.makeText(this, R.string.fork_created, Toast.LENGTH_SHORT).show()
    }

    private fun copyMessage(message: ChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_message), message.content))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeak(message: ChatMessage) {
        val engine = tts ?: return
        if (!engine.isReady()) {
            snack(R.string.tts_unavailable)
            return
        }
        if (engine.isSpeaking()) {
            stopSpeaking()
        } else {
            engine.speak(message.content)
            binding.ttsPill.visibility = View.VISIBLE
            adapter.setSpeaking(message)
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        hideTtsPill()
        adapter.setSpeaking(null)
    }

    private fun hideTtsPill() {
        binding.ttsPill.visibility = View.GONE
    }

    // ------------------------------------------------------------- dictation

    /**
     * Long-press mic: explicit host-stream session (R-01). The mic leaves the
     * phone only while this session is live; teardown joins a reader thread,
     * so it runs off the main thread.
     */
    private fun toggleHostStream() {
        val controller = AgentHolder.micSession
        if (controller == null || AgentHolder.agent == null || !AgentHolder.connectionState.value) {
            snack(R.string.not_connected)
            return
        }
        if (AgentHolder.micStreaming.value) {
            lifecycleScope.launch(Dispatchers.Default) { controller.stopSession("user") }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingHostStream = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val started = controller.startSession(applicationContext, source = "chat-ui")
            runOnUiThread {
                if (started) {
                    hapticTick()
                    snack(R.string.mic_host_started)
                } else {
                    snack(R.string.mic_start_failed)
                }
            }
        }
    }

    private fun toggleDictation(input: EditText) {
        if (recorder.isRecording()) {
            stopDictation(input)
            return
        }
        if (sttPending) {
            sttPending = false
            setListeningUi(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        sttPending = true
        setListeningUi(true)
        val engine = SttEngine.get(this)
        if (engine.isReady()) {
            beginRecording()
            return
        }
        engine.ensureLoaded {
            // loader thread — hop back to the main thread for UI/recording
            runOnUiThread {
                if (isDestroyed || !sttPending) return@runOnUiThread
                if (SttEngine.get(this).isReady()) {
                    beginRecording()
                } else {
                    sttPending = false
                    setListeningUi(false)
                    snack(R.string.stt_not_ready)
                }
            }
        }
    }

    private fun beginRecording() {
        val session = SttEngine.get(this).newSession()
        if (session == null) {
            sttPending = false
            setListeningUi(false)
            snack(R.string.stt_not_ready)
            return
        }
        sttSession = session
        val input = binding.input
        val editable = input.text?.toString().orEmpty()
        sttDraftPrefix = editable.substring(0, input.selectionStart.coerceIn(0, editable.length))
        lastGeneratedDraft = null
        recorder.start(onChunk = ::onSttChunk)
        if (!recorder.isRecording()) {
            sttSession = null
            sttPending = false
            setListeningUi(false)
            snack(R.string.mic_start_failed)
            return
        }
        hapticTick()
        partialJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            while (isActive && sttSession != null) {
                delay(PARTIAL_INTERVAL_MS)
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_DICTATION_MS) {
                    stopDictation(input)
                    snack(R.string.mic_session_limit, Snackbar.LENGTH_LONG)
                    break
                }
                val current = sttSession ?: break
                val text = withContext(Dispatchers.IO) {
                    SttEngine.get(this@ChatActivity).partial(current)
                }
                if (text.isNotBlank()) {
                    updateDraft(text)
                }
            }
        }
    }

    /** Recorder thread: accumulate audio only — decoding happens on IO. */
    private fun onSttChunk(chunk: FloatArray) {
        val session = sttSession ?: return
        SttEngine.get(this).feed(session, chunk)
    }

    private fun stopDictation(input: EditText) {
        sttPending = false
        partialJob?.cancel()
        partialJob = null
        val session = sttSession
        sttSession = null
        recorder.stop()
        hapticTick()
        setListeningUi(false)
        if (session != null) {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    SttEngine.get(this@ChatActivity).finish(session)
                }
                if (text.isNotBlank()) {
                    updateDraft(text)
                }
            }
        }
    }

    /** Live draft: prefix (text before the cursor at dictation start) + partial.
     *  R-24: text the user typed since the last generated draft is preserved
     *  and re-appended, not silently overwritten. */
    private fun updateDraft(text: String) {
        val input = binding.input
        val current = input.text?.toString().orEmpty()
        val generated = sttDraftPrefix + text
        val updated = if (lastGeneratedDraft != null && current != lastGeneratedDraft) {
            val userTail = if (current.startsWith(lastGeneratedDraft!!)) {
                current.substring(lastGeneratedDraft!!.length)
            } else {
                ""
            }
            generated + userTail
        } else {
            generated
        }
        input.setText(updated)
        input.setSelection(updated.length)
        lastGeneratedDraft = updated
    }

    private fun setListeningUi(listening: Boolean) {
        binding.listening.visibility = if (listening) View.VISIBLE else View.GONE
        updateComposerButtons()
    }

    companion object {
        private const val REQUEST_CODE_PERMS = 42
        private const val STATE_DRAFT = "state_draft"
        private const val STATE_CHAT_ID = "state_chat_id"
        private const val STATE_SEARCH = "state_search"

        /** Widget/shortcut deep actions (voice or camera right after unlock). */
        const val EXTRA_AUTO_ACTION = "auto_action"
        const val EXTRA_NEW_CHAT = "extra_new_chat"

        /** Cold-open a specific chat (project chat list). */
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val AUTO_VOICE = "voice"
        const val AUTO_CAMERA = "camera"

        /** How often the offline recognizer re-decodes the accumulated audio. */
        private const val PARTIAL_INTERVAL_MS = 1000L

        /** Hard cap on one local dictation session (R-11 memory fuse). */
        private const val MAX_DICTATION_MS = 5 * 60_000L

        /** History window sent to the host AI per message (R-05 payload budget). */
        private const val HISTORY_WINDOW = 20

        /** Typewriter pacing: full reveal in ~1.5 s regardless of length. */
        private const val TYPEWRITER_TICKS = 60
        private const val TYPEWRITER_INTERVAL_MS = 25L

        /** Per-chat drafts kept in memory; oldest evicted beyond this. */
        private const val DRAFT_CACHE_LIMIT = 50

        /** Scale pulse on the bubble a search result points at. */
        private const val PULSE_SCALE = 1.04f
    }
}
