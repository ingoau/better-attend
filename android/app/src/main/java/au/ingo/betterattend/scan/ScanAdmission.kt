package au.ingo.betterattend.scan

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.PendingScan
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.util.Time
import java.time.Duration
import java.time.Instant

/** Why a scan isn't letting someone in. Shared by the server verdict and the offline pre-check. */
enum class RejectReason(
    /** Card title, e.g. "Withdrawn". */
    val title: String,
    /** Lower-case reason for banners and logs: "Mia Chen: consent not signed". */
    val short: String,
) {
    Withdrawn("Withdrawn", "registration withdrawn"),
    RegistrationRejected("Registration rejected", "registration rejected"),
    ConsentMissing("Consent not signed", "consent not signed"),
    WrongEvent("Wrong event", "registered for another event"),
    AlreadyCheckedIn("Already scanned", "already checked in"),
    NotRegistered("Not registered", "not registered for this event"),
}

/** The result of checking a scan against what's cached on this device. */
sealed interface Precheck {
    /** Nothing in the cache says no. [participant] is the roster match, if any. */
    data class Pass(val participant: Participant?) : Precheck

    /** [detail] is the other event's name ([RejectReason.WrongEvent]) or the first scan time ([RejectReason.AlreadyCheckedIn]). */
    data class Block(val reason: RejectReason, val participant: Participant?, val detail: String? = null) : Precheck
}

/**
 * Who may be admitted, decided from a participant record. Attend records a scan for anyone on the
 * event (withdrawn people included), so the app applies these rules itself: to the fresh record the
 * server returns with each scan (online) and to the cached roster (offline pre-check).
 */
object ScanAdmission {
    /** Past this, the offline warning about the roster's age gets louder. */
    val STALE_ROSTER: Duration = Duration.ofHours(1)

    /**
     * What stops [p] being admitted, or null. A missing waiver only blocks at checkpoints that check
     * people in: someone already inside still gets lunch.
     */
    fun problem(p: Participant, checksIn: Boolean): RejectReason? = when {
        p.status == "withdrawn" -> RejectReason.Withdrawn
        p.status == "rejected" -> RejectReason.RegistrationRejected
        checksIn && !p.waiverSigned -> RejectReason.ConsentMissing
        else -> null
    }

    /**
     * Checks an offline scan against the cached roster before it's queued.
     *
     * @param roster this event's cached roster (null if never synced, e.g. no access to the participant list).
     * @param contextId the selected checkpoint; null skips the "already checked in" check.
     * @param pending scans already queued, so the same ticket scanned twice offline isn't queued twice.
     * @param otherRosters cached rosters of the user's other events, by event name, for "wrong event".
     * @param enforceAdmission false when staff have deliberately admitted the person (check-in from their
     *   page, a roll call tick): the server would accept the scan, so only checks it would fail on (not
     *   registered, wrong event) and the duplicate check remain, exactly as online.
     */
    fun precheck(
        input: ScanInput,
        roster: Roster?,
        contextId: String?,
        checksIn: Boolean,
        pending: List<PendingScan> = emptyList(),
        otherRosters: Map<String, Roster> = emptyMap(),
        enforceAdmission: Boolean = true,
    ): Precheck {
        val code = input.badgeToken ?: input.participantId ?: return Precheck.Pass(null)
        // A roster that's never had a full sync is partial: it can't prove someone isn't registered.
        if (roster == null || roster.syncedAt == null) return Precheck.Pass(roster?.find(code))
        val p = roster.find(code)
        if (p == null) {
            // Badges can be written after the last sync, so an unknown badge token isn't proof.
            if (input.badgeToken != null) return Precheck.Pass(null)
            otherRosters.entries.firstNotNullOfOrNull { (name, r) -> r.find(code)?.let { name to it } }?.let { (name, other) ->
                return Precheck.Block(RejectReason.WrongEvent, other, name)
            }
            return Precheck.Block(RejectReason.NotRegistered, null)
        }
        if (enforceAdmission) problem(p, checksIn)?.let { return Precheck.Block(it, p) }
        if (contextId != null) {
            p.scansByContext.firstOrNull { it.scanContextId == contextId }?.let {
                return Precheck.Block(RejectReason.AlreadyCheckedIn, p, it.firstScannedAt)
            }
            pending.firstOrNull { q -> q.eventId == roster.eventId && q.scanContextId == contextId && q.resolvesTo(roster, p) }?.let {
                return Precheck.Block(RejectReason.AlreadyCheckedIn, p, it.scannedAt)
            }
        }
        return Precheck.Pass(p)
    }

    private fun PendingScan.resolvesTo(roster: Roster, p: Participant): Boolean =
        (input.badgeToken ?: input.participantId)?.let { roster.find(it)?.participantEventId == p.participantEventId } == true

    /** When [roster] was last synced (ISO), or null if there's no complete roster on this device. */
    fun rosterTime(roster: Roster?): String? = roster?.takeIf { it.syncedAt != null }?.let { it.lastSyncAt ?: it.syncedAt }

    /** "Roster from 14 min ago" for a roster synced at [rosterAt]; null when there's no roster. */
    fun rosterAgeLabel(rosterAt: String?, now: Instant = Instant.now()): String? {
        val ago = Time.ago(rosterAt, now) ?: return null
        return if (ago == "just now") "Roster synced just now" else "Roster from $ago"
    }

    /** True when there's no roster or it's older than [STALE_ROSTER]. */
    fun isRosterStale(rosterAt: String?, now: Instant = Instant.now()): Boolean =
        Time.parse(rosterAt)?.let { Duration.between(it, now) > STALE_ROSTER } ?: true
}
