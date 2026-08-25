package dev.vynkor.agent.agent

import dev.vynkor.agent.Agent
import kotlinx.coroutines.flow.MutableStateFlow

/** Fine-grained host connection state for the UI (Settings + drawer). */
sealed interface HostStatus {
    data object Idle : HostStatus
    data object Connecting : HostStatus
    data class Unreachable(val reason: String) : HostStatus
    data object Connected : HostStatus
    data object Reconnecting : HostStatus
}

/**
 * Process-wide handle on the live Agent (so services like NotificationListener
 * reach the push paths) plus thread-safe state flows the UI observes.
 */
object AgentHolder {
    @Volatile
    var agent: Agent? = null

    val connectionState = MutableStateFlow(false)

    /** Fine connection status; conflated — UI always sees the latest. */
    val hostStatus = MutableStateFlow<HostStatus>(HostStatus.Idle)

    /** True only while an explicit host-stream mic session is live (R-01 gate). */
    val micStreaming = MutableStateFlow(false)

    /** Session gate owned by [AgentService]; null while the service is down. */
    @Volatile
    var micSession: MicSessionController? = null
}
