package dev.vynkor.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.vynkor.agent.agent.AppPrefs
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.ProjectStore
import dev.vynkor.agent.databinding.ActivityProjectChatsBinding

/**
 * One project's chat list, opened from the drawer's projects section:
 * tap a chat to continue it, FAB starts a new chat inside the project.
 */
class ProjectChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectChatsBinding

    private var profileId: String = ""
    private var projectId: String = ""

    private lateinit var adapter: ChatListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityProjectChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = projectName.ifBlank { getString(R.string.projects_title) }

        adapter = ChatListAdapter(
            onOpen = { chat, _ ->
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id),
                )
            },
            onLongPress = { chat -> showChatMenu(chat) },
        )
        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter
        ChatSwipe.attach(
            binding.chatList,
            chatAt = { pos ->
                (adapter.currentList.getOrNull(pos) as? DrawerItem.ChatEntry)?.row?.chat
            },
            onPin = { chat ->
                ChatStore.setPinned(this, profileId, chat.id, !chat.pinned)
                refresh()
            },
            onDeleteAsk = { confirmDelete(it) },
        )

        binding.newChat.setOnClickListener {
            val chat = Chat(projectId = projectId)
            ChatStore.save(this, profileId, chat)
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id),
            )
            finish()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Returning from a chat may have changed titles/order — re-read. */
    private fun refresh() {
        val chats = ChatStore.list(this, profileId).filter { it.projectId == projectId }
        binding.projectEmpty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(chats)
    }

    private fun showChatMenu(chat: Chat) {
        val options = arrayOf(
            getString(R.string.move_to_project),
            getString(R.string.chat_duplicate),
            getString(R.string.delete_chat),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(chat.title.ifBlank { getString(R.string.new_chat) })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> moveToProjectDialog(chat)
                    1 -> {
                        ChatStore.cloneChat(this, profileId, chat.id)
                        refresh()
                    }
                    2 -> confirmDelete(chat)
                }
            }
            .show()
    }

    /**
     * Same dialog as the main drawer, "No project" included — this is how a
     * chat leaves a project. Moving into another project works too.
     */
    private fun moveToProjectDialog(chat: Chat) {
        val projects = ProjectStore.list(this, profileId).filter { it.id != projectId }
        val labels = mutableListOf(getString(R.string.no_project_item))
        labels.addAll(projects.map { it.name })
        val ids = mutableListOf<String?>(null)
        ids.addAll(projects.map { it.id })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.move_to_project)
            .setItems(labels.toTypedArray()) { _, which ->
                ChatStore.moveToProject(this, profileId, chat.id, ids[which])
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(chat: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(R.string.delete_chat_confirm)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                ChatStore.delete(this, profileId, chat.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_PROJECT_NAME = "project_name"

        fun intent(context: Context, profileId: String, projectId: String, projectName: String): Intent =
            Intent(context, ProjectChatsActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
    }
}
