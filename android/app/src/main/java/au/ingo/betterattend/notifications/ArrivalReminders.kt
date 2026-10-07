package au.ingo.betterattend.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.ingo.betterattend.container
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.TravelCalendar
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID

/**
 * Pickup reminders, scheduled on the phone from the cached travel calendar so they fire on time even
 * offline. Rescheduled whenever the calendar refreshes: collected people drop out, new times move the
 * reminder, and an upcoming arrival seen at the last reschedule that moves by 15+ minutes gets a
 * "time changed" notice.
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
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ARRIVES = "arrives"
    /** Request codes are this plus the slot's position: at most [ArrivalAlerts.MAX_SLOTS], so they never collide. */
    private const val REQUEST_BASE = 42_100

    /** What's scheduled. Ids and times only: names live in the alarms, never on disk. */
    @Serializable
    private data class State(
        val eventId: String? = null,
        /** Entry id → arrival time it was last scheduled for. */
        val scheduled: Map<String, String> = emptyMap(),
        /** Request codes of the alarms set. */
        val alarms: List<Int> = emptyList(),
        /** One per alarm set by the last reschedule: an alarm already on its way when it was replaced stays quiet. */
        val tokens: Set<String> = emptySet(),
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
        val tokens = HashSet<String>()
        val alarms = ArrivalAlerts.slots(calendar, now, reminded).mapIndexed { i, slot ->
            val token = UUID.randomUUID().toString().also(tokens::add)
            val intent = Intent(app, ArrivalReminderReceiver::class.java)
                .putExtra(EXTRA_TITLE, ArrivalAlerts.reminderTitle(slot, tz))
                .putExtra(EXTRA_TEXT, ArrivalAlerts.reminderText(slot))
                .putExtra(EXTRA_EVENT, eventId)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_ARRIVES, slot.arrivesAt.toEpochMilli())
                .putExtra(EXTRA_REMINDED, slot.entries.map { ArrivalAlerts.remindedKey(it.id, slot.arrivesAt) }.toTypedArray())
            val code = REQUEST_BASE + i
            val pending = PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setAlarm(app, slot.remindAt, pending)
            code
        }
        write(app, State(eventId, upcoming.map { (e, at) -> e.id to at.toString() }.toMap(), alarms, tokens, reminded))
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
    internal fun fire(context: Context, intent: Intent, now: Instant = Instant.now()) {
        val app = context.applicationContext
        val state = read(app)
        val eventId = intent.getStringExtra(EXTRA_EVENT)
        // Replaced or cancelled since (rescheduled, turned off, signed out, another event): an alarm that
        // was already on its way stays quiet.
        if (eventId == null || state.eventId != eventId || intent.getStringExtra(EXTRA_TOKEN) !in state.tokens) return
        val keys = intent.getStringArrayExtra(EXTRA_REMINDED).orEmpty()
        if (keys.isNotEmpty() && keys.all { it in state.reminded }) return
        write(app, state.copy(reminded = state.reminded + keys))
        // Without exact alarms, a deep-sleeping phone can deliver this late: past the arrival it's no use.
        if (intent.getLongExtra(EXTRA_ARRIVES, Long.MAX_VALUE) < now.toEpochMilli()) return
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

    /**
     * On time when the phone allows exact alarms (always before Android 12; on 13+ only if the user allows
     * "Alarms & reminders"). Otherwise inexact: a sleeping phone may deliver it a little late, and
     * every travel refresh sets it again closer to the time.
     */
    private fun setAlarm(context: Context, at: Instant, pending: PendingIntent) {
        val am = alarmManager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            }
        }
    }

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

/** Alarms don't survive a reboot or an app update: set them again from the cached travel calendar. */
class ArrivalRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val done = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                withTimeoutOrNull(8_000) { NotificationWatch.restoreReminders(context, c) }
            } finally {
                done.finish()
            }
        }
    }
}
