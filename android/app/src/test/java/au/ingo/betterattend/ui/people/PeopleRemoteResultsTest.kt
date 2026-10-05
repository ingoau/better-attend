package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.ParticipantChange
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Server search results follow edits and removals made from someone's page. */
class PeopleRemoteResultsTest {
    private val event = SampleData.event
    private val mia = Participant("p1", "pe1", displayName = "Mia", phone = "0400 000 000")
    private val leo = Participant("p2", "pe2", displayName = "Leo")
    private val state = PeopleUiState(event = event, query = "zz", remoteResults = listOf(mia, leo))

    @Test fun removal_dropsThePerson() {
        assertEquals(listOf(leo), state.withChange(ParticipantChange.Removed(event.id, "pe1")).remoteResults)
    }

    @Test fun edit_replacesTheirCopy_includingClearedFields() {
        val edited = mia.copy(displayName = "Mia C", phone = null)
        val after = state.withChange(ParticipantChange.Edited(event.id, edited)).remoteResults!!
        assertEquals("Mia C", after.first().displayName)
        assertNull(after.first().phone)
        assertEquals(leo, after[1])
    }

    @Test fun otherEventsAndNoResults_areLeftAlone() {
        assertEquals(state, state.withChange(ParticipantChange.Removed("other-event", "pe1")))
        val none = state.copy(remoteResults = null)
        assertEquals(none, none.withChange(ParticipantChange.Removed(event.id, "pe1")))
    }
}
