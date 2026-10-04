package au.ingo.betterattend.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.auth.SecureTokenStore
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.data.repo.ScanRepository
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** SecureBox must never fall back to plaintext: no key means nothing sensitive reaches the disk. */
@RunWith(RobolectricTestRunner::class)
class SecureBoxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cacheDir get() = File(context.filesDir, "cache_v1")
    private val secret = "Mia Chen, anaphylaxis, room 204"

    @Before fun setUp() { cacheDir.deleteRecursively() }
    @After fun tearDown() { SecureBox.resetForTesting() }

    private fun filesOnDisk() = cacheDir.listFiles().orEmpty().filter { it.isFile }

    @Test fun production_withoutAKeystore_failsClosed() = runBlocking {
        // The JVM has no Android Keystore: exactly the "key unavailable" case. Nothing is injected.
        SecureBox.resetForTesting()
        assertFalse(SecureBox.check())
        assertNull(SecureBox.encrypt(secret.toByteArray()))
        assertNull(SecureBox.encryptString(secret))
        assertFalse(SecureBox.available.value)

        val cache = JsonCache(context)
        cache.write("roster_e1", String.serializer(), secret)
        assertTrue("nothing written without a key", filesOnDisk().isEmpty())
        assertNull(cache.read("roster_e1", String.serializer()))
    }

    @Test fun missingKey_neverWritesOrReadsPlaintext() = runBlocking {
        // A file written while a key existed…
        SecureBox.injectForTesting(IdentityCipher)
        val cache = JsonCache(context)
        cache.write("old", String.serializer(), secret)
        assertEquals(1, filesOnDisk().size)

        // …isn't handed back as-is once the key is gone, and new writes are dropped.
        SecureBox.injectForTesting(null)
        assertFalse(SecureBox.available.value)
        assertNull(cache.read("old", String.serializer()))
        cache.write("new", String.serializer(), secret)
        assertEquals(listOf("old.bin"), filesOnDisk().map { it.name })
    }

    @Test fun withAKey_diskHoldsCiphertextOnly() = runBlocking {
        SecureBox.injectForTesting(InMemoryAesCipher())
        assertTrue(SecureBox.check())
        val cache = JsonCache(context)
        cache.write("roster_e1", String.serializer(), secret)
        val raw = filesOnDisk().single().readBytes().decodeToString()
        assertFalse(raw.contains("Mia Chen"))
        assertEquals(secret, cache.read("roster_e1", String.serializer()))
    }

    @Test fun tokenStore_keepsTheSessionInMemoryOnly() {
        SecureBox.injectForTesting(null)
        val store = SecureTokenStore(context)
        store.update("secret-token", "2026-10-18T00:00:00Z")
        store.user = User(id = "u1", email = "staff@example.com")
        assertEquals("still signed in for this run", "secret-token", store.token)

        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE).all
        assertFalse(prefs.containsKey("token"))
        assertFalse(prefs.containsKey("user"))
        assertTrue(prefs.values.none { it.toString().contains("secret-token") || it.toString().contains("staff@example.com") })
        assertNull("gone after a restart", SecureTokenStore(context).token)
    }

    @Test fun scanning_keepsWorkingWithoutAKey() = runBlocking {
        SecureBox.injectForTesting(null)
        val tokens = object : TokenStore {
            override val token = "t"
            override fun update(token: String?, expiresAt: String?) {}
        }
        val api = AttendApi(tokens, onSessionExpired = {}, baseUrl = "http://127.0.0.1:1") // offline
        val cache = JsonCache(context)
        val scans = ScanRepository(api, cache, ParticipantRepository(api, cache), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val outcome = scans.submit("e1", ScanInput(participantId = "aaaaaaa1-0000-4000-8000-000000000000"), "desk", "Desk")
        assertTrue(outcome is ScanOutcome.Queued)
        assertEquals("queued in memory", 1, scans.pending.value.size)
        assertTrue("but not on disk", filesOnDisk().isEmpty())
        // And an encrypted roster can't be planted and read back either.
        cache.write("roster_e1", Roster.serializer(), Roster("e1"))
        assertTrue(filesOnDisk().isEmpty())
    }
}
