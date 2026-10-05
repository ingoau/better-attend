package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Travel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleFilterTest {
    private fun p(
        id: Int,
        name: String,
        status: String = "complete",
        checkedInAt: String? = null,
        email: String? = null,
        pronouns: String? = null,
        anaphylaxis: Boolean = false,
        highSupport: Boolean = false,
        fridge: Boolean = false,
        waiver: Boolean = true,
        nfc: Boolean = false,
        inbound: Travel? = null,
        outbound: Travel? = null,
        diet: String? = null,
        scans: List<ContextScanSummary> = emptyList(),
    ) = Participant(
        participantId = "0000000$id-aaaa-4bbb-8ccc-dddddddddddd",
        participantEventId = "pe$id",
        displayName = name.substringBefore(' '), fullName = name, email = email, pronouns = pronouns,
        status = status, checkedInAt = checkedInAt, hasAnaphylaxisRisk = anaphylaxis, highSupportFlag = highSupport,
        requiresRefrigeration = fridge, waiverSigned = waiver, nfcBadgeAssigned = nfc,
        travelInbound = inbound, travelOutbound = outbound, dietType = diet, scansByContext = scans,
    )

    private val zoe = p(1, "Zoë Adams", checkedInAt = "2026-10-03T09:00:00Z", email = "zoe@example.com", pronouns = "she/her",
        scans = listOf(ContextScanSummary("c1", "Desk", checksIn = true, scanCount = 1, firstScannedAt = "2026-10-03T09:00:00Z")))
    private val arjun = p(2, "arjun Patel", anaphylaxis = true, inbound = Travel(mode = "plane"), diet = "vegan")
    private val bea = p(3, "Bea Brown", status = "in_progress", waiver = false, nfc = true, outbound = Travel(mode = "train"))
    private val carl = p(4, "Carl Chen", status = "withdrawn", checkedInAt = "2026-10-03T08:00:00Z")
    private val dan = p(5, "Dan Diaz", checkedInAt = "2026-10-03T10:00:00Z", highSupport = true,
        scans = listOf(ContextScanSummary("c2", "Airport", scanCount = 2)))
    private val all = listOf(zoe, arjun, bea, carl, dan)

    private fun run(query: String = "", quick: QuickFilter = QuickFilter.All, options: FilterOptions = FilterOptions(), sort: SortOrder = SortOrder.Name, sensitive: Boolean = true) =
        PeopleFilter.apply(all, query, quick, options, sort, sensitive)

    @Test fun quickFilterCounts() {
        val counts = run().counts
        assertEquals(4, counts[QuickFilter.All]) // withdrawn hidden
        assertEquals(2, counts[QuickFilter.Here]) // zoe, dan (carl withdrawn)
        assertEquals(1, counts[QuickFilter.NotHere]) // arjun (bea isn't complete)
        assertEquals(2, counts[QuickFilter.NeedsAttention])
        assertEquals(1, counts[QuickFilter.NotComplete])
        assertEquals(1, counts[QuickFilter.Withdrawn])
    }

    @Test fun withdrawnOnlyInWithdrawnChip() {
        assertFalse(run().participants.contains(carl))
        assertEquals(listOf(carl), run(quick = QuickFilter.Withdrawn).participants)
    }

    @Test fun searchIsAccentAndCaseInsensitiveAndMultiTerm() {
        assertEquals(listOf(zoe), run("zoe").participants)
        assertEquals(listOf(zoe), run("ADAMS she").participants)
        assertEquals(listOf(zoe), run("example.com").participants)
        assertTrue(run("zoe nobody").participants.isEmpty())
    }

    @Test fun searchMatchesShortCode() {
        assertEquals(listOf(arjun), run("00000002").participants)
    }

    @Test fun countsFollowSearch() {
        val counts = run("dan").counts
        assertEquals(1, counts[QuickFilter.All])
        assertEquals(1, counts[QuickFilter.Here])
        assertEquals(0, counts[QuickFilter.NotHere])
    }

    @Test fun scannedAtContext() {
        assertEquals(listOf(dan), run(options = FilterOptions(scannedAtContextId = "c2")).participants)
        assertEquals(listOf(arjun, bea, zoe), run(options = FilterOptions(scannedAtContextId = "c2", notScannedAtContext = true)).participants)
    }

    @Test fun travelFilters() {
        assertEquals(listOf(arjun), run(options = FilterOptions(inboundTravel = TriState.Yes)).participants)
        assertEquals(listOf(bea), run(options = FilterOptions(travelModes = setOf("train"))).participants)
        assertEquals(listOf(bea, dan, zoe), run(options = FilterOptions(inboundTravel = TriState.No)).participants)
    }

    @Test fun waiverNfcStatus() {
        assertEquals(listOf(bea), run(options = FilterOptions(waiverSigned = TriState.No)).participants)
        assertEquals(listOf(bea), run(options = FilterOptions(nfcAssigned = TriState.Yes)).participants)
        assertEquals(listOf(bea), run(options = FilterOptions(statuses = setOf("in_progress"))).participants)
        assertEquals(3, FilterOptions(waiverSigned = TriState.No, nfcAssigned = TriState.Yes, statuses = setOf("x")).activeCount)
    }

    @Test fun dietFilterIgnoredWithoutSensitiveAccess() {
        assertEquals(listOf(arjun), run(options = FilterOptions(dietTypes = setOf("vegan"))).participants)
        assertEquals(4, run(options = FilterOptions(dietTypes = setOf("vegan")), sensitive = false).participants.size)
    }

    @Test fun sortByName() {
        assertEquals(listOf(arjun, bea, dan, zoe), run().participants)
    }

    @Test fun sortByRecentCheckIn() {
        assertEquals(listOf(dan, zoe, arjun, bea), run(sort = SortOrder.RecentCheckIn).participants)
    }

    @Test fun sortByStatus() {
        // Checked in first, then complete, then in progress.
        assertEquals(listOf(dan, zoe, arjun, bea), run(sort = SortOrder.Status).participants)
    }

    @Test fun headersOnlyWhenSortedByName() {
        val items = PeopleFilter.withHeaders(run().participants, SortOrder.Name)
        assertEquals(listOf("A", "B", "D", "Z"), items.filterIsInstance<PeopleListItem.Header>().map { it.letter })
        assertEquals(8, items.size)
        assertTrue(PeopleFilter.withHeaders(run().participants, SortOrder.Status).none { it is PeopleListItem.Header })
    }

    @Test fun segmentPositionsRestartUnderEachHeader() {
        val h = { l: String -> PeopleListItem.Header(l) }
        val r = { id: Int -> PeopleListItem.Person(p(id, "P$id")) }
        val items = listOf(h("A"), r(1), r(2), r(3), h("B"), r(4))
        assertEquals(listOf(null, 0 to 3, 1 to 3, 2 to 3, null, 0 to 1), segmentPositions(items))
        // Without headers (status sort) the whole list is one group.
        assertEquals(listOf(0 to 2, 1 to 2), segmentPositions(listOf(r(1), r(2))))
        assertEquals(emptyList<Pair<Int, Int>?>(), segmentPositions(emptyList()))
    }

    @Test fun nonLetterNamesGoUnderHash() {
        assertEquals("#", PeopleFilter.sectionLetter(p(9, "42 Robot")))
        assertEquals("E", PeopleFilter.sectionLetter(p(9, "Élodie Martin")))
    }

    @Test fun checkInScanPicksEarliestCheckInContext() {
        val x = p(7, "X", scans = listOf(
            ContextScanSummary("a", "Lunch", checksIn = false, firstScannedAt = "2026-10-03T07:00:00Z"),
            ContextScanSummary("b", "Late desk", checksIn = true, firstScannedAt = "2026-10-03T09:00:00Z"),
            ContextScanSummary("c", "Desk", checksIn = true, firstScannedAt = "2026-10-03T08:00:00Z"),
        ))
        assertEquals("Desk", PeopleFilter.checkInScan(x)?.scanContextName)
    }
}
