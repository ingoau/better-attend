package au.ingo.betterattend.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.TravelCalendar
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Pickup reminders, scheduled on the phone from the cached travel calendar so they fire on time even
 * offline. Rescheduled whenever the calendar refreshes: collected people drop out, new times move the
 * reminder, and a reminder already scheduled that moves by 15+ minutes gets a "time changed" notice.
 * Only the selected event's arrivals are scheduled.
 */
object ArrivalReminders {
    private const val CHANNEL = "arrivals"
    private const val OPEN_REQUEST = 4204
    private const val PREFS = "arrival_reminders"
    private const val STATE = "state"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_EVENT = "event"
    const val EXTRA_REMINDED = "reminded"

    /** What's scheduled. Ids and times only: names live in the alarms, never on disk. */
    @Serializable
    private data class State(
        val eventId: String? = null,
        /** Entry id → arrival time it was last scheduled for. */
        val scheduled: Map<String, String> = emptyMap(),
        /** Request codes of the alarms set. */
        val alarms: List<Int> = emptyList(),
        /** [ArrivalAlerts.remindedKey]s already notified. */
        val reminded: Set<String> = emptySet(),
    )

    @Synchronized
    fun reschedule(context: Context, eventId: String, eventName: String?, calendar: TravelCalendar, now: Instant = Instant.now()) {
        val app = context.applicationContext
        var state = read(app)
        if (state.eventId != eventId) {
            cancelAlarms(app, state)
            state = State(eventId)
        }
        val tz = calendar.eventTimezone

        val changes = ArrivalAlerts.changes(state.scheduled, calendar, now)
        if (changes.isNotEmpty()) notifyChanges(app, eventId, eventName, changes.map { ArrivalAlerts.changeLine(it, tz) })

        cancelAlarms(app, state)
        val upcoming = ArrivalAlerts.awaitingPickup(calendar, now)
        val reminded = state.reminded.filterTo(HashSet()) { key -> upcoming.any { (e, at) -> ArrivalAlerts.remindedKey(e.id, at) == key } }
        val alarms = ArrivalAlerts.slots(calendar, now, reminded).map { slot ->
            val intent = Intent(app, ArrivalReminderReceiver::class.java)
                .putExtra(EXTRA_TITLE, ArrivalAlerts.reminderTitle(slot, tz))
                .putExtra(EXTRA_TEXT, ArrivalAlerts.reminderText(slot))
                .putExtra(EXTRA_EVENT, eventId)
                .putExtra(EXTRA_REMINDED, slot.entries.map { ArrivalAlerts.remindedKey(it.id, slot.arrivesAt) }.toTypedArray())
            val code = slot.key.hashCode()
            val pending = PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Inexact while idle (no exact-alarm permission needed); a few minutes' drift is fine for a 30-minute heads-up.
            runCatching { alarmManager(app)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.remindAt.toEpochMilli(), pending) }
            code
        }
        write(app, State(eventId, upcoming.map { (e, at) -> e.id to at.toString() }.toMap(), alarms, reminded))
    }

    /** Turned off, signed out, or switched to an event without travel. */
    @Synchronized
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app, read(app))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The event reminders are scheduled for, if any. */
    fun scheduledEvent(context: Context): String? = read(context.applicationContext).eventId

    @Synchronized
    internal fun fire(context: Context, intent: Intent) {
        val app = context.applicationContext
        val state = read(app)
        val eventId = intent.getStringExtra(EXTRA_EVENT)
        // Cancelled since (turned off, signed out, another event): an alarm that slipped through stays quiet.
        if (eventId == null || state.eventId != eventId) return
        val keys = intent.getStringArrayExtra(EXTRA_REMINDED).orEmpty()
        write(app, state.copy(reminded = state.reminded + keys))
        if (!AlertNotifications.canPost(app)) return
        channel(app)
        val tag = "${AlertNotifications.TAG_PREFIX}$CHANNEL:$eventId"
        val notification = AlertNotifications.builder(app, CHANNEL, intent.getStringExtra(EXTRA_TITLE).orEmpty(), open(app), tag)
            .setContentText(intent.getStringExtra(EXTRA_TEXT))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(tag, AlertNotifications.nextId(), notification) }
    }

    private fun notifyChanges(context: Context, eventId: String, eventName: String?, lines: List<String>) {
        if (!AlertNotifications.canPost(context)) return
        channel(context)
        val tag = "${AlertNotifications.TAG_PREFIX}$CHANNEL:$eventId"
        val notification = AlertNotifications.builder(context, CHANNEL, ArrivalAlerts.changeTitle(lines.size), open(context), tag)
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach(style::addLine) }.setSummaryText(eventName))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag, AlertNotifications.nextId(), notification) }
    }

    private fun channel(context: Context) = AlertNotifications.channel(
        context, CHANNEL, "Arrivals to pick up", "Reminders before people awaiting pickup arrive, and when their arrival time changes",
        android.app.NotificationManager.IMPORTANCE_HIGH,
    )

    private fun open(context: Context) = AlertNotifications.openTab(context, "travel", OPEN_REQUEST)

    private fun cancelAlarms(context: Context, state: State) {
        val am = alarmManager(context) ?: return
        state.alarms.forEach { code ->
            val pending = PendingIntent.getBroadcast(
                context, code, Intent(context, ArrivalReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return@forEach
            am.cancel(pending)
            pending.cancel()
        }
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun read(context: Context): State =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATE, null)
            ?.let { runCatching { AttendJson.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()

    private fun write(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE, AttendJson.encodeToString(State.serializer(), state)).apply()
    }
}

/** Posts a pickup reminder when its alarm goes off. */
class ArrivalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ArrivalReminders.fire(context, intent)
}
