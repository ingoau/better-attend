package au.ingo.betterattend.signups

import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.IdentityCipher
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ParticipantsResponse
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import au.ingo.betterattend.data.store.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Spotting new signups between roster syncs, and how the notification words them. */
@RunWith(RobolectricTestRunner::class)
class SignupsTest {
    private fun person(n: Int, name: String, status: String? = "complete") =
        Participant(participantId = "p$n", participantEventId = "pe$n", displayName = name, status = status)

    private val mia = person(1, "Mia Chen")
    private val ollie = person(2, "Ollie Smith")
    private val ava = person(3, "Ava Jones", status = "in_progress")

    // ---- SignupLogic ----

    @Test fun newRegistrationsAreSignups() {
        val old = Roster("e1", listOf(mia))
        val new = Roster("e1", listOf(ava, mia, ollie))
        assertEquals(listOf("pe3", "pe2"), SignupLogic.newSignups(old, new).map { it.participantEventId })
    }

    @Test fun invitesWithdrawalsAndRejectionsAreNot() {
        val old = Roster("e1", listOf(mia))
        val new = Roster("e1", listOf(mia, person(2, "Invited", "invited"), person(3, "Gone", "withdrawn"), person(4, "No", "rejected")))
        assertTrue(SignupLogic.newSignups(old, new).isEmpty())
    }

    @Test fun anInviteeWhoStartsRegisteringIsASignup() {
        val invited = person(2, "Ollie Smith", "invited")
        val old = Roster("e1", listOf(mia, invited))
        val new = Roster("e1", listOf(mia, invited.copy(status = "in_progress")))
        assertEquals(listOf("pe2"), SignupLogic.newSignups(old, new).map { it.participantEventId })
    }

    @Test fun reinstatementsAreNot() {
        val old = Roster("e1", listOf(mia, person(2, "Ollie Smith", "withdrawn"), person(3, "Ava Jones", "rejected")))
        val new = Roster("e1", listOf(mia, ollie, ava))
        assertTrue(SignupLogic.newSignups(old, new).isEmpty())
    }

    @Test fun onlyRostersSyncedSinceTurningItOnAreABaseline() {
        val on = AppSettings(signupNotifications = true, signupNotificationsSince = "2026-10-03T10:00:00Z")
        assertTrue(SignupLogic.shouldNotify(on, "2026-10-03T10:15:00Z"))
        assertTrue(SignupLogic.shouldNotify(on, "2026-10-03T10:00:00Z"))
        // Cached from before it was on: everyone since then would show up as new.
        assertFalse(SignupLogic.shouldNotify(on, "2026-10-01T09:00:00Z"))
        assertFalse(SignupLogic.shouldNotify(on, null))
        assertFalse(SignupLogic.shouldNotify(on.copy(signupNotifications = false), "2026-10-03T10:15:00Z"))
        assertFalse(SignupLogic.shouldNotify(on.copy(signupNotificationsSince = null), "2026-10-03T10:15:00Z"))
    }

    @Test fun updatesToPeopleAlreadySignedUpAreNot() {
        val old = Roster("e1", listOf(mia, ava))
        val new = Roster("e1", listOf(mia.copy(checkedInAt = "2026-10-03T00:00:00Z"), ava.copy(status = "complete")))
        assertTrue(SignupLogic.newSignups(old, new).isEmpty())
    }

    @Test fun wording() {
        assertEquals("New signup for Campfire", SignupLogic.title(1, "Campfire"))
        assertEquals("3 new signups for Campfire", SignupLogic.title(3, "Campfire"))
        assertEquals("2 new signups", SignupLogic.title(2, null))
        assertEquals("New signups for Campfire", SignupLogic.groupTitle("Campfire"))
        assertEquals("Mia Chen", SignupLogic.summary(listOf(mia)))
        assertEquals("Mia Chen and Ollie Smith", SignupLogic.summary(listOf(mia, ollie)))
        assertEquals("Mia Chen, Ollie Smith and Ava Jones", SignupLogic.summary(listOf(mia, ollie, ava)))
        val five = listOf(mia, ollie, ava, person(4, "Kai"), person(5, "Zoë"))
        assertEquals("Mia Chen, Ollie Smith, Ava Jones and 2 others", SignupLogic.summary(five))
        assertEquals("Mia Chen, Ollie Smith, Ava Jones and 1 other", SignupLogic.summary(five.take(4)))
    }

    // ---- ParticipantRepository.sync ----

    private val server = MockWebServer()
    private var roster: List<Participant> = emptyList()
    private lateinit var cache: JsonCache
    private lateinit var repo: ParticipantRepository
    private val notified = mutableListOf<Pair<String, List<String>>>()
    private val previousSyncs = mutableListOf<String?>()

    private val tokens = object : TokenStore {
        override val token: String = "t"
        override fun update(token: String?, expiresAt: String?) {}
    }

    @Before fun setUp() = runBlocking {
        SecureBox.injectForTesting(IdentityCipher)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                    .body(AttendJson.encodeToString(ParticipantsResponse.serializer(), ParticipantsResponse(roster, Instant.now().toString())))
                    .build()
        }
        server.start()
        cache = JsonCache(ApplicationProvider.getApplicationContext())
        cache.clear()
        repo = ParticipantRepository(AttendApi(tokens, onSessionExpired = {}, baseUrl = server.url("/").toString().trimEnd('/')), cache)
        repo.onNewSignups = { eventId, signups, previousSyncAt ->
            notified += eventId to signups.map { it.name }
            previousSyncs += previousSyncAt
        }
    }

    @After fun tearDown() {
        server.close()
        SecureBox.resetForTesting()
    }

    @Test fun firstDownloadIsNotASignup() = runBlocking {
        roster = listOf(mia, ollie)
        repo.sync("e1")
        assertTrue(notified.isEmpty())
    }

    @Test fun laterSyncsReportWhoIsNew() = runBlocking {
        roster = listOf(mia)
        repo.sync("e1")
        val firstSync = repo.roster("e1")!!.lastSyncAt
        roster = listOf(ollie) // delta: only what changed
        repo.sync("e1")
        assertEquals(listOf("e1" to listOf("Ollie Smith")), notified)
        assertEquals(listOf(firstSync), previousSyncs)
        // Nothing new: no call.
        roster = listOf(ollie.copy(checkedInAt = "2026-10-03T00:00:00Z"))
        repo.sync("e1")
        assertEquals(1, notified.size)
        // A full sync compares against the cached roster too.
        roster = listOf(mia, ollie, ava)
        repo.sync("e1", forceFull = true)
        assertEquals("e1" to listOf("Ava Jones"), notified.last())
    }

    @Test fun aFailingNotifierDoesNotFailTheSync() = runBlocking {
        repo.onNewSignups = { _, _, _ -> error("boom") }
        roster = listOf(mia)
        repo.sync("e1")
        roster = listOf(ollie)
        assertEquals(2, repo.sync("e1").participants.size)
    }
}
