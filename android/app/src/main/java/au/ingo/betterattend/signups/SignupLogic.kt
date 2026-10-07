package au.ingo.betterattend.signups

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.Roster

/** Who signed up between two syncs of a roster, and how a notification words it. */
object SignupLogic {
    /**
     * People who count as signed up in [new] but didn't in [old]: registrations that weren't on the
     * roster before, or that were only invited by staff and have now started registering. Staff
     * invites, withdrawals and rejections aren't signups.
     */
    fun newSignups(old: Roster, new: Roster): List<Participant> =
        new.participants.filter { p -> p.isSignedUp && old.byEventId[p.participantEventId]?.isSignedUp != true }

    private val Participant.isSignedUp: Boolean get() = isActive && status != "invited"

    /** "New signup for Campfire" / "3 new signups for Campfire" */
    fun title(count: Int, eventName: String?): String {
        val what = if (count == 1) "New signup" else "$count new signups"
        return if (eventName.isNullOrBlank()) what else "$what for $eventName"
    }

    /** "Mia Chen, Ollie Smith and 2 others" */
    fun summary(people: List<Participant>, shown: Int = 3): String {
        val names = people.map { it.name }
        return when {
            names.size <= 1 -> names.firstOrNull().orEmpty()
            names.size <= shown -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            else -> {
                val rest = names.size - shown
                names.take(shown).joinToString(", ") + " and $rest other${if (rest == 1) "" else "s"}"
            }
        }
    }
}
