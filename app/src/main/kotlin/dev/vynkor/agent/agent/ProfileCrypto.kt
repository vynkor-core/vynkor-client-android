package dev.vynkor.agent.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R-03: encrypts the profile store at rest with an AES-256-GCM key that never
 * leaves [KeyStore.AndroidKeyStore]. Wire format: `base64(iv || ciphertext)`.
 *
 * Failure policy is fail-closed for writes: when the keystore or cipher fails,
 * callers must NOT fall back to persisting plaintext secrets (see
 * [ProfileStore.persist]); a failing decrypt is treated as a corrupt store.
 */
internal object ProfileCrypto {
    private const val TAG = "ProfileCrypto"
    private const val KEY_ALIAS = "vynkor_profile_store"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv ?: error("keystore cipher produced no IV")
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.onFailure { Log.e(TAG, "profile encryption failed", it) }.getOrNull()

    fun decrypt(encoded: String): String? = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        if (data.size <= IV_LEN) error("ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_LEN))
        String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)
    }.onFailure { Log.e(TAG, "profile decryption failed", it) }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
