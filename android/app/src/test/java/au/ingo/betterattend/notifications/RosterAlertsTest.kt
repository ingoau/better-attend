package au.ingo.betterattend.notifications

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Spotting signups and withdrawals between roster syncs, and how the notifications word them. */
@RunWith(RobolectricTestRunner::class)
class RosterAlertsTest {
    private fun person(n: Int, name: String, status: String? = "complete") =
        Participant(participantId = "p$n", participantEventId = "pe$n", displayName = name, status = status)

    private val mia = person(1, "Mia Chen")
    private val ollie = person(2, "Ollie Smith")
    private val ava = person(3, "Ava Jones", status = "in_progress")

    // ---- Signups ----

    @Test fun newRegistrationsAreSignups() {
        val old = Roster("e1", listOf(mia))
        val new = Roster("e1", listOf(ava, mia, ollie))
        assertEquals(listOf("pe3", "pe2"), RosterAlerts.newSignups(old, new).map { it.participantEventId })
    }

    @Test fun invitesWithdrawalsAndRejectionsAreNotSignups() {
        val old = Roster("e1", listOf(mia))
        val new = Roster("e1", listOf(mia, person(2, "Invited", "invited"), person(3, "Gone", "withdrawn"), person(4, "No", "rejected")))
        assertTrue(RosterAlerts.newSignups(old, new).isEmpty())
    }

    @Test fun anInviteeWhoStartsRegisteringIsASignup() {
        val invited = person(2, "Ollie Smith", "invited")
        val old = Roster("e1", listOf(mia, invited))
        val new = Roster("e1", listOf(mia, invited.copy(status = "in_progress")))
        assertEquals(listOf("pe2"), RosterAlerts.newSignups(old, new).map { it.participantEventId })
    }

    @Test fun reinstatementsAreNotSignups() {
        val old = Roster("e1", listOf(mia, person(2, "Ollie Smith", "withdrawn"), person(3, "Ava Jones", "rejected")))
        assertTrue(RosterAlerts.newSignups(old, Roster("e1", listOf(mia, ollie, ava))).isEmpty())
    }

    @Test fun updatesToPeopleAlreadySignedUpAreNotSignups() {
        val old = Roster("e1", listOf(mia, ava))
        val new = Roster("e1", listOf(mia.copy(checkedInAt = "2026-10-03T00:00:00Z"), ava.copy(status = "complete")))
        assertTrue(RosterAlerts.changes(old, new).isEmpty())
    }

    // ---- Withdrawals ----

    @Test fun peopleWhoWereSignedUpAndWithdrawAreWithdrawals() {
        val old = Roster("e1", listOf(mia, ollie, ava))
        val new = Roster("e1", listOf(mia, ollie.copy(status = "withdrawn"), ava.copy(status = "withdrawn")))
        assertEquals(listOf("pe2", "pe3"), RosterAlerts.newWithdrawals(old, new).map { it.participantEventId })
    }

    @Test fun rejectionsInviteesAndStrangersAreNotWithdrawals() {
        val invited = person(2, "Ollie Smith", "invited")
        val old = Roster("e1", listOf(mia, invited, person(3, "Gone", "withdrawn")))
        val new = Roster("e1", listOf(
            mia.copy(status = "rejected"), invited.copy(status = "withdrawn"), person(3, "Gone", "withdrawn"), person(4, "New", "withdrawn"),
        ))
        assertTrue(RosterAlerts.newWithdrawals(old, new).isEmpty())
    }

    // ---- Baseline and wording ----

    @Test fun onlyRostersSyncedSinceTurningItOnAreABaseline() {
        val since = "2026-10-03T10:00:00Z"
        assertTrue(RosterAlerts.isBaseline(since, "2026-10-03T10:15:00Z"))
        assertTrue(RosterAlerts.isBaseline(since, since))
        // Cached from before it was on: everything since then would show up as new.
        assertFalse(RosterAlerts.isBaseline(since, "2026-10-01T09:00:00Z"))
        assertFalse(RosterAlerts.isBaseline(since, null))
        assertFalse(RosterAlerts.isBaseline(null, "2026-10-03T10:15:00Z"))
    }

    @Test fun wording() {
        assertEquals("New signup for Campfire", RosterAlerts.signupTitle(1, "Campfire"))
        assertEquals("3 new signups for Campfire", RosterAlerts.signupTitle(3, "Campfire"))
        assertEquals("2 new signups", RosterAlerts.signupTitle(2, null))
        assertEquals("New signups for Campfire", RosterAlerts.signupGroupTitle("Campfire"))
        assertEquals("Withdrawal from Campfire", RosterAlerts.withdrawalTitle(1, "Campfire"))
        assertEquals("2 withdrawals from Campfire", RosterAlerts.withdrawalTitle(2, "Campfire"))
        assertEquals("Withdrawals", RosterAlerts.withdrawalGroupTitle(" "))
        assertEquals("Mia", RosterAlerts.summary(listOf("Mia")))
        assertEquals("Mia and Ollie", RosterAlerts.summary(listOf("Mia", "Ollie")))
        assertEquals("Mia, Ollie and Ava", RosterAlerts.summary(listOf("Mia", "Ollie", "Ava")))
        assertEquals("Mia, Ollie, Ava and 2 others", RosterAlerts.summary(listOf("Mia", "Ollie", "Ava", "Kai", "Zoë")))
        assertEquals("Mia, Ollie, Ava and 1 other", RosterAlerts.summary(listOf("Mia", "Ollie", "Ava", "Kai")))
    }

    // ---- ParticipantRepository.sync ----

    private val server = MockWebServer()
    private var roster: List<Participant> = emptyList()
    private lateinit var cache: JsonCache
    private lateinit var repo: ParticipantRepository
    private val reported = mutableListOf<Pair<RosterChanges, String?>>()

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
        repo.onRosterChanges = { _, changes, previousSyncAt -> reported += changes to previousSyncAt }
    }

    @After fun tearDown() {
        server.close()
        SecureBox.resetForTesting()
    }

    private fun names(people: List<Participant>) = people.map { it.name }

    @Test fun firstDownloadReportsNothing() = runBlocking {
        roster = listOf(mia, ollie)
        repo.sync("e1")
        assertTrue(reported.isEmpty())
    }

    @Test fun laterSyncsReportWhatChanged() = runBlocking {
        roster = listOf(mia, ava)
        repo.sync("e1")
        val firstSync = repo.roster("e1")!!.lastSyncAt
        roster = listOf(ollie, ava.copy(status = "withdrawn")) // delta: only what changed
        repo.sync("e1")
        assertEquals(1, reported.size)
        assertEquals(listOf("Ollie Smith"), names(reported[0].first.signups))
        assertEquals(listOf("Ava Jones"), names(reported[0].first.withdrawals))
        assertEquals(firstSync, reported[0].second)
        // Nothing new: no call.
        roster = listOf(ollie.copy(checkedInAt = "2026-10-03T00:00:00Z"))
        repo.sync("e1")
        assertEquals(1, reported.size)
        // A full sync compares against the cached roster too.
        roster = listOf(mia, ollie, ava.copy(status = "withdrawn"), person(4, "Kai Tanaka"))
        repo.sync("e1", forceFull = true)
        assertEquals(listOf("Kai Tanaka"), names(reported.last().first.signups))
        assertTrue(reported.last().first.withdrawals.isEmpty())
    }

    @Test fun aFailingNotifierDoesNotFailTheSync() = runBlocking {
        repo.onRosterChanges = { _, _, _ -> error("boom") }
        roster = listOf(mia)
        repo.sync("e1")
        roster = listOf(ollie)
        assertEquals(2, repo.sync("e1").participants.size)
    }
}
