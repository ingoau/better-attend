package au.ingo.betterattend.data

import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Personal
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Roster cache updates after editing or removing someone. */
@RunWith(RobolectricTestRunner::class)
class ParticipantRepositoryEditTest {
    private val tokens = object : TokenStore {
        override val token: String = "t"
        override fun update(token: String?, expiresAt: String?) {}
    }
    private lateinit var cache: JsonCache
    private lateinit var repo: ParticipantRepository

    private val mia = Participant(
        participantId = "p1", participantEventId = "pe1", displayName = "Mia", fullName = "Mia Chen", email = "mia@example.com",
        status = "complete", tshirtSize = "M", pronouns = "she/her",
        personal = Personal(legalFirstName = "Mia", legalLastName = "Chen", tshirtSize = "M", dateOfBirth = "2010-01-01"),
    )
    private val ollie = mia.copy(participantId = "p2", participantEventId = "pe2", displayName = "Ollie", fullName = "Ollie Smith", personal = null)

    @Before fun setUp() = runBlocking {
        SecureBox.injectForTesting(IdentityCipher)
        cache = JsonCache(ApplicationProvider.getApplicationContext())
        cache.clear()
        cache.write("roster_e1", Roster.serializer(), Roster("e1", listOf(mia, ollie), syncedAt = "2026-10-03T00:00:00Z"))
        repo = ParticipantRepository(AttendApi(tokens, onSessionExpired = {}, baseUrl = "http://127.0.0.1:1"), cache)
    }

    @After fun tearDown() { SecureBox.resetForTesting() }

    @Test fun applyEditKeepsClearedFieldsCleared() = runBlocking {
        // Fresh detail after clearing the T-shirt size and pronouns: the top-level size isn't in the detail payload at all.
        val fresh = mia.copy(tshirtSize = null, pronouns = null, displayName = "Mimi", personal = mia.personal!!.copy(tshirtSize = null, preferredName = "Mimi"))
        repo.applyEdit("e1", fresh)
        val stored = repo.roster("e1")!!.byEventId.getValue("pe1")
        assertNull(stored.tshirtSize)
        assertNull(stored.pronouns)
        assertEquals("Mimi", stored.name)
        assertEquals("2010-01-01", stored.personal?.dateOfBirth)
        // Re-sorted by name, and written through to the encrypted cache.
        assertEquals(listOf("Mimi", "Ollie"), repo.roster("e1")!!.participants.map { it.name })
        val reloaded = cache.read("roster_e1", Roster.serializer())!!
        assertNull(reloaded.byEventId.getValue("pe1").tshirtSize)
    }

    @Test fun removeDropsTheRegistration() = runBlocking {
        repo.remove("e1", "pe1")
        assertEquals(listOf("pe2"), repo.roster("e1")!!.participants.map { it.participantEventId })
        assertEquals(listOf("pe2"), cache.read("roster_e1", Roster.serializer())!!.participants.map { it.participantEventId })
        // Removing someone who isn't there (or from an event with no roster) is a no-op.
        repo.remove("e1", "nobody")
        repo.remove("e2", "pe2")
        assertEquals(1, repo.roster("e1")!!.participants.size)
        assertNull(repo.roster("e2"))
    }
}
