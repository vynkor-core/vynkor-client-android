package dev.vynkor.agent.agent

import dev.vynkor.agent.PairingApplier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Agent chat routing, goal parsing and re-pair matching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentChatTest {

    @Test
    fun `profiles default to the host agent and old json migrates to it`() {
        assertTrue(HostProfile().usesAgent)
        val legacy = HostProfile.fromJson(JSONObject("""{"host_url":"ws://h:1","ai_model":"llama3.2"}"""))
        assertTrue(legacy.usesAgent)
    }

    @Test
    fun `chat target survives the json roundtrip`() {
        val p = HostProfile(chatTarget = HostProfile.CHAT_TARGET_AI, aiModel = "qwen")
        val restored = HostProfile.fromJson(p.toJson())
        assertFalse(restored.usesAgent)
        assertEquals("qwen", restored.aiModel)
    }

    @Test
    fun `completed goal yields its final answer`() {
        val o = JSONObject("""{"id":"7","status":"completed","final_answer":"done"}""")
        assertEquals(GoalOutcome.Answer("done"), AiClient.parseGoal(o))
    }

    @Test
    fun `confirmation halt carries goal id and tool`() {
        val o = JSONObject("""{"id":"9","status":"needs_confirmation","pending_tool":"fs_write"}""")
        assertEquals(GoalOutcome.NeedsConfirmation("9", "fs_write"), AiClient.parseGoal(o))
    }

    @Test
    fun `error status surfaces the host error text`() {
        val o = JSONObject("""{"id":"1","status":"error","error":"ai unreachable"}""")
        assertEquals(GoalOutcome.Failed("error", "ai unreachable"), AiClient.parseGoal(o))
    }

    @Test
    fun `re-pair matches the same host regardless of scheme and path`() {
        assertTrue(PairingApplier.sameHost("ws://192.168.1.5:8888/ws", "wss://192.168.1.5:8888/ws"))
        assertTrue(PairingApplier.sameHost("wss://Host:8888", "wss://host:8888/ws"))
        assertFalse(PairingApplier.sameHost("wss://192.168.1.45:8888/ws", "wss://192.168.31.189:8888/ws"))
    }
}
