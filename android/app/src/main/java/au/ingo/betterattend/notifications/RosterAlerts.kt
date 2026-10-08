package au.ingo.betterattend.notifications

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.util.Time

/** What changed between two syncs of a roster that organizers can be notified about. */
data class RosterChanges(
    val signups: List<Participant> = emptyList(),
    val withdrawals: List<Participant> = emptyList(),
) {
    fun isEmpty() = signups.isEmpty() && withdrawals.isEmpty()
}

/** Who signed up or withdrew between two syncs of a roster, and how a notification words it. */
object RosterAlerts {
    fun changes(old: Roster, new: Roster) = RosterChanges(newSignups(old, new), newWithdrawals(old, new))

    /**
     * People who count as signed up in [new] but weren't on [old] at all, or were only invited by staff
     * there and have now started registering. Staff invites, withdrawals, rejections and reinstatements
     * aren't signups.
     */
    fun newSignups(old: Roster, new: Roster): List<Participant> =
        new.participants.filter { p ->
            val before = old.byEventId[p.participantEventId]
            p.isSignedUp && (before == null || before.status == "invited")
        }

    /**
     * People who were signed up in [old] and have withdrawn in [new]. Rejections are staff decisions,
     * and invitees who never registered weren't coming, so neither counts.
     */
    fun newWithdrawals(old: Roster, new: Roster): List<Participant> =
        new.participants.filter { p ->
            p.status == "withdrawn" && old.byEventId[p.participantEventId]?.isSignedUp == true
        }

    private val Participant.isSignedUp: Boolean get() = isActive && status != "invited"

    /**
     * Only report changes measured against a roster synced after the alert was turned on ([since]): one
     * cached from days earlier would announce everything that happened while it was off.
     */
    fun isBaseline(since: String?, previousSyncAt: String?): Boolean {
        val on = Time.parse(since) ?: return false
        val previous = Time.parse(previousSyncAt) ?: return false
        return !previous.isBefore(on)
    }

    /** "New signup for Campfire" / "3 new signups for Campfire" */
    fun signupTitle(count: Int, eventName: String?): String =
        forEvent(if (count == 1) "New signup" else "$count new signups", eventName)

    /** "Withdrawal from Campfire" / "3 withdrawals from Campfire" */
    fun withdrawalTitle(count: Int, eventName: String?): String {
        val what = if (count == 1) "Withdrawal" else "$count withdrawals"
        return if (eventName.isNullOrBlank()) what else "$what from $eventName"
    }

    /** "New signups for Campfire": the bundle of an event's signup notifications. */
    fun signupGroupTitle(eventName: String?): String = forEvent("New signups", eventName)

    /** "Withdrawals from Campfire": the bundle of an event's withdrawal notifications. */
    fun withdrawalGroupTitle(eventName: String?): String =
        if (eventName.isNullOrBlank()) "Withdrawals" else "Withdrawals from $eventName"

    private fun forEvent(what: String, eventName: String?) = if (eventName.isNullOrBlank()) what else "$what for $eventName"

    /** "Mia Chen, Ollie Smith and 2 others" */
    fun summary(names: List<String>, shown: Int = 3): String = when {
        names.size <= 1 -> names.firstOrNull().orEmpty()
        names.size <= shown -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        else -> {
            val rest = names.size - shown
            names.take(shown).joinToString(", ") + " and $rest other${if (rest == 1) "" else "s"}"
        }
    }
}
