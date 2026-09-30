package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Accessibility
import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.DietaryDetail
import au.ingo.betterattend.data.model.EmergencyContact
import au.ingo.betterattend.data.model.Group
import au.ingo.betterattend.data.model.Guardian
import au.ingo.betterattend.data.model.Address
import au.ingo.betterattend.data.model.SafeguardingDetail
import au.ingo.betterattend.data.model.Travel
import au.ingo.betterattend.data.model.TravelLeg
import au.ingo.betterattend.ui.people.DetailUiState
import au.ingo.betterattend.ui.people.NfcWriteSheetContent
import au.ingo.betterattend.ui.people.NfcWriteState
import au.ingo.betterattend.ui.people.NoteItem
import au.ingo.betterattend.ui.people.NotesSection
import au.ingo.betterattend.ui.people.ParticipantDetailContent
import au.ingo.betterattend.ui.people.UndoCheckInCard
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

object DetailFixtures {
    val now: Instant = Instant.parse("2026-10-03T01:32:00Z")

    val sensitive = SampleData.participantDetail.copy(
        medications = "EpiPen (x2), insulin (keep cold)",
        lifeThreateningAllergies = "Peanuts",
        dietType = "vegetarian",
        freedomWaiverGranted = false,
        groups = listOf(Group("g1", "Team Blue", "#3b82f6"), Group("g2", "Workshop B", "#33d6a6")),
        scansByContext = listOf(
            ContextScanSummary("c2", "Airport pickup", isTravelPickup = true, scanCount = 1, firstScannedAt = "2026-10-02T23:05:00Z", lastScannedAt = "2026-10-02T23:05:00Z"),
            ContextScanSummary("c1", "Check-in desk", checksIn = true, scanCount = 2, firstScannedAt = "2026-10-03T00:41:00Z", lastScannedAt = "2026-10-03T01:02:00Z"),
        ),
        checkedInAt = "2026-10-03T00:41:00Z",
        personal = SampleData.participantDetail.personal?.copy(
            address = Address(line1 = "12 Home St", city = "Melbourne", state = "VIC", postalCode = "3000", country = "AU"),
        ),
        travelInbound = Travel(
            direction = "inbound", mode = "plane", carrier = "Qantas", flightNumber = "QF402", departureCity = "Melbourne", arrivalCity = "Sydney",
            isUnaccompaniedMinor = true, passportNationality = "AU",
            legs = listOf(TravelLeg(flightCode = "QF402", departureAirport = "MEL", arrivalAirport = "SYD", departureTime = "2026-10-02T21:00:00Z",
                arrivalTime = "2026-10-02T22:25:00Z", liveStatus = "Landed", liveArrivalTime = "2026-10-02T22:18:00Z", travelPickedUpAt = "2026-10-02T23:05:00Z")),
        ),
        travelOutbound = Travel(direction = "outbound", mode = "train", trainDepartureStation = "Central", trainArrivalStation = "Southern Cross",
            departureTime = "2026-10-05T04:00:00Z", arrivalTime = "2026-10-05T15:00:00Z", notes = "Parent meeting at Southern Cross"),
        dietaryDetail = DietaryDetail(intolerances = "Lactose"),
        accessibility = Accessibility(noiseSensitivity = true, needsCaptioning = true, hasAdhd = true, sensoryNeeds = "Ear defenders in bag; quiet space helps."),
        safeguardingDetail = SafeguardingDetail(authorizedPickupAdults = "Jordan Chen, Alex Chen", highSupportNotes = null, otherInstructions = "Check in with Maya each evening."),
        guardians = listOf(
            Guardian(name = "Jordan Chen", phone = "+61411111111", email = "jordan@example.com", relationship = "Parent", isPrimary = true, status = "completed",
                mediaPermission = true, photoPermission = false, travelPermission = true, emergencyMedicalConsent = true, otcMedicationConsent = true,
                emergencyContacts = listOf(EmergencyContact(name = "Alex Chen", phone = "+61422222222", relationship = "Aunt", priority = 2))),
        ),
    )

    val eventSensitive = SampleData.event.copy(role = "safeguarding_lead", canViewSensitiveData = true, canViewParticipantPii = true)

    /** What the API returns to a `limited` staffer: no phone/DOB/address, no sensitive blocks. */
    val limited = sensitive.copy(
        phone = null, allergies = null, medications = null, lifeThreateningAllergies = null, dietType = null, freedomWaiverGranted = null,
        emergencyContacts = null, medicalDetail = null, dietaryDetail = null, accessibility = null, safeguardingDetail = null,
        personal = sensitive.personal?.copy(dateOfBirth = null, address = null),
        guardians = sensitive.guardians?.map { g -> g.copy(email = null, phone = null, emergencyContacts = g.emergencyContacts.map { it.copy(name = "Alex", email = null, relationship = null) }) },
        travelInbound = sensitive.travelInbound?.copy(originAddress = null),
        checkedInAt = null, scansByContext = sensitive.scansByContext.take(1),
    )
    val eventLimited = SampleData.event.copy(role = "limited", canViewSensitiveData = false, canViewParticipantPii = false)

    val notes = SampleData.notes.map { NoteItem(it) } + NoteItem(
        SampleData.notes[0].copy(id = "local-1", content = "Picked up spare charger from lost property.", createdAt = "2026-10-03T01:31:00Z"),
        failed = true,
    )

    fun state(event: au.ingo.betterattend.data.model.Event, p: au.ingo.betterattend.data.model.Participant) = DetailUiState(
        event = event, participant = p, detailLoaded = true, loading = false, notes = notes, contexts = SampleData.contexts,
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ParticipantDetailScreenshots : ScreenshotTest() {
    private val now = DetailFixtures.now

    @Test fun sensitiveTop() = snap("detail_sensitive_top") {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventSensitive, DetailFixtures.sensitive), now = now)
    }

    @Test fun limitedTop() = snap("detail_limited_top") {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventLimited, DetailFixtures.limited), now = now)
    }

    @Test fun rosterCopyWhileLoading() = snap("detail_loading") {
        ParticipantDetailContent(
            DetailUiState(event = DetailFixtures.eventSensitive, participant = SampleData.participants[4], loading = true, contexts = SampleData.contexts),
            now = now,
        )
    }

    @Test fun undoDialog() = snap("detail_undo_dialog") {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            UndoCheckInCard("Maya", DetailFixtures.sensitive.scansByContext, "Australia/Sydney", "c1", {}, {}, {})
        }
    }

    @Test fun notesComposer() = snap("detail_notes") {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            NotesSection(
                DetailFixtures.notes, null, now, { _, _, _ -> true }, {}, {},
                initialDraft = "Asked for a vegetarian lunch swap on Sunday.", initialType = "logistical", initialSensitivity = "restricted",
            )
        }
    }

    @Test fun nfcWaiting() = snap("nfc_waiting") { NfcWriteSheetContent(NfcWriteState.Waiting("tok", hasSlackLink = true), "Maya", {}, {}, {}, animate = false) }
    @Test fun nfcWriting() = snap("nfc_writing") { NfcWriteSheetContent(NfcWriteState.Writing, "Maya", {}, {}, {}, animate = false) }
    @Test fun nfcSuccess() = snap("nfc_success") { NfcWriteSheetContent(NfcWriteState.Success, "Maya", {}, {}, {}, animate = false) }
    @Test fun nfcDisabled() = snap("nfc_disabled") { NfcWriteSheetContent(NfcWriteState.Disabled, "Maya", {}, {}, {}, animate = false) }
    @Test fun nfcFailed() = snap("nfc_failed") {
        NfcWriteSheetContent(NfcWriteState.Failed("NFC badges are not enabled for this event", retryable = false), "Maya", {}, {}, {}, animate = false)
    }
}

/** Full-length renders so every section is visible in one image. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h5200dp-mdpi")
class ParticipantDetailFullScreenshots : ScreenshotTest() {
    private val now = DetailFixtures.now

    @Test fun sensitiveFull() = snap("detail_sensitive_full") {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventSensitive, DetailFixtures.sensitive), now = now)
    }

    @Test fun limitedFull() = snap("detail_limited_full") {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventLimited, DetailFixtures.limited), now = now)
    }
}
