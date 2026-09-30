package au.ingo.betterattend.data.repo

import au.ingo.betterattend.util.resultOf
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SettingsStore
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

class EventRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    private val _events = MutableStateFlow<List<Event>?>(null)
    /** null until the cache has been read. */
    val events: StateFlow<List<Event>?> = _events.asStateFlow()

    private val _contexts = MutableStateFlow<Map<String, List<ScanContext>>>(emptyMap())
    val contexts: StateFlow<Map<String, List<ScanContext>>> = _contexts.asStateFlow()

    /** The event the organizer is working on: the saved choice, else whatever is live or next. */
    val selectedEvent: StateFlow<Event?> = combine(_events, settings.settings.map { it.selectedEventId }) { events, id ->
        events?.let { list -> list.firstOrNull { it.id == id } ?: suggestEvent(list) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            _events.value = cache.read(KEY_EVENTS, ListSerializer(Event.serializer())) ?: _events.value
        }
    }

    suspend fun refresh(): Result<List<Event>> = resultOf {
        val list = api.events()
        _events.value = list
        cache.write(KEY_EVENTS, ListSerializer(Event.serializer()), list)
        list
    }

    suspend fun select(eventId: String) = settings.setSelectedEvent(eventId)

    /**
     * The selected event, waiting for the events cache and saved settings to load first. Use this
     * from background work (widgets, workers): [selectedEvent] starts as null in a fresh process and
     * would otherwise fall back to a different event than the one the user picked.
     */
    suspend fun resolveSelected(timeoutMs: Long = 3_000): Event? {
        val list = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { events.filterNotNull().first() } ?: return null
        val id = settings.current().selectedEventId
        return list.firstOrNull { it.id == id } ?: suggestEvent(list)
    }

    fun cachedContexts(eventId: String): List<ScanContext>? = _contexts.value[eventId]

    suspend fun loadContexts(eventId: String) {
        if (_contexts.value[eventId] == null) {
            cache.read("contexts_$eventId", ListSerializer(ScanContext.serializer()))?.let { cached ->
                _contexts.update { it + (eventId to cached) }
            }
        }
    }

    suspend fun refreshContexts(eventId: String): Result<List<ScanContext>> = resultOf {
        val list = api.scanContexts(eventId)
        _contexts.update { it + (eventId to list) }
        cache.write("contexts_$eventId", ListSerializer(ScanContext.serializer()), list)
        list
    }

    suspend fun clear() {
        _events.value = emptyList()
        _contexts.value = emptyMap()
    }

    companion object {
        private const val KEY_EVENTS = "events"

        /** Live event first, then the next upcoming one, else the most recent. */
        fun suggestEvent(events: List<Event>, now: Instant = Instant.now()): Event? {
            if (events.isEmpty()) return null
            events.firstOrNull { Time.phase(it.startsAt, it.endsAt, now) == Time.Phase.Live }?.let { return it }
            events.filter { Time.phase(it.startsAt, it.endsAt, now) == Time.Phase.Upcoming }
                .minByOrNull { Time.parse(it.startsAt) ?: Instant.MAX }?.let { return it }
            return events.maxByOrNull { Time.parse(it.startsAt) ?: Instant.MIN }
        }

        /** Picks the context whose window contains now, else the first check-in context, else the first. */
        fun defaultContext(contexts: List<ScanContext>, now: Instant = Instant.now()): ScanContext? =
            contexts.firstOrNull { c ->
                val s = Time.parse(c.startsAt); val e = Time.parse(c.endsAt)
                s != null && e != null && !now.isBefore(s) && !now.isAfter(e)
            } ?: contexts.firstOrNull { it.checksIn } ?: contexts.firstOrNull()
    }
}
