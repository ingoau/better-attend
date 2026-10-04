package au.ingo.betterattend

import android.content.Context
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.auth.AuthRepository
import au.ingo.betterattend.data.auth.SecureTokenStore
import au.ingo.betterattend.data.repo.EventRepository
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.ScanRepository
import au.ingo.betterattend.data.repo.TicketRepository
import au.ingo.betterattend.data.repo.TravelRepository
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import au.ingo.betterattend.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import au.ingo.betterattend.data.auth.AuthState

/** Hand-rolled dependency container; one per process, reachable via [AttendApp.container]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** False when the Keystore key is unavailable: nothing sensitive is written to disk, the app runs online only. */
    val secureStorage: Boolean = SecureBox.check()

    val settings = SettingsStore(appContext)
    val cache = JsonCache(appContext)
    val tokenStore = SecureTokenStore(appContext)

    val api: AttendApi = AttendApi(tokens = tokenStore, onSessionExpired = { auth.sessionExpired() })
    val auth: AuthRepository = AuthRepository(tokenStore) { api }

    val events = EventRepository(api, cache, settings, scope)
    val participants = ParticipantRepository(api, cache)
    val scans = ScanRepository(api, cache, participants, scope)
    val tickets = TicketRepository(api, cache, scope)
    val travel = TravelRepository(api, cache)

    init {
        scans.fallbackContext = { eventId ->
            events.loadContexts(eventId)
            events.cachedContexts(eventId)?.let { list -> (list.firstOrNull { it.checksIn } ?: list.firstOrNull())?.id }
        }
        // Offline "wrong event" check: the code may be on another of this user's events.
        scans.otherRosters = { eventId ->
            events.events.value.orEmpty().filter { it.id != eventId }
                .mapNotNull { e -> participants.load(e.id)?.let { e.name to it } }
                .toMap()
        }
        // Whenever the session ends (sign-out or expiry), wipe cached account data.
        scope.launch {
            var wasSignedIn = false
            auth.state.collect { state ->
                if (state is AuthState.SignedIn) wasSignedIn = true
                else if (state is AuthState.SignedOut && wasSignedIn) { wasSignedIn = false; clearAccountData() }
            }
        }
    }

    /** Wipes every cached byte of account data (sign-out / session expiry). */
    suspend fun clearAccountData() {
        // Each step independently: a failing disk shouldn't leave the rest of the data behind.
        runCatching { scans.clear() }
        runCatching { participants.clear() } // also clears the encrypted file cache
        runCatching { events.clear() }
        runCatching { tickets.clear() }
        runCatching { travel.clear() }
        runCatching { settings.clearAccountData() }
        // Participant headshots (minors) live in Coil's caches.
        runCatching {
            val loader = coil3.SingletonImageLoader.get(appContext)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }
}
