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
        scans.clear()
        participants.clear() // also clears the encrypted file cache
        events.clear()
        tickets.clear()
        travel.clear()
        settings.clearAccountData()
    }
}
