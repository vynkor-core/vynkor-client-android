package dev.vynkor.agent.agent

import android.net.Uri
import android.util.Base64
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Decodes the host-generated `vynkor://pair?z=1&d=<deflate+base64url(JSON)>`
 * payload into a [HostProfile]. The QR/link is a physical, unidirectional
 * trusted channel, so it may carry the per-device secret once and the served
 * TLS cert for pinning.
 *
 * E-01: only payload `v=2` is accepted. A v=1 payload carries the host MASTER
 * jwt_secret — accepting it would reintroduce the exact trust excess E-01
 * removed, so old links are rejected with an explicit reason instead.
 */
object PairingPayload {
    const val SCHEME = "vynkor"
    const val HOST = "pair"
    private const val VERSION = 2

    sealed class Result {
        data class Ok(val profile: HostProfile) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun parse(raw: String): HostProfile? = (parseWithReason(raw) as? Result.Ok)?.profile

    fun parseWithReason(raw: String): Result {
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            return Result.Invalid("not a valid URI")
        }
        if (uri.scheme != SCHEME || uri.host != HOST) return Result.Invalid("wrong link shape")
        val encoded = uri.getQueryParameter("d") ?: return Result.Invalid("missing data")
        val json = try {
            val compressed = Base64.decode(encoded, Base64.URL_SAFE)
            if (uri.getQueryParameter("z") == "1") inflate(compressed) else String(compressed, Charsets.UTF_8)
        } catch (_: Exception) {
            return Result.Invalid("undecodable payload")
        } ?: return Result.Invalid("undecodable payload")
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return Result.Invalid("payload is not JSON")
        }
        when (o.optInt("v")) {
            VERSION -> {}
            1 -> return Result.Invalid(
                "pairing v1 rejected — this host shares its master secret; update the host and re-pair"
            )
            else -> return Result.Invalid("unsupported pairing version ${o.optInt("v")}")
        }
        if (o.optString("host_url").isBlank() || o.optString("jwt_token").isBlank()) {
            return Result.Invalid("missing host_url or token")
        }
        // a v2 payload without a device secret is malformed by definition
        if (o.optString("device_secret").isBlank()) {
            return Result.Invalid("payload carries no device secret")
        }

        return Result.Ok(
            HostProfile(
                name = o.optString("name").ifBlank { o.optString("device_id") },
                hostUrl = o.optString("host_url"),
                deviceId = o.optString("device_id"),
                jwtToken = o.optString("jwt_token"),
                deviceSecret = o.optString("device_secret"),
                certPem = o.optString("cert_pem"),
            )
        )
    }

    /** zlib (RFC 1950) inflate — matches the host CLI's ZlibEncoder output. */
    private fun inflate(data: ByteArray): String? {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) return null
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }
}
