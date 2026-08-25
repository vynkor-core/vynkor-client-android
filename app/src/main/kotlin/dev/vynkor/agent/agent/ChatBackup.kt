package dev.vynkor.agent.agent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted device backup: profiles + chats + projects of every host.
 *
 * Envelope is a JSON text file; the payload (a JSON document with all user
 * data) is AES-256-GCM encrypted under a PBKDF2WithHmacSHA256 key derived
 * from the user's password — the file is safe to store anywhere, and the
 * restore works on another device without any Keystore dependency.
 */
object ChatBackup {
    private const val FORMAT = "vynkor-backup"
    private const val VERSION = 1
    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    private const val KDF_ITERS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16

    data class Stats(val profiles: Int, val chats: Int, val projects: Int)

    fun buildJson(context: Context): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created_at", System.currentTimeMillis())
        val profiles = JSONArray()
        val chats = JSONObject()
        val projects = JSONObject()
        ProfileStore.list(context).forEach { profile ->
            profiles.put(profile.toJson())
            chats.put(profile.id, JSONArray(ChatStore.exportJson(context, profile.id)))
            projects.put(
                profile.id,
                JSONArray().apply {
                    ProjectStore.list(context, profile.id).forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("name", it.name)
                                .put("created_at", it.createdAt),
                        )
                    }
                },
            )
        }
        return root
            .put("profiles", profiles)
            .put("chats", chats)
            .put("projects", projects)
            .toString()
    }

    /** Null when the password is wrong or the envelope is damaged. */
    fun decryptToJson(envelopeJson: String, password: CharArray): String? = try {
        val o = JSONObject(envelopeJson)
        if (o.optString("format") != FORMAT || o.optInt("version") != VERSION) null else {
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    fun encryptToJson(backupJson: String, password: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val data = cipher.doFinal(backupJson.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", KDF_ALG)
            .put("iters", KDF_ITERS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString()
    }

    /**
     * Destructive restore: wipes local chats/projects and replaces profiles
     * with the backup contents. Returns counts for the confirmation dialog.
     */
    fun restore(context: Context, decryptedJson: String): Stats? = try {
        val root = JSONObject(decryptedJson)
        val profiles = mutableListOf<HostProfile>()
        val profilesArr = root.getJSONArray("profiles")
        for (i in 0 until profilesArr.length()) {
            profiles.add(HostProfile.fromJson(profilesArr.getJSONObject(i)))
        }
        var chatCount = 0
        var projectCount = 0
        val chats = root.getJSONObject("chats")
        val projects = root.getJSONObject("projects")
        ProjectStore.wipeAll(context)
        profiles.forEach { profile ->
            val id = profile.id
            if (chats.has(id)) {
                chatCount += ChatStore.importJson(context, id, chats.get(id).toString())
            }
            if (projects.has(id)) {
                val arr = projects.getJSONArray(id)
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    ProjectStore.save(
                        context,
                        id,
                        Project(
                            id = p.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            name = p.optString("name"),
                            createdAt = p.optLong("created_at", System.currentTimeMillis()),
                        ),
                    )
                    projectCount++
                }
            }
        }
        ProfileStore.replaceAll(context, profiles)
        Stats(profiles.size, chatCount, projectCount)
    } catch (_: Exception) {
        null
    }

    /** Counts for the confirmation dialog, without touching local data. */
    fun peekStats(decryptedJson: String): Stats? = try {
        val root = JSONObject(decryptedJson)
        fun countArrayObject(key: String): Int {
            val obj = root.getJSONObject(key)
            var n = 0
            val keys = obj.keys()
            while (keys.hasNext()) n += obj.getJSONArray(keys.next()).length()
            return n
        }
        Stats(
            profiles = root.getJSONArray("profiles").length(),
            chats = countArrayObject("chats"),
            projects = countArrayObject("projects"),
        )
    } catch (_: Exception) {
        null
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, KDF_ITERS, KEY_BITS)
        val key = SecretKeyFactory.getInstance(KDF_ALG).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }
}
