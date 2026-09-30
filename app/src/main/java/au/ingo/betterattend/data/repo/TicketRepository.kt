package au.ingo.betterattend.data.repo

import au.ingo.betterattend.util.resultOf
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.model.Ticket
import au.ingo.betterattend.data.store.JsonCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** The signed-in user's own tickets, cached so the QR code works with no signal at the door. */
class TicketRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
    scope: CoroutineScope,
) {
    private val _tickets = MutableStateFlow<List<Ticket>?>(null)
    val tickets: StateFlow<List<Ticket>?> = _tickets.asStateFlow()

    init {
        scope.launch {
            if (_tickets.value == null) _tickets.value = cache.read(KEY, ListSerializer(Ticket.serializer()))
        }
    }

    suspend fun refresh(): Result<List<Ticket>> = resultOf {
        val fresh = api.tickets()
        // Keep detail extras (messages, travel) from earlier detail fetches.
        val old = _tickets.value.orEmpty().associateBy { it.id }
        val merged = fresh.map { t ->
            old[t.id]?.let { o -> t.copy(messages = t.messages.ifEmpty { o.messages }, travelInbound = t.travelInbound ?: o.travelInbound) } ?: t
        }
        _tickets.value = merged
        cache.write(KEY, ListSerializer(Ticket.serializer()), merged)
        merged
    }

    suspend fun refreshTicket(id: String): Result<Ticket> = resultOf {
        val t = api.ticket(id)
        val list = _tickets.value.orEmpty()
        val updated = if (list.any { it.id == id }) list.map { if (it.id == id) t else it } else list + t
        _tickets.value = updated
        cache.write(KEY, ListSerializer(Ticket.serializer()), updated)
        t
    }

    suspend fun googleWalletUrl(id: String): Result<String> = resultOf { api.googleWalletUrl(id) }

    fun clear() { _tickets.value = emptyList() }

    companion object { private const val KEY = "tickets" }
}
