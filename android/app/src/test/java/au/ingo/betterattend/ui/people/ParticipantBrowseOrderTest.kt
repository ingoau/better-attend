package au.ingo.betterattend.ui.people

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ParticipantBrowseOrderTest {
    @Before fun clear() { ParticipantBrowseOrder.take("", "") }

    @Test fun openedFromList_pagesThroughThatList() {
        ParticipantBrowseOrder.set("e1", "b", listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), ParticipantBrowseOrder.take("e1", "b"))
    }

    @Test fun handOffIsConsumedOnce() {
        ParticipantBrowseOrder.set("e1", "b", listOf("a", "b", "c"))
        ParticipantBrowseOrder.take("e1", "b")
        // Opened again later from the scanner or dashboard: just that person.
        assertEquals(listOf("b"), ParticipantBrowseOrder.take("e1", "b"))
    }

    @Test fun nothingSet_singlePage() {
        assertEquals(listOf("x"), ParticipantBrowseOrder.take("e1", "x"))
    }

    @Test fun handOffForSomeoneElse_isIgnored() {
        ParticipantBrowseOrder.set("e1", "a", listOf("a", "b"))
        assertEquals(listOf("b"), ParticipantBrowseOrder.take("e1", "b"))
        // ...and doesn't linger for a later open either.
        assertEquals(listOf("a"), ParticipantBrowseOrder.take("e1", "a"))
    }

    @Test fun otherEvent_isIgnored() {
        ParticipantBrowseOrder.set("e1", "a", listOf("a", "b"))
        assertEquals(listOf("a"), ParticipantBrowseOrder.take("e2", "a"))
    }

    @Test fun duplicatesDropped() {
        ParticipantBrowseOrder.set("e1", "a", listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), ParticipantBrowseOrder.take("e1", "a"))
    }
}
