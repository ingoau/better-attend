package au.ingo.betterattend.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.container
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.repo.EventRepository
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The organizer alerts (Settings → Notifications): signups and withdrawals spotted as the roster
 * syncs, pickup reminders scheduled as travel refreshes. While any is on, a 15-minute background
 * check keeps the selected event's roster and travel fresh.
 */
object NotificationWatch {
    private const val PERIODIC = "event_alerts_periodic"

    fun start(context: Context, c: AppContainer) {
        val app = context.applicationContext
        c.participants.onRosterChanges = { eventId, changes, previousSyncAt -> onRosterChanges(app, c, eventId, changes, previousSyncAt) }
        c.travel.onRefreshed = { eventId, calendar -> onTravel(app, c, eventId, calendar) }

        // Background checks run exactly while an alert is on and someone is signed in.
        c.scope.launch {
            combine(c.settings.settings, c.auth.state) { s, auth -> s.anyEventAlerts && auth is AuthState.SignedIn }
                .distinctUntilChanged()
                .collect { on -> schedule(app, on) }
        }
        // Pickup reminders follow the setting and the selected event.
        c.scope.launch {
            combine(c.settings.settings, c.auth.state, c.events.events) { s, auth, events -> reminders(s, auth, events) }
                .distinctUntilChanged { a, b -> a == b || (a is Reminders.For && b is Reminders.For && a.event.id == b.event.id) }
                .collect { r -> apply(app, c, r) }
        }
    }

    /**
     * Where pickup reminders should be, worked out from the events list and the saved choice directly:
     * [EventRepository.selectedEvent] is its own flow and can still read null just after the events
     * load, which would wipe what's scheduled (and what's already been reminded) on every cold start.
     */
    private fun reminders(s: AppSettings, auth: AuthState, events: List<Event>?): Reminders {
        if (!s.arrivalNotifications || auth !is AuthState.SignedIn) return Reminders.Off
        // A fresh process hasn't read the events cache yet: keep what's scheduled until it has.
        if (events == null) return Reminders.Unknown
        val event = events.firstOrNull { it.id == s.selectedEventId } ?: EventRepository.suggestEvent(events)
        return if (event == null || !event.travelEnabled) Reminders.Off else Reminders.For(event)
    }

    private suspend fun apply(context: Context, c: AppContainer, r: Reminders) {
        when (r) {
            Reminders.Unknown -> Unit
            Reminders.Off -> ArrivalReminders.cancelAll(context)
            is Reminders.For -> {
                if (ArrivalReminders.scheduledEvent(context) != r.event.id) ArrivalReminders.cancelAll(context)
                c.travel.load(r.event.id)?.let { ArrivalReminders.reschedule(context, r.event.id, r.event.name, it) }
            }
        }
    }

    /** After a reboot or an app update, when every alarm is gone: reschedule from the cached calendar. */
    suspend fun restoreReminders(context: Context, c: AppContainer) {
        val events = withTimeoutOrNull(3_000) { c.events.events.filterNotNull().first() }
        apply(context.applicationContext, c, reminders(c.settings.current(), c.auth.state.value, events))
    }

    private sealed interface Reminders {
        data object Unknown : Reminders
        data object Off : Reminders
        data class For(val event: Event) : Reminders
    }

    private suspend fun onRosterChanges(context: Context, c: AppContainer, eventId: String, changes: RosterChanges, previousSyncAt: String?) {
        val s = c.settings.current()
        val name = eventName(c, eventId)
        fun post(kind: RosterNotifier.Kind, on: Boolean, since: String?, people: List<Participant>) {
            if (on && people.isNotEmpty() && RosterAlerts.isBaseline(since, previousSyncAt)) RosterNotifier.notify(context, kind, eventId, name, people)
        }
        post(RosterNotifier.Kind.Signups, s.signupNotifications, s.signupNotificationsSince, changes.signups)
        post(RosterNotifier.Kind.Withdrawals, s.withdrawalNotifications, s.withdrawalNotificationsSince, changes.withdrawals)
    }

    private suspend fun onTravel(context: Context, c: AppContainer, eventId: String, calendar: TravelCalendar) {
        if (!c.settings.current().arrivalNotifications || c.auth.state.value !is AuthState.SignedIn) return
        val event = c.events.resolveSelected()?.takeIf { it.id == eventId && it.travelEnabled } ?: return
        // Signed out or turned off while that resolved: nothing may be scheduled after the wipe.
        if (!c.settings.current().arrivalNotifications || c.auth.state.value !is AuthState.SignedIn) return
        ArrivalReminders.reschedule(context, eventId, event.name, calendar)
    }

    private fun eventName(c: AppContainer, eventId: String) = c.events.events.value?.firstOrNull { it.id == eventId }?.name

    private fun schedule(context: Context, on: Boolean) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!on) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<NotificationWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** One check: a roster call and a travel call at most, skipping a roster the app synced moments ago. */
class NotificationWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        if (!s.anyEventAlerts || c.auth.state.value !is AuthState.SignedIn) return Result.success()
        val event: Event = c.events.resolveSelected() ?: c.events.refresh().getOrNull()?.let { EventRepository.suggestEvent(it) }
            ?: return Result.success()
        if ((s.signupNotifications || s.withdrawalNotifications) && EventPermissions.canViewParticipants(event)) {
            val last = Time.parse(c.participants.load(event.id)?.lastSyncAt)
            if (last == null || Duration.between(last, Instant.now()) >= MIN_ROSTER_AGE) {
                runCatching { c.participants.sync(event.id) }.onFailure { Log.i(TAG, "roster sync failed: ${it.message}") }
            }
        }
        if (s.arrivalNotifications && event.travelEnabled) {
            c.travel.refresh(event.id).onFailure { Log.i(TAG, "travel refresh failed: ${it.message}") }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "NotificationWatch"
        /** Don't re-sync a roster the app (or a widget) synced moments ago. */
        private val MIN_ROSTER_AGE: Duration = Duration.ofSeconds(45)
    }
}
