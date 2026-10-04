package au.ingo.betterattend.data

import au.ingo.betterattend.data.store.BoxCipher
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores bytes as they are. Test-only on purpose: production code has no plaintext path, so tests
 * that need a working cache on the JVM (no Android Keystore) inject this explicitly via
 * `SecureBox.injectForTesting`.
 */
object IdentityCipher : BoxCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(data: ByteArray): ByteArray = data
}

/** Real AES-GCM with an in-memory key, to check what lands on disk isn't readable. */
class InMemoryAesCipher : BoxCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        c.doFinal(data, 12, data.size - 12)
    }.getOrNull()
}
