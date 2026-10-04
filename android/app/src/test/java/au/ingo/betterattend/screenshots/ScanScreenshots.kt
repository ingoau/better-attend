package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.ScanContextRef
import au.ingo.betterattend.data.model.ScanResult
import au.ingo.betterattend.data.repo.PendingScan
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanLogEntry
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.data.repo.ScanRejection
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.scan.NfcStatus
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.scan.CameraAccess
import au.ingo.betterattend.ui.scan.CameraPlaceholder
import au.ingo.betterattend.ui.scan.ContextListContent
import au.ingo.betterattend.ui.scan.ContextSelector
import au.ingo.betterattend.ui.scan.FindPersonContent
import au.ingo.betterattend.ui.scan.PendingQueueContent
import au.ingo.betterattend.ui.scan.RecentScansContent
import au.ingo.betterattend.ui.scan.ResultKind
import au.ingo.betterattend.ui.scan.ScanActions
import au.ingo.betterattend.ui.scan.ScanCard
import au.ingo.betterattend.ui.scan.ScanContent
import au.ingo.betterattend.ui.scan.ScanUiState
import au.ingo.betterattend.ui.scan.SearchUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScanScreenshots : ScreenshotTest() {
    private val event = SampleData.event
    private val maya = SampleData.participants[2] // anaphylaxis + refrigeration
    private val noah = SampleData.participants[5] // high support
    private val sam = SampleData.participants[0]

    private val base = ScanUiState(
        event = event,
        contexts = SampleData.contexts,
        selectedContextId = "c1",
        nfc = NfcStatus.Ready,
        torchAvailable = true,
    )

    private fun card(kind: ResultKind, title: String, message: String? = null, p: au.ingo.betterattend.data.model.Participant? = null, undo: Boolean = false, retry: Boolean = false) =
        ScanCard(key = kind.name, kind = kind, title = title, message = message, participant = p, contextName = "Check-in desk", contextId = "c1", canUndo = undo, retryable = retry)

    @Composable
    private fun Screen(state: ScanUiState) = ScanContent(
        state = state,
        user = SampleData.user,
        actions = ScanActions(onDetails = {}),
        camera = { CameraPlaceholder() },
    )

    @Test fun ready() = snap("scan_ready") { Screen(base) }

    @Test fun scanned() = snap("scan_card_scanned") {
        Screen(base.copy(card = card(ResultKind.Scanned, "Scanned", "Checked in", maya, undo = true)))
    }

    @Test fun alreadyScanned() = snap("scan_card_already") {
        Screen(base.copy(card = card(ResultKind.AlreadyScanned, "Already scanned", "First scanned at 9:41 AM", noah)))
    }

    @Test fun savedOffline() = snap("scan_card_offline") {
        Screen(base.copy(pendingCount = 3, card = card(ResultKind.SavedOffline, "Saved offline", "Offline, will confirm later", sam)
            .copy(rosterNote = "Roster from 14 min ago")))
    }

    @Test fun savedOfflineStaleRoster() = snap("scan_card_offline_stale") {
        Screen(base.copy(pendingCount = 3, card = card(ResultKind.SavedOffline, "Saved offline", "Offline, will confirm later", sam)
            .copy(rosterNote = "Roster from 3 h ago", rosterStale = true)))
    }

    @Test fun confirming() = snap("scan_card_confirming") {
        Screen(base.copy(inFlight = 1, card = card(ResultKind.Confirming, "Confirming…", p = maya)))
    }

    @Test fun offlineRejected() = snap("scan_card_offline_rejected") {
        val withdrawn = SampleData.participants[10]
        Screen(base.copy(card = card(ResultKind.Rejected, "Withdrawn", "Isla has withdrawn from this event.", withdrawn, retry = true)
            .copy(rosterNote = "Offline, not saved · Roster from 14 min ago")))
    }

    @Test fun serverRejectedInterrupt() = snap("scan_server_rejected") {
        Screen(base.copy(
            card = card(ResultKind.Rejected, "Consent not signed", "Maya's waiver hasn't been signed. Sort it out before checking them in. Scan removed.", maya),
            alerts = listOf(ScanRejection("a1", event.id, maya.participantEventId, maya.fullName!!, "consent not signed", "2026-10-03T00:41:00Z", "Check-in desk")),
        ))
    }

    @Test fun storageUnavailable() = snap("scan_storage_unavailable") { Screen(base.copy(storageUnavailable = true)) }

    @Test fun notRegistered() = snap("scan_card_not_registered") {
        Screen(base.copy(card = card(ResultKind.Rejected, "Not registered", "No registration for this event matches that code.")))
    }

    @Test fun failedRetry() = snap("scan_card_failed") {
        Screen(base.copy(card = card(ResultKind.Rejected, "Couldn't scan", "Your session has expired. Please sign in again.", sam, retry = true)))
    }

    @Test fun notAttend() = snap("scan_card_not_attend") {
        Screen(base.copy(card = card(ResultKind.Rejected, "Not an Attend code", "This QR code isn't an Attend ticket or badge.").copy(contextName = null)))
    }

    @Test fun checking() = snap("scan_card_checking") {
        Screen(base.copy(inFlight = 1, card = card(ResultKind.Checking, "Checking…", p = sam)))
    }

    @Test fun undone() = snap("scan_card_undone") {
        Screen(base.copy(card = card(ResultKind.Undone, "Scan undone", "Maya is no longer marked as scanned here", maya)))
    }

    @Test fun permissionNeeded() = snap("scan_permission") {
        Screen(base.copy(camera = CameraAccess.NeedsRequest, nfc = NfcStatus.Disabled))
    }

    @Test fun permissionBlocked() = snap("scan_permission_blocked") {
        Screen(base.copy(camera = CameraAccess.PermanentlyDenied, contexts = SampleData.contexts.take(1)))
    }

    @Test fun noEvent() = snap("scan_no_event") { Screen(ScanUiState(event = null)) }

    private val manyContexts = SampleData.contexts + listOf(
        ScanContext("c4", "Hotel check-in", position = 3),
        ScanContext("c5", "Workshop: Rust", position = 4, startsAt = "2026-10-04T15:00:00+10:00", endsAt = "2026-10-04T16:00:00+10:00"),
        ScanContext("c6", "Bus to venue", position = 5),
    )

    @Test fun contextSelectors() = snap("scan_context_selector") {
        Column(Modifier.padding(16.dp)) {
            ContextSelector(SampleData.contexts, "c2", event.timezone, null, {}, {}, Modifier.padding(bottom = 16.dp))
            ContextSelector(SampleData.contexts.take(1), "c1", event.timezone, null, {}, {}, Modifier.padding(bottom = 16.dp))
            ContextSelector(manyContexts, "c5", event.timezone, null, {}, {}, Modifier.padding(bottom = 16.dp))
            ContextSelector(null, null, event.timezone, "You're offline. Check your connection.", {}, {}, Modifier.padding(bottom = 16.dp))
        }
    }

    @Test fun contextSheet() = snap("scan_context_sheet") { ContextListContent(manyContexts, "c3", event.timezone) {} }

    @Test fun findPerson() = snap("scan_find_person") {
        FindPersonContent(
            state = SearchUiState(query = "ma", results = SampleData.participants.filter { it.name.startsWith("Ma") || it.name.startsWith("Sam") || it.name == "Isla" }, remoteLoading = true),
            contextName = "Check-in desk", checksIn = true, timezone = event.timezone, selectedContextId = "c1",
            onQuery = {}, onCheckIn = {}, onSubmitDirect = {}, autoFocus = false,
        )
    }

    @Test fun findPersonDirect() = snap("scan_find_person_id") {
        val id = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        FindPersonContent(
            state = SearchUiState(query = id, directInput = ScanInput(participantId = id, source = "manual"), results = listOf(sam)),
            contextName = "Saturday lunch", checksIn = false, timezone = event.timezone, selectedContextId = "c3",
            onQuery = {}, onCheckIn = {}, onSubmitDirect = {}, autoFocus = false,
        )
    }

    @Test fun findPersonEmpty() = snap("scan_find_person_hint") {
        FindPersonContent(
            state = SearchUiState(), contextName = "Check-in desk", checksIn = true, timezone = event.timezone, selectedContextId = "c1",
            onQuery = {}, onCheckIn = {}, onSubmitDirect = {}, autoFocus = false,
        )
    }

    private fun pending(i: Int, name: String?) = PendingScan(
        clientScanId = "p$i", eventId = event.id, scanContextId = "c1", scanContextName = "Check-in desk",
        input = ScanInput(participantId = "attend://checkin/x$i"), scannedAt = "2026-10-03T0${i}:1$i:00Z", attempts = 1,
        lastError = if (i == 1) "You're offline. Check your connection." else null, knownName = name,
    )

    @Test fun pendingQueue() = snap("scan_pending") {
        PendingQueueContent(listOf(pending(1, "Sam Lee"), pending(2, "Arjun Patel"), pending(3, null)), syncing = false, timezone = event.timezone, onSyncNow = {}, onDiscard = {})
    }

    @Test fun pendingEmpty() = snap("scan_pending_empty") {
        PendingQueueContent(emptyList(), syncing = false, timezone = event.timezone, onSyncNow = {}, onDiscard = {})
    }

    @Test fun recentScans() = snap("scan_recent") {
        val ctx = ScanContextRef("c1", "Check-in desk", checksIn = true)
        RecentScansContent(
            listOf(
                ScanLogEntry(ScanOutcome.Scanned("s1", ScanResult(outcome = "scanned", scanContext = ctx), maya), "Check-in desk", "2026-10-03T01:29:00Z"),
                ScanLogEntry(ScanOutcome.AlreadyScanned("s2", ScanResult(outcome = "already_scanned", scanContext = ctx), noah), "Check-in desk", "2026-10-03T01:27:00Z"),
                ScanLogEntry(ScanOutcome.Queued("s3", pending(3, "Arjun Patel"), null, "offline"), "Check-in desk", "2026-10-03T01:25:00Z"),
                ScanLogEntry(ScanOutcome.Failed("s4", "Not registered for this event", null, true), "Check-in desk", "2026-10-03T01:20:00Z"),
                ScanLogEntry(ScanOutcome.Rejected("s5", RejectReason.Withdrawn, SampleData.participants[10], offline = true), "Check-in desk", "2026-10-03T01:18:00Z"),
            ),
            event.timezone,
            onOpen = {},
        )
    }
}
