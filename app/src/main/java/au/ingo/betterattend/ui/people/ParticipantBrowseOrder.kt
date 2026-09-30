package au.ingo.betterattend.ui.people

/**
 * The order of the People list the user just tapped into, so Participant detail can swipe to the
 * previous / next person in that same (filtered, sorted) order.
 *
 * It's a one-shot hand-off: People calls [set] right before navigating, and the detail screen
 * [take]s it once when it opens. A detail screen opened from anywhere else (scanner, dashboard,
 * travel, a widget) finds nothing waiting for it, or a hand-off meant for someone else, and shows
 * just the one person.
 */
object ParticipantBrowseOrder {
    private data class Pending(val eventId: String, val anchor: String, val ids: List<String>)

    private var pending: Pending? = null

    /** Called by the People list as a row is opened. [ids] is the list as displayed, containing [anchor]. */
    @Synchronized
    fun set(eventId: String, anchor: String, ids: List<String>) {
        pending = Pending(eventId, anchor, ids.distinct())
    }

    /**
     * Consumes the hand-off for this detail screen. Returns the ids to page through (always
     * containing [participantEventId]); just that id when the screen wasn't opened from the list.
     */
    @Synchronized
    fun take(eventId: String, participantEventId: String): List<String> {
        val p = pending
        pending = null
        return if (p != null && p.eventId == eventId && p.anchor == participantEventId && participantEventId in p.ids) p.ids
        else listOf(participantEventId)
    }
}
