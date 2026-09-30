package au.ingo.betterattend.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.repo.EventRepository
import au.ingo.betterattend.container
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Scheduling for [WidgetSyncWorker]. */
object WidgetSync {
    private const val PERIODIC = "widget_sync_periodic"
    private const val ONE_OFF = "widget_sync_now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic 15-minute sync while any widget is placed; cancelled once the last one is removed. */
    fun ensureScheduled(context: Context) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (WidgetKind.placed(context).isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One refresh as soon as there's a network. Repeated taps while one is pending are ignored. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>().setConstraints(network).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, request) }
    }

    /** A widget was added: render from cache right away, then sync. */
    fun onWidgetAdded(context: Context) {
        ensureScheduled(context)
        syncNow(context)
    }
}

/**
 * Background refresh for the widgets. Budget-conscious: the server allows 300 requests per
 * 5 minutes per IP (shared by everyone on the venue Wi-Fi), so each run makes at most a few calls
 * and skips anything refreshed very recently.
 */
class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val placed = WidgetKind.placed(ctx)
        if (placed.isEmpty()) {
            WidgetSync.ensureScheduled(ctx)
            return Result.success()
        }
        val c = ctx.container
        val user = (c.auth.state.value as? AuthState.SignedIn)?.user
        if (user != null) {
            val wantsOrganizer = WidgetKind.CheckIn in placed || WidgetKind.Arrivals in placed
            if (wantsOrganizer && (user.isOrganizer || user.globalAdmin)) syncOrganizer(placed)
            if (WidgetKind.Ticket in placed && user.isParticipant) {
                c.tickets.refresh().onFailure { Log.i(TAG, "tickets refresh failed: ${it.message}") }
            }
        }
        WidgetUpdater.publish(ctx, c, force = true) // re-render even if unchanged so relative times stay fresh
        return Result.success()
    }

    private suspend fun syncOrganizer(placed: Set<WidgetKind>) {
        val c = applicationContext.container
        var events = withTimeoutOrNull(3_000) { c.events.events.filterNotNull().first() }
        if (events.isNullOrEmpty()) events = c.events.refresh().getOrNull() // first run on a fresh install: 1 call
        val event = c.events.resolveSelected() ?: events?.let { EventRepository.suggestEvent(it) } ?: return

        if (WidgetKind.CheckIn in placed && event.canViewParticipants) {
            val roster = c.participants.load(event.id)
            val last = Time.parse(roster?.lastSyncAt)
            if (last == null || Duration.between(last, Instant.now()) > MIN_ROSTER_AGE) {
                runCatching { c.participants.sync(event.id) }.onFailure { Log.i(TAG, "roster sync failed: ${it.message}") }
            }
            c.events.loadContexts(event.id)
            if (c.events.cachedContexts(event.id) == null) c.events.refreshContexts(event.id)
        }
        if (WidgetKind.Arrivals in placed && event.travelEnabled) {
            c.travel.refresh(event.id).onFailure { Log.i(TAG, "travel refresh failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "WidgetSync"
        /** Don't re-sync a roster the app (or another widget tap) synced moments ago. */
        private val MIN_ROSTER_AGE: Duration = Duration.ofSeconds(45)
    }
}

/** The small refresh button on organizer widgets. */
class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetSync.syncNow(context)
    }
}

/** Generated widget-picker previews (Android 15+). Rate-limited by the system, so publish once per app version. */
object WidgetPreviews {
    private const val PREFS = "widget_previews"
    private const val VERSION = 1

    suspend fun publishOnce(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) >= VERSION) return
        val mgr = GlanceAppWidgetManager(context)
        val results = WidgetKind.entries.map { k -> runCatching { mgr.setWidgetPreviews(k.receiver) }.getOrDefault(-1) }
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
            prefs.edit().putInt("version", VERSION).apply()
        }
    }
}
