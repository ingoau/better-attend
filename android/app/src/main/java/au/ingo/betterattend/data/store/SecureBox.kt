package au.ingo.betterattend.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by a non-exportable Android Keystore key. Used for the session
 * token and for every on-disk cache (participant data about minors never sits in plaintext).
 * Falls back to identity if the Keystore is unavailable (e.g. JVM tests).
 */
object SecureBox {
    private const val ALIAS = "attend_cache_key_v1"
    private const val IV_LEN = 12

    private val key: SecretKey? by lazy {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }.generateKey()
        }.getOrNull()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val k = key ?: return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray? {
        val k = key ?: return data
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data, 0, IV_LEN))
            cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
        }.getOrNull()
    }

    fun encryptString(s: String): String = Base64.encodeToString(encrypt(s.toByteArray()), Base64.NO_WRAP)
    fun decryptString(s: String): String? = runCatching { decrypt(Base64.decode(s, Base64.NO_WRAP))?.decodeToString() }.getOrNull()
}
