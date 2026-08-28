package dev.vynkor.agent.agent

import java.util.UUID

data class ChatMessage(
    val role: String,                       // "user" | "assistant" | "error"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // R-25: stable identity for fork branching; (timestamp, role, content)
    // collides on duplicate messages. Last + defaulted so positional
    // constructors keep compiling.
    val id: String = UUID.randomUUID().toString(),
    val attachments: List<Attachment> = emptyList(),
)

// №36: fully immutable — updates go through copy(), so the store's cached
// instances can never be mutated behind DiffUtil's back and concurrent UI
// edits cannot race a serialization snapshot.
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    // Projects: "" = no project (default inbox).
    val projectId: String = "",
    val pinned: Boolean = false,
)
