package au.ingo.betterattend.ui.scan

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.PendingScan
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.scan.FeedbackKind
import au.ingo.betterattend.scan.NfcStatus
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import java.time.Instant
import java.time.LocalDate

enum class ResultKind { Checking, Scanned, AlreadyScanned, SavedOffline, Rejected, Undone }

/** What the result card shows. One per scan attempt; the next scan replaces it. */
@Immutable
data class ScanCard(
    val key: String,
    val kind: ResultKind,
    val title: String,
    val message: String? = null,
    val participant: Participant? = null,
    val contextName: String? = null,
    /** Server scan context id, used for undo. */
    val contextId: String? = null,
    val retryable: Boolean = false,
    val canUndo: Boolean = false,
    val busy: Boolean = false,
    /** Same-code gate key, released when the card is dismissed. */
    val gateKey: String? = null,
    val input: ScanInput? = null,
)

enum class CameraAccess { Unknown, Granted, NeedsRequest, PermanentlyDenied }

/** Everything the (stateless) scanner UI needs. */
@Immutable
data class ScanUiState(
    val eventsLoading: Boolean = false,
    val event: Event? = null,
    /** null while loading with nothing cached. */
    val contexts: List<ScanContext>? = emptyList(),
    val contextsError: String? = null,
    val selectedContextId: String? = null,
    val card: ScanCard? = null,
    val pendingCount: Int = 0,
    val inFlight: Int = 0,
    val camera: CameraAccess = CameraAccess.Granted,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val nfc: NfcStatus = NfcStatus.Unavailable,
    val sounds: Boolean = true,
    val haptics: Boolean = true,
) {
    val selectedContext: ScanContext? get() = contexts?.firstOrNull { it.id == selectedContextId }
    val ready: Boolean get() = event != null && (contexts != null || contextsError != null)
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val results: List<Participant> = emptyList(),
    val remoteLoading: Boolean = false,
    val remoteError: String? = null,
    /** Set when the query is itself a scannable id/QR payload. */
    val directInput: ScanInput? = null,
    val canSearch: Boolean = true,
    val rosterEmpty: Boolean = false,
)

fun ResultKind.feedback(): FeedbackKind? = when (this) {
    ResultKind.Checking, ResultKind.Undone -> null
    ResultKind.Scanned -> FeedbackKind.Success
    ResultKind.AlreadyScanned -> FeedbackKind.Warning
    ResultKind.SavedOffline -> FeedbackKind.Info
    ResultKind.Rejected -> FeedbackKind.Reject
}

/** Colours for an outcome, all from the theme's semantic status palette. */
data class OutcomeColors(val strong: Color, val onStrong: Color, val container: Color, val onContainer: Color)

@Composable
fun ResultKind.colors(): OutcomeColors {
    val s = MaterialTheme.status
    return when (this) {
        ResultKind.Scanned -> OutcomeColors(s.success, s.onSuccess, s.successContainer, s.onSuccessContainer)
        ResultKind.AlreadyScanned -> OutcomeColors(s.warning, s.onWarning, s.warningContainer, s.onWarningContainer)
        ResultKind.Checking, ResultKind.SavedOffline -> OutcomeColors(s.info, s.onInfo, s.infoContainer, s.onInfoContainer)
        ResultKind.Rejected -> OutcomeColors(s.danger, s.onDanger, s.dangerContainer, s.onDangerContainer)
        ResultKind.Undone -> MaterialTheme.colorScheme.let { OutcomeColors(it.secondary, it.onSecondary, it.secondaryContainer, it.onSecondaryContainer) }
    }
}

fun ResultKind.icon(): ImageVector = when (this) {
    ResultKind.Checking -> Icons.Outlined.History
    ResultKind.Scanned -> Icons.Outlined.CheckCircle
    ResultKind.AlreadyScanned -> Icons.Outlined.History
    ResultKind.SavedOffline -> Icons.Outlined.CloudQueue
    ResultKind.Rejected -> Icons.Outlined.ErrorOutline
    ResultKind.Undone -> Icons.AutoMirrored.Outlined.Undo
}

fun ScanContext.icon(): ImageVector = when {
    isTravelPickup || isAirport -> Icons.Outlined.FlightLand
    checksIn -> Icons.AutoMirrored.Outlined.Login
    else -> Icons.Outlined.Place
}

/** "Now" if the window contains [now]; otherwise a compact time window like "Sat 12:00 – 1:30 PM". */
fun ScanContext.windowLabel(tz: String?, now: Instant = Instant.now()): String? {
    val s = Time.parse(startsAt) ?: return null
    val e = Time.parse(endsAt)
    val zone = Time.zone(tz)
    val startDay = s.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val day = if (startDay == today) "" else Time.day(startsAt, tz)?.substringBefore(' ')?.plus(" ").orEmpty()
    val start = Time.time(startsAt, tz)
    val end = e?.let { Time.time(endsAt, tz) }
    return day + if (end != null) "$start – $end" else "from $start"
}

fun ScanContext.isLive(now: Instant = Instant.now()): Boolean {
    val s = Time.parse(startsAt) ?: return false
    val e = Time.parse(endsAt) ?: return false
    return !now.isBefore(s) && !now.isAfter(e)
}

/** Maps a repository outcome to the card we show. */
fun ScanOutcome.toCard(key: String, context: ScanContext?, tz: String?, gateKey: String?, input: ScanInput?): ScanCard {
    val base = ScanCard(key = key, kind = ResultKind.Checking, title = "", participant = participant, contextName = context?.name,
        contextId = context?.id, gateKey = gateKey, input = input)
    return when (this) {
        is ScanOutcome.Scanned -> {
            val ctx = result.scanContext
            base.copy(
                kind = ResultKind.Scanned,
                title = "Scanned",
                message = if (ctx?.checksIn ?: context?.checksIn == true) "Checked in" else null,
                contextName = ctx?.name ?: context?.name,
                contextId = ctx?.id ?: context?.id,
                canUndo = (participant?.participantEventId ?: result.scan?.participantEventId) != null && (ctx?.id ?: context?.id) != null && !result.deduplicated,
                participant = participant,
            )
        }
        is ScanOutcome.AlreadyScanned -> base.copy(
            kind = ResultKind.AlreadyScanned,
            title = "Already scanned",
            message = result.firstScannedAt?.let { first -> "First scanned at ${firstScanLabel(first, tz)}" },
            contextName = result.scanContext?.name ?: context?.name,
            contextId = result.scanContext?.id ?: context?.id,
        )
        is ScanOutcome.Queued -> base.copy(
            kind = ResultKind.SavedOffline,
            title = "Saved offline",
            message = "Will sync automatically when you're back online",
        )
        is ScanOutcome.Failed -> base.copy(
            kind = ResultKind.Rejected,
            title = if (notFound) "Not registered" else "Couldn't scan",
            message = if (notFound) "No registration for this event matches that code." else message,
            retryable = !notFound,
        )
    }
}

/** "9:41 AM" today, else "Fri 9:41 AM". */
fun firstScanLabel(iso: String, tz: String?): String {
    val zone = Time.zone(tz)
    val t = Time.parse(iso)?.atZone(zone) ?: return iso
    val time = Time.time(iso, tz).orEmpty()
    return if (t.toLocalDate() == LocalDate.now(zone)) time else "${Time.day(iso, tz)?.substringBefore(' ')} $time"
}

fun PendingScan.displayName(): String = knownName ?: input.badgeToken?.let { "NFC badge" } ?: "Unknown attendee"
