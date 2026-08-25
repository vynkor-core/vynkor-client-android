package dev.vynkor.agent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R-30: profile JSON is the persistence contract (now encrypted at rest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostProfileJsonTest {

    private fun full() = HostProfile(
        id = "id-1",
        name = "lab",
        hostUrl = "ws://10.0.0.2:7431",
        deviceId = "phone-1",
        jwtToken = "tok",
        jwtSecret = "sec",
        certPem = "pem",
        userId = "behzod",
        aiProvider = "anthropic",
        aiModel = "claude-sonnet-4-5",
        aiBaseUrl = "https://api.example.com",
        aiApiKeyEnv = "KEY",
        aiAgent = "agent-7",
    )

    @Test
    fun `json roundtrip preserves every field`() {
        val restored = HostProfile.fromJson(full().toJson())
        assertEquals(full(), restored)
    }

    @Test
    fun `fromJson tolerates missing fields with defaults`() {
        val o = JSONObject("""{"host_url":"ws://h:1"}""")
        val p = HostProfile.fromJson(o)
        assertEquals("ws://h:1", p.hostUrl)
        assertEquals("", p.jwtToken)
        assertEquals("", p.jwtSecret)
        assertEquals("default", p.userId)
        assertTrue(p.id.isNotBlank())
    }

    @Test
    fun `blank id in json gets a fresh uuid`() {
        val o = JSONObject("""{"id":"","host_url":"ws://h:1"}""")
        val first = HostProfile.fromJson(o)
        val second = HostProfile.fromJson(o)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `effectiveModel falls back to provider default`() {
        assertEquals(
            "claude-sonnet-4-5",
            HostProfile(aiProvider = "anthropic").effectiveModel(),
        )
        assertEquals(
            "llama3.2",
            HostProfile(aiProvider = "openai").effectiveModel(),
        )
        assertEquals("custom", HostProfile(aiModel = "custom").effectiveModel())
    }

    @Test
    fun `effectiveBaseUrl has no synthetic localhost default`() {
        // №53: a phone pointing at its own localhost masked misconfiguration.
        assertEquals("", HostProfile(aiProvider = "openai").effectiveBaseUrl())
        assertEquals("https://x", HostProfile(aiBaseUrl = "https://x").effectiveBaseUrl())
    }
}
