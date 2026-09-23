package dev.vynkor.agent.agent

import dev.vynkor.agent.ActionReplyStatus
import dev.vynkor.agent.Agent
import org.json.JSONArray
import org.json.JSONObject

class AiException(message: String) : Exception(message)

data class AiReply(
    val content: String,
    val stopReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

data class AiModel(
    val id: String,
    val provider: String,
    val baseUrl: String,
    val apiKeyEnv: String,
    val isDefault: Boolean,
)

data class AiAgent(
    val id: String,
    val name: String,
    val modelId: String,
    val systemPrompt: String,
    val goal: String,
    val description: String,
    val isDefault: Boolean,
)

/** Terminal state of one host `agent` goal. */
sealed interface GoalOutcome {
    data class Answer(val text: String) : GoalOutcome
    /** The model picked a tool marked requires_confirmation; host waits for goal_resume. */
    data class NeedsConfirmation(val goalId: String, val tool: String) : GoalOutcome
    data class Declined(val detail: String) : GoalOutcome
    data class Failed(val status: String, val detail: String) : GoalOutcome
}

/**
 * Thin wrapper over the host `ai` and `agent` plugins. The plugin keeps its own database
 * of declared + auto-discovered models and agent profiles (host-side), so
 * the app asks the host for the list (`list_models`/`list_agents`) instead
 * of carrying provider/base_url/api_key_env itself. `chat_completion` names
 * either an agent (`agent_id`) or a model id; the host resolves the
 * endpoint and the API key (which never travels).
 */
object AiClient {
    private const val TIMEOUT_MS = 30_000u

    /** A goal is a whole plan/act loop (several completions + tool calls). */
    private const val AGENT_TIMEOUT_MS = 300_000u
    private const val GOAL_MAX_CHARS = 4000
    private const val CONTEXT_MAX_CHARS = 12_000
    private const val TITLE_MAX_CHARS = 60

    /**
     * One `ai.chat_completion`. A model the host already knows (listed by
     * `list_models`) is named by id alone — the host resolves provider,
     * endpoint and key. The explicit provider/base_url/api_key_env triple is
     * only for a hand-typed model the host has no record of.
     */
    fun chat(
        agent: Agent,
        profile: HostProfile,
        messages: List<Pair<String, String>>,
        hostModelIds: Set<String> = emptySet(),
    ): AiReply {
        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val params = JSONObject().apply {
            val agentId = profile.aiAgent
            val model = profile.effectiveModel()
            if (agentId.isNotBlank()) {
                put("agent_id", agentId)
            } else {
                put("model", model)
                val known = model in hostModelIds
                if (!known && profile.aiApiKeyEnv.isNotBlank()) {
                    put("provider", profile.aiProvider)
                    if (profile.effectiveBaseUrl().isNotBlank()) put("base_url", profile.effectiveBaseUrl())
                    put("api_key_env", profile.aiApiKeyEnv)
                }
            }
            put("messages", msgs)
            put("max_tokens", 1024)
            put("timeout_ms", 30_000)
        }
        val data = request(agent, "chat_completion", params)
        val usage = data.optJSONObject("usage")
        return AiReply(
            content = data.optString("content"),
            stopReason = data.optString("stop_reason"),
            inputTokens = usage?.optLong("input_tokens") ?: 0L,
            outputTokens = usage?.optLong("output_tokens") ?: 0L,
        )
    }

    // ------------------------------------------------------ agent plugin

    /**
     * Runs one goal on the host `agent` plugin (`goal_start`): the plugin
     * plans, calls tools through the kernel and answers. Synchronous on the
     * host side, so the timeout covers a whole multi-step loop.
     */
    fun goalStart(
        agent: Agent,
        profile: HostProfile,
        goal: String,
        context: String?,
        title: String,
    ): GoalOutcome {
        val params = JSONObject().apply {
            put("goal", goal.take(GOAL_MAX_CHARS).ifBlank { "(see context)" })
            if (!context.isNullOrBlank()) put("context", context.takeLast(CONTEXT_MAX_CHARS))
            if (title.isNotBlank()) put("title", title.take(TITLE_MAX_CHARS))
            if (profile.aiAgent.isNotBlank()) put("agent_id", profile.aiAgent)
        }
        return parseGoal(request(agent, "goal_start", params, AGENT_TIMEOUT_MS))
    }

    /** Answers a `needs_confirmation` halt: runs (or declines) the pending tool. */
    fun goalResume(agent: Agent, goalId: String, approve: Boolean): GoalOutcome {
        val params = JSONObject().put("id", goalId).put("approve", approve)
        return parseGoal(request(agent, "goal_resume", params, AGENT_TIMEOUT_MS))
    }

    internal fun parseGoal(o: JSONObject): GoalOutcome {
        val id = o.optString("id")
        val answer = o.optString("final_answer")
        return when (val status = o.optString("status")) {
            "completed" -> GoalOutcome.Answer(answer)
            "needs_confirmation" -> GoalOutcome.NeedsConfirmation(id, o.optString("pending_tool"))
            "declined" -> GoalOutcome.Declined(answer.ifBlank { o.optString("pending_tool") })
            "max_steps_reached" -> GoalOutcome.Failed(status, answer)
            else -> GoalOutcome.Failed(status, o.optString("error").ifBlank { answer })
        }
    }

    /** Models the host can complete with (declared + auto-discovered). */
    fun listModels(agent: Agent): List<AiModel> {
        val arr = requestArray(agent, "list_models")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    AiModel(
                        id = o.optString("id"),
                        provider = o.optString("provider"),
                        baseUrl = o.optString("base_url"),
                        apiKeyEnv = o.optString("api_key_env"),
                        isDefault = o.optBoolean("is_default"),
                    )
                )
            }
        }
    }

    /** Agent profiles defined on the host. */
    fun listAgents(agent: Agent): List<AiAgent> {
        val arr = requestArray(agent, "list_agents")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    AiAgent(
                        id = o.optString("id"),
                        name = o.optString("name").ifBlank { o.optString("id") },
                        modelId = o.optString("model_id"),
                        systemPrompt = o.optString("system_prompt"),
                        goal = o.optString("goal"),
                        description = o.optString("description"),
                        isDefault = o.optBoolean("is_default"),
                    )
                )
            }
        }
    }

    private fun requestArray(agent: Agent, action: String): JSONArray =
        JSONArray(requestRaw(agent, action, JSONObject()))

    private fun request(
        agent: Agent,
        action: String,
        params: JSONObject,
        timeoutMs: UInt = TIMEOUT_MS,
    ): JSONObject = JSONObject(requestRaw(agent, action, params, timeoutMs))

    private fun requestRaw(
        agent: Agent,
        action: String,
        params: JSONObject,
        timeoutMs: UInt = TIMEOUT_MS,
    ): String {
        val reply = agent.request(
            "kernel",
            action,
            params.toString().toByteArray(Charsets.UTF_8),
            timeoutMs,
        )
        if (reply.status != ActionReplyStatus.OK) {
            val detail = reply.error.ifBlank { reply.status.name }
            throw AiException(detail)
        }
        return String(reply.dataJson, Charsets.UTF_8)
    }
}