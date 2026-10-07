package au.ingo.betterattend.signups

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.util.Time

/** Who signed up between two syncs of a roster, and how a notification words it. */
object SignupLogic {
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

    private val Participant.isSignedUp: Boolean get() = isActive && status != "invited"

    /**
     * Only report signups measured against a roster synced after the setting was turned on: one cached
     * from days earlier would announce everyone who registered while it was off.
     */
    fun shouldNotify(settings: AppSettings, previousSyncAt: String?): Boolean {
        if (!settings.signupNotifications) return false
        val since = Time.parse(settings.signupNotificationsSince) ?: return false
        val previous = Time.parse(previousSyncAt) ?: return false
        return !previous.isBefore(since)
    }

    /** "New signup for Campfire" / "3 new signups for Campfire" */
    fun title(count: Int, eventName: String?): String {
        val what = if (count == 1) "New signup" else "$count new signups"
        return if (eventName.isNullOrBlank()) what else "$what for $eventName"
    }

    /** "New signups for Campfire": the bundle of an event's notifications. */
    fun groupTitle(eventName: String?): String = if (eventName.isNullOrBlank()) "New signups" else "New signups for $eventName"

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
