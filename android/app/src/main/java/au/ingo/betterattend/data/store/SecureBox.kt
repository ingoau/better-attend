package au.ingo.betterattend.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals bytes for disk. [SecureBox] uses the Keystore one; tests may inject their own. */
interface BoxCipher {
    fun encrypt(plain: ByteArray): ByteArray
    /** null if [data] wasn't sealed by this cipher (or was tampered with). */
    fun decrypt(data: ByteArray): ByteArray?
}

/**
 * AES-GCM encryption backed by a non-exportable Android Keystore key. Used for the session
 * token and for every on-disk cache (participant data about minors never sits in plaintext).
 *
 * Fails closed: if the Keystore key can't be created or loaded, [encrypt] returns null and callers
 * write nothing to disk, so the app keeps working online only. [available] drives the warning
 * shown in the app. Only tests can swap the cipher, via [injectForTesting].
 */
object SecureBox {
    private const val ALIAS = "attend_cache_key_v1"
    private const val IV_LEN = 12

    private val keystore: BoxCipher? by lazy { runCatching { KeystoreCipher(loadOrCreateKey()) }.getOrNull() }

    /** Set only by tests. A holder so that injecting `null` (no key) differs from "not injected". */
    @Volatile private var injected: Array<BoxCipher?>? = null

    private val cipher: BoxCipher?
        get() = injected.let { if (it != null) it[0] else keystore }

    private val _available = MutableStateFlow(true)

    /** False once the encryption key turned out to be unavailable (nothing is being saved to disk). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Resolves the key now (it's otherwise lazy) and returns whether encryption works. */
    fun check(): Boolean = (cipher != null).also { _available.value = it }

    /** Sealed bytes, or null when there's no key: never the plaintext. */
    fun encrypt(plain: ByteArray): ByteArray? {
        val c = cipher ?: run { _available.value = false; return null }
        return runCatching { c.encrypt(plain) }.getOrNull()
    }

    /** Opened bytes, or null when there's no key or [data] doesn't open with it. */
    fun decrypt(data: ByteArray): ByteArray? {
        val c = cipher ?: run { _available.value = false; return null }
        return runCatching { c.decrypt(data) }.getOrNull()
    }

    fun encryptString(s: String): String? = encrypt(s.toByteArray())?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    fun decryptString(s: String): String? = runCatching { decrypt(Base64.decode(s, Base64.NO_WRAP))?.decodeToString() }.getOrNull()

    /**
     * Tests only: use [cipher] instead of the Keystore (which doesn't exist on the JVM). Pass null to
     * simulate a missing key. The Keystore is used again after [resetForTesting].
     */
    @VisibleForTesting
    fun injectForTesting(cipher: BoxCipher?) {
        injected = arrayOf(cipher)
        _available.value = cipher != null
    }

    @VisibleForTesting
    fun resetForTesting() {
        injected = null
        _available.value = true
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
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
    }

    private class KeystoreCipher(private val key: SecretKey) : BoxCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(plain)
        }

        override fun decrypt(data: ByteArray): ByteArray? = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_LEN))
            cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
        }.getOrNull()
    }
}
