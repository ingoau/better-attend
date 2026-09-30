package au.ingo.betterattend.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import java.time.Instant

/** Illustrative data for widget-picker previews and tests (no real people). */
object WidgetSamples {
    val now: Instant = Instant.parse("2026-10-03T01:30:00Z")

    val organizer = OrganizerWidgetData(
        eventId = "sample", eventName = "Campfire Sydney", timezone = "Australia/Sydney",
        hasCounts = true, checkedIn = 84, expected = 120, notArrived = 36, lastHour = 12,
        contexts = listOf(
            ContextCount("c1", "Check-in desk", 84, checksIn = true),
            ContextCount("c2", "Airport pickup", 23, isTravel = true),
            ContextCount("c3", "Saturday lunch", 71),
            ContextCount("c4", "Swag table", 40),
        ),
        updatedAt = "2026-10-03T01:26:00Z",
        travelEnabled = true,
        travel = TravelWidgetData(
            awaitingPickup = 7, collected = 12, checkedIn = 18, total = 41,
            nextArrivalName = "Sam", nextArrivalAt = "2026-10-03T11:40:00+10:00", nextArrivalRoute = "MEL → SYD", nextArrivalReference = "QF401",
        ),
    )

    val ticket = TicketWidgetData(
        id = "sample", eventName = "Campfire Sydney", startsAt = "2026-10-05T22:00:00Z", endsAt = "2026-10-07T06:00:00Z",
        timezone = "Australia/Sydney", city = "Sydney", confirmed = true, shortCode = "A1B2C3D4", statusLabel = "Ready",
    )

    val snapshot = WidgetSnapshot(signedIn = true, organizer = organizer, ticket = ticket, isParticipant = true, builtAt = now.toString())
}

/** Standard responsive breakpoints (≈ 2x2, 4x2 and 4x3 cells on a phone). */
object WidgetSizes {
    val Tiny = DpSize(57.dp, 57.dp)
    val QuickWide = DpSize(130.dp, 57.dp)
    val Small = DpSize(120.dp, 110.dp)
    val Wide = DpSize(250.dp, 110.dp)
    val Large = DpSize(250.dp, 230.dp)
}

/** Base receiver: kick a sync when the first widget of a kind is placed; stop syncing when none remain. */
abstract class AttendWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSync.onWidgetAdded(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSync.ensureScheduled(context)
    }
}
