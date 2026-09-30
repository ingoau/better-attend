package au.ingo.betterattend.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.reflect.KClass

/** The widget types we ship, with their receivers. */
enum class WidgetKind(val receiver: KClass<out GlanceAppWidgetReceiver>) {
    CheckIn(CheckInWidgetReceiver::class),
    Arrivals(ArrivalsWidgetReceiver::class),
    QuickScan(QuickScanWidgetReceiver::class),
    Ticket(TicketWidgetReceiver::class);

    companion object {
        /** Which widget types currently have at least one instance on a home screen. */
        fun placed(context: Context): Set<WidgetKind> {
            val mgr = AppWidgetManager.getInstance(context) ?: return emptySet()
            return entries.filterTo(mutableSetOf()) { k ->
                runCatching { mgr.getAppWidgetIds(ComponentName(context, k.receiver.java)).isNotEmpty() }.getOrDefault(false)
            }
        }
    }
}

/**
 * Keeps the widget snapshot in sync with the app's repositories and pushes it to the widgets.
 * Rendering reads [snapshot]; nothing here hits the network.
 */
object WidgetUpdater {
    private const val TAG = "WidgetUpdater"
    private const val KEY = "widget_snapshot"

    private val _snapshot = MutableStateFlow<WidgetSnapshot?>(null)
    val snapshot: StateFlow<WidgetSnapshot?> = _snapshot.asStateFlow()
    private val publishLock = Mutex()

    /** Called from AttendApp.onCreate: watch repos and republish (debounced) whenever they change. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        container.scope.launch {
            WidgetSync.ensureScheduled(app)
            WidgetPreviews.publishOnce(app)
            combine(
                container.auth.state,
                container.events.selectedEvent,
                container.participants.rosters,
                container.events.contexts,
                container.travel.calendars,
                container.tickets.tickets,
            ) { _ -> System.nanoTime() } // every change is distinct; debounce collapses bursts
                .debounce(2_000)
                .collect {
                    if (WidgetKind.placed(app).isNotEmpty()) runCatching { publish(app, container) }.onFailure { Log.w(TAG, "publish failed", it) }
                }
        }
    }

    /** The current snapshot: memory, else the encrypted cache, else built from cached repo data. */
    suspend fun current(context: Context): WidgetSnapshot {
        _snapshot.value?.let { return it }
        val container = containerOrNull(context) ?: return WidgetSnapshot.SignedOut
        container.cache.read(KEY, WidgetSnapshot.serializer())?.let { cached ->
            // A snapshot outliving the session (e.g. process died mid sign-out) must not leak data.
            if (container.auth.state.value is AuthState.SignedIn) { _snapshot.compareAndSet(null, cached); return _snapshot.value ?: cached }
        }
        return runCatching { buildFromRepos(container) }.getOrDefault(WidgetSnapshot.SignedOut).also { _snapshot.compareAndSet(null, it) }
    }

    /**
     * Rebuilds the snapshot from the repositories (loading their disk caches as needed) and, if it
     * changed or [force] is set, saves it and re-renders every widget.
     */
    suspend fun publish(context: Context, container: AppContainer, force: Boolean = false) = publishLock.withLock {
        val snap = buildFromRepos(container)
        val changed = !snap.sameContentAs(_snapshot.value)
        _snapshot.value = snap
        if (changed) {
            if (snap.signedIn) container.cache.write(KEY, WidgetSnapshot.serializer(), snap) else container.cache.remove(KEY)
        }
        if (changed || force) updateAllWidgets(context)
    }

    suspend fun updateAllWidgets(context: Context) {
        runCatching { CheckInWidget().updateAll(context) }
        runCatching { ArrivalsWidget().updateAll(context) }
        runCatching { QuickScanWidget().updateAll(context) }
        runCatching { TicketWidget().updateAll(context) }
    }

    private suspend fun buildFromRepos(container: AppContainer): WidgetSnapshot {
        val user = (container.auth.state.value as? AuthState.SignedIn)?.user ?: return WidgetSnapshot.SignedOut
        // Only organizers need to wait for the events cache to load; participants may never have one.
        val events = if (user.isOrganizer || user.globalAdmin) withTimeoutOrNull(3_000) { container.events.events.filterNotNull().first() }
        else container.events.events.value
        val isOrganizer = user.isOrganizer || user.globalAdmin || !events.isNullOrEmpty()
        val event: Event? = if (isOrganizer) container.events.resolveSelected() else null
        if (event != null) {
            if (event.canViewParticipants) container.participants.load(event.id)
            container.events.loadContexts(event.id)
            if (event.travelEnabled) container.travel.load(event.id)
        }
        val tickets = withTimeoutOrNull(2_000) { container.tickets.tickets.filterNotNull().first() }
        return WidgetSnapshots.build(
            user = user,
            event = event,
            roster = event?.let { container.participants.roster(it.id) },
            contexts = event?.let { container.events.cachedContexts(it.id) },
            travel = event?.let { container.travel.calendars.value[it.id] },
            tickets = tickets,
            isOrganizer = isOrganizer,
        )
    }

    private fun containerOrNull(context: Context): AppContainer? =
        runCatching { (context.applicationContext as au.ingo.betterattend.AttendApp).container }.getOrNull()
}
