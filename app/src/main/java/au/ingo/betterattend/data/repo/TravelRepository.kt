package au.ingo.betterattend.data.repo

import au.ingo.betterattend.util.resultOf
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.store.JsonCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TravelRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
) {
    private val _calendars = MutableStateFlow<Map<String, TravelCalendar>>(emptyMap())
    val calendars: StateFlow<Map<String, TravelCalendar>> = _calendars.asStateFlow()

    suspend fun load(eventId: String): TravelCalendar? {
        _calendars.value[eventId]?.let { return it }
        return cache.read(key(eventId), TravelCalendar.serializer())?.also { c -> _calendars.update { it + (eventId to c) } }
    }

    suspend fun refresh(eventId: String): Result<TravelCalendar> = resultOf {
        val cal = api.travel(eventId)
        _calendars.update { it + (eventId to cal) }
        cache.write(key(eventId), TravelCalendar.serializer(), cal)
        cal
    }

    fun clear() { _calendars.value = emptyMap() }

    private fun key(eventId: String) = "travel_$eventId"
}
