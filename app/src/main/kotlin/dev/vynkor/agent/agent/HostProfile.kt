package dev.vynkor.agent.agent

import org.json.JSONObject
import java.util.UUID

/**
 * One saved host kernel the agent can connect to, with its per-host AI-plugin
 * settings (`ai.*` fields — the `ai` plugin's `chat_completion` params, minus
 * the API key itself, which never leaves the host).
 */
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val hostUrl: String = "",
    val deviceId: String = "",
    val jwtToken: String = "",
    val jwtSecret: String = "",
    val certPem: String = "",
    val userId: String = "default",
    val aiProvider: String = "openai",
    val aiModel: String = "",
    val aiBaseUrl: String = "",
    val aiApiKeyEnv: String = "",
    /** Selected agent profile id on the host ('' = host default agent). */
    val aiAgent: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host_url", hostUrl)
        put("device_id", deviceId)
        put("jwt_token", jwtToken)
        put("jwt_secret", jwtSecret)
        put("cert_pem", certPem)
        put("user_id", userId)
        put("ai_provider", aiProvider)
        put("ai_model", aiModel)
        put("ai_base_url", aiBaseUrl)
        put("ai_api_key_env", aiApiKeyEnv)
        put("ai_agent", aiAgent)
    }

    fun effectiveModel(): String =
        aiModel.ifBlank { DEFAULT_MODEL_BY_PROVIDER[aiProvider] ?: "" }

    // №53: no synthetic default — `http://localhost:11434` on a phone pointed
    // at itself and masked misconfiguration. Blank means "unset"; the host
    // omits base_url and ProfileActivity injects defaults for new profiles.
    fun effectiveBaseUrl(): String = aiBaseUrl

    companion object {
        val DEFAULT_MODEL_BY_PROVIDER: Map<String, String> = mapOf(
            "openai" to "llama3.2",
            "anthropic" to "claude-sonnet-4-5",
        )

        fun fromJson(o: JSONObject): HostProfile = HostProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            hostUrl = o.optString("host_url"),
            deviceId = o.optString("device_id"),
            jwtToken = o.optString("jwt_token"),
            jwtSecret = o.optString("jwt_secret"),
            certPem = o.optString("cert_pem"),
            userId = o.optString("user_id").ifBlank { "default" },
            aiProvider = o.optString("ai_provider").ifBlank { "openai" },
            aiModel = o.optString("ai_model"),
            aiBaseUrl = o.optString("ai_base_url"),
            aiApiKeyEnv = o.optString("ai_api_key_env"),
            aiAgent = o.optString("ai_agent"),
        )
    }
}
