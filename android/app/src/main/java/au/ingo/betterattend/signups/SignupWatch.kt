package au.ingo.betterattend.signups

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
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Background checks for new signups while the setting is on: a 15-minute roster delta sync of the
 * selected event. The sync itself spots the signups and raises the notification (see [AttendApp]).
 */
object SignupWatch {
    private const val PERIODIC = "signup_watch_periodic"

    /** Keeps the periodic check scheduled exactly while the setting is on and someone is signed in. */
    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        container.scope.launch {
            combine(container.settings.settings, container.auth.state) { s, auth -> s.signupNotifications && auth is AuthState.SignedIn }
                .distinctUntilChanged()
                .collect { on -> schedule(app, on) }
        }
    }

    private fun schedule(context: Context, on: Boolean) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!on) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SignupWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** One check: a single roster call at most, skipped if the app (or a widget) synced moments ago. */
class SignupWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.current().signupNotifications) return Result.success()
        if (c.auth.state.value !is AuthState.SignedIn) return Result.success()
        val event = c.events.resolveSelected() ?: return Result.success()
        if (!EventPermissions.canViewParticipants(event)) return Result.success()
        val last = Time.parse(c.participants.load(event.id)?.lastSyncAt)
        if (last != null && Duration.between(last, Instant.now()) < MIN_ROSTER_AGE) return Result.success()
        runCatching { c.participants.sync(event.id) }.onFailure { Log.i(TAG, "roster sync failed: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "SignupWatch"
        private val MIN_ROSTER_AGE: Duration = Duration.ofSeconds(45)
    }
}
