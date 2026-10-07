package au.ingo.betterattend.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.util.Time
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** Pickup reminders from the travel calendar: which arrivals, when, time changes, and the alarms behind them. */
@RunWith(RobolectricTestRunner::class)
class ArrivalAlertsTest {
    private val now: Instant = Instant.parse("2026-10-03T00:00:00Z")
    private fun at(minutes: Long) = now.plusSeconds(minutes * 60).toString()

    private fun arrival(id: String, name: String, minutes: Long?, pickup: String? = "awaiting_pickup", direction: String = "inbound") =
        TravelEntry(
            id = id, participantName = name, direction = direction, primaryTimeAt = minutes?.let(::at), pickupState = pickup,
            reference = "QF14$id", route = "SYD → CBR",
        )

    private val mia = arrival("1", "Mia Chen", 120)
    private val ollie = arrival("2", "Ollie Smith", 120)
    private val ava = arrival("3", "Ava Jones", 20)
    private val calendar = TravelCalendar(
        eventTimezone = "Australia/Sydney",
        entries = listOf(
            mia, ollie, ava,
            arrival("4", "Collected", 60, pickup = "collected"),
            arrival("5", "No pickup", 60, pickup = "pickup_not_needed"),
            arrival("6", "Leaving", 60, direction = "outbound"),
            arrival("7", "Landed", -5),
            arrival("8", "Unscheduled", null),
        ),
    )

    // ---- ArrivalAlerts ----

    @Test fun onlyUpcomingArrivalsAwaitingPickupGetAReminder() {
        val slots = ArrivalAlerts.slots(calendar, now)
        assertEquals(listOf(listOf("3"), listOf("1", "2")), slots.map { s -> s.entries.map { it.id } })
        // 30 minutes ahead, or straight away when that's already passed.
        assertEquals(now, slots[0].remindAt)
        assertEquals(Instant.parse(at(90)), slots[1].remindAt)
    }

    @Test fun peopleAlreadyRemindedAtThatTimeAreLeftOut() {
        val reminded = setOf(ArrivalAlerts.remindedKey("3", Instant.parse(at(20))), ArrivalAlerts.remindedKey("1", Instant.parse(at(120))))
        assertEquals(listOf(listOf("2")), ArrivalAlerts.slots(calendar, now, reminded).map { s -> s.entries.map { it.id } })
        // A new time is a new reminder.
        val moved = calendar.copy(entries = listOf(ava.copy(primaryTimeAt = at(50))))
        assertEquals(1, ArrivalAlerts.slots(moved, now, reminded).size)
    }

    @Test fun timeChangesOfAtLeast15Minutes() {
        val previous = mapOf("1" to at(120), "2" to at(120), "3" to at(20))
        val updated = calendar.copy(entries = listOf(mia.copy(primaryTimeAt = at(165)), ollie.copy(primaryTimeAt = at(130)), ava))
        val changes = ArrivalAlerts.changes(previous, updated, now)
        assertEquals(listOf("1"), changes.map { it.entry.id })
        val tz = "Australia/Sydney"
        assertEquals("Mia Chen now arrives at ${Time.time(at(165), tz)} (45 min later)", ArrivalAlerts.changeLine(changes.single(), tz))
        // Someone never scheduled before isn't a change.
        assertTrue(ArrivalAlerts.changes(emptyMap(), updated, now).isEmpty())
    }

    @Test fun wording() {
        val tz = "Australia/Sydney"
        val (single, pair) = ArrivalAlerts.slots(calendar, now)
        assertEquals("Pickup at ${Time.time(at(20), tz)}: Ava Jones", ArrivalAlerts.reminderTitle(single, tz))
        assertEquals("QF143 · SYD → CBR", ArrivalAlerts.reminderText(single))
        assertEquals("2 arrivals to collect at ${Time.time(at(120), tz)}", ArrivalAlerts.reminderTitle(pair, tz))
        assertEquals("Mia Chen and Ollie Smith", ArrivalAlerts.reminderText(pair))
        assertEquals("Arrival time changed", ArrivalAlerts.changeTitle(1))
        assertEquals("2 arrival times changed", ArrivalAlerts.changeTitle(2))
    }

    // ---- ArrivalReminders ----

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private val posted get() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ArrivalReminders.cancelAll(app)
    }

    @After fun tearDown() = ArrivalReminders.cancelAll(app)

    @Test fun schedulesOneAlarmPerArrivalTimeAndPostsWhenItFires() {
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        assertEquals(2, alarms.size)
        assertEquals("e1", ArrivalReminders.scheduledEvent(app))

        val intent = alarms.minByOrNull { it.triggerAtTime }!!.operation.let { shadowOf(it).savedIntent }
        ArrivalReminders.fire(app, intent, now)
        assertEquals(1, posted.size)
        // Delivered twice (a retried broadcast): still one notification.
        ArrivalReminders.fire(app, intent, now)
        assertEquals(1, posted.size)
        // Rescheduling after it fired doesn't remind about the same people at the same time again.
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        assertEquals(1, alarms.size)
    }

    @Test fun aReplacedAlarmOrOneThatArrivesTooLateStaysQuiet() {
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        val first = alarms.map { shadowOf(it.operation).savedIntent }
        // Rescheduled (say Ollie was collected) while the old alarms were already on their way.
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar.copy(entries = listOf(mia, ava)), now)
        first.forEach { ArrivalReminders.fire(app, it, now) }
        assertTrue(posted.isEmpty())
        // Delivered after the person has arrived: no use any more.
        val late = shadowOf(alarms.minByOrNull { it.triggerAtTime }!!.operation).savedIntent
        ArrivalReminders.fire(app, late, now.plusSeconds(25 * 60))
        assertTrue(posted.isEmpty())
    }

    @Test fun aMovedArrivalIsAnnouncedAndRescheduled() {
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        val updated = calendar.copy(entries = listOf(mia.copy(primaryTimeAt = at(180)), ollie, ava))
        ArrivalReminders.reschedule(app, "e1", "Campfire", updated, now)
        assertEquals(1, posted.size)
        assertEquals("Arrival time changed", shadowOf(posted.single()).contentTitle)
        assertEquals(3, alarms.size) // Ava, Ollie, and Mia at her new time
    }

    @Test fun collectedPeopleDropOutAndCancellingClearsEverything() {
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        val collected = calendar.copy(entries = listOf(mia, ollie, ava.copy(pickupState = "collected")))
        ArrivalReminders.reschedule(app, "e1", "Campfire", collected, now)
        assertEquals(1, alarms.size)
        ArrivalReminders.cancelAll(app)
        assertTrue(alarms.isEmpty())
        assertEquals(null, ArrivalReminders.scheduledEvent(app))
    }

    @Test fun anAlarmForAnotherEventStaysQuiet() {
        ArrivalReminders.reschedule(app, "e1", "Campfire", calendar, now)
        val intent = shadowOf(alarms.first().operation).savedIntent
        ArrivalReminders.reschedule(app, "e2", "Other", TravelCalendar(), now)
        ArrivalReminders.fire(app, Intent(intent), now)
        assertTrue(posted.isEmpty())
    }
}
