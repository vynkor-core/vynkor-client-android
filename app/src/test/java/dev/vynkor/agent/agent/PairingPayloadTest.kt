package dev.vynkor.agent.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-30: the pairing payload is a security boundary (R-02) — every malformed
 * shape must be rejected, valid shapes must round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingPayloadTest {

    private fun encode(json: String): String =
        android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )

    private fun url(json: String) = "vynkor://pair?d=${encode(json)}"

    private fun validJson() = """
        {"v":1,"host_url":"ws://10.0.0.2:7431","jwt_token":"tok",
         "device_id":"phone-1","name":"lab","jwt_secret":"s3cret","cert_pem":"pem"}
    """.trimIndent()

    @Test
    fun `valid payload parses into a profile`() {
        val p = PairingPayload.parse(url(validJson()))
        assertNotNull(p)
        assertEquals("ws://10.0.0.2:7431", p!!.hostUrl)
        assertEquals("tok", p.jwtToken)
        assertEquals("phone-1", p.deviceId)
        assertEquals("s3cret", p.jwtSecret)
        assertEquals("pem", p.certPem)
        assertEquals("lab", p.name)
    }

    @Test
    fun `missing name falls back to device_id`() {
        val json = """{"v":1,"host_url":"ws://h:1","jwt_token":"t","device_id":"dev-9"}"""
        assertEquals("dev-9", PairingPayload.parse(url(json))!!.name)
    }

    @Test
    fun `wrong scheme is rejected`() {
        assertNull(PairingPayload.parse(url(validJson()).replace("vynkor://", "evil://")))
    }

    @Test
    fun `wrong host segment is rejected`() {
        assertNull(PairingPayload.parse(url(validJson()).replace("pair?", "other?")))
    }

    @Test
    fun `unknown version is rejected`() {
        val json = validJson().replace("\"v\":1", "\"v\":99")
        assertNull(PairingPayload.parse(url(json)))
    }

    @Test
    fun `missing d parameter is rejected`() {
        assertNull(PairingPayload.parse("vynkor://pair"))
    }

    @Test
    fun `garbage base64 is rejected`() {
        assertNull(PairingPayload.parse("vynkor://pair?d=%%%not-base64%%%"))
    }

    @Test
    fun `valid base64 with garbage json is rejected`() {
        assertNull(PairingPayload.parse(url("{not json")))
    }

    @Test
    fun `blank host_url is rejected`() {
        val json = """{"v":1,"host_url":"","jwt_token":"t"}"""
        assertNull(PairingPayload.parse(url(json)))
    }

    @Test
    fun `blank jwt_token is rejected`() {
        val json = """{"v":1,"host_url":"ws://h:1","jwt_token":""}"""
        assertNull(PairingPayload.parse(url(json)))
    }

    @Test
    fun `url-safe base64 with padding decodes`() {
        // 40 bytes of JSON → padded base64; decoder must accept it either way
        val json = """{"v":1,"host_url":"ws://h:1","jwt_token":"token-token-token"}"""
        val encoded = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE,
        )
        assertTrue(PairingPayload.parse("vynkor://pair?d=$encoded") != null)
    }

    @Test
    fun `profile defaults are sane`() {
        val profile = ApplicationProvider.getApplicationContext<android.content.Context>()
            .let { HostProfile(name = "x", hostUrl = "ws://h:1") }
        assertTrue(profile.id.isNotBlank())
        assertEquals("default", profile.userId)
    }
}
